package plugin

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	stdpath "path"
	"sort"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
)

const (
	// MaxArchiveBytes caps the uploaded plugin ZIP archive size at 16 MiB.
	MaxArchiveBytes = 16 << 20
	// MaxArchivePlugins caps the number of .js plugin entries processed in a single ZIP archive.
	MaxArchivePlugins = 100
	// MaxArchiveUncompressedBytes caps cumulative decompressed bytes read from a single ZIP archive at 32 MiB.
	MaxArchiveUncompressedBytes = 32 << 20
)

// InstallArchiveRequest defines inputs for batch-installing JS provider plugins from a ZIP archive.
type InstallArchiveRequest struct {
	ArchiveBytes   []byte   `json:"-"`
	Enabled        *bool    `json:"enabled"`
	SelectedIDs    []string `json:"selected_ids"`
	AllowedOrigins []string `json:"allowed_origins"`
}

type archiveCandidate struct {
	entryPath string
	file      *zip.File
}

type archivePluginEntry struct {
	EntryPath string
	Code      string
	Oversized bool
}

type evaluatedArchiveEntry struct {
	EntryPath string
	Code      string
	Inspect   model.PluginArchiveEntryInspectDTO
}

// InspectArchive extracts and statically validates all .js plugin scripts inside a ZIP archive
// without persisting files or mutating the active provider registry.
func (m *Manager) InspectArchive(ctx context.Context, archiveBytes []byte) (model.PluginArchiveInspectResultDTO, error) {
	evaluated, err := m.evaluateArchiveEntries(ctx, archiveBytes)
	if err != nil {
		return model.PluginArchiveInspectResultDTO{}, err
	}

	res := model.PluginArchiveInspectResultDTO{
		Total:   len(evaluated),
		Entries: make([]model.PluginArchiveEntryInspectDTO, 0, len(evaluated)),
	}
	for _, ev := range evaluated {
		if ev.Inspect.OK {
			res.ValidCount++
		} else {
			res.InvalidCount++
		}
		res.Entries = append(res.Entries, ev.Inspect)
	}
	return res, nil
}

// InstallArchive extracts, validates, and batch-installs .js plugins from a ZIP archive.
// When req.SelectedIDs is non-empty, only valid entries whose manifest.ID is in req.SelectedIDs are installed;
// otherwise all discovered .js entries are processed and invalid entries are recorded as failed items.
func (m *Manager) InstallArchive(ctx context.Context, req InstallArchiveRequest) (model.PluginArchiveInstallResultDTO, error) {
	if m.store == nil {
		return model.PluginArchiveInstallResultDTO{}, model.NewError(model.ErrInternal, http.StatusInternalServerError, "插件存储未初始化")
	}

	allowedOrigins, err := ValidateAndNormalizeOrigins(req.AllowedOrigins)
	if err != nil {
		return model.PluginArchiveInstallResultDTO{}, err
	}

	evaluated, err := m.evaluateArchiveEntries(ctx, req.ArchiveBytes)
	if err != nil {
		return model.PluginArchiveInstallResultDTO{}, err
	}

	selectedSet := make(map[string]bool, len(req.SelectedIDs))
	for _, rawID := range req.SelectedIDs {
		if id := strings.TrimSpace(rawID); id != "" {
			selectedSet[id] = true
		}
	}
	hasFilter := len(selectedSet) > 0

	res := model.PluginArchiveInstallResultDTO{
		Installed: make([]model.ProviderPluginDTO, 0),
		Results:   make([]model.PluginArchiveInstallItemDTO, 0),
	}

	for _, ev := range evaluated {
		if ctx != nil {
			if err := ctx.Err(); err != nil {
				return res, model.WrapError(model.ErrCancelled, 499, "插件压缩包安装已取消", err)
			}
		}

		if hasFilter {
			if !ev.Inspect.OK || ev.Inspect.Manifest == nil || !selectedSet[ev.Inspect.Manifest.ID] {
				continue
			}
		}

		res.Total++
		itemID := ""
		if ev.Inspect.Manifest != nil {
			itemID = ev.Inspect.Manifest.ID
		}

		if !ev.Inspect.OK {
			res.FailedCount++
			res.Results = append(res.Results, model.PluginArchiveInstallItemDTO{
				EntryPath: ev.EntryPath,
				ID:        itemID,
				OK:        false,
				Error:     ev.Inspect.Error,
			})
			continue
		}

		dto, installErr := m.Install(ctx, InstallRequest{
			Code:           ev.Code,
			Enabled:        req.Enabled,
			AllowedOrigins: allowedOrigins,
		})
		if installErr != nil {
			res.FailedCount++
			res.Results = append(res.Results, model.PluginArchiveInstallItemDTO{
				EntryPath: ev.EntryPath,
				ID:        itemID,
				OK:        false,
				Error:     archiveEntryErrorMessage(installErr),
			})
			continue
		}

		dtoCopy := dto
		res.InstalledCount++
		res.Installed = append(res.Installed, dtoCopy)
		res.Results = append(res.Results, model.PluginArchiveInstallItemDTO{
			EntryPath: ev.EntryPath,
			ID:        dtoCopy.ID,
			OK:        true,
			Plugin:    &dtoCopy,
		})
	}

	return res, nil
}

func (m *Manager) evaluateArchiveEntries(ctx context.Context, archiveBytes []byte) ([]evaluatedArchiveEntry, error) {
	rawEntries, err := extractArchivePlugins(ctx, archiveBytes)
	if err != nil {
		return nil, err
	}

	evaluated := make([]evaluatedArchiveEntry, 0, len(rawEntries))
	seenIDs := make(map[string]string, len(rawEntries))

	for _, raw := range rawEntries {
		if ctx != nil {
			if err := ctx.Err(); err != nil {
				return nil, model.WrapError(model.ErrCancelled, 499, "插件压缩包检查已取消", err)
			}
		}

		if raw.Oversized {
			evaluated = append(evaluated, evaluatedArchiveEntry{
				EntryPath: raw.EntryPath,
				Inspect: model.PluginArchiveEntryInspectDTO{
					EntryPath: raw.EntryPath,
					OK:        false,
					Error:     "插件源码体积超过 512 KiB 上限",
				},
			})
			continue
		}

		insp, err := Inspect(ctx, raw.Code)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				if ctx != nil && ctx.Err() != nil {
					return nil, err
				}
			}
			evaluated = append(evaluated, evaluatedArchiveEntry{
				EntryPath: raw.EntryPath,
				Code:      raw.Code,
				Inspect: model.PluginArchiveEntryInspectDTO{
					EntryPath: raw.EntryPath,
					OK:        false,
					Error:     archiveEntryErrorMessage(err),
				},
			})
			continue
		}

		manifestCopy := insp.Manifest
		id := manifestCopy.ID

		if m != nil && m.isBuiltinConflict(id) {
			evaluated = append(evaluated, evaluatedArchiveEntry{
				EntryPath: raw.EntryPath,
				Code:      raw.Code,
				Inspect: model.PluginArchiveEntryInspectDTO{
					EntryPath:   raw.EntryPath,
					OK:          false,
					Error:       fmt.Sprintf("插件 ID %q 与内置音源冲突", id),
					SHA256:      insp.SHA256,
					Manifest:    &manifestCopy,
					Permissions: insp.Permissions,
					Warnings:    insp.Warnings,
				},
			})
			continue
		}

		if prevPath, dup := seenIDs[id]; dup {
			evaluated = append(evaluated, evaluatedArchiveEntry{
				EntryPath: raw.EntryPath,
				Code:      raw.Code,
				Inspect: model.PluginArchiveEntryInspectDTO{
					EntryPath:   raw.EntryPath,
					OK:          false,
					Error:       fmt.Sprintf("压缩包中存在重复插件 ID: %s（已存在于 %s）", id, prevPath),
					SHA256:      insp.SHA256,
					Manifest:    &manifestCopy,
					Permissions: insp.Permissions,
					Warnings:    insp.Warnings,
				},
			})
			continue
		}
		seenIDs[id] = raw.EntryPath

		evaluated = append(evaluated, evaluatedArchiveEntry{
			EntryPath: raw.EntryPath,
			Code:      raw.Code,
			Inspect: model.PluginArchiveEntryInspectDTO{
				EntryPath:   raw.EntryPath,
				OK:          true,
				SHA256:      insp.SHA256,
				Manifest:    &manifestCopy,
				Permissions: insp.Permissions,
				Warnings:    insp.Warnings,
			},
		})
	}

	return evaluated, nil
}

func extractArchivePlugins(ctx context.Context, archiveBytes []byte) ([]archivePluginEntry, error) {
	if ctx != nil {
		if err := ctx.Err(); err != nil {
			return nil, model.WrapError(model.ErrCancelled, 499, "插件压缩包读取已取消", err)
		}
	}
	if len(archiveBytes) == 0 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件压缩包不能为空")
	}
	if len(archiveBytes) > MaxArchiveBytes {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件压缩包体积超过 16 MiB 上限")
	}

	zr, err := zip.NewReader(bytes.NewReader(archiveBytes), int64(len(archiveBytes)))
	if err != nil {
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的 ZIP 压缩包文件", err)
	}

	candidates := make([]archiveCandidate, 0)
	for _, f := range zr.File {
		if ctx != nil {
			if err := ctx.Err(); err != nil {
				return nil, model.WrapError(model.ErrCancelled, 499, "插件压缩包读取已取消", err)
			}
		}

		cleanPath, skip, pathErr := cleanArchiveEntryPath(f.Name, f.FileInfo().IsDir(), f.Mode())
		if pathErr != nil {
			return nil, pathErr
		}
		if skip {
			continue
		}

		candidates = append(candidates, archiveCandidate{
			entryPath: cleanPath,
			file:      f,
		})
		if len(candidates) > MaxArchivePlugins {
			return nil, model.NewError(
				model.ErrInvalidInput,
				http.StatusBadRequest,
				fmt.Sprintf("压缩包内插件数量超过 %d 个上限", MaxArchivePlugins),
			)
		}
	}

	if len(candidates) == 0 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "压缩包中未找到任何 .js 插件文件")
	}

	sort.SliceStable(candidates, func(i, j int) bool {
		return candidates[i].entryPath < candidates[j].entryPath
	})

	entries := make([]archivePluginEntry, 0, len(candidates))
	var totalRead int64

	for _, cand := range candidates {
		if ctx != nil {
			if err := ctx.Err(); err != nil {
				return nil, model.WrapError(model.ErrCancelled, 499, "插件压缩包读取已取消", err)
			}
		}

		if cand.file.UncompressedSize64 > MaxArchiveUncompressedBytes {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "压缩包累计解压体积超过 32 MiB 上限")
		}

		remaining := int64(MaxArchiveUncompressedBytes) - totalRead
		readLimit := int64(MaxSourceBytes) + 1
		if remaining+1 < readLimit {
			readLimit = remaining + 1
		}

		data, readErr := readZipEntryBounded(cand.file, readLimit)
		if readErr != nil {
			return nil, model.WrapError(
				model.ErrInvalidInput,
				http.StatusBadRequest,
				fmt.Sprintf("读取压缩包条目 %s 失败", cand.entryPath),
				readErr,
			)
		}

		totalRead += int64(len(data))
		if totalRead > MaxArchiveUncompressedBytes {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "压缩包累计解压体积超过 32 MiB 上限")
		}

		if len(data) > MaxSourceBytes {
			entries = append(entries, archivePluginEntry{
				EntryPath: cand.entryPath,
				Oversized: true,
			})
			continue
		}

		entries = append(entries, archivePluginEntry{
			EntryPath: cand.entryPath,
			Code:      string(data),
		})
	}

	return entries, nil
}

func readZipEntryBounded(f *zip.File, limit int64) ([]byte, error) {
	rc, err := f.Open()
	if err != nil {
		return nil, err
	}
	defer rc.Close()
	return io.ReadAll(io.LimitReader(rc, limit))
}

func cleanArchiveEntryPath(rawName string, isDir bool, mode fs.FileMode) (string, bool, error) {
	if strings.ContainsRune(rawName, 0) {
		return "", false, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "压缩包包含非法空字符路径")
	}

	norm := strings.ReplaceAll(rawName, "\\", "/")
	if norm == "" {
		return "", true, nil
	}

	if strings.HasPrefix(norm, "/") {
		return "", false, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("压缩包包含非法绝对路径: %s", rawName))
	}
	if len(norm) >= 2 && ((norm[0] >= 'a' && norm[0] <= 'z') || (norm[0] >= 'A' && norm[0] <= 'Z')) && norm[1] == ':' {
		return "", false, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("压缩包包含非法绝对路径: %s", rawName))
	}

	parts := strings.Split(norm, "/")
	cleanedParts := make([]string, 0, len(parts))
	for _, seg := range parts {
		if seg == "" || seg == "." {
			continue
		}
		if seg == ".." {
			return "", false, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("压缩包包含非法路径穿越条目: %s", rawName))
		}
		cleanedParts = append(cleanedParts, seg)
	}

	if isDir || strings.HasSuffix(norm, "/") || len(cleanedParts) == 0 {
		return "", true, nil
	}
	if mode&os.ModeSymlink != 0 {
		return "", true, nil
	}

	for _, seg := range cleanedParts {
		if seg == "__MACOSX" || strings.HasPrefix(seg, ".") {
			return "", true, nil
		}
	}

	cleanPath := strings.Join(cleanedParts, "/")
	if !strings.EqualFold(stdpath.Ext(cleanPath), ".js") {
		return "", true, nil
	}
	return cleanPath, false, nil
}

func archiveEntryErrorMessage(err error) string {
	if err == nil {
		return ""
	}
	if de, ok := model.AsDomainError(err); ok && strings.TrimSpace(de.Detail) != "" {
		return de.Detail
	}
	return err.Error()
}
