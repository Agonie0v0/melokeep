package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/search"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const (
	durableNamespaceFavorite       = "favorite"
	durableNamespacePlaylist       = "playlist"
	durableNamespacePlaylistIssues = "playlist_issues"
	mainPlaylistID                 = "main"
)

var streamCopyBufPool = sync.Pool{
	New: func() any {
		b := make([]byte, 32*1024)
		return &b
	},
}

var playlistSlugRe = regexp.MustCompile(`^[\w-]+$`)

func (s *Server) handleSearchStart(w http.ResponseWriter, r *http.Request) {
	if s.deps.Search == nil {
		writeError(w, http.StatusServiceUnavailable, "搜索服务未初始化")
		return
	}
	var req search.CreateRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	jobID, err := s.deps.Search.CreateSearch(r.Context(), actor, s.currentSnapshot(), req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": jobID})
}

func (s *Server) handleSearchStop(w http.ResponseWriter, r *http.Request) {
	if s.deps.Search == nil {
		writeJSON(w, http.StatusOK, map[string]any{"cancelled": true})
		return
	}
	ident := r.PathValue("ident")
	actor := actorFromRequest(r)
	if err := s.deps.Search.Cancel(r.Context(), actor, ident); err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"cancelled": true})
}

func (s *Server) handleSearchEvents(w http.ResponseWriter, r *http.Request) {
	if s.deps.Search == nil {
		writeError(w, http.StatusServiceUnavailable, "搜索服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	var after int64
	if rawAfter := strings.TrimSpace(r.URL.Query().Get("after")); rawAfter != "" {
		v, err := strconv.ParseInt(rawAfter, 10, 64)
		if err != nil || v < 0 {
			writeError(w, http.StatusBadRequest, "事件序号无效")
			return
		}
		after = v
	}
	lastEventID := strings.TrimSpace(r.Header.Get("Last-Event-ID"))
	sub, err := s.deps.Search.Subscribe(r.Context(), ident, after, lastEventID)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	defer sub.Close()

	flusher, _ := w.(http.Flusher)
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	if flusher != nil {
		flusher.Flush()
	}

	for {
		ev, err := sub.Next(r.Context())
		if err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, search.ErrSlowSubscriber) || errors.Is(err, context.Canceled) || r.Context().Err() != nil {
				return
			}
			return
		}
		if _, wErr := w.Write(ev.SSEBytes()); wErr != nil {
			return
		}
		if flusher != nil {
			flusher.Flush()
		}
	}
}

func (s *Server) loadDomainTrack(ctx context.Context, sid string) (model.ProviderTrack, *store.AssetRecord, bool, error) {
	if s.deps.Store == nil {
		return model.ProviderTrack{}, nil, false, nil
	}
	ref, _, ok, err := s.deps.Store.GetPublicRef(ctx, sid)
	if err != nil {
		return model.ProviderTrack{}, nil, false, err
	}
	if ok && ref.Kind == "asset" {
		asset, aOK, aErr := s.deps.Store.GetAsset(ctx, ref.TargetID)
		if aErr != nil || !aOK || asset.Status == "deleted" {
			return model.ProviderTrack{}, nil, false, aErr
		}
		return model.ProviderTrack{}, &asset, true, nil
	}
	if !ok {
		assetLookupID := sid
		if strings.HasPrefix(sid, "asset:") {
			assetLookupID = strings.TrimPrefix(sid, "asset:")
		} else if strings.HasPrefix(sid, "local-") {
			assetLookupID = strings.TrimPrefix(sid, "local-")
		}
		if asset, aOK, aErr := s.deps.Store.GetAsset(ctx, assetLookupID); aErr == nil && aOK && asset.Status != "deleted" {
			return model.ProviderTrack{}, &asset, true, nil
		}
		return model.ProviderTrack{}, nil, false, nil
	}
	tr, variants, tOK, tErr := s.deps.Store.GetProviderTrack(ctx, ref.TargetID)
	if tErr != nil || !tOK || tr.DeletedAt != nil {
		return model.ProviderTrack{}, nil, false, tErr
	}
	var meta struct {
		Cover     *string `json:"cover"`
		SizeBytes *int64  `json:"size_bytes"`
		SizeLabel *string `json:"size_label"`
		Episodes  int     `json:"episodes"`
		Lyrics    string  `json:"lyrics"`
	}
	_ = json.Unmarshal([]byte(tr.MetadataJSON), &meta)
	return model.ProviderTrack{
		PublicID:   sid,
		Source:     tr.Source,
		SourceID:   tr.Locator,
		RootSource: tr.RootSource,
		Title:      tr.Title,
		Artist:     tr.Artist,
		Album:      tr.Album,
		Duration:   float64(tr.DurationMS) / 1000.0,
		Cover:      meta.Cover,
		SizeBytes:  meta.SizeBytes,
		SizeLabel:  meta.SizeLabel,
		Episodes:   meta.Episodes,
		Lyrics:     meta.Lyrics,
		Variants:   variants,
	}, nil, true, nil
}

func (s *Server) findDownloadedFileForTrack(ctx context.Context, sid string) (string, string, bool) {
	if s.deps.Store == nil || s.deps.Files == nil {
		return "", "", false
	}
	dls, err := s.deps.Store.ListDownloads(ctx, 200)
	if err != nil {
		return "", "", false
	}
	for _, dl := range dls {
		if dl.Status != model.DownloadComplete || dl.File == nil || strings.TrimSpace(*dl.File) == "" {
			continue
		}
		if dl.PublicID == sid {
			if vp, asset, vErr := s.resolveLibraryTarget(ctx, *dl.File, true); vErr == nil {
				assetID := ""
				if asset != nil {
					assetID = asset.ID
				}
				return vp.AbsPath, assetID, true
			}
		}
	}
	return "", "", false
}

func (s *Server) localTrackDTOFromAsset(asset store.AssetRecord) model.TrackDTO {
	pathToken := "asset:" + asset.ID
	ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(asset.RelPath)), ".")
	var cover *string
	if s.deps.Files != nil {
		if vp, err := s.resolveAssetAbsPath(asset); err == nil {
			hasCover := media.HasLocalCover(vp)
			if !hasCover && s.deps.Tags != nil {
				if td, tErr := s.deps.Tags.ReadMetadata(context.Background(), vp); tErr == nil && td.HasCover {
					hasCover = true
				}
			}
			if hasCover {
				c := fmt.Sprintf("/api/files/cover?path=%s&v=%d", url.QueryEscape(pathToken), asset.MTimeNS)
				cover = &c
			}
		}
	}
	audioURL := "/api/files/audio?path=" + url.QueryEscape(pathToken)
	detail := "音乐库文件"
	size := asset.Size
	return model.TrackDTO{
		ID:        pathToken,
		Source:    "local",
		SourceID:  asset.ID,
		Title:     firstNonEmpty(asset.Title, strings.TrimSuffix(asset.Name, filepath.Ext(asset.Name))),
		Artist:    asset.Artist,
		Album:     asset.Album,
		Duration:  float64(asset.DurationMS) / 1000.0,
		Cover:     cover,
		SizeBytes: &size,
		Qualities: []model.QualityDTO{},
		Playback: model.PlaybackDTO{
			State:       "ready",
			URL:         &audioURL,
			Codec:       &ext,
			Detail:      &detail,
			PreviewOnly: false,
		},
	}
}

func (s *Server) resolveMediaCached(ctx context.Context, sid string, tr model.ProviderTrack, refresh bool, allowExternalWrite bool) ([]model.ResolvedMedia, error) {
	snap := s.currentSnapshot()
	cacheKey := sid + "|" + snap.Revision
	if !refresh {
		s.resolveMu.Lock()
		entry, ok := s.resolveCache[cacheKey]
		if ok && time.Now().Before(entry.ExpiresAt) {
			out := make([]model.ResolvedMedia, len(entry.Media))
			copy(out, entry.Media)
			s.resolveMu.Unlock()
			return out, nil
		}
		if ok {
			delete(s.resolveCache, cacheKey)
		}
		s.resolveMu.Unlock()
		if s.deps.Search != nil {
			if cached, ok := s.deps.Search.CachedResolved(sid, snap.Revision); ok && len(cached) > 0 {
				return cached, nil
			}
		}
	}
	if s.deps.Providers == nil {
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "音源注册表未初始化")
	}
	prov, ok := s.deps.Providers.Get(tr.RootSource)
	if !ok {
		prov, ok = s.deps.Providers.Get(tr.Source)
	}
	if !ok {
		return nil, model.NewError(model.ErrUnavailable, http.StatusBadRequest, "不支持的音源: "+tr.Source)
	}
	var logFn provider.LogFunc
	if s.deps.Logs != nil {
		logFn = s.deps.Logs.Push
	}
	rc := provider.RequestContext{
		Config:             snap,
		NoCache:            refresh,
		TaskID:             tr.PublicID,
		LogFunc:            logFn,
		AllowExternalWrite: allowExternalWrite,
	}
	resolved, err := prov.Resolve(provider.WithRequestContext(ctx, rc), tr, model.QualityPolicy{AllowPreview: true})
	if err != nil {
		if s.deps.Logs != nil {
			s.deps.Logs.Push("warning", fmt.Sprintf("解析曲目失败：《%s》(%s): %v", tr.Title, tr.Source, err), "playback", tr.PublicID)
		}
		return nil, err
	}
	hasPlayableURL := false
	for _, rm := range resolved {
		if strings.TrimSpace(rm.URL) != "" {
			hasPlayableURL = true
			break
		}
	}
	if hasPlayableURL {
		s.resolveMu.Lock()
		if len(s.resolveCache) > 256 {
			now := time.Now()
			for k, v := range s.resolveCache {
				if now.After(v.ExpiresAt) {
					delete(s.resolveCache, k)
				}
			}
		}
		copied := make([]model.ResolvedMedia, len(resolved))
		copy(copied, resolved)
		s.resolveCache[cacheKey] = resolvedCacheEntry{
			Media:     copied,
			ExpiresAt: time.Now().Add(resolveCacheTTL),
		}
		s.resolveMu.Unlock()
	}
	return resolved, nil
}

func (s *Server) buildResolvedTrackDTO(ctx context.Context, sid string, refresh bool, allowExternalWrite bool) (model.TrackDTO, error) {
	if strings.HasPrefix(sid, "navidrome-") {
		if !refresh {
			if dto, ok, err := s.loadTrackDTO(ctx, sid); err == nil && ok && dto.Playback.State == "ready" && dto.Playback.URL != nil && *dto.Playback.URL != "" {
				return dto, nil
			}
		}
		if s.deps.Navidrome != nil {
			navID := strings.TrimPrefix(sid, "navidrome-")
			dto, err := s.deps.Navidrome.GetSong(ctx, navID)
			if err != nil {
				return model.TrackDTO{}, err
			}
			return dto, nil
		}
	}
	tr, asset, found, err := s.loadDomainTrack(ctx, sid)
	if err != nil {
		return model.TrackDTO{}, err
	}
	if !found {
		return model.TrackDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "曲目不存在")
	}
	if asset != nil {
		return s.localTrackDTOFromAsset(*asset), nil
	}
	if localAbs, _, ok := s.findDownloadedFileForTrack(ctx, sid); ok {
		info, statErr := os.Stat(localAbs)
		if statErr == nil {
			ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(localAbs)), ".")
			sz := info.Size()
			audioURL := "/api/stream/" + url.PathEscape(sid)
			detail := "已下载本地音频"
			dto := model.TrackDTOFromDomain(tr, model.PlaybackDTO{
				State:       "ready",
				URL:         &audioURL,
				Codec:       &ext,
				Detail:      &detail,
				PreviewOnly: false,
			})
			dto.SizeBytes = &sz
			return dto, nil
		}
	}

	resolved, err := s.resolveMediaCached(ctx, sid, tr, refresh, allowExternalWrite)
	if err != nil {
		return model.TrackDTO{}, err
	}
	tr.PublicID = sid
	model.ApplyResolvedMedia(&tr, resolved)
	playback := model.PlaybackDTOFromResolvedTrack(tr, "")
	dto := model.TrackDTOFromDomain(tr, playback)
	if s.deps.Store != nil {
		_ = s.storeTrackDTO(ctx, dto)
	}
	return dto, nil
}

func (s *Server) storeTrackDTO(ctx context.Context, dto model.TrackDTO) error {
	if s.deps.Store == nil || dto.ID == "" {
		return nil
	}
	raw, err := json.Marshal(dto)
	if err != nil {
		return err
	}
	_, err = s.deps.Store.PutDurableRecord(ctx, "track_dto", dto.ID, string(raw), 0)
	return err
}

func (s *Server) loadTrackDTO(ctx context.Context, sid string) (model.TrackDTO, bool, error) {
	if s.deps.Store == nil {
		return model.TrackDTO{}, false, nil
	}
	if rec, ok, err := s.deps.Store.GetDurableRecord(ctx, "track_dto", sid); err == nil && ok {
		var dto model.TrackDTO
		if json.Unmarshal([]byte(rec.BodyJSON), &dto) == nil && dto.ID != "" {
			return dto, true, nil
		}
	}
	tr, asset, found, err := s.loadDomainTrack(ctx, sid)
	if err != nil || !found {
		return model.TrackDTO{}, false, err
	}
	if asset != nil {
		return s.localTrackDTOFromAsset(*asset), true, nil
	}
	return model.TrackDTOFromDomain(tr, search.PendingTrackPlayback()), true, nil
}

func (s *Server) handleTrackPlayback(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	dto, err := s.buildResolvedTrackDTO(r.Context(), sid, false, !actorFromRequest(r).ReadOnlyAI)
	if err != nil {
		s.deps.Logs.Push("error", redactTextURLs("准备试听失败: "+err.Error()), "playback", sid)
		writeDomainError(w, err)
		return
	}
	codec := ""
	if dto.Playback.Codec != nil {
		codec = *dto.Playback.Codec
	}
	s.deps.Logs.Push("info", fmt.Sprintf("准备试听：%s · %s · %s · 状态 %s", dto.Title, dto.Source, codec, dto.Playback.State), "playback", sid)
	writeJSON(w, http.StatusOK, dto)
}

func (s *Server) handleTrackVersions(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	dto, err := s.buildResolvedTrackDTO(r.Context(), sid, true, !actorFromRequest(r).ReadOnlyAI)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, dto)
}

func cleanLyricsPayload(raw string) string {
	txt := strings.TrimSpace(raw)
	switch strings.ToLower(txt) {
	case "", "null", "none":
		return ""
	default:
		return txt
	}
}

func extractLyricsFromResolved(items []model.ResolvedMedia) string {
	for _, rm := range items {
		if txt := cleanLyricsPayload(rm.Lyrics); txt != "" {
			return txt
		}
	}
	return ""
}

func (s *Server) readSafeSidecarLyrics(absAudioPath string) string {
	if s.deps.Files == nil {
		return ""
	}
	lrcPath := strings.TrimSuffix(absAudioPath, filepath.Ext(absAudioPath)) + ".lrc"
	vp, err := files.ValidatePath(s.deps.Files.Roots(), lrcPath, files.ValidateOptions{MustExist: true})
	if err != nil {
		return ""
	}
	f, err := os.Open(vp.AbsPath)
	if err != nil {
		return ""
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, (2<<20)+1))
	if err != nil || len(raw) > (2<<20) {
		return ""
	}
	return string(raw)
}

func (s *Server) handleTrackLyrics(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	if strings.HasPrefix(sid, "navidrome-") && s.deps.Navidrome != nil {
		navID := strings.TrimPrefix(sid, "navidrome-")
		artist, title := "", ""
		if dto, ok, err := s.loadTrackDTO(r.Context(), sid); err == nil && ok {
			artist = dto.Artist
			title = dto.Title
		}
		txt := cleanLyricsPayload(s.deps.Navidrome.GetLyrics(r.Context(), navID, artist, title))
		writeJSON(w, http.StatusOK, map[string]any{"text": txt})
		return
	}
	tr, asset, found, err := s.loadDomainTrack(r.Context(), sid)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if found && asset != nil {
		if abs, aErr := s.resolveAssetAbsPath(*asset); aErr == nil {
			var release func()
			if s.deps.Files != nil {
				if rel, lErr := s.deps.Files.Locks().AcquireRead(asset.ID); lErr == nil {
					release = rel
				}
			}
			if release != nil {
				defer release()
			}
			if s.deps.Tags != nil {
				if td, tErr := s.deps.Tags.ReadMetadata(r.Context(), abs); tErr == nil && td.Lyrics != "" {
					writeJSON(w, http.StatusOK, map[string]any{"text": td.Lyrics})
					return
				}
			}
			if lrcTxt := s.readSafeSidecarLyrics(abs); lrcTxt != "" {
				writeJSON(w, http.StatusOK, map[string]any{"text": lrcTxt})
				return
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{"text": ""})
		return
	}
	if localAbs, assetID, ok := s.findDownloadedFileForTrack(r.Context(), sid); ok {
		var release func()
		if s.deps.Files != nil && assetID != "" {
			if rel, lErr := s.deps.Files.Locks().AcquireRead(assetID); lErr == nil {
				release = rel
			}
		}
		if release != nil {
			defer release()
		}
		if s.deps.Tags != nil {
			if td, tErr := s.deps.Tags.ReadMetadata(r.Context(), localAbs); tErr == nil && td.Lyrics != "" {
				writeJSON(w, http.StatusOK, map[string]any{"text": td.Lyrics})
				return
			}
		}
		if lrcTxt := s.readSafeSidecarLyrics(localAbs); lrcTxt != "" {
			writeJSON(w, http.StatusOK, map[string]any{"text": lrcTxt})
			return
		}
	}
	if !found {
		writeJSON(w, http.StatusOK, map[string]any{"text": ""})
		return
	}
	if txt := cleanLyricsPayload(tr.Lyrics); txt != "" {
		writeJSON(w, http.StatusOK, map[string]any{"text": txt})
		return
	}
	if s.deps.Search != nil {
		snap := s.currentSnapshot()
		if cached, ok := s.deps.Search.CachedResolved(sid, snap.Revision); ok {
			if txt := extractLyricsFromResolved(cached); txt != "" {
				writeJSON(w, http.StatusOK, map[string]any{"text": txt})
				return
			}
		}
	}
	if strings.TrimSpace(tr.SourceID) != "" && s.deps.Providers != nil {
		// Prefer the provider's dedicated lyrics capability; it never triggers heavy audio resolution.
		if txt := s.fetchProviderLyrics(r.Context(), sid, tr); txt != "" {
			writeJSON(w, http.StatusOK, map[string]any{"text": txt})
			return
		}
		if resolved, rErr := s.resolveMediaCached(r.Context(), sid, tr, false, !actorFromRequest(r).ReadOnlyAI); rErr == nil {
			if txt := extractLyricsFromResolved(resolved); txt != "" {
				writeJSON(w, http.StatusOK, map[string]any{"text": txt})
				return
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"text": ""})
}

// fetchProviderLyrics asks the track's provider for lyrics through the optional
// provider.LyricsProvider capability. It returns "" when unsupported, empty, or failed.
func (s *Server) fetchProviderLyrics(ctx context.Context, sid string, tr model.ProviderTrack) string {
	if s.deps.Providers == nil {
		return ""
	}
	prov, ok := s.deps.Providers.Get(tr.RootSource)
	if !ok {
		prov, ok = s.deps.Providers.Get(tr.Source)
	}
	if !ok {
		return ""
	}
	lp, ok := prov.(provider.LyricsProvider)
	if !ok {
		return ""
	}
	var logFn provider.LogFunc
	if s.deps.Logs != nil {
		logFn = s.deps.Logs.Push
	}
	lrcCtx := provider.WithRequestContext(ctx, provider.RequestContext{
		Config:  s.currentSnapshot(),
		TaskID:  sid,
		LogFunc: logFn,
	})
	txt, err := lp.Lyrics(lrcCtx, tr)
	if err != nil {
		if s.deps.Logs != nil {
			s.deps.Logs.Push("warning", fmt.Sprintf("音源歌词获取失败 (%s): %v", prov.ID(), err), "playback", sid)
		}
		return ""
	}
	return cleanLyricsPayload(txt)
}

func (s *Server) resolveTrackEpisodes(ctx context.Context, sid string) ([]model.TrackDTO, error) {
	tr, _, found, err := s.loadDomainTrack(ctx, sid)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "曲目不存在")
	}

	if s.deps.Store != nil {
		if ref, _, rOK, rErr := s.deps.Store.GetPublicRef(ctx, sid); rErr == nil && rOK && (ref.Kind == "track" || ref.Kind == "candidate") {
			if rec, _, gOK, gErr := s.deps.Store.GetProviderTrack(ctx, ref.TargetID); gErr == nil && gOK {
				var meta struct {
					EpisodeIDs []string `json:"episode_ids"`
				}
				if json.Unmarshal([]byte(rec.MetadataJSON), &meta) == nil && len(meta.EpisodeIDs) > 0 {
					out := make([]model.TrackDTO, 0, len(meta.EpisodeIDs))
					for _, epID := range meta.EpisodeIDs {
						if dto, dOK, dErr := s.loadTrackDTO(ctx, epID); dErr == nil && dOK {
							out = append(out, dto)
						}
					}
					if len(out) > 0 {
						return out, nil
					}
				}
			}
		}
	}

	if s.deps.Providers == nil || strings.TrimSpace(tr.SourceID) == "" {
		return []model.TrackDTO{}, nil
	}
	prov, ok := s.deps.Providers.Get(firstNonEmpty(tr.RootSource, tr.Source))
	if !ok {
		return []model.TrackDTO{}, nil
	}
	epProv, ok := prov.(provider.EpisodeProvider)
	if !ok {
		return []model.TrackDTO{}, nil
	}

	snap := s.currentSnapshot()
	var logFn provider.LogFunc
	if s.deps.Logs != nil {
		logFn = s.deps.Logs.Push
	}
	epCtx := provider.WithRequestContext(ctx, provider.RequestContext{
		Config:  snap,
		TaskID:  sid,
		LogFunc: logFn,
	})
	rawEpisodes, err := epProv.Episodes(epCtx, tr)
	if err != nil {
		return nil, err
	}
	if len(rawEpisodes) == 0 {
		return []model.TrackDTO{}, nil
	}

	out := make([]model.TrackDTO, 0, len(rawEpisodes))
	epIDs := make([]string, 0, len(rawEpisodes))
	for _, ep := range rawEpisodes {
		if ep.Source == "" {
			ep.Source = tr.Source
		}
		if ep.RootSource == "" {
			ep.RootSource = firstNonEmpty(tr.RootSource, prov.EngineName())
		}
		if ep.PublicID == "" {
			ep.PublicID = deterministicCandidateID(firstNonEmpty(ep.RootSource, ep.Source), ep.SourceID, snap.Revision)
		}
		if s.deps.Store != nil {
			metaBytes, _ := json.Marshal(map[string]any{
				"cover":      ep.Cover,
				"size_bytes": ep.SizeBytes,
				"size_label": ep.SizeLabel,
				"episodes":   ep.Episodes,
				"lyrics":     ep.Lyrics,
			})
			trID, uErr := s.deps.Store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
				Source:       ep.Source,
				Locator:      ep.SourceID,
				RootSource:   ep.RootSource,
				Title:        ep.Title,
				Artist:       ep.Artist,
				Album:        ep.Album,
				DurationMS:   int64(ep.Duration * 1000),
				MetadataJSON: string(metaBytes),
			}, ep.Variants)
			if uErr != nil {
				return nil, uErr
			}
			_ = s.deps.Store.PutPublicRef(ctx, model.PublicRef{
				PublicID: ep.PublicID,
				Kind:     "candidate",
				TargetID: trID,
				Source:   ep.Source,
				SourceID: ep.SourceID,
			}, "")
		}
		playback := model.PlaybackDTOFromResolvedTrack(ep, "")
		if len(ep.Variants) == 0 {
			playback = search.PendingTrackPlayback()
		}
		dto := model.TrackDTOFromDomain(ep, playback)
		if s.deps.Store != nil {
			_ = s.storeTrackDTO(ctx, dto)
		}
		epIDs = append(epIDs, dto.ID)
		out = append(out, dto)
	}
	if s.deps.Store != nil && len(epIDs) > 0 {
		if ref, _, rOK, rErr := s.deps.Store.GetPublicRef(ctx, sid); rErr == nil && rOK && (ref.Kind == "track" || ref.Kind == "candidate") {
			metaBytes, _ := json.Marshal(map[string]any{
				"cover":       tr.Cover,
				"size_bytes":  tr.SizeBytes,
				"size_label":  tr.SizeLabel,
				"episodes":    len(epIDs),
				"episode_ids": epIDs,
				"lyrics":      tr.Lyrics,
			})
			_, _ = s.deps.Store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
				ID:           ref.TargetID,
				Source:       tr.Source,
				Locator:      tr.SourceID,
				RootSource:   tr.RootSource,
				Title:        tr.Title,
				Artist:       tr.Artist,
				Album:        tr.Album,
				DurationMS:   int64(tr.Duration * 1000),
				MetadataJSON: string(metaBytes),
			}, tr.Variants)
		}
	}
	return out, nil
}

func (s *Server) handleTrackEpisodes(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	episodes, err := s.resolveTrackEpisodes(r.Context(), sid)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, episodes)
}

func isRejectedStreamContentType(ct string) bool {
	lower := strings.ToLower(strings.TrimSpace(strings.Split(ct, ";")[0]))
	switch lower {
	case "text/html", "application/json", "application/xml", "text/xml":
		return true
	default:
		return false
	}
}

func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	tr, asset, found, err := s.loadDomainTrack(r.Context(), sid)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "曲目不存在")
		return
	}
	if asset != nil {
		abs, aErr := s.resolveAssetAbsPath(*asset)
		if aErr != nil {
			writeDomainError(w, aErr)
			return
		}
		s.serveLocalPath(w, r, abs, asset.ID, "inline")
		return
	}
	if localAbs, assetID, ok := s.findDownloadedFileForTrack(r.Context(), sid); ok {
		s.serveLocalPath(w, r, localAbs, assetID, "inline")
		return
	}
	if s.deps.Transport == nil {
		writeError(w, http.StatusServiceUnavailable, "网络传输层未初始化")
		return
	}

	snap := s.currentSnapshot()
	for attempt := 0; attempt < 2; attempt++ {
		refresh := attempt > 0
		resolved, rErr := s.resolveMediaCached(r.Context(), sid, tr, refresh, !actorFromRequest(r).ReadOnlyAI)
		if rErr != nil {
			writeDomainError(w, rErr)
			return
		}
		var target *model.ResolvedMedia
		for i := range resolved {
			if strings.HasPrefix(resolved[i].URL, "http://") || strings.HasPrefix(resolved[i].URL, "https://") {
				target = &resolved[i]
				break
			}
		}
		if target == nil {
			writeError(w, http.StatusBadRequest, "该曲目暂不支持在线流式试听")
			return
		}
		upReq, reqErr := http.NewRequestWithContext(r.Context(), http.MethodGet, target.URL, nil)
		if reqErr != nil {
			writeError(w, http.StatusBadGateway, "无效的音频流地址")
			return
		}
		for k, v := range target.Headers {
			if strings.TrimSpace(k) == "" || strings.TrimSpace(v) == "" {
				continue
			}
			upReq.Header.Set(k, v)
		}
		if upReq.Header.Get("User-Agent") == "" {
			upReq.Header.Set("User-Agent", provider.DefaultUserAgent)
		}
		if len(target.Cookies) > 0 {
			upReq.Header.Set("Cookie", provider.FormatCookieHeader(target.Cookies))
		}
		if rng := r.Header.Get("Range"); rng != "" {
			upReq.Header.Set("Range", rng)
		}
		if ifRng := r.Header.Get("If-Range"); ifRng != "" {
			upReq.Header.Set("If-Range", ifRng)
		}
		upReq.Header.Set("Accept-Encoding", "identity")

		rc := provider.RequestContext{Config: snap}
		proxyURL, _, account := provider.SourceRequestOptions(rc, firstNonEmpty(tr.RootSource, tr.Source))
		resp, doErr := s.deps.Transport.Do(r.Context(), upReq, transport.RequestOptions{
			Domain:              transport.DomainPublicMedia,
			ConfigRevision:      snap.Revision,
			ProxyURL:            proxyURL,
			Account:             account,
			MaxResponseBytes:    -1,
			Idempotent:          true,
			AllowHTTPSDowngrade: true,
		})
		if doErr != nil {
			if attempt == 0 {
				continue
			}
			writeDomainError(w, doErr)
			return
		}

		if (resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusGone || resp.StatusCode == http.StatusBadGateway) && attempt == 0 {
			_ = resp.Body.Close()
			continue
		}
		ct := resp.Header.Get("Content-Type")
		if isRejectedStreamContentType(ct) {
			_ = resp.Body.Close()
			if attempt == 0 {
				continue
			}
			writeError(w, http.StatusBadGateway, "音源返回无效媒体内容")
			return
		}
		for _, h := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
			if val := resp.Header.Get(h); val != "" {
				w.Header().Set(h, val)
			}
		}
		if w.Header().Get("Content-Type") == "" {
			w.Header().Set("Content-Type", "application/octet-stream")
		}
		w.WriteHeader(resp.StatusCode)
		if r.Method != http.MethodHead {
			bufPtr := streamCopyBufPool.Get().(*[]byte)
			_, _ = io.CopyBuffer(w, resp.Body, *bufPtr)
			streamCopyBufPool.Put(bufPtr)
		}
		_ = resp.Body.Close()
		return
	}
}

func (s *Server) handleFavorites(w http.ResponseWriter, r *http.Request) {
	if s.deps.Store == nil {
		writeJSON(w, http.StatusOK, []model.TrackDTO{})
		return
	}
	recs, err := s.deps.Store.ListDurableRecords(r.Context(), durableNamespaceFavorite, 5000)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	out := make([]model.TrackDTO, 0, len(recs))
	for _, rec := range recs {
		var dto model.TrackDTO
		if json.Unmarshal([]byte(rec.BodyJSON), &dto) == nil {
			out = append(out, dto)
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleFavoriteAdd(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	dto, found, err := s.loadTrackDTO(r.Context(), sid)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "曲目不存在")
		return
	}
	raw, _ := json.Marshal(dto)
	if _, err := s.deps.Store.PutDurableRecord(r.Context(), durableNamespaceFavorite, sid, string(raw), 0); err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", fmt.Sprintf("已收藏曲目：%s - %s", dto.Title, dto.Artist), "playlist", "")
	writeJSON(w, http.StatusOK, map[string]any{"saved": true})
}

func (s *Server) handleFavoriteRemove(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	if s.deps.Store != nil {
		if err := s.deps.Store.DeleteDurableRecord(r.Context(), durableNamespaceFavorite, sid); err != nil {
			writeDomainError(w, err)
			return
		}
	}
	s.pushLog("info", "已取消收藏曲目", "playlist", sid)
	writeJSON(w, http.StatusOK, map[string]any{"saved": true})
}

func (s *Server) handlePlaylistRead(w http.ResponseWriter, r *http.Request) {
	if s.deps.Store == nil {
		writeJSON(w, http.StatusOK, []model.TrackDTO{})
		return
	}
	rec, ok, err := s.deps.Store.GetDurableRecord(r.Context(), durableNamespacePlaylist, mainPlaylistID)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok || strings.TrimSpace(rec.BodyJSON) == "" {
		writeJSON(w, http.StatusOK, []model.TrackDTO{})
		return
	}
	var list []model.TrackDTO
	if err := json.Unmarshal([]byte(rec.BodyJSON), &list); err != nil {
		writeJSON(w, http.StatusOK, []model.TrackDTO{})
		return
	}
	if list == nil {
		list = []model.TrackDTO{}
	}
	writeJSON(w, http.StatusOK, list)
}

func (s *Server) handlePlaylistWrite(w http.ResponseWriter, r *http.Request) {
	var ids []string
	if !decodeJSON(w, r, s.defaultJSONLimit(), &ids) {
		return
	}
	if len(ids) > 3000 {
		writeError(w, http.StatusBadRequest, "播放列表最多 3000 首")
		return
	}
	tracks := make([]model.TrackDTO, 0, len(ids))
	for _, sid := range ids {
		if dto, found, err := s.loadTrackDTO(r.Context(), sid); err == nil && found {
			tracks = append(tracks, dto)
		}
	}
	if s.deps.Store != nil {
		raw, _ := json.Marshal(tracks)
		if _, err := s.deps.Store.PutDurableRecord(r.Context(), durableNamespacePlaylist, mainPlaylistID, string(raw), 0); err != nil {
			writeDomainError(w, err)
			return
		}
	}
	s.pushLog("info", fmt.Sprintf("已更新待播列表（共 %d 首）", len(tracks)), "playlist", "")
	writeJSON(w, http.StatusOK, map[string]any{"saved": true})
}

type playlistImportRequest struct {
	Source string `json:"source"`
	ID     string `json:"id"`
	Name   string `json:"name"`
}

func (s *Server) handlePlaylistImport(w http.ResponseWriter, r *http.Request) {
	var req playlistImportRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	locator := strings.TrimSpace(req.ID)
	if locator == "" {
		writeError(w, http.StatusBadRequest, "请输入歌单链接或 ID")
		return
	}
	if !strings.HasPrefix(locator, "http://") && !strings.HasPrefix(locator, "https://") && !playlistSlugRe.MatchString(locator) {
		writeError(w, http.StatusBadRequest, "请输入有效的歌单链接或标识")
		return
	}
	if s.deps.Providers == nil {
		writeError(w, http.StatusServiceUnavailable, "音源服务未初始化")
		return
	}
	prov, ok := s.deps.Providers.Get(req.Source)
	if !ok {
		writeError(w, http.StatusBadRequest, "不支持的音源")
		return
	}
	pp, ok := prov.(model.PlaylistProvider)
	if !ok || !prov.Capabilities().Playlist {
		writeError(w, http.StatusBadRequest, "该音源暂不支持导入歌单")
		return
	}

	snap := s.currentSnapshot()
	var plLogFn provider.LogFunc
	if s.deps.Logs != nil {
		plLogFn = s.deps.Logs.Push
		s.deps.Logs.Push("info", fmt.Sprintf("开始导入歌单：音源=%s", prov.ID()), "playlist", req.Source)
	}
	rc := provider.RequestContext{Config: snap, TaskID: req.Source, LogFunc: plLogFn}
	ctx := provider.WithRequestContext(r.Context(), rc)
	var imported []model.TrackDTO
	var refs []string
	seen := make(map[string]bool)

	summary, err := pp.Playlist(ctx, locator, func(pt model.ProviderTrack) error {
		if pt.PublicID == "" {
			pt.PublicID = deterministicCandidateID(firstNonEmpty(pt.RootSource, prov.EngineName(), pt.Source), pt.SourceID, snap.Revision)
		}
		if seen[pt.PublicID] {
			return nil
		}
		seen[pt.PublicID] = true
		if s.deps.Store != nil {
			metaBytes, _ := json.Marshal(map[string]any{
				"cover":      pt.Cover,
				"size_bytes": pt.SizeBytes,
				"size_label": pt.SizeLabel,
				"episodes":   pt.Episodes,
			})
			trID, uErr := s.deps.Store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
				Source:       pt.Source,
				Locator:      pt.SourceID,
				RootSource:   firstNonEmpty(pt.RootSource, prov.EngineName()),
				Title:        pt.Title,
				Artist:       pt.Artist,
				Album:        pt.Album,
				DurationMS:   int64(pt.Duration * 1000),
				MetadataJSON: string(metaBytes),
			}, pt.Variants)
			if uErr != nil {
				return uErr
			}
			if pErr := s.deps.Store.PutPublicRef(ctx, model.PublicRef{
				PublicID: pt.PublicID,
				Kind:     "candidate",
				TargetID: trID,
				Source:   pt.Source,
				SourceID: pt.SourceID,
			}, ""); pErr != nil {
				return pErr
			}
		}
		dto := model.TrackDTOFromDomain(pt, search.PendingTrackPlayback())
		_ = s.storeTrackDTO(ctx, dto)
		imported = append(imported, dto)
		refs = append(refs, pt.PublicID)
		return nil
	})
	if err != nil {
		if s.deps.Logs != nil {
			s.deps.Logs.Push("error", fmt.Sprintf("导入歌单失败（%s）：%v", prov.ID(), err), "playlist", req.Source)
		}
		writeDomainError(w, err)
		return
	}
	if len(imported) == 0 && len(summary.Issues) == 0 {
		if s.deps.Logs != nil {
			s.deps.Logs.Push("warning", fmt.Sprintf("导入歌单未解析到曲目（%s）", prov.ID()), "playlist", req.Source)
		}
		writeError(w, http.StatusUnprocessableEntity, "未解析到曲目，未创建歌单。请检查歌单链接、公开状态和音源配置，更多信息可查看运行日志。")
		return
	}
	if s.deps.Logs != nil {
		s.deps.Logs.Push("info", fmt.Sprintf("导入歌单完成（%s）：成功 %d 首，问题 %d 首", prov.ID(), len(imported), len(summary.Issues)), "playlist", req.Source)
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = strings.TrimSpace(summary.Name)
	}
	if name == "" {
		name = fmt.Sprintf("%s歌单", prov.ID())
	}
	detail := fmt.Sprintf("成功导入 %d 首", len(imported))
	if len(summary.Issues) > 0 {
		detail += fmt.Sprintf("，%d 首未获取到可用资源，详见未导入曲目。", len(summary.Issues))
	} else {
		detail += "。"
	}
	actor := actorFromRequest(r)
	opID := model.NewUUID()
	issues := summary.Issues
	if issues == nil {
		issues = []model.PlaylistIssueDTO{}
	}
	if imported == nil {
		imported = []model.TrackDTO{}
	}
	issuesBytes, _ := json.Marshal(issues)
	var colDTO model.SavedPlaylistDTO
	if s.deps.Store != nil {
		col, cErr := s.deps.Store.CreateCollection(ctx, store.CreateCollectionParams{
			ID:        opID,
			Name:      name,
			Kind:      "playlist",
			OwnerKey:  actor.Key(),
			Source:    prov.ID(),
			SourceURL: locator,
			Detail:    detail,
			TaskID:    opID,
		})
		if cErr != nil {
			writeDomainError(w, cErr)
			return
		}
		if len(refs) > 0 {
			rev, _ := strconv.ParseInt(col.Revision, 10, 64)
			if _, aErr := s.deps.Store.AddCollectionItems(ctx, col.ID, refs, rev); aErr != nil {
				writeDomainError(w, aErr)
				return
			}
		}
		if len(issues) > 0 {
			_, _ = s.deps.Store.PutDurableRecord(ctx, durableNamespacePlaylistIssues, col.ID, string(issuesBytes), 0)
		}
		colDTO = model.SavedPlaylistDTO{
			ID:        col.ID,
			Name:      col.Name,
			Source:    col.Source,
			SourceURL: col.SourceURL,
			CreatedAt: col.CreatedAt,
			Tracks:    imported,
			Issues:    issues,
			Detail:    col.Detail,
			TaskID:    col.ID,
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"playlist": colDTO,
		"tracks":   imported,
		"detail":   detail,
	})
}

func deterministicCandidateID(rootSource, sourceID, rev string) string {
	if sourceID == "" {
		return model.NewUUID()
	}
	sum := sha256.Sum256([]byte(fmt.Sprintf("cand:%s:%s:%s", rootSource, sourceID, rev)))
	return "cand-" + hex.EncodeToString(sum[:12])
}

func (s *Server) loadCollectionIssues(ctx context.Context, collectionID string) []model.PlaylistIssueDTO {
	if s.deps.Store == nil {
		return []model.PlaylistIssueDTO{}
	}
	rec, ok, err := s.deps.Store.GetDurableRecord(ctx, durableNamespacePlaylistIssues, collectionID)
	if err != nil || !ok || strings.TrimSpace(rec.BodyJSON) == "" {
		return []model.PlaylistIssueDTO{}
	}
	var issues []model.PlaylistIssueDTO
	if json.Unmarshal([]byte(rec.BodyJSON), &issues) != nil || issues == nil {
		return []model.PlaylistIssueDTO{}
	}
	return issues
}

func (s *Server) buildSavedPlaylistDTO(ctx context.Context, col model.Collection, items []model.CollectionItem) model.SavedPlaylistDTO {
	tracks := make([]model.TrackDTO, 0, len(items))
	for _, it := range items {
		if dto, found, err := s.loadTrackDTO(ctx, it.PublicID); err == nil && found {
			tracks = append(tracks, dto)
		}
	}
	issues := s.loadCollectionIssues(ctx, col.ID)
	taskID := col.TaskID
	if taskID == "" {
		taskID = col.ID
	}
	return model.SavedPlaylistDTO{
		Revision:  col.Revision,
		Owner:     col.Owner,
		ID:        col.ID,
		Name:      col.Name,
		Source:    col.Source,
		SourceURL: col.SourceURL,
		CreatedAt: col.CreatedAt,
		Tracks:    tracks,
		Issues:    issues,
		Detail:    col.Detail,
		TaskID:    taskID,
	}
}

func (s *Server) handleCollections(w http.ResponseWriter, r *http.Request) {
	if s.deps.Store == nil {
		writeJSON(w, http.StatusOK, []model.PlaylistSummaryDTO{})
		return
	}
	cols, err := s.deps.Store.ListCollections(r.Context(), "", "playlist")
	if err != nil {
		writeDomainError(w, err)
		return
	}
	out := make([]model.PlaylistSummaryDTO, 0, len(cols))
	for _, col := range cols {
		_, items, ok, gErr := s.deps.Store.GetCollection(r.Context(), col.ID)
		if gErr != nil || !ok {
			continue
		}
		var cover *string
		for _, it := range items {
			if dto, found, _ := s.loadTrackDTO(r.Context(), it.PublicID); found && dto.Cover != nil && *dto.Cover != "" {
				cover = dto.Cover
				break
			}
		}
		issues := s.loadCollectionIssues(r.Context(), col.ID)
		out = append(out, model.PlaylistSummaryDTO{
			Revision:   col.Revision,
			Owner:      col.Owner,
			ID:         col.ID,
			Name:       col.Name,
			Source:     col.Source,
			SourceURL:  col.SourceURL,
			CreatedAt:  col.CreatedAt,
			Detail:     col.Detail,
			TaskID:     col.ID,
			TrackCount: len(items),
			IssueCount: len(issues),
			Cover:      cover,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

type playlistCreateRequest struct {
	Name     string   `json:"name"`
	TrackIDs []string `json:"track_ids"`
}

func (s *Server) handleCollectionCreate(w http.ResponseWriter, r *http.Request) {
	var req playlistCreateRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		writeError(w, http.StatusBadRequest, "请输入歌单名称")
		return
	}
	seen := make(map[string]bool)
	var validRefs []string
	var tracks []model.TrackDTO
	for _, tid := range req.TrackIDs {
		if seen[tid] {
			continue
		}
		seen[tid] = true
		if dto, found, err := s.loadTrackDTO(r.Context(), tid); err == nil && found {
			validRefs = append(validRefs, tid)
			tracks = append(tracks, dto)
		}
	}
	source := "custom"
	if len(tracks) > 0 && tracks[0].Source != "" {
		source = tracks[0].Source
	}
	detail := fmt.Sprintf("已创建歌单“%s”，共 %d 首曲目。", name, len(tracks))
	actor := actorFromRequest(r)
	col, err := s.deps.Store.CreateCollection(r.Context(), store.CreateCollectionParams{
		Name:     name,
		Kind:     "playlist",
		OwnerKey: actor.Key(),
		Source:   source,
		Detail:   detail,
	})
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if len(validRefs) > 0 {
		rev, _ := strconv.ParseInt(col.Revision, 10, 64)
		if _, aErr := s.deps.Store.AddCollectionItems(r.Context(), col.ID, validRefs, rev); aErr != nil {
			writeDomainError(w, aErr)
			return
		}
	}
	if tracks == nil {
		tracks = []model.TrackDTO{}
	}
	s.pushLog("info", detail, "playlist", col.ID)
	writeJSON(w, http.StatusOK, model.SavedPlaylistDTO{
		ID:        col.ID,
		Name:      col.Name,
		Source:    col.Source,
		SourceURL: col.SourceURL,
		CreatedAt: col.CreatedAt,
		Tracks:    tracks,
		Issues:    []model.PlaylistIssueDTO{},
		Detail:    col.Detail,
		TaskID:    col.ID,
	})
}

func (s *Server) handleCollectionRead(w http.ResponseWriter, r *http.Request) {
	ident := r.PathValue("ident")
	col, items, ok, err := s.deps.Store.GetCollection(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeError(w, http.StatusNotFound, "歌单不存在")
		return
	}
	writeJSON(w, http.StatusOK, s.buildSavedPlaylistDTO(r.Context(), col, items))
}

type playlistTracksRequest struct {
	TrackIDs []string `json:"track_ids"`
}

func (s *Server) handleCollectionAddTracks(w http.ResponseWriter, r *http.Request) {
	ident := r.PathValue("ident")
	var req playlistTracksRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	col, items, ok, err := s.deps.Store.GetCollection(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeError(w, http.StatusNotFound, "歌单不存在")
		return
	}
	existing := make(map[string]bool, len(items))
	for _, it := range items {
		existing[it.PublicID] = true
	}
	var toAdd []string
	for _, tid := range req.TrackIDs {
		if existing[tid] {
			continue
		}
		if _, found, lErr := s.loadTrackDTO(r.Context(), tid); lErr == nil && found {
			existing[tid] = true
			toAdd = append(toAdd, tid)
		}
	}
	if len(toAdd) > 0 && len(items)+len(toAdd) > 3000 {
		toAdd = toAdd[:3000-len(items)]
	}
	if len(toAdd) > 0 {
		rev, _ := strconv.ParseInt(col.Revision, 10, 64)
		if _, err := s.deps.Store.AddCollectionItems(r.Context(), ident, toAdd, rev); err != nil {
			writeDomainError(w, err)
			return
		}
	}
	col, items, _, err = s.deps.Store.GetCollection(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	dto := s.buildSavedPlaylistDTO(r.Context(), col, items)
	dto.Detail = fmt.Sprintf("已向歌单“%s”加入 %d 首曲目，共 %d 首。", col.Name, len(toAdd), len(dto.Tracks))
	s.pushLog("info", dto.Detail, "playlist", ident)
	writeJSON(w, http.StatusOK, dto)
}

type playlistNameRequest struct {
	Name string `json:"name"`
}

func (s *Server) handleCollectionRename(w http.ResponseWriter, r *http.Request) {
	ident := r.PathValue("ident")
	var req playlistNameRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		writeError(w, http.StatusBadRequest, "请输入歌单名称")
		return
	}
	col, _, ok, err := s.deps.Store.GetCollection(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeError(w, http.StatusNotFound, "歌单不存在")
		return
	}
	rev, _ := strconv.ParseInt(col.Revision, 10, 64)
	if _, err := s.deps.Store.RenameCollection(r.Context(), ident, name, rev); err != nil {
		writeDomainError(w, err)
		return
	}
	col, items, _, err := s.deps.Store.GetCollection(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", fmt.Sprintf("已重命名歌单为“%s”", name), "playlist", ident)
	writeJSON(w, http.StatusOK, s.buildSavedPlaylistDTO(r.Context(), col, items))
}

func (s *Server) handleCollectionDelete(w http.ResponseWriter, r *http.Request) {
	ident := r.PathValue("ident")
	_, _, ok, err := s.deps.Store.GetCollection(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeError(w, http.StatusNotFound, "歌单不存在")
		return
	}
	if err := s.deps.Store.DeleteCollection(r.Context(), ident); err != nil {
		writeDomainError(w, err)
		return
	}
	if s.deps.Store != nil {
		_ = s.deps.Store.DeleteDurableRecord(r.Context(), durableNamespacePlaylistIssues, ident)
	}
	s.pushLog("info", "已删除自建歌单", "playlist", ident)
	writeJSON(w, http.StatusOK, map[string]any{"deleted": true})
}

func (s *Server) handleCollectionRemoveTrack(w http.ResponseWriter, r *http.Request) {
	ident := r.PathValue("ident")
	sid := r.PathValue("sid")
	col, _, ok, err := s.deps.Store.GetCollection(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeError(w, http.StatusNotFound, "歌单不存在")
		return
	}
	rev, _ := strconv.ParseInt(col.Revision, 10, 64)
	if _, err := s.deps.Store.RemoveCollectionItem(r.Context(), ident, sid, rev); err != nil {
		writeDomainError(w, err)
		return
	}
	col, items, _, err := s.deps.Store.GetCollection(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", fmt.Sprintf("已从歌单“%s”移除 1 首曲目", col.Name), "playlist", ident)
	writeJSON(w, http.StatusOK, s.buildSavedPlaylistDTO(r.Context(), col, items))
}

// Legacy search/resolve/validate/tasks endpoints.

func (s *Server) handleLegacySearch(w http.ResponseWriter, r *http.Request) {
	if s.deps.Search == nil {
		writeError(w, http.StatusServiceUnavailable, "搜索服务未初始化")
		return
	}
	var req search.CreateRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	jobID, err := s.deps.Search.CreateSearch(r.Context(), actor, s.currentSnapshot(), req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	sub, err := s.deps.Search.Subscribe(r.Context(), jobID, 0, "")
	if err != nil {
		writeDomainError(w, err)
		return
	}
	defer sub.Close()
	var results []model.TrackDTO
	for {
		ev, sErr := sub.Next(r.Context())
		if sErr != nil {
			break
		}
		if ev.Type == "track" {
			var dto model.TrackDTO
			if json.Unmarshal(ev.Data, &dto) == nil {
				results = append(results, dto)
			}
		}
	}
	if results == nil {
		results = []model.TrackDTO{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}

func (s *Server) handleLegacySongDetail(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	dto, found, err := s.loadTrackDTO(r.Context(), sid)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "歌曲不存在或已过期")
		return
	}
	writeJSON(w, http.StatusOK, dto)
}

type legacyResolveRequest struct {
	URL     string `json:"url"`
	Source  string `json:"source"`
	Title   string `json:"title"`
	Artist  string `json:"artist"`
	Keyword string `json:"keyword"`
}

func (s *Server) handleLegacyResolve(w http.ResponseWriter, r *http.Request) {
	var req legacyResolveRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	keyword := strings.TrimSpace(firstNonEmpty(req.Keyword, strings.TrimSpace(req.Title+" "+req.Artist), req.URL))
	if keyword == "" {
		writeError(w, http.StatusBadRequest, "缺少可用于重新搜索的关键词")
		return
	}
	if s.deps.Search == nil {
		writeError(w, http.StatusServiceUnavailable, "搜索服务未初始化")
		return
	}
	snap := s.currentSnapshot()
	var sources []string
	if src := strings.TrimSpace(req.Source); src != "" {
		if s.deps.Providers != nil {
			if p, ok := s.deps.Providers.Get(src); ok {
				sources = []string{p.ID()}
			} else {
				writeError(w, http.StatusBadRequest, "音源 "+src+" 未启用或不存在")
				return
			}
		} else {
			sources = []string{src}
		}
	}
	actor := actorFromRequest(r)
	jobID, err := s.deps.Search.CreateSearch(r.Context(), actor, snap, search.CreateRequest{
		Keyword: keyword,
		Sources: sources,
		Limit:   5,
	})
	if err != nil {
		writeDomainError(w, err)
		return
	}
	sub, err := s.deps.Search.Subscribe(r.Context(), jobID, 0, "")
	if err != nil {
		writeDomainError(w, err)
		return
	}
	defer sub.Close()

	var results []model.TrackDTO
	for {
		ev, sErr := sub.Next(r.Context())
		if sErr != nil {
			break
		}
		if ev.Type == "track" {
			var dto model.TrackDTO
			if json.Unmarshal(ev.Data, &dto) == nil && dto.ID != "" {
				results = append(results, dto)
			}
		} else if ev.Type == "done" {
			break
		}
	}
	if len(results) == 0 {
		writeError(w, http.StatusNotFound, "未能重新找到该曲目")
		return
	}
	best := results[0]
	wantTitle := strings.TrimSpace(req.Title)
	if wantTitle != "" {
		for _, cand := range results {
			if strings.TrimSpace(cand.Title) == wantTitle {
				best = cand
				break
			}
		}
	}
	writeJSON(w, http.StatusOK, best)
}

func (s *Server) handleLegacyEpisodes(w http.ResponseWriter, r *http.Request) {
	sid := r.PathValue("sid")
	episodes, err := s.resolveTrackEpisodes(r.Context(), sid)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": episodes})
}

func (s *Server) handleLegacyPlaylist(w http.ResponseWriter, r *http.Request) {
	s.handlePlaylistImport(w, r)
}

type validateSongsRequest struct {
	IDs []string `json:"ids"`
}

func (s *Server) handleLegacyValidateSongs(w http.ResponseWriter, r *http.Request) {
	var req validateSongsRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	seen := make(map[string]bool)
	invalid := make([]string, 0)
	for _, sid := range req.IDs {
		if seen[sid] {
			continue
		}
		seen[sid] = true
		_, found, err := s.loadTrackDTO(r.Context(), sid)
		if err != nil || !found {
			invalid = append(invalid, sid)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"invalid": invalid})
}

type legacyDownloadRequest struct {
	IDs []string `json:"ids"`
}

func (s *Server) handleLegacyDownload(w http.ResponseWriter, r *http.Request) {
	if s.deps.Download == nil {
		writeError(w, http.StatusServiceUnavailable, "下载服务未初始化")
		return
	}
	var req legacyDownloadRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	snap := s.currentSnapshot()
	seen := make(map[string]bool)
	var jobIDs []string
	var expired []string
	var songs []map[string]any
	for _, sid := range req.IDs {
		if seen[sid] {
			continue
		}
		seen[sid] = true
		dto, found, _ := s.loadTrackDTO(r.Context(), sid)
		if !found {
			expired = append(expired, sid)
			continue
		}
		qID := ""
		if len(dto.Qualities) > 0 {
			qID = dto.Qualities[0].ID
		}
		res, err := s.deps.Download.Enqueue(r.Context(), actor, snap, download.EnqueueRequest{
			PublicID:  sid,
			QualityID: qID,
		})
		if err != nil {
			continue
		}
		jobIDs = append(jobIDs, res.Download.ID)
		songs = append(songs, map[string]any{
			"id":        sid,
			"status":    "pending",
			"detail":    "",
			"progress":  0,
			"attempts":  0,
			"song_name": dto.Title,
			"singers":   dto.Artist,
			"source":    dto.Source,
		})
	}
	if len(jobIDs) == 0 {
		writeError(w, http.StatusNotFound, "所选歌曲结果均已过期，请重新解析后再下载")
		return
	}
	taskID := model.NewUUID()[:8]
	s.legacyMu.Lock()
	for len(s.legacyOrder) >= legacyTaskMaxEntries {
		oldest := s.legacyOrder[len(s.legacyOrder)-1]
		s.legacyOrder = s.legacyOrder[:len(s.legacyOrder)-1]
		delete(s.legacyTasks, oldest)
	}
	s.legacyTasks[taskID] = &legacyTaskEntry{
		ID:        taskID,
		Status:    "pending",
		Progress:  0,
		CreatedAt: time.Now().Format("15:04:05"),
		Songs:     songs,
		JobIDs:    jobIDs,
	}
	s.legacyOrder = append([]string{taskID}, s.legacyOrder...)
	s.legacyMu.Unlock()
	if expired == nil {
		expired = []string{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"task_id": taskID,
		"queued":  len(jobIDs),
		"expired": expired,
	})
}

func (s *Server) handleLegacyTaskRetry(w http.ResponseWriter, r *http.Request) {
	taskID := r.PathValue("task_id")
	s.legacyMu.Lock()
	task, ok := s.legacyTasks[taskID]
	if !ok {
		s.legacyMu.Unlock()
		writeError(w, http.StatusNotFound, "任务不存在或已被清除")
		return
	}
	jobIDs := append([]string(nil), task.JobIDs...)
	s.legacyMu.Unlock()
	retried := 0
	actor := actorFromRequest(r)
	snap := s.currentSnapshot()
	if s.deps.Download != nil {
		for _, jid := range jobIDs {
			if _, err := s.deps.Download.Retry(r.Context(), actor, jid, snap); err == nil {
				retried++
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"task_id": taskID, "retrying": retried})
}

func (s *Server) handleLegacyTasksDelete(w http.ResponseWriter, r *http.Request) {
	var req validateSongsRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	removed := 0
	s.legacyMu.Lock()
	for _, tid := range req.IDs {
		if _, ok := s.legacyTasks[tid]; ok {
			delete(s.legacyTasks, tid)
			removed++
		}
	}
	filtered := s.legacyOrder[:0]
	for _, tid := range s.legacyOrder {
		if _, ok := s.legacyTasks[tid]; ok {
			filtered = append(filtered, tid)
		}
	}
	s.legacyOrder = filtered
	s.legacyMu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"removed": removed, "skipped": 0})
}

func (s *Server) handleLegacyTasksClearFinished(w http.ResponseWriter, r *http.Request) {
	removed := 0
	s.legacyMu.Lock()
	filtered := s.legacyOrder[:0]
	for _, tid := range s.legacyOrder {
		t := s.legacyTasks[tid]
		if t != nil && (t.Status == "done" || t.Status == "failed" || t.Status == "partial") {
			delete(s.legacyTasks, tid)
			removed++
			continue
		}
		filtered = append(filtered, tid)
	}
	s.legacyOrder = filtered
	s.legacyMu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"removed": removed})
}

func (s *Server) handleLegacyTasks(w http.ResponseWriter, r *http.Request) {
	s.legacyMu.Lock()
	out := make([]*legacyTaskEntry, 0, len(s.legacyOrder))
	for _, tid := range s.legacyOrder {
		if len(out) >= 50 {
			break
		}
		if t, ok := s.legacyTasks[tid]; ok {
			out = append(out, t)
		}
	}
	s.legacyMu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"tasks": out})
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
