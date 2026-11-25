package httpapi

import (
	"archive/zip"
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"testing/fstest"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/library"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/plugin"
	"github.com/agonie0v0/melokeep/internal/search"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type stubProvider struct {
	id         string
	engineName string
	category   string
	label      string
	searchFn   func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error)
	resolveFn  func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error)
}

func (p *stubProvider) ID() string         { return p.id }
func (p *stubProvider) EngineName() string { return p.engineName }
func (p *stubProvider) Capabilities() model.Capability {
	return model.Capability{Tier: "native", Search: true, Playlist: true}
}
func (p *stubProvider) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if p.searchFn != nil {
		return p.searchFn(ctx, opts, emit)
	}
	return model.SearchSummary{}, nil
}
func (p *stubProvider) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	if p.resolveFn != nil {
		return p.resolveFn(ctx, track, policy)
	}
	return nil, nil
}

func newTestServer(t *testing.T, configure func(*Dependencies, string)) (*Server, *store.Store, string) {
	t.Helper()
	tmp := t.TempDir()
	dbPath := filepath.Join(tmp, "melokeep.db")
	st, err := store.Open(context.Background(), store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	musicRoot := filepath.Join(tmp, "music")
	if err := os.MkdirAll(musicRoot, 0o755); err != nil {
		t.Fatalf("mkdir music: %v", err)
	}
	canonRoot, err := files.CanonicalizeRoot(musicRoot)
	if err != nil {
		t.Fatalf("canonicalize root: %v", err)
	}
	if err := st.UpsertRoot(context.Background(), store.RootRecord{
		ID:            "root-1",
		CanonicalPath: canonRoot,
		Enabled:       true,
		Healthy:       true,
	}); err != nil {
		t.Fatalf("upsert root: %v", err)
	}
	locks := files.NewAssetLockManager(2 * time.Second)
	coord := files.NewCoordinator(st, locks, []files.Root{{ID: "root-1", CanonicalPath: canonRoot}})

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))

	primaryProv := &stubProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		category:   "开放许可",
		label:      "Audius",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			_ = emit(model.ProviderTrack{
				Source:     "AudiusMusicClient",
				SourceID:   "AudiusMusicClient_01",
				RootSource: "AudiusMusicClient",
				Title:      "Sunrise Demo",
				Artist:     "Open Artist",
				Album:      "测试专辑",
				Duration:   210,
				Variants: []model.MediaVariant{
					{
						VariantID:    "engine:flac:999",
						Label:        "FLAC 无损",
						Codec:        "flac",
						Lossless:     true,
						Availability: "resolved",
						Evidence:     "engine",
					},
				},
			})
			return model.SearchSummary{Count: 1}, nil
		},
	}
	reg, err := provider.NewRegistry([]provider.Entry{
		{
			Provider: primaryProv,
			Meta: provider.Metadata{
				Label:    "Audius",
				Category: "开放许可",
			},
		},
	})
	if err != nil {
		t.Fatalf("new registry: %v", err)
	}

	budgets := config.DefaultResourceBudgets()
	rt := config.DefaultRuntimeConfig()
	rt.Sources = []string{"AudiusMusicClient"}
	snap := config.NewSnapshot(rt, budgets)
	searchSvc := search.NewService(st, reg, budgets)
	t.Cleanup(func() { searchSvc.Close() })

	deps := Dependencies{
		Store:           st,
		Transport:       pool,
		Providers:       reg,
		Search:          searchSvc,
		Files:           coord,
		Sessions:        auth.NewSessionManager(),
		RateLimiter:     auth.NewLoginRateLimiter(),
		Budgets:         budgets,
		InitialSnapshot: snap,
		FrontendFS: fstest.MapFS{
			"index.html":           &fstest.MapFile{Data: []byte("<html><body>MeloKeep SPA</body></html>")},
			"assets/app.js":        &fstest.MapFile{Data: []byte("console.log('ok');")},
			"favicon.ico":          &fstest.MapFile{Data: []byte("ico")},
		},
		IsWindows: true,
		Platform:  "win32",
		WorkDir:   canonRoot,
	}
	if configure != nil {
		configure(&deps, canonRoot)
	}
	srv, err := NewServer(deps)
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	return srv, st, canonRoot
}

var pathParamRe = regexp.MustCompile(`\{[^}]+\}`)

func normalizeRouteKey(method, path string) string {
	return method + " " + pathParamRe.ReplaceAllString(path, "{}")
}

func TestRouteParityWithContract(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "contracts", "v1", "routes.json"))
	if err != nil {
		t.Fatalf("read contracts/v1/routes.json: %v", err)
	}
	var doc struct {
		EffectiveRoutes []struct {
			Method string `json:"method"`
			Path   string `json:"path"`
		} `json:"effective_routes"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("parse routes.json: %v", err)
	}
	srv, _, _ := newTestServer(t, nil)
	have := make(map[string]bool)
	for _, r := range srv.Routes() {
		have[normalizeRouteKey(r.Method, r.Path)] = true
	}
	if !have["GET /api/library/files"] {
		t.Fatalf("missing GET /api/library/files route")
	}
	for _, er := range doc.EffectiveRoutes {
		key := normalizeRouteKey(er.Method, er.Path)
		if !have[key] {
			t.Fatalf("missing contract route: %s %s", er.Method, er.Path)
		}
	}
}

func TestHealthMetaAuthCSRFTrustedProxyAndJSON422(t *testing.T) {
	user := "admin"
	pass := "secret-pass"
	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.AuthEnabled = true
		d.AuthUserDigest = sha256.Sum256([]byte(user))
		d.AuthPassDigest = sha256.Sum256([]byte(pass))
		d.TrustedProxies = []string{"10.0.0.1"}
	})

	// Health & auth status unauthenticated.
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/health", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("health status = %d", rec.Code)
	}

	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/auth/status", nil))
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"required":true`) || !strings.Contains(rec.Body.String(), `"authenticated":false`) {
		t.Fatalf("auth status unexpected: %d %s", rec.Code, rec.Body.String())
	}

	// Protected route without session -> 401.
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/config", nil))
	if rec.Code != http.StatusUnauthorized || !strings.Contains(rec.Body.String(), "登录已失效，请重新登录") {
		t.Fatalf("expected 401, got %d %s", rec.Code, rec.Body.String())
	}

	// Missing CSRF header -> 403.
	req := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass"}`))
	req.RemoteAddr = "198.51.100.10:1234"
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden || !strings.Contains(rec.Body.String(), "请求来源校验失败") {
		t.Fatalf("expected 403 CSRF, got %d %s", rec.Code, rec.Body.String())
	}

	// Wrong password from untrusted IP with spoofed X-Forwarded-For should still rate-limit by RemoteAddr.
	for i := 0; i <= auth.LoginFailureLimit; i++ {
		r := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"wrong"}`))
		r.Header.Set("X-MeloKeep-Request", "1")
		r.Header.Set("X-Forwarded-For", "203.0.113."+string(rune('1'+i)))
		r.RemoteAddr = "198.51.100.10:4444"
		w := httptest.NewRecorder()
		srv.ServeHTTP(w, r)
		if i < auth.LoginFailureLimit && w.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d expected 401, got %d", i, w.Code)
		}
		if i == auth.LoginFailureLimit {
			if w.Code != http.StatusTooManyRequests || w.Header().Get("Retry-After") != "300" {
				t.Fatalf("expected 429 + Retry-After: 300, got %d hdr=%q body=%s", w.Code, w.Header().Get("Retry-After"), w.Body.String())
			}
		}
	}

	// Spoofed multi-hop X-Forwarded-For through a trusted proxy picks right-most untrusted IP.
	for i := 0; i <= auth.LoginFailureLimit; i++ {
		r := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"wrong"}`))
		r.Header.Set("X-MeloKeep-Request", "1")
		r.Header.Set("X-Forwarded-For", "1.2.3."+string(rune('1'+i))+", 203.0.113.88, 10.0.0.1")
		r.RemoteAddr = "10.0.0.1:9001"
		w := httptest.NewRecorder()
		srv.ServeHTTP(w, r)
		if i < auth.LoginFailureLimit && w.Code != http.StatusUnauthorized {
			t.Fatalf("trusted proxy attempt %d expected 401, got %d", i, w.Code)
		}
		if i == auth.LoginFailureLimit && w.Code != http.StatusTooManyRequests {
			t.Fatalf("expected 429 for right-most untrusted XFF client, got %d %s", w.Code, w.Body.String())
		}
	}

	// Trusted proxy honors X-Forwarded-For, so login succeeds for distinct client IP.
	r := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass"}`))
	r.Header.Set("X-MeloKeep-Request", "1")
	r.Header.Set("X-Forwarded-For", "203.0.113.99")
	r.RemoteAddr = "10.0.0.1:9000"
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, r)
	if rec.Code != http.StatusOK {
		t.Fatalf("login via trusted proxy failed: %d %s", rec.Code, rec.Body.String())
	}
	cookies := rec.Result().Cookies()
	var sessionCookie *http.Cookie
	var sawRootClear bool
	for _, c := range cookies {
		if c.Name == auth.SessionCookieName && c.Path == "/api" && c.Value != "" {
			sessionCookie = c
		}
		if c.Name == auth.SessionCookieName && c.Path == "/" && c.MaxAge == 0 {
			sawRootClear = true
		}
	}
	if sessionCookie == nil || !sawRootClear {
		t.Fatalf("expected /api session cookie and / clear cookie, got %+v", cookies)
	}

	// Authenticated GET /api/config -> 200.
	cfgReq := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	cfgReq.AddCookie(sessionCookie)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, cfgReq)
	if rec.Code != http.StatusOK {
		t.Fatalf("auth config failed: %d %s", rec.Code, rec.Body.String())
	}

	// Corrupt JSON -> FastAPI-compatible 422.
	badReq := httptest.NewRequest(http.MethodPost, "/api/playlists", strings.NewReader(`{bad`))
	badReq.Header.Set("X-MeloKeep-Request", "1")
	badReq.AddCookie(sessionCookie)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, badReq)
	if rec.Code != http.StatusUnprocessableEntity || !strings.Contains(rec.Body.String(), `"json_invalid"`) {
		t.Fatalf("expected 422 json_invalid, got %d %s", rec.Code, rec.Body.String())
	}

	// Unknown /api/* -> JSON 404 (not SPA HTML), and wrong method -> JSON 405.
	unk := httptest.NewRecorder()
	srv.ServeHTTP(unk, httptest.NewRequest(http.MethodGet, "/api/unknown/route", nil))
	if unk.Code != http.StatusNotFound || !strings.Contains(unk.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("expected JSON 404 for unknown /api, got %d ct=%q body=%s", unk.Code, unk.Header().Get("Content-Type"), unk.Body.String())
	}
	mna := httptest.NewRecorder()
	srv.ServeHTTP(mna, httptest.NewRequest(http.MethodPut, "/api/auth/login", nil))
	if mna.Code != http.StatusMethodNotAllowed || !strings.Contains(mna.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("expected JSON 405, got %d ct=%q body=%s", mna.Code, mna.Header().Get("Content-Type"), mna.Body.String())
	}

	// SPA route returns index.html.
	spa := httptest.NewRecorder()
	srv.ServeHTTP(spa, httptest.NewRequest(http.MethodGet, "/library/view", nil))
	if spa.Code != http.StatusOK || !strings.Contains(spa.Body.String(), "MeloKeep SPA") {
		t.Fatalf("expected SPA HTML, got %d %s", spa.Code, spa.Body.String())
	}
}

func TestConfigHiddenMergeAndTransportInvalidation(t *testing.T) {
	srv, _, _ := newTestServer(t, nil)
	prevRev := srv.currentSnapshot().Revision

	putBody := `{
		"sources":["AudiusMusicClient"],
		"results_per_source":15,
		"source_settings":{"AudiusMusicClient":{"cookie":"uin=secret123;","proxy":"http://user:pass@127.0.0.1:8888"}}
	}`
	req := httptest.NewRequest(http.MethodPut, "/api/config", strings.NewReader(putBody))
	req.Header.Set("X-MeloKeep-Request", "1")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("PUT /api/config failed: %d %s", rec.Code, rec.Body.String())
	}
	midRev := srv.currentSnapshot().Revision
	if midRev == prevRev {
		t.Fatalf("expected revision change after config update")
	}

	// GET /api/config must redact secrets.
	getRec := httptest.NewRecorder()
	srv.ServeHTTP(getRec, httptest.NewRequest(http.MethodGet, "/api/config", nil))
	if getRec.Code != http.StatusOK {
		t.Fatalf("GET /api/config failed: %d", getRec.Code)
	}
	if strings.Contains(getRec.Body.String(), "secret123") || strings.Contains(getRec.Body.String(), "user:pass") {
		t.Fatalf("GET /api/config leaked secrets: %s", getRec.Body.String())
	}

	// PUT /api/config with [已隐藏] preserves secret cookie/proxy.
	put2 := `{
		"sources":["AudiusMusicClient"],
		"results_per_source":20,
		"source_settings":{"AudiusMusicClient":{"cookie":"` + config.HiddenPlaceholder + `","proxy":"` + config.HiddenPlaceholder + `"}}
	}`
	req2 := httptest.NewRequest(http.MethodPut, "/api/config", strings.NewReader(put2))
	req2.Header.Set("X-MeloKeep-Request", "1")
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("second PUT /api/config failed: %d %s", rec2.Code, rec2.Body.String())
	}
	gotSS := srv.currentSnapshot().Runtime.SourceSettings["AudiusMusicClient"]
	if gotSS.Cookie != "uin=secret123;" || gotSS.Proxy != "http://user:pass@127.0.0.1:8888" {
		t.Fatalf("hidden merge lost secrets: %+v", gotSS)
	}
}

func TestSearchSSEAndFavoritesCollections(t *testing.T) {
	srv, _, _ := newTestServer(t, nil)

	// Empty search -> 400.
	emptyReq := httptest.NewRequest(http.MethodPost, "/api/search/jobs", strings.NewReader(`{"keyword":"   "}`))
	emptyReq.Header.Set("X-MeloKeep-Request", "1")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, emptyReq)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for empty search, got %d %s", rec.Code, rec.Body.String())
	}

	// Start search job.
	startReq := httptest.NewRequest(http.MethodPost, "/api/search/jobs", strings.NewReader(`{"keyword":"Sunrise Demo","sources":["AudiusMusicClient"]}`))
	startReq.Header.Set("X-MeloKeep-Request", "1")
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, startReq)
	if rec.Code != http.StatusOK {
		t.Fatalf("search start failed: %d %s", rec.Code, rec.Body.String())
	}
	var startResp struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &startResp)
	if startResp.ID == "" {
		t.Fatalf("missing search job id")
	}

	// Stream SSE events via real httptest server to avoid ResponseRecorder blocking nuances.
	ts := httptest.NewServer(srv)
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/api/search/jobs/" + startResp.ID + "/events")
	if err != nil {
		t.Fatalf("SSE get: %v", err)
	}
	bodyBytes, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK || !strings.Contains(resp.Header.Get("Content-Type"), "text/event-stream") {
		t.Fatalf("unexpected SSE status/header: %d %v", resp.StatusCode, resp.Header)
	}
	sseText := string(bodyBytes)
	if !strings.Contains(sseText, "event: track") || !strings.Contains(sseText, "event: source") || !strings.Contains(sseText, "event: done") {
		t.Fatalf("missing SSE events: %s", sseText)
	}

	// Extract track ID from SSE track event.
	var trackID string
	sc := bufio.NewScanner(strings.NewReader(sseText))
	for sc.Scan() {
		line := sc.Text()
		if strings.HasPrefix(line, "data: ") && strings.Contains(line, `"AudiusMusicClient_01"`) {
			var tr model.TrackDTO
			if json.Unmarshal([]byte(strings.TrimPrefix(line, "data: ")), &tr) == nil {
				trackID = tr.ID
				break
			}
		}
	}
	if trackID == "" {
		t.Fatalf("failed to parse track id from SSE: %s", sseText)
	}

	// Replay with Last-Event-ID: 1 must omit id: 1.
	reqReplay, _ := http.NewRequest(http.MethodGet, ts.URL+"/api/search/jobs/"+startResp.ID+"/events", nil)
	reqReplay.Header.Set("Last-Event-ID", "1")
	resp2, err := http.DefaultClient.Do(reqReplay)
	if err != nil {
		t.Fatalf("SSE replay: %v", err)
	}
	replayBytes, _ := io.ReadAll(resp2.Body)
	_ = resp2.Body.Close()
	if strings.Contains(string(replayBytes), "\nid: 1\n") || strings.HasPrefix(string(replayBytes), "id: 1\n") {
		t.Fatalf("Last-Event-ID replay included id 1: %s", string(replayBytes))
	}

	// Invalid after -> 400, unknown job -> 404, DELETE missing -> 200.
	badAfter := httptest.NewRecorder()
	srv.ServeHTTP(badAfter, httptest.NewRequest(http.MethodGet, "/api/search/jobs/"+startResp.ID+"/events?after=abc", nil))
	if badAfter.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 on invalid after, got %d", badAfter.Code)
	}
	unkSSE := httptest.NewRecorder()
	srv.ServeHTTP(unkSSE, httptest.NewRequest(http.MethodGet, "/api/search/jobs/missing-job/events", nil))
	if unkSSE.Code != http.StatusNotFound {
		t.Fatalf("expected 404 on missing job, got %d", unkSSE.Code)
	}
	delReq := httptest.NewRequest(http.MethodDelete, "/api/search/jobs/missing-job", nil)
	delReq.Header.Set("X-MeloKeep-Request", "1")
	delRec := httptest.NewRecorder()
	srv.ServeHTTP(delRec, delReq)
	if delRec.Code != http.StatusOK || !strings.Contains(delRec.Body.String(), `"cancelled":true`) {
		t.Fatalf("expected cancelled:true, got %d %s", delRec.Code, delRec.Body.String())
	}

	// Favorites & collections round-trip.
	favPut := httptest.NewRequest(http.MethodPut, "/api/favorites/"+trackID, nil)
	favPut.Header.Set("X-MeloKeep-Request", "1")
	favRec := httptest.NewRecorder()
	srv.ServeHTTP(favRec, favPut)
	if favRec.Code != http.StatusOK {
		t.Fatalf("favorite put failed: %d %s", favRec.Code, favRec.Body.String())
	}

	plReq := httptest.NewRequest(http.MethodPost, "/api/playlists", strings.NewReader(`{"name":"测试歌单","track_ids":["`+trackID+`"]}`))
	plReq.Header.Set("X-MeloKeep-Request", "1")
	plRec := httptest.NewRecorder()
	srv.ServeHTTP(plRec, plReq)
	if plRec.Code != http.StatusOK {
		t.Fatalf("create playlist failed: %d %s", plRec.Code, plRec.Body.String())
	}
	var plDTO model.SavedPlaylistDTO
	_ = json.Unmarshal(plRec.Body.Bytes(), &plDTO)
	if plDTO.ID == "" || len(plDTO.Tracks) != 1 || plDTO.Tracks[0].ID != trackID {
		t.Fatalf("unexpected playlist DTO: %+v", plDTO)
	}
}

func TestLibraryFilesPaginationAndMediaRangeAndStream(t *testing.T) {
	var upstreamCalls atomic.Int32
	var srv *Server
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		call := upstreamCalls.Add(1)
		if r.URL.Path == "/expired" || (r.URL.Path == "/audio" && call == 1) {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		if r.URL.Path == "/html" {
			w.Header().Set("Content-Type", "text/html")
			_, _ = w.Write([]byte("<html>error</html>"))
			return
		}
		payload := bytes.Repeat([]byte("A"), 128)
		if rng := r.Header.Get("Range"); rng == "bytes=0-15" {
			w.Header().Set("Content-Type", "audio/mpeg")
			w.Header().Set("Content-Range", "bytes 0-15/128")
			w.Header().Set("Accept-Ranges", "bytes")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(payload[:16])
			return
		}
		w.Header().Set("Content-Type", "audio/mpeg")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
	}))
	defer upstream.Close()

	srv, st, root := newTestServer(t, nil)
	// Replace stub resolveFn via new server with custom provider for stream test.
	var mode atomic.Int32
	reg, _ := provider.NewRegistry([]provider.Entry{
		{
			Provider: &stubProvider{
				id:         "AudiusMusicClient",
				engineName: "AudiusMusicClient",
				resolveFn: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
					path := "/audio"
					if mode.Load() == 1 {
						path = "/html"
					}
					return []model.ResolvedMedia{{
						VariantID: "v1",
						URL:       upstream.URL + path,
					}}, nil
				},
			},
			Meta: provider.Metadata{
				Label:    "Audius",
				Category: "开放许可",
			},
		},
	})
	srv.deps.Providers = reg

	// Seed 3 audio files + assets.
	audioBytes := bytes.Repeat([]byte("RIFF0123"), 32)
	var assetIDs []string
	for _, rel := range []string{"a/01.wav", "a/02.wav", "b/03.wav"} {
		abs := filepath.Join(root, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(abs, audioBytes, 0o644); err != nil {
			t.Fatal(err)
		}
		a, err := st.UpsertAsset(context.Background(), store.AssetRecord{
			RootID:  "root-1",
			RelPath: rel,
			Size:    int64(len(audioBytes)),
			MTimeNS: 1000,
			Title:   rel,
			Editable: true,
		}, 0, true)
		if err != nil {
			t.Fatal(err)
		}
		assetIDs = append(assetIDs, a.ID)
	}
	// Sidecar cover for first file.
	_ = os.WriteFile(filepath.Join(root, "a", "01.jpg"), []byte{0xFF, 0xD8, 0xFF, 0xD9}, 0o644)
	_, _ = st.SetLibraryIndexing(context.Background(), true, false)

	// Page 1 of /api/library/files?limit=2&sort=title
	p1Rec := httptest.NewRecorder()
	srv.ServeHTTP(p1Rec, httptest.NewRequest(http.MethodGet, "/api/library/files?limit=2&sort=title", nil))
	if p1Rec.Code != http.StatusOK {
		t.Fatalf("page 1 failed: %d %s", p1Rec.Code, p1Rec.Body.String())
	}
	var page1 model.LibraryFilesPageDTO
	_ = json.Unmarshal(p1Rec.Body.Bytes(), &page1)
	if len(page1.Items) != 2 || page1.NextCursor == nil || !page1.Indexing {
		t.Fatalf("unexpected page1: %+v", page1)
	}

	// Filter/sort mismatch with old cursor -> 400.
	badCur := httptest.NewRecorder()
	srv.ServeHTTP(badCur, httptest.NewRequest(http.MethodGet, "/api/library/files?limit=2&sort=artist&cursor="+*page1.NextCursor, nil))
	if badCur.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 on cursor sort mismatch, got %d %s", badCur.Code, badCur.Body.String())
	}

	// Page 2 succeeds.
	p2Rec := httptest.NewRecorder()
	srv.ServeHTTP(p2Rec, httptest.NewRequest(http.MethodGet, "/api/library/files?limit=2&sort=title&cursor="+*page1.NextCursor, nil))
	if p2Rec.Code != http.StatusOK {
		t.Fatalf("page 2 failed: %d %s", p2Rec.Code, p2Rec.Body.String())
	}
	var page2 model.LibraryFilesPageDTO
	_ = json.Unmarshal(p2Rec.Body.Bytes(), &page2)
	if len(page2.Items) != 1 || page2.NextCursor != nil {
		t.Fatalf("unexpected page2: %+v", page2)
	}

	// Bump index_revision -> old cursor returns 409.
	_, _ = st.SetLibraryIndexing(context.Background(), false, true)
	staleRec := httptest.NewRecorder()
	srv.ServeHTTP(staleRec, httptest.NewRequest(http.MethodGet, "/api/library/files?limit=2&sort=title&cursor="+*page1.NextCursor, nil))
	if staleRec.Code != http.StatusConflict {
		t.Fatalf("expected 409 on stale index_revision, got %d %s", staleRec.Code, staleRec.Body.String())
	}

	// Legacy /api/files returns array of 3.
	legRec := httptest.NewRecorder()
	srv.ServeHTTP(legRec, httptest.NewRequest(http.MethodGet, "/api/files", nil))
	if legRec.Code != http.StatusOK {
		t.Fatalf("GET /api/files failed: %d", legRec.Code)
	}
	var legItems []model.LibraryFileDTO
	_ = json.Unmarshal(legRec.Body.Bytes(), &legItems)
	if len(legItems) != 3 {
		t.Fatalf("expected 3 legacy files, got %d", len(legItems))
	}

	// Local audio GET, HEAD, 206, 416, attachment, cover, traversal 403.
	audPath := "/api/files/audio?path=asset:" + assetIDs[0]
	headRec := httptest.NewRecorder()
	srv.ServeHTTP(headRec, httptest.NewRequest(http.MethodHead, audPath, nil))
	if headRec.Code != http.StatusOK || headRec.Body.Len() != 0 || headRec.Header().Get("Accept-Ranges") != "bytes" {
		t.Fatalf("HEAD audio unexpected: %d len=%d hdr=%v", headRec.Code, headRec.Body.Len(), headRec.Header())
	}
	rngReq := httptest.NewRequest(http.MethodGet, audPath, nil)
	rngReq.Header.Set("Range", "bytes=0-15")
	rngRec := httptest.NewRecorder()
	srv.ServeHTTP(rngRec, rngReq)
	if rngRec.Code != http.StatusPartialContent || rngRec.Body.Len() != 16 || rngRec.Header().Get("Content-Range") != "bytes 0-15/256" {
		t.Fatalf("206 range unexpected: %d len=%d cr=%q", rngRec.Code, rngRec.Body.Len(), rngRec.Header().Get("Content-Range"))
	}
	oosReq := httptest.NewRequest(http.MethodGet, audPath, nil)
	oosReq.Header.Set("Range", "bytes=9999-")
	oosRec := httptest.NewRecorder()
	srv.ServeHTTP(oosRec, oosReq)
	if oosRec.Code != http.StatusRequestedRangeNotSatisfiable {
		t.Fatalf("expected 416, got %d", oosRec.Code)
	}
	attRec := httptest.NewRecorder()
	srv.ServeHTTP(attRec, httptest.NewRequest(http.MethodGet, "/api/files/content?path=asset:"+assetIDs[0], nil))
	if attRec.Code != http.StatusOK || !strings.Contains(attRec.Header().Get("Content-Disposition"), "attachment") {
		t.Fatalf("content disposition unexpected: %d %q", attRec.Code, attRec.Header().Get("Content-Disposition"))
	}
	covRec := httptest.NewRecorder()
	srv.ServeHTTP(covRec, httptest.NewRequest(http.MethodGet, "/api/files/cover?path=asset:"+assetIDs[0], nil))
	if covRec.Code != http.StatusOK || covRec.Header().Get("Content-Type") != "image/jpeg" {
		t.Fatalf("cover unexpected: %d %q", covRec.Code, covRec.Header().Get("Content-Type"))
	}
	travRec := httptest.NewRecorder()
	srv.ServeHTTP(travRec, httptest.NewRequest(http.MethodGet, "/api/files/audio?path=../secret.mp3", nil))
	if travRec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 traversal, got %d %s", travRec.Code, travRec.Body.String())
	}

	// Remote /api/stream/{sid}: retries once on 403, forwards Range 206, rejects HTML with 502.
	trID, err := st.UpsertProviderTrack(context.Background(), store.ProviderTrackRecord{
		Source:     "AudiusMusicClient",
		Locator:    "remote-1",
		RootSource: "AudiusMusicClient",
		Title:      "RemoteSong",
		Artist:     "Tester",
	}, []model.MediaVariant{{VariantID: "v1", Label: "MP3", Codec: "mp3", Availability: "resolved"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := st.PutPublicRef(context.Background(), model.PublicRef{
		PublicID: "remote-pub-1",
		Kind:     "candidate",
		TargetID: trID,
		Source:   "AudiusMusicClient",
		SourceID: "remote-1",
	}, ""); err != nil {
		t.Fatal(err)
	}

	stReq := httptest.NewRequest(http.MethodGet, "/api/stream/remote-pub-1", nil)
	stReq.Header.Set("Range", "bytes=0-15")
	stRec := httptest.NewRecorder()
	srv.ServeHTTP(stRec, stReq)
	if stRec.Code != http.StatusPartialContent || stRec.Body.Len() != 16 || upstreamCalls.Load() < 2 {
		t.Fatalf("remote stream retry/206 failed: code=%d len=%d calls=%d body=%s", stRec.Code, stRec.Body.Len(), upstreamCalls.Load(), stRec.Body.String())
	}

	mode.Store(1)
	trID2, err := st.UpsertProviderTrack(context.Background(), store.ProviderTrackRecord{
		Source:     "AudiusMusicClient",
		Locator:    "remote-2",
		RootSource: "AudiusMusicClient",
		Title:      "RemoteHTML",
		Artist:     "Tester",
	}, []model.MediaVariant{{VariantID: "v2", Label: "MP3", Codec: "mp3", Availability: "resolved"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := st.PutPublicRef(context.Background(), model.PublicRef{
		PublicID: "remote-pub-2",
		Kind:     "candidate",
		TargetID: trID2,
		Source:   "AudiusMusicClient",
		SourceID: "remote-2",
	}, ""); err != nil {
		t.Fatal(err)
	}
	htmlReq := httptest.NewRequest(http.MethodGet, "/api/stream/remote-pub-2", nil)
	htmlRec := httptest.NewRecorder()
	srv.ServeHTTP(htmlRec, htmlReq)
	if htmlRec.Code != http.StatusBadGateway {
		t.Fatalf("expected 502 on HTML upstream stream, got %d %s", htmlRec.Code, htmlRec.Body.String())
	}
}

func TestLibraryReportCSVFormulaNeutralizationAndBOM(t *testing.T) {
	srv, st, root := newTestServer(t, func(d *Dependencies, root string) {
		// Library service wired below once store is created.
	})
	coord := srv.deps.Files
	libSvc := library.NewService(st, coord, nil, nil, nil, library.ServiceOptions{})
	srv.deps.Library = libSvc

	audioFile := filepath.Join(root, "evil.mp3")
	_ = os.WriteFile(audioFile, []byte("ID3"), 0o644)
	job, err := st.CreateLibraryPreview(context.Background(), store.CreateLibraryPreviewParams{
		JobID:    "job-csv-1",
		Action:   "organize",
		OwnerKey: "web:anon",
		Items: []store.LibraryItemInput{
			{
				ItemID:       "item-1",
				OriginalPath: audioFile,
				TargetPath:   audioFile,
				Name:         "=SUM(A1)",
				State:        model.LibraryItemComplete,
				BeforeJSON:   `{"title":"+cmd"}`,
				ProposedJSON: `{"title":"@evil"}`,
			},
		},
	})
	if err != nil {
		t.Fatalf("CreateLibraryPreview: %v", err)
	}

	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/library/jobs/"+job.ID+"/report", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("report failed: %d %s", rec.Code, rec.Body.String())
	}
	raw := rec.Body.Bytes()
	if len(raw) < 3 || raw[0] != 0xEF || raw[1] != 0xBB || raw[2] != 0xBF {
		t.Fatalf("missing UTF-8 BOM in CSV report")
	}
	rows, err := csv.NewReader(bytes.NewReader(raw[3:])).ReadAll()
	if err != nil || len(rows) != 2 {
		t.Fatalf("parse CSV failed: err=%v rows=%v", err, rows)
	}
	if rows[1][0] != "'=SUM(A1)" {
		t.Fatalf("CSV formula cell not neutralized: %q", rows[1][0])
	}
}

func TestBatchDownloadEnqueueWithIdempotencyKey(t *testing.T) {
	srv, st, _ := newTestServer(t, nil)
	dlSvc := download.NewService(st, srv.deps.Transport, srv.deps.Providers, srv.deps.Files, nil, nil, download.Options{
		StagingRoot: t.TempDir(),
	})
	t.Cleanup(func() { _ = dlSvc.Close() })
	srv.deps.Download = dlSvc

	ctx := context.Background()
	var ids []string
	for _, loc := range []string{"batch-1", "batch-2"} {
		trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
			Source:     "AudiusMusicClient",
			Locator:    loc,
			RootSource: "AudiusMusicClient",
			Title:      "Track " + loc,
			Artist:     "Tester",
		}, []model.MediaVariant{{VariantID: "v1-" + loc, Label: "MP3", Codec: "mp3", Availability: "resolved"}})
		if err != nil {
			t.Fatal(err)
		}
		pubID := "pub-" + loc
		if err := st.PutPublicRef(ctx, model.PublicRef{
			PublicID: pubID,
			Kind:     "candidate",
			TargetID: trID,
			Source:   "AudiusMusicClient",
			SourceID: loc,
		}, ""); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, pubID)
	}

	payload := `[{"track_id":"` + ids[0] + `","quality_id":"v1-batch-1"},{"track_id":"` + ids[1] + `","quality_id":"v1-batch-2"}]`
	req1 := httptest.NewRequest(http.MethodPost, "/api/downloads", strings.NewReader(payload))
	req1.Header.Set("Content-Type", "application/json")
	req1.Header.Set("X-MeloKeep-Request", "1")
	req1.Header.Set("Idempotency-Key", "batch-key-1")
	rec1 := httptest.NewRecorder()
	srv.ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusOK {
		t.Fatalf("batch download with Idempotency-Key failed: code=%d body=%s", rec1.Code, rec1.Body.String())
	}
	var jobs1 []model.DownloadJobDTO
	if err := json.Unmarshal(rec1.Body.Bytes(), &jobs1); err != nil || len(jobs1) != 2 {
		t.Fatalf("expected 2 download jobs, got err=%v body=%s", err, rec1.Body.String())
	}

	req2 := httptest.NewRequest(http.MethodPost, "/api/downloads", strings.NewReader(payload))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("X-MeloKeep-Request", "1")
	req2.Header.Set("Idempotency-Key", "batch-key-1")
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("idempotent replay failed: code=%d body=%s", rec2.Code, rec2.Body.String())
	}
	var jobs2 []model.DownloadJobDTO
	if err := json.Unmarshal(rec2.Body.Bytes(), &jobs2); err != nil || len(jobs2) != 2 {
		t.Fatalf("expected 2 replayed jobs, got err=%v body=%s", err, rec2.Body.String())
	}
	if jobs1[0].ID != jobs2[0].ID || jobs1[1].ID != jobs2[1].ID {
		t.Fatalf("idempotent replay mismatch: %v vs %v", jobs1, jobs2)
	}
}

func TestEngineConfigMatchesFrontendSettingsContract(t *testing.T) {
	srv, _, _ := newTestServer(t, nil)

	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/engine/config", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/engine/config status=%d body=%s", rec.Code, rec.Body.String())
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal /api/engine/config: %v", err)
	}
	for _, key := range []string{"music_sources", "global_client_cfg", "cookies", "proxy_sources", "init_music_clients_cfg", "clients_threadings", "requests_overrides", "search_rules", "default_threads", "request_timeout"} {
		if _, ok := got[key]; !ok || got[key] == nil {
			t.Fatalf("expected non-nil %q in /api/engine/config response: %s", key, rec.Body.String())
		}
	}
	sources, _ := got["music_sources"].([]any)
	if len(sources) == 0 || sources[0] != "AudiusMusicClient" {
		t.Fatalf("expected music_sources to use EngineName, got %v", got["music_sources"])
	}

	putBody := `{"music_sources":["AudiusMusicClient"],"global_client_cfg":{"search_size_per_source":8},"request_timeout":25,"default_threads":6,"cookies":{"AudiusMusicClient":"uin=12345"}}`
	putReq := httptest.NewRequest(http.MethodPut, "/api/engine/config", strings.NewReader(putBody))
	putReq.Header.Set("Content-Type", "application/json")
	putReq.Header.Set("X-MeloKeep-Request", "1")
	putRec := httptest.NewRecorder()
	srv.ServeHTTP(putRec, putReq)
	if putRec.Code != http.StatusOK {
		t.Fatalf("PUT /api/engine/config status=%d body=%s", putRec.Code, putRec.Body.String())
	}
	snap := srv.currentSnapshot()
	if snap.Runtime.ResultsPerSource != 8 || snap.Runtime.SearchTimeoutSeconds != 25 || snap.Runtime.DownloadConcurrency != 6 {
		t.Fatalf("expected runtime fields updated, got %+v", snap.Runtime)
	}
	if snap.Runtime.SourceSettings["AudiusMusicClient"].Cookie != "uin=12345" {
		t.Fatalf("expected source cookie saved, got %+v", snap.Runtime.SourceSettings)
	}
}

func TestTrackLyricsOnlineCachedAndOnDemandResolve(t *testing.T) {
	resolvedLyrics := "[00:02.00]在线解析歌词"
	prov := &stubProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		resolveFn: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			return []model.ResolvedMedia{
				{
					VariantID: "v_1",
					Variant: model.MediaVariant{
						VariantID: "v_1",
						Codec:     "flac",
						Lossless:  true,
					},
					URL:    "https://cdn.example.com/test.flac",
					Expiry: time.Now().Add(time.Minute),
					Codec:  "flac",
					Lyrics: resolvedLyrics,
				},
			}, nil
		},
	}
	srv, st, _ := newTestServer(t, func(deps *Dependencies, _ string) {
		reg, regErr := provider.NewRegistry([]provider.Entry{
			{
				Provider: prov,
				Meta: provider.Metadata{
					Label:    "Audius",
					Category: "开放许可",
				},
			},
		})
		if regErr != nil {
			t.Fatalf("new registry: %v", regErr)
		}
		deps.Providers = reg
	})
	ctx := context.Background()

	// 1. Track with pre-persisted lyrics in MetadataJSON returns directly.
	idPersisted := "trk-lyrics-persisted"
	_, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		ID:           idPersisted,
		Source:       "AudiusMusicClient",
		Locator:      "mid-1",
		RootSource:   "AudiusMusicClient",
		Title:        "Persisted",
		MetadataJSON: `{"lyrics":"[00:00.50]已缓存歌词"}`,
		ProviderJSON: "{}",
	}, nil)
	if err != nil {
		t.Fatalf("UpsertProviderTrack persisted: %v", err)
	}
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: idPersisted, Kind: "candidate", TargetID: idPersisted, Source: "AudiusMusicClient", SourceID: "mid-1"}, "")

	rec1 := httptest.NewRecorder()
	srv.ServeHTTP(rec1, httptest.NewRequest(http.MethodGet, "/api/tracks/"+idPersisted+"/lyrics", nil))
	if rec1.Code != http.StatusOK || !strings.Contains(rec1.Body.String(), "已缓存歌词") {
		t.Fatalf("expected persisted online lyrics, got status=%d body=%s", rec1.Code, rec1.Body.String())
	}

	// 2. Track with "NULL" metadata falls back to on-demand provider Resolve for lyrics.
	idResolve := "trk-lyrics-resolve"
	_, err = st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		ID:           idResolve,
		Source:       "AudiusMusicClient",
		Locator:      "mid-2",
		RootSource:   "AudiusMusicClient",
		Title:        "NeedsResolve",
		MetadataJSON: `{"lyrics":"NULL"}`,
		ProviderJSON: "{}",
	}, nil)
	if err != nil {
		t.Fatalf("UpsertProviderTrack resolve: %v", err)
	}
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: idResolve, Kind: "candidate", TargetID: idResolve, Source: "AudiusMusicClient", SourceID: "mid-2"}, "")

	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/api/tracks/"+idResolve+"/lyrics", nil))
	if rec2.Code != http.StatusOK || !strings.Contains(rec2.Body.String(), "在线解析歌词") {
		t.Fatalf("expected on-demand resolved lyrics, got status=%d body=%s", rec2.Code, rec2.Body.String())
	}
}

func TestLogBufferCapacityIDsAndRedaction(t *testing.T) {
	buf := NewLogBuffer(0)
	for i := 0; i < 805; i++ {
		buf.Push("warn", "请求 https://api.example.com/music?token=secret123&k=1 失败", "search", "job-1")
	}
	entries := buf.List()
	if len(entries) != 800 {
		t.Fatalf("expected default ring capacity 800, got %d", len(entries))
	}
	if entries[0].ID != 6 || entries[len(entries)-1].ID != 805 {
		t.Fatalf("expected monotonically increasing IDs 6..805, got first=%d last=%d", entries[0].ID, entries[len(entries)-1].ID)
	}
	if entries[0].Level != "warning" {
		t.Fatalf("expected warn normalized to warning, got %q", entries[0].Level)
	}
	if strings.Contains(entries[0].Message, "secret123") {
		t.Fatalf("expected URL token redacted from log message, got %q", entries[0].Message)
	}
}

type episodeStubProvider struct {
	stubProvider
}

func (p *episodeStubProvider) Episodes(_ context.Context, tr model.ProviderTrack) ([]model.ProviderTrack, error) {
	return []model.ProviderTrack{
		{Source: "itunes", SourceID: tr.SourceID + "#ep:1", RootSource: "ITunesMusicClient", Title: "Ep 1", Artist: tr.Artist, Album: tr.Album, Duration: 120},
		{Source: "itunes", SourceID: tr.SourceID + "#ep:2", RootSource: "ITunesMusicClient", Title: "Ep 2", Artist: tr.Artist, Album: tr.Album, Duration: 180},
	}, nil
}

type stubNavidromeGateway struct {
	NavidromeGateway
	lyrics string
	songFn func(ctx context.Context, ident string) (model.TrackDTO, error)
}

func (g *stubNavidromeGateway) GetLyrics(_ context.Context, ident, artist, title string) string {
	return g.lyrics
}

func (g *stubNavidromeGateway) GetSong(ctx context.Context, ident string) (model.TrackDTO, error) {
	if g.songFn != nil {
		return g.songFn(ctx, ident)
	}
	return model.TrackDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "Navidrome 歌曲不存在")
}

func TestLibraryFilesCoverFoldersEpisodesResolveAndNavidromeLyrics(t *testing.T) {
	epProv := &episodeStubProvider{
		stubProvider: stubProvider{
			id:         "itunes",
			engineName: "ITunesMusicClient",
			category:   "有声/播客",
			label:      "iTunes 播客",
			searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
				_ = emit(model.ProviderTrack{Source: "itunes", SourceID: "pod-1", RootSource: "ITunesMusicClient", Title: "Test Podcast", Artist: "Host", Duration: 300})
				return model.SearchSummary{}, nil
			},
			resolveFn: func(ctx context.Context, tr model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
				br := 128
				return []model.ResolvedMedia{{VariantID: "v1", Variant: model.MediaVariant{VariantID: "v1", Label: "MP3 128k", Codec: "mp3", Bitrate: &br, Availability: "verified"}, Codec: "mp3"}}, nil
			},
		},
	}
	primaryProv := &stubProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		category:   "开放许可",
		label:      "Audius",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			_ = emit(model.ProviderTrack{Source: "AudiusMusicClient", SourceID: "mid-resolved-1", RootSource: "AudiusMusicClient", Title: "Sunrise Demo", Artist: "Open Artist", Duration: 223})
			return model.SearchSummary{}, nil
		},
		resolveFn: func(ctx context.Context, tr model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			br := 320
			return []model.ResolvedMedia{{VariantID: "vq", Variant: model.MediaVariant{VariantID: "vq", Label: "MP3 320k", Codec: "mp3", Bitrate: &br, Availability: "verified"}, Codec: "mp3"}}, nil
		},
	}

	srv, st, canonRoot := newTestServer(t, func(deps *Dependencies, _ string) {
		reg, err := provider.NewRegistry([]provider.Entry{
			{Provider: epProv, Meta: provider.Metadata{Label: "iTunes", Category: "播客"}},
			{Provider: primaryProv, Meta: provider.Metadata{Label: "Audius", Category: "开放许可"}},
		})
		if err != nil {
			t.Fatalf("new registry: %v", err)
		}
		deps.Providers = reg
		deps.Navidrome = &stubNavidromeGateway{
			lyrics: "[00:01.00]Navidrome 同步歌词",
			songFn: func(_ context.Context, ident string) (model.TrackDTO, error) {
				if ident == "song-99" {
					u := "/api/navidrome/stream/song-99"
					c := "flac"
					return model.TrackDTO{
						ID:       "navidrome-song-99",
						Source:   "navidrome",
						SourceID: "song-99",
						Title:    "Clear Sky",
						Artist:   "Open Artist",
						Playback: model.PlaybackDTO{State: "ready", URL: &u, Codec: &c},
					}, nil
				}
				return model.TrackDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "Navidrome 歌曲不存在")
			},
		}
	})
	ctx := context.Background()

	// 1. Library asset with ID3v2 PNG cover + folders tree.
	pngBytes := []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 1, 0, 0, 0, 1}
	var apic bytes.Buffer
	apic.WriteByte(0)
	apic.WriteString("image/png")
	apic.WriteByte(0)
	apic.WriteByte(3)
	apic.WriteByte(0)
	apic.Write(pngBytes)
	var frame bytes.Buffer
	frame.WriteString("APIC")
	_ = binary.Write(&frame, binary.BigEndian, uint32(apic.Len()))
	frame.Write([]byte{0, 0})
	frame.Write(apic.Bytes())
	sz := frame.Len()
	hdr := []byte{'I', 'D', '3', 3, 0, 0, byte((sz >> 21) & 0x7f), byte((sz >> 14) & 0x7f), byte((sz >> 7) & 0x7f), byte(sz & 0x7f)}
	subDir := filepath.Join(canonRoot, "ArtistA", "AlbumA")
	if err := os.MkdirAll(subDir, 0o755); err != nil {
		t.Fatal(err)
	}
	mp3File := filepath.Join(subDir, "song.mp3")
	if err := os.WriteFile(mp3File, append(hdr, frame.Bytes()...), 0o644); err != nil {
		t.Fatal(err)
	}
	upAsset, err := st.UpsertAsset(ctx, store.AssetRecord{
		ID:       "asset-mp3-1",
		RootID:   "root-1",
		RelPath:  "ArtistA/AlbumA/song.mp3",
		PathKey:  "ArtistA/AlbumA/song.mp3",
		Folder:   "ArtistA/AlbumA",
		Name:     "song.mp3",
		Status:   "active",
		Editable: true,
		Size:     int64(len(hdr) + frame.Len()),
		MTimeNS:  123456,
		Title:    "Song MP3",
		Metadata: map[string]string{"title": "Song MP3", "_has_cover": "1", "_bitrate": "320000"},
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "asset:" + upAsset.ID, Kind: "asset", TargetID: upAsset.ID, Source: "local", SourceID: upAsset.ID}, "")

	pageRec := httptest.NewRecorder()
	srv.ServeHTTP(pageRec, httptest.NewRequest(http.MethodGet, "/api/library/files?limit=10", nil))
	if pageRec.Code != http.StatusOK {
		t.Fatalf("GET /api/library/files status=%d body=%s", pageRec.Code, pageRec.Body.String())
	}
	var page model.LibraryFilesPageDTO
	if err := json.Unmarshal(pageRec.Body.Bytes(), &page); err != nil || len(page.Items) != 1 || len(page.Folders) != 1 {
		t.Fatalf("unexpected library page: err=%v page=%+v", err, page)
	}
	tr := page.Items[0].Track
	if tr == nil || tr.Cover == nil || !strings.Contains(*tr.Cover, "/api/files/cover") || tr.Playback.URL == nil || tr.Playback.Bitrate == nil || *tr.Playback.Bitrate != 320 {
		t.Fatalf("expected populated cover/playback/bitrate, got %+v", tr)
	}
	if _, leaked := page.Items[0].Metadata["_has_cover"]; leaked {
		t.Fatalf("internal _has_cover flag leaked into Metadata")
	}
	covRec := httptest.NewRecorder()
	srv.ServeHTTP(covRec, httptest.NewRequest(http.MethodGet, "/api/files/cover?path=asset:"+upAsset.ID, nil))
	if covRec.Code != http.StatusOK || covRec.Header().Get("Content-Type") != "image/png" || !bytes.Equal(covRec.Body.Bytes(), pngBytes) {
		t.Fatalf("GET /api/files/cover failed: status=%d ct=%q len=%d", covRec.Code, covRec.Header().Get("Content-Type"), covRec.Body.Len())
	}

	// 2. Episodes expansion.
	_, err = st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		ID: "pod-trk-1", Source: "itunes", Locator: "pod-1", RootSource: "ITunesMusicClient", Title: "Podcast Show", Artist: "Host",
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "pod-pub-1", Kind: "candidate", TargetID: "pod-trk-1", Source: "itunes", SourceID: "pod-1"}, "")
	epRec := httptest.NewRecorder()
	srv.ServeHTTP(epRec, httptest.NewRequest(http.MethodGet, "/api/tracks/pod-pub-1/episodes", nil))
	if epRec.Code != http.StatusOK || !strings.Contains(epRec.Body.String(), "Ep 2") {
		t.Fatalf("GET /api/tracks/.../episodes failed: status=%d body=%s", epRec.Code, epRec.Body.String())
	}
	legEpRec := httptest.NewRecorder()
	srv.ServeHTTP(legEpRec, httptest.NewRequest(http.MethodGet, "/api/song/pod-pub-1/episodes", nil))
	if legEpRec.Code != http.StatusOK || !strings.Contains(legEpRec.Body.String(), `"results"`) {
		t.Fatalf("GET /api/song/.../episodes failed: status=%d body=%s", legEpRec.Code, legEpRec.Body.String())
	}

	// 3. Navidrome lyrics & playback (both live getSong lookup and cached track_dto).
	navLrcRec := httptest.NewRecorder()
	srv.ServeHTTP(navLrcRec, httptest.NewRequest(http.MethodGet, "/api/tracks/navidrome-song-99/lyrics", nil))
	if navLrcRec.Code != http.StatusOK || !strings.Contains(navLrcRec.Body.String(), "Navidrome 同步歌词") {
		t.Fatalf("expected Navidrome lyrics, got status=%d body=%s", navLrcRec.Code, navLrcRec.Body.String())
	}

	navPlayReq := httptest.NewRequest(http.MethodPost, "/api/tracks/navidrome-song-99/playback", nil)
	navPlayReq.Header.Set("X-MeloKeep-Request", "1")
	navPlayRec := httptest.NewRecorder()
	srv.ServeHTTP(navPlayRec, navPlayReq)
	if navPlayRec.Code != http.StatusOK || !strings.Contains(navPlayRec.Body.String(), `"/api/navidrome/stream/song-99"`) {
		t.Fatalf("expected Navidrome playback ready, got status=%d body=%s", navPlayRec.Code, navPlayRec.Body.String())
	}

	cachedURL := "/api/navidrome/stream/cached-1"
	cachedCodec := "mp3"
	cachedDTO := model.TrackDTO{
		ID:       "navidrome-cached-1",
		Source:   "navidrome",
		SourceID: "cached-1",
		Title:    "Ten Years Demo",
		Playback: model.PlaybackDTO{State: "ready", URL: &cachedURL, Codec: &cachedCodec},
	}
	cachedRaw, _ := json.Marshal(cachedDTO)
	_, _ = st.PutDurableRecord(ctx, "track_dto", cachedDTO.ID, string(cachedRaw), 0)
	cachedPlayReq := httptest.NewRequest(http.MethodPost, "/api/tracks/navidrome-cached-1/playback", nil)
	cachedPlayReq.Header.Set("X-MeloKeep-Request", "1")
	cachedPlayRec := httptest.NewRecorder()
	srv.ServeHTTP(cachedPlayRec, cachedPlayReq)
	if cachedPlayRec.Code != http.StatusOK || !strings.Contains(cachedPlayRec.Body.String(), `"/api/navidrome/stream/cached-1"`) {
		t.Fatalf("expected cached Navidrome playback ready, got status=%d body=%s", cachedPlayRec.Code, cachedPlayRec.Body.String())
	}

	// 4. Legacy POST /api/resolve.
	resReq := httptest.NewRequest(http.MethodPost, "/api/resolve", strings.NewReader(`{"title":"Sunrise Demo","artist":"Open Artist","source":"AudiusMusicClient"}`))
	resReq.Header.Set("X-MeloKeep-Request", "1")
	resRec := httptest.NewRecorder()
	srv.ServeHTTP(resRec, resReq)
	if resRec.Code != http.StatusOK || !strings.Contains(resRec.Body.String(), "Sunrise Demo") {
		t.Fatalf("POST /api/resolve failed: status=%d body=%s", resRec.Code, resRec.Body.String())
	}
}

func TestHandleDownloadEnqueueAutoScrapeAndOrganize(t *testing.T) {
	wav := make([]byte, 44+32)
	copy(wav[0:4], "RIFF")
	binary.LittleEndian.PutUint32(wav[4:8], 36+32)
	copy(wav[8:16], "WAVEfmt ")
	binary.LittleEndian.PutUint32(wav[16:20], 16)
	binary.LittleEndian.PutUint16(wav[20:22], 1)
	binary.LittleEndian.PutUint16(wav[22:24], 1)
	binary.LittleEndian.PutUint32(wav[24:28], 8000)
	binary.LittleEndian.PutUint32(wav[28:32], 16000)
	binary.LittleEndian.PutUint16(wav[32:34], 2)
	binary.LittleEndian.PutUint16(wav[34:36], 16)
	copy(wav[36:40], "data")
	binary.LittleEndian.PutUint32(wav[40:44], 32)

	audioSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/wav")
		_, _ = w.Write(wav)
	}))
	defer audioSrv.Close()

	var dlSvc *download.Service
	var libSvc *library.Service
	srv, st, _ := newTestServer(t, func(deps *Dependencies, tmp string) {
		prov := &stubProvider{
			id:         "AudiusMusicClient",
			engineName: "AudiusMusicClient",
			category:   "开放许可",
			label:      "Audius",
			resolveFn: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
				sz := int64(len(wav))
				return []model.ResolvedMedia{
					{
						VariantID: "var-wav",
						Variant: model.MediaVariant{
							VariantID:    "var-wav",
							Label:        "WAV",
							Codec:        "wav",
							Lossless:     true,
							Availability: "verified",
							Evidence:     "probed_audio",
							SizeBytes:    &sz,
						},
						URL:       audioSrv.URL + "/" + track.SourceID + ".wav",
						Codec:     "wav",
						SizeBytes: &sz,
						Expiry:    time.Now().Add(time.Hour),
					},
				}, nil
			},
		}
		reg, err := provider.NewRegistry([]provider.Entry{{Provider: prov, Meta: provider.Metadata{Label: "Audius"}}})
		if err != nil {
			t.Fatal(err)
		}
		deps.Providers = reg
		snapProvider := func() config.Snapshot { return deps.InitialSnapshot }
		scraper := library.NewScraper(deps.Transport, deps.Providers, deps.Media, library.ScraperOptions{})
		libSvc = library.NewService(deps.Store, deps.Files, deps.Tags, deps.Media, scraper, library.ServiceOptions{
			SnapshotProvider: snapProvider,
		})
		dlSvc = download.NewService(deps.Store, deps.Transport, deps.Providers, deps.Files, deps.Media, deps.Tags, download.Options{
			StagingRoot:        filepath.Join(tmp, "staging"),
			Budgets:            deps.Budgets,
			SnapshotProvider:   snapProvider,
			AutoOrganizeRunner: libSvc.AutoOrganizeDownload,
		})
		t.Cleanup(func() { _ = dlSvc.Close() })
		deps.Library = libSvc
		deps.Download = dlSvc
	})

	ctx := context.Background()
	cases := []struct {
		id         string
		autoScrape bool
		autoOrg    bool
		wantAction string
	}{
		{id: "t-none", autoScrape: false, autoOrg: false, wantAction: ""},
		{id: "t-scrape", autoScrape: true, autoOrg: false, wantAction: "scrape"},
		{id: "t-org", autoScrape: false, autoOrg: true, wantAction: "organize"},
		{id: "t-both", autoScrape: true, autoOrg: true, wantAction: "both"},
	}

	for _, tc := range cases {
		t.Run(tc.id, func(t *testing.T) {
			getSetRec := httptest.NewRecorder()
			srv.ServeHTTP(getSetRec, httptest.NewRequest(http.MethodGet, "/api/library/settings", nil))
			if getSetRec.Code != http.StatusOK {
				t.Fatalf("GET /api/library/settings failed: %d %s", getSetRec.Code, getSetRec.Body.String())
			}
			var settingsMap map[string]any
			if err := json.Unmarshal(getSetRec.Body.Bytes(), &settingsMap); err != nil {
				t.Fatalf("decode library settings: %v", err)
			}
			settingsMap["auto_scrape"] = tc.autoScrape
			settingsMap["auto_organize"] = tc.autoOrg
			settingsBody, _ := json.Marshal(settingsMap)
			setReq := httptest.NewRequest(http.MethodPut, "/api/library/settings", bytes.NewReader(settingsBody))
			setReq.Header.Set("Content-Type", "application/json")
			setReq.Header.Set("X-MeloKeep-Request", "1")
			setRec := httptest.NewRecorder()
			srv.ServeHTTP(setRec, setReq)
			if setRec.Code != http.StatusOK {
				t.Fatalf("PUT /api/library/settings failed: %d %s", setRec.Code, setRec.Body.String())
			}

			trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
				ID: "prov-" + tc.id, Source: "AudiusMusicClient", Locator: "loc-" + tc.id, RootSource: "AudiusMusicClient",
				Title: "Song-" + tc.id, Artist: "Artist", Album: "Album", DurationMS: 1000,
			}, []model.MediaVariant{{
				VariantID: "var-wav", Label: "WAV", Codec: "wav", Lossless: true, Availability: "verified", Evidence: "probed_audio",
			}})
			if err != nil {
				t.Fatal(err)
			}
			pubID := "pub-" + tc.id
			if err := st.PutPublicRef(ctx, model.PublicRef{
				PublicID: pubID, Kind: "candidate", TargetID: trID, Source: "AudiusMusicClient", SourceID: "loc-" + tc.id,
			}, ""); err != nil {
				t.Fatal(err)
			}

			enqReq := httptest.NewRequest(http.MethodPost, "/api/downloads", strings.NewReader(`[{"track_id":"`+pubID+`","quality_id":"var-wav"}]`))
			enqReq.Header.Set("Content-Type", "application/json")
			enqReq.Header.Set("X-MeloKeep-Request", "1")
			enqRec := httptest.NewRecorder()
			srv.ServeHTTP(enqRec, enqReq)
			if enqRec.Code != http.StatusOK {
				t.Fatalf("POST /api/downloads failed: %d %s", enqRec.Code, enqRec.Body.String())
			}
			var jobs []model.DownloadJobDTO
			_ = json.Unmarshal(enqRec.Body.Bytes(), &jobs)
			if len(jobs) != 1 {
				t.Fatalf("expected 1 enqueued job, got %+v", jobs)
			}

			var finalDTO model.DownloadJobDTO
			deadline := time.Now().Add(6 * time.Second)
			for time.Now().Before(deadline) {
				dl, ok, _ := dlSvc.Get(ctx, jobs[0].ID)
				if ok {
					dto, _ := dlSvc.ToDTO(ctx, dl)
					if dto.Status == "complete" && (tc.wantAction == "" || dto.Processing != nil) {
						finalDTO = dto
						break
					}
				}
				time.Sleep(25 * time.Millisecond)
			}
			if finalDTO.Status != "complete" {
				t.Fatalf("download %s did not complete in time", jobs[0].ID)
			}
			if tc.wantAction == "" {
				if finalDTO.Processing != nil {
					t.Fatalf("expected no auto-organize job when both disabled, got %+v", finalDTO.Processing)
				}
				return
			}
			if finalDTO.Processing == nil || finalDTO.Processing.ID == "" {
				t.Fatalf("expected auto-organize Processing created for %s, got nil", tc.id)
			}
			lj, _, jOK, err := st.GetLibraryJob(ctx, finalDTO.Processing.ID)
			if err != nil || !jOK {
				t.Fatalf("GetLibraryJob %s failed: ok=%v err=%v", finalDTO.Processing.ID, jOK, err)
			}
			if lj.Action != tc.wantAction {
				t.Fatalf("expected library job action=%q, got %q", tc.wantAction, lj.Action)
			}
		})
	}
}

func TestUIPreferencesPersistenceAndMerge(t *testing.T) {
	srv, _, _ := newTestServer(t, nil)

	// Empty initially.
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/preferences", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("initial GET /api/preferences status = %d", rec.Code)
	}
	if strings.TrimSpace(rec.Body.String()) != "{}" {
		t.Fatalf("expected empty {}, got %s", rec.Body.String())
	}

	// Write appearance and navigation order.
	put1 := httptest.NewRequest(http.MethodPut, "/api/preferences", strings.NewReader(`{
		"appearance": {
			"mode": "dark",
			"light_preset": "warm-vinyl",
			"dark_preset": "vinyl-gold",
			"custom_accent": "#eab308",
			"ambient_glow": false,
			"density": "compact"
		},
		"navigation_order": ["assistant", "search", "bad-id", "search", "settings"]
	}`))
	put1.Header.Set("Content-Type", "application/json")
	put1.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	rec1 := httptest.NewRecorder()
	srv.ServeHTTP(rec1, put1)
	if rec1.Code != http.StatusOK {
		t.Fatalf("PUT 1 status = %d %s", rec1.Code, rec1.Body.String())
	}

	// Partial update merges player + library_tree_width without clobbering appearance.
	put2 := httptest.NewRequest(http.MethodPut, "/api/preferences", strings.NewReader(`{
		"player": { "volume": 1.5, "shuffle": true },
		"library_tree_width": 320,
		"navidrome_view": { "mode": "albums", "layout": "cards" }
	}`))
	put2.Header.Set("Content-Type", "application/json")
	put2.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, put2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("PUT 2 status = %d %s", rec2.Code, rec2.Body.String())
	}

	// Reset tree width via 0 and clear custom accent via "".
	put3 := httptest.NewRequest(http.MethodPut, "/api/preferences", strings.NewReader(`{
		"appearance": { "custom_accent": "" },
		"library_tree_width": 0
	}`))
	put3.Header.Set("Content-Type", "application/json")
	put3.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	rec3 := httptest.NewRecorder()
	srv.ServeHTTP(rec3, put3)
	if rec3.Code != http.StatusOK {
		t.Fatalf("PUT 3 status = %d %s", rec3.Code, rec3.Body.String())
	}

	// Read back from store and verify merged state.
	recGet := httptest.NewRecorder()
	srv.ServeHTTP(recGet, httptest.NewRequest(http.MethodGet, "/api/preferences", nil))
	if recGet.Code != http.StatusOK {
		t.Fatalf("final GET status = %d", recGet.Code)
	}
	var got model.UIPreferencesDTO
	if err := json.Unmarshal(recGet.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode final prefs: %v", err)
	}
	if got.Appearance == nil || got.Appearance.Mode != "dark" || got.Appearance.DarkPreset != "vinyl-gold" {
		t.Fatalf("unexpected appearance: %+v", got.Appearance)
	}
	if got.Appearance.CustomAccent == nil || *got.Appearance.CustomAccent != "" {
		t.Fatalf("expected cleared custom_accent \"\", got %+v", got.Appearance.CustomAccent)
	}
	if len(got.NavigationOrder) != 3 || got.NavigationOrder[0] != "assistant" || got.NavigationOrder[1] != "search" || got.NavigationOrder[2] != "settings" {
		t.Fatalf("unexpected navigation_order: %v", got.NavigationOrder)
	}
	if got.Player == nil || got.Player.Volume == nil || *got.Player.Volume != 1 || got.Player.Shuffle == nil || !*got.Player.Shuffle {
		t.Fatalf("unexpected player prefs: %+v", got.Player)
	}
	if got.LibraryTreeWidth == nil || *got.LibraryTreeWidth != 0 {
		t.Fatalf("expected LibraryTreeWidth=0 after reset, got %v", got.LibraryTreeWidth)
	}
	if got.NavidromeView == nil || got.NavidromeView.Mode != "albums" || got.NavidromeView.Layout != "cards" {
		t.Fatalf("unexpected navidrome_view: %+v", got.NavidromeView)
	}
}

func TestLibraryJobDeduplicationDeleteAndBatchDeleteHTTP(t *testing.T) {
	srv, st, root := newTestServer(t, nil)
	libSvc := library.NewService(st, srv.deps.Files, nil, nil, nil, library.ServiceOptions{})
	t.Cleanup(func() { _ = libSvc.Close() })
	srv.deps.Library = libSvc

	audioPath := filepath.Join(root, "http-track.wav")
	audioBytes := []byte("RIFF\x00\x00\x00\x00WAVEfmt ")
	if err := os.WriteFile(audioPath, audioBytes, 0o644); err != nil {
		t.Fatal(err)
	}

	submitPreview := func() model.LibraryJobDTO {
		body, _ := json.Marshal(map[string]any{
			"paths":      []string{audioPath},
			"action":     "organize",
			"auto_apply": false,
		})
		req := httptest.NewRequest(http.MethodPost, "/api/library/jobs", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("POST /api/library/jobs failed: %d %s", rec.Code, rec.Body.String())
		}
		var out model.LibraryJobDTO
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatalf("decode job: %v", err)
		}
		return out
	}

	// 1. Repeated preview submit deduplicates old unapplied preview job.
	firstPreview := submitPreview()
	secondPreview := submitPreview()
	if firstPreview.ID == secondPreview.ID {
		t.Fatalf("expected fresh job id, got %s", secondPreview.ID)
	}
	listRec := httptest.NewRecorder()
	srv.ServeHTTP(listRec, httptest.NewRequest(http.MethodGet, "/api/library/jobs", nil))
	if listRec.Code != http.StatusOK {
		t.Fatalf("GET /api/library/jobs failed: %d %s", listRec.Code, listRec.Body.String())
	}
	var listBody struct {
		Jobs  []model.LibraryJobDTO `json:"jobs"`
		Total int                   `json:"total"`
	}
	if err := json.Unmarshal(listRec.Body.Bytes(), &listBody); err != nil {
		t.Fatalf("decode list: %v", err)
	}
	if listBody.Total != 1 || len(listBody.Jobs) != 1 || listBody.Jobs[0].ID != secondPreview.ID {
		t.Fatalf("expected 1 deduplicated job (%s), got %+v", secondPreview.ID, listBody)
	}

	// 2. Seed running & finished jobs for delete and batch-delete checks.
	ctx := context.Background()
	_, err := st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "http-running-1", Action: "both", State: model.LibraryJobScanning, OwnerKey: "anonymous:web:anonymous",
		Items: []store.LibraryItemInput{{ItemID: "i1", OriginalPath: audioPath, Name: "http-track.wav"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "http-done-1", Action: "both", State: model.LibraryJobComplete, OwnerKey: "anonymous:web:anonymous",
		Items: []store.LibraryItemInput{{ItemID: "i2", OriginalPath: audioPath, Name: "http-track.wav"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "http-failed-1", Action: "both", State: model.LibraryJobFailed, OwnerKey: "anonymous:web:anonymous",
		Items: []store.LibraryItemInput{{ItemID: "i3", OriginalPath: audioPath, Name: "http-track.wav"}},
	})
	if err != nil {
		t.Fatal(err)
	}

	// Single delete running job -> 409.
	delRunningReq := httptest.NewRequest(http.MethodDelete, "/api/library/jobs/http-running-1", nil)
	delRunningReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	delRunningRec := httptest.NewRecorder()
	srv.ServeHTTP(delRunningRec, delRunningReq)
	if delRunningRec.Code != http.StatusConflict {
		t.Fatalf("expected 409 deleting running job, got %d %s", delRunningRec.Code, delRunningRec.Body.String())
	}

	// Single delete preview job -> 200 {"deleted": true, "id": ...}.
	delPreviewReq := httptest.NewRequest(http.MethodDelete, "/api/library/jobs/"+secondPreview.ID, nil)
	delPreviewReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	delPreviewRec := httptest.NewRecorder()
	srv.ServeHTTP(delPreviewRec, delPreviewReq)
	if delPreviewRec.Code != http.StatusOK {
		t.Fatalf("expected 200 deleting preview job, got %d %s", delPreviewRec.Code, delPreviewRec.Body.String())
	}
	var delResp struct {
		Deleted bool   `json:"deleted"`
		ID      string `json:"id"`
	}
	if err := json.Unmarshal(delPreviewRec.Body.Bytes(), &delResp); err != nil || !delResp.Deleted || delResp.ID != secondPreview.ID {
		t.Fatalf("unexpected delete response: %s (err=%v)", delPreviewRec.Body.String(), err)
	}

	// Deleting already deleted job -> 404.
	delMissingReq := httptest.NewRequest(http.MethodDelete, "/api/library/jobs/"+secondPreview.ID, nil)
	delMissingReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	delMissingRec := httptest.NewRecorder()
	srv.ServeHTTP(delMissingRec, delMissingReq)
	if delMissingRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 deleting missing job, got %d %s", delMissingRec.Code, delMissingRec.Body.String())
	}

	// Batch delete by IDs: skips running, deletes finished.
	batchByIDsReq := httptest.NewRequest(http.MethodPost, "/api/library/jobs/batch-delete", strings.NewReader(`{"ids":["http-done-1","http-running-1","http-done-1"],"mode":""}`))
	batchByIDsReq.Header.Set("Content-Type", "application/json")
	batchByIDsReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	batchByIDsRec := httptest.NewRecorder()
	srv.ServeHTTP(batchByIDsRec, batchByIDsReq)
	if batchByIDsRec.Code != http.StatusOK {
		t.Fatalf("POST batch-delete by ids failed: %d %s", batchByIDsRec.Code, batchByIDsRec.Body.String())
	}
	var batchResp struct {
		Deleted        int      `json:"deleted"`
		DeletedIDs     []string `json:"deleted_ids"`
		SkippedRunning []string `json:"skipped_running"`
	}
	if err := json.Unmarshal(batchByIDsRec.Body.Bytes(), &batchResp); err != nil {
		t.Fatalf("decode batch response: %v", err)
	}
	if batchResp.Deleted != 1 || len(batchResp.DeletedIDs) != 1 || batchResp.DeletedIDs[0] != "http-done-1" ||
		len(batchResp.SkippedRunning) != 1 || batchResp.SkippedRunning[0] != "http-running-1" {
		t.Fatalf("unexpected batch-delete by ids response: %+v", batchResp)
	}

	// Batch delete mode="finished": clears remaining failed job while keeping running job.
	batchFinReq := httptest.NewRequest(http.MethodPost, "/api/library/jobs/batch-delete", strings.NewReader(`{"mode":"finished"}`))
	batchFinReq.Header.Set("Content-Type", "application/json")
	batchFinReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	batchFinRec := httptest.NewRecorder()
	srv.ServeHTTP(batchFinRec, batchFinReq)
	if batchFinRec.Code != http.StatusOK {
		t.Fatalf("POST batch-delete finished failed: %d %s", batchFinRec.Code, batchFinRec.Body.String())
	}
	var finResp struct {
		Deleted        int      `json:"deleted"`
		DeletedIDs     []string `json:"deleted_ids"`
		SkippedRunning []string `json:"skipped_running"`
	}
	if err := json.Unmarshal(batchFinRec.Body.Bytes(), &finResp); err != nil {
		t.Fatalf("decode finished batch response: %v", err)
	}
	if finResp.Deleted != 1 || len(finResp.DeletedIDs) != 1 || finResp.DeletedIDs[0] != "http-failed-1" || len(finResp.SkippedRunning) != 0 {
		t.Fatalf("unexpected batch-delete finished response: %+v", finResp)
	}

	// Running job must remain, audio file on disk must be untouched.
	if _, _, ok, _ := st.GetLibraryJob(ctx, "http-running-1"); !ok {
		t.Fatalf("expected running job preserved")
	}
	gotAudio, err := os.ReadFile(audioPath)
	if err != nil || string(gotAudio) != string(audioBytes) {
		t.Fatalf("expected original audio intact on disk, got len=%d err=%v", len(gotAudio), err)
	}
}

// ---------------------------------------------------------------------------
// Provider plugin HTTP API
// ---------------------------------------------------------------------------

func demoPluginScript(id string) string {
	return fmt.Sprintf(`
module.exports = {
	manifest: {
		apiVersion: 1,
		id: %q,
		name: "Open Demo",
		version: "1.0.0",
		description: "Demo open-license catalog",
		capabilities: { search: true, lyrics: true, metadata: true },
		settings: [
			{ key: "api_key", label: "API Key", type: "secret" },
			{ key: "region", label: "Region", type: "text", default_value: "global" }
		]
	},
	search(ctx, opts) {
		return [{ sourceId: "demo-1", title: "Open Song", artist: "Open Artist", album: "Open Album", duration: 120 }];
	},
	resolve(ctx, track, policy) { return []; },
	lyrics(ctx, track) { return "[00:01.00]Open lyric"; },
	metadata(ctx, opts) {
		return [{ sourceId: "meta-1", title: opts.title || opts.query, artist: "Open Artist", album: "Open Album" }];
	}
};
`, id)
}

func newPluginTestServer(t *testing.T, readOnly bool) *Server {
	t.Helper()
	var libSvc *library.Service
	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.ReadOnly = readOnly
		d.Plugins = plugin.NewManager(d.Store, d.Transport, d.Providers, t.TempDir(), nil)
		scraper := library.NewScraper(d.Transport, d.Providers, d.Media, library.ScraperOptions{})
		libSvc = library.NewService(d.Store, d.Files, d.Tags, d.Media, scraper, library.ServiceOptions{})
		d.Library = libSvc
	})
	t.Cleanup(func() { _ = libSvc.Close() })
	return srv
}

func jsonCall(t *testing.T, srv *Server, method, path string, body any) (int, map[string]any) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal body: %v", err)
		}
		rd = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(method, path, rd)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-MeloKeep-Request", "1")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

func listedIDs(t *testing.T, srv *Server, path, key string) map[string]bool {
	t.Helper()
	code, out := jsonCall(t, srv, http.MethodGet, path, nil)
	if code != http.StatusOK {
		t.Fatalf("GET %s failed: %d", path, code)
	}
	ids := make(map[string]bool)
	items, _ := out[key].([]any)
	for _, it := range items {
		if m, ok := it.(map[string]any); ok {
			if id, _ := m["id"].(string); id != "" {
				ids[id] = true
			}
		}
	}
	return ids
}

func TestPluginHTTPAPILifecycle(t *testing.T) {
	srv := newPluginTestServer(t, false)
	const pid = "open-demo"

	// 1. Empty list.
	code, out := jsonCall(t, srv, http.MethodGet, "/api/plugins", nil)
	list, isList := out["plugins"].([]any)
	if code != http.StatusOK || !isList || len(list) != 0 {
		t.Fatalf("expected empty plugin list, got %d %v", code, out)
	}

	// 2. Inspect (valid / empty / invalid script).
	code, out = jsonCall(t, srv, http.MethodPost, "/api/plugins/inspect", map[string]any{"code": demoPluginScript(pid)})
	manifest, _ := out["manifest"].(map[string]any)
	if sum, _ := out["sha256"].(string); code != http.StatusOK || len(sum) != 64 || manifest["id"] != pid {
		t.Fatalf("inspect failed: %d %v", code, out)
	}
	if code, _ = jsonCall(t, srv, http.MethodPost, "/api/plugins/inspect", map[string]any{}); code != http.StatusBadRequest {
		t.Fatalf("expected 400 for inspect without code/source_url, got %d", code)
	}
	if code, _ = jsonCall(t, srv, http.MethodPost, "/api/plugins/inspect", map[string]any{"code": "this is not valid ((("}); code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid script, got %d", code)
	}

	// CSRF protection applies to plugin mutations.
	noCSRF := httptest.NewRequest(http.MethodPost, "/api/plugins", strings.NewReader(`{}`))
	noCSRFRec := httptest.NewRecorder()
	srv.ServeHTTP(noCSRFRec, noCSRF)
	if noCSRFRec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 without CSRF header, got %d", noCSRFRec.Code)
	}

	// 3. Install; the plugin shows up in /api/config and /api/library/settings immediately.
	if ids := listedIDs(t, srv, "/api/config", "available_sources"); ids[pid] {
		t.Fatalf("plugin must not be listed before install")
	}
	code, out = jsonCall(t, srv, http.MethodPost, "/api/plugins", map[string]any{
		"code":     demoPluginScript(pid),
		"settings": map[string]string{"api_key": "s3cr3t-value", "region": "eu"},
	})
	dto, _ := out["plugin"].(map[string]any)
	if code != http.StatusOK || dto["id"] != pid || dto["enabled"] != true {
		t.Fatalf("install failed: %d %v", code, out)
	}
	settings, _ := dto["settings"].(map[string]any)
	if settings["api_key"] != config.HiddenPlaceholder || settings["region"] != "eu" {
		t.Fatalf("expected secret setting redacted in response, got %v", settings)
	}
	if !listedIDs(t, srv, "/api/config", "available_sources")[pid] {
		t.Fatalf("installed plugin missing from /api/config available_sources")
	}
	if !listedIDs(t, srv, "/api/library/settings", "metadata_sources")[pid] {
		t.Fatalf("installed plugin missing from /api/library/settings metadata_sources")
	}
	code, out = jsonCall(t, srv, http.MethodGet, "/api/plugins", nil)
	if list, _ = out["plugins"].([]any); code != http.StatusOK || len(list) != 1 {
		t.Fatalf("expected 1 plugin in list, got %d %v", code, out)
	}
	if strings.Contains(fmt.Sprint(out), "s3cr3t-value") {
		t.Fatalf("plugin list leaked secret value: %v", out)
	}

	// Invalid script id (400) and built-in id conflict (409).
	if code, _ = jsonCall(t, srv, http.MethodPost, "/api/plugins", map[string]any{"code": demoPluginScript("Bad ID")}); code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid plugin id, got %d", code)
	}
	if code, _ = jsonCall(t, srv, http.MethodPost, "/api/plugins", map[string]any{"code": demoPluginScript("audius")}); code != http.StatusConflict {
		t.Fatalf("expected 409 for built-in id conflict, got %d", code)
	}

	// 4. Plugin lyrics are served by the provider capability without audio resolution.
	ctx := context.Background()
	trID, err := srv.deps.Store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source: pid, Locator: "demo-1", RootSource: pid, Title: "Open Song", Artist: "Open Artist",
	}, nil)
	if err != nil {
		t.Fatalf("UpsertProviderTrack: %v", err)
	}
	if err := srv.deps.Store.PutPublicRef(ctx, model.PublicRef{
		PublicID: "plugin-trk-1", Kind: "candidate", TargetID: trID, Source: pid, SourceID: "demo-1",
	}, ""); err != nil {
		t.Fatalf("PutPublicRef: %v", err)
	}
	code, out = jsonCall(t, srv, http.MethodGet, "/api/tracks/plugin-trk-1/lyrics", nil)
	if txt, _ := out["text"].(string); code != http.StatusOK || !strings.Contains(txt, "Open lyric") {
		t.Fatalf("expected plugin lyrics, got %d %v", code, out)
	}

	// 5. Test actions: search / lyrics / metadata, plus bad action and unknown plugin.
	testPath := "/api/plugins/" + pid + "/test"
	code, out = jsonCall(t, srv, http.MethodPost, testPath, map[string]any{"action": "search", "keyword": "open"})
	res, _ := out["result"].(map[string]any)
	tracks, _ := res["tracks"].([]any)
	if code != http.StatusOK || out["ok"] != true || len(tracks) != 1 {
		t.Fatalf("plugin search test failed: %d %v", code, out)
	}
	code, out = jsonCall(t, srv, http.MethodPost, testPath, map[string]any{
		"action": "lyrics", "track": map[string]any{"sourceId": "demo-1", "title": "Open Song"},
	})
	res, _ = out["result"].(map[string]any)
	if lrc, _ := res["lyrics"].(string); code != http.StatusOK || out["ok"] != true || !strings.Contains(lrc, "Open lyric") {
		t.Fatalf("plugin lyrics test failed: %d %v", code, out)
	}
	code, out = jsonCall(t, srv, http.MethodPost, testPath, map[string]any{"action": "metadata", "query": "open", "title": "Open Song"})
	res, _ = out["result"].(map[string]any)
	items, _ := res["items"].([]any)
	if code != http.StatusOK || out["ok"] != true || len(items) != 1 {
		t.Fatalf("plugin metadata test failed: %d %v", code, out)
	}
	if code, _ = jsonCall(t, srv, http.MethodPost, testPath, map[string]any{"action": "bogus"}); code != http.StatusBadRequest {
		t.Fatalf("expected 400 for unsupported test action, got %d", code)
	}
	if code, _ = jsonCall(t, srv, http.MethodPost, "/api/plugins/missing-plugin/test", map[string]any{"action": "search"}); code != http.StatusNotFound {
		t.Fatalf("expected 404 testing unknown plugin, got %d", code)
	}

	// 6. Disable -> disappears from config; re-enable with new settings keeps the secret.
	code, out = jsonCall(t, srv, http.MethodPatch, "/api/plugins/"+pid, map[string]any{"enabled": false})
	dto, _ = out["plugin"].(map[string]any)
	if code != http.StatusOK || dto["enabled"] != false {
		t.Fatalf("disable failed: %d %v", code, out)
	}
	if listedIDs(t, srv, "/api/config", "available_sources")[pid] {
		t.Fatalf("disabled plugin must disappear from /api/config")
	}
	code, out = jsonCall(t, srv, http.MethodPatch, "/api/plugins/"+pid, map[string]any{
		"enabled":  true,
		"settings": map[string]string{"api_key": config.HiddenPlaceholder, "region": "us"},
	})
	dto, _ = out["plugin"].(map[string]any)
	settings, _ = dto["settings"].(map[string]any)
	if code != http.StatusOK || dto["enabled"] != true || settings["region"] != "us" || settings["api_key"] != config.HiddenPlaceholder {
		t.Fatalf("re-enable failed: %d %v", code, out)
	}
	if !listedIDs(t, srv, "/api/config", "available_sources")[pid] {
		t.Fatalf("re-enabled plugin missing from /api/config")
	}
	if code, _ = jsonCall(t, srv, http.MethodPatch, "/api/plugins/missing-plugin", map[string]any{"enabled": false}); code != http.StatusNotFound {
		t.Fatalf("expected 404 patching unknown plugin, got %d", code)
	}
	if code, _ = jsonCall(t, srv, http.MethodPatch, "/api/plugins/Bad.Id", map[string]any{"enabled": false}); code != http.StatusBadRequest {
		t.Fatalf("expected 400 patching invalid plugin id, got %d", code)
	}

	// 7. Delete -> removed everywhere; second delete is 404.
	code, out = jsonCall(t, srv, http.MethodDelete, "/api/plugins/"+pid, nil)
	if code != http.StatusOK || out["ok"] != true || out["id"] != pid {
		t.Fatalf("delete failed: %d %v", code, out)
	}
	if listedIDs(t, srv, "/api/config", "available_sources")[pid] || listedIDs(t, srv, "/api/library/settings", "metadata_sources")[pid] {
		t.Fatalf("deleted plugin must disappear from /api/config and /api/library/settings")
	}
	if code, _ = jsonCall(t, srv, http.MethodDelete, "/api/plugins/"+pid, nil); code != http.StatusNotFound {
		t.Fatalf("expected 404 on repeated delete, got %d", code)
	}
}

func buildPluginZipBytes(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, body := range files {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatalf("zip create %q: %v", name, err)
		}
		if _, err := w.Write([]byte(body)); err != nil {
			t.Fatalf("zip write %q: %v", name, err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("zip close: %v", err)
	}
	return buf.Bytes()
}

func multipartArchiveRequest(t *testing.T, path string, fileField string, fileName string, zipBytes []byte, fields map[string]string, withCSRF bool) *http.Request {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	if fileField != "" {
		fw, err := mw.CreateFormFile(fileField, fileName)
		if err != nil {
			t.Fatalf("CreateFormFile: %v", err)
		}
		if _, err := fw.Write(zipBytes); err != nil {
			t.Fatalf("write zip part: %v", err)
		}
	}
	for k, v := range fields {
		if err := mw.WriteField(k, v); err != nil {
			t.Fatalf("WriteField %q: %v", k, err)
		}
	}
	if err := mw.Close(); err != nil {
		t.Fatalf("close multipart: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, path, &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	if withCSRF {
		req.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	}
	return req
}

func TestPluginArchiveHTTPAPILifecycle(t *testing.T) {
	srv := newPluginTestServer(t, false)

	archiveBytes := buildPluginZipBytes(t, map[string]string{
		"b1/zip-alpha.js":        demoPluginScript("zip-alpha"),
		"b2/zip-beta.js":         demoPluginScript("zip-beta"),
		"b2/broken.js":           "this is broken {{{",
		"b3/builtin-clash.js":    demoPluginScript("audius"),
		"b4/dup-alpha.js":        demoPluginScript("zip-alpha"),
		"__MACOSX/b1/._alpha.js": "mac junk",
		"README.md":              "# plugins",
	})

	// 1. Missing CSRF header is rejected on multipart POST routes.
	noCSRFInspect := multipartArchiveRequest(t, "/api/plugins/inspect-archive", "file", "plugins.zip", archiveBytes, nil, false)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, noCSRFInspect)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 without CSRF on inspect-archive, got %d %s", rec.Code, rec.Body.String())
	}
	noCSRFInstall := multipartArchiveRequest(t, "/api/plugins/install-archive", "file", "plugins.zip", archiveBytes, nil, false)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, noCSRFInstall)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 without CSRF on install-archive, got %d %s", rec.Code, rec.Body.String())
	}

	// 2. Inspect via multipart/form-data.
	inspReq := multipartArchiveRequest(t, "/api/plugins/inspect-archive", "file", "plugins.zip", archiveBytes, nil, true)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, inspReq)
	if rec.Code != http.StatusOK {
		t.Fatalf("inspect-archive multipart failed: %d %s", rec.Code, rec.Body.String())
	}
	var inspRes model.PluginArchiveInspectResultDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &inspRes); err != nil {
		t.Fatalf("decode inspect-archive: %v", err)
	}
	if inspRes.Total != 5 || inspRes.ValidCount != 2 || inspRes.InvalidCount != 3 || len(inspRes.Entries) != 5 {
		t.Fatalf("unexpected inspect-archive summary: %+v", inspRes)
	}

	// 3. Inspect via raw application/zip body.
	rawReq := httptest.NewRequest(http.MethodPost, "/api/plugins/inspect-archive", bytes.NewReader(archiveBytes))
	rawReq.Header.Set("Content-Type", "application/zip")
	rawReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, rawReq)
	if rec.Code != http.StatusOK {
		t.Fatalf("inspect-archive application/zip failed: %d %s", rec.Code, rec.Body.String())
	}
	var rawInspRes model.PluginArchiveInspectResultDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &rawInspRes); err != nil {
		t.Fatalf("decode raw inspect-archive: %v", err)
	}
	if rawInspRes.Total != 5 || rawInspRes.ValidCount != 2 || rawInspRes.InvalidCount != 3 {
		t.Fatalf("unexpected raw inspect-archive result: %+v", rawInspRes)
	}

	// 4. Error cases: missing file field, corrupt zip, unsupported content type.
	missingFileReq := multipartArchiveRequest(t, "/api/plugins/inspect-archive", "", "", nil, map[string]string{"note": "empty"}, true)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, missingFileReq)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for missing file field, got %d %s", rec.Code, rec.Body.String())
	}
	corruptReq := multipartArchiveRequest(t, "/api/plugins/inspect-archive", "file", "bad.zip", []byte("not-a-zip"), nil, true)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, corruptReq)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for corrupt zip, got %d %s", rec.Code, rec.Body.String())
	}
	badCTReq := httptest.NewRequest(http.MethodPost, "/api/plugins/inspect-archive", strings.NewReader(`{}`))
	badCTReq.Header.Set("Content-Type", "application/json")
	badCTReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, badCTReq)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for unsupported content-type, got %d %s", rec.Code, rec.Body.String())
	}

	// 5. Partial install with selected_ids JSON array + enabled=true; verify cache invalidation & hot registration.
	srv.resolveMu.Lock()
	srv.resolveCache["dummy-key"] = resolvedCacheEntry{ExpiresAt: time.Now().Add(time.Minute)}
	srv.resolveMu.Unlock()

	selReq := multipartArchiveRequest(t, "/api/plugins/install-archive", "file", "plugins.zip", archiveBytes, map[string]string{
		"enabled":      "true",
		"selected_ids": `["zip-alpha"]`,
	}, true)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, selReq)
	if rec.Code != http.StatusOK {
		t.Fatalf("install-archive selected failed: %d %s", rec.Code, rec.Body.String())
	}
	var selRes model.PluginArchiveInstallResultDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &selRes); err != nil {
		t.Fatalf("decode install-archive selected: %v", err)
	}
	if selRes.Total != 1 || selRes.InstalledCount != 1 || selRes.FailedCount != 0 || len(selRes.Installed) != 1 || selRes.Installed[0].ID != "zip-alpha" {
		t.Fatalf("unexpected selected install result: %+v", selRes)
	}
	srv.resolveMu.Lock()
	cacheLen := len(srv.resolveCache)
	srv.resolveMu.Unlock()
	if cacheLen != 0 {
		t.Fatalf("expected resolveCache invalidated after archive install, still has %d entries", cacheLen)
	}
	if !listedIDs(t, srv, "/api/config", "available_sources")["zip-alpha"] {
		t.Fatalf("zip-alpha missing from /api/config available_sources after install-archive")
	}
	if listedIDs(t, srv, "/api/config", "available_sources")["zip-beta"] {
		t.Fatalf("unselected zip-beta must not be installed")
	}
	if !listedIDs(t, srv, "/api/library/settings", "metadata_sources")["zip-alpha"] {
		t.Fatalf("zip-alpha missing from /api/library/settings metadata_sources")
	}

	// Verify audit log recorded batch install.
	var foundAudit bool
	for _, entry := range srv.deps.Logs.List() {
		if entry.Category == "plugin" && strings.Contains(entry.Message, "zip-alpha") {
			foundAudit = true
			break
		}
	}
	if !foundAudit {
		t.Fatalf("expected audit log entry for archive install, got %+v", srv.deps.Logs.List())
	}

	// 6. Comma-separated selected_ids + enabled=false installs zip-beta disabled.
	csvReq := multipartArchiveRequest(t, "/api/plugins/install-archive", "file", "plugins.zip", archiveBytes, map[string]string{
		"enabled":      "false",
		"selected_ids": "zip-beta",
	}, true)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, csvReq)
	if rec.Code != http.StatusOK {
		t.Fatalf("install-archive csv failed: %d %s", rec.Code, rec.Body.String())
	}
	var csvRes model.PluginArchiveInstallResultDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &csvRes); err != nil {
		t.Fatalf("decode csv install-archive: %v", err)
	}
	if csvRes.Total != 1 || csvRes.InstalledCount != 1 || csvRes.Installed[0].Enabled {
		t.Fatalf("expected zip-beta installed disabled, got %+v", csvRes)
	}
	if listedIDs(t, srv, "/api/config", "available_sources")["zip-beta"] {
		t.Fatalf("disabled zip-beta must not appear in /api/config available_sources")
	}

	// 7. Install all (no selected_ids) with enabled=true and allowed_origins enables both valid plugins and reports 3 failed items.
	allReq := multipartArchiveRequest(t, "/api/plugins/install-archive", "file", "plugins.zip", archiveBytes, map[string]string{
		"enabled":         "true",
		"allowed_origins": `["http://192.168.1.20:8080"]`,
	}, true)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, allReq)
	if rec.Code != http.StatusOK {
		t.Fatalf("install-archive all failed: %d %s", rec.Code, rec.Body.String())
	}
	var allRes model.PluginArchiveInstallResultDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &allRes); err != nil {
		t.Fatalf("decode all install-archive: %v", err)
	}
	if allRes.Total != 5 || allRes.InstalledCount != 2 || allRes.FailedCount != 3 || len(allRes.Installed) != 2 || len(allRes.Results) != 5 {
		t.Fatalf("unexpected install-all result: %+v", allRes)
	}
	avail := listedIDs(t, srv, "/api/config", "available_sources")
	if !avail["zip-alpha"] || !avail["zip-beta"] {
		t.Fatalf("expected both zip-alpha and zip-beta active in /api/config, got %v", avail)
	}

	// 8. Invalid form parameter values return 400.
	badEnabledReq := multipartArchiveRequest(t, "/api/plugins/install-archive", "file", "plugins.zip", archiveBytes, map[string]string{
		"enabled": "maybe",
	}, true)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, badEnabledReq)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid enabled value, got %d %s", rec.Code, rec.Body.String())
	}
	badSelReq := multipartArchiveRequest(t, "/api/plugins/install-archive", "file", "plugins.zip", archiveBytes, map[string]string{
		"selected_ids": "[invalid-json",
	}, true)
	rec = httptest.NewRecorder()
	srv.ServeHTTP(rec, badSelReq)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid selected_ids JSON, got %d %s", rec.Code, rec.Body.String())
	}

	// 9. Auth-enabled server accepts authenticated multipart upload with CSRF header and rejects unauthenticated upload.
	authSrv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.AuthEnabled = true
		d.AuthUserDigest = sha256.Sum256([]byte("admin"))
		d.AuthPassDigest = sha256.Sum256([]byte("pass"))
		d.Plugins = plugin.NewManager(d.Store, d.Transport, d.Providers, t.TempDir(), nil)
	})
	unauthReq := multipartArchiveRequest(t, "/api/plugins/inspect-archive", "file", "plugins.zip", archiveBytes, nil, true)
	rec = httptest.NewRecorder()
	authSrv.ServeHTTP(rec, unauthReq)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for unauthenticated multipart inspect-archive, got %d %s", rec.Code, rec.Body.String())
	}
	tok, err := authSrv.deps.Sessions.Create("admin")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	authReq := multipartArchiveRequest(t, "/api/plugins/inspect-archive", "file", "plugins.zip", archiveBytes, nil, true)
	authReq.AddCookie(&http.Cookie{Name: auth.SessionCookieName, Value: tok})
	rec = httptest.NewRecorder()
	authSrv.ServeHTTP(rec, authReq)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 for authenticated multipart inspect-archive with CSRF, got %d %s", rec.Code, rec.Body.String())
	}
}

func TestPluginHTTPAPIReadOnlyGuard(t *testing.T) {
	srv := newPluginTestServer(t, true)
	archiveBytes := buildPluginZipBytes(t, map[string]string{
		"ro-demo.js": demoPluginScript("ro-demo"),
	})

	if code, _ := jsonCall(t, srv, http.MethodGet, "/api/plugins", nil); code != http.StatusOK {
		t.Fatalf("GET /api/plugins must stay readable in read-only mode, got %d", code)
	}
	if code, _ := jsonCall(t, srv, http.MethodPost, "/api/plugins/inspect", map[string]any{"code": demoPluginScript("ro-demo")}); code != http.StatusOK {
		t.Fatalf("inspect must stay available in read-only mode, got %d", code)
	}
	inspArchRec := httptest.NewRecorder()
	srv.ServeHTTP(inspArchRec, multipartArchiveRequest(t, "/api/plugins/inspect-archive", "file", "ro.zip", archiveBytes, nil, true))
	if inspArchRec.Code != http.StatusOK {
		t.Fatalf("inspect-archive must stay available in read-only mode, got %d %s", inspArchRec.Code, inspArchRec.Body.String())
	}
	instArchRec := httptest.NewRecorder()
	srv.ServeHTTP(instArchRec, multipartArchiveRequest(t, "/api/plugins/install-archive", "file", "ro.zip", archiveBytes, nil, true))
	var instArchOut map[string]any
	_ = json.Unmarshal(instArchRec.Body.Bytes(), &instArchOut)
	if instArchRec.Code != http.StatusForbidden || instArchOut["detail"] != "只读验证模式禁止修改插件" {
		t.Fatalf("POST /api/plugins/install-archive expected 403 read-only guard, got %d %v", instArchRec.Code, instArchOut)
	}
	for _, tc := range []struct {
		method, path string
		body         any
	}{
		{http.MethodPost, "/api/plugins", map[string]any{"code": demoPluginScript("ro-demo")}},
		{http.MethodPatch, "/api/plugins/ro-demo", map[string]any{"enabled": false}},
		{http.MethodDelete, "/api/plugins/ro-demo", nil},
	} {
		code, out := jsonCall(t, srv, tc.method, tc.path, tc.body)
		if code != http.StatusForbidden || out["detail"] != "只读验证模式禁止修改插件" {
			t.Fatalf("%s %s expected 403 read-only guard, got %d %v", tc.method, tc.path, code, out)
		}
	}
}

func TestMetaDefaultSourcesAreDynamicAndCompliant(t *testing.T) {
	srv, _, _ := newTestServer(t, nil)
	code, out := jsonCall(t, srv, http.MethodGet, "/api/meta", nil)
	defaults, _ := out["default_sources"].([]any)
	if code != http.StatusOK || len(defaults) != 1 || defaults[0] != "AudiusMusicClient" {
		t.Fatalf("expected default_sources limited to registered built-in sources, got %d %v", code, out)
	}
}

func TestPlaylistImportRejectsInvalidLocatorWithoutVendorTemplates(t *testing.T) {
	srv, _, _ := newTestServer(t, nil)
	code, out := jsonCall(t, srv, http.MethodPost, "/api/playlist/import", map[string]any{"source": "AudiusMusicClient", "id": "not a valid id!"})
	if code != http.StatusBadRequest || out["detail"] != "请输入有效的歌单链接或标识" {
		t.Fatalf("expected 400 for invalid playlist locator, got %d %v", code, out)
	}
	code, out = jsonCall(t, srv, http.MethodPost, "/api/playlist/import", map[string]any{"source": "AudiusMusicClient", "id": "12345"})
	if code != http.StatusBadRequest {
		t.Fatalf("expected 400 for provider without playlist support, got %d %v", code, out)
	}
}

type lyricsStubProvider struct {
	stubProvider
	lyricsFn func(ctx context.Context, track model.ProviderTrack) (string, error)
}

func (p *lyricsStubProvider) Lyrics(ctx context.Context, track model.ProviderTrack) (string, error) {
	if p.lyricsFn != nil {
		return p.lyricsFn(ctx, track)
	}
	return "", nil
}

func TestTrackLyricsPrefersLyricsProviderOverResolve(t *testing.T) {
	var resolveCalls atomic.Int32
	prov := &lyricsStubProvider{
		stubProvider: stubProvider{
			id:         "AudiusMusicClient",
			engineName: "AudiusMusicClient",
			resolveFn: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
				resolveCalls.Add(1)
				return []model.ResolvedMedia{{
					VariantID: "v1",
					URL:       "https://cdn.example.org/fallback.mp3",
					Codec:     "mp3",
					Lyrics:    "[00:05.00]Fallback Resolve Lyric",
				}}, nil
			},
		},
		lyricsFn: func(ctx context.Context, track model.ProviderTrack) (string, error) {
			if track.SourceID == "direct-lrc" {
				return "[00:01.00]Direct Provider Lyric", nil
			}
			return "NULL", nil
		},
	}

	srv, st, _ := newTestServer(t, func(deps *Dependencies, _ string) {
		reg, err := provider.NewRegistry([]provider.Entry{{
			Provider: prov,
			Meta:     provider.Metadata{Label: "Audius", Category: "开放许可"},
		}})
		if err != nil {
			t.Fatalf("NewRegistry: %v", err)
		}
		deps.Providers = reg
	})

	ctx := context.Background()
	for _, tc := range []struct {
		pubID, loc string
	}{
		{"lrc-direct", "direct-lrc"},
		{"lrc-fallback", "empty-lrc"},
	} {
		trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
			Source:     "AudiusMusicClient",
			Locator:    tc.loc,
			RootSource: "AudiusMusicClient",
			Title:      "Sample " + tc.loc,
			Artist:     "Open Artist",
		}, nil)
		if err != nil {
			t.Fatalf("UpsertProviderTrack: %v", err)
		}
		if err := st.PutPublicRef(ctx, model.PublicRef{
			PublicID: tc.pubID,
			Kind:     "candidate",
			TargetID: trID,
			Source:   "AudiusMusicClient",
			SourceID: tc.loc,
		}, ""); err != nil {
			t.Fatalf("PutPublicRef: %v", err)
		}
	}

	// 1. Direct LyricsProvider hit must NOT call Resolve.
	code, out := jsonCall(t, srv, http.MethodGet, "/api/tracks/lrc-direct/lyrics", nil)
	if txt, _ := out["text"].(string); code != http.StatusOK || txt != "[00:01.00]Direct Provider Lyric" {
		t.Fatalf("expected direct provider lyrics, got %d %v", code, out)
	}
	if resolveCalls.Load() != 0 {
		t.Fatalf("expected Resolve not to be called when LyricsProvider returns lyrics, got %d calls", resolveCalls.Load())
	}

	// 2. Empty/NULL LyricsProvider response falls back to Resolve.
	code, out = jsonCall(t, srv, http.MethodGet, "/api/tracks/lrc-fallback/lyrics", nil)
	if txt, _ := out["text"].(string); code != http.StatusOK || txt != "[00:05.00]Fallback Resolve Lyric" {
		t.Fatalf("expected fallback resolve lyrics, got %d %v", code, out)
	}
	if resolveCalls.Load() != 1 {
		t.Fatalf("expected 1 Resolve fallback call, got %d", resolveCalls.Load())
	}
}

func TestUIPreferencesLyricsCustomizationAndBounds(t *testing.T) {
	srv, _, _ := newTestServer(t, nil)

	putPrefs := func(payload string) model.UIPreferencesDTO {
		req := httptest.NewRequest(http.MethodPut, "/api/preferences", strings.NewReader(payload))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("PUT /api/preferences failed: %d %s", rec.Code, rec.Body.String())
		}
		var out model.UIPreferencesDTO
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatalf("decode prefs: %v", err)
		}
		return out
	}

	// 1. Out-of-bounds low + invalid active color + invalid align.
	got1 := putPrefs(`{
		"player": {
			"lyrics_font_size": 8,
			"lyrics_active_color": "red",
			"lyrics_inactive_opacity": 0.05,
			"lyrics_align": "right"
		}
	}`)
	if got1.Player == nil || got1.Player.LyricsFontSize == nil || *got1.Player.LyricsFontSize != 14 {
		t.Fatalf("expected lyrics_font_size clamped to 14, got %+v", got1.Player)
	}
	if got1.Player.LyricsInactiveOpacity == nil || *got1.Player.LyricsInactiveOpacity != 0.2 {
		t.Fatalf("expected lyrics_inactive_opacity clamped to 0.2, got %+v", got1.Player)
	}
	if got1.Player.LyricsActiveColor != nil {
		t.Fatalf("expected invalid lyrics_active_color ignored, got %q", *got1.Player.LyricsActiveColor)
	}
	if got1.Player.LyricsAlign != "" {
		t.Fatalf("expected invalid lyrics_align ignored, got %q", got1.Player.LyricsAlign)
	}

	// 2. Out-of-bounds high + whitespace-trimmed valid hex + valid align.
	got2 := putPrefs(`{
		"player": {
			"lyrics_font_size": 60,
			"lyrics_active_color": "  #22c55e  ",
			"lyrics_inactive_opacity": 1.8,
			"lyrics_align": "left"
		}
	}`)
	if *got2.Player.LyricsFontSize != 32 {
		t.Fatalf("expected lyrics_font_size clamped to 32, got %d", *got2.Player.LyricsFontSize)
	}
	if got2.Player.LyricsActiveColor == nil || *got2.Player.LyricsActiveColor != "#22c55e" {
		t.Fatalf("expected trimmed #22c55e, got %+v", got2.Player.LyricsActiveColor)
	}
	if *got2.Player.LyricsInactiveOpacity != 0.9 {
		t.Fatalf("expected lyrics_inactive_opacity clamped to 0.9, got %v", *got2.Player.LyricsInactiveOpacity)
	}
	if got2.Player.LyricsAlign != "left" {
		t.Fatalf("expected lyrics_align=left, got %q", got2.Player.LyricsAlign)
	}

	// 3. Valid values + empty active color (theme default) + partial update preserves fields.
	_ = putPrefs(`{
		"player": {
			"lyrics_font_size": 22,
			"lyrics_active_color": "",
			"lyrics_inactive_opacity": 0.55,
			"lyrics_align": "center"
		}
	}`)
	_ = putPrefs(`{
		"player": {
			"volume": 0.65,
			"lyrics_active_color": "#nothex",
			"lyrics_align": "justify"
		}
	}`)

	getRec := httptest.NewRecorder()
	srv.ServeHTTP(getRec, httptest.NewRequest(http.MethodGet, "/api/preferences", nil))
	if getRec.Code != http.StatusOK {
		t.Fatalf("GET /api/preferences status = %d", getRec.Code)
	}
	var final model.UIPreferencesDTO
	if err := json.Unmarshal(getRec.Body.Bytes(), &final); err != nil {
		t.Fatalf("decode final prefs: %v", err)
	}
	if final.Player == nil || final.Player.Volume == nil || *final.Player.Volume != 0.65 ||
		final.Player.LyricsFontSize == nil || *final.Player.LyricsFontSize != 22 ||
		final.Player.LyricsActiveColor == nil || *final.Player.LyricsActiveColor != "" ||
		final.Player.LyricsInactiveOpacity == nil || *final.Player.LyricsInactiveOpacity != 0.55 ||
		final.Player.LyricsAlign != "center" {
		t.Fatalf("unexpected persisted player preferences: %+v", final.Player)
	}
}

func TestLibraryProcessAndApplyAsyncAndSyncEndpoints(t *testing.T) {
	emptyITunes := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"results":[]}`))
	}))
	defer emptyITunes.Close()

	var libSvc *library.Service
	srv, _, root := newTestServer(t, func(d *Dependencies, _ string) {
		scraper := library.NewScraper(d.Transport, d.Providers, d.Media, library.ScraperOptions{
			ITunesBaseURL: emptyITunes.URL + "/itunes",
		})
		libSvc = library.NewService(d.Store, d.Files, d.Tags, d.Media, scraper, library.ServiceOptions{})
		d.Library = libSvc
	})
	t.Cleanup(func() { _ = libSvc.Close() })

	pollJobHTTP := func(jobID string, pred func(model.LibraryJobDTO) bool) model.LibraryJobDTO {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			rec := httptest.NewRecorder()
			srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/library/jobs/"+jobID, nil))
			if rec.Code != http.StatusOK {
				t.Fatalf("GET /api/library/jobs/%s status=%d %s", jobID, rec.Code, rec.Body.String())
			}
			var dto model.LibraryJobDTO
			if err := json.Unmarshal(rec.Body.Bytes(), &dto); err != nil {
				t.Fatalf("decode job: %v", err)
			}
			if pred(dto) {
				return dto
			}
			time.Sleep(15 * time.Millisecond)
		}
		t.Fatalf("timed out polling job %s", jobID)
		return model.LibraryJobDTO{}
	}

	scrapePath := filepath.Join(root, "incoming", "unmatched-http.wav")
	_ = os.MkdirAll(filepath.Dir(scrapePath), 0o755)
	if err := os.WriteFile(scrapePath, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644); err != nil {
		t.Fatal(err)
	}

	// 1. Default POST /api/library/jobs for action="scrape" uses SubmitAsync (returns scanning).
	code, out := jsonCall(t, srv, http.MethodPost, "/api/library/jobs", map[string]any{
		"action":  "scrape",
		"paths":   []string{scrapePath},
		"sources": []string{"itunes"},
	})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobScanning) {
		t.Fatalf("expected 200 scanning from async scrape, got %d %v", code, out)
	}
	asyncScrapeID, _ := out["id"].(string)
	doneScrape := pollJobHTTP(asyncScrapeID, func(j model.LibraryJobDTO) bool {
		return j.Status != string(model.LibraryJobScanning)
	})
	if doneScrape.Status != string(model.LibraryJobNeedsReview) {
		t.Fatalf("expected needs_review after empty scrape, got %s", doneScrape.Status)
	}

	// 2. Apply on all-needs_review job MUST return 400 BadRequest (not silent 200 OK).
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+asyncScrapeID+"/apply", map[string]any{})
	if code != http.StatusBadRequest {
		t.Fatalf("expected 400 applying needs_review job, got %d %v", code, out)
	}

	// 3. Preview with "existing" then Apply succeeds with 200 complete.
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+asyncScrapeID+"/preview", map[string]any{
		"choices": map[string]string{doneScrape.Items[0].ID: "existing"},
	})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobPreviewReady) {
		t.Fatalf("expected preview_ready after existing choice, got %d %v", code, out)
	}
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+asyncScrapeID+"/apply", map[string]any{})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobComplete) {
		t.Fatalf("expected complete after apply existing, got %d %v", code, out)
	}

	// 4. POST /api/library/jobs?sync=true forces synchronous Submit even for scrape.
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs?sync=true", map[string]any{
		"action":  "scrape",
		"paths":   []string{scrapePath},
		"sources": []string{"itunes"},
	})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobNeedsReview) {
		t.Fatalf("expected synchronous needs_review with ?sync=true, got %d %v", code, out)
	}

	// 5. Batch > 5 items: Apply uses ApplyAsync (returns applying), Apply?sync=true uses sync Apply.
	var batchPaths []string
	for i := 1; i <= 7; i++ {
		p := filepath.Join(root, "incoming", fmt.Sprintf("batch-%d.wav", i))
		if err := os.WriteFile(p, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644); err != nil {
			t.Fatal(err)
		}
		batchPaths = append(batchPaths, p)
	}
	// <= 10 paths with action="edit" -> synchronous preview_ready.
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs", map[string]any{
		"action":  "edit",
		"paths":   batchPaths,
		"changes": map[string]string{"album": "AsyncBatch"},
	})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobPreviewReady) {
		t.Fatalf("expected sync preview_ready for 7-item edit, got %d %v", code, out)
	}
	batchJobID, _ := out["id"].(string)

	// Apply without ?sync=true -> ApplyAsync returns "applying" immediately.
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+batchJobID+"/apply", map[string]any{})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobApplying) {
		t.Fatalf("expected 200 applying for >5 executable items, got %d %v", code, out)
	}
	batchDone := pollJobHTTP(batchJobID, func(j model.LibraryJobDTO) bool {
		return j.Status == string(model.LibraryJobComplete) || j.Status == string(model.LibraryJobFailed) || j.Status == string(model.LibraryJobPartial)
	})
	if batchDone.Status != string(model.LibraryJobComplete) {
		t.Fatalf("expected async apply complete, got %s (%s)", batchDone.Status, batchDone.Detail)
	}

	// Apply?sync=true with > 5 items -> synchronous complete.
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs", map[string]any{
		"action":  "edit",
		"paths":   batchPaths,
		"changes": map[string]string{"genre": "Ambient"},
	})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobPreviewReady) {
		t.Fatalf("expected sync preview_ready for 2nd 7-item edit, got %d %v", code, out)
	}
	syncJobID, _ := out["id"].(string)
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+syncJobID+"/apply?sync=true", map[string]any{})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobComplete) {
		t.Fatalf("expected immediate complete with ?sync=true, got %d %v", code, out)
	}

	// 6. scope_dir="" (empty paths) triggers SubmitAsync even for organize.
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs", map[string]any{
		"action":    "organize",
		"scope_dir": "",
	})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobScanning) {
		t.Fatalf("expected scanning for scope_dir=\"\", got %d %v", code, out)
	}
	scopeJobID, _ := out["id"].(string)
	scopeDone := pollJobHTTP(scopeJobID, func(j model.LibraryJobDTO) bool {
		return j.Status != string(model.LibraryJobScanning)
	})
	if scopeDone.Status != string(model.LibraryJobPreviewReady) || len(scopeDone.Items) < 7 {
		t.Fatalf("expected scope_dir scan preview_ready with >=7 items, got status=%s len=%d", scopeDone.Status, len(scopeDone.Items))
	}
}

func TestLibraryRetryAsyncAndSyncHTTP(t *testing.T) {
	var libSvc *library.Service
	srv, st, root := newTestServer(t, func(d *Dependencies, _ string) {
		scraper := library.NewScraper(d.Transport, d.Providers, d.Media, library.ScraperOptions{})
		libSvc = library.NewService(d.Store, d.Files, d.Tags, d.Media, scraper, library.ServiceOptions{})
		d.Library = libSvc
	})
	t.Cleanup(func() { _ = libSvc.Close() })

	ctx := context.Background()
	_, caseMode, _ := files.ProbeCapabilities(root)

	makeBatch := func(prefix string) []string {
		var paths []string
		for i := 0; i < 6; i++ {
			p := filepath.Join(root, "retry_http", fmt.Sprintf("%s_%02d.wav", prefix, i))
			_ = os.MkdirAll(filepath.Dir(p), 0o755)
			if err := os.WriteFile(p, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644); err != nil {
				t.Fatal(err)
			}
			paths = append(paths, p)
		}
		return paths
	}

	bumpAllRevisions := func(prefix string) {
		for i := 0; i < 6; i++ {
			rel := fmt.Sprintf("retry_http/%s_%02d.wav", prefix, i)
			pk := files.ComputePathKey(caseMode, rel)
			cur, ok, err := st.GetAssetByRootAndPathKey(ctx, "root-1", pk)
			if err != nil || !ok {
				t.Fatalf("GetAssetByRootAndPathKey %s: ok=%v err=%v", rel, ok, err)
			}
			if _, err := st.UpsertAsset(ctx, cur, cur.Revision, false); err != nil {
				t.Fatalf("bump rev %s: %v", rel, err)
			}
		}
	}

	// 1. Create a failed 6-item job, then POST /api/library/jobs/{id}/retry -> returns applying (async).
	pathsAsync := makeBatch("async")
	code, out := jsonCall(t, srv, http.MethodPost, "/api/library/jobs?sync=true", map[string]any{
		"action":  "edit",
		"paths":   pathsAsync,
		"changes": map[string]string{"album": "RetryAsyncAlbum"},
	})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobPreviewReady) {
		t.Fatalf("submit async batch: %d %v", code, out)
	}
	asyncJobID, _ := out["id"].(string)
	bumpAllRevisions("async")
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+asyncJobID+"/apply?sync=true", map[string]any{})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobFailed) {
		t.Fatalf("expected failed job after rev bump, got %d %v", code, out)
	}

	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+asyncJobID+"/retry", map[string]any{})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobApplying) {
		t.Fatalf("expected 200 applying from async retry (>5 items), got %d %v", code, out)
	}
	deadline := time.Now().Add(5 * time.Second)
	var finalStatus string
	for time.Now().Before(deadline) {
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/library/jobs/"+asyncJobID, nil))
		var dto model.LibraryJobDTO
		_ = json.Unmarshal(rec.Body.Bytes(), &dto)
		finalStatus = dto.Status
		if finalStatus == string(model.LibraryJobComplete) || finalStatus == string(model.LibraryJobFailed) || finalStatus == string(model.LibraryJobPartial) {
			break
		}
		time.Sleep(15 * time.Millisecond)
	}
	if finalStatus != string(model.LibraryJobComplete) {
		t.Fatalf("expected async retry complete, got %s", finalStatus)
	}

	// 2. Create a failed 6-item job, then POST /api/library/jobs/{id}/retry?sync=true -> returns complete immediately.
	pathsSync := makeBatch("sync")
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs?sync=true", map[string]any{
		"action":  "edit",
		"paths":   pathsSync,
		"changes": map[string]string{"album": "RetrySyncAlbum"},
	})
	if code != http.StatusOK {
		t.Fatalf("submit sync batch: %d %v", code, out)
	}
	syncJobID, _ := out["id"].(string)
	bumpAllRevisions("sync")
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+syncJobID+"/apply?sync=true", map[string]any{})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobFailed) {
		t.Fatalf("expected failed sync job after rev bump, got %d %v", code, out)
	}
	code, out = jsonCall(t, srv, http.MethodPost, "/api/library/jobs/"+syncJobID+"/retry?sync=true", map[string]any{})
	if code != http.StatusOK || out["status"] != string(model.LibraryJobComplete) {
		t.Fatalf("expected immediate complete with retry?sync=true, got %d %v", code, out)
	}
}

type stubNavidromeDownloadGateway struct {
	NavidromeGateway
	songs       map[string]model.TrackDTO
	albumTracks map[string][]model.TrackDTO
	allTracks   []model.TrackDTO
	mediaBytes  map[string][]byte
	mediaCT     map[string]string
}

func (g *stubNavidromeDownloadGateway) GetSong(_ context.Context, ident string) (model.TrackDTO, error) {
	id := strings.TrimPrefix(strings.TrimSpace(ident), "navidrome-")
	if tr, ok := g.songs[id]; ok {
		return tr, nil
	}
	return model.TrackDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "歌曲不存在")
}

func (g *stubNavidromeDownloadGateway) GetAlbum(_ context.Context, ident string) (any, error) {
	tracks, ok := g.albumTracks[ident]
	if !ok {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "专辑不存在")
	}
	return map[string]any{"tracks": tracks}, nil
}

func (g *stubNavidromeDownloadGateway) ListSongs(_ context.Context, offset, size int) (any, error) {
	if offset >= len(g.allTracks) {
		return map[string]any{"tracks": []model.TrackDTO{}, "has_more": false}, nil
	}
	end := offset + size
	hasMore := true
	if end >= len(g.allTracks) {
		end = len(g.allTracks)
		hasMore = false
	}
	return map[string]any{"tracks": g.allTracks[offset:end], "has_more": hasMore}, nil
}

func (g *stubNavidromeDownloadGateway) Search(_ context.Context, query string, offset int) (any, error) {
	var matched []model.TrackDTO
	for _, tr := range g.allTracks {
		if strings.Contains(strings.ToLower(tr.Title+" "+tr.Artist), strings.ToLower(query)) {
			matched = append(matched, tr)
		}
	}
	const pageSize = 2
	if offset >= len(matched) {
		return map[string]any{"tracks": []model.TrackDTO{}, "has_more": false}, nil
	}
	end := offset + pageSize
	hasMore := true
	if end >= len(matched) {
		end = len(matched)
		hasMore = false
	}
	return map[string]any{"tracks": matched[offset:end], "has_more": hasMore}, nil
}

func (g *stubNavidromeDownloadGateway) OpenDownload(_ context.Context, ident, _ string) (int, http.Header, io.ReadCloser, error) {
	id := strings.TrimPrefix(strings.TrimSpace(ident), "navidrome-")
	data, ok := g.mediaBytes[id]
	if !ok {
		return 0, nil, nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "媒体文件不可用")
	}
	ct := g.mediaCT[id]
	if ct == "" {
		ct = "audio/flac"
	}
	hdr := http.Header{
		"Content-Type":   {ct},
		"Content-Length": {fmt.Sprintf("%d", len(data))},
	}
	return http.StatusOK, hdr, io.NopCloser(bytes.NewReader(data)), nil
}

func TestNavidromeDownloadAndArchiveHTTP(t *testing.T) {
	codecFlac := "flac"
	codecMP3 := "mp3"
	gw := &stubNavidromeDownloadGateway{
		songs: map[string]model.TrackDTO{
			"s1": {ID: "navidrome-s1", Source: "navidrome", SourceID: "s1", Title: "晨风序曲", Artist: "林澈", Playback: model.PlaybackDTO{Codec: &codecFlac}},
			"s2": {ID: "navidrome-s2", Source: "navidrome", SourceID: "s2", Title: "晨风序曲", Artist: "林澈", Playback: model.PlaybackDTO{Codec: &codecFlac}},
			"s3": {ID: "navidrome-s3", Source: "navidrome", SourceID: "s3", Title: "Night Drive", Artist: "Neon", Playback: model.PlaybackDTO{Codec: &codecMP3}},
			"s4": {ID: "navidrome-s4", Source: "navidrome", SourceID: "s4", Title: "Night Horizon", Artist: "Neon", Playback: model.PlaybackDTO{Codec: &codecMP3}},
		},
		albumTracks: map[string][]model.TrackDTO{
			"al-1": {
				{ID: "navidrome-s3", Source: "navidrome", SourceID: "s3", Title: "Night Drive", Artist: "Neon", Playback: model.PlaybackDTO{Codec: &codecMP3}},
			},
		},
		allTracks: []model.TrackDTO{
			{ID: "navidrome-s1", Source: "navidrome", SourceID: "s1", Title: "晨风序曲", Artist: "林澈", Playback: model.PlaybackDTO{Codec: &codecFlac}},
			{ID: "navidrome-s3", Source: "navidrome", SourceID: "s3", Title: "Night Drive", Artist: "Neon", Playback: model.PlaybackDTO{Codec: &codecMP3}},
			{ID: "navidrome-s4", Source: "navidrome", SourceID: "s4", Title: "Night Horizon", Artist: "Neon", Playback: model.PlaybackDTO{Codec: &codecMP3}},
		},
		mediaBytes: map[string][]byte{
			"s1": []byte("FLAC-ONE"),
			"s2": []byte("FLAC-TWO"),
			"s3": []byte("MP3-THREE"),
			"s4": []byte("MP3-FOUR"),
		},
		mediaCT: map[string]string{
			"s1": "audio/flac",
			"s2": "audio/flac",
			"s3": "audio/mpeg",
			"s4": "audio/mpeg",
		},
	}

	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.Navidrome = gw
	})

	// 1. Single download sets safe attachment headers + nosniff + body.
	recOne := httptest.NewRecorder()
	srv.ServeHTTP(recOne, httptest.NewRequest(http.MethodGet, "/api/navidrome/download/s1", nil))
	if recOne.Code != http.StatusOK {
		t.Fatalf("GET /api/navidrome/download/s1 failed: %d %s", recOne.Code, recOne.Body.String())
	}
	if recOne.Body.String() != "FLAC-ONE" {
		t.Fatalf("unexpected download body: %q", recOne.Body.String())
	}
	disp := recOne.Header().Get("Content-Disposition")
	if !strings.HasPrefix(disp, "attachment;") || !strings.Contains(disp, "UTF-8''") || !strings.Contains(disp, ".flac") {
		t.Fatalf("unexpected Content-Disposition: %q", disp)
	}
	if recOne.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatalf("expected X-Content-Type-Options: nosniff, got %q", recOne.Header().Get("X-Content-Type-Options"))
	}

	// 2. Empty archive request -> 400.
	code, _ := jsonCall(t, srv, http.MethodPost, "/api/navidrome/downloads/archive", map[string]any{})
	if code != http.StatusBadRequest {
		t.Fatalf("expected 400 on empty archive request, got %d", code)
	}

	// 3. > 500 tracks archive request -> 400 with exact cap message.
	tooMany := make([]string, 501)
	for i := range tooMany {
		tooMany[i] = fmt.Sprintf("song-%d", i)
	}
	code, out := jsonCall(t, srv, http.MethodPost, "/api/navidrome/downloads/archive", map[string]any{
		"song_ids": tooMany,
	})
	if code != http.StatusBadRequest {
		t.Fatalf("expected 400 on >500 tracks, got %d %v", code, out)
	}

	// 4. song_ids archive: duplicate filename deduplication (" (2)") + failing song logged in _download_errors.txt.
	reqBody, _ := json.Marshal(map[string]any{
		"song_ids": []string{"s1", "s2", "missing-99"},
	})
	recZip := httptest.NewRecorder()
	reqZip := httptest.NewRequest(http.MethodPost, "/api/navidrome/downloads/archive", bytes.NewReader(reqBody))
	reqZip.Header.Set("Content-Type", "application/json")
	reqZip.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(recZip, reqZip)
	if recZip.Code != http.StatusOK {
		t.Fatalf("POST /api/navidrome/downloads/archive failed: %d %s", recZip.Code, recZip.Body.String())
	}
	if recZip.Header().Get("Content-Type") != "application/zip" {
		t.Fatalf("expected application/zip, got %q", recZip.Header().Get("Content-Type"))
	}
	zr, err := zip.NewReader(bytes.NewReader(recZip.Body.Bytes()), int64(recZip.Body.Len()))
	if err != nil {
		t.Fatalf("open response zip: %v", err)
	}
	entries := make(map[string]string, len(zr.File))
	for _, f := range zr.File {
		rc, err := f.Open()
		if err != nil {
			t.Fatalf("open zip entry %s: %v", f.Name, err)
		}
		b, _ := io.ReadAll(rc)
		_ = rc.Close()
		entries[f.Name] = string(b)
	}
	if entries["林澈 - 晨风序曲.flac"] != "FLAC-ONE" {
		t.Fatalf("expected first dup entry FLAC-ONE, got %+v", entries)
	}
	if entries["林澈 - 晨风序曲 (2).flac"] != "FLAC-TWO" {
		t.Fatalf("expected second dup entry FLAC-TWO, got %+v", entries)
	}
	if !strings.Contains(entries["_download_errors.txt"], "missing-99") {
		t.Fatalf("expected missing-99 in _download_errors.txt, got %q", entries["_download_errors.txt"])
	}

	// 5. album_id archive.
	albumBody, _ := json.Marshal(map[string]any{"album_id": "al-1"})
	recAlb := httptest.NewRecorder()
	reqAlb := httptest.NewRequest(http.MethodPost, "/api/navidrome/downloads/archive", bytes.NewReader(albumBody))
	reqAlb.Header.Set("Content-Type", "application/json")
	reqAlb.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(recAlb, reqAlb)
	if recAlb.Code != http.StatusOK {
		t.Fatalf("album archive failed: %d %s", recAlb.Code, recAlb.Body.String())
	}
	zAlb, err := zip.NewReader(bytes.NewReader(recAlb.Body.Bytes()), int64(recAlb.Body.Len()))
	if err != nil || len(zAlb.File) != 1 || zAlb.File[0].Name != "Neon - Night Drive.mp3" {
		t.Fatalf("unexpected album archive entries: err=%v files=%+v", err, zAlb)
	}

	// 6. all=true + query across paginated Search (note Search paginates 2 items per page? No, s3+s4 match query "Night", verify pagination works).
	searchBody, _ := json.Marshal(map[string]any{"all": true, "query": "Night"})
	recSearch := httptest.NewRecorder()
	reqSearch := httptest.NewRequest(http.MethodPost, "/api/navidrome/downloads/archive", bytes.NewReader(searchBody))
	reqSearch.Header.Set("Content-Type", "application/json")
	reqSearch.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(recSearch, reqSearch)
	if recSearch.Code != http.StatusOK {
		t.Fatalf("search archive failed: %d %s", recSearch.Code, recSearch.Body.String())
	}
	zSearch, err := zip.NewReader(bytes.NewReader(recSearch.Body.Bytes()), int64(recSearch.Body.Len()))
	if err != nil || len(zSearch.File) != 2 {
		t.Fatalf("expected 2 tracks in search archive, got err=%v count=%d", err, len(zSearch.File))
	}
}

func TestHandleStreamInjectsDefaultUserAgentAndSkipsBlankHeaders(t *testing.T) {
	payload := bytes.Repeat([]byte("MP3STREAM"), 32)
	var gotUA, gotReferer, gotBlankHeader atomic.Value
	var useExplicitUA atomic.Bool

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ua := r.Header.Get("User-Agent")
		if ua == "" || strings.Contains(ua, "Go-http-client") {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		gotUA.Store(ua)
		gotReferer.Store(r.Header.Get("Referer"))
		if vals := r.Header.Values("X-Empty"); len(vals) > 0 {
			gotBlankHeader.Store(strings.Join(vals, ","))
		} else {
			gotBlankHeader.Store("")
		}
		w.Header().Set("Content-Type", "audio/mpeg")
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(payload)))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
	}))
	defer upstream.Close()

	srv, st, _ := newTestServer(t, nil)
	reg, err := provider.NewRegistry([]provider.Entry{
		{
			Provider: &stubProvider{
				id:         "AudiusMusicClient",
				engineName: "AudiusMusicClient",
				resolveFn: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
					hdrs := map[string]string{
						"User-Agent": "   ",
						"X-Empty":    "",
						"Referer":    "https://music.163.com/",
					}
					if useExplicitUA.Load() {
						hdrs = map[string]string{
							"User-Agent": "ExplicitPlayer/2.5",
						}
					}
					return []model.ResolvedMedia{{
						VariantID: "v1",
						URL:       upstream.URL + "/audio.mp3",
						Headers:   hdrs,
					}}, nil
				},
			},
			Meta: provider.Metadata{Label: "Audius", Category: "开放许可"},
		},
	})
	if err != nil {
		t.Fatalf("new registry: %v", err)
	}
	srv.deps.Providers = reg

	seedStreamTrack := func(locator, pubID string) {
		trID, sErr := st.UpsertProviderTrack(context.Background(), store.ProviderTrackRecord{
			Source:     "AudiusMusicClient",
			Locator:    locator,
			RootSource: "AudiusMusicClient",
			Title:      "StreamSong-" + locator,
			Artist:     "Tester",
		}, []model.MediaVariant{{VariantID: "v1", Label: "MP3", Codec: "mp3", Availability: "resolved"}})
		if sErr != nil {
			t.Fatal(sErr)
		}
		if pErr := st.PutPublicRef(context.Background(), model.PublicRef{
			PublicID: pubID, Kind: "candidate", TargetID: trID, Source: "AudiusMusicClient", SourceID: locator,
		}, ""); pErr != nil {
			t.Fatal(pErr)
		}
	}

	seedStreamTrack("ua-default", "stream-ua-default")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/stream/stream-ua-default", nil))
	if rec.Code != http.StatusOK || !bytes.Equal(rec.Body.Bytes(), payload) {
		t.Fatalf("expected 200 streamed payload, got code=%d len=%d body=%s", rec.Code, rec.Body.Len(), rec.Body.String())
	}
	if ua, _ := gotUA.Load().(string); ua != provider.DefaultUserAgent {
		t.Fatalf("expected DefaultUserAgent %q, got %q", provider.DefaultUserAgent, ua)
	}
	if ref, _ := gotReferer.Load().(string); ref != "https://music.163.com/" {
		t.Fatalf("expected Referer preserved, got %q", ref)
	}
	if blank, _ := gotBlankHeader.Load().(string); blank != "" {
		t.Fatalf("expected blank X-Empty header omitted, got %q", blank)
	}

	// Explicit User-Agent should be preserved without overwriting.
	useExplicitUA.Store(true)
	seedStreamTrack("ua-explicit", "stream-ua-explicit")
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/api/stream/stream-ua-explicit", nil))
	if rec2.Code != http.StatusOK {
		t.Fatalf("expected 200 with explicit UA, got code=%d body=%s", rec2.Code, rec2.Body.String())
	}
	if ua, _ := gotUA.Load().(string); ua != "ExplicitPlayer/2.5" {
		t.Fatalf("expected explicit User-Agent preserved, got %q", ua)
	}
}

type pagedEpisodeStubProvider struct {
	stubProvider
	pageCalls atomic.Int32
}

func (p *pagedEpisodeStubProvider) Episodes(_ context.Context, tr model.ProviderTrack) ([]model.ProviderTrack, error) {
	out := make([]model.ProviderTrack, 0, 15)
	for i := 1; i <= 15; i++ {
		idx := i
		out = append(out, model.ProviderTrack{
			Source:        "paged-src",
			SourceID:      fmt.Sprintf("%s#ep:%d", tr.SourceID, i),
			RootSource:    "paged-src",
			Title:         fmt.Sprintf("Legacy Ep %d", i),
			Artist:        tr.Artist,
			Album:         tr.Album,
			PlaylistIndex: &idx,
		})
	}
	return out, nil
}

func (p *pagedEpisodeStubProvider) EpisodesPage(_ context.Context, tr model.ProviderTrack, opts provider.EpisodePageOptions) (provider.EpisodePage, error) {
	p.pageCalls.Add(1)
	const total = 15
	start := (opts.Page - 1) * opts.PageSize
	if start >= total {
		return provider.EpisodePage{
			Items:    []model.ProviderTrack{},
			Page:     opts.Page,
			PageSize: opts.PageSize,
			Total:    total,
			HasMore:  false,
		}, nil
	}
	end := min(start+opts.PageSize, total)
	items := make([]model.ProviderTrack, 0, end-start)
	for i := start + 1; i <= end; i++ {
		idx := i
		items = append(items, model.ProviderTrack{
			Source:        "paged-src",
			SourceID:      fmt.Sprintf("%s#ep:%d", tr.SourceID, i),
			RootSource:    "paged-src",
			Title:         fmt.Sprintf("Chapter %d", i),
			Artist:        tr.Artist,
			Album:         tr.Album,
			Duration:      600,
			PlaylistIndex: &idx,
		})
	}
	return provider.EpisodePage{
		Items:    items,
		Page:     opts.Page,
		PageSize: opts.PageSize,
		Total:    total,
		HasMore:  end < total,
	}, nil
}

type legacySliceEpisodeProvider struct {
	stubProvider
}

func (p *legacySliceEpisodeProvider) Episodes(_ context.Context, tr model.ProviderTrack) ([]model.ProviderTrack, error) {
	out := make([]model.ProviderTrack, 0, 15)
	for i := 1; i <= 15; i++ {
		idx := i
		out = append(out, model.ProviderTrack{
			Source:        "legacy-slice-src",
			SourceID:      fmt.Sprintf("%s#ep:%d", tr.SourceID, i),
			RootSource:    "legacy-slice-src",
			Title:         fmt.Sprintf("Slice Ep %d", i),
			Artist:        tr.Artist,
			Album:         tr.Album,
			PlaylistIndex: &idx,
		})
	}
	return out, nil
}

func TestTrackEpisodesPaginationAndMetadata(t *testing.T) {
	pagedProv := &pagedEpisodeStubProvider{
		stubProvider: stubProvider{id: "paged-src", engineName: "paged-src", category: "播客", label: "Paged"},
	}
	legacyProv := &legacySliceEpisodeProvider{
		stubProvider: stubProvider{id: "legacy-slice-src", engineName: "legacy-slice-src", category: "播客", label: "LegacySlice"},
	}

	srv, st, _ := newTestServer(t, func(deps *Dependencies, _ string) {
		reg, err := provider.NewRegistry([]provider.Entry{
			{Provider: pagedProv, Meta: provider.Metadata{Label: "Paged", Category: "播客"}},
			{Provider: legacyProv, Meta: provider.Metadata{Label: "LegacySlice", Category: "播客"}},
		})
		if err != nil {
			t.Fatal(err)
		}
		deps.Providers = reg
	})
	ctx := context.Background()

	// Seed parent with episodes=-1 and pre-existing episode_ids cache (must NOT truncate paged mode!).
	parentMeta, _ := json.Marshal(map[string]any{
		"episodes":    -1,
		"episode_ids": []string{"stale-ep-id"},
	})
	_, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		ID: "book-rec-1", Source: "paged-src", Locator: "book-1", RootSource: "paged-src",
		Title: "Paged Book", Artist: "Reader", MetadataJSON: string(parentMeta),
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "book-pub-1", Kind: "candidate", TargetID: "book-rec-1", Source: "paged-src", SourceID: "book-1"}, "")

	// 1. Paged request with page_size=2 gets clamped to 10; ignores stale episode_ids cache.
	rec1 := httptest.NewRecorder()
	srv.ServeHTTP(rec1, httptest.NewRequest(http.MethodGet, "/api/tracks/book-pub-1/episodes?page=1&page_size=2", nil))
	if rec1.Code != http.StatusOK {
		t.Fatalf("GET paged episodes p1 failed: %d %s", rec1.Code, rec1.Body.String())
	}
	var dto1 model.EpisodePageDTO
	if err := json.Unmarshal(rec1.Body.Bytes(), &dto1); err != nil {
		t.Fatalf("decode dto1: %v", err)
	}
	if dto1.Page != 1 || dto1.PageSize != 10 || dto1.Total != 15 || !dto1.HasMore || len(dto1.Items) != 10 {
		t.Fatalf("unexpected dto1: %+v", dto1)
	}
	if dto1.Items[0].PlaylistIndex == nil || *dto1.Items[0].PlaylistIndex != 1 {
		t.Fatalf("expected playlist_index=1, got %v", dto1.Items[0].PlaylistIndex)
	}
	// Verify playlist_index persisted into MetadataJSON and loaded back by loadDomainTrack.
	loadedEp, _, foundEp, lErr := srv.loadDomainTrack(ctx, dto1.Items[0].ID)
	if lErr != nil || !foundEp || loadedEp.PlaylistIndex == nil || *loadedEp.PlaylistIndex != 1 {
		t.Fatalf("expected loadDomainTrack to restore playlist_index=1, got found=%v ep=%+v err=%v", foundEp, loadedEp, lErr)
	}

	// 2. Page 2 returns remaining 5 items with has_more=false; page_size=500 clamped to 200.
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/api/tracks/book-pub-1/episodes?page=2&page_size=10", nil))
	if rec2.Code != http.StatusOK {
		t.Fatalf("GET paged episodes p2 failed: %d %s", rec2.Code, rec2.Body.String())
	}
	var dto2 model.EpisodePageDTO
	_ = json.Unmarshal(rec2.Body.Bytes(), &dto2)
	if dto2.Page != 2 || dto2.Total != 15 || dto2.HasMore || len(dto2.Items) != 5 || *dto2.Items[4].PlaylistIndex != 15 {
		t.Fatalf("unexpected dto2: %+v", dto2)
	}

	// 3. Verify parent track's episodes (-1) was NOT overwritten by single-page count.
	parentLoaded, _, _, _ := srv.loadDomainTrack(ctx, "book-pub-1")
	if parentLoaded.Episodes != -1 {
		t.Fatalf("expected parent episodes=-1 preserved in paged mode, got %d", parentLoaded.Episodes)
	}

	// 4. Out-of-range page serializes items as [] (not null), and ?paged=1 defaults page=1, page_size=100.
	recEmpty := httptest.NewRecorder()
	srv.ServeHTTP(recEmpty, httptest.NewRequest(http.MethodGet, "/api/tracks/book-pub-1/episodes?page=5&page_size=500", nil))
	if recEmpty.Code != http.StatusOK || !strings.Contains(recEmpty.Body.String(), `"items":[]`) || !strings.Contains(recEmpty.Body.String(), `"page_size":200`) {
		t.Fatalf("unexpected empty/clamped paged response: %d %s", recEmpty.Code, recEmpty.Body.String())
	}
	recPagedFlag := httptest.NewRecorder()
	srv.ServeHTTP(recPagedFlag, httptest.NewRequest(http.MethodGet, "/api/tracks/book-pub-1/episodes?paged=1", nil))
	if recPagedFlag.Code != http.StatusOK {
		t.Fatalf("GET ?paged=1 failed: %d %s", recPagedFlag.Code, recPagedFlag.Body.String())
	}
	var dtoPagedFlag model.EpisodePageDTO
	_ = json.Unmarshal(recPagedFlag.Body.Bytes(), &dtoPagedFlag)
	if dtoPagedFlag.Page != 1 || dtoPagedFlag.PageSize != 100 || len(dtoPagedFlag.Items) != 15 || dtoPagedFlag.HasMore {
		t.Fatalf("unexpected ?paged=1 result: %+v", dtoPagedFlag)
	}

	// 5. Legacy EpisodeProvider (non-PagedEpisodeProvider): unpaged returns []TrackDTO, paged slices in Go.
	_, err = st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		ID: "leg-rec-1", Source: "legacy-slice-src", Locator: "leg-1", RootSource: "legacy-slice-src",
		Title: "Legacy Pod", Artist: "Host",
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "leg-pub-1", Kind: "candidate", TargetID: "leg-rec-1", Source: "legacy-slice-src", SourceID: "leg-1"}, "")

	legPagedRec := httptest.NewRecorder()
	srv.ServeHTTP(legPagedRec, httptest.NewRequest(http.MethodGet, "/api/tracks/leg-pub-1/episodes?page=2&page_size=10", nil))
	if legPagedRec.Code != http.StatusOK {
		t.Fatalf("legacy paged failed: %d %s", legPagedRec.Code, legPagedRec.Body.String())
	}
	var legDTO model.EpisodePageDTO
	_ = json.Unmarshal(legPagedRec.Body.Bytes(), &legDTO)
	if legDTO.Page != 2 || legDTO.PageSize != 10 || legDTO.Total != 15 || legDTO.HasMore || len(legDTO.Items) != 5 {
		t.Fatalf("unexpected legacy Go-sliced page: %+v", legDTO)
	}

	legUnpagedRec := httptest.NewRecorder()
	srv.ServeHTTP(legUnpagedRec, httptest.NewRequest(http.MethodGet, "/api/tracks/leg-pub-1/episodes", nil))
	var unpagedArr []model.TrackDTO
	if err := json.Unmarshal(legUnpagedRec.Body.Bytes(), &unpagedArr); err != nil || len(unpagedArr) != 15 {
		t.Fatalf("expected unpaged []TrackDTO of 15 items, err=%v len=%d body=%s", err, len(unpagedArr), legUnpagedRec.Body.String())
	}
}

func TestDownloadEnqueueAudiobookSkipAutoOrganizeAndListLimit(t *testing.T) {
	wav := make([]byte, 44+32)
	copy(wav[0:4], "RIFF")
	binary.LittleEndian.PutUint32(wav[4:8], 36+32)
	copy(wav[8:16], "WAVEfmt ")
	binary.LittleEndian.PutUint32(wav[16:20], 16)
	binary.LittleEndian.PutUint16(wav[20:22], 1)
	binary.LittleEndian.PutUint16(wav[22:24], 1)
	binary.LittleEndian.PutUint32(wav[24:28], 8000)
	binary.LittleEndian.PutUint32(wav[28:32], 16000)
	binary.LittleEndian.PutUint16(wav[32:34], 2)
	binary.LittleEndian.PutUint16(wav[34:36], 16)
	copy(wav[36:40], "data")
	binary.LittleEndian.PutUint32(wav[40:44], 32)

	audioSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/wav")
		_, _ = w.Write(wav)
	}))
	defer audioSrv.Close()

	var dlSvc *download.Service
	srv, st, canonRoot := newTestServer(t, func(deps *Dependencies, tmp string) {
		prov := &stubProvider{
			id:         "AudiusMusicClient",
			engineName: "AudiusMusicClient",
			resolveFn: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
				br128 := 128
				br999 := 999
				return []model.ResolvedMedia{
					{
						VariantID: "var-flac",
						Variant:   model.MediaVariant{VariantID: "var-flac", Label: "FLAC", Codec: "flac", Bitrate: &br999, Lossless: true},
						URL:       audioSrv.URL + "/high.flac",
						Codec:     "flac",
						Bitrate:   &br999,
					},
					{
						VariantID: "var-mp3-128",
						Variant:   model.MediaVariant{VariantID: "var-mp3-128", Label: "MP3 128k", Codec: "mp3", Bitrate: &br128},
						URL:       audioSrv.URL + "/std.mp3",
						Codec:     "mp3",
						Bitrate:   &br128,
					},
				}, nil
			},
		}
		reg, _ := provider.NewRegistry([]provider.Entry{{Provider: prov, Meta: provider.Metadata{Label: "Audius"}}})
		deps.Providers = reg
		snapProvider := func() config.Snapshot { return deps.InitialSnapshot }
		scraper := library.NewScraper(deps.Transport, deps.Providers, deps.Media, library.ScraperOptions{})
		libSvc := library.NewService(deps.Store, deps.Files, deps.Tags, deps.Media, scraper, library.ServiceOptions{
			SnapshotProvider: snapProvider,
		})
		dlSvc = download.NewService(deps.Store, deps.Transport, deps.Providers, deps.Files, deps.Media, deps.Tags, download.Options{
			StagingRoot:        filepath.Join(tmp, "staging"),
			Budgets:            deps.Budgets,
			SnapshotProvider:   snapProvider,
			AutoOrganizeRunner: libSvc.AutoOrganizeDownload,
		})
		t.Cleanup(func() { _ = dlSvc.Close() })
		deps.Library = libSvc
		deps.Download = dlSvc
	})

	// Enable auto_scrape + auto_organize so we prove skip_auto_organize=true suppresses it.
	getSetRec := httptest.NewRecorder()
	srv.ServeHTTP(getSetRec, httptest.NewRequest(http.MethodGet, "/api/library/settings", nil))
	var settingsMap map[string]any
	_ = json.Unmarshal(getSetRec.Body.Bytes(), &settingsMap)
	settingsMap["auto_scrape"] = true
	settingsMap["auto_organize"] = true
	settingsBody, _ := json.Marshal(settingsMap)
	setReq := httptest.NewRequest(http.MethodPut, "/api/library/settings", bytes.NewReader(settingsBody))
	setReq.Header.Set("Content-Type", "application/json")
	setReq.Header.Set("X-MeloKeep-Request", "1")
	setRec := httptest.NewRecorder()
	srv.ServeHTTP(setRec, setReq)
	if setRec.Code != http.StatusOK {
		t.Fatalf("PUT /api/library/settings failed: %d %s", setRec.Code, setRec.Body.String())
	}

	ctx := context.Background()
	trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		ID: "ab-ep-3", Source: "AudiusMusicClient", Locator: "ab-loc-3", RootSource: "AudiusMusicClient",
		Title: "第三回", Artist: "说书人", Album: "", DurationMS: 1000,
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := st.PutPublicRef(ctx, model.PublicRef{
		PublicID: "pub-ab-ep-3", Kind: "candidate", TargetID: trID, Source: "AudiusMusicClient", SourceID: "ab-loc-3",
	}, ""); err != nil {
		t.Fatal(err)
	}

	enqBody := `[{"track_id":"pub-ab-ep-3","quality_id":"","preferred_quality":"standard","layout":"audiobook","book_title":"示例有声书","chapter_index":3,"chapter_total":50,"skip_auto_organize":true}]`
	enqReq := httptest.NewRequest(http.MethodPost, "/api/downloads", strings.NewReader(enqBody))
	enqReq.Header.Set("Content-Type", "application/json")
	enqReq.Header.Set("X-MeloKeep-Request", "1")
	enqRec := httptest.NewRecorder()
	srv.ServeHTTP(enqRec, enqReq)
	if enqRec.Code != http.StatusOK {
		t.Fatalf("POST /api/downloads failed: %d %s", enqRec.Code, enqRec.Body.String())
	}
	var jobs []model.DownloadJobDTO
	_ = json.Unmarshal(enqRec.Body.Bytes(), &jobs)
	if len(jobs) != 1 || jobs[0].QualityLabel != "自动（标准音质）" {
		t.Fatalf("unexpected enqueued audiobook job: %+v", jobs)
	}

	var finalDTO model.DownloadJobDTO
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		dl, ok, _ := dlSvc.Get(ctx, jobs[0].ID)
		if ok {
			dto, _ := dlSvc.ToDTO(ctx, dl)
			if dto.Status == "complete" {
				finalDTO = dto
				break
			}
		}
		time.Sleep(25 * time.Millisecond)
	}
	if finalDTO.Status != "complete" {
		t.Fatalf("audiobook download did not complete in time: %+v", finalDTO)
	}
	if finalDTO.Processing != nil {
		t.Fatalf("expected skip_auto_organize=true to skip Processing, got %+v", finalDTO.Processing)
	}
	wantPath := filepath.Join(canonRoot, "示例有声书", "003 - 第三回.mp3")
	if _, err := os.Stat(wantPath); err != nil {
		t.Fatalf("expected audiobook output at %s: %v", wantPath, err)
	}

	// Verify GET /api/downloads?limit=500 returns > 200 items while default returns 200.
	for i := 0; i < 249; i++ {
		if _, err := st.CreateDownload(ctx, store.CreateDownloadParams{
			ID:             fmt.Sprintf("seed-dl-%03d", i),
			TrackID:        trID,
			PublicID:       "pub-ab-ep-3",
			QualityID:      "",
			QualityLabel:   "自动（标准音质）",
			DownloadSource: "AudiusMusicClient",
			ActorKey:       "anonymous:web:anonymous",
			DedupeKey:      fmt.Sprintf("seed-dedupe-%03d", i),
			ForceNew:       true,
			IntentNonce:    fmt.Sprintf("nonce-%03d", i),
		}); err != nil {
			t.Fatalf("seed download %d: %v", i, err)
		}
	}

	defRec := httptest.NewRecorder()
	srv.ServeHTTP(defRec, httptest.NewRequest(http.MethodGet, "/api/downloads", nil))
	var defList []model.DownloadJobDTO
	_ = json.Unmarshal(defRec.Body.Bytes(), &defList)
	if len(defList) != 200 {
		t.Fatalf("expected default GET /api/downloads to return 200 items, got %d", len(defList))
	}

	limRec := httptest.NewRecorder()
	srv.ServeHTTP(limRec, httptest.NewRequest(http.MethodGet, "/api/downloads?limit=500", nil))
	var limList []model.DownloadJobDTO
	_ = json.Unmarshal(limRec.Body.Bytes(), &limList)
	if len(limList) != 250 {
		t.Fatalf("expected GET /api/downloads?limit=500 to return 250 items, got %d", len(limList))
	}
}

func TestEngineConfigWorkDirValidationAndOpenDir(t *testing.T) {
	openCalled := 0
	srv, _, canonRoot := newTestServer(t, func(deps *Dependencies, _ string) {
		deps.OpenDirCallback = func() error {
			openCalled++
			return nil
		}
	})

	putEngine := func(body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPut, "/api/engine/config", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec
	}

	// 1. Blank work_dir rejected with 400.
	for _, payload := range []string{
		`{"global_client_cfg":{"work_dir":"   "}}`,
		`{"work_dir":""}`,
	} {
		rec := putEngine(payload)
		if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "下载目录不能为空") {
			t.Fatalf("expected 400 blank work_dir for %s, got %d %s", payload, rec.Code, rec.Body.String())
		}
	}

	// 2. Outside-root and traversal work_dir rejected with 400.
	outsideDir := filepath.Join(t.TempDir(), "outside")
	outsideBytes, _ := json.Marshal(map[string]any{"work_dir": outsideDir})
	for _, payload := range []string{
		string(outsideBytes),
		`{"global_client_cfg":{"work_dir":"../outside"}}`,
	} {
		rec := putEngine(payload)
		if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "MELOKEEP_FILE_ROOT") {
			t.Fatalf("expected 400 outside work_dir for %s, got %d %s", payload, rec.Code, rec.Body.String())
		}
	}

	// 3. Relative subdir via global_client_cfg.work_dir succeeds and creates subdir on disk.
	recSub := putEngine(`{"global_client_cfg":{"work_dir":"待刮削"}}`)
	if recSub.Code != http.StatusOK {
		t.Fatalf("expected 200 for relative subdir, got %d %s", recSub.Code, recSub.Body.String())
	}
	wantSub := filepath.Join(canonRoot, "待刮削")
	if got := srv.currentSnapshot().Runtime.WorkDir; got != wantSub {
		t.Fatalf("Runtime.WorkDir = %q, want %q", got, wantSub)
	}
	if st, err := os.Stat(wantSub); err != nil || !st.IsDir() {
		t.Fatalf("expected %s created on disk: err=%v", wantSub, err)
	}

	// 4. Top-level work_dir works when global_client_cfg.work_dir is blank/omitted.
	recTop := putEngine(`{"global_client_cfg":{"work_dir":""},"work_dir":"incoming/sub"}`)
	if recTop.Code != http.StatusOK {
		t.Fatalf("expected 200 for top-level work_dir fallback, got %d %s", recTop.Code, recTop.Body.String())
	}
	wantTop := filepath.Join(canonRoot, "incoming", "sub")
	if got := srv.currentSnapshot().Runtime.WorkDir; got != wantTop {
		t.Fatalf("Runtime.WorkDir = %q, want %q", got, wantTop)
	}

	// 5. handleOpenDir uses dynamic Runtime.WorkDir.
	openReq := httptest.NewRequest(http.MethodPost, "/api/open-dir", nil)
	openReq.Header.Set("X-MeloKeep-Request", "1")
	openRec := httptest.NewRecorder()
	srv.ServeHTTP(openRec, openReq)
	if openRec.Code != http.StatusOK || openCalled != 1 {
		t.Fatalf("POST /api/open-dir failed: code=%d called=%d body=%s", openRec.Code, openCalled, openRec.Body.String())
	}
	var openResp map[string]any
	if err := json.Unmarshal(openRec.Body.Bytes(), &openResp); err != nil {
		t.Fatal(err)
	}
	if openResp["dir"] != wantTop {
		t.Fatalf("expected open-dir dir=%q, got %v", wantTop, openResp["dir"])
	}

	// Non-desktop mode also surfaces dynamic Runtime.WorkDir in error message.
	srv.deps.IsWindows = false
	openReq2 := httptest.NewRequest(http.MethodPost, "/api/open-dir", nil)
	openReq2.Header.Set("X-MeloKeep-Request", "1")
	openRec2 := httptest.NewRecorder()
	srv.ServeHTTP(openRec2, openReq2)
	var errResp struct {
		Detail string `json:"detail"`
	}
	if err := json.Unmarshal(openRec2.Body.Bytes(), &errResp); err != nil {
		t.Fatalf("unmarshal open-dir error response: %v body=%s", err, openRec2.Body.String())
	}
	if openRec2.Code != http.StatusBadRequest || !strings.Contains(errResp.Detail, wantTop) {
		t.Fatalf("expected non-desktop open-dir error to mention %q, got %d %s", wantTop, openRec2.Code, openRec2.Body.String())
	}
}

func TestEngineConfigWorkDirRejectsFileAndUnwritableDirs(t *testing.T) {
	srv, _, canonRoot := newTestServer(t, nil)
	putEngine := func(body string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(http.MethodPut, "/api/engine/config", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec
	}

	prevWorkDir := srv.currentSnapshot().Runtime.WorkDir

	// 1. Cross-platform: existing regular file as work_dir must be rejected and leave Runtime.WorkDir unchanged.
	notDirPath := filepath.Join(canonRoot, "not_a_dir.txt")
	if err := os.WriteFile(notDirPath, []byte("file"), 0o644); err != nil {
		t.Fatal(err)
	}
	recFile := putEngine(`{"global_client_cfg":{"work_dir":"not_a_dir.txt"}}`)
	if recFile.Code != http.StatusBadRequest || !strings.Contains(recFile.Body.String(), "不是目录") {
		t.Fatalf("expected 400 non-dir rejection, got %d %s", recFile.Code, recFile.Body.String())
	}
	if got := srv.currentSnapshot().Runtime.WorkDir; got != prevWorkDir {
		t.Fatalf("Runtime.WorkDir changed after file rejection: got %q want %q", got, prevWorkDir)
	}

	// 2. Unix: 0o500 and 0o000 subdirectories must be rejected with 403 + UID:GID + PUID/PGID hint.
	if runtime.GOOS != "windows" && os.Geteuid() != 0 {
		roDir := filepath.Join(canonRoot, "ro-dir")
		if err := os.MkdirAll(roDir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.Chmod(roDir, 0o500); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = os.Chmod(roDir, 0o755) })

		lockedDir := filepath.Join(canonRoot, "locked-dir")
		if err := os.MkdirAll(lockedDir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.Chmod(lockedDir, 0o000); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = os.Chmod(lockedDir, 0o755) })

		for _, tc := range []struct {
			name    string
			workDir string
			wantDir string
		}{
			{"0o500 dir", "ro-dir", "ro-dir"},
			{"0o500 nested", "ro-dir/待刮削", "ro-dir"},
			{"0o000 dir", "locked-dir", "locked-dir"},
			{"0o000 nested", "locked-dir/待刮削", "locked-dir"},
		} {
			payload, _ := json.Marshal(map[string]any{
				"global_client_cfg": map[string]any{"work_dir": tc.workDir},
			})
			rec := putEngine(string(payload))
			body := rec.Body.String()
			if rec.Code != http.StatusForbidden {
				t.Fatalf("%s: expected 403, got %d %s", tc.name, rec.Code, body)
			}
			if !strings.Contains(body, "UID:GID=") || !strings.Contains(body, "PUID/PGID") || !strings.Contains(body, tc.wantDir) {
				t.Fatalf("%s: expected UID:GID, PUID/PGID, and %q in %s", tc.name, tc.wantDir, body)
			}
			if got := srv.currentSnapshot().Runtime.WorkDir; got != prevWorkDir {
				t.Fatalf("%s: Runtime.WorkDir changed: got %q want %q", tc.name, got, prevWorkDir)
			}
		}
	}
}

func TestCollectionCreateAndAddTracksValidation(t *testing.T) {
	srv, st, canonRoot := newTestServer(t, nil)
	ctx := context.Background()

	postJSON := func(path string, payload any) *httptest.ResponseRecorder {
		t.Helper()
		raw, err := json.Marshal(payload)
		if err != nil {
			t.Fatalf("marshal payload: %v", err)
		}
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec
	}

	seedOnlineSearchTrack := func(pubID, sourceID, title string) {
		t.Helper()
		trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
			Source:     "AudiusMusicClient",
			Locator:    sourceID,
			RootSource: "AudiusMusicClient",
			Title:      title,
			Artist:     "Online Artist",
			Album:      "Online Album",
			DurationMS: 185000,
		}, []model.MediaVariant{{
			VariantID:    "engine:mp3:320",
			Label:        "MP3 320k",
			Codec:        "mp3",
			Availability: "resolved",
			Evidence:     "engine",
		}})
		if err != nil {
			t.Fatalf("UpsertProviderTrack(%s): %v", pubID, err)
		}
		if err := st.PutPublicRef(ctx, model.PublicRef{
			PublicID: pubID,
			Kind:     "candidate",
			TargetID: trID,
			Source:   "AudiusMusicClient",
			SourceID: sourceID,
		}, ""); err != nil {
			t.Fatalf("PutPublicRef(%s): %v", pubID, err)
		}
		dto := model.TrackDTO{
			ID:         pubID,
			Source:     "AudiusMusicClient",
			SourceID:   sourceID,
			RootSource: "AudiusMusicClient",
			Title:      title,
			Artist:     "Online Artist",
			Album:      "Online Album",
			Duration:   185,
			Playback: model.PlaybackDTO{
				State: "pending",
			},
		}
		rawDTO, err := json.Marshal(dto)
		if err != nil {
			t.Fatalf("marshal track_dto(%s): %v", pubID, err)
		}
		if _, err := st.PutDurableRecord(ctx, "track_dto", pubID, string(rawDTO), 0); err != nil {
			t.Fatalf("PutDurableRecord track_dto(%s): %v", pubID, err)
		}
	}

	// Seed two track_dto-persisted online search tracks and one local asset track.
	track1 := "cand-online-01"
	track2 := "cand-online-02"
	seedOnlineSearchTrack(track1, "audius-src-01", "在线检索曲目一")
	seedOnlineSearchTrack(track2, "audius-src-02", "在线检索曲目二")

	localFile := filepath.Join(canonRoot, "local-pl.wav")
	if err := os.WriteFile(localFile, []byte("RIFF0000WAVE"), 0o644); err != nil {
		t.Fatal(err)
	}
	assetRec, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID:   "root-1",
		RelPath:  "local-pl.wav",
		PathKey:  "local-pl.wav",
		Name:     "local-pl.wav",
		Status:   "active",
		Editable: true,
		Size:     12,
		Title:    "本地曲目",
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	localTrack := "asset:" + assetRec.ID
	if err := st.PutPublicRef(ctx, model.PublicRef{
		PublicID: localTrack,
		Kind:     "asset",
		TargetID: assetRec.ID,
		Source:   "local",
		SourceID: assetRec.ID,
	}, ""); err != nil {
		t.Fatal(err)
	}

	// 1. Empty track_ids still creates an empty playlist (200 OK).
	emptyRec := postJSON("/api/playlists", map[string]any{
		"name":      "空白歌单",
		"track_ids": []string{},
	})
	if emptyRec.Code != http.StatusOK {
		t.Fatalf("expected 200 creating empty playlist, got %d %s", emptyRec.Code, emptyRec.Body.String())
	}
	var emptyDTO model.SavedPlaylistDTO
	if err := json.Unmarshal(emptyRec.Body.Bytes(), &emptyDTO); err != nil || len(emptyDTO.Tracks) != 0 {
		t.Fatalf("unexpected empty playlist DTO: err=%v body=%s", err, emptyRec.Body.String())
	}

	// 2. Creating playlist with all invalid track_ids returns 404 and does NOT persist an empty playlist.
	invalidCreateRec := postJSON("/api/playlists", map[string]any{
		"name":      "无效歌单",
		"track_ids": []string{"missing-1", "missing-1", "device:local-1", "radio_1"},
	})
	if invalidCreateRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for invalid create track_ids, got %d %s", invalidCreateRec.Code, invalidCreateRec.Body.String())
	}
	var errDTO model.ErrorDTO
	_ = json.Unmarshal(invalidCreateRec.Body.Bytes(), &errDTO)
	if errDTO.Detail != "曲目不存在或已失效，无法创建并加入歌单" {
		t.Fatalf("unexpected create error detail: %v", errDTO.Detail)
	}
	cols, err := st.ListCollections(ctx, "", "playlist")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range cols {
		if c.Name == "无效歌单" {
			t.Fatalf("invalid playlist creation leaked collection %+v", c)
		}
	}

	// 3. Creating playlist with valid track_dto-persisted online search track succeeds.
	createRec := postJSON("/api/playlists", map[string]any{
		"name":      "在线精选",
		"track_ids": []string{track1, track1},
	})
	if createRec.Code != http.StatusOK {
		t.Fatalf("expected 200 creating playlist with track_dto track, got %d %s", createRec.Code, createRec.Body.String())
	}
	var created model.SavedPlaylistDTO
	if err := json.Unmarshal(createRec.Body.Bytes(), &created); err != nil {
		t.Fatalf("unmarshal created playlist: %v", err)
	}
	if created.ID == "" || len(created.Tracks) != 1 || created.Tracks[0].ID != track1 || created.Tracks[0].Title != "在线检索曲目一" {
		t.Fatalf("unexpected created playlist: %+v", created)
	}

	// 4. Adding all invalid track_ids to existing playlist returns 404 and leaves tracks untouched.
	invalidAddRec := postJSON("/api/playlists/"+created.ID+"/tracks", map[string]any{
		"track_ids": []string{"missing-2", "missing-2", "device:99"},
	})
	if invalidAddRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for invalid add track_ids, got %d %s", invalidAddRec.Code, invalidAddRec.Body.String())
	}
	var addErrDTO model.ErrorDTO
	_ = json.Unmarshal(invalidAddRec.Body.Bytes(), &addErrDTO)
	if addErrDTO.Detail != "曲目不存在或已失效，无法加入歌单" {
		t.Fatalf("unexpected add error detail: %v", addErrDTO.Detail)
	}

	// 5. Adding new valid tracks (duplicate track2 in request + local asset track) adds deduplicated count once.
	addRec := postJSON("/api/playlists/"+created.ID+"/tracks", map[string]any{
		"track_ids": []string{track2, track2, localTrack},
	})
	if addRec.Code != http.StatusOK {
		t.Fatalf("expected 200 adding valid tracks, got %d %s", addRec.Code, addRec.Body.String())
	}
	var added model.SavedPlaylistDTO
	if err := json.Unmarshal(addRec.Body.Bytes(), &added); err != nil {
		t.Fatalf("unmarshal added playlist: %v", err)
	}
	wantAddDetail := "已向歌单“在线精选”加入 2 首曲目，共 3 首。"
	if len(added.Tracks) != 3 || added.Detail != wantAddDetail {
		t.Fatalf("unexpected add result: len=%d detail=%q want=%q", len(added.Tracks), added.Detail, wantAddDetail)
	}

	// 6. Re-adding already existing tracks returns 200 OK with "already in playlist" detail.
	dupAddRec := postJSON("/api/playlists/"+created.ID+"/tracks", map[string]any{
		"track_ids": []string{track1, track1, track2},
	})
	if dupAddRec.Code != http.StatusOK {
		t.Fatalf("expected 200 re-adding existing tracks, got %d %s", dupAddRec.Code, dupAddRec.Body.String())
	}
	var dupAdded model.SavedPlaylistDTO
	if err := json.Unmarshal(dupAddRec.Body.Bytes(), &dupAdded); err != nil {
		t.Fatalf("unmarshal dup add playlist: %v", err)
	}
	wantDupDetail := "所选曲目已在歌单“在线精选”中，共 3 首。"
	if len(dupAdded.Tracks) != 3 || dupAdded.Detail != wantDupDetail {
		t.Fatalf("unexpected duplicate add result: len=%d detail=%q want=%q", len(dupAdded.Tracks), dupAdded.Detail, wantDupDetail)
	}

	// 7. Re-adding an existing track alongside a missing ID also returns 200 OK with "already in playlist" detail.
	mixedDupRec := postJSON("/api/playlists/"+created.ID+"/tracks", map[string]any{
		"track_ids": []string{track1, "missing-3"},
	})
	if mixedDupRec.Code != http.StatusOK {
		t.Fatalf("expected 200 for existing+missing tracks, got %d %s", mixedDupRec.Code, mixedDupRec.Body.String())
	}
	var mixedDup model.SavedPlaylistDTO
	if err := json.Unmarshal(mixedDupRec.Body.Bytes(), &mixedDup); err != nil {
		t.Fatalf("unmarshal mixed dup playlist: %v", err)
	}
	if len(mixedDup.Tracks) != 3 || mixedDup.Detail != wantDupDetail {
		t.Fatalf("unexpected mixed duplicate add result: len=%d detail=%q want=%q", len(mixedDup.Tracks), mixedDup.Detail, wantDupDetail)
	}
}

func TestDownloadDeleteOptionalFileRemovalAndPermissions(t *testing.T) {
	srv, st, canonRoot := newTestServer(t, nil)
	dlSvc := download.NewService(st, srv.deps.Transport, srv.deps.Providers, srv.deps.Files, nil, nil, download.Options{
		StagingRoot: t.TempDir(),
	})
	t.Cleanup(func() { _ = dlSvc.Close() })
	srv.deps.Download = dlSvc

	ctx := context.Background()
	seedDownloadedJob := func(suffix string, owner string, createOnDisk bool, badPath string) (dlID string, assetID string, filePath string) {
		trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
			Source:     "AudiusMusicClient",
			Locator:    "dl-loc-" + suffix,
			RootSource: "AudiusMusicClient",
			Title:      "Track " + suffix,
			Artist:     "Artist A",
		}, []model.MediaVariant{{VariantID: "v-" + suffix, Label: "FLAC", Codec: "flac", Availability: "resolved"}})
		if err != nil {
			t.Fatal(err)
		}
		pubID := "pub-dl-" + suffix
		if err := st.PutPublicRef(ctx, model.PublicRef{
			PublicID: pubID, Kind: "candidate", TargetID: trID, Source: "AudiusMusicClient", SourceID: "dl-loc-" + suffix,
		}, ""); err != nil {
			t.Fatal(err)
		}
		res, err := st.CreateDownload(ctx, store.CreateDownloadParams{
			TrackID:        trID,
			VariantID:      "v-" + suffix,
			PublicID:       pubID,
			QualityID:      "v-" + suffix,
			QualityLabel:   "FLAC",
			DownloadSource: "AudiusMusicClient",
			ActorKey:       owner,
			DedupeKey:      "dedupe-" + suffix,
			Detail:         "queued",
		})
		if err != nil {
			t.Fatal(err)
		}
		if badPath != "" {
			if _, err := st.Writer().ExecContext(ctx, `UPDATE downloads SET state = ?, file_token = ? WHERE id = ?`, string(model.DownloadComplete), badPath, res.Download.ID); err != nil {
				t.Fatal(err)
			}
			return res.Download.ID, "", ""
		}

		rel := filepath.ToSlash(filepath.Join("Artist A", "Track-"+suffix+".flac"))
		abs := filepath.Join(canonRoot, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(abs, []byte("fLaC-TEST-"+suffix), 0o644); err != nil {
			t.Fatal(err)
		}
		assetRec, err := st.UpsertAsset(ctx, store.AssetRecord{
			RootID:   "root-1",
			RelPath:  rel,
			PathKey:  files.ComputePathKey("insensitive", rel),
			Folder:   "Artist A",
			Name:     "Track-" + suffix + ".flac",
			Status:   "active",
			Editable: true,
			Size:     int64(len("fLaC-TEST-" + suffix)),
			Title:    "Track " + suffix,
			Artist:   "Artist A",
		}, 0, true)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := st.Writer().ExecContext(ctx, `UPDATE downloads SET state = ?, file_token = ? WHERE id = ?`, string(model.DownloadComplete), "asset:"+assetRec.ID, res.Download.ID); err != nil {
			t.Fatal(err)
		}
		if !createOnDisk {
			_ = os.Remove(abs)
		}
		return res.Download.ID, assetRec.ID, abs
	}

	delHTTP := func(target string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodDelete, target, nil)
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec
	}

	// 1. Default delete without delete_file preserves file on disk.
	id1, _, path1 := seedDownloadedJob("1", "anonymous:web:anonymous", true, "")
	rec1 := delHTTP("/api/downloads/" + id1)
	if rec1.Code != http.StatusOK {
		t.Fatalf("DELETE without delete_file failed: %d %s", rec1.Code, rec1.Body.String())
	}
	var out1 struct {
		Removed     bool     `json:"removed"`
		FileDeleted bool     `json:"file_deleted"`
		TrackIDs    []string `json:"track_ids"`
	}
	if err := json.Unmarshal(rec1.Body.Bytes(), &out1); err != nil {
		t.Fatal(err)
	}
	if !out1.Removed || out1.FileDeleted || out1.TrackIDs == nil || len(out1.TrackIDs) != 0 {
		t.Fatalf("unexpected default delete response: %+v raw=%s", out1, rec1.Body.String())
	}
	if _, err := os.Stat(path1); err != nil {
		t.Fatalf("expected file preserved when delete_file omitted: %v", err)
	}

	// 2. delete_file=1 deletes file, soft-deletes asset, cleans refs, and returns track_ids.
	id2, asset2, path2 := seedDownloadedJob("2", "anonymous:web:anonymous", true, "")
	if err := st.PutPublicRef(ctx, model.PublicRef{
		PublicID: "asset:" + asset2, Kind: "asset", TargetID: asset2, Source: "local", SourceID: asset2,
	}, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := st.PutDurableRecord(ctx, durableNamespaceFavorite, "asset:"+asset2, `{"id":"asset:`+asset2+`"}`, 0); err != nil {
		t.Fatal(err)
	}
	col, err := st.CreateCollection(ctx, store.CreateCollectionParams{Name: "Cleanup List", Kind: "playlist", OwnerKey: "anonymous:web:anonymous"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.AddCollectionItems(ctx, col.ID, []string{"asset:" + asset2}, 1); err != nil {
		t.Fatal(err)
	}

	rec2 := delHTTP("/api/downloads/" + id2 + "?delete_file=1")
	if rec2.Code != http.StatusOK {
		t.Fatalf("DELETE ?delete_file=1 failed: %d %s", rec2.Code, rec2.Body.String())
	}
	var out2 struct {
		Removed     bool     `json:"removed"`
		FileDeleted bool     `json:"file_deleted"`
		TrackIDs    []string `json:"track_ids"`
	}
	if err := json.Unmarshal(rec2.Body.Bytes(), &out2); err != nil {
		t.Fatal(err)
	}
	if !out2.Removed || !out2.FileDeleted || len(out2.TrackIDs) != 2 {
		t.Fatalf("unexpected delete_file=1 response: %+v raw=%s", out2, rec2.Body.String())
	}
	if _, err := os.Stat(path2); !os.IsNotExist(err) {
		t.Fatalf("expected file removed from disk, statErr=%v", err)
	}
	if gotAsset, ok, _ := st.GetAsset(ctx, asset2); !ok || gotAsset.Status != "deleted" {
		t.Fatalf("expected asset soft deleted, ok=%v status=%s", ok, gotAsset.Status)
	}
	if _, favOK, _ := st.GetDurableRecord(ctx, durableNamespaceFavorite, "asset:"+asset2); favOK {
		t.Fatal("expected favorite reference cleaned up")
	}
	if _, colItems, _, _ := st.GetCollection(ctx, col.ID); len(colItems) != 0 {
		t.Fatalf("expected collection item cleaned up, got %+v", colItems)
	}

	// 3. delete_file=YeS tolerates already-missing file on disk and still removes download record.
	id3, _, _ := seedDownloadedJob("3", "anonymous:web:anonymous", false, "")
	rec3 := delHTTP("/api/downloads/" + id3 + "?delete_file=YeS")
	if rec3.Code != http.StatusOK {
		t.Fatalf("DELETE missing file failed: %d %s", rec3.Code, rec3.Body.String())
	}
	var out3 struct {
		Removed     bool     `json:"removed"`
		FileDeleted bool     `json:"file_deleted"`
		TrackIDs    []string `json:"track_ids"`
	}
	_ = json.Unmarshal(rec3.Body.Bytes(), &out3)
	if !out3.Removed || out3.FileDeleted || out3.TrackIDs == nil || len(out3.TrackIDs) != 0 {
		t.Fatalf("unexpected missing-file response: %+v", out3)
	}
	if _, exists, _ := dlSvc.Get(ctx, id3); exists {
		t.Fatal("expected download record removed when file missing")
	}

	// 4. Non-NotFound file error (path outside roots) returns error and keeps download record.
	outsideFile := filepath.Join(t.TempDir(), "outside.flac")
	_ = os.WriteFile(outsideFile, []byte("fLaC"), 0o644)
	id4, _, _ := seedDownloadedJob("4", "anonymous:web:anonymous", false, outsideFile)
	rec4 := delHTTP("/api/downloads/" + id4 + "?delete_file=true")
	if rec4.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for outside-root file, got %d %s", rec4.Code, rec4.Body.String())
	}
	if _, exists, _ := dlSvc.Get(ctx, id4); !exists {
		t.Fatal("download record must NOT be deleted when file resolution fails with non-NotFound error")
	}

	// 5. Permission checks for delete_file=1: ReadOnlyAI and missing PermLocalWrite return 403 and keep both record & file.
	ownerActor := auth.ChannelActor("telegram", "u1", auth.PermRead, auth.PermLocalWrite)
	id5, _, path5 := seedDownloadedJob("5", ownerActor.Key(), true, "")

	callAs := func(actor auth.Actor) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodDelete, "/api/downloads/"+id5+"?delete_file=1", nil)
		req.SetPathValue("ident", id5)
		req = req.WithContext(withActor(req.Context(), actor))
		rec := httptest.NewRecorder()
		srv.handleDownloadDelete(rec, req)
		return rec
	}

	roAI := ownerActor
	roAI.ReadOnlyAI = true
	if rec := callAs(roAI); rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for ReadOnlyAI, got %d %s", rec.Code, rec.Body.String())
	}
	readOnlyUser := auth.ChannelActor("telegram", "u1", auth.PermRead)
	if rec := callAs(readOnlyUser); rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 without PermLocalWrite, got %d %s", rec.Code, rec.Body.String())
	}
	otherUser := auth.ChannelActor("telegram", "u2", auth.PermRead, auth.PermLocalWrite)
	if rec := callAs(otherUser); rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for non-owner, got %d %s", rec.Code, rec.Body.String())
	}
	if _, err := os.Stat(path5); err != nil {
		t.Fatalf("file should still exist after rejected permission checks: %v", err)
	}
	if _, exists, _ := dlSvc.Get(ctx, id5); !exists {
		t.Fatal("download record should still exist after rejected permission checks")
	}
}

func TestCollectionBatchRemoveTracks(t *testing.T) {
	srv, st, _ := newTestServer(t, nil)
	ctx := context.Background()

	seedTrack := func(id, title string) {
		trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
			Source:     "AudiusMusicClient",
			Locator:    "src-" + id,
			RootSource: "AudiusMusicClient",
			Title:      title,
			Artist:     "Artist A",
		}, nil)
		if err != nil {
			t.Fatal(err)
		}
		if err := st.PutPublicRef(ctx, model.PublicRef{
			PublicID: id,
			Kind:     "candidate",
			TargetID: trID,
			Source:   "AudiusMusicClient",
			SourceID: "src-" + id,
		}, ""); err != nil {
			t.Fatal(err)
		}
		dto := model.TrackDTO{
			ID:       id,
			Source:   "AudiusMusicClient",
			SourceID: "src-" + id,
			Title:    title,
			Artist:   "Artist A",
			Playback: model.PlaybackDTO{State: "pending"},
		}
		raw, _ := json.Marshal(dto)
		if _, err := st.PutDurableRecord(ctx, "track_dto", id, string(raw), 0); err != nil {
			t.Fatal(err)
		}
	}
	seedTrack("trk-1", "Track A")
	seedTrack("trk-2", "Track B")
	seedTrack("trk-3", "Track C")
	seedTrack("trk-4", "Track D")

	postJSON := func(path string, body any) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(body)
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec
	}

	createRec := postJSON("/api/playlists", map[string]any{
		"name":      "Batch Remove Demo",
		"track_ids": []string{"trk-1", "trk-2", "trk-3", "trk-4"},
	})
	if createRec.Code != http.StatusOK {
		t.Fatalf("create playlist failed: %d %s", createRec.Code, createRec.Body.String())
	}
	var created model.SavedPlaylistDTO
	_ = json.Unmarshal(createRec.Body.Bytes(), &created)

	// 1. Empty / whitespace-only track_ids -> 400.
	emptyRec := postJSON("/api/playlists/"+created.ID+"/tracks/remove", map[string]any{
		"track_ids": []string{"", "   "},
	})
	if emptyRec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for blank track_ids, got %d %s", emptyRec.Code, emptyRec.Body.String())
	}
	var errDTO model.ErrorDTO
	_ = json.Unmarshal(emptyRec.Body.Bytes(), &errDTO)
	if errDTO.Detail != "请选择要移出的曲目" {
		t.Fatalf("unexpected 400 detail: %v", errDTO.Detail)
	}

	// 2. Missing playlist -> 404.
	missingRec := postJSON("/api/playlists/non-existent/tracks/remove", map[string]any{
		"track_ids": []string{"trk-1"},
	})
	if missingRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for missing playlist, got %d %s", missingRec.Code, missingRec.Body.String())
	}

	// 3. Batch remove trk-2 (duplicated + padded) and trk-4 alongside absent ref.
	remRec := postJSON("/api/playlists/"+created.ID+"/tracks/remove", map[string]any{
		"track_ids": []string{"trk-2", " trk-2 ", "trk-4", "trk-absent", ""},
	})
	if remRec.Code != http.StatusOK {
		t.Fatalf("batch remove failed: %d %s", remRec.Code, remRec.Body.String())
	}
	var updated model.SavedPlaylistDTO
	if err := json.Unmarshal(remRec.Body.Bytes(), &updated); err != nil {
		t.Fatal(err)
	}
	if len(updated.Tracks) != 2 || updated.Tracks[0].ID != "trk-1" || updated.Tracks[1].ID != "trk-3" {
		t.Fatalf("unexpected remaining tracks: %+v", updated.Tracks)
	}
	wantDetail := "已从歌单“Batch Remove Demo”移出 2 首曲目，共 2 首。"
	if updated.Detail != wantDetail {
		t.Fatalf("unexpected Detail: %q, want %q", updated.Detail, wantDetail)
	}

	// Verify DB positions compacted to 0, 1.
	_, items, ok, err := st.GetCollection(ctx, created.ID)
	if err != nil || !ok || len(items) != 2 || items[0].Position != 0 || items[1].Position != 1 {
		t.Fatalf("expected compacted positions 0,1; got ok=%v items=%+v err=%v", ok, items, err)
	}
}
