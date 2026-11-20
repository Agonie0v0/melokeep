package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
)

type folderTargetPayload struct {
	Root   string `json:"root"`
	Folder string `json:"folder"`
}

func mergeLibraryFolders(indexed, disk []model.LibraryFolderDTO) []model.LibraryFolderDTO {
	seen := make(map[string]model.LibraryFolderDTO, len(indexed)+len(disk))
	indexedByRoot := make(map[string][]string)
	diskByRoot := make(map[string][]string)

	norm := func(item model.LibraryFolderDTO) (string, string) {
		root := filepath.Clean(strings.TrimSpace(item.Root))
		folder := strings.Trim(strings.TrimSpace(filepath.ToSlash(item.Folder)), "/")
		if folder == "." {
			folder = ""
		}
		return root, folder
	}
	for _, item := range indexed {
		root, folder := norm(item)
		key := root + "\x00" + folder
		if _, ok := seen[key]; !ok {
			seen[key] = model.LibraryFolderDTO{Root: root, Folder: folder}
			indexedByRoot[root] = append(indexedByRoot[root], folder)
		}
	}
	for _, item := range disk {
		root, folder := norm(item)
		if folder != "" {
			diskByRoot[root] = append(diskByRoot[root], folder)
		}
	}
	// Only add leaf disk folders that aren't already covered by an indexed folder or deeper disk folder,
	// so parent-only directories aren't duplicated alongside their children.
	for root, folders := range diskByRoot {
		allKnown := append([]string(nil), indexedByRoot[root]...)
		allKnown = append(allKnown, folders...)
		for _, folder := range folders {
			key := root + "\x00" + folder
			if _, ok := seen[key]; ok {
				continue
			}
			prefix := folder + "/"
			hasDescendant := false
			for _, cand := range allKnown {
				if strings.HasPrefix(cand, prefix) {
					hasDescendant = true
					break
				}
			}
			if !hasDescendant {
				seen[key] = model.LibraryFolderDTO{Root: root, Folder: folder}
			}
		}
	}
	out := make([]model.LibraryFolderDTO, 0, len(seen))
	for _, item := range seen {
		out = append(out, item)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Root != out[j].Root {
			return out[i].Root < out[j].Root
		}
		return out[i].Folder < out[j].Folder
	})
	return out
}

func (s *Server) cleanupDeletedAssetRefs(ctx context.Context, assetIDs []string) []string {
	if len(assetIDs) == 0 {
		return []string{}
	}
	idSet := make(map[string]bool, len(assetIDs)*2)
	trackIDs := make([]string, 0, len(assetIDs)*2)
	for _, assetID := range assetIDs {
		assetID = strings.TrimSpace(assetID)
		if assetID == "" {
			continue
		}
		for _, tid := range []string{"local-" + assetID, "asset:" + assetID} {
			if !idSet[tid] {
				idSet[tid] = true
				trackIDs = append(trackIDs, tid)
			}
			if s.deps.Store != nil {
				_ = s.deps.Store.DeleteDurableRecord(ctx, durableNamespaceFavorite, tid)
				_ = s.deps.Store.DeleteDurableRecord(ctx, "track_dto", tid)
			}
		}
	}
	if s.deps.Store != nil && len(idSet) > 0 {
		if rec, ok, err := s.deps.Store.GetDurableRecord(ctx, durableNamespacePlaylist, mainPlaylistID); err == nil && ok {
			var list []model.TrackDTO
			if json.Unmarshal([]byte(rec.BodyJSON), &list) == nil {
				filtered := make([]model.TrackDTO, 0, len(list))
				changed := false
				for _, item := range list {
					if idSet[item.ID] {
						changed = true
						continue
					}
					filtered = append(filtered, item)
				}
				if changed {
					raw, _ := json.Marshal(filtered)
					_, _ = s.deps.Store.PutDurableRecord(ctx, durableNamespacePlaylist, mainPlaylistID, string(raw), rec.Revision)
				}
			}
		}
	}
	if s.deps.Store != nil && len(trackIDs) > 0 {
		_ = s.deps.Store.RemoveCollectionRefs(ctx, trackIDs)
	}
	return trackIDs
}

func (s *Server) acquireFolderAssetLocks(ctx context.Context, rootID, caseMode, folder string) (func(), error) {
	if s.deps.Store == nil || s.deps.Files == nil {
		return func() {}, nil
	}
	assets, err := s.deps.Store.ListAssetsByFolderPrefix(ctx, rootID, caseMode, folder)
	if err != nil {
		return nil, err
	}
	ids := make([]string, 0, len(assets))
	for _, asset := range assets {
		if asset.ID != "" {
			ids = append(ids, asset.ID)
		}
	}
	return s.deps.Files.Locks().AcquireWriteMulti(ctx, ids)
}

func (s *Server) handleMakeLibraryFolder(w http.ResponseWriter, r *http.Request) {
	if s.deps.Files == nil {
		writeDomainError(w, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件服务未初始化"))
		return
	}
	var body struct {
		Root   string `json:"root"`
		Parent string `json:"parent"`
		Name   string `json:"name"`
	}
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	cleanName, err := files.ValidateFolderSegment(body.Name)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	parent := strings.Trim(strings.TrimSpace(filepath.ToSlash(body.Parent)), "/")
	targetFolder := cleanName
	if parent != "" && parent != "." {
		targetFolder = parent + "/" + cleanName
	}
	vp, err := files.ValidateFolderPath(s.deps.Files.Roots(), body.Root, targetFolder, false)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if _, stErr := os.Lstat(vp.AbsPath); stErr == nil {
		writeError(w, http.StatusConflict, "同名文件夹已存在")
		return
	} else if !errors.Is(stErr, os.ErrNotExist) {
		writeDomainError(w, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "检查目录状态失败", stErr))
		return
	}
	if err := os.MkdirAll(vp.AbsPath, 0o755); err != nil {
		writeDomainError(w, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "创建文件夹失败", err))
		return
	}
	s.pushLog("info", "已新建文件夹："+vp.RelPath, "library", "")
	writeJSON(w, http.StatusOK, model.LibraryFolderDTO{
		Root:   vp.Root.CanonicalPath,
		Folder: vp.RelPath,
	})
}

func (s *Server) handleRenameLibraryFolder(w http.ResponseWriter, r *http.Request) {
	if s.deps.Files == nil {
		writeDomainError(w, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件服务未初始化"))
		return
	}
	var body struct {
		Root    string `json:"root"`
		Folder  string `json:"folder"`
		NewName string `json:"new_name"`
	}
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	src, err := files.ValidateFolderPath(s.deps.Files.Roots(), body.Root, body.Folder, true)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	cleanName, err := files.ValidateFolderSegment(body.NewName)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	nextFolder := cleanName
	if src.Folder != "" {
		nextFolder = src.Folder + "/" + cleanName
	}
	if nextFolder == src.RelPath {
		writeJSON(w, http.StatusOK, model.LibraryFolderDTO{
			Root:   src.Root.CanonicalPath,
			Folder: src.RelPath,
		})
		return
	}
	dst, err := files.ValidateFolderPath(s.deps.Files.Roots(), src.Root.CanonicalPath, nextFolder, false)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	sameCaseSlot := dst.Root.ID == src.Root.ID && dst.PathKey == src.PathKey && dst.RelPath != src.RelPath
	if !sameCaseSlot {
		if _, stErr := os.Lstat(dst.AbsPath); stErr == nil {
			writeError(w, http.StatusConflict, "目标文件夹已存在")
			return
		} else if !errors.Is(stErr, os.ErrNotExist) {
			writeDomainError(w, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "检查目标目录失败", stErr))
			return
		}
	}
	if err := files.VerifySubtreeNoSymlinks(src.AbsPath); err != nil {
		writeDomainError(w, err)
		return
	}
	release, err := s.acquireFolderAssetLocks(r.Context(), src.Root.ID, src.Root.CaseMode, src.RelPath)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	defer release()

	if sameCaseSlot {
		tmpPath := src.AbsPath + ".melokeep-case-" + model.NewUUID()
		if err := os.Rename(src.AbsPath, tmpPath); err != nil {
			writeDomainError(w, model.WrapError(model.ErrConflict, http.StatusConflict, "重命名文件夹失败（可能被占用或权限不足）", err))
			return
		}
		if err := os.Rename(tmpPath, dst.AbsPath); err != nil {
			_ = os.Rename(tmpPath, src.AbsPath)
			writeDomainError(w, model.WrapError(model.ErrConflict, http.StatusConflict, "重命名文件夹失败（可能被占用或权限不足）", err))
			return
		}
	} else if err := os.Rename(src.AbsPath, dst.AbsPath); err != nil {
		writeDomainError(w, model.WrapError(model.ErrConflict, http.StatusConflict, "重命名文件夹失败（可能被占用或权限不足）", err))
		return
	}
	if s.deps.Store != nil {
		if err := s.deps.Store.RelocateFolderAssets(r.Context(), src.Root.ID, src.Root.CaseMode, src.RelPath, dst.Root.ID, dst.Root.CaseMode, dst.RelPath); err != nil {
			if sameCaseSlot {
				tmpPath := dst.AbsPath + ".melokeep-case-" + model.NewUUID()
				if os.Rename(dst.AbsPath, tmpPath) == nil {
					_ = os.Rename(tmpPath, src.AbsPath)
				}
			} else {
				_ = os.Rename(dst.AbsPath, src.AbsPath)
			}
			writeDomainError(w, err)
			return
		}
	}
	s.pushLog("info", "已重命名文件夹："+src.RelPath+" -> "+dst.RelPath, "library", "")
	writeJSON(w, http.StatusOK, model.LibraryFolderDTO{
		Root:   dst.Root.CanonicalPath,
		Folder: dst.RelPath,
	})
}

func (s *Server) handleMoveLibraryItems(w http.ResponseWriter, r *http.Request) {
	if s.deps.Files == nil {
		writeDomainError(w, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件服务未初始化"))
		return
	}
	var body struct {
		Files        []string              `json:"files"`
		Folders      []folderTargetPayload `json:"folders"`
		TargetRoot   string                `json:"target_root"`
		TargetFolder string                `json:"target_folder"`
	}
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	if len(body.Files) == 0 && len(body.Folders) == 0 {
		writeError(w, http.StatusBadRequest, "请先选择要移动的文件或文件夹")
		return
	}

	roots := s.deps.Files.Roots()
	cleanTargetFolder := strings.Trim(strings.TrimSpace(filepath.ToSlash(body.TargetFolder)), "/")
	if cleanTargetFolder == "." {
		cleanTargetFolder = ""
	}
	var targetRoot files.Root
	var targetAbs string
	if cleanTargetFolder == "" {
		rMatch, ok := files.ResolveRoot(roots, body.TargetRoot)
		if !ok {
			writeError(w, http.StatusBadRequest, "请选择目标音乐根目录")
			return
		}
		targetRoot = rMatch
		targetAbs = rMatch.CanonicalPath
	} else {
		tvp, err := files.ValidateFolderPath(roots, body.TargetRoot, cleanTargetFolder, true)
		if err != nil {
			writeDomainError(w, err)
			return
		}
		targetRoot = tvp.Root
		targetAbs = tvp.AbsPath
		cleanTargetFolder = tvp.RelPath
	}

	moved := 0
	var failures []string

	for _, rawPath := range body.Files {
		vp, asset, err := s.resolveLibraryTarget(r.Context(), rawPath, false)
		if err != nil {
			failures = append(failures, err.Error())
			continue
		}
		dstPath := filepath.Join(targetAbs, vp.Name)
		if filepath.Clean(dstPath) == filepath.Clean(vp.AbsPath) {
			continue
		}
		ext := strings.ToLower(filepath.Ext(vp.Name))
		if asset != nil || files.AudioExtensions[ext] {
			assetID := ""
			var rev int64
			if asset != nil {
				assetID = asset.ID
				rev = asset.Revision
			}
			if _, opErr := s.deps.Files.Execute(r.Context(), files.OperationRequest{
				Kind:             "move",
				AssetID:          assetID,
				ExpectedRevision: rev,
				SourcePath:       vp.AbsPath,
				TargetPath:       dstPath,
				DisableBackup:    true,
			}); opErr != nil {
				failures = append(failures, vp.Name+"："+opErr.Error())
				continue
			}
			moved++
			continue
		}

		dstVal, dErr := files.ValidatePath(roots, dstPath, files.ValidateOptions{MustExist: false})
		if dErr != nil {
			failures = append(failures, vp.Name+"："+dErr.Error())
			continue
		}
		if _, stErr := os.Lstat(dstVal.AbsPath); stErr == nil {
			failures = append(failures, vp.Name+"：目标目录已存在同名文件")
			continue
		}
		if rErr := os.Rename(vp.AbsPath, dstVal.AbsPath); rErr != nil {
			failures = append(failures, vp.Name+"：移动失败")
			continue
		}
		files.SafeRemoveEmptyParents(filepath.Dir(vp.AbsPath), roots)
		moved++
	}

	for _, item := range body.Folders {
		src, err := files.ValidateFolderPath(roots, item.Root, item.Folder, true)
		if err != nil {
			failures = append(failures, err.Error())
			continue
		}
		if src.Root.ID == targetRoot.ID && (cleanTargetFolder == src.RelPath || strings.HasPrefix(cleanTargetFolder, src.RelPath+"/")) {
			failures = append(failures, src.RelPath+"：不能移动到自身或子目录中")
			continue
		}
		nextFolder := src.Name
		if cleanTargetFolder != "" {
			nextFolder = cleanTargetFolder + "/" + src.Name
		}
		if src.Root.ID == targetRoot.ID && nextFolder == src.RelPath {
			continue
		}
		dst, err := files.ValidateFolderPath(roots, targetRoot.CanonicalPath, nextFolder, false)
		if err != nil {
			failures = append(failures, src.RelPath+"："+err.Error())
			continue
		}
		if _, stErr := os.Lstat(dst.AbsPath); stErr == nil {
			failures = append(failures, src.Name+"：目标目录下已存在同名文件夹")
			continue
		}
		if err := files.VerifySubtreeNoSymlinks(src.AbsPath); err != nil {
			failures = append(failures, src.RelPath+"："+err.Error())
			continue
		}
		release, err := s.acquireFolderAssetLocks(r.Context(), src.Root.ID, src.Root.CaseMode, src.RelPath)
		if err != nil {
			failures = append(failures, src.RelPath+"："+err.Error())
			continue
		}
		if rErr := os.Rename(src.AbsPath, dst.AbsPath); rErr != nil {
			release()
			failures = append(failures, src.RelPath+"：移动目录失败")
			continue
		}
		if s.deps.Store != nil {
			if uErr := s.deps.Store.RelocateFolderAssets(r.Context(), src.Root.ID, src.Root.CaseMode, src.RelPath, dst.Root.ID, dst.Root.CaseMode, dst.RelPath); uErr != nil {
				_ = os.Rename(dst.AbsPath, src.AbsPath)
				release()
				failures = append(failures, src.RelPath+"：更新索引失败")
				continue
			}
		}
		release()
		moved++
	}

	if moved == 0 && len(failures) > 0 {
		writeError(w, http.StatusConflict, strings.Join(failures, "；"))
		return
	}
	if moved > 0 {
		s.pushLog("info", "已移动 "+itoa(moved)+" 项到 "+(cleanTargetFolderOrRoot(cleanTargetFolder)), "library", "")
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"moved":  moved,
		"failed": failures,
	})
}

func (s *Server) handleBatchDeleteLibraryItems(w http.ResponseWriter, r *http.Request) {
	if s.deps.Files == nil {
		writeDomainError(w, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件服务未初始化"))
		return
	}
	var body struct {
		Files   []string              `json:"files"`
		Folders []folderTargetPayload `json:"folders"`
	}
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	if len(body.Files) == 0 && len(body.Folders) == 0 {
		writeError(w, http.StatusBadRequest, "请先选择要删除的文件或文件夹")
		return
	}

	roots := s.deps.Files.Roots()
	deletedFiles := 0
	deletedFolders := 0
	var deletedAssetIDs []string
	var failures []string

	// Deduplicate nested folders: if parent folder is already selected, skip child folder.
	type resolvedFolder struct {
		vp files.ValidatedPath
	}
	var folderTargets []resolvedFolder
	for _, item := range body.Folders {
		vp, err := files.ValidateFolderPath(roots, item.Root, item.Folder, false)
		if err != nil {
			failures = append(failures, err.Error())
			continue
		}
		folderTargets = append(folderTargets, resolvedFolder{vp: vp})
	}
	sort.Slice(folderTargets, func(i, j int) bool {
		return len(folderTargets[i].vp.AbsPath) < len(folderTargets[j].vp.AbsPath)
	})
	var prunedFolders []resolvedFolder
	for _, cand := range folderTargets {
		covered := false
		for _, kept := range prunedFolders {
			if cand.vp.Root.ID == kept.vp.Root.ID && (cand.vp.RelPath == kept.vp.RelPath || strings.HasPrefix(cand.vp.RelPath, kept.vp.RelPath+"/")) {
				covered = true
				break
			}
		}
		if !covered {
			prunedFolders = append(prunedFolders, cand)
		}
	}

	for _, item := range prunedFolders {
		vp := item.vp
		info, statErr := os.Lstat(vp.AbsPath)
		if statErr == nil {
			if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
				failures = append(failures, vp.RelPath+"：目标不是合法的真实目录")
				continue
			}
			if err := files.VerifySubtreeNoSymlinks(vp.AbsPath); err != nil {
				failures = append(failures, vp.RelPath+"："+err.Error())
				continue
			}
			release, err := s.acquireFolderAssetLocks(r.Context(), vp.Root.ID, vp.Root.CaseMode, vp.RelPath)
			if err != nil {
				failures = append(failures, vp.RelPath+"："+err.Error())
				continue
			}
			rmErr := removeDirRegularFilesOnly(vp.AbsPath)
			if rmErr != nil {
				release()
				failures = append(failures, vp.RelPath+"："+rmErr.Error())
				continue
			}
			if s.deps.Store != nil {
				ids, dErr := s.deps.Store.SoftDeleteAssetsByFolder(r.Context(), vp.Root.ID, vp.Root.CaseMode, vp.RelPath)
				if dErr != nil {
					release()
					failures = append(failures, vp.RelPath+"："+dErr.Error())
					continue
				}
				deletedAssetIDs = append(deletedAssetIDs, ids...)
			}
			release()
			deletedFolders++
			continue
		}
		if !errors.Is(statErr, fs.ErrNotExist) {
			failures = append(failures, vp.RelPath+"：读取目录失败")
			continue
		}
		var ids []string
		if s.deps.Store != nil {
			release, err := s.acquireFolderAssetLocks(r.Context(), vp.Root.ID, vp.Root.CaseMode, vp.RelPath)
			if err != nil {
				failures = append(failures, vp.RelPath+"："+err.Error())
				continue
			}
			var dErr error
			ids, dErr = s.deps.Store.SoftDeleteAssetsByFolder(r.Context(), vp.Root.ID, vp.Root.CaseMode, vp.RelPath)
			release()
			if dErr != nil {
				failures = append(failures, vp.RelPath+"："+dErr.Error())
				continue
			}
			deletedAssetIDs = append(deletedAssetIDs, ids...)
		}
		if len(ids) > 0 {
			deletedFolders++
			continue
		}
		failures = append(failures, vp.RelPath+"：not_found: 文件夹不存在")
	}

	for _, rawPath := range body.Files {
		vp, asset, err := s.resolveLibraryTarget(r.Context(), rawPath, false)
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				continue
			}
			if de, ok := model.AsDomainError(err); ok && de.Code == model.ErrNotFound {
				continue
			}
			failures = append(failures, err.Error())
			continue
		}
		ext := strings.ToLower(filepath.Ext(vp.Name))
		if asset != nil || files.AudioExtensions[ext] {
			assetID := ""
			var rev int64
			if asset != nil {
				assetID = asset.ID
				rev = asset.Revision
			}
			if _, opErr := s.deps.Files.Execute(r.Context(), files.OperationRequest{
				Kind:             "delete",
				AssetID:          assetID,
				ExpectedRevision: rev,
				SourcePath:       vp.AbsPath,
			}); opErr != nil {
				failures = append(failures, vp.Name+"："+opErr.Error())
				continue
			}
			if assetID != "" {
				deletedAssetIDs = append(deletedAssetIDs, assetID)
			}
			deletedFiles++
			continue
		}
		if rmErr := os.Remove(vp.AbsPath); rmErr != nil {
			failures = append(failures, vp.Name+"：删除文件失败")
			continue
		}
		files.SafeRemoveEmptyParents(filepath.Dir(vp.AbsPath), roots)
		deletedFiles++
	}

	trackIDs := s.cleanupDeletedAssetRefs(r.Context(), deletedAssetIDs)
	if deletedFiles == 0 && deletedFolders == 0 && len(failures) > 0 {
		status := http.StatusConflict
		allNotFound := true
		for _, f := range failures {
			if !strings.Contains(f, "not_found:") {
				allNotFound = false
				break
			}
		}
		if allNotFound {
			status = http.StatusNotFound
		}
		writeError(w, status, strings.Join(failures, "；"))
		return
	}
	s.pushLog("info", "批量删除完成：文件 "+itoa(deletedFiles)+" 个，文件夹 "+itoa(deletedFolders)+" 个", "library", "")
	writeJSON(w, http.StatusOK, map[string]any{
		"deleted_files":   deletedFiles,
		"deleted_folders": deletedFolders,
		"track_ids":       trackIDs,
		"failed":          failures,
	})
}

func removeDirRegularFilesOnly(dir string) error {
	if err := files.VerifySubtreeNoSymlinks(dir); err != nil {
		return err
	}
	if err := os.RemoveAll(dir); err != nil {
		return model.WrapError(model.ErrConflict, http.StatusConflict, "删除文件夹失败（可能存在正在占用的文件）", err)
	}
	return nil
}

func cleanTargetFolderOrRoot(folder string) string {
	if folder == "" {
		return "根目录"
	}
	return folder
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}
