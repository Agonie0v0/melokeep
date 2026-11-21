package navidrome

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

func newTestStore(t *testing.T) *store.Store {
	t.Helper()
	st, err := store.Open(context.Background(), store.Options{
		Path: filepath.Join(t.TempDir(), "melokeep.db"),
	})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func TestNavidromeCRUDScanStreamAndDeletionBoundary(t *testing.T) {
	var deletedCount atomic.Int32
	var lastAuthParams atomic.Value
	playlistSongs := []map[string]any{
		{"id": "s1", "title": "晨风序曲", "artist": "独立乐手", "album": "晨风集", "duration": 223.0, "suffix": "flac", "bitRate": 900, "coverArt": "c1", "size": 31457280},
	}
	changed := "2026-11-14T10:00:00Z"

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		lastAuthParams.Store(r.Form.Get("u") + ":" + r.Form.Get("t") + ":" + r.Form.Get("apiKey"))
		ep := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/rest/"), ".view")
		w.Header().Set("Content-Type", "application/json")
		switch ep {
		case "ping":
			_ = json.NewEncoder(w).Encode(map[string]any{"subsonic-response": map[string]any{"status": "ok", "serverVersion": "0.53.3"}})
		case "startScan", "getScanStatus":
			_ = json.NewEncoder(w).Encode(map[string]any{"subsonic-response": map[string]any{"status": "ok", "scanStatus": map[string]any{"scanning": ep == "startScan", "count": 42}}})
		case "getPlaylists":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status": "ok",
					"playlists": map[string]any{
						"playlist": []map[string]any{
							{"id": "pl1", "name": "我的歌单", "owner": "alice", "songCount": len(playlistSongs), "created": "2026-11-14T09:00:00Z"},
							{"id": "pl_other", "name": "他人歌单", "owner": "bob", "songCount": 1, "created": "2026-11-14T09:00:00Z"},
						},
					},
				},
			})
		case "getPlaylist":
			id := r.Form.Get("id")
			owner := "alice"
			if id == "pl_other" {
				owner = "bob"
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status": "ok",
					"playlist": map[string]any{
						"id": id, "name": "我的歌单", "owner": owner, "changed": changed, "songCount": len(playlistSongs),
						"entry": playlistSongs,
					},
				},
			})
		case "createPlaylist":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status":   "ok",
					"playlist": map[string]any{"id": "pl1", "name": r.Form.Get("name"), "owner": "alice"},
				},
			})
		case "updatePlaylist":
			if len(r.Form["songIndexToRemove"]) > 0 {
				playlistSongs = nil
				changed = "2026-11-14T10:05:00Z"
			} else if len(r.Form["songIdToAdd"]) > 0 {
				playlistSongs = append(playlistSongs, map[string]any{"id": "s2", "title": "星海回响", "artist": "独立乐手", "duration": 226.0, "suffix": "mp3"})
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"subsonic-response": map[string]any{"status": "ok"}})
		case "deletePlaylist":
			deletedCount.Add(1)
			_ = json.NewEncoder(w).Encode(map[string]any{"subsonic-response": map[string]any{"status": "ok"}})
		case "getArtists":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status": "ok",
					"artists": map[string]any{
						"index": []map[string]any{{"artist": []map[string]any{{"id": "ar1", "name": "独立乐手", "albumCount": 10, "coverArt": "ar-art-1"}}}},
					},
				},
			})
		case "getAlbum":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status": "ok",
					"album":  map[string]any{"id": "al1", "name": "晨风集", "artist": "独立乐手", "songCount": 1, "song": playlistSongs},
				},
			})
		case "search3":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status":        "ok",
					"searchResult3": map[string]any{"song": playlistSongs, "album": []map[string]any{{"id": "al1", "name": "晨风集", "artist": "独立乐手", "songCount": 1}}},
				},
			})
		case "getLyricsBySongId":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status": "ok",
					"lyricsList": map[string]any{
						"structuredLyrics": []map[string]any{{
							"synced": true,
							"offset": 500,
							"line":   []map[string]any{{"start": 2500, "value": "晨风轻轻吹过晴朗的早晨"}},
						}},
					},
				},
			})
		case "stream":
			w.Header().Set("Content-Type", "audio/flac")
			w.Header().Set("Content-Range", "bytes 0-3/10")
			w.Header().Set("Accept-Ranges", "bytes")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write([]byte("fLaC"))
		default:
			_ = json.NewEncoder(w).Encode(map[string]any{"subsonic-response": map[string]any{"status": "failed", "error": map[string]any{"code": 70}}})
		}
	}))
	defer srv.Close()

	st := newTestStore(t)
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	var remembered []string
	client, err := NewClient(pool, st, Settings{}, func(_ context.Context, tr model.TrackDTO) {
		remembered = append(remembered, tr.ID)
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}

	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	pub, err := client.UpdateSettings(ctx, admin, SettingsUpdate{
		Enabled:       true,
		URL:           srv.URL,
		Username:      "alice",
		Password:      "super-secret-pass",
		AutoScan:      true,
		DeleteEnabled: false,
	})
	if err != nil {
		t.Fatalf("UpdateSettings: %v", err)
	}
	if !pub.HasPassword || pub.HasAPIKey {
		t.Fatalf("unexpected public dto flags: %+v", pub)
	}
	pubJSON, _ := json.Marshal(pub)
	if strings.Contains(string(pubJSON), "super-secret-pass") {
		t.Fatalf("secret leaked in public DTO: %s", string(pubJSON))
	}

	// Merge with HiddenPlaceholder preserves password.
	_, err = client.UpdateSettings(ctx, admin, SettingsUpdate{
		Enabled:       true,
		URL:           srv.URL,
		Username:      "alice",
		Password:      config.HiddenPlaceholder,
		DeleteEnabled: false,
	})
	if err != nil {
		t.Fatalf("UpdateSettings hidden merge: %v", err)
	}
	if client.Settings().Password != "super-secret-pass" {
		t.Fatalf("password lost after hidden merge")
	}

	// Reload from store into a new client.
	reloaded, err := NewClient(pool, st, Settings{}, nil)
	if err != nil {
		t.Fatalf("NewClient reload: %v", err)
	}
	loaded, err := reloaded.LoadSettings(ctx)
	if err != nil || loaded.Password != "super-secret-pass" {
		t.Fatalf("LoadSettings failed: %v", err)
	}

	ping, err := client.Ping(ctx)
	if err != nil || !ping.OK || ping.Version != "0.53.3" {
		t.Fatalf("Ping failed: %+v %v", ping, err)
	}
	if scan, err := client.StartScan(ctx, admin); err != nil || !scan.Scanning {
		t.Fatalf("StartScan failed: %+v %v", scan, err)
	}
	artists, err := client.GetArtists(ctx)
	if err != nil || len(artists) != 1 {
		t.Fatalf("GetArtists failed: %+v %v", artists, err)
	}
	if artists[0].Cover == nil || *artists[0].Cover != "/api/navidrome/cover/ar-art-1" {
		t.Fatalf("unexpected GetArtists Cover: %#v", artists[0].Cover)
	}
	if artists[0].AvatarURL != "/api/artists/avatar?name=%E7%8B%AC%E7%AB%8B%E4%B9%90%E6%89%8B" {
		t.Fatalf("unexpected GetArtists AvatarURL: %q", artists[0].AvatarURL)
	}
	if alb, err := client.GetAlbum(ctx, "al1"); err != nil || len(alb.Tracks) != 1 {
		t.Fatalf("GetAlbum failed: %+v %v", alb, err)
	}
	if sr, err := client.Search(ctx, "晨风序曲", 0); err != nil || len(sr.Tracks) != 1 || len(remembered) == 0 {
		t.Fatalf("Search failed: %+v %v", sr, err)
	}
	if lrc := client.GetLyrics(ctx, "s1", "独立乐手", "晨风序曲"); lrc != "[00:02.000]晨风轻轻吹过晴朗的早晨" {
		t.Fatalf("unexpected synced lyrics: %q", lrc)
	}

	// Deletion boundary: DeleteEnabled=false must block deletion even for admin.
	if err := client.DeletePlaylist(ctx, admin, "pl1"); err == nil {
		t.Fatalf("expected delete blocked when DeleteEnabled=false")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusForbidden {
		t.Fatalf("expected 403, got %v", err)
	}

	// Enable delete, but read-only AI and non-owner playlist must still be 403.
	_, _ = client.UpdateSettings(ctx, admin, SettingsUpdate{
		Enabled:       true,
		URL:           srv.URL,
		Username:      "alice",
		Password:      config.HiddenPlaceholder,
		DeleteEnabled: true,
	})
	roActor := auth.WebAdminActor("admin")
	roActor.ReadOnlyAI = true
	if err := client.DeletePlaylist(ctx, roActor, "pl1"); err == nil {
		t.Fatalf("expected read-only AI delete blocked")
	}
	if err := client.DeletePlaylist(ctx, admin, "pl_other"); err == nil {
		t.Fatalf("expected non-owner delete blocked")
	}
	if deletedCount.Load() != 0 {
		t.Fatalf("server deletePlaylist should not have been called yet")
	}

	// Playlist CRUD + revision conflict check.
	pl, err := client.GetPlaylist(ctx, "pl1")
	if err != nil {
		t.Fatalf("GetPlaylist: %v", err)
	}
	if _, err := client.RemovePlaylistTracks(ctx, admin, "pl1", []int{0}, strings.Repeat("0", 64)); err == nil {
		t.Fatalf("expected 409 conflict on wrong playlist revision")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409, got %v", err)
	}
	if _, err := client.RemovePlaylistTracks(ctx, admin, "pl1", []int{0}, pl.Revision); err != nil {
		t.Fatalf("RemovePlaylistTracks failed: %v", err)
	}
	if err := client.DeletePlaylist(ctx, admin, "pl1"); err != nil {
		t.Fatalf("DeletePlaylist failed: %v", err)
	}
	if deletedCount.Load() != 1 {
		t.Fatalf("expected 1 server deletePlaylist call, got %d", deletedCount.Load())
	}

	// Range streaming proxy.
	ms, err := client.OpenMedia(ctx, "stream", "s1", "bytes=0-3")
	if err != nil {
		t.Fatalf("OpenMedia: %v", err)
	}
	rawStream, _ := io.ReadAll(ms.Body)
	_ = ms.Body.Close()
	if ms.StatusCode != http.StatusPartialContent || string(rawStream) != "fLaC" {
		t.Fatalf("unexpected stream response: status=%d body=%q", ms.StatusCode, string(rawStream))
	}
}

func TestNavidromeUnreachableAndURLValidation(t *testing.T) {
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	if _, err := NewClient(pool, nil, Settings{Enabled: true, URL: "http://user:pass@127.0.0.1:4533"}, nil); err == nil {
		t.Fatalf("expected invalid URL with userinfo rejected")
	}
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	closedURL := srv.URL
	srv.Close()

	c, err := NewClient(pool, nil, Settings{Enabled: true, URL: closedURL, Username: "u", APIKey: "secret-key-123"}, nil)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	_, err = c.Ping(context.Background())
	if err == nil {
		t.Fatalf("expected unreachable error")
	}
	de, ok := model.AsDomainError(err)
	if !ok || de.HTTPStatus() != http.StatusServiceUnavailable {
		t.Fatalf("expected 503 Unavailable, got %v", err)
	}
	if strings.Contains(c.RedactError(err), "secret-key-123") {
		t.Fatalf("secret leaked in redacted error")
	}
}

func TestNormalizeAndMergeSettingsPreservesAndClearsSecrets(t *testing.T) {
	existing := Settings{
		Enabled:  true,
		URL:      "https://nav.example.com",
		Username: "alice",
		Password: "saved-password",
		APIKey:   "saved-api-key",
	}

	// Omitted and null secret fields must preserve existing credentials.
	upd := SettingsUpdate{
		Enabled:  true,
		URL:      "https://nav.example.com",
		Username: "alice",
	}
	NormalizeSettingsUpdate(map[string]any{
		"enabled":  true,
		"url":      "https://nav.example.com",
		"username": "alice",
		"password": nil,
	}, &upd)
	merged, err := MergeSettings(existing, upd)
	if err != nil {
		t.Fatalf("MergeSettings null/omitted failed: %v", err)
	}
	if merged.Password != "saved-password" || merged.APIKey != "saved-api-key" {
		t.Fatalf("expected existing secrets preserved, got pass=%q key=%q", merged.Password, merged.APIKey)
	}

	// ClearPassword with new password must keep the new value; with empty must clear.
	updNewPass := SettingsUpdate{
		Enabled:       true,
		URL:           "https://nav.example.com",
		Username:      "alice",
		Password:      "brand-new-pass",
		ClearPassword: true,
		ClearAPIKey:   true,
	}
	merged, err = MergeSettings(existing, updNewPass)
	if err != nil {
		t.Fatalf("MergeSettings replace+clear failed: %v", err)
	}
	if merged.Password != "brand-new-pass" || merged.APIKey != "" {
		t.Fatalf("expected new password preserved and api_key cleared, got pass=%q key=%q", merged.Password, merged.APIKey)
	}

	// Explicit empty password clears stored password.
	updEmpty := SettingsUpdate{
		Enabled:  true,
		URL:      "https://nav.example.com",
		Username: "alice",
		Password: "",
	}
	NormalizeSettingsUpdate(map[string]any{
		"enabled":  true,
		"url":      "https://nav.example.com",
		"username": "alice",
		"password": "",
	}, &updEmpty)
	merged, err = MergeSettings(existing, updEmpty)
	if err != nil {
		t.Fatalf("MergeSettings empty clear failed: %v", err)
	}
	if merged.Password != "" || merged.APIKey != "saved-api-key" {
		t.Fatalf("expected password cleared and omitted api_key preserved, got pass=%q key=%q", merged.Password, merged.APIKey)
	}
}

func TestOpenMediaDownloadFallbackAndJSONRejection(t *testing.T) {
	var sawDownloadFormatRaw atomic.Bool
	var sawStreamFormatRaw atomic.Bool
	var downloadCalls atomic.Int32
	var streamCalls atomic.Int32

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		switch r.URL.Path {
		case "/rest/download.view":
			downloadCalls.Add(1)
			if r.URL.Query().Get("format") != "" {
				sawDownloadFormatRaw.Store(true)
			}
			if id == "orig-1" {
				w.Header().Set("Content-Type", "audio/flac")
				w.Header().Set("Content-Disposition", `attachment; filename="Morning.flac"`)
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte("fLaC-ORIGINAL"))
				return
			}
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"subsonic-response":{"status":"failed","error":{"code":70,"message":"download disabled"}}}`))
		case "/rest/stream.view":
			streamCalls.Add(1)
			if r.URL.Query().Get("format") == "raw" {
				sawStreamFormatRaw.Store(true)
			}
			if id == "fallback-1" {
				w.Header().Set("Content-Type", "audio/mpeg")
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte("ID3-FALLBACK"))
				return
			}
			w.Header().Set("Content-Type", "application/xml; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`<subsonic-response status="failed"/>`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()
	client, err := NewClient(pool, nil, Settings{
		Enabled:  true,
		URL:      srv.URL,
		Username: "alice",
		APIKey:   "k-1",
	}, nil)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	ctx := context.Background()

	// 1. Direct download.view succeeds without forcing format=raw and sets nosniff + Content-Disposition.
	ms, err := client.OpenMedia(ctx, "download", "orig-1", "")
	if err != nil {
		t.Fatalf("OpenMedia download failed: %v", err)
	}
	raw, _ := io.ReadAll(ms.Body)
	_ = ms.Body.Close()
	if string(raw) != "fLaC-ORIGINAL" {
		t.Fatalf("unexpected download body: %q", string(raw))
	}
	if sawDownloadFormatRaw.Load() {
		t.Fatalf("download endpoint should not force format=raw")
	}
	if got := ms.Headers.Get("Content-Disposition"); !strings.Contains(got, "Morning.flac") {
		t.Fatalf("expected Content-Disposition preserved, got %q", got)
	}
	if ms.Headers.Get("X-Content-Type-Options") != "nosniff" {
		t.Fatalf("expected nosniff header, got %q", ms.Headers.Get("X-Content-Type-Options"))
	}

	// 2. download.view JSON error is intercepted and falls back to stream?format=raw.
	msFB, err := client.OpenMedia(ctx, "download", "fallback-1", "")
	if err != nil {
		t.Fatalf("OpenMedia fallback failed: %v", err)
	}
	rawFB, _ := io.ReadAll(msFB.Body)
	_ = msFB.Body.Close()
	if string(rawFB) != "ID3-FALLBACK" || !sawStreamFormatRaw.Load() || streamCalls.Load() != 1 {
		t.Fatalf("expected stream?format=raw fallback, got body=%q sawRaw=%v calls=%d", string(rawFB), sawStreamFormatRaw.Load(), streamCalls.Load())
	}

	// 3. When both download and stream return JSON/XML, ErrInvalidResponse is returned.
	if _, err := client.OpenMedia(ctx, "download", "both-bad", ""); err == nil {
		t.Fatalf("expected error when both download and stream return XML/JSON")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrInvalidResponse {
		t.Fatalf("expected ErrInvalidResponse, got %v", err)
	}
	if downloadCalls.Load() < 3 {
		t.Fatalf("expected download endpoint called for each test case")
	}
}

func TestNavidromeArtistCoverAndAvatarURL(t *testing.T) {
	ctx := context.Background()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/rest/"), ".view") {
		case "getArtists":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status": "ok",
					"artists": map[string]any{
						"index": []map[string]any{
							{
								"artist": []map[string]any{
									{
										"id":             "ar-cover",
										"name":           "陈绮贞",
										"albumCount":     6,
										"coverArt":       "ar-ar-cover",
										"artistImageUrl": "https://img.example.com/ignored.jpg",
									},
									{
										"id":             "ar-placeholder",
										"name":           "A / B",
										"albumCount":     2,
										"artistImageUrl": "https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png",
									},
									{
										"id":             "ar-imgurl",
										"name":           "Daft Punk",
										"albumCount":     4,
										"artistImageUrl": "https://cdn.example.com/daft.jpg",
									},
								},
							},
						},
					},
				},
			})
		case "getArtist":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status": "ok",
					"artist": map[string]any{
						"id":         "ar-cover",
						"name":       "陈绮贞",
						"coverArt":   "ar-ar-cover",
						"albumCount": 1,
						"album": []map[string]any{
							{"id": "al-1", "name": "华丽的冒险", "artist": "陈绮贞", "songCount": 10},
						},
					},
				},
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	st := newTestStore(t)
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()

	client, err := NewClient(pool, st, Settings{}, nil)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	if _, err := client.UpdateSettings(ctx, auth.WebAdminActor("admin"), SettingsUpdate{
		Enabled:  true,
		URL:      srv.URL,
		Username: "alice",
		Password: "secret",
	}); err != nil {
		t.Fatalf("UpdateSettings: %v", err)
	}

	artists, err := client.GetArtists(ctx)
	if err != nil || len(artists) != 3 {
		t.Fatalf("GetArtists failed: %+v %v", artists, err)
	}
	if artists[0].Cover == nil || *artists[0].Cover != "/api/navidrome/cover/ar-ar-cover" {
		t.Fatalf("expected coverArt mapped Cover for artist 0, got %#v", artists[0].Cover)
	}
	if artists[0].AvatarURL != "/api/artists/avatar?name=%E9%99%88%E7%BB%AE%E8%B4%9E" {
		t.Fatalf("unexpected artist 0 avatar_url: %q", artists[0].AvatarURL)
	}
	if artists[1].Cover != nil {
		t.Fatalf("expected Last.fm placeholder artistImageUrl filtered out, got %q", *artists[1].Cover)
	}
	if artists[1].AvatarURL != "/api/artists/avatar?name=A+%2F+B" {
		t.Fatalf("unexpected artist 1 avatar_url: %q", artists[1].AvatarURL)
	}
	if artists[2].Cover == nil || *artists[2].Cover != "https://cdn.example.com/daft.jpg" {
		t.Fatalf("expected valid artistImageUrl preserved in Cover, got %#v", artists[2].Cover)
	}
	if artists[2].AvatarURL != "/api/artists/avatar?name=Daft+Punk" {
		t.Fatalf("unexpected artist 2 avatar_url: %q", artists[2].AvatarURL)
	}

	detail, err := client.GetArtist(ctx, "ar-cover")
	if err != nil {
		t.Fatalf("GetArtist failed: %v", err)
	}
	if detail.ID != "ar-cover" || detail.Name != "陈绮贞" || len(detail.Albums) != 1 {
		t.Fatalf("unexpected ArtistDetailDTO: %+v", detail)
	}
	if detail.Cover == nil || *detail.Cover != "/api/navidrome/cover/ar-ar-cover" {
		t.Fatalf("unexpected ArtistDetailDTO Cover: %#v", detail.Cover)
	}
	if detail.AvatarURL != "/api/artists/avatar?name=%E9%99%88%E7%BB%AE%E8%B4%9E" {
		t.Fatalf("unexpected ArtistDetailDTO AvatarURL: %q", detail.AvatarURL)
	}
}
