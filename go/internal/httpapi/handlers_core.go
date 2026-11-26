package httpapi

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func (s *Server) serveIndexHTML(w http.ResponseWriter, r *http.Request) {
	if s.deps.FrontendFS == nil {
		writeError(w, http.StatusNotFound, "前端页面未部署")
		return
	}
	data, err := fs.ReadFile(s.deps.FrontendFS, "index.html")
	if err != nil {
		writeError(w, http.StatusNotFound, "index.html 未找到")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	s.serveIndexHTML(w, r)
}

func (s *Server) handleAssets(w http.ResponseWriter, r *http.Request) {
	if s.deps.FrontendFS == nil {
		writeError(w, http.StatusNotFound, "Not Found")
		return
	}
	http.FileServer(http.FS(s.deps.FrontendFS)).ServeHTTP(w, r)
}

func (s *Server) handleStaticFallback(w http.ResponseWriter, r *http.Request) {
	if isAPIOrExternalPath(r.URL.Path) {
		writeError(w, http.StatusNotFound, "Not Found")
		return
	}
	if s.deps.FrontendFS != nil {
		rel := strings.TrimPrefix(r.URL.Path, "/")
		if rel != "" {
			if info, err := fs.Stat(s.deps.FrontendFS, rel); err == nil && !info.IsDir() {
				http.FileServer(http.FS(s.deps.FrontendFS)).ServeHTTP(w, r)
				return
			}
		}
	}
	s.serveIndexHTML(w, r)
}

func (s *Server) availableSources() []model.AvailableSourceDTO {
	if s.deps.Providers == nil {
		return []model.AvailableSourceDTO{}
	}
	avail := s.deps.Providers.AvailableSources()
	if avail == nil {
		return []model.AvailableSourceDTO{}
	}
	return avail
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	count := 0
	if s.deps.Providers != nil {
		count = len(s.deps.Providers.IDs())
	}
	bi := s.currentBuildInfo()
	payload := map[string]any{
		"ok":           true,
		"backend":      "melokeep",
		"engine":       "melokeep-native",
		"version":      bi.Version,
		"sources":      count,
		"in_container": bi.InContainer,
	}
	if bi.ShortCommit != "" {
		payload["commit"] = bi.ShortCommit
	}
	if bi.Ref != "" {
		payload["ref"] = bi.Ref
	}
	if bi.BuildTime != "" {
		payload["build_time"] = bi.BuildTime
	}
	writeJSON(w, http.StatusOK, payload)
}

func (s *Server) handleMeta(w http.ResponseWriter, r *http.Request) {
	categories := make(map[string][]string)
	registered := make(map[string]bool)
	var registeredOrder []string
	if s.deps.Providers != nil {
		for _, src := range s.deps.Providers.AvailableSources() {
			eng := src.EngineName
			if eng == "" {
				eng = src.ID
			}
			registered[eng] = true
			if src.Origin != "plugin" {
				registeredOrder = append(registeredOrder, eng)
			}
			cat := src.Category
			if cat == "" {
				cat = "其他"
			}
			categories[cat] = append(categories[cat], eng)
		}
	}
	// Prefer the compliant built-in defaults that are actually registered; otherwise fall back
	// to the first registered built-in source so the UI always has a usable default.
	defaults := make([]string, 0, 3)
	for _, d := range config.DefaultRuntimeConfig().Sources {
		if len(registered) == 0 || registered[d] {
			defaults = append(defaults, d)
		}
	}
	if len(defaults) == 0 && len(registeredOrder) > 0 {
		defaults = append(defaults, registeredOrder[0])
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"version":         s.deps.EngineVersion,
		"categories":      categories,
		"default_sources": defaults,
		"platform":        s.deps.Platform,
		"is_windows":      s.deps.IsWindows,
		"auth_enabled":    s.deps.AuthEnabled,
		"cookie_secure":   s.deps.CookieSecure,
	})
}

func (s *Server) handleAuthStatus(w http.ResponseWriter, r *http.Request) {
	authenticated := !s.deps.AuthEnabled
	var userID string
	if s.deps.AuthEnabled && s.deps.Sessions != nil {
		if a, err := s.deps.Sessions.ValidateRequest(r); err == nil {
			authenticated = true
			userID = a.UserID
		}
	}
	payload := map[string]any{
		"required":      s.deps.AuthEnabled,
		"authenticated": authenticated,
	}
	if authenticated && s.deps.AuthEnabled && s.deps.Sessions != nil {
		mediaTok, mediaExp := s.deps.Sessions.SignMediaToken(userID, auth.DefaultMediaTokenTTL)
		payload["media_token"] = mediaTok
		payload["media_token_expires_at"] = mediaExp
	}
	writeJSON(w, http.StatusOK, payload)
}

type loginRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	ClientType string `json:"client_type"`
	DeviceName string `json:"device_name"`
}

func clearLegacyRootCookie(w http.ResponseWriter, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     auth.SessionCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   0,
		Expires:  time.Unix(0, 0).UTC(),
		HttpOnly: true,
		Secure:   secure,
		SameSite: http.SameSiteStrictMode,
	})
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	isApp := strings.EqualFold(strings.TrimSpace(req.ClientType), "app")
	if !(isApp && !s.deps.AuthEnabled) {
		if len(req.Username) < 1 || len(req.Username) > 256 || len(req.Password) < 1 || len(req.Password) > 1024 {
			writeError(w, http.StatusUnprocessableEntity, "用户名或密码格式无效")
			return
		}
	}
	ip := s.clientIP(r)
	if s.deps.RateLimiter != nil {
		if err := s.deps.RateLimiter.Allow(ip); err != nil {
			writeDomainError(w, err)
			return
		}
	}
	if s.deps.AuthEnabled {
		uSum := sha256.Sum256([]byte(req.Username))
		pSum := sha256.Sum256([]byte(req.Password))
		uOK := subtle.ConstantTimeCompare(uSum[:], s.deps.AuthUserDigest[:]) == 1
		pOK := subtle.ConstantTimeCompare(pSum[:], s.deps.AuthPassDigest[:]) == 1
		if !(uOK && pOK) {
			if s.deps.RateLimiter != nil {
				s.deps.RateLimiter.RecordFailure(ip)
			}
			s.pushLog("warning", "Web 登录失败：用户名或密码错误 ("+ip+")", "system", "")
			writeError(w, http.StatusUnauthorized, "用户名或密码错误")
			return
		}
	}
	if s.deps.RateLimiter != nil {
		s.deps.RateLimiter.Reset(ip)
	}
	if !s.deps.AuthEnabled {
		if isApp {
			s.pushLog("info", "App 免密码模式访问确认 ("+ip+")", "system", "")
			writeJSON(w, http.StatusOK, map[string]any{
				"success":      true,
				"auth_enabled": false,
				"token":        "",
				"media_token":  "",
			})
			return
		}
		s.pushLog("info", "Web 免密码模式访问确认 ("+ip+")", "system", "")
		writeJSON(w, http.StatusOK, map[string]any{"success": true})
		return
	}
	if s.deps.Sessions == nil {
		writeError(w, http.StatusInternalServerError, "创建会话失败")
		return
	}
	if isApp {
		rawToken, rec, err := s.deps.Sessions.CreateDeviceToken(r.Context(), req.Username, req.DeviceName)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "创建设备令牌失败")
			return
		}
		mediaTok, mediaExp := s.deps.Sessions.SignMediaToken(req.Username, auth.DefaultMediaTokenTTL)
		s.pushLog("info", fmt.Sprintf("App 设备登录成功：%s (%s)", rec.DeviceName, ip), "system", "")
		writeJSON(w, http.StatusOK, map[string]any{
			"success":                true,
			"auth_enabled":           true,
			"token":                  rawToken,
			"token_id":               rec.ID,
			"expires_at":             rec.ExpiresAt,
			"media_token":            mediaTok,
			"media_token_expires_at": mediaExp,
		})
		return
	}
	token, err := s.deps.Sessions.Create(req.Username)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "创建会话失败")
		return
	}
	clearLegacyRootCookie(w, s.deps.CookieSecure)
	auth.SetSessionCookie(w, token, s.deps.CookieSecure)
	s.pushLog("info", "Web 用户登录成功 ("+ip+")", "system", "")
	writeJSON(w, http.StatusOK, map[string]any{"success": true})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if s.deps.Sessions != nil {
		if bearer, ok := auth.ExtractBearerToken(r); ok {
			_, _ = s.deps.Sessions.RevokeBearerToken(r.Context(), bearer)
		}
		if c, err := r.Cookie(auth.SessionCookieName); err == nil && c.Value != "" {
			s.deps.Sessions.Delete(c.Value)
		}
	}
	auth.ClearSessionCookie(w, s.deps.CookieSecure)
	clearLegacyRootCookie(w, s.deps.CookieSecure)
	s.pushLog("info", "Web 用户已退出登录 ("+s.clientIP(r)+")", "system", "")
	writeJSON(w, http.StatusOK, map[string]any{"success": true})
}

func (s *Server) handleAuthMediaToken(w http.ResponseWriter, r *http.Request) {
	if s.deps.Sessions == nil {
		writeError(w, http.StatusServiceUnavailable, "会话服务未初始化")
		return
	}
	actor := actorFromRequest(r)
	tok, exp := s.deps.Sessions.SignMediaToken(actor.UserID, auth.DefaultMediaTokenTTL)
	writeJSON(w, http.StatusOK, map[string]any{
		"media_token": tok,
		"expires_at":  exp,
	})
}

func (s *Server) handleAuthDevices(w http.ResponseWriter, r *http.Request) {
	if s.deps.Sessions == nil {
		writeError(w, http.StatusServiceUnavailable, "会话服务未初始化")
		return
	}
	actor := actorFromRequest(r)
	userFilter := ""
	if s.deps.AuthEnabled {
		userFilter = actor.UserID
	}
	records, err := s.deps.Sessions.ListDeviceTokens(r.Context(), userFilter)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	var currentID string
	if bearer, ok := auth.ExtractBearerToken(r); ok {
		if curRec, valid, vErr := s.deps.Sessions.ValidateBearerToken(r.Context(), bearer); vErr == nil && valid {
			currentID = curRec.ID
		}
	}
	devices := make([]DeviceTokenDTO, 0, len(records))
	for _, rec := range records {
		devices = append(devices, DeviceTokenDTO{
			ID:         rec.ID,
			UserID:     rec.UserID,
			DeviceName: rec.DeviceName,
			CreatedAt:  rec.CreatedAt,
			LastUsedAt: rec.LastUsedAt,
			ExpiresAt:  rec.ExpiresAt,
			Current:    currentID != "" && rec.ID == currentID,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": devices})
}

func (s *Server) handleAuthDeviceDelete(w http.ResponseWriter, r *http.Request) {
	if s.deps.Sessions == nil {
		writeError(w, http.StatusServiceUnavailable, "会话服务未初始化")
		return
	}
	ident := strings.TrimSpace(r.PathValue("ident"))
	if ident == "" {
		writeError(w, http.StatusBadRequest, "设备 ID 不能为空")
		return
	}
	rec, ok, err := s.deps.Sessions.GetDeviceToken(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeError(w, http.StatusNotFound, "设备令牌不存在")
		return
	}
	actor := actorFromRequest(r)
	if s.deps.AuthEnabled && actor.UserID != "" && rec.UserID != actor.UserID {
		writeError(w, http.StatusForbidden, "无权吊销其他用户的设备令牌")
		return
	}
	deleted, err := s.deps.Sessions.DeleteDeviceToken(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !deleted {
		writeError(w, http.StatusNotFound, "设备令牌不存在")
		return
	}
	s.pushLog("info", fmt.Sprintf("已吊销设备登录令牌：%s (%s)", rec.DeviceName, ident), "system", "")
	writeJSON(w, http.StatusOK, map[string]any{
		"deleted": true,
		"id":      ident,
	})
}

func (s *Server) pushLog(level, message, category, taskID string) {
	if s != nil && s.deps.Logs != nil && strings.TrimSpace(message) != "" {
		s.deps.Logs.Push(level, message, category, taskID)
	}
}

func (s *Server) handleReadConfig(w http.ResponseWriter, r *http.Request) {
	snap := s.currentSnapshot()
	writeJSON(w, http.StatusOK, snap.PublicDTO(s.availableSources()))
}

type writeConfigRequest struct {
	Sources                []string                          `json:"sources"`
	ResultsPerSource       *int                              `json:"results_per_source"`
	SearchTimeoutSeconds   *int                              `json:"search_timeout_seconds"`
	PlaybackTimeoutSeconds *int                              `json:"playback_timeout_seconds"`
	DownloadConcurrency    *int                              `json:"download_concurrency"`
	DownloadLayout         *string                           `json:"download_layout"`
	LyricsMode             *string                           `json:"lyrics_mode"`
	EmbedMetadata          *bool                             `json:"embed_metadata"`
	SourceSettings         map[string]model.SourceSettingDTO `json:"source_settings"`
}

func (s *Server) resolveRegisteredSourceKey(raw string) (string, bool) {
	key := strings.TrimSpace(raw)
	if key == "" {
		return "", false
	}
	if s.deps.Providers == nil {
		return key, true
	}
	p, ok := s.deps.Providers.Get(key)
	if !ok {
		return "", false
	}
	return p.ID(), true
}

func validateHTTPProxyURL(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" || trimmed == config.HiddenPlaceholder {
		return trimmed, nil
	}
	u, err := url.Parse(trimmed)
	scheme := ""
	if u != nil {
		scheme = strings.ToLower(u.Scheme)
	}
	if err != nil || (scheme != "http" && scheme != "https" && scheme != "socks5" && scheme != "socks5h") || u.Hostname() == "" || u.Port() == "0" {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "代理必须使用 HTTP / HTTPS / SOCKS5")
	}
	if strings.EqualFold(u.Hostname(), "169.254.169.254") || strings.EqualFold(u.Hostname(), "metadata.google.internal") {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "代理地址不能指向云元数据服务")
	}
	return trimmed, nil
}

func (s *Server) handleWriteConfig(w http.ResponseWriter, r *http.Request) {
	var req writeConfigRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	snap := s.currentSnapshot()
	nextRT := snap.Runtime

	if len(req.Sources) == 0 {
		writeError(w, http.StatusBadRequest, "请选择已注册的音源")
		return
	}
	seenSrc := make(map[string]bool, len(req.Sources))
	dedupSrc := make([]string, 0, len(req.Sources))
	for _, rawSrc := range req.Sources {
		id, ok := s.resolveRegisteredSourceKey(rawSrc)
		if !ok {
			writeError(w, http.StatusBadRequest, "请选择已注册的音源")
			return
		}
		if !seenSrc[id] {
			seenSrc[id] = true
			dedupSrc = append(dedupSrc, id)
		}
	}
	nextRT.Sources = dedupSrc

	if req.ResultsPerSource != nil {
		if *req.ResultsPerSource < 1 || *req.ResultsPerSource > 50 {
			writeError(w, http.StatusBadRequest, "结果数或请求超时超出范围")
			return
		}
		nextRT.ResultsPerSource = *req.ResultsPerSource
	}
	if req.SearchTimeoutSeconds != nil {
		if *req.SearchTimeoutSeconds < 0 || *req.SearchTimeoutSeconds > 300 {
			writeError(w, http.StatusBadRequest, "结果数或请求超时超出范围")
			return
		}
		nextRT.SearchTimeoutSeconds = *req.SearchTimeoutSeconds
	}
	if req.PlaybackTimeoutSeconds != nil {
		if *req.PlaybackTimeoutSeconds < 0 || *req.PlaybackTimeoutSeconds > 300 {
			writeError(w, http.StatusBadRequest, "结果数或请求超时超出范围")
			return
		}
		nextRT.PlaybackTimeoutSeconds = *req.PlaybackTimeoutSeconds
	}
	if req.DownloadConcurrency != nil {
		if *req.DownloadConcurrency < 1 || *req.DownloadConcurrency > 4 {
			writeError(w, http.StatusBadRequest, "下载并发需在 1 到 4 之间")
			return
		}
		nextRT.DownloadConcurrency = *req.DownloadConcurrency
	}
	if req.DownloadLayout != nil {
		switch *req.DownloadLayout {
		case "source", "flat", "artist", "artist_album":
			nextRT.DownloadLayout = *req.DownloadLayout
		default:
			writeError(w, http.StatusBadRequest, "下载偏好无效")
			return
		}
	}
	if req.LyricsMode != nil {
		switch *req.LyricsMode {
		case "both", "lrc", "embed", "none":
			nextRT.LyricsMode = *req.LyricsMode
		default:
			writeError(w, http.StatusBadRequest, "下载偏好无效")
			return
		}
	}
	if req.EmbedMetadata != nil {
		nextRT.EmbedMetadata = *req.EmbedMetadata
	}

	mergedSettings := make(map[string]model.SourceSettingDTO, len(nextRT.SourceSettings))
	for k, v := range nextRT.SourceSettings {
		mergedSettings[k] = v
	}
	for key, incoming := range req.SourceSettings {
		id, ok := s.resolveRegisteredSourceKey(key)
		if !ok {
			writeError(w, http.StatusBadRequest, "不支持的音源配置")
			return
		}
		proxyVal, err := validateHTTPProxyURL(incoming.Proxy)
		if err != nil {
			writeDomainError(w, err)
			return
		}
		prev := mergedSettings[id]
		if prev == (model.SourceSettingDTO{}) && key != id {
			prev = mergedSettings[key]
		}
		mergedSettings[id] = model.SourceSettingDTO{
			Cookie: config.MergeHiddenString(prev.Cookie, incoming.Cookie),
			Proxy:  config.MergeHiddenString(prev.Proxy, proxyVal),
		}
	}
	nextRT.SourceSettings = mergedSettings

	if err := s.persistRuntimeConfig(r, nextRT); err != nil {
		writeDomainError(w, err)
		return
	}
	s.swapSnapshot(config.NewSnapshot(nextRT, snap.Budgets))
	s.pushLog("info", fmt.Sprintf("已更新基础运行设置 (音源=%d, 并发=%d, 布局=%s)", len(nextRT.Sources), nextRT.DownloadConcurrency, nextRT.DownloadLayout), "engine", "")
	writeJSON(w, http.StatusOK, map[string]any{
		"saved":            true,
		"restart_required": false,
		"source_errors":    map[string]any{},
	})
}

func (s *Server) persistRuntimeConfig(r *http.Request, rt config.RuntimeConfig) error {
	if s.deps.Store == nil {
		return nil
	}
	raw, err := json.Marshal(rt)
	if err != nil {
		return model.WrapError(model.ErrInternal, http.StatusInternalServerError, "序列化配置失败", err)
	}
	_, err = s.deps.Store.PutSetting(r.Context(), store.NamespaceRuntimeConfig, string(raw), 0)
	return err
}

func (s *Server) engineNameForSource(id string) string {
	if s.deps.Providers != nil {
		if p, ok := s.deps.Providers.Get(id); ok && p.EngineName() != "" {
			return p.EngineName()
		}
	}
	return id
}

func (s *Server) redactedEngineConfigMap(rt config.RuntimeConfig) map[string]any {
	settings := make(map[string]model.SourceSettingDTO, len(rt.SourceSettings))
	cookies := make(map[string]string, len(rt.SourceSettings))
	for k, v := range rt.SourceSettings {
		redactedCookie := config.RedactValue(v.Cookie)
		settings[k] = model.SourceSettingDTO{
			Cookie: redactedCookie,
			Proxy:  config.RedactURL(v.Proxy),
		}
		if redactedCookie != "" {
			cookies[s.engineNameForSource(k)] = redactedCookie
		}
	}
	musicSources := make([]string, 0, len(rt.Sources))
	for _, id := range rt.Sources {
		musicSources = append(musicSources, s.engineNameForSource(id))
	}
	proxySources := make([]string, 0, len(rt.ProxySources))
	for _, id := range rt.ProxySources {
		proxySources = append(proxySources, s.engineNameForSource(id))
	}
	return map[string]any{
		"engine":                   rt.Engine,
		"sources":                  rt.Sources,
		"music_sources":            musicSources,
		"results_per_source":       rt.ResultsPerSource,
		"search_timeout_seconds":   rt.SearchTimeoutSeconds,
		"playback_timeout_seconds": rt.PlaybackTimeoutSeconds,
		"download_concurrency":     rt.DownloadConcurrency,
		"default_threads":          rt.DownloadConcurrency,
		"request_timeout":          rt.SearchTimeoutSeconds,
		"download_layout":          rt.DownloadLayout,
		"lyrics_mode":              rt.LyricsMode,
		"embed_metadata":           rt.EmbedMetadata,
		"source_settings":          settings,
		"cookies":                  cookies,
		"global_client_cfg": map[string]any{
			"work_dir":               rt.WorkDir,
			"search_size_per_source": rt.ResultsPerSource,
			"search_size_per_page":   20,
			"max_retries":            2,
		},
		"init_music_clients_cfg": map[string]any{},
		"clients_threadings":     map[string]any{},
		"requests_overrides":     map[string]any{},
		"search_rules":           map[string]any{},
		"proxy_url":              config.RedactURL(rt.ProxyURL),
		"proxy_scope":            rt.ProxyScope,
		"proxy_sources":          proxySources,
		"work_dir":               rt.WorkDir,
		"data_dir":               rt.DataDir,
	}
}

func (s *Server) handleReadEngineConfig(w http.ResponseWriter, r *http.Request) {
	snap := s.currentSnapshot()
	writeJSON(w, http.StatusOK, s.redactedEngineConfigMap(snap.Runtime))
}

func (s *Server) handleWriteEngineConfig(w http.ResponseWriter, r *http.Request) {
	var body map[string]any
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	snap := s.currentSnapshot()
	nextRT := snap.Runtime

	if rawSources, ok := body["music_sources"]; ok {
		if list, ok := rawSources.([]any); ok && len(list) > 0 {
			sources := make([]string, 0, len(list))
			for _, item := range list {
				if name, ok := item.(string); ok {
					id, valid := s.resolveRegisteredSourceKey(name)
					if !valid {
						writeError(w, http.StatusBadRequest, "不支持的音源: "+name)
						return
					}
					sources = append(sources, id)
				}
			}
			if len(sources) > 0 {
				nextRT.Sources = sources
			}
		}
	}
	if rawSources, ok := body["sources"]; ok {
		if list, ok := rawSources.([]any); ok && len(list) > 0 {
			sources := make([]string, 0, len(list))
			for _, item := range list {
				if name, ok := item.(string); ok {
					id, valid := s.resolveRegisteredSourceKey(name)
					if !valid {
						writeError(w, http.StatusBadRequest, "不支持的音源: "+name)
						return
					}
					sources = append(sources, id)
				}
			}
			if len(sources) > 0 {
				nextRT.Sources = sources
			}
		}
	}
	if rawProxy, ok := body["proxy_url"].(string); ok {
		p, err := validateHTTPProxyURL(rawProxy)
		if err != nil {
			writeDomainError(w, err)
			return
		}
		nextRT.ProxyURL = config.MergeHiddenString(nextRT.ProxyURL, p)
	}
	if rawScope, ok := body["proxy_scope"].(string); ok {
		switch rawScope {
		case "", "all", "selected", "none":
			if rawScope != "" {
				nextRT.ProxyScope = rawScope
			}
		default:
			writeError(w, http.StatusBadRequest, "无效的音源代理作用范围")
			return
		}
	}
	if rawLayout, ok := body["download_layout"].(string); ok && rawLayout != "" {
		switch rawLayout {
		case "source", "flat", "artist", "artist_album":
			nextRT.DownloadLayout = rawLayout
		default:
			writeError(w, http.StatusBadRequest, "下载偏好无效")
			return
		}
	}
	if rawLyrics, ok := body["lyrics_mode"].(string); ok && rawLyrics != "" {
		switch rawLyrics {
		case "both", "lrc", "embed", "none":
			nextRT.LyricsMode = rawLyrics
		default:
			writeError(w, http.StatusBadRequest, "下载偏好无效")
			return
		}
	}
	if rawEmbed, ok := body["embed_metadata"].(bool); ok {
		nextRT.EmbedMetadata = rawEmbed
	}
	if rawCnt, ok := body["results_per_source"].(float64); ok {
		v := int(rawCnt)
		if v < 1 || v > 50 {
			writeError(w, http.StatusBadRequest, "结果数或请求超时超出范围")
			return
		}
		nextRT.ResultsPerSource = v
	}
	if rawTimeout, ok := body["search_timeout_seconds"].(float64); ok {
		v := int(rawTimeout)
		if v < 0 || v > 300 {
			writeError(w, http.StatusBadRequest, "结果数或请求超时超出范围")
			return
		}
		nextRT.SearchTimeoutSeconds = v
	}
	if rawTimeout, ok := body["request_timeout"].(float64); ok {
		v := int(rawTimeout)
		if v < 0 || v > 300 {
			writeError(w, http.StatusBadRequest, "结果数或请求超时超出范围")
			return
		}
		nextRT.SearchTimeoutSeconds = v
	}
	if rawThreads, ok := body["default_threads"].(float64); ok {
		v := int(rawThreads)
		if v < 1 || v > 50 {
			writeError(w, http.StatusBadRequest, "默认下载线程数超出范围")
			return
		}
		nextRT.DownloadConcurrency = v
	}
	var (
		hasWorkDir bool
		rawWorkDir string
	)
	if rawGlobal, ok := body["global_client_cfg"].(map[string]any); ok {
		if rawCnt, ok := rawGlobal["search_size_per_source"].(float64); ok {
			v := int(rawCnt)
			if v < 1 || v > 50 {
				writeError(w, http.StatusBadRequest, "结果数或请求超时超出范围")
				return
			}
			nextRT.ResultsPerSource = v
		}
		if rawVal, exists := rawGlobal["work_dir"]; exists {
			hasWorkDir = true
			if wd, ok := rawVal.(string); ok {
				rawWorkDir = wd
			}
		}
	}
	if strings.TrimSpace(rawWorkDir) == "" {
		if rawVal, exists := body["work_dir"]; exists {
			hasWorkDir = true
			if wd, ok := rawVal.(string); ok {
				rawWorkDir = wd
			}
		}
	}
	if hasWorkDir {
		trimmed := strings.TrimSpace(rawWorkDir)
		if trimmed == "" {
			writeError(w, http.StatusBadRequest, "下载目录不能为空")
			return
		}
		var roots []files.Root
		if s.deps.Files != nil {
			roots = s.deps.Files.Roots()
		}
		if len(roots) == 0 && strings.TrimSpace(s.deps.WorkDir) != "" {
			base := strings.TrimSpace(s.deps.WorkDir)
			if absBase, err := filepath.Abs(base); err == nil {
				base = filepath.Clean(absBase)
			} else {
				base = filepath.Clean(base)
			}
			roots = []files.Root{{ID: "work", CanonicalPath: base}}
		}
		if len(roots) > 0 {
			defaultRoot := roots[0].CanonicalPath
			if wd := strings.TrimSpace(s.deps.WorkDir); wd != "" && filepath.IsAbs(wd) {
				defaultRoot = filepath.Clean(wd)
			}
			matchedRoot, resolvedDir, err := files.ResolveManagedDir(roots, trimmed, defaultRoot)
			if err != nil {
				writeDomainError(w, err)
				return
			}
			if !s.deps.ReadOnly {
				if err := os.MkdirAll(resolvedDir, 0o755); err != nil {
					if errors.Is(err, os.ErrPermission) {
						if permErr := files.CheckNearestWritableAncestor(matchedRoot.CanonicalPath, resolvedDir); permErr != nil {
							writeDomainError(w, permErr)
							return
						}
					}
					writeDomainError(w, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "创建下载目录失败: "+err.Error(), err))
					return
				}
				if err := files.CheckDirWritable(resolvedDir); err != nil {
					writeDomainError(w, err)
					return
				}
			}
			nextRT.WorkDir = resolvedDir
		} else {
			nextRT.WorkDir = trimmed
		}
	}
	if rawProxySources, ok := body["proxy_sources"].([]any); ok {
		ps := make([]string, 0, len(rawProxySources))
		for _, item := range rawProxySources {
			if name, ok := item.(string); ok {
				if id, valid := s.resolveRegisteredSourceKey(name); valid {
					ps = append(ps, id)
				}
			}
		}
		nextRT.ProxySources = ps
	}
	if rawCookies, ok := body["cookies"].(map[string]any); ok {
		merged := make(map[string]model.SourceSettingDTO, len(nextRT.SourceSettings))
		for k, v := range nextRT.SourceSettings {
			merged[k] = v
		}
		for k, rawVal := range rawCookies {
			id, valid := s.resolveRegisteredSourceKey(k)
			if !valid {
				continue
			}
			var cookieStr string
			switch v := rawVal.(type) {
			case string:
				cookieStr = v
			case map[string]any:
				parts := make([]string, 0, len(v))
				for ck, cv := range v {
					parts = append(parts, fmt.Sprintf("%s=%v", ck, cv))
				}
				cookieStr = strings.Join(parts, "; ")
			}
			prev := merged[id]
			merged[id] = model.SourceSettingDTO{
				Cookie: config.MergeHiddenString(prev.Cookie, cookieStr),
				Proxy:  prev.Proxy,
			}
		}
		nextRT.SourceSettings = merged
	}
	if rawSS, ok := body["source_settings"].(map[string]any); ok {
		merged := make(map[string]model.SourceSettingDTO, len(nextRT.SourceSettings))
		for k, v := range nextRT.SourceSettings {
			merged[k] = v
		}
		for k, rawVal := range rawSS {
			id, valid := s.resolveRegisteredSourceKey(k)
			if !valid {
				writeError(w, http.StatusBadRequest, "不支持的音源配置")
				return
			}
			m, _ := rawVal.(map[string]any)
			cookie, _ := m["cookie"].(string)
			proxy, _ := m["proxy"].(string)
			p, err := validateHTTPProxyURL(proxy)
			if err != nil {
				writeDomainError(w, err)
				return
			}
			prev := merged[id]
			merged[id] = model.SourceSettingDTO{
				Cookie: config.MergeHiddenString(prev.Cookie, cookie),
				Proxy:  config.MergeHiddenString(prev.Proxy, p),
			}
		}
		nextRT.SourceSettings = merged
	}

	if err := s.persistRuntimeConfig(r, nextRT); err != nil {
		writeDomainError(w, err)
		return
	}
	s.swapSnapshot(config.NewSnapshot(nextRT, snap.Budgets))
	s.pushLog("info", fmt.Sprintf("已更新基础运行设置 (音源=%d, 并发=%d, 布局=%s)", len(nextRT.Sources), nextRT.DownloadConcurrency, nextRT.DownloadLayout), "engine", "")
	writeJSON(w, http.StatusOK, map[string]any{
		"saved":         true,
		"source_errors": map[string]any{},
	})
}

func (s *Server) handleEngineLatest(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"engine":           "melokeep-native",
		"current":          s.deps.EngineVersion,
		"latest":           s.deps.EngineVersion,
		"update_available": false,
	})
}

func (s *Server) handleEngineUpdate(w http.ResponseWriter, r *http.Request) {
	writeError(w, http.StatusForbidden, "引擎与安全依赖随 MeloKeep 一起升级，请更新源码或重建镜像")
}

func (s *Server) handleEngineUpdateStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"running": false,
		"ok":      nil,
		"detail":  "",
		"to":      nil,
		"current": s.deps.EngineVersion,
		"enabled": false,
	})
}

func (s *Server) handleRestart(w http.ResponseWriter, r *http.Request) {
	s.deps.Logs.Push("info", "收到重启请求，1 秒后重启…", "system", "")
	if s.deps.RestartCallback != nil {
		go func() {
			time.Sleep(1 * time.Second)
			s.deps.RestartCallback()
		}()
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

type playbackEventRequest struct {
	TrackID      string  `json:"track_id"`
	Event        string  `json:"event"`
	Stage        string  `json:"stage"`
	Code         int     `json:"code"`
	NetworkState int     `json:"network_state"`
	ReadyState   int     `json:"ready_state"`
	Position     float64 `json:"position"`
	Detail       string  `json:"detail"`
	Browser      string  `json:"browser"`
}

func (s *Server) handlePlaybackEvent(w http.ResponseWriter, r *http.Request) {
	var req playbackEventRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	eventLabels := map[string]string{
		"started":   "开始播放",
		"failed":    "播放失败",
		"stalled":   "缓冲停滞",
		"recovered": "恢复播放",
	}
	stageLabels := map[string]string{
		"prepare": "准备曲目",
		"format":  "格式检查",
		"play":    "启动播放",
		"media":   "媒体解码/网络",
		"buffer":  "缓冲",
	}
	evLabel, ok1 := eventLabels[req.Event]
	stLabel, ok2 := stageLabels[req.Stage]
	if !ok1 || !ok2 {
		writeError(w, http.StatusUnprocessableEntity, "无效的播放事件或阶段")
		return
	}

	key := playbackDedupeKey{
		TrackID: req.TrackID,
		Event:   req.Event,
		Stage:   req.Stage,
		Code:    req.Code,
		Detail:  req.Detail,
	}
	now := time.Now()
	s.playbackMu.Lock()
	if last, exists := s.playbackReport[key]; exists && now.Sub(last) < playbackLogWindow {
		s.playbackMu.Unlock()
		writeJSON(w, http.StatusOK, map[string]any{"recorded": false})
		return
	}
	if len(s.playbackReport) >= playbackLogMaxKeys {
		for k, t := range s.playbackReport {
			if now.Sub(t) >= playbackLogWindow || len(s.playbackReport) >= playbackLogMaxKeys {
				delete(s.playbackReport, k)
				if len(s.playbackReport) < playbackLogMaxKeys {
					break
				}
			}
		}
	}
	s.playbackReport[key] = now
	s.playbackMu.Unlock()

	level := "info"
	if req.Event == "failed" {
		level = "error"
	} else if req.Event == "stalled" {
		level = "warning"
	}
	msg := fmt.Sprintf("浏览器试听 %s · 阶段 %s · %s\nMediaError=%d，networkState=%d，readyState=%d，播放位置=%.2fs\n浏览器：%s",
		evLabel, stLabel, redactTextURLs(req.Detail), req.Code, req.NetworkState, req.ReadyState, req.Position, req.Browser)
	s.deps.Logs.Push(level, msg, "playback", req.TrackID)
	writeJSON(w, http.StatusOK, map[string]any{"recorded": true})
}

func (s *Server) handleOpenDir(w http.ResponseWriter, r *http.Request) {
	dir := strings.TrimSpace(s.currentSnapshot().Runtime.WorkDir)
	if dir == "" || (dir == config.DefaultRuntimeConfig().WorkDir && strings.TrimSpace(s.deps.WorkDir) != "") {
		dir = strings.TrimSpace(s.deps.WorkDir)
	}
	if dir == "" {
		dir = "melokeep_outputs"
	}
	if !s.deps.IsWindows || s.deps.OpenDirCallback == nil {
		writeError(w, http.StatusBadRequest, "服务端非本机桌面环境，下载目录为："+dir)
		return
	}
	if err := s.deps.OpenDirCallback(); err != nil {
		writeError(w, http.StatusInternalServerError, "无法打开目录: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "dir": dir})
}

func (s *Server) handleCacheClear(w http.ResponseWriter, r *http.Request) {
	var removedFiles int
	var freedBytes int64
	if s.deps.Files != nil {
		for _, root := range s.deps.Files.Roots() {
			var dirs []string
			_ = filepath.WalkDir(root.CanonicalPath, func(p string, d fs.DirEntry, err error) error {
				if err != nil {
					return nil
				}
				if d.Type()&fs.ModeSymlink != 0 {
					return nil
				}
				if d.IsDir() {
					if p != root.CanonicalPath {
						dirs = append(dirs, p)
					}
					return nil
				}
				if strings.EqualFold(filepath.Ext(p), ".pkl") {
					if info, sErr := d.Info(); sErr == nil {
						freedBytes += info.Size()
					}
					if rmErr := os.Remove(p); rmErr == nil {
						removedFiles++
					}
				}
				return nil
			})
			for i := len(dirs) - 1; i >= 0; i-- {
				entries, err := os.ReadDir(dirs[i])
				if err == nil && len(entries) == 0 {
					_ = os.Remove(dirs[i])
				}
			}
		}
	}
	s.resolveMu.Lock()
	clear(s.resolveCache)
	s.resolveMu.Unlock()
	if s.deps.Search != nil {
		s.deps.Search.ClearCache()
	}
	freedKB := freedBytes / 1024
	s.deps.Logs.Push("info", fmt.Sprintf("清理缓存: 删除 %d 个缓存文件，释放 %d KB", removedFiles, freedKB), "system", "")
	writeJSON(w, http.StatusOK, map[string]any{
		"removed":  removedFiles,
		"freed_kb": freedKB,
	})
}

func (s *Server) handleLogs(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"logs": s.deps.Logs.List(),
	})
}

func (s *Server) handleLogsClear(w http.ResponseWriter, r *http.Request) {
	s.deps.Logs.Clear()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func isValidHexColor(s string) bool {
	if len(s) != 7 || s[0] != '#' {
		return false
	}
	for i := 1; i < 7; i++ {
		c := s[i]
		isHex := (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
		if !isHex {
			return false
		}
	}
	return true
}

func sanitizeUIPreferencesPatch(dst *model.UIPreferencesDTO, req model.UIPreferencesDTO) {
	if req.Appearance != nil {
		if dst.Appearance == nil {
			dst.Appearance = &model.AppearancePreferencesDTO{}
		}
		switch req.Appearance.Mode {
		case "system", "light", "dark":
			dst.Appearance.Mode = req.Appearance.Mode
		}
		switch req.Appearance.LightPreset {
		case "pure-light", "warm-vinyl", "nordic-frost", "rose-mist":
			dst.Appearance.LightPreset = req.Appearance.LightPreset
		}
		switch req.Appearance.DarkPreset {
		case "obsidian", "vinyl-gold", "midnight-aurora", "cyber-amethyst":
			dst.Appearance.DarkPreset = req.Appearance.DarkPreset
		}
		if req.Appearance.CustomAccent != nil {
			accent := strings.TrimSpace(*req.Appearance.CustomAccent)
			if accent == "" || isValidHexColor(accent) {
				dst.Appearance.CustomAccent = &accent
			}
		}
		if req.Appearance.AmbientGlow != nil {
			v := *req.Appearance.AmbientGlow
			dst.Appearance.AmbientGlow = &v
		}
		switch req.Appearance.Density {
		case "modern", "compact":
			dst.Appearance.Density = req.Appearance.Density
		}
	}

	if req.NavigationOrder != nil {
		validNav := map[string]bool{
			"search": true, "daily": true, "assistant": true, "player": true,
			"playlists": true, "queue": true, "files": true,
			"organize": true, "logs": true, "settings": true,
		}
		seen := make(map[string]bool, len(req.NavigationOrder))
		cleaned := make([]string, 0, len(req.NavigationOrder))
		for _, id := range req.NavigationOrder {
			if validNav[id] && !seen[id] {
				seen[id] = true
				cleaned = append(cleaned, id)
			}
		}
		if len(cleaned) > 0 {
			dst.NavigationOrder = cleaned
		}
	}

	if req.Player != nil {
		if dst.Player == nil {
			dst.Player = &model.PlayerPreferencesDTO{}
		}
		if req.Player.Volume != nil {
			v := *req.Player.Volume
			if v < 0 {
				v = 0
			} else if v > 1 {
				v = 1
			}
			dst.Player.Volume = &v
		}
		if req.Player.Shuffle != nil {
			v := *req.Player.Shuffle
			dst.Player.Shuffle = &v
		}
		if req.Player.Repeat != nil {
			v := *req.Player.Repeat
			dst.Player.Repeat = &v
		}
		if req.Player.FollowLyrics != nil {
			v := *req.Player.FollowLyrics
			dst.Player.FollowLyrics = &v
		}
		if req.Player.LargeLyrics != nil {
			v := *req.Player.LargeLyrics
			dst.Player.LargeLyrics = &v
		}
		if req.Player.LyricsFontSize != nil {
			v := *req.Player.LyricsFontSize
			if v < 14 {
				v = 14
			} else if v > 32 {
				v = 32
			}
			dst.Player.LyricsFontSize = &v
		}
		if req.Player.LyricsActiveColor != nil {
			color := strings.TrimSpace(*req.Player.LyricsActiveColor)
			if color == "" || isValidHexColor(color) {
				dst.Player.LyricsActiveColor = &color
			}
		}
		if req.Player.LyricsInactiveOpacity != nil {
			v := *req.Player.LyricsInactiveOpacity
			if !math.IsNaN(v) && !math.IsInf(v, 0) {
				if v < 0.2 {
					v = 0.2
				} else if v > 0.9 {
					v = 0.9
				}
				dst.Player.LyricsInactiveOpacity = &v
			}
		}
		switch strings.TrimSpace(req.Player.LyricsAlign) {
		case "center", "left":
			dst.Player.LyricsAlign = strings.TrimSpace(req.Player.LyricsAlign)
		}
	}

	if req.LibraryTreeWidth != nil {
		w := *req.LibraryTreeWidth
		if w <= 0 {
			zero := 0
			dst.LibraryTreeWidth = &zero
		} else {
			if w < 200 {
				w = 200
			} else if w > 480 {
				w = 480
			}
			dst.LibraryTreeWidth = &w
		}
	}

	if req.NavidromeView != nil {
		if dst.NavidromeView == nil {
			dst.NavidromeView = &model.NavidromeViewPreferencesDTO{}
		}
		switch req.NavidromeView.Mode {
		case "songs", "albums", "artists":
			dst.NavidromeView.Mode = req.NavidromeView.Mode
		}
		switch req.NavidromeView.Layout {
		case "cards", "list":
			dst.NavidromeView.Layout = req.NavidromeView.Layout
		}
	}

	if req.DismissedUpdate != nil {
		key := strings.TrimSpace(*req.DismissedUpdate)
		if len(key) > 128 {
			key = key[:128]
		}
		dst.DismissedUpdate = &key
	}
}

func (s *Server) handleReadPreferences(w http.ResponseWriter, r *http.Request) {
	if s.deps.Store == nil {
		writeJSON(w, http.StatusOK, model.UIPreferencesDTO{})
		return
	}
	s.prefsMu.Lock()
	defer s.prefsMu.Unlock()
	rec, ok, err := s.deps.Store.GetSetting(r.Context(), store.NamespaceUIPreferences)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !ok {
		writeJSON(w, http.StatusOK, model.UIPreferencesDTO{})
		return
	}
	var prefs model.UIPreferencesDTO
	if err := json.Unmarshal([]byte(rec.BodyJSON), &prefs); err != nil {
		writeJSON(w, http.StatusOK, model.UIPreferencesDTO{})
		return
	}
	writeJSON(w, http.StatusOK, prefs)
}

func (s *Server) handleWritePreferences(w http.ResponseWriter, r *http.Request) {
	if s.deps.ReadOnly {
		writeError(w, http.StatusForbidden, "只读模式下不允许修改配置")
		return
	}
	var req model.UIPreferencesDTO
	if !decodeJSON(w, r, 64*1024, &req) {
		return
	}
	if s.deps.Store == nil {
		var merged model.UIPreferencesDTO
		sanitizeUIPreferencesPatch(&merged, req)
		writeJSON(w, http.StatusOK, merged)
		return
	}
	s.prefsMu.Lock()
	defer s.prefsMu.Unlock()

	var current model.UIPreferencesDTO
	if rec, ok, err := s.deps.Store.GetSetting(r.Context(), store.NamespaceUIPreferences); err != nil {
		writeDomainError(w, err)
		return
	} else if ok {
		_ = json.Unmarshal([]byte(rec.BodyJSON), &current)
	}
	sanitizeUIPreferencesPatch(&current, req)
	raw, err := json.Marshal(current)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "序列化界面偏好失败")
		return
	}
	if _, err := s.deps.Store.PutSetting(r.Context(), store.NamespaceUIPreferences, string(raw), 0); err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, current)
}
