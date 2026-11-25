package httpapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func TestSystemUpdateCompareAndCache(t *testing.T) {
	var calls atomic.Int32
	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		switch r.URL.Path {
		case "/repos/Agonie0v0/melokeep/releases/latest":
			w.WriteHeader(http.StatusNotFound)
		case "/repos/Agonie0v0/melokeep/compare/abc1234567890...main":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"status":   "ahead",
				"ahead_by": 2,
				"html_url": "https://github.com/Agonie0v0/melokeep/compare/abc1234567890...main",
				"commits": []map[string]any{
					{
						"sha":      "1111111111111111",
						"html_url": "https://github.com/Agonie0v0/melokeep/commit/1111111111111111",
						"commit": map[string]any{
							"message": "fix: first change\n\ndetails",
							"author":  map[string]any{"name": "alice", "date": "2026-11-17T08:00:00Z"},
						},
					},
					{
						"sha":      "2222222222222222",
						"html_url": "https://github.com/Agonie0v0/melokeep/commit/2222222222222222",
						"commit": map[string]any{
							"message": "feat: newest change",
							"author":  map[string]any{"name": "bob", "date": "2026-11-17T09:00:00Z"},
						},
					},
				},
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer gh.Close()

	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		// Use nil Transport in unit test to bypass public-IP SSRF guard against httptest loopback.
		d.Transport = nil
		d.GitHubAPIBase = gh.URL
		d.BuildInfo = model.BuildInfoDTO{
			Version:     "1.0.0-go",
			Commit:      "abc1234567890",
			ShortCommit: "abc1234",
			Ref:         "main",
			InContainer: true,
		}
	})

	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var got model.SystemUpdateDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if got.Mode != "compare" || !got.HasUpdate || !got.Comparable || got.AheadBy != 2 {
		t.Fatalf("unexpected update result: %+v", got)
	}
	if got.Cached {
		t.Fatalf("first check should not be cached")
	}
	if len(got.Commits) != 2 || got.Commits[0].Short != "2222222" || got.Commits[0].Title != "feat: newest change" {
		t.Fatalf("expected newest commit first, got %+v", got.Commits)
	}

	firstCalls := calls.Load()
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	var cached model.SystemUpdateDTO
	_ = json.Unmarshal(rec2.Body.Bytes(), &cached)
	if !cached.Cached || calls.Load() != firstCalls {
		t.Fatalf("expected cached response without hitting GitHub again, calls=%d->%d cached=%v", firstCalls, calls.Load(), cached.Cached)
	}

	rec3 := httptest.NewRecorder()
	srv.ServeHTTP(rec3, httptest.NewRequest(http.MethodGet, "/api/system/update?force=1", nil))
	var forced model.SystemUpdateDTO
	_ = json.Unmarshal(rec3.Body.Bytes(), &forced)
	if forced.Cached || calls.Load() == firstCalls {
		t.Fatalf("expected force=1 to bypass cache")
	}
}

func TestSystemUpdateReleaseModeAndRateLimit(t *testing.T) {
	var mode string = "release"
	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if mode == "ratelimit" {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		if r.URL.Path == "/repos/Agonie0v0/melokeep/releases/latest" {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"tag_name":     "v1.2.0",
				"name":         "v1.2.0",
				"body":         "## 更新内容\n- 修复若干问题",
				"published_at": "2026-11-17T09:00:00Z",
				"html_url":     "https://github.com/Agonie0v0/melokeep/releases/tag/v1.2.0",
			})
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer gh.Close()

	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.Transport = nil
		d.GitHubAPIBase = gh.URL
		d.BuildInfo = model.BuildInfoDTO{Version: "v1.1.0"}
	})

	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	var got model.SystemUpdateDTO
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.Mode != "release" || !got.HasUpdate || !got.Comparable || !strings.Contains(got.ReleaseNotes, "更新内容") {
		t.Fatalf("unexpected release result: %+v", got)
	}

	mode = "ratelimit"
	recErr := httptest.NewRecorder()
	srv.ServeHTTP(recErr, httptest.NewRequest(http.MethodGet, "/api/system/update?force=1", nil))
	if recErr.Code != http.StatusOK {
		t.Fatalf("expected graceful 200 on rate limit, got %d", recErr.Code)
	}
	var errDTO model.SystemUpdateDTO
	_ = json.Unmarshal(recErr.Body.Bytes(), &errDTO)
	if errDTO.Error == "" || errDTO.Mode != "error" {
		t.Fatalf("expected error field on rate limit, got %+v", errDTO)
	}
}

func TestSystemUpdateAssetsAndSelfUpdateEndpoints(t *testing.T) {
	selfID := "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
	dockerSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/_ping":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("OK"))
		case r.Method == http.MethodGet && r.URL.Path == "/containers/"+selfID+"/json":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"Id":     selfID,
				"Name":   "/melokeep",
				"Image":  "sha256:same-sha",
				"Config": map[string]any{"Image": "ghcr.io/agonie0v0/melokeep:latest"},
			})
		case r.Method == http.MethodPost && r.URL.Path == "/images/create":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"Pull complete","id":"l1"}` + "\n"))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/images/"):
			_ = json.NewEncoder(w).Encode(map[string]any{"Id": "sha256:same-sha"})
		default:
			http.NotFound(w, r)
		}
	}))
	defer dockerSrv.Close()

	t.Setenv("MELOKEEP_DOCKER_SOCKET", dockerSrv.URL)
	t.Setenv("MELOKEEP_CONTAINER_ID", selfID)

	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/repos/Agonie0v0/melokeep/releases/latest" {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"tag_name":     "v1.3.0",
				"name":         "v1.3.0",
				"body":         "Release with APK/IPA assets",
				"published_at": "2026-11-20T12:00:00Z",
				"html_url":     "https://github.com/Agonie0v0/melokeep/releases/tag/v1.3.0",
				"assets": []map[string]any{
					{
						"name":                 "MeloKeep-android-release.apk",
						"browser_download_url": "https://github.com/Agonie0v0/melokeep/releases/download/v1.3.0/MeloKeep-android-release.apk",
						"size":                 15857935,
						"content_type":         "application/vnd.android.package-archive",
					},
					{
						"name":                 "MeloKeep-ios.ipa",
						"browser_download_url": "https://github.com/Agonie0v0/melokeep/releases/download/v1.3.0/MeloKeep-ios.ipa",
						"size":                 24112233,
						"content_type":         "application/octet-stream",
					},
				},
			})
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer gh.Close()

	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.Transport = nil
		d.GitHubAPIBase = gh.URL
		d.BuildInfo = model.BuildInfoDTO{Version: "v1.2.0", InContainer: true}
	})

	// 1. GET /api/system/update includes latest.assets and self_update.
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/system/update status=%d %s", rec.Code, rec.Body.String())
	}
	var sysUpd model.SystemUpdateDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &sysUpd); err != nil {
		t.Fatalf("decode SystemUpdateDTO: %v", err)
	}
	if sysUpd.Latest == nil || len(sysUpd.Latest.Assets) != 2 || sysUpd.Latest.Assets[0].Name != "MeloKeep-android-release.apk" {
		t.Fatalf("expected 2 release assets parsed, got %+v", sysUpd.Latest)
	}
	if sysUpd.SelfUpdate == nil || !sysUpd.SelfUpdate.Supported || sysUpd.SelfUpdate.Mode != "docker_socket" {
		t.Fatalf("expected supported docker_socket self_update, got %+v", sysUpd.SelfUpdate)
	}

	// 2. GET /api/system/update/status returns SelfUpdateStatusDTO.
	recSt := httptest.NewRecorder()
	srv.ServeHTTP(recSt, httptest.NewRequest(http.MethodGet, "/api/system/update/status", nil))
	if recSt.Code != http.StatusOK {
		t.Fatalf("GET /api/system/update/status status=%d", recSt.Code)
	}

	// 3. POST /api/system/update/apply returns 202 Accepted.
	recApply := httptest.NewRecorder()
	reqApply := httptest.NewRequest(http.MethodPost, "/api/system/update/apply", nil)
	reqApply.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(recApply, reqApply)
	if recApply.Code != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted from POST /api/system/update/apply, got %d %s", recApply.Code, recApply.Body.String())
	}
}

func TestSystemUpdateTokenSaveClearRedactionAndCacheInvalidation(t *testing.T) {
	t.Setenv("MELOKEEP_GITHUB_TOKEN", "")
	t.Setenv("GITHUB_TOKEN", "")

	var mu sync.Mutex
	var lastAuth string
	var releaseLatestCalls, releasesListCalls atomic.Int32

	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		lastAuth = r.Header.Get("Authorization")
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/repos/Agonie0v0/melokeep/releases/latest":
			releaseLatestCalls.Add(1)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"tag_name":     "v1.4.0",
				"name":         "v1.4.0",
				"published_at": "2026-11-21T08:00:00Z",
				"html_url":     "https://github.com/Agonie0v0/melokeep/releases/tag/v1.4.0",
			})
		case "/repos/Agonie0v0/melokeep/releases":
			releasesListCalls.Add(1)
			_ = json.NewEncoder(w).Encode([]map[string]any{
				{
					"tag_name":     "v1.4.0",
					"name":         "v1.4.0",
					"published_at": "2026-11-21T08:00:00Z",
					"html_url":     "https://github.com/Agonie0v0/melokeep/releases/tag/v1.4.0",
					"draft":        false,
					"prerelease":   false,
					"assets": []map[string]any{
						{
							"name":                 "MeloKeep-v1.4.0-android.apk",
							"browser_download_url": "https://example.com/v1.4.0.apk",
							"size":                 123456,
						},
					},
				},
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer gh.Close()

	srv, st, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.Transport = nil
		d.GitHubAPIBase = gh.URL
		d.BuildInfo = model.BuildInfoDTO{Version: "v1.3.0"}
	})

	// 1. Initial settings read: unconfigured.
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/system/update/settings", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/system/update/settings status=%d %s", rec.Code, rec.Body.String())
	}
	var stDTO model.SystemUpdateSettingsDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &stDTO); err != nil {
		t.Fatalf("decode settings: %v", err)
	}
	if stDTO.GitHubTokenConfigured || stDTO.GitHubTokenSaved || stDTO.GitHubTokenSource != "" {
		t.Fatalf("expected unconfigured settings, got %+v", stDTO)
	}

	// 2. Populate both updateCache and appUpdateCache without token.
	recSys := httptest.NewRecorder()
	srv.ServeHTTP(recSys, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	var sysDTO model.SystemUpdateDTO
	_ = json.Unmarshal(recSys.Body.Bytes(), &sysDTO)
	if sysDTO.Cached || sysDTO.GitHubTokenConfigured || sysDTO.GitHubTokenSaved || sysDTO.GitHubTokenSource != "" {
		t.Fatalf("unexpected initial system update DTO: %+v", sysDTO)
	}

	recSysCached := httptest.NewRecorder()
	srv.ServeHTTP(recSysCached, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	var sysCached model.SystemUpdateDTO
	_ = json.Unmarshal(recSysCached.Body.Bytes(), &sysCached)
	if !sysCached.Cached || sysCached.GitHubTokenConfigured || sysCached.GitHubTokenSaved {
		t.Fatalf("expected cached system update DTO with token status, got %+v", sysCached)
	}

	recApp := httptest.NewRecorder()
	srv.ServeHTTP(recApp, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=android", nil))
	var appDTO model.AppUpdateCheckDTO
	_ = json.Unmarshal(recApp.Body.Bytes(), &appDTO)
	if appDTO.Cached || appDTO.GitHubTokenConfigured || appDTO.Release == nil {
		t.Fatalf("unexpected initial app update DTO: %+v", appDTO)
	}

	// 3. Save token via PUT /api/system/update/settings.
	const secretTok = "ghp_super_secret_token_98765"
	putBody, _ := json.Marshal(map[string]any{"github_token": "  " + secretTok + "  "})
	reqPut := httptest.NewRequest(http.MethodPut, "/api/system/update/settings", bytes.NewReader(putBody))
	reqPut.Header.Set("Content-Type", "application/json")
	reqPut.Header.Set("X-MeloKeep-Request", "1")
	recPut := httptest.NewRecorder()
	srv.ServeHTTP(recPut, reqPut)
	if recPut.Code != http.StatusOK {
		t.Fatalf("PUT settings status=%d %s", recPut.Code, recPut.Body.String())
	}
	if strings.Contains(recPut.Body.String(), secretTok) {
		t.Fatalf("PUT response leaked plaintext token: %s", recPut.Body.String())
	}
	var putDTO model.SystemUpdateSettingsDTO
	_ = json.Unmarshal(recPut.Body.Bytes(), &putDTO)
	if !putDTO.GitHubTokenConfigured || !putDTO.GitHubTokenSaved || putDTO.GitHubTokenSource != "settings" {
		t.Fatalf("unexpected PUT settings DTO: %+v", putDTO)
	}

	// Verify persisted record in store.
	recSetting, ok, err := st.GetSetting(context.Background(), store.NamespaceSystemUpdate)
	if err != nil || !ok || !strings.Contains(recSetting.BodyJSON, secretTok) {
		t.Fatalf("expected persisted token in store, ok=%v err=%v", ok, err)
	}

	// Verify GET settings and logs do NOT leak plaintext token.
	recRead2 := httptest.NewRecorder()
	srv.ServeHTTP(recRead2, httptest.NewRequest(http.MethodGet, "/api/system/update/settings", nil))
	if strings.Contains(recRead2.Body.String(), secretTok) {
		t.Fatalf("GET settings leaked plaintext token: %s", recRead2.Body.String())
	}
	for _, entry := range srv.deps.Logs.List() {
		if strings.Contains(entry.Message, secretTok) {
			t.Fatalf("log entry leaked plaintext token: %q", entry.Message)
		}
	}

	// 4. Both caches must be invalidated after PUT, and next requests must send Authorization header.
	prevLatest := releaseLatestCalls.Load()
	prevList := releasesListCalls.Load()

	recSysAfter := httptest.NewRecorder()
	srv.ServeHTTP(recSysAfter, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	var sysAfter model.SystemUpdateDTO
	_ = json.Unmarshal(recSysAfter.Body.Bytes(), &sysAfter)
	if sysAfter.Cached || releaseLatestCalls.Load() != prevLatest+1 {
		t.Fatalf("expected system update cache invalidated after token save, cached=%v calls=%d->%d", sysAfter.Cached, prevLatest, releaseLatestCalls.Load())
	}
	if !sysAfter.GitHubTokenConfigured || !sysAfter.GitHubTokenSaved || sysAfter.GitHubTokenSource != "settings" {
		t.Fatalf("expected token status populated after save, got %+v", sysAfter)
	}
	mu.Lock()
	gotAuth := lastAuth
	mu.Unlock()
	if gotAuth != "Bearer "+secretTok {
		t.Fatalf("expected Bearer token sent to GitHub, got %q", gotAuth)
	}

	recAppAfter := httptest.NewRecorder()
	srv.ServeHTTP(recAppAfter, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=android", nil))
	var appAfter model.AppUpdateCheckDTO
	_ = json.Unmarshal(recAppAfter.Body.Bytes(), &appAfter)
	if appAfter.Cached || releasesListCalls.Load() != prevList+1 {
		t.Fatalf("expected app update cache invalidated after token save, cached=%v calls=%d->%d", appAfter.Cached, prevList, releasesListCalls.Load())
	}
	if !appAfter.GitHubTokenConfigured || !appAfter.GitHubTokenSaved || appAfter.GitHubTokenSource != "settings" {
		t.Fatalf("expected app update token status populated, got %+v", appAfter)
	}

	// 5. Clear token and verify cache invalidation + empty Authorization header.
	codeClear, clearOut := jsonCall(t, srv, http.MethodPut, "/api/system/update/settings", map[string]any{"clear_github_token": true})
	if codeClear != http.StatusOK || clearOut["github_token_configured"] != false || clearOut["github_token_saved"] != false {
		t.Fatalf("expected cleared settings, got %d %v", codeClear, clearOut)
	}
	for _, entry := range srv.deps.Logs.List() {
		if strings.Contains(entry.Message, secretTok) {
			t.Fatalf("log entry leaked plaintext token after clear: %q", entry.Message)
		}
	}

	recSysCleared := httptest.NewRecorder()
	srv.ServeHTTP(recSysCleared, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	var sysCleared model.SystemUpdateDTO
	_ = json.Unmarshal(recSysCleared.Body.Bytes(), &sysCleared)
	if sysCleared.Cached || sysCleared.GitHubTokenConfigured || sysCleared.GitHubTokenSaved || sysCleared.GitHubTokenSource != "" {
		t.Fatalf("expected cache invalidated & unconfigured after clear, got %+v", sysCleared)
	}
	mu.Lock()
	gotAuthAfterClear := lastAuth
	mu.Unlock()
	if gotAuthAfterClear != "" {
		t.Fatalf("expected empty Authorization header after clear, got %q", gotAuthAfterClear)
	}
}

func TestSystemUpdateTokenEnvFallbackAndPrecedence(t *testing.T) {
	t.Setenv("MELOKEEP_GITHUB_TOKEN", "")
	t.Setenv("GITHUB_TOKEN", "")

	var mu sync.Mutex
	var authHeaders []string
	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		authHeaders = append(authHeaders, r.Header.Get("Authorization"))
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/repos/Agonie0v0/melokeep/releases/latest" {
			_ = json.NewEncoder(w).Encode(map[string]any{"tag_name": "v1.4.0"})
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer gh.Close()

	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.Transport = nil
		d.GitHubAPIBase = gh.URL
		d.BuildInfo = model.BuildInfoDTO{Version: "v1.3.0"}
	})

	// 1. GITHUB_TOKEN fallback.
	t.Setenv("GITHUB_TOKEN", "ghp_fallback_env")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/system/update?force=1", nil))
	var dto model.SystemUpdateDTO
	_ = json.Unmarshal(rec.Body.Bytes(), &dto)
	if !dto.GitHubTokenConfigured || dto.GitHubTokenSaved || dto.GitHubTokenSource != "env" {
		t.Fatalf("expected GITHUB_TOKEN env source, got %+v", dto)
	}

	// 2. MELOKEEP_GITHUB_TOKEN overrides GITHUB_TOKEN.
	t.Setenv("MELOKEEP_GITHUB_TOKEN", "ghp_melokeep_env")
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/api/system/update?force=1", nil))
	var dto2 model.SystemUpdateDTO
	_ = json.Unmarshal(rec2.Body.Bytes(), &dto2)
	if !dto2.GitHubTokenConfigured || dto2.GitHubTokenSaved || dto2.GitHubTokenSource != "env" {
		t.Fatalf("expected MELOKEEP_GITHUB_TOKEN env source, got %+v", dto2)
	}

	// 3. Saved settings token overrides both env vars.
	code, out := jsonCall(t, srv, http.MethodPut, "/api/system/update/settings", map[string]any{"github_token": "ghp_saved_override"})
	if code != http.StatusOK || out["github_token_source"] != "settings" || out["github_token_saved"] != true {
		t.Fatalf("expected settings override, got %d %v", code, out)
	}
	rec3 := httptest.NewRecorder()
	srv.ServeHTTP(rec3, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
	var dto3 model.SystemUpdateDTO
	_ = json.Unmarshal(rec3.Body.Bytes(), &dto3)
	if !dto3.GitHubTokenConfigured || !dto3.GitHubTokenSaved || dto3.GitHubTokenSource != "settings" {
		t.Fatalf("expected settings source on update check, got %+v", dto3)
	}

	// 4. Clearing settings falls back to MELOKEEP_GITHUB_TOKEN.
	codeClr, outClr := jsonCall(t, srv, http.MethodPut, "/api/system/update/settings", map[string]any{"clear_github_token": true})
	if codeClr != http.StatusOK || outClr["github_token_configured"] != true || outClr["github_token_saved"] != false || outClr["github_token_source"] != "env" {
		t.Fatalf("expected env fallback after clear, got %d %v", codeClr, outClr)
	}
	rec4 := httptest.NewRecorder()
	srv.ServeHTTP(rec4, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))

	mu.Lock()
	got := append([]string(nil), authHeaders...)
	mu.Unlock()
	want := []string{
		"Bearer ghp_fallback_env",
		"Bearer ghp_melokeep_env",
		"Bearer ghp_saved_override",
		"Bearer ghp_melokeep_env",
	}
	if len(got) != len(want) {
		t.Fatalf("authHeaders mismatch got=%v want=%v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("authHeader[%d] got=%q want=%q", i, got[i], want[i])
		}
	}
}

func TestSystemUpdateStatusErrorHintsAndNoCache(t *testing.T) {
	t.Setenv("MELOKEEP_GITHUB_TOKEN", "")
	t.Setenv("GITHUB_TOKEN", "")

	var statusCode atomic.Int32
	var calls atomic.Int32
	statusCode.Store(http.StatusUnauthorized)

	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(int(statusCode.Load()))
	}))
	defer gh.Close()

	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.Transport = nil
		d.GitHubAPIBase = gh.URL
	})

	// 1. Configure token and trigger 401 -> specific invalid/expired token hint, not cached.
	if code, _ := jsonCall(t, srv, http.MethodPut, "/api/system/update/settings", map[string]any{"github_token": "ghp_bad_token"}); code != http.StatusOK {
		t.Fatalf("save token failed: %d", code)
	}

	for _, path := range []string{"/api/system/update", "/api/system/update/app?platform=android"} {
		before := calls.Load()
		rec1 := httptest.NewRecorder()
		srv.ServeHTTP(rec1, httptest.NewRequest(http.MethodGet, path, nil))
		rec2 := httptest.NewRecorder()
		srv.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, path, nil))
		if rec1.Code != http.StatusOK || rec2.Code != http.StatusOK {
			t.Fatalf("%s expected 200 on upstream error, got %d / %d", path, rec1.Code, rec2.Code)
		}
		if calls.Load() != before+2 {
			t.Fatalf("%s expected error not cached, calls=%d->%d", path, before, calls.Load())
		}
		var body map[string]any
		_ = json.Unmarshal(rec1.Body.Bytes(), &body)
		errMsg, _ := body["error"].(string)
		if !strings.Contains(errMsg, "GitHub Token 无效或已过期（HTTP 401）") {
			t.Fatalf("%s expected 401 token error message, got %q", path, errMsg)
		}
		if body["github_token_configured"] != true || body["github_token_saved"] != true || body["github_token_source"] != "settings" {
			t.Fatalf("%s expected token status on error response, got %v", path, body)
		}
	}

	// 2. 403 / 429 WITH token -> permission/retry hint (not asking to configure token).
	for _, sc := range []int32{http.StatusForbidden, http.StatusTooManyRequests} {
		statusCode.Store(sc)
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/system/update", nil))
		var sysErr model.SystemUpdateDTO
		_ = json.Unmarshal(rec.Body.Bytes(), &sysErr)
		if !strings.Contains(sysErr.Error, "权限") || strings.Contains(sysErr.Error, "建议配置 GitHub Token") {
			t.Fatalf("HTTP %d with token expected permission/retry hint, got %q", sc, sysErr.Error)
		}
	}

	// 3. 403 / 429 WITHOUT token -> suggest configuring GitHub Token or proxy.
	if code, _ := jsonCall(t, srv, http.MethodPut, "/api/system/update/settings", map[string]any{"clear_github_token": true}); code != http.StatusOK {
		t.Fatalf("clear token failed: %d", code)
	}
	for _, sc := range []int32{http.StatusForbidden, http.StatusTooManyRequests} {
		statusCode.Store(sc)
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=ios", nil))
		var appErr model.AppUpdateCheckDTO
		_ = json.Unmarshal(rec.Body.Bytes(), &appErr)
		if !strings.Contains(appErr.Error, "建议配置 GitHub Token") {
			t.Fatalf("HTTP %d without token expected configure token hint, got %q", sc, appErr.Error)
		}
	}
}

func TestSystemUpdateSettingsPermissionsAndValidation(t *testing.T) {
	t.Setenv("MELOKEEP_GITHUB_TOKEN", "")
	t.Setenv("GITHUB_TOKEN", "")

	// 1. Validation 400 checks.
	srv, _, _ := newTestServer(t, nil)
	invalidBodies := []any{
		map[string]any{},
		map[string]any{"github_token": "   "},
		map[string]any{"github_token": strings.Repeat("a", 513)},
		map[string]any{"github_token": "ghp_bad token"},
		map[string]any{"github_token": "ghp_bad\ntoken"},
		map[string]any{"github_token": "ghp_bad\x7ftoken"},
	}
	for i, body := range invalidBodies {
		if code, _ := jsonCall(t, srv, http.MethodPut, "/api/system/update/settings", body); code != http.StatusBadRequest {
			t.Fatalf("case %d expected 400, got %d (body=%v)", i, code, body)
		}
	}
	if code, _ := jsonCall(t, srv, http.MethodPut, "/api/system/update/settings", map[string]any{"github_token": strings.Repeat("a", 512)}); code != http.StatusOK {
		t.Fatalf("expected 512-char token to pass validation, got %d", code)
	}

	// Invalid platform query on /api/system/update/app -> 400.
	for _, badPath := range []string{"/api/system/update/app", "/api/system/update/app?platform=windows"} {
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, badPath, nil))
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("%s expected 400, got %d %s", badPath, rec.Code, rec.Body.String())
		}
	}

	// 2. ReadOnly mode allows GET settings (200) and blocks PUT settings (403).
	roSrv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.ReadOnly = true
	})
	if code, _ := jsonCall(t, roSrv, http.MethodGet, "/api/system/update/settings", nil); code != http.StatusOK {
		t.Fatalf("ReadOnly GET /api/system/update/settings expected 200, got %d", code)
	}
	if code, _ := jsonCall(t, roSrv, http.MethodPut, "/api/system/update/settings", map[string]any{"github_token": "ghp_abc"}); code != http.StatusForbidden {
		t.Fatalf("ReadOnly PUT /api/system/update/settings expected 403, got %d", code)
	}

	// 3. Actor permission checks: unauthenticated, non-admin, ReadOnlyAI -> 403.
	callSettings := func(method string, body []byte, actor auth.Actor) int {
		var rd *bytes.Reader
		if body != nil {
			rd = bytes.NewReader(body)
		} else {
			rd = bytes.NewReader(nil)
		}
		req := httptest.NewRequest(method, "/api/system/update/settings", rd)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		req = req.WithContext(withActor(req.Context(), actor))
		rec := httptest.NewRecorder()
		if method == http.MethodGet {
			srv.handleSystemUpdateSettingsRead(rec, req)
		} else {
			srv.handleSystemUpdateSettingsWrite(rec, req)
		}
		return rec.Code
	}
	validPut := []byte(`{"github_token":"ghp_valid_123"}`)
	roAI := auth.WebAdminActor("admin")
	roAI.ReadOnlyAI = true
	deniedActors := []auth.Actor{
		auth.UnauthenticatedActor(),
		auth.ChannelActor("telegram", "u1", auth.PermRead, auth.PermLocalWrite),
		roAI,
	}
	for i, actor := range deniedActors {
		if code := callSettings(http.MethodGet, nil, actor); code != http.StatusForbidden {
			t.Fatalf("actor[%d] GET expected 403, got %d", i, code)
		}
		if code := callSettings(http.MethodPut, validPut, actor); code != http.StatusForbidden {
			t.Fatalf("actor[%d] PUT expected 403, got %d", i, code)
		}
	}

	// 4. AuthEnabled=true rejects unauthenticated HTTP callers with 401.
	authSrv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.AuthEnabled = true
		d.AuthUserDigest = sha256.Sum256([]byte("admin"))
		d.AuthPassDigest = sha256.Sum256([]byte("pass"))
	})
	rec401 := httptest.NewRecorder()
	authSrv.ServeHTTP(rec401, httptest.NewRequest(http.MethodGet, "/api/system/update/settings", nil))
	if rec401.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 without session, got %d", rec401.Code)
	}
}

func TestSystemUpdateAppPaginationSemverAndCache(t *testing.T) {
	t.Setenv("MELOKEEP_GITHUB_TOKEN", "")
	t.Setenv("GITHUB_TOKEN", "")

	var mu sync.Mutex
	var requestedPages []string
	var mode string = "paged"

	// Build a >1.2 MiB payload for page 1 so we also verify the 4MiB response limit via Transport pool.
	largePadding := strings.Repeat("x", 1300*1024)

	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/repos/Agonie0v0/melokeep/releases" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if r.URL.Query().Get("per_page") != "100" {
			t.Errorf("expected per_page=100, got %q", r.URL.Query().Get("per_page"))
		}
		page := r.URL.Query().Get("page")
		mu.Lock()
		requestedPages = append(requestedPages, page)
		curMode := mode
		mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		if curMode == "none" {
			_ = json.NewEncoder(w).Encode([]map[string]any{
				{
					"tag_name":   "v1.0.20",
					"draft":      false,
					"prerelease": false,
					"assets": []map[string]any{
						{"name": "melokeep-web.tar.gz", "browser_download_url": "https://example.com/web.tar.gz"},
					},
				},
			})
			return
		}

		switch page {
		case "1":
			items := make([]map[string]any, 0, 100)
			items = append(items,
				map[string]any{
					"tag_name":   "v1.5.0",
					"name":       "Web 1.5.0",
					"body":       largePadding,
					"draft":      false,
					"prerelease": false,
					"assets": []map[string]any{
						{"name": "melokeep-web.tar.gz", "browser_download_url": "https://example.com/web.tar.gz"},
					},
				},
				map[string]any{
					"tag_name":   "v1.4.0",
					"draft":      true,
					"prerelease": false,
					"assets": []map[string]any{
						{"name": "MeloKeep-v1.4.0.apk", "browser_download_url": "https://example.com/v1.4.0.apk"},
					},
				},
				map[string]any{
					"tag_name":   "v1.3.0-rc.1",
					"draft":      false,
					"prerelease": true,
					"assets": []map[string]any{
						{"name": "MeloKeep-v1.3.0-rc.1.apk", "browser_download_url": "https://example.com/v1.3.0-rc.1.apk"},
					},
				},
				map[string]any{
					"tag_name":   "v1.2.5",
					"draft":      false,
					"prerelease": false,
					"assets": []map[string]any{
						{"name": "MeloKeep-v1.2.5.apk", "browser_download_url": "   "},
					},
				},
			)
			for len(items) < 100 {
				items = append(items, map[string]any{
					"tag_name":   fmt.Sprintf("v1.1.%d", 100-len(items)),
					"draft":      false,
					"prerelease": false,
					"assets": []map[string]any{
						{"name": "melokeep-web.tar.gz", "browser_download_url": "https://example.com/web.tar.gz"},
					},
				})
			}
			_ = json.NewEncoder(w).Encode(items)
		case "2":
			_ = json.NewEncoder(w).Encode([]map[string]any{
				{
					"tag_name":   "v1.0.9",
					"name":       "v1.0.9",
					"draft":      false,
					"prerelease": false,
					"assets": []map[string]any{
						{"name": "MeloKeep-v1.0.9.apk", "browser_download_url": "https://example.com/v1.0.9.apk", "size": 1000},
					},
				},
				{
					"tag_name":     "v1.0.13",
					"name":         "Mobile v1.0.13",
					"html_url":     "https://github.com/Agonie0v0/melokeep/releases/tag/v1.0.13",
					"body":         "Release 1.0.13",
					"published_at": "2026-11-22T00:00:00Z",
					"draft":        false,
					"prerelease":   false,
					"assets": []map[string]any{
						{
							"name":                 "MeloKeep-v1.0.13-android.apk",
							"browser_download_url": "https://example.com/v1.0.13.apk",
							"size":                 15000000,
							"content_type":         "application/vnd.android.package-archive",
						},
						{
							"name":                 "MeloKeep-v1.0.13-ios.ipa",
							"browser_download_url": "https://example.com/v1.0.13.ipa",
							"size":                 22000000,
							"content_type":         "application/octet-stream",
						},
					},
				},
				{
					"tag_name":   "v1.0.10",
					"name":       "v1.0.10",
					"draft":      false,
					"prerelease": false,
					"assets": []map[string]any{
						{"name": "MeloKeep-v1.0.10.apk", "browser_download_url": "https://example.com/v1.0.10.apk", "size": 1100},
					},
				},
			})
		case "3":
			_ = json.NewEncoder(w).Encode([]map[string]any{
				{
					"tag_name":   "v1.0.8",
					"draft":      false,
					"prerelease": false,
					"assets": []map[string]any{
						{"name": "MeloKeep-v1.0.8.ipa", "browser_download_url": "https://example.com/v1.0.8.ipa"},
					},
				},
			})
		default:
			_ = json.NewEncoder(w).Encode([]map[string]any{})
		}
	}))
	defer gh.Close()

	// Keep default Transport (WithAllowTestLoopback=true) to exercise 4MiB MaxResponseBytes!
	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.GitHubAPIBase = gh.URL
	})

	// 1. Android fetches page 1 -> page 2 (picks v1.0.13 over v1.0.9/v1.0.10; page 1..2 had <100 items on page 2 so loop exits before page 3).
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=ANDROID", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("android update status=%d %s", rec.Code, rec.Body.String())
	}
	var androidDTO model.AppUpdateCheckDTO
	if err := json.Unmarshal(rec.Body.Bytes(), &androidDTO); err != nil {
		t.Fatalf("decode android DTO: %v", err)
	}
	if androidDTO.Platform != "android" || androidDTO.Cached || androidDTO.Error != "" {
		t.Fatalf("unexpected android DTO: %+v", androidDTO)
	}
	if androidDTO.Release == nil || androidDTO.Release.TagName != "v1.0.13" || len(androidDTO.Release.Assets) != 2 {
		t.Fatalf("expected v1.0.13 release with 2 assets, got %+v", androidDTO.Release)
	}
	mu.Lock()
	gotPages := fmt.Sprintf("%v", requestedPages)
	requestedPages = nil
	mu.Unlock()
	if gotPages != "[1 2]" {
		t.Fatalf("expected pages [1 2], got %s", gotPages)
	}

	// 2. Android cache hit.
	recCached := httptest.NewRecorder()
	srv.ServeHTTP(recCached, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=android", nil))
	var androidCached model.AppUpdateCheckDTO
	_ = json.Unmarshal(recCached.Body.Bytes(), &androidCached)
	if !androidCached.Cached || androidCached.Release == nil || androidCached.Release.TagName != "v1.0.13" {
		t.Fatalf("expected android cache hit, got %+v", androidCached)
	}
	mu.Lock()
	if len(requestedPages) != 0 {
		t.Fatalf("expected no GitHub calls on cache hit, got %v", requestedPages)
	}
	mu.Unlock()

	// 3. iOS uses its own cache key, fetches fresh, then hits cache on second call.
	recIOS := httptest.NewRecorder()
	srv.ServeHTTP(recIOS, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=ios", nil))
	var iosDTO model.AppUpdateCheckDTO
	_ = json.Unmarshal(recIOS.Body.Bytes(), &iosDTO)
	if iosDTO.Platform != "ios" || iosDTO.Cached || iosDTO.Release == nil || iosDTO.Release.TagName != "v1.0.13" {
		t.Fatalf("unexpected ios DTO: %+v", iosDTO)
	}
	recIOSCached := httptest.NewRecorder()
	srv.ServeHTTP(recIOSCached, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=ios", nil))
	var iosCached model.AppUpdateCheckDTO
	_ = json.Unmarshal(recIOSCached.Body.Bytes(), &iosCached)
	if !iosCached.Cached {
		t.Fatalf("expected ios cache hit")
	}

	// 4. force=true bypasses cache; when page 1 returns <100 items with no match, loop stops early instead of needlessly fetching pages 2..3.
	mu.Lock()
	mode = "none"
	requestedPages = nil
	mu.Unlock()

	recForce := httptest.NewRecorder()
	srv.ServeHTTP(recForce, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=android&force=true", nil))
	var noneDTO model.AppUpdateCheckDTO
	_ = json.Unmarshal(recForce.Body.Bytes(), &noneDTO)
	if noneDTO.Cached || noneDTO.Error != "" || noneDTO.Release != nil {
		t.Fatalf("expected nil release without error, got %+v", noneDTO)
	}
	mu.Lock()
	pagesNone := fmt.Sprintf("%v", requestedPages)
	requestedPages = nil
	mu.Unlock()
	if pagesNone != "[1]" {
		t.Fatalf("expected early stop on partial page 1 [1], got %s", pagesNone)
	}

	recNoneCached := httptest.NewRecorder()
	srv.ServeHTTP(recNoneCached, httptest.NewRequest(http.MethodGet, "/api/system/update/app?platform=android", nil))
	var noneCached model.AppUpdateCheckDTO
	_ = json.Unmarshal(recNoneCached.Body.Bytes(), &noneCached)
	if !noneCached.Cached || noneCached.Release != nil {
		t.Fatalf("expected nil release result to be cached, got %+v", noneCached)
	}
}
