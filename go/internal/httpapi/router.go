package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/dockerupdate"
	"github.com/agonie0v0/melokeep/internal/model"
)

const (
	resolveCacheTTL      = 60 * time.Second
	playbackLogWindow    = 5 * time.Second
	playbackLogMaxKeys   = 128
	legacyTaskMaxEntries = 200
)

type resolvedCacheEntry struct {
	Media     []model.ResolvedMedia
	ExpiresAt time.Time
}

type playbackDedupeKey struct {
	TrackID string
	Event   string
	Stage   string
	Code    int
	Detail  string
}

type legacyTaskEntry struct {
	ID         string           `json:"id"`
	Status     string           `json:"status"`
	Progress   int              `json:"progress"`
	CreatedAt  string           `json:"created_at"`
	FinishedAt *string          `json:"finished_at"`
	Songs      []map[string]any `json:"songs"`
	JobIDs     []string         `json:"-"`
}

type Server struct {
	deps           Dependencies
	mux            *http.ServeMux
	routes         []RouteDef
	trustedProxies trustedProxyMatcher

	snapshotMu sync.RWMutex
	snapshot   config.Snapshot

	resolveMu    sync.Mutex
	resolveCache map[string]resolvedCacheEntry

	playbackMu     sync.Mutex
	playbackReport map[playbackDedupeKey]time.Time

	legacyMu    sync.Mutex
	legacyOrder []string
	legacyTasks map[string]*legacyTaskEntry

	updateMu           sync.Mutex
	updateCache        *model.SystemUpdateDTO
	updateCacheExpires time.Time
	selfUpdater        *dockerupdate.Manager

	prefsMu sync.Mutex
}

func NewServer(deps Dependencies) (*Server, error) {
	if deps.EngineVersion == "" {
		deps.EngineVersion = "1.0.0"
	}
	if deps.Platform == "" {
		if deps.IsWindows {
			deps.Platform = "win32"
		} else {
			deps.Platform = "linux"
		}
	}
	if deps.Logs == nil {
		deps.Logs = NewLogBuffer(800)
	}
	var tokenStore auth.DeviceTokenStore
	if deps.Store != nil {
		tokenStore = deps.Store
	}
	if deps.Sessions == nil {
		deps.Sessions = auth.NewSessionManagerWithStore(tokenStore)
	} else if tokenStore != nil {
		deps.Sessions.SetStoreIfNil(tokenStore)
	}
	if deps.RateLimiter == nil {
		deps.RateLimiter = auth.NewLoginRateLimiter()
	}
	if deps.Budgets == (config.ResourceBudgets{}) {
		deps.Budgets = config.DefaultResourceBudgets()
	}
	if deps.InitialSnapshot.Revision == "" {
		deps.InitialSnapshot = config.NewSnapshot(config.DefaultRuntimeConfig(), deps.Budgets)
	} else if deps.InitialSnapshot.Budgets == (config.ResourceBudgets{}) {
		deps.InitialSnapshot.Budgets = deps.Budgets
	}

	s := &Server{
		deps:           deps,
		mux:            http.NewServeMux(),
		trustedProxies: parseTrustedProxies(deps.TrustedProxies),
		snapshot:       deps.InitialSnapshot,
		resolveCache:   make(map[string]resolvedCacheEntry),
		playbackReport: make(map[playbackDedupeKey]time.Time),
		legacyTasks:    make(map[string]*legacyTaskEntry),
	}
	s.registerRoutes()
	return s, nil
}

func (s *Server) Routes() []RouteDef {
	out := make([]RouteDef, len(s.routes))
	copy(out, s.routes)
	return out
}

func (s *Server) currentSnapshot() config.Snapshot {
	s.snapshotMu.RLock()
	defer s.snapshotMu.RUnlock()
	return s.snapshot
}

func (s *Server) swapSnapshot(next config.Snapshot) config.Snapshot {
	s.snapshotMu.Lock()
	prev := s.snapshot
	s.snapshot = next
	s.snapshotMu.Unlock()
	if s.deps.Transport != nil && prev.Revision != "" && prev.Revision != next.Revision {
		s.deps.Transport.InvalidateRevision(prev.Revision)
	}
	return prev
}

func (s *Server) defaultJSONLimit() int64 {
	if s.deps.Budgets.DefaultJSONMaxBytes > 0 {
		return int64(s.deps.Budgets.DefaultJSONMaxBytes)
	}
	return 1 << 20
}

func (s *Server) imageJSONLimit() int64 {
	if s.deps.Budgets.ImageJSONMaxBytes > 0 {
		return int64(s.deps.Budgets.ImageJSONMaxBytes)
	}
	return 5 << 20
}

type memResponseWriter struct {
	header http.Header
	code   int
	body   bytes.Buffer
}

func (w *memResponseWriter) Header() http.Header {
	return w.header
}

func (w *memResponseWriter) WriteHeader(statusCode int) {
	if w.code == 0 {
		w.code = statusCode
	}
}

func (w *memResponseWriter) Write(p []byte) (int, error) {
	if w.code == 0 {
		w.code = http.StatusOK
	}
	return w.body.Write(p)
}

// DispatchOperationInProcess invokes the HTTP mux directly in memory without opening a network socket.
func (s *Server) DispatchOperationInProcess(ctx context.Context, actor auth.Actor, method, pathPattern string, payload map[string]any) (any, error) {
	if s == nil || s.mux == nil {
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "HTTP 路由服务未初始化")
	}
	pathParams, _ := payload["path_params"].(map[string]any)
	resolvedPath := pathPattern
	for {
		start := strings.IndexByte(resolvedPath, '{')
		if start < 0 {
			break
		}
		end := strings.IndexByte(resolvedPath[start:], '}')
		if end < 0 {
			break
		}
		name := resolvedPath[start+1 : start+end]
		var val string
		if pathParams != nil {
			if raw, ok := pathParams[name]; ok && raw != nil {
				val = strings.TrimSpace(fmt.Sprint(raw))
			}
		}
		if val == "" && payload != nil {
			if raw, ok := payload[name]; ok && raw != nil {
				val = strings.TrimSpace(fmt.Sprint(raw))
			}
		}
		if val == "" {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少路径参数: "+name)
		}
		resolvedPath = resolvedPath[:start] + url.PathEscape(val) + resolvedPath[start+end+1:]
	}

	q := url.Values{}
	if qMap, ok := payload["query"].(map[string]any); ok {
		for k, v := range qMap {
			if v != nil {
				q.Set(k, fmt.Sprint(v))
			}
		}
	} else if method == http.MethodGet || method == http.MethodDelete {
		for k, v := range payload {
			if v == nil || k == "path_params" || k == "body" || k == "query" {
				continue
			}
			if strings.Contains(pathPattern, "{"+k+"}") {
				continue
			}
			switch tv := v.(type) {
			case string, float64, int, int64, bool:
				q.Set(k, fmt.Sprint(tv))
			}
		}
	}
	targetURI := resolvedPath
	if encoded := q.Encode(); encoded != "" {
		targetURI += "?" + encoded
	}

	var bodyReader io.Reader
	hasBody := false
	if bObj, ok := payload["body"]; ok && bObj != nil {
		if raw, err := json.Marshal(bObj); err == nil {
			bodyReader = bytes.NewReader(raw)
			hasBody = true
		}
	} else if method != http.MethodGet && method != http.MethodHead && len(payload) > 0 {
		clean := make(map[string]any, len(payload))
		for k, v := range payload {
			if k == "path_params" || k == "query" || strings.Contains(pathPattern, "{"+k+"}") {
				continue
			}
			clean[k] = v
		}
		if len(clean) > 0 {
			if raw, err := json.Marshal(clean); err == nil {
				bodyReader = bytes.NewReader(raw)
				hasBody = true
			}
		}
	}

	req, err := http.NewRequestWithContext(withActor(ctx, actor), method, targetURI, bodyReader)
	if err != nil {
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "构造内部操作请求失败", err)
	}
	req.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	if hasBody {
		req.Header.Set("Content-Type", "application/json")
	}

	rec := &memResponseWriter{header: make(http.Header)}
	s.mux.ServeHTTP(rec, req)
	status := rec.code
	if status == 0 {
		status = http.StatusOK
	}
	raw := rec.body.Bytes()
	if status >= 400 {
		var errBody struct {
			Detail any `json:"detail"`
		}
		msg := strings.TrimSpace(string(raw))
		if json.Unmarshal(raw, &errBody) == nil && errBody.Detail != nil {
			msg = strings.TrimSpace(fmt.Sprint(errBody.Detail))
		}
		if msg == "" {
			msg = http.StatusText(status)
		}
		code := model.ErrInternal
		switch status {
		case http.StatusBadRequest, http.StatusUnprocessableEntity:
			code = model.ErrInvalidInput
		case http.StatusUnauthorized:
			code = model.ErrUnauthorized
		case http.StatusForbidden:
			code = model.ErrForbidden
		case http.StatusNotFound:
			code = model.ErrNotFound
		case http.StatusConflict:
			code = model.ErrConflict
		case http.StatusTooManyRequests:
			code = model.ErrRateLimited
		case http.StatusNotImplemented:
			code = model.ErrUnsupported
		case http.StatusServiceUnavailable:
			code = model.ErrUnavailable
		}
		return nil, model.NewError(code, status, msg)
	}
	if len(bytes.TrimSpace(raw)) == 0 {
		return map[string]any{"ok": true, "status": strconv.Itoa(status)}, nil
	}
	var out any
	if err := json.Unmarshal(raw, &out); err != nil {
		return string(raw), nil
	}
	return out, nil
}

func (s *Server) addRoute(method, path, endpoint string, handler http.HandlerFunc) {
	s.routes = append(s.routes, RouteDef{
		Method:   method,
		Path:     path,
		Endpoint: endpoint,
	})
	pattern := method + " " + path
	if method == http.MethodGet && path == "/" {
		s.mux.HandleFunc("GET /{$}", handler)
		return
	}
	s.mux.HandleFunc(pattern, handler)
}

func (s *Server) registerRoutes() {
	// Static & SPA routes.
	s.addRoute(http.MethodGet, "/", "engine_api.index", s.handleIndex)
	s.mux.HandleFunc("GET /assets/", s.handleAssets)
	s.mux.HandleFunc("GET /", s.handleStaticFallback)

	// Core, auth, config, logs & engine routes.
	s.addRoute(http.MethodPost, "/api/playback/events", "engine_api.playback_event", s.handlePlaybackEvent)
	s.addRoute(http.MethodGet, "/api/health", "engine_api.health", s.handleHealth)
	s.addRoute(http.MethodGet, "/api/auth/status", "engine_api.auth_status", s.handleAuthStatus)
	s.addRoute(http.MethodPost, "/api/auth/login", "legacy.app.login", s.handleLogin)
	s.addRoute(http.MethodPost, "/api/auth/logout", "legacy.app.logout", s.handleLogout)
	s.addRoute(http.MethodPost, "/api/auth/media-token", "engine_api.auth_media_token", s.handleAuthMediaToken)
	s.addRoute(http.MethodGet, "/api/auth/devices", "engine_api.auth_devices", s.handleAuthDevices)
	s.addRoute(http.MethodDelete, "/api/auth/devices/{ident}", "engine_api.auth_device_delete", s.handleAuthDeviceDelete)
	s.addRoute(http.MethodGet, "/api/meta", "legacy.app.meta", s.handleMeta)
	s.addRoute(http.MethodGet, "/api/config", "engine_api.read_config", s.handleReadConfig)
	s.addRoute(http.MethodPut, "/api/config", "engine_api.write_config", s.handleWriteConfig)
	s.addRoute(http.MethodGet, "/api/preferences", "engine_api.read_preferences", s.handleReadPreferences)
	s.addRoute(http.MethodPut, "/api/preferences", "engine_api.write_preferences", s.handleWritePreferences)
	s.addRoute(http.MethodGet, "/api/engine/config", "engine_api.read_engine_config", s.handleReadEngineConfig)
	s.addRoute(http.MethodPut, "/api/engine/config", "engine_api.write_engine_config", s.handleWriteEngineConfig)
	s.addRoute(http.MethodGet, "/api/engine/latest", "legacy.app.enginelatest", s.handleEngineLatest)
	s.addRoute(http.MethodPost, "/api/engine/update", "legacy.app.engineupdate", s.handleEngineUpdate)
	s.addRoute(http.MethodGet, "/api/engine/update", "legacy.app.engineupdatestatus", s.handleEngineUpdateStatus)
	s.addRoute(http.MethodGet, "/api/system/update", "engine_api.system_update", s.handleSystemUpdate)
	s.addRoute(http.MethodGet, "/api/system/update/status", "engine_api.system_update_status", s.handleSystemUpdateStatus)
	s.addRoute(http.MethodPost, "/api/system/update/apply", "engine_api.system_update_apply", s.handleSystemUpdateApply)
	s.addRoute(http.MethodPost, "/api/restart", "legacy.app.restartserver", s.handleRestart)
	s.addRoute(http.MethodPost, "/api/open-dir", "legacy.app.opendir", s.handleOpenDir)
	s.addRoute(http.MethodPost, "/api/cache/clear", "legacy.app.clearcache", s.handleCacheClear)
	s.addRoute(http.MethodGet, "/api/logs", "legacy.app.logs", s.handleLogs)
	s.addRoute(http.MethodPost, "/api/logs/clear", "legacy.app.clearlogs", s.handleLogsClear)

	// Search, tracks, stream, favorites & playlists.
	s.addRoute(http.MethodPost, "/api/search/jobs", "engine_api.search_start", s.handleSearchStart)
	s.addRoute(http.MethodDelete, "/api/search/jobs/{ident}", "engine_api.search_stop", s.handleSearchStop)
	s.addRoute(http.MethodGet, "/api/search/jobs/{ident}/events", "engine_api.search_events", s.handleSearchEvents)
	s.addRoute(http.MethodPost, "/api/tracks/{sid}/playback", "engine_api.playback", s.handleTrackPlayback)
	s.addRoute(http.MethodPost, "/api/tracks/{sid}/versions", "engine_api.versions", s.handleTrackVersions)
	s.addRoute(http.MethodGet, "/api/tracks/{sid}/lyrics", "engine_api.lyrics", s.handleTrackLyrics)
	s.addRoute(http.MethodGet, "/api/tracks/{sid}/episodes", "engine_api.episodes", s.handleTrackEpisodes)
	s.addRoute(http.MethodGet, "/api/stream/{sid}", "engine_api.stream", s.handleStream)

	s.addRoute(http.MethodGet, "/api/favorites", "engine_api.favorites", s.handleFavorites)
	s.addRoute(http.MethodPut, "/api/favorites/{sid}", "engine_api.favorite_add", s.handleFavoriteAdd)
	s.addRoute(http.MethodDelete, "/api/favorites/{sid}", "engine_api.favorite_remove", s.handleFavoriteRemove)
	s.addRoute(http.MethodGet, "/api/playlist", "engine_api.playlist_read", s.handlePlaylistRead)
	s.addRoute(http.MethodPut, "/api/playlist", "engine_api.playlist_write", s.handlePlaylistWrite)
	s.addRoute(http.MethodPost, "/api/playlist/import", "engine_api.playlist_import", s.handlePlaylistImport)
	s.addRoute(http.MethodGet, "/api/playlists", "engine_api.collections", s.handleCollections)
	s.addRoute(http.MethodPost, "/api/playlists", "engine_api.collection_create", s.handleCollectionCreate)
	s.addRoute(http.MethodGet, "/api/playlists/{ident}", "engine_api.collection_read", s.handleCollectionRead)
	s.addRoute(http.MethodPost, "/api/playlists/{ident}/tracks", "engine_api.collection_add_tracks", s.handleCollectionAddTracks)
	s.addRoute(http.MethodPatch, "/api/playlists/{ident}", "engine_api.collection_rename", s.handleCollectionRename)
	s.addRoute(http.MethodDelete, "/api/playlists/{ident}", "engine_api.collection_delete", s.handleCollectionDelete)
	s.addRoute(http.MethodDelete, "/api/playlists/{ident}/tracks/{sid}", "engine_api.collection_remove_track", s.handleCollectionRemoveTrack)

	// Legacy search/resolve/validate/tasks endpoints.
	s.addRoute(http.MethodPost, "/api/search", "legacy.app.search", s.handleLegacySearch)
	s.addRoute(http.MethodGet, "/api/song/{sid}", "legacy.app.songdetail", s.handleLegacySongDetail)
	s.addRoute(http.MethodPost, "/api/resolve", "legacy.app.resolve", s.handleLegacyResolve)
	s.addRoute(http.MethodGet, "/api/song/{sid}/episodes", "legacy.app.episodes", s.handleLegacyEpisodes)
	s.addRoute(http.MethodPost, "/api/playlist", "legacy.app.playlist", s.handleLegacyPlaylist)
	s.addRoute(http.MethodPost, "/api/songs/validate", "legacy.app.validatesongs", s.handleLegacyValidateSongs)
	s.addRoute(http.MethodPost, "/api/download", "legacy.app.download", s.handleLegacyDownload)
	s.addRoute(http.MethodPost, "/api/tasks/{task_id}/retry", "legacy.app.retrytask", s.handleLegacyTaskRetry)
	s.addRoute(http.MethodPost, "/api/tasks/delete", "legacy.app.deletetasks", s.handleLegacyTasksDelete)
	s.addRoute(http.MethodPost, "/api/tasks/clear-finished", "legacy.app.clearfinishedtasks", s.handleLegacyTasksClearFinished)
	s.addRoute(http.MethodGet, "/api/tasks", "legacy.app.tasks", s.handleLegacyTasks)

	// Downloads & files endpoints.
	s.addRoute(http.MethodGet, "/api/downloads", "engine_api.downloads", s.handleDownloads)
	s.addRoute(http.MethodPost, "/api/downloads", "engine_api.enqueue", s.handleDownloadEnqueue)
	s.addRoute(http.MethodPost, "/api/downloads/{ident}/{action}", "engine_api.control_download", s.handleDownloadControl)
	s.addRoute(http.MethodDelete, "/api/downloads/{ident}", "engine_api.remove_download", s.handleDownloadDelete)

	s.addRoute(http.MethodGet, "/api/files", "engine_api.files", s.handleFiles)
	s.addRoute(http.MethodGet, "/api/library/files", "engine_api.library_files", s.handleLibraryFiles)
	s.addRoute(http.MethodPost, "/api/library/refresh", "engine_api.library_refresh", s.handleLibraryRefresh)
	s.addRoute(http.MethodGet, "/api/files/audio", "engine_api.local_audio", s.handleLocalAudio)
	s.addRoute(http.MethodGet, "/api/files/cover", "engine_api.local_cover", s.handleLocalCover)
	s.addRoute(http.MethodPatch, "/api/files/tags", "engine_api.edit_file_tags", s.handleEditFileTags)
	s.addRoute(http.MethodGet, "/api/files/content", "engine_api.file_content", s.handleFileContent)
	s.addRoute(http.MethodDelete, "/api/files", "engine_api.delete_library_file", s.handleDeleteLibraryFile)
	s.addRoute(http.MethodPost, "/api/files/mkdir", "engine_api.mkdir_library_folder", s.handleMakeLibraryFolder)
	s.addRoute(http.MethodPost, "/api/files/rename", "engine_api.rename_library_folder", s.handleRenameLibraryFolder)
	s.addRoute(http.MethodPost, "/api/files/move", "engine_api.move_library_items", s.handleMoveLibraryItems)
	s.addRoute(http.MethodPost, "/api/files/batch-delete", "engine_api.batch_delete_library_items", s.handleBatchDeleteLibraryItems)
	s.addRoute(http.MethodGet, "/api/file", "legacy.app.getfile", s.handleLegacyGetFile)
	s.addRoute(http.MethodDelete, "/api/file", "legacy.app.deletefile", s.handleLegacyDeleteFile)

	// Library pipeline endpoints.
	s.addRoute(http.MethodGet, "/api/library/settings", "library_pipeline.settings", s.handleLibrarySettings)
	s.addRoute(http.MethodPut, "/api/library/settings", "library_pipeline.save_settings", s.handleLibrarySaveSettings)
	s.addRoute(http.MethodPut, "/api/library/credentials", "library_pipeline.save_credentials", s.handleLibrarySaveCredentials)
	s.addRoute(http.MethodGet, "/api/library/watch", "library_pipeline.watch_status", s.handleLibraryWatchStatus)
	s.addRoute(http.MethodPost, "/api/library/watch/scan", "library_pipeline.watch_scan", s.handleLibraryWatchScan)
	s.addRoute(http.MethodPut, "/api/library/navidrome", "library_pipeline.save_navidrome", s.handleLibrarySaveNavidrome)
	s.addRoute(http.MethodGet, "/api/library/jobs", "library_pipeline.jobs", s.handleLibraryJobs)
	s.addRoute(http.MethodGet, "/api/library/versions", "library_pipeline.versions", s.handleLibraryVersions)
	s.addRoute(http.MethodGet, "/api/library/jobs/{ident}/report", "library_pipeline.report", s.handleLibraryJobReport)
	s.addRoute(http.MethodGet, "/api/library/jobs/{ident}", "library_pipeline.job", s.handleLibraryJob)
	s.addRoute(http.MethodDelete, "/api/library/jobs/{ident}", "library_pipeline.delete", s.handleLibraryDeleteJob)
	s.addRoute(http.MethodPost, "/api/library/jobs", "library_pipeline.process", s.handleLibraryProcess)
	s.addRoute(http.MethodPost, "/api/library/jobs/batch-delete", "library_pipeline.batch_delete", s.handleLibraryBatchDeleteJobs)
	s.addRoute(http.MethodPost, "/api/library/jobs/{ident}/apply", "library_pipeline.apply", s.handleLibraryApply)
	s.addRoute(http.MethodPost, "/api/library/jobs/{ident}/search", "library_pipeline.search_candidates", s.handleLibrarySearchCandidates)
	s.addRoute(http.MethodPost, "/api/library/jobs/{ident}/preview", "library_pipeline.preview", s.handleLibraryPreview)
	s.addRoute(http.MethodPost, "/api/library/jobs/{ident}/cancel", "library_pipeline.cancel", s.handleLibraryCancel)
	s.addRoute(http.MethodPost, "/api/library/jobs/{ident}/retry", "library_pipeline.retry", s.handleLibraryRetry)
	s.addRoute(http.MethodPost, "/api/library/jobs/{ident}/restore", "library_pipeline.restore", s.handleLibraryRestore)
	s.addRoute(http.MethodPost, "/api/library/backups/purge", "library_pipeline.purge_backups", s.handleLibraryPurgeBackups)
	s.addRoute(http.MethodDelete, "/api/library/files/locks", "library_pipeline.unlock", s.handleLibraryUnlock)

	// Navidrome endpoints.
	s.addRoute(http.MethodGet, "/api/navidrome/capabilities", "engine_api.navidrome_capabilities", s.handleNavidromeCapabilities)
	s.addRoute(http.MethodGet, "/api/navidrome/songs/{ident}/deletion", "engine_api.navidrome_delete_preview", s.handleNavidromeDeletePreview)
	s.addRoute(http.MethodDelete, "/api/navidrome/songs/{ident}", "engine_api.navidrome_delete_song", s.handleNavidromeDeleteSong)
	s.addRoute(http.MethodGet, "/api/navidrome/playlists", "navidrome.playlists", s.handleNavidromePlaylists)
	s.addRoute(http.MethodGet, "/api/navidrome/playlists/{ident}", "navidrome.playlist", s.handleNavidromePlaylist)
	s.addRoute(http.MethodPost, "/api/navidrome/playlists", "navidrome.create_playlist", s.handleNavidromeCreatePlaylist)
	s.addRoute(http.MethodPatch, "/api/navidrome/playlists/{ident}", "navidrome.rename_playlist", s.handleNavidromeRenamePlaylist)
	s.addRoute(http.MethodDelete, "/api/navidrome/playlists/{ident}", "navidrome.delete_playlist", s.handleNavidromeDeletePlaylist)
	s.addRoute(http.MethodPost, "/api/navidrome/playlists/{ident}/tracks", "navidrome.add_songs", s.handleNavidromeAddSongs)
	s.addRoute(http.MethodPost, "/api/navidrome/playlists/{ident}/tracks/remove", "navidrome.remove_songs", s.handleNavidromeRemoveSongs)
	s.addRoute(http.MethodGet, "/api/navidrome/songs", "navidrome.songs", s.handleNavidromeSongs)
	s.addRoute(http.MethodGet, "/api/navidrome/albums", "navidrome.albums", s.handleNavidromeAlbums)
	s.addRoute(http.MethodGet, "/api/navidrome/albums/{ident}", "navidrome.album", s.handleNavidromeAlbum)
	s.addRoute(http.MethodGet, "/api/navidrome/artists", "navidrome.artists", s.handleNavidromeArtists)
	s.addRoute(http.MethodGet, "/api/navidrome/artists/{ident}", "navidrome.artist", s.handleNavidromeArtist)
	s.addRoute(http.MethodGet, "/api/navidrome/search", "navidrome.search", s.handleNavidromeSearch)
	s.addRoute(http.MethodGet, "/api/navidrome/cover/{ident}", "navidrome.cover", s.handleNavidromeCover)
	s.addRoute(http.MethodGet, "/api/navidrome/stream/{ident}", "navidrome.stream", s.handleNavidromeStream)
	s.addRoute(http.MethodGet, "/api/navidrome/download/{ident}", "navidrome.download", s.handleNavidromeDownload)
	s.addRoute(http.MethodPost, "/api/navidrome/downloads/archive", "navidrome.download_archive", s.handleNavidromeDownloadArchive)
	s.addRoute(http.MethodPost, "/api/navidrome/test", "navidrome.test", s.handleNavidromeTest)
	s.addRoute(http.MethodGet, "/api/navidrome/scan", "navidrome.scan_status", s.handleNavidromeScanStatus)
	s.addRoute(http.MethodPost, "/api/navidrome/scan", "navidrome.scan", s.handleNavidromeScan)

	// AI agent endpoints.
	s.addRoute(http.MethodGet, "/api/agent/settings", "ai_agent.settings_read", s.handleAgentSettingsRead)
	s.addRoute(http.MethodPut, "/api/agent/settings", "ai_agent.settings_write", s.handleAgentSettingsWrite)
	s.addRoute(http.MethodGet, "/api/agent/sessions", "ai_agent.sessions", s.handleAgentSessions)
	s.addRoute(http.MethodPost, "/api/agent/settings/test", "ai_agent.test", s.handleAgentTest)
	s.addRoute(http.MethodPost, "/api/agent/settings/test-capability/{capability}", "ai_agent.test_capability", s.handleAgentTestCapability)
	s.addRoute(http.MethodPost, "/api/agent/settings/models", "ai_agent.models", s.handleAgentModels)
	s.addRoute(http.MethodPost, "/api/agent/sessions", "ai_agent.create", s.handleAgentCreateSession)
	s.addRoute(http.MethodGet, "/api/agent/sessions/{ident}", "ai_agent.read", s.handleAgentReadSession)
	s.addRoute(http.MethodDelete, "/api/agent/sessions/{ident}", "ai_agent.delete", s.handleAgentDeleteSession)
	s.addRoute(http.MethodPost, "/api/agent/sessions/{ident}/messages", "ai_agent.send", s.handleAgentSendMessage)
	s.addRoute(http.MethodPost, "/api/agent/sessions/{ident}/stop", "ai_agent.stop", s.handleAgentStop)
	s.addRoute(http.MethodPost, "/api/agent/sessions/{ident}/resume", "ai_agent.resume", s.handleAgentResume)
	s.addRoute(http.MethodPost, "/api/agent/sessions/{ident}/events/delete", "ai_agent.delete_events", s.handleAgentDeleteEvents)
	s.addRoute(http.MethodPost, "/api/agent/sessions/{ident}/import/search", "ai_agent.import_search", s.handleAgentImportSearch)
	s.addRoute(http.MethodPost, "/api/agent/sessions/{ident}/import/execute", "ai_agent.import_execute", s.handleAgentImportExecute)

	// Bot & notification endpoints.
	s.addRoute(http.MethodGet, "/api/bots/config", "bot_notifications.read_config", s.handleBotReadConfig)
	s.addRoute(http.MethodPut, "/api/bots/config", "bot_notifications.write_config", s.handleBotWriteConfig)
	s.addRoute(http.MethodPost, "/api/bots/test", "bot_notifications.test_connection", s.handleBotTestConnection)
	s.addRoute(http.MethodGet, "/downloads/{ident}", "bot_notifications.download", s.handleBotSignedDownload)
	s.addRoute(http.MethodGet, "/bot/wecom", "bot_notifications.verify_wecom", s.handleBotVerifyWeCom)
	s.addRoute(http.MethodPost, "/bot/wecom", "bot_notifications.receive_wecom", s.handleBotReceiveWeCom)
	s.addRoute(http.MethodPost, "/bot/feishu", "bot_channels.feishu", s.handleBotReceiveFeishu)
	s.addRoute(http.MethodPost, "/bot/dingtalk", "bot_channels.dingtalk", s.handleBotReceiveDingTalk)
	s.addRoute(http.MethodPost, "/bot/discord", "bot_channels.discord", s.handleBotReceiveDiscord)
	s.addRoute(http.MethodPost, "/api/bots/discord/commands", "bot_channels.register_discord", s.handleBotRegisterDiscord)

	// Provider plugin endpoints.
	s.addRoute(http.MethodGet, "/api/plugins", "engine_api.plugins_list", s.handlePluginsList)
	s.addRoute(http.MethodPost, "/api/plugins/inspect", "engine_api.plugins_inspect", s.handlePluginsInspect)
	s.addRoute(http.MethodPost, "/api/plugins/inspect-archive", "engine_api.plugins_inspect_archive", s.handlePluginsInspectArchive)
	s.addRoute(http.MethodPost, "/api/plugins/install-archive", "engine_api.plugins_install_archive", s.handlePluginsInstallArchive)
	s.addRoute(http.MethodPost, "/api/plugins", "engine_api.plugins_install", s.handlePluginsInstall)
	s.addRoute(http.MethodPatch, "/api/plugins/{ident}", "engine_api.plugins_update", s.handlePluginsUpdate)
	s.addRoute(http.MethodDelete, "/api/plugins/{ident}", "engine_api.plugins_delete", s.handlePluginsDelete)
	s.addRoute(http.MethodPost, "/api/plugins/{ident}/test", "engine_api.plugins_test", s.handlePluginsTest)
}
