package httpapi

import (
	"bytes"
	"context"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/library"
	"github.com/agonie0v0/melokeep/internal/library/index"
	"github.com/agonie0v0/melokeep/internal/model"
)

const libraryCredentialsNamespace = "library_credentials"

type libraryCredentials struct {
	AcoustIDAPIKey string `json:"acoustid_api_key"`
}

func (s *Server) buildLibraryConfigDTO(ctx context.Context, settings library.Settings) model.LibraryConfigDTO {
	var navCfg model.NavidromeConfigDTO
	if s.deps.Navidrome != nil {
		if nc, err := s.deps.Navidrome.GetPublicConfig(ctx); err == nil {
			navCfg = nc
		}
	}
	fileRoot := ""
	if s.deps.Files != nil {
		roots := s.deps.Files.Roots()
		if len(roots) > 0 {
			fileRoot = roots[0].CanonicalPath
		}
	}
	hasAcoustID := false
	if s.deps.Store != nil {
		if rec, ok, err := s.deps.Store.GetSetting(ctx, libraryCredentialsNamespace); err == nil && ok {
			var creds libraryCredentials
			if json.Unmarshal([]byte(rec.BodyJSON), &creds) == nil && strings.TrimSpace(creds.AcoustIDAPIKey) != "" {
				hasAcoustID = true
			}
		}
	}
	tools := map[string]bool{
		"ffmpeg":  false,
		"ffprobe": false,
		"fpcalc":  false,
		"mutagen": s.deps.Tags != nil,
	}
	if s.deps.Media != nil {
		tools["ffmpeg"] = s.deps.Media.Available("ffmpeg")
		tools["ffprobe"] = s.deps.Media.Available("ffprobe")
		tools["fpcalc"] = s.deps.Media.Available("fpcalc")
	}
	return model.LibraryConfigDTO{
		AutoScrape:         settings.AutoScrape,
		AutoOrganize:       settings.AutoOrganize,
		LibraryDir:         settings.LibraryDir,
		FileRoot:           fileRoot,
		FolderTemplate:     settings.FolderTemplate,
		FilenameTemplate:   settings.FilenameTemplate,
		Sources:            settings.Sources,
		Overwrite:          settings.Overwrite,
		LyricsMode:         settings.LyricsMode,
		CoverMode:          settings.CoverMode,
		Fields:             settings.Fields,
		BackupDays:         settings.BackupDays,
		Navidrome:          navCfg,
		MetadataSources:    s.deps.Library.SourceCatalog(),
		Chinese:            settings.Chinese,
		RepairEncoding:     settings.RepairEncoding,
		WatchEnabled:       settings.WatchEnabled,
		WatchDirs:          settings.WatchDirs,
		WatchInterval:      settings.WatchInterval,
		WatchStableSeconds: settings.WatchStableSeconds,
		WatchAction:        settings.WatchAction,
		WatchApply:         settings.WatchApply,
		WatchFailureDir:    settings.WatchFailureDir,
		HasAcoustIDKey:     hasAcoustID,
		Tools:              tools,
	}
}

func (s *Server) handleLibrarySettings(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	settings, err := s.deps.Library.Settings(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, s.buildLibraryConfigDTO(r.Context(), settings))
}

func (s *Server) handleLibrarySaveSettings(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	var req library.Settings
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	saved, err := s.deps.Library.SaveSettings(r.Context(), actor, req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if w := s.deps.Library.Watcher(); w != nil {
		w.Wake()
	}
	s.pushLog("info", fmt.Sprintf("已保存曲库整理与监控规则 (整理目录=%s, 备份天数=%d, 目录监控=%v)", saved.LibraryDir, saved.BackupDays, saved.WatchEnabled), "library", "")
	writeJSON(w, http.StatusOK, s.buildLibraryConfigDTO(r.Context(), saved))
}

func (s *Server) handleLibrarySaveCredentials(w http.ResponseWriter, r *http.Request) {
	var req libraryCredentials
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	var existing libraryCredentials
	if s.deps.Store != nil {
		if rec, ok, err := s.deps.Store.GetSetting(r.Context(), libraryCredentialsNamespace); err == nil && ok {
			_ = json.Unmarshal([]byte(rec.BodyJSON), &existing)
		}
	}
	merged := libraryCredentials{
		AcoustIDAPIKey: config.MergeHiddenString(existing.AcoustIDAPIKey, strings.TrimSpace(req.AcoustIDAPIKey)),
	}
	if s.deps.Store != nil {
		raw, _ := json.Marshal(merged)
		if _, err := s.deps.Store.PutSetting(r.Context(), libraryCredentialsNamespace, string(raw), 0); err != nil {
			writeDomainError(w, err)
			return
		}
	}
	hasKey := strings.TrimSpace(merged.AcoustIDAPIKey) != ""
	if hasKey {
		s.pushLog("info", "已更新 AcoustID 音频指纹 Key", "library", "")
	} else {
		s.pushLog("info", "已清空 AcoustID 音频指纹 Key", "library", "")
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"has_acoustid_key": hasKey,
	})
}

func (s *Server) handleLibraryWatchStatus(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil || s.deps.Library.Watcher() == nil {
		writeJSON(w, http.StatusOK, map[string]any{"detail": "尚未检查目录"})
		return
	}
	ws, err := s.deps.Library.Watcher().Status(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, ws)
}

func (s *Server) handleLibraryWatchScan(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil || s.deps.Library.Watcher() == nil {
		writeError(w, http.StatusServiceUnavailable, "目录监控服务未初始化")
		return
	}
	ws, err := s.deps.Library.Watcher().ScanNow(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	totalScanned := 0
	if s.deps.Indexer != nil && s.deps.Store != nil {
		if roots, rErr := s.deps.Store.ListRoots(r.Context()); rErr == nil {
			for _, rootRec := range roots {
				if rootRec.Enabled {
					if st, sErr := s.deps.Indexer.ScanRootWithOptions(r.Context(), rootRec, index.ScanOptions{RepairMissingCovers: true}); sErr == nil {
						totalScanned += st.ScannedFiles
					}
				}
			}
		}
	}
	s.pushLog("info", fmt.Sprintf("手动刷新曲库索引与监控检查完成：扫描文件=%d（%s）", totalScanned, ws.Detail), "library", "")
	writeJSON(w, http.StatusOK, ws)
}

func (s *Server) handleLibrarySaveNavidrome(w http.ResponseWriter, r *http.Request) {
	if s.deps.Navidrome == nil {
		writeError(w, http.StatusServiceUnavailable, "Navidrome 服务未配置")
		return
	}
	var body map[string]any
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	dto, err := s.deps.Navidrome.SaveConfig(r.Context(), actorFromRequest(r), body)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已保存 Navidrome 服务连接设置", "library", "")
	writeJSON(w, http.StatusOK, dto)
}

func (s *Server) handleLibraryJobs(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeJSON(w, http.StatusOK, map[string]any{"jobs": []model.LibraryJobDTO{}, "has_more": false, "total": 0})
		return
	}
	offset := 0
	size := 30
	if rawOff := strings.TrimSpace(r.URL.Query().Get("offset")); rawOff != "" {
		v, err := strconv.Atoi(rawOff)
		if err != nil || v < 0 {
			writeError(w, http.StatusBadRequest, "分页参数无效")
			return
		}
		offset = v
	}
	if rawSize := strings.TrimSpace(r.URL.Query().Get("size")); rawSize != "" {
		v, err := strconv.Atoi(rawSize)
		if err != nil || v < 1 || v > 100 {
			writeError(w, http.StatusBadRequest, "分页参数无效")
			return
		}
		size = v
	}
	statusFilter := strings.TrimSpace(r.URL.Query().Get("status"))
	if statusFilter == "" {
		statusFilter = "all"
	}

	allJobs, err := s.deps.Library.ListJobs(r.Context(), 500)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	var filtered []model.LibraryJobDTO
	for _, j := range allJobs {
		if statusFilter != "all" {
			switch statusFilter {
			case "active":
				if j.Status != "queued" && j.Status != "scanning" && j.Status != "preview_ready" && j.Status != "needs_review" && j.Status != "applying" {
					continue
				}
			case "failed":
				if j.Status != "failed" && j.Status != "partial" && j.Status != "paused" {
					continue
				}
			default:
				if j.Status != statusFilter {
					continue
				}
			}
		}
		filtered = append(filtered, j)
	}
	total := len(filtered)
	end := offset + size
	if offset > total {
		offset = total
	}
	if end > total {
		end = total
	}
	slice := filtered[offset:end]
	if slice == nil {
		slice = []model.LibraryJobDTO{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"jobs":     slice,
		"has_more": total > end,
		"total":    total,
	})
}

func (s *Server) handleLibraryVersions(w http.ResponseWriter, r *http.Request) {
	vp, asset, err := s.resolveLibraryTarget(r.Context(), r.URL.Query().Get("path"), false)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	type versionEntry struct {
		JobID      string            `json:"job_id"`
		CreatedAt  int64             `json:"created_at"`
		Action     string            `json:"action"`
		Status     string            `json:"status"`
		Before     map[string]string `json:"before"`
		After      map[string]string `json:"after"`
		Restorable bool              `json:"restorable"`
	}
	versions := make([]versionEntry, 0)
	if s.deps.Library != nil {
		jobs, lErr := s.deps.Library.ListJobs(r.Context(), 100)
		if lErr == nil {
			for _, summary := range jobs {
				full, ok, gErr := s.deps.Library.GetJob(r.Context(), summary.ID)
				if gErr != nil || !ok {
					continue
				}
				for _, it := range full.Items {
					matched := it.OriginalPath == vp.AbsPath || it.Target == vp.AbsPath
					if !matched && asset != nil && it.ID == asset.ID {
						matched = true
					}
					if !matched || it.OperationID == "" {
						continue
					}
					versions = append(versions, versionEntry{
						JobID:      full.ID,
						CreatedAt:  full.CreatedAt,
						Action:     full.Action,
						Status:     it.Status,
						Before:     it.Before,
						After:      it.After,
						Restorable: it.Status == "complete",
					})
				}
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"versions": versions})
}

func neutralizeCSVCell(val string) string {
	if val == "" {
		return ""
	}
	switch val[0] {
	case '=', '+', '-', '@', '\t', '\r':
		return "'" + val
	default:
		return val
	}
}

func (s *Server) handleLibraryJobReport(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	job, ok, err := s.deps.Library.GetJob(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeError(w, http.StatusNotFound, "整理任务不存在")
		return
	}
	var buf bytes.Buffer
	buf.Write([]byte{0xEF, 0xBB, 0xBF})
	cw := csv.NewWriter(&buf)
	_ = cw.Write([]string{"文件", "状态", "原路径", "目标路径", "修改前", "修改后", "警告或错误", "输出文件", "检查参数"})
	for _, item := range job.Items {
		beforeJSON, _ := json.Marshal(item.Before)
		afterJSON, _ := json.Marshal(item.After)
		warns := append([]string(nil), item.Warnings...)
		if item.Error != "" {
			warns = append(warns, item.Error)
		}
		outs := make([]string, 0, len(item.Outputs))
		for _, o := range item.Outputs {
			outs = append(outs, o.Path)
		}
		qualJSON, _ := json.Marshal(item.Quality)
		row := []string{
			item.Name,
			item.Status,
			item.OriginalPath,
			item.Target,
			string(beforeJSON),
			string(afterJSON),
			strings.Join(warns, "；"),
			strings.Join(outs, "\n"),
			string(qualJSON),
		}
		for i := range row {
			row[i] = neutralizeCSVCell(row[i])
		}
		_ = cw.Write(row)
	}
	cw.Flush()
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="library-report-%s.csv"`, strings.ReplaceAll(ident, `"`, "")))
	w.Header().Set("Content-Length", strconv.Itoa(buf.Len()))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(buf.Bytes())
}

func (s *Server) handleLibraryJob(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	job, ok, err := s.deps.Library.GetJob(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeError(w, http.StatusNotFound, "整理任务不存在")
		return
	}
	writeJSON(w, http.StatusOK, job)
}

func isSyncQueryParam(r *http.Request) bool {
	v := strings.TrimSpace(r.URL.Query().Get("sync"))
	return strings.EqualFold(v, "true") || v == "1"
}

func (s *Server) handleLibraryProcess(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	var req library.SubmitRequest
	if !decodeJSON(w, r, s.imageJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	hasScopeDir := req.ScopeDir != nil && (len(req.Paths) == 0 || strings.TrimSpace(*req.ScopeDir) != "")
	useAsync := !isSyncQueryParam(r) && (req.Action == "scrape" ||
		req.Action == "both" ||
		req.Action == "quality" ||
		req.Action == "duplicates" ||
		hasScopeDir ||
		len(req.Paths) > 10)

	var (
		job model.LibraryJobDTO
		err error
	)
	if useAsync {
		job, err = s.deps.Library.SubmitAsync(r.Context(), actor, req)
	} else {
		job, err = s.deps.Library.Submit(r.Context(), actor, req)
	}
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

type libraryChoicesRequest struct {
	Choices map[string]string `json:"choices"`
}

func (s *Server) handleLibraryApply(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	var req libraryChoicesRequest
	present, ok := decodeJSONOptional(w, r, s.defaultJSONLimit(), &req)
	if !ok {
		return
	}
	actor := actorFromRequest(r)
	if present && len(req.Choices) > 0 {
		if _, err := s.deps.Library.Review(r.Context(), actor, ident, req.Choices); err != nil {
			writeDomainError(w, err)
			return
		}
	}

	useAsync := false
	if !isSyncQueryParam(r) {
		if currentJob, found, gErr := s.deps.Library.GetJob(r.Context(), ident); gErr == nil && found {
			executableCnt := 0
			for _, it := range currentJob.Items {
				if it.Status == string(model.LibraryItemComplete) || it.Status == string(model.LibraryItemSkipped) {
					continue
				}
				if it.Status == string(model.LibraryItemNeedsReview) && it.Chosen == nil {
					continue
				}
				executableCnt++
			}
			if executableCnt > 5 {
				useAsync = true
			}
		}
	}

	var (
		job model.LibraryJobDTO
		err error
	)
	if useAsync {
		job, err = s.deps.Library.ApplyAsync(r.Context(), actor, ident)
	} else {
		job, err = s.deps.Library.Apply(r.Context(), actor, ident)
	}
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

type libraryCandidateSearchRequest struct {
	Item    string   `json:"item"`
	Query   string   `json:"query"`
	Sources []string `json:"sources"`
}

func (s *Server) handleLibrarySearchCandidates(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	var req libraryCandidateSearchRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	job, err := s.deps.Library.SearchItemCandidates(r.Context(), actorFromRequest(r), ident, req.Item, req.Query, req.Sources)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

func (s *Server) handleLibraryPreview(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	var req libraryChoicesRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	job, err := s.deps.Library.Review(r.Context(), actor, ident, req.Choices)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

func (s *Server) handleLibraryCancel(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	actor := actorFromRequest(r)
	if _, err := s.deps.Library.Cancel(r.Context(), actor, ident); err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"cancelled": true})
}

type libraryBatchDeleteRequest struct {
	IDs  []string `json:"ids"`
	Mode string   `json:"mode"`
}

func (s *Server) handleLibraryDeleteJob(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := strings.TrimSpace(r.PathValue("ident"))
	if ident == "" {
		writeError(w, http.StatusBadRequest, "整理任务 ID 不能为空")
		return
	}
	actor := actorFromRequest(r)
	if err := s.deps.Library.DeleteJob(r.Context(), actor, ident); err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已删除整理任务记录", "library", ident)
	writeJSON(w, http.StatusOK, map[string]any{
		"deleted": true,
		"id":      ident,
	})
}

func (s *Server) handleLibraryBatchDeleteJobs(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	var req libraryBatchDeleteRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	deletedIDs, skippedRunning, err := s.deps.Library.BatchDeleteJobs(r.Context(), actor, req.IDs, req.Mode)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if deletedIDs == nil {
		deletedIDs = []string{}
	}
	if skippedRunning == nil {
		skippedRunning = []string{}
	}
	if len(deletedIDs) > 0 {
		s.pushLog("info", fmt.Sprintf("批量删除整理任务记录：已移除 %d 条（跳过运行中 %d 条）", len(deletedIDs), len(skippedRunning)), "library", "")
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"deleted":         len(deletedIDs),
		"deleted_ids":     deletedIDs,
		"skipped_running": skippedRunning,
	})
}

func (s *Server) handleLibraryRetry(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	actor := actorFromRequest(r)

	useAsync := false
	if !isSyncQueryParam(r) {
		if currentJob, found, gErr := s.deps.Library.GetJob(r.Context(), ident); gErr == nil && found {
			retryableCnt := 0
			for _, it := range currentJob.Items {
				switch it.Status {
				case string(model.LibraryItemFailed), string(model.LibraryItemQueued), string(model.LibraryItemApplying):
					retryableCnt++
				case string(model.LibraryItemNeedsReview):
					if it.Chosen != nil {
						retryableCnt++
					}
				}
			}
			if retryableCnt > 5 {
				useAsync = true
			}
		}
	}

	var (
		job model.LibraryJobDTO
		err error
	)
	if useAsync {
		job, err = s.deps.Library.RetryAsync(r.Context(), actor, ident)
	} else {
		job, err = s.deps.Library.Retry(r.Context(), actor, ident)
	}
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

type libraryRestoreRequest struct {
	Items []string `json:"items"`
}

func (s *Server) handleLibraryRestore(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	var req libraryRestoreRequest
	present, ok := decodeJSONOptional(w, r, s.defaultJSONLimit(), &req)
	if !ok {
		return
	}
	actor := actorFromRequest(r)
	itemID := ""
	if present && len(req.Items) > 0 {
		for _, id := range req.Items {
			if _, err := s.deps.Library.Restore(r.Context(), actor, ident, id); err != nil {
				writeDomainError(w, err)
				return
			}
		}
	} else {
		job, exists, gErr := s.deps.Library.GetJob(r.Context(), ident)
		if gErr != nil {
			writeDomainError(w, gErr)
			return
		}
		if !exists {
			writeError(w, http.StatusNotFound, "整理任务不存在")
			return
		}
		for _, it := range job.Items {
			if it.OperationID != "" {
				itemID = it.ID
				if _, err := s.deps.Library.Restore(r.Context(), actor, ident, itemID); err != nil {
					writeDomainError(w, err)
					return
				}
			}
		}
	}
	updated, _, err := s.deps.Library.GetJob(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, updated)
}

func (s *Server) handleLibraryUnlock(w http.ResponseWriter, r *http.Request) {
	vp, asset, err := s.resolveLibraryTarget(r.Context(), r.URL.Query().Get("path"), false)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if asset != nil && s.deps.Store != nil {
		if err := s.deps.Store.UpdateAssetLocks(r.Context(), asset.ID, []string{}, asset.Revision); err != nil {
			writeDomainError(w, err)
			return
		}
	}
	s.pushLog("info", "已解除歌曲标签锁定："+vp.Name, "library", "")
	writeJSON(w, http.StatusOK, map[string]any{"unlocked": true})
}

func (s *Server) handleLibraryPurgeBackups(w http.ResponseWriter, r *http.Request) {
	if s.deps.Library == nil {
		writeError(w, http.StatusServiceUnavailable, "曲库整理服务未初始化")
		return
	}
	removed, freedBytes, err := s.deps.Library.PurgeBackups(r.Context(), actorFromRequest(r))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"removed_operations": removed,
		"freed_bytes":        freedBytes,
	})
}
