package plugin

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
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

// InstallRequest defines inputs for installing or replacing a JS provider plugin.
type InstallRequest struct {
	Code           string            `json:"code"`
	SourceURL      string            `json:"source_url"`
	Enabled        *bool             `json:"enabled"`
	Settings       map[string]string `json:"settings"`
	AllowedOrigins []string          `json:"allowed_origins"`
}

// UpdateRequest defines mutable configuration fields for an installed JS plugin.
type UpdateRequest struct {
	Enabled        *bool             `json:"enabled"`
	Settings       map[string]string `json:"settings"`
	AllowedOrigins *[]string         `json:"allowed_origins"`
}

// TestRequest specifies a single sandboxed verification call against an installed plugin.
type TestRequest struct {
	Action  string         `json:"action"`
	Keyword string         `json:"keyword"`
	Track   map[string]any `json:"track"`
	Locator string         `json:"locator"`
	Query   string         `json:"query"`
	Title   string         `json:"title"`
	Artist  string         `json:"artist"`
	Album   string         `json:"album"`
	Limit   int            `json:"limit"`
}

// ManagerOption customizes Manager runtime behavior.
type ManagerOption func(*Manager)

// WithInvokeTimeout overrides the per-invocation sandbox timeout (useful in tests).
func WithInvokeTimeout(d time.Duration) ManagerOption {
	return func(m *Manager) {
		if d > 0 {
			m.invokeTimeout = d
		}
	}
}

// Manager coordinates plugin disk persistence, SQLite storage, inspection, testing, and Registry hot-reloading.
type Manager struct {
	mu            sync.Mutex
	store         *store.Store
	pool          *transport.Pool
	reg           *provider.Registry
	pluginDir     string
	logFn         provider.LogFunc
	invokeTimeout time.Duration
}

// NewManager creates a Manager storing plugin scripts inside pluginDir and syncing enabled plugins into reg.
func NewManager(st *store.Store, pool *transport.Pool, reg *provider.Registry, pluginDir string, logFn provider.LogFunc, opts ...ManagerOption) *Manager {
	if pool == nil {
		pool = transport.NewPool()
	}
	dir := strings.TrimSpace(pluginDir)
	if dir == "" {
		dir = filepath.Join("data", "go", "plugins")
	}
	m := &Manager{
		store:         st,
		pool:          pool,
		reg:           reg,
		pluginDir:     filepath.Clean(dir),
		logFn:         logFn,
		invokeTimeout: DefaultInvokeTimeout,
	}
	for _, opt := range opts {
		if opt != nil {
			opt(m)
		}
	}
	return m
}

// LoadAll reads persisted plugin metadata from store, compiles valid scripts from pluginDir,
// registers enabled plugins, and isolates per-plugin load failures into last_error without blocking startup.
func (m *Manager) LoadAll(ctx context.Context) error {
	if m.store == nil {
		return model.NewError(model.ErrInternal, http.StatusInternalServerError, "插件存储未初始化")
	}
	m.mu.Lock()
	defer m.mu.Unlock()

	recs, err := m.store.ListProviderPlugins(ctx)
	if err != nil {
		return err
	}

	for _, rec := range recs {
		if err := m.loadOneLocked(ctx, rec); err != nil {
			rec.LastError = err.Error()
			rec.UpdatedAt = time.Now().UTC().UnixMilli()
			_ = m.store.UpsertProviderPlugin(ctx, rec)
			if m.reg != nil {
				m.reg.UnregisterPlugin(rec.ID)
			}
			m.emitLog("error", fmt.Sprintf("加载插件 %q 失败（已跳过）: %v", rec.ID, err), rec.ID)
		}
	}
	return nil
}

var reservedBuiltinPluginIDs = map[string]bool{
	"audius":            true,
	"fma":               true,
	"ccmixter":          true,
	"opengameart":       true,
	"wikimedia":         true,
	"wikimedia_commons": true,
	"jamendo":           true,
	"itunes":            true,
	"musicbrainz":       true,
	"acoustid":          true,
	"lrclib":            true,
}

func (m *Manager) isBuiltinConflict(id string) bool {
	if reservedBuiltinPluginIDs[strings.ToLower(strings.TrimSpace(id))] {
		return true
	}
	return m.reg != nil && m.reg.HasBuiltin(id)
}

func (m *Manager) loadOneLocked(ctx context.Context, rec store.ProviderPluginRecord) error {
	if err := ValidatePluginID(rec.ID); err != nil {
		return err
	}
	if m.isBuiltinConflict(rec.ID) {
		return model.NewError(model.ErrConflict, http.StatusConflict, fmt.Sprintf("插件 ID %q 与内置音源冲突", rec.ID))
	}

	scriptPath, err := m.pluginFilePath(rec.ID)
	if err != nil {
		return err
	}
	rawBytes, err := os.ReadFile(scriptPath)
	if err != nil {
		return fmt.Errorf("读取插件脚本失败: %w", err)
	}
	if len(rawBytes) > MaxSourceBytes {
		return fmt.Errorf("插件脚本大小超过 512 KiB 上限")
	}

	sum := sha256.Sum256(rawBytes)
	actualSHA := hex.EncodeToString(sum[:])
	if rec.SHA256 != "" && !strings.EqualFold(rec.SHA256, actualSHA) {
		return fmt.Errorf("插件脚本 SHA-256 校验不一致")
	}

	compiled, err := ParseAndCompile(string(rawBytes))
	if err != nil {
		return err
	}
	if compiled.Manifest.ID != rec.ID {
		return fmt.Errorf("脚本声明 ID %q 与数据库记录 %q 不一致", compiled.Manifest.ID, rec.ID)
	}

	var storedSettings map[string]string
	if strings.TrimSpace(rec.SettingsJSON) != "" {
		if err := json.Unmarshal([]byte(rec.SettingsJSON), &storedSettings); err != nil {
			return fmt.Errorf("解析插件 settings_json 失败: %w", err)
		}
	}
	var rawOrigins []string
	if strings.TrimSpace(rec.AllowedOriginsJSON) != "" {
		if err := json.Unmarshal([]byte(rec.AllowedOriginsJSON), &rawOrigins); err != nil {
			return fmt.Errorf("解析插件 allowed_origins_json 失败: %w", err)
		}
	}
	allowedOrigins, err := ValidateAndNormalizeOrigins(rawOrigins)
	if err != nil {
		return err
	}

	prov, err := NewProvider(compiled, ProviderOptions{
		Pool:           m.pool,
		Settings:       storedSettings,
		AllowedOrigins: allowedOrigins,
		InvokeTimeout:  m.invokeTimeout,
		LogFunc:        m.logFn,
	})
	if err != nil {
		return err
	}

	if rec.Enabled {
		if m.reg != nil {
			if err := m.reg.RegisterPlugin(prov.RegistryEntry()); err != nil {
				return err
			}
		}
	} else if m.reg != nil {
		m.reg.UnregisterPlugin(rec.ID)
	}

	if rec.LastError != "" {
		rec.LastError = ""
		rec.UpdatedAt = time.Now().UTC().UnixMilli()
		_ = m.store.UpsertProviderPlugin(ctx, rec)
	}
	return nil
}

// List returns all persisted provider plugins with secret setting values redacted.
func (m *Manager) List(ctx context.Context) ([]model.ProviderPluginDTO, error) {
	if m.store == nil {
		return nil, model.NewError(model.ErrInternal, http.StatusInternalServerError, "插件存储未初始化")
	}
	recs, err := m.store.ListProviderPlugins(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]model.ProviderPluginDTO, 0, len(recs))
	for _, rec := range recs {
		out = append(out, recordToDTO(rec))
	}
	return out, nil
}

// Inspect validates and summarizes permissions/warnings for a plugin script from code or a public sourceURL.
func (m *Manager) Inspect(ctx context.Context, code, sourceURL string) (model.PluginInspectResultDTO, error) {
	resolvedCode, err := m.resolveSourceCode(ctx, code, sourceURL)
	if err != nil {
		return model.PluginInspectResultDTO{}, err
	}
	return Inspect(ctx, resolvedCode)
}

// Install compiles, persists to disk and SQLite, and hot-registers a plugin when enabled.
func (m *Manager) Install(ctx context.Context, req InstallRequest) (model.ProviderPluginDTO, error) {
	if m.store == nil {
		return model.ProviderPluginDTO{}, model.NewError(model.ErrInternal, http.StatusInternalServerError, "插件存储未初始化")
	}
	code, err := m.resolveSourceCode(ctx, req.Code, req.SourceURL)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}
	compiled, err := ParseAndCompile(code)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}
	id := compiled.Manifest.ID
	if m.isBuiltinConflict(id) {
		return model.ProviderPluginDTO{}, model.NewError(model.ErrConflict, http.StatusConflict, fmt.Sprintf("插件 ID %q 与内置音源冲突", id))
	}

	allowedOrigins, err := ValidateAndNormalizeOrigins(req.AllowedOrigins)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	existing, hasExisting, err := m.store.GetProviderPlugin(ctx, id)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}
	var prevSettings map[string]string
	if hasExisting && strings.TrimSpace(existing.SettingsJSON) != "" {
		_ = json.Unmarshal([]byte(existing.SettingsJSON), &prevSettings)
	}
	mergedSettings := mergeSettingsUpdate(compiled.Manifest, prevSettings, req.Settings)

	enabled := true
	if req.Enabled != nil {
		enabled = *req.Enabled
	}

	prov, err := NewProvider(compiled, ProviderOptions{
		Pool:           m.pool,
		Settings:       mergedSettings,
		AllowedOrigins: allowedOrigins,
		InvokeTimeout:  m.invokeTimeout,
		LogFunc:        m.logFn,
	})
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}

	scriptPath, err := m.pluginFilePath(id)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}
	if err := writeFileAtomic0600(scriptPath, []byte(code)); err != nil {
		return model.ProviderPluginDTO{}, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "写入插件文件失败", err)
	}

	manifestBytes, err := json.Marshal(compiled.Manifest)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}
	settingsBytes, err := json.Marshal(mergedSettings)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}
	originsBytes, err := json.Marshal(allowedOrigins)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}

	now := time.Now().UTC().UnixMilli()
	createdAt := now
	if hasExisting && existing.CreatedAt > 0 {
		createdAt = existing.CreatedAt
	}

	rec := store.ProviderPluginRecord{
		ID:                 id,
		Enabled:            enabled,
		SourceURL:          strings.TrimSpace(req.SourceURL),
		SHA256:             compiled.SHA256,
		ManifestJSON:       string(manifestBytes),
		SettingsJSON:       string(settingsBytes),
		AllowedOriginsJSON: string(originsBytes),
		LastError:          "",
		CreatedAt:          createdAt,
		UpdatedAt:          now,
	}

	if err := m.store.UpsertProviderPlugin(ctx, rec); err != nil {
		if !hasExisting {
			_ = os.Remove(scriptPath)
		}
		return model.ProviderPluginDTO{}, err
	}

	if m.reg != nil {
		if enabled {
			if err := m.reg.RegisterPlugin(prov.RegistryEntry()); err != nil {
				return model.ProviderPluginDTO{}, err
			}
		} else {
			m.reg.UnregisterPlugin(id)
		}
	}

	m.emitLog("info", fmt.Sprintf("已安装插件 %s (%s v%s)", compiled.Manifest.Name, id, compiled.Manifest.Version), id)
	return buildPluginDTO(rec, compiled.Manifest, mergedSettings, allowedOrigins), nil
}

// Update modifies enabled state, settings (preserving secrets on placeholder), and allowed_origins, syncing Registry live.
func (m *Manager) Update(ctx context.Context, id string, req UpdateRequest) (model.ProviderPluginDTO, error) {
	if m.store == nil {
		return model.ProviderPluginDTO{}, model.NewError(model.ErrInternal, http.StatusInternalServerError, "插件存储未初始化")
	}
	if err := ValidatePluginID(id); err != nil {
		return model.ProviderPluginDTO{}, err
	}
	id = strings.TrimSpace(id)

	m.mu.Lock()
	defer m.mu.Unlock()

	rec, ok, err := m.store.GetProviderPlugin(ctx, id)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}
	if !ok {
		return model.ProviderPluginDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, fmt.Sprintf("插件 %q 不存在", id))
	}

	manifest, prevSettings, prevOrigins := decodeRecordPayloads(rec)

	nextEnabled := rec.Enabled
	if req.Enabled != nil {
		nextEnabled = *req.Enabled
	}

	nextSettings := prevSettings
	if req.Settings != nil {
		nextSettings = mergeSettingsUpdate(manifest, prevSettings, req.Settings)
	}

	nextOrigins := prevOrigins
	if req.AllowedOrigins != nil {
		nextOrigins, err = ValidateAndNormalizeOrigins(*req.AllowedOrigins)
		if err != nil {
			return model.ProviderPluginDTO{}, err
		}
	}

	scriptPath, err := m.pluginFilePath(id)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}

	var compiled *CompiledPlugin
	if rawBytes, readErr := os.ReadFile(scriptPath); readErr == nil {
		if c, cErr := ParseAndCompile(string(rawBytes)); cErr == nil {
			compiled = c
			manifest = c.Manifest
			rec.SHA256 = c.SHA256
			if mb, mErr := json.Marshal(manifest); mErr == nil {
				rec.ManifestJSON = string(mb)
			}
		} else if nextEnabled {
			rec.LastError = cErr.Error()
			rec.UpdatedAt = time.Now().UTC().UnixMilli()
			_ = m.store.UpsertProviderPlugin(ctx, rec)
			return model.ProviderPluginDTO{}, cErr
		}
	} else if nextEnabled {
		rec.LastError = readErr.Error()
		rec.UpdatedAt = time.Now().UTC().UnixMilli()
		_ = m.store.UpsertProviderPlugin(ctx, rec)
		return model.ProviderPluginDTO{}, model.WrapError(model.ErrNotFound, http.StatusNotFound, "插件脚本文件丢失", readErr)
	}

	settingsBytes, err := json.Marshal(nextSettings)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}
	originsBytes, err := json.Marshal(nextOrigins)
	if err != nil {
		return model.ProviderPluginDTO{}, err
	}

	rec.Enabled = nextEnabled
	rec.SettingsJSON = string(settingsBytes)
	rec.AllowedOriginsJSON = string(originsBytes)
	if compiled != nil {
		rec.LastError = ""
	}
	rec.UpdatedAt = time.Now().UTC().UnixMilli()

	if m.reg != nil {
		if nextEnabled && compiled != nil {
			prov, err := NewProvider(compiled, ProviderOptions{
				Pool:           m.pool,
				Settings:       nextSettings,
				AllowedOrigins: nextOrigins,
				InvokeTimeout:  m.invokeTimeout,
				LogFunc:        m.logFn,
			})
			if err != nil {
				return model.ProviderPluginDTO{}, err
			}
			if err := m.reg.RegisterPlugin(prov.RegistryEntry()); err != nil {
				return model.ProviderPluginDTO{}, err
			}
		} else if !nextEnabled {
			m.reg.UnregisterPlugin(id)
		}
	}

	if err := m.store.UpsertProviderPlugin(ctx, rec); err != nil {
		return model.ProviderPluginDTO{}, err
	}
	return buildPluginDTO(rec, manifest, nextSettings, nextOrigins), nil
}

// Delete unregisters a plugin from Registry, removes its database row, and removes its .js file.
func (m *Manager) Delete(ctx context.Context, id string) (bool, error) {
	if m.store == nil {
		return false, model.NewError(model.ErrInternal, http.StatusInternalServerError, "插件存储未初始化")
	}
	if err := ValidatePluginID(id); err != nil {
		return false, err
	}
	id = strings.TrimSpace(id)

	m.mu.Lock()
	defer m.mu.Unlock()

	if m.reg != nil {
		m.reg.UnregisterPlugin(id)
	}
	deleted, err := m.store.DeleteProviderPlugin(ctx, id)
	if err != nil {
		return false, err
	}
	if scriptPath, pErr := m.pluginFilePath(id); pErr == nil {
		if rmErr := os.Remove(scriptPath); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
			return deleted, rmErr
		}
	}
	return deleted, nil
}

// Test executes a single sandboxed plugin action and returns timing, result payload, and captured ctx.log entries.
func (m *Manager) Test(ctx context.Context, id string, req TestRequest) (model.PluginTestResultDTO, error) {
	if m.store == nil {
		return model.PluginTestResultDTO{}, model.NewError(model.ErrInternal, http.StatusInternalServerError, "插件存储未初始化")
	}
	if err := ValidatePluginID(id); err != nil {
		return model.PluginTestResultDTO{}, err
	}
	id = strings.TrimSpace(id)

	action := strings.ToLower(strings.TrimSpace(req.Action))
	switch action {
	case "search", "resolve", "playlist", "episodes", "lyrics", "metadata":
	default:
		return model.PluginTestResultDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的测试动作: %q", req.Action))
	}

	rec, ok, err := m.store.GetProviderPlugin(ctx, id)
	if err != nil {
		return model.PluginTestResultDTO{}, err
	}
	if !ok {
		return model.PluginTestResultDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, fmt.Sprintf("插件 %q 不存在", id))
	}

	scriptPath, err := m.pluginFilePath(id)
	if err != nil {
		return model.PluginTestResultDTO{}, err
	}
	rawBytes, err := os.ReadFile(scriptPath)
	if err != nil {
		return model.PluginTestResultDTO{}, model.WrapError(model.ErrNotFound, http.StatusNotFound, "读取插件脚本失败", err)
	}
	compiled, err := ParseAndCompile(string(rawBytes))
	if err != nil {
		return model.PluginTestResultDTO{}, err
	}

	_, storedSettings, allowedOrigins := decodeRecordPayloads(rec)
	prov, err := NewProvider(compiled, ProviderOptions{
		Pool:           m.pool,
		Settings:       storedSettings,
		AllowedOrigins: allowedOrigins,
		InvokeTimeout:  m.invokeTimeout,
		LogFunc:        m.logFn,
	})
	if err != nil {
		return model.PluginTestResultDTO{}, err
	}

	rc := provider.RequestContextFrom(ctx)
	_, cookies, _ := provider.SourceRequestOptions(rc, id)
	effSettings := effectiveSettingsMap(compiled.Manifest, storedSettings)
	fwdLogFn := rc.LogFunc
	if fwdLogFn == nil {
		fwdLogFn = m.logFn
	}
	collector := newLogCollector(id, rc.TaskID, fwdLogFn, collectSecrets(compiled.Manifest, effSettings, cookies))
	testCtx := withLogCollector(ctx, collector)

	start := time.Now()
	result, callErr := m.runTestAction(testCtx, prov, action, req)
	durMS := time.Since(start).Milliseconds()

	if callErr != nil {
		return model.PluginTestResultDTO{
			OK:         false,
			Action:     action,
			DurationMS: durMS,
			Logs:       collector.snapshot(),
			Error:      callErr.Error(),
		}, nil
	}
	return model.PluginTestResultDTO{
		OK:         true,
		Action:     action,
		DurationMS: durMS,
		Result:     result,
		Logs:       collector.snapshot(),
	}, nil
}

func (m *Manager) runTestAction(ctx context.Context, p *Provider, action string, req TestRequest) (any, error) {
	switch action {
	case "search":
		limit := req.Limit
		if limit <= 0 {
			limit = 10
		}
		tracks := make([]model.ProviderTrack, 0)
		summary, err := p.Search(ctx, model.SearchOptions{
			Keyword: req.Keyword,
			Limit:   limit,
			Refresh: true,
		}, func(tr model.ProviderTrack) error {
			tracks = append(tracks, tr)
			return nil
		})
		if err != nil {
			return nil, err
		}
		return map[string]any{"tracks": tracks, "count": summary.Count, "detail": summary.Detail}, nil

	case "resolve":
		tr := normalizeTestTrack(p, req.Track)
		media, err := p.Resolve(ctx, tr, model.QualityPolicy{AllowPreview: true})
		if err != nil {
			return nil, err
		}
		return map[string]any{"media": media}, nil

	case "playlist":
		tracks := make([]model.ProviderTrack, 0)
		summary, err := p.Playlist(ctx, req.Locator, func(tr model.ProviderTrack) error {
			tracks = append(tracks, tr)
			return nil
		})
		if err != nil {
			return nil, err
		}
		return map[string]any{"summary": summary, "tracks": tracks}, nil

	case "episodes":
		tr := normalizeTestTrack(p, req.Track)
		episodes, err := p.Episodes(ctx, tr)
		if err != nil {
			return nil, err
		}
		return map[string]any{"episodes": episodes}, nil

	case "lyrics":
		tr := normalizeTestTrack(p, req.Track)
		lrc, err := p.Lyrics(ctx, tr)
		if err != nil {
			return nil, err
		}
		return map[string]any{"lyrics": lrc}, nil

	case "metadata":
		limit := req.Limit
		if limit <= 0 {
			limit = 8
		}
		query := strings.TrimSpace(req.Query)
		if query == "" {
			query = strings.TrimSpace(req.Keyword)
		}
		items, err := p.SearchMetadata(ctx, query, req.Title, req.Artist, req.Album, limit)
		if err != nil {
			return nil, err
		}
		return map[string]any{"items": items}, nil
	}
	return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的测试动作")
}

func normalizeTestTrack(p *Provider, raw map[string]any) model.ProviderTrack {
	if tr, ok := p.normalizeTrackMap(raw); ok {
		return tr
	}
	return model.ProviderTrack{
		PublicID:   model.NewUUID(),
		Source:     p.ID(),
		RootSource: p.EngineName(),
		Title:      strings.TrimSpace(anyToString(raw["title"])),
		Artist:     strings.TrimSpace(anyToString(raw["artist"])),
		Album:      strings.TrimSpace(anyToString(raw["album"])),
		Duration:   provider.ParseDurationSeconds(raw["duration"]),
		Variants:   []model.MediaVariant{},
	}
}

func (m *Manager) resolveSourceCode(ctx context.Context, code, sourceURL string) (string, error) {
	if strings.TrimSpace(code) != "" {
		return code, nil
	}
	rawURL := strings.TrimSpace(sourceURL)
	if rawURL == "" {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "必须提供插件源码或 source_url")
	}
	return m.fetchPluginSource(ctx, rawURL)
}

func (m *Manager) fetchPluginSource(ctx context.Context, rawURL string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil {
		return "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "source_url 格式无效", err)
	}
	scheme := strings.ToLower(u.Scheme)
	if (scheme != "http" && scheme != "https") || u.Hostname() == "" || u.User != nil {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "source_url 仅支持不含凭据的 http/https 地址")
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "构造下载请求失败", err)
	}
	req.Header.Set("User-Agent", defaultPluginUserAgent)

	resp, err := m.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		Timeout:          15 * time.Second,
		MaxResponseBytes: MaxSourceBytes,
		Idempotent:       true,
	})
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("拉取插件脚本失败，状态码 %d", resp.StatusCode))
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, MaxSourceBytes+1))
	if err != nil {
		return "", model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "读取插件脚本失败", err)
	}
	if len(raw) > MaxSourceBytes {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "远程插件脚本体积超过 512 KiB 上限")
	}
	return string(raw), nil
}

func (m *Manager) pluginFilePath(id string) (string, error) {
	if err := ValidatePluginID(id); err != nil {
		return "", err
	}
	base := filepath.Clean(m.pluginDir)
	target := filepath.Clean(filepath.Join(base, id+".js"))
	if !strings.HasPrefix(target, base+string(filepath.Separator)) {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "非法插件路径")
	}
	return target, nil
}

func (m *Manager) emitLog(level, msg, taskID string) {
	if m.logFn != nil {
		m.logFn(level, msg, "plugin", taskID)
	}
}

func writeFileAtomic0600(targetPath string, content []byte) error {
	dir := filepath.Dir(targetPath)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".plugin-*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = tmp.Close()
			_ = os.Remove(tmpPath)
		}
	}()

	if err := tmp.Chmod(0o600); err != nil {
		return err
	}
	if _, err := tmp.Write(content); err != nil {
		return err
	}
	if err := tmp.Sync(); err != nil {
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpPath, targetPath); err != nil {
		return err
	}
	_ = os.Chmod(targetPath, 0o600)
	cleanup = false
	return nil
}

func decodeRecordPayloads(rec store.ProviderPluginRecord) (model.PluginManifestDTO, map[string]string, []string) {
	var manifest model.PluginManifestDTO
	if strings.TrimSpace(rec.ManifestJSON) != "" {
		_ = json.Unmarshal([]byte(rec.ManifestJSON), &manifest)
	}
	if manifest.Settings == nil {
		manifest.Settings = []model.PluginSettingFieldDTO{}
	}

	settings := make(map[string]string)
	if strings.TrimSpace(rec.SettingsJSON) != "" {
		_ = json.Unmarshal([]byte(rec.SettingsJSON), &settings)
	}

	origins := make([]string, 0)
	if strings.TrimSpace(rec.AllowedOriginsJSON) != "" {
		_ = json.Unmarshal([]byte(rec.AllowedOriginsJSON), &origins)
	}
	return manifest, settings, origins
}

func recordToDTO(rec store.ProviderPluginRecord) model.ProviderPluginDTO {
	manifest, settings, origins := decodeRecordPayloads(rec)
	return buildPluginDTO(rec, manifest, settings, origins)
}

func buildPluginDTO(rec store.ProviderPluginRecord, manifest model.PluginManifestDTO, settings map[string]string, origins []string) model.ProviderPluginDTO {
	if manifest.Settings == nil {
		manifest.Settings = []model.PluginSettingFieldDTO{}
	}
	if origins == nil {
		origins = []string{}
	}
	return model.ProviderPluginDTO{
		ID:             rec.ID,
		Enabled:        rec.Enabled,
		SourceURL:      rec.SourceURL,
		SHA256:         rec.SHA256,
		Manifest:       manifest,
		Settings:       redactSettingsMap(manifest, settings),
		AllowedOrigins: origins,
		LastError:      rec.LastError,
		CreatedAt:      rec.CreatedAt,
		UpdatedAt:      rec.UpdatedAt,
	}
}
