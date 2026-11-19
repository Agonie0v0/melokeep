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
