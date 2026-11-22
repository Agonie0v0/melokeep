package httpapi

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

var mediaTypesByExt = map[string]string{
	".mp3":  "audio/mpeg",
	".flac": "audio/flac",
	".m4a":  "audio/mp4",
	".mp4":  "audio/mp4",
	".ogg":  "audio/ogg",
	".opus": "audio/opus",
	".wav":  "audio/wav",
	".aac":  "audio/aac",
	".aiff": "audio/aiff",
	".aif":  "audio/aiff",
	".wma":  "audio/x-ms-wma",
	".ape":  "audio/ape",
	".wv":   "audio/wavpack",
}

type downloadEnqueueItem struct {
	TrackID          string `json:"track_id"`
	QualityID        string `json:"quality_id"`
	PreferredQuality string `json:"preferred_quality,omitempty"`
	Layout           string `json:"layout,omitempty"`
	BookTitle        string `json:"book_title,omitempty"`
	ChapterIndex     int    `json:"chapter_index,omitempty"`
	ChapterTotal     int    `json:"chapter_total,omitempty"`
	SkipAutoOrganize bool   `json:"skip_auto_organize,omitempty"`
	ForceNew         bool   `json:"force_new,omitempty"`
	IntentNonce      string `json:"intent_nonce,omitempty"`
	AllowPreview     bool   `json:"allow_preview,omitempty"`
}

func (s *Server) handleDownloads(w http.ResponseWriter, r *http.Request) {
	if s.deps.Download == nil {
		writeJSON(w, http.StatusOK, []model.DownloadJobDTO{})
		return
	}
	limit := 200
	if rawLim := strings.TrimSpace(r.URL.Query().Get("limit")); rawLim != "" {
		if v, err := strconv.Atoi(rawLim); err == nil && v > 0 {
			limit = v
		}
	}
	if limit > 2000 {
		limit = 2000
	}
	list, err := s.deps.Download.List(r.Context(), limit)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	out := make([]model.DownloadJobDTO, 0, len(list))
	for _, dl := range list {
		dto, dErr := s.deps.Download.ToDTO(r.Context(), dl)
		if dErr != nil {
			continue
		}
		out = append(out, dto)
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleDownloadEnqueue(w http.ResponseWriter, r *http.Request) {
	if s.deps.Download == nil {
		writeError(w, http.StatusServiceUnavailable, "下载服务未初始化")
		return
	}
	var items []downloadEnqueueItem
	if !decodeJSON(w, r, s.defaultJSONLimit(), &items) {
		return
	}
	if len(items) < 1 || len(items) > 200 {
		writeError(w, http.StatusBadRequest, "每批请选择 1–200 首")
		return
	}
	actor := actorFromRequest(r)
	snap := s.currentSnapshot()
	var defaultAutoOrg *download.AutoOrganizeConfig
	if s.deps.Library != nil {
		if ls, lErr := s.deps.Library.Settings(r.Context()); lErr == nil && (ls.AutoScrape || ls.AutoOrganize) {
			action := "organize"
			switch {
			case ls.AutoScrape && ls.AutoOrganize:
				action = "both"
			case ls.AutoScrape:
				action = "scrape"
			}
			defaultAutoOrg = &download.AutoOrganizeConfig{Enabled: true, Action: action}
		}
	}
	out := make([]model.DownloadJobDTO, 0, len(items))
	baseKey := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	for idx, it := range items {
		ikey := baseKey
		if ikey != "" && len(items) > 1 {
			ikey = ikey + ":" + strconv.Itoa(idx)
		}
		autoOrg := defaultAutoOrg
		if it.SkipAutoOrganize {
			autoOrg = nil
		}
		res, err := s.deps.Download.Enqueue(r.Context(), actor, snap, download.EnqueueRequest{
			PublicID:         it.TrackID,
			QualityID:        it.QualityID,
			PreferredQuality: it.PreferredQuality,
			Layout:           it.Layout,
			BookTitle:        it.BookTitle,
			ChapterIndex:     it.ChapterIndex,
			ChapterTotal:     it.ChapterTotal,
			IdempotencyKey:   ikey,
			ForceNew:         it.ForceNew,
			IntentNonce:      it.IntentNonce,
			AllowPreview:     it.AllowPreview,
			AutoOrganize:     autoOrg,
		})
		if err != nil {
			writeDomainError(w, err)
			return
		}
		dto, dErr := s.deps.Download.ToDTO(r.Context(), res.Download)
		if dErr != nil {
			writeDomainError(w, dErr)
			return
		}
		out = append(out, dto)
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleDownloadControl(w http.ResponseWriter, r *http.Request) {
	if s.deps.Download == nil {
		writeError(w, http.StatusServiceUnavailable, "下载服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	action := r.PathValue("action")
	actor := actorFromRequest(r)
	snap := s.currentSnapshot()

	var dl model.Download
	var err error
	switch action {
	case "pause":
		dl, err = s.deps.Download.Pause(r.Context(), actor, ident)
	case "cancel":
		dl, err = s.deps.Download.Cancel(r.Context(), actor, ident)
	case "resume":
		dl, err = s.deps.Download.Resume(r.Context(), actor, ident, snap)
	case "retry":
		dl, err = s.deps.Download.Retry(r.Context(), actor, ident, snap)
	default:
		writeError(w, http.StatusBadRequest, "操作无效")
		return
	}
	if err != nil {
		writeDomainError(w, err)
		return
	}
	dto, dErr := s.deps.Download.ToDTO(r.Context(), dl)
	if dErr != nil {
		writeDomainError(w, dErr)
		return
	}
	s.pushLog("info", fmt.Sprintf("下载任务操作 (%s)：%s -> %s", action, dto.Track.Title, dto.Status), "download", ident)
	writeJSON(w, http.StatusOK, dto)
}

func (s *Server) handleDownloadDelete(w http.ResponseWriter, r *http.Request) {
	if s.deps.Download == nil {
		writeError(w, http.StatusServiceUnavailable, "下载服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	actor := actorFromRequest(r)
	if err := s.deps.Download.Delete(r.Context(), actor, ident); err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已移除下载任务记录", "download", ident)
	writeJSON(w, http.StatusOK, map[string]any{"removed": true})
}

func (s *Server) queryLibraryFilesPage(ctx context.Context, q store.LibraryFilesQuery) (model.LibraryFilesPageDTO, error) {
	if s.deps.Indexer != nil {
		return s.deps.Indexer.QueryFiles(ctx, q)
	}
	if s.deps.Store != nil {
		return s.deps.Store.QueryLibraryFiles(ctx, q)
	}
	return model.LibraryFilesPageDTO{
		Items:         []model.LibraryFileDTO{},
		IndexRevision: 1,
	}, nil
}

type libraryRefreshResult struct {
	RootsScanned   int      `json:"roots_scanned"`
	ScannedFiles   int      `json:"scanned_files"`
	UnchangedFiles int      `json:"unchanged_files"`
	UpdatedOrNew   int      `json:"updated_or_new"`
	MarkedMissing  int64    `json:"marked_missing"`
	UnhealthyRoots []string `json:"unhealthy_roots"`
}

func (s *Server) rescanLibraryRoots(ctx context.Context) (libraryRefreshResult, error) {
	res := libraryRefreshResult{
		UnhealthyRoots: []string{},
	}
	if s.deps.Indexer == nil || s.deps.Store == nil {
		return res, model.NewError(model.ErrConflict, http.StatusServiceUnavailable, "曲库索引服务未初始化")
	}
	roots, err := s.deps.Store.ListRoots(ctx)
	if err != nil {
		return res, err
	}
	for _, rt := range roots {
		if ctx.Err() != nil {
			return res, ctx.Err()
		}
		if !rt.Enabled {
			continue
		}
		res.RootsScanned++
		stats, scanErr := s.deps.Indexer.ScanRoot(ctx, rt)
		if scanErr != nil {
			res.UnhealthyRoots = append(res.UnhealthyRoots, rt.ID)
			s.pushLog("warning", fmt.Sprintf("手动刷新曲库目录失败 (%s): %v", rt.ID, scanErr), "library", rt.ID)
			continue
		}
		if !stats.Healthy {
			res.UnhealthyRoots = append(res.UnhealthyRoots, rt.ID)
		}
		res.ScannedFiles += stats.ScannedFiles
		res.UnchangedFiles += stats.UnchangedFiles
		res.UpdatedOrNew += stats.UpdatedOrNew
		res.MarkedMissing += stats.MarkedMissing
	}
	return res, nil
}

func (s *Server) handleLibraryRefresh(w http.ResponseWriter, r *http.Request) {
	res, err := s.rescanLibraryRoots(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog(
		"info",
		fmt.Sprintf("曲库手动刷新完成：扫描 %d 个目录，共 %d 首（新增/更新 %d，未变化 %d，标记缺失 %d）",
			res.RootsScanned, res.ScannedFiles, res.UpdatedOrNew, res.UnchangedFiles, res.MarkedMissing),
		"library",
		"",
	)
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleLibraryFiles(w http.ResponseWriter, r *http.Request) {
	if rawRescan := strings.TrimSpace(r.URL.Query().Get("rescan")); rawRescan == "1" || strings.EqualFold(rawRescan, "true") {
		if s.deps.Indexer != nil && s.deps.Store != nil {
			if _, err := s.rescanLibraryRoots(r.Context()); err != nil {
				s.pushLog("warning", "列表刷新前重扫曲库失败: "+err.Error(), "library", "")
			}
		}
	}
	q := store.LibraryFilesQuery{
		Limit:  100,
		Cursor: strings.TrimSpace(r.URL.Query().Get("cursor")),
		Query:  r.URL.Query().Get("query"),
		Root:   strings.TrimSpace(r.URL.Query().Get("root")),
		Folder: r.URL.Query().Get("folder"),
		Sort:   strings.TrimSpace(r.URL.Query().Get("sort")),
	}
	if rawLim := strings.TrimSpace(r.URL.Query().Get("limit")); rawLim != "" {
		v, err := strconv.Atoi(rawLim)
		if err != nil || v < 1 || v > 500 {
			writeError(w, http.StatusBadRequest, "分页参数无效")
			return
		}
		q.Limit = v
	}
	page, err := s.queryLibraryFilesPage(r.Context(), q)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if page.Items == nil {
		page.Items = []model.LibraryFileDTO{}
	}
	if q.Cursor == "" && s.deps.Files != nil {
		page.Folders = mergeLibraryFolders(page.Folders, files.ListSubdirectories(s.deps.Files.Roots(), 5000))
	}
	writeJSON(w, http.StatusOK, page)
}

func (s *Server) handleFiles(w http.ResponseWriter, r *http.Request) {
	var all []model.LibraryFileDTO
	cursor := ""
	var expectedRev int64
	for pages := 0; pages < 20; pages++ {
		page, err := s.queryLibraryFilesPage(r.Context(), store.LibraryFilesQuery{
			Limit:  500,
			Cursor: cursor,
			Sort:   "path_key",
		})
		if err != nil {
			writeDomainError(w, err)
			return
		}
		if expectedRev == 0 {
			expectedRev = page.IndexRevision
		} else if page.IndexRevision != expectedRev {
			break
		}
		all = append(all, page.Items...)
		if page.NextCursor == nil || *page.NextCursor == "" || len(all) >= 5000 {
			break
		}
		cursor = *page.NextCursor
	}
	if all == nil {
		all = []model.LibraryFileDTO{}
	}
	writeJSON(w, http.StatusOK, all)
}

func (s *Server) resolveAssetAbsPath(asset store.AssetRecord) (string, error) {
	if s.deps.Store == nil || s.deps.Files == nil {
		return "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "曲库服务未初始化")
	}
	rootRec, ok, err := s.deps.Store.GetRoot(context.Background(), asset.RootID)
	if err != nil {
		return "", err
	}
	if !ok {
		return "", model.NewError(model.ErrNotFound, http.StatusNotFound, "曲库根目录不存在")
	}
	cand := filepath.Join(rootRec.CanonicalPath, filepath.FromSlash(asset.RelPath))
	vp, err := files.ValidatePath(s.deps.Files.Roots(), cand, files.ValidateOptions{MustExist: true})
	if err != nil {
		return "", err
	}
	return vp.AbsPath, nil
}

func (s *Server) resolveLibraryTarget(ctx context.Context, rawPath string, audioOnly bool) (files.ValidatedPath, *store.AssetRecord, error) {
	rawPath = strings.TrimSpace(rawPath)
	if rawPath == "" {
		return files.ValidatedPath{}, nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少文件路径")
	}
	if s.deps.Files == nil {
		return files.ValidatedPath{}, nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件服务未初始化")
	}
	roots := s.deps.Files.Roots()
	if s.deps.Store != nil {
		if strings.HasPrefix(rawPath, "asset:") {
			assetID := strings.TrimPrefix(rawPath, "asset:")
			asset, ok, err := s.deps.Store.GetAsset(ctx, assetID)
			if err != nil {
				return files.ValidatedPath{}, nil, err
			}
			if !ok || asset.Status == "deleted" {
				return files.ValidatedPath{}, nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "文件不存在")
			}
			rootRec, rOK, rErr := s.deps.Store.GetRoot(ctx, asset.RootID)
			if rErr != nil {
				return files.ValidatedPath{}, nil, rErr
			}
			if !rOK {
				return files.ValidatedPath{}, nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "文件根目录不存在")
			}
			cand := filepath.Join(rootRec.CanonicalPath, filepath.FromSlash(asset.RelPath))
			vp, vErr := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: true, AudioOnly: audioOnly})
			if vErr != nil {
				return files.ValidatedPath{}, nil, vErr
			}
			return vp, &asset, nil
		}
		if asset, ok, err := s.deps.Store.ResolveAssetAlias(ctx, rawPath); err == nil && ok && asset.Status != "deleted" {
			if rootRec, rOK, rErr := s.deps.Store.GetRoot(ctx, asset.RootID); rErr == nil && rOK {
				cand := filepath.Join(rootRec.CanonicalPath, filepath.FromSlash(asset.RelPath))
				if vp, vErr := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: true, AudioOnly: audioOnly}); vErr == nil {
					return vp, &asset, nil
				}
			}
		}
	}

	var lastErr error
	if !filepath.IsAbs(rawPath) {
		for _, root := range roots {
			cand := filepath.Join(root.CanonicalPath, filepath.FromSlash(rawPath))
			vp, err := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: true, AudioOnly: audioOnly})
			if err == nil {
				if rec, aOK, aErr := s.deps.Store.GetAssetByRootAndPathKey(ctx, vp.Root.ID, vp.PathKey); aErr == nil && aOK && rec.Status != "deleted" {
					return vp, &rec, nil
				}
				return vp, nil, nil
			}
			lastErr = err
			if de, ok := model.AsDomainError(err); ok && de.Code == model.ErrForbidden {
				return files.ValidatedPath{}, nil, err
			}
		}
		if lastErr != nil {
			return files.ValidatedPath{}, nil, lastErr
		}
	}
	vp, err := files.ValidatePath(roots, rawPath, files.ValidateOptions{MustExist: true, AudioOnly: audioOnly})
	if err != nil {
		return files.ValidatedPath{}, nil, err
	}
	if s.deps.Store != nil {
		if rec, aOK, aErr := s.deps.Store.GetAssetByRootAndPathKey(ctx, vp.Root.ID, vp.PathKey); aErr == nil && aOK && rec.Status != "deleted" {
			return vp, &rec, nil
		}
	}
	return vp, nil, nil
}

func (s *Server) serveLocalPath(w http.ResponseWriter, r *http.Request, absPath, assetID, disposition string) {
	if s.deps.Files != nil && assetID != "" {
		release, err := s.deps.Files.Locks().AcquireRead(assetID)
		if err != nil {
			writeDomainError(w, err)
			return
		}
		defer release()
	}
	f, err := os.Open(absPath)
	if err != nil {
		if os.IsNotExist(err) {
			writeError(w, http.StatusNotFound, "文件不存在")
			return
		}
		writeError(w, http.StatusConflict, "文件无法读取")
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.IsDir() {
		writeError(w, http.StatusNotFound, "文件不存在")
		return
	}
	ext := strings.ToLower(filepath.Ext(absPath))
	ct := mediaTypesByExt[ext]
	if ct == "" {
		ct = "application/octet-stream"
	}
	w.Header().Set("Content-Type", ct)
	name := filepath.Base(absPath)
	if disposition == "attachment" {
		w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, strings.ReplaceAll(name, `"`, "")))
	} else {
		w.Header().Set("Content-Disposition", fmt.Sprintf(`inline; filename="%s"`, strings.ReplaceAll(name, `"`, "")))
	}
	http.ServeContent(w, r, name, info.ModTime(), f)
}

func (s *Server) handleLocalAudio(w http.ResponseWriter, r *http.Request) {
	vp, asset, err := s.resolveLibraryTarget(r.Context(), r.URL.Query().Get("path"), true)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	assetID := ""
	if asset != nil {
		assetID = asset.ID
	}
	s.serveLocalPath(w, r, vp.AbsPath, assetID, "inline")
}

func (s *Server) handleFileContent(w http.ResponseWriter, r *http.Request) {
	vp, asset, err := s.resolveLibraryTarget(r.Context(), r.URL.Query().Get("path"), false)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	assetID := ""
	if asset != nil {
		assetID = asset.ID
	}
	s.serveLocalPath(w, r, vp.AbsPath, assetID, "attachment")
}

func (s *Server) handleLocalCover(w http.ResponseWriter, r *http.Request) {
	vp, _, err := s.resolveLibraryTarget(r.Context(), r.URL.Query().Get("path"), true)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if data, mime, ok := media.ExtractEmbeddedCover(vp.AbsPath); ok {
		if mime != "image/jpeg" && mime != "image/png" && mime != "image/webp" {
			writeError(w, http.StatusUnsupportedMediaType, "不支持该封面格式")
			return
		}
		w.Header().Set("Content-Type", mime)
		w.Header().Set("Content-Length", strconv.Itoa(len(data)))
		w.WriteHeader(http.StatusOK)
		if r.Method != http.MethodHead {
			_, _ = w.Write(data)
		}
		return
	}
	if raw, mime, ok := media.FindSidecarCover(vp.AbsPath); ok {
		w.Header().Set("Content-Type", mime)
		w.Header().Set("Content-Length", strconv.Itoa(len(raw)))
		w.WriteHeader(http.StatusOK)
		if r.Method != http.MethodHead {
			_, _ = w.Write(raw)
		}
		return
	}
	writeError(w, http.StatusNotFound, "文件没有封面")
}

func (s *Server) handleEditFileTags(w http.ResponseWriter, r *http.Request) {
	vp, asset, err := s.resolveLibraryTarget(r.Context(), r.URL.Query().Get("path"), true)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	var rawChanges map[string]*string
	if !decodeJSON(w, r, s.defaultJSONLimit(), &rawChanges) {
		return
	}
	changes := make(map[string]string)
	for k, v := range rawChanges {
		if v != nil {
			changes[k] = strings.TrimSpace(*v)
		}
	}
	var assetID string
	var expectedRev int64
	if asset != nil {
		assetID = asset.ID
		expectedRev = asset.Revision
	}
	if s.deps.Tags == nil {
		writeError(w, http.StatusServiceUnavailable, "标签编辑服务未初始化")
		return
	}
	mergedMeta := make(map[string]string)
	lockSeen := make(map[string]bool)
	var lockedFields []string
	if asset != nil {
		for k, v := range asset.Metadata {
			mergedMeta[k] = v
		}
		for _, f := range asset.LockedFields {
			if f != "" && !lockSeen[f] {
				lockSeen[f] = true
				lockedFields = append(lockedFields, f)
			}
		}
	}
	for k, v := range changes {
		mergedMeta[k] = v
		if k != "" && !lockSeen[k] {
			lockSeen[k] = true
			lockedFields = append(lockedFields, k)
		}
	}
	title := mergedMeta["title"]
	if _, explicitTitle := changes["title"]; !explicitTitle {
		title = firstNonEmpty(title, strings.TrimSuffix(vp.Name, filepath.Ext(vp.Name)))
	}
	artist := mergedMeta["artist"]
	album := mergedMeta["album"]
	res, opErr := s.deps.Files.Execute(r.Context(), files.OperationRequest{
		Kind:             "edit",
		AssetID:          assetID,
		ExpectedRevision: expectedRev,
		SourcePath:       vp.AbsPath,
		TargetPath:       vp.AbsPath,
		NewMetadata:      mergedMeta,
		NewTitle:         title,
		NewArtist:        artist,
		NewAlbum:         album,
		AllowEmptyMeta:   true,
		NewLockedFields:  lockedFields,
		Editable:         true,
		Mutator: func(ctx context.Context, stagePath string) ([]files.SidecarOutput, error) {
			_, wErr := s.deps.Tags.WriteStagedTags(ctx, helper.WriteTagsRequest{
				AllowedStageRoot: filepath.Dir(stagePath),
				StagePath:        stagePath,
				Tags:             changes,
			})
			return nil, wErr
		},
	})
	if opErr != nil {
		s.pushLog("error", fmt.Sprintf("编辑歌曲标签失败 (%s)：%v", vp.Name, opErr), "library", "")
		writeDomainError(w, opErr)
		return
	}
	s.pushLog("info", "已更新歌曲标签并锁定字段："+vp.Name, "library", "")
	if res.Asset != nil {
		dto := s.localTrackDTOFromAsset(*res.Asset)
		_ = s.storeTrackDTO(r.Context(), dto)
		pathToken := "asset:" + res.Asset.ID
		editable := res.Asset.Editable
		writeJSON(w, http.StatusOK, model.LibraryFileDTO{
			Path:         pathToken,
			Name:         res.Asset.Name,
			Size:         res.Asset.Size,
			Root:         vp.Root.CanonicalPath,
			Folder:       res.Asset.Folder,
			Track:        &dto,
			Editable:     &editable,
			Metadata:     res.Asset.Metadata,
			LockedFields: res.Asset.LockedFields,
		})
		return
	}
	writeJSON(w, http.StatusOK, model.LibraryFileDTO{
		Path:   vp.AbsPath,
		Name:   vp.Name,
		Root:   vp.Root.CanonicalPath,
		Folder: vp.Folder,
	})
}

func (s *Server) handleDeleteLibraryFile(w http.ResponseWriter, r *http.Request) {
	vp, asset, err := s.resolveLibraryTarget(r.Context(), r.URL.Query().Get("path"), false)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	assetID := ""
	var expectedRev int64
	if asset != nil {
		assetID = asset.ID
		expectedRev = asset.Revision
	}
	if _, opErr := s.deps.Files.Execute(r.Context(), files.OperationRequest{
		Kind:             "delete",
		AssetID:          assetID,
		ExpectedRevision: expectedRev,
		SourcePath:       vp.AbsPath,
	}); opErr != nil {
		writeDomainError(w, opErr)
		return
	}
	trackIDs := s.cleanupDeletedAssetRefs(r.Context(), []string{assetID})
	s.pushLog("info", "已删除音乐文件："+vp.Name, "library", "")
	writeJSON(w, http.StatusOK, map[string]any{
		"deleted":   true,
		"track_ids": trackIDs,
	})
}

func (s *Server) resolveLegacyDirRel(rawDir, rawRel string) (files.ValidatedPath, error) {
	if s.deps.Files == nil {
		return files.ValidatedPath{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件服务未初始化")
	}
	cleanDir, err := filepath.Abs(strings.TrimSpace(rawDir))
	if err != nil {
		return files.ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "目录不在允许范围内")
	}
	var matched *files.Root
	for _, root := range s.deps.Files.Roots() {
		if filepath.Clean(root.CanonicalPath) == filepath.Clean(cleanDir) {
			rCopy := root
			matched = &rCopy
			break
		}
	}
	if matched == nil {
		return files.ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "目录不在允许范围内")
	}
	cand := filepath.Join(matched.CanonicalPath, filepath.FromSlash(rawRel))
	return files.ValidatePath([]files.Root{*matched}, cand, files.ValidateOptions{MustExist: true})
}

func (s *Server) handleLegacyGetFile(w http.ResponseWriter, r *http.Request) {
	vp, err := s.resolveLegacyDirRel(r.URL.Query().Get("d"), r.URL.Query().Get("rel"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.serveLocalPath(w, r, vp.AbsPath, "", "attachment")
}

func (s *Server) handleLegacyDeleteFile(w http.ResponseWriter, r *http.Request) {
	vp, err := s.resolveLegacyDirRel(r.URL.Query().Get("d"), r.URL.Query().Get("rel"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if _, opErr := s.deps.Files.Execute(r.Context(), files.OperationRequest{
		Kind:       "delete",
		SourcePath: vp.AbsPath,
	}); opErr != nil {
		writeDomainError(w, opErr)
		return
	}
	s.deps.Logs.Push("info", "已删除文件: "+vp.Name, "system", "")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
