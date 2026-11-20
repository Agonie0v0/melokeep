package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/agonie0v0/melokeep/internal/model"
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
