package integration

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/app"
	"github.com/agonie0v0/melokeep/internal/bot"
	"github.com/agonie0v0/melokeep/internal/legacyimport"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
	_ "modernc.org/sqlite"
)

func makeTinyMP3Bytes() []byte {
	// ID3v2.3 header (10 bytes, size 0) + MPEG1 Layer3 frame header + padding.
	buf := make([]byte, 128*1024)
	copy(buf[:10], []byte{'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})
	buf[10] = 0xFF
	buf[11] = 0xFB
	buf[12] = 0x90
	buf[13] = 0x00
	for i := 14; i < len(buf); i++ {
		buf[i] = byte(i % 251)
	}
	return buf
}

type apiClient struct {
	t       *testing.T
	baseURL string
	client  *http.Client
}

func (c *apiClient) do(method, path string, body any, extraHeaders map[string]string) *http.Response {
	c.t.Helper()
	var rdr io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			c.t.Fatalf("marshal %s %s: %v", method, path, err)
		}
		rdr = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, c.baseURL+path, rdr)
	if err != nil {
		c.t.Fatalf("new request %s %s: %v", method, path, err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range extraHeaders {
		req.Header.Set(k, v)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		c.t.Fatalf("do %s %s: %v", method, path, err)
	}
	return resp
}

func (c *apiClient) doJSON(method, path string, body any, csrf bool, wantStatus int, out any) []byte {
	c.t.Helper()
	hdr := map[string]string{}
	if csrf {
		hdr["X-MeloKeep-Request"] = "1"
	}
	resp := c.do(method, path, body, hdr)
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		c.t.Fatalf("read %s %s: %v", method, path, err)
	}
	if resp.StatusCode != wantStatus {
		c.t.Fatalf("%s %s status=%d want=%d body=%s", method, path, resp.StatusCode, wantStatus, string(raw))
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			c.t.Fatalf("unmarshal %s %s: %v body=%s", method, path, err, string(raw))
		}
	}
	return raw
}

func TestE2EClosedLoop(t *testing.T) {
	audioBytes := makeTinyMP3Bytes()
	var navScanCount atomic.Int64
	var feishuHookCount atomic.Int64
	var feishuDMCount atomic.Int64
	var mockMu sync.Mutex
	var feishuHookLast string

	extSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/v1/tracks/search":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"data":[{"id":"au-186016","title":"Morning Breeze","duration":5,"user":{"name":"Open Horizon"}}]}`))
		case r.URL.Path == "/v1/tracks/au-186016/stream":
			w.Header().Set("Content-Type", "audio/mpeg")
			http.ServeContent(w, r, "morning_breeze.mp3", time.Unix(1700000000, 0), bytes.NewReader(audioBytes))
		case r.URL.Path == "/rest/ping" || r.URL.Path == "/rest/ping.view":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"subsonic-response":{"status":"ok","version":"1.16.1","serverVersion":"0.53.0"}}`))
		case r.URL.Path == "/rest/startScan" || r.URL.Path == "/rest/startScan.view":
			navScanCount.Add(1)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"subsonic-response":{"status":"ok","version":"1.16.1","scanStatus":{"scanning":true,"count":1}}}`))
		case r.URL.Path == "/rest/getScanStatus" || r.URL.Path == "/rest/getScanStatus.view":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"subsonic-response":{"status":"ok","version":"1.16.1","scanStatus":{"scanning":false,"count":1}}}`))
		case r.URL.Path == "/v1/chat/completions":
			raw, _ := io.ReadAll(r.Body)
			var req struct {
				Messages []struct {
					Role       string `json:"role"`
					Content    string `json:"content"`
					ToolCallID string `json:"tool_call_id"`
				} `json:"messages"`
			}
			_ = json.Unmarshal(raw, &req)
			toolCount := 0
			var lastToolContent string
			for _, m := range req.Messages {
				if m.Role == "tool" {
					toolCount++
					lastToolContent = m.Content
				}
			}
			w.Header().Set("Content-Type", "application/json")
			switch toolCount {
			case 0:
				_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"search_music","arguments":"{\"keyword\":\"Morning Breeze\"}"}}]}}]}`))
			case 1:
				var parsed struct {
					Tracks []struct {
						ID string `json:"id"`
					} `json:"tracks"`
				}
				_ = json.Unmarshal([]byte(lastToolContent), &parsed)
				tid := ""
				if len(parsed.Tracks) > 0 {
					tid = parsed.Tracks[0].ID
				}
				argsBytes, _ := json.Marshal(map[string]any{
					"name":      "AI 歌单",
					"track_ids": []string{tid},
				})
				respPayload := map[string]any{
					"choices": []map[string]any{{
						"message": map[string]any{
							"role":    "assistant",
							"content": "",
							"tool_calls": []map[string]any{{
								"id":   "call_2",
								"type": "function",
								"function": map[string]any{
									"name":      "create_playlist",
									"arguments": string(argsBytes),
								},
							}},
						},
					}},
				}
				b, _ := json.Marshal(respPayload)
				_, _ = w.Write(b)
			default:
				_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"已为您搜索并创建歌单：AI 歌单"}}]}`))
			}
		case r.URL.Path == "/open-apis/bot/v2/hook/test-hook":
			raw, _ := io.ReadAll(r.Body)
			mockMu.Lock()
			feishuHookLast = string(raw)
			mockMu.Unlock()
			feishuHookCount.Add(1)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"code":0,"msg":"ok"}`))
		case r.URL.Path == "/open-apis/auth/v3/tenant_access_token/internal":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"code":0,"tenant_access_token":"t-mock-token","expire":7200}`))
		case r.URL.Path == "/open-apis/im/v1/messages":
			feishuDMCount.Add(1)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"code":0,"data":{"message_id":"om_1"}}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer extSrv.Close()

	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")

	ctx := context.Background()
	a, err := app.New(ctx, app.Config{
		DataDir:           dataDir,
		MusicRoots:        []string{musicRoot},
		AuthUser:          "admin",
		AuthPass:          "secret123",
		AllowTestLoopback: true,
		FFmpegPath:        "disabled",
		FFprobePath:       "disabled",
		FpcalcPath:        "disabled",
		ProviderEndpoints: map[string]string{
			"audius_search":     extSrv.URL + "/v1/tracks/search",
			"audius_track_base": extSrv.URL + "/v1/tracks",
		},
	})
	if err != nil {
		t.Fatalf("app.New: %v", err)
	}
	defer a.Close()

	apiSrv := httptest.NewServer(a.Handler)
	defer apiSrv.Close()

	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	cli := &apiClient{
		t:       t,
		baseURL: apiSrv.URL,
		client:  &http.Client{Timeout: 15 * time.Second, Jar: jar},
	}

	// 1. Auth & CSRF enforcement.
	cli.doJSON(http.MethodGet, "/api/config", nil, false, http.StatusUnauthorized, nil)
	cli.doJSON(http.MethodPost, "/api/auth/login", map[string]string{"username": "admin", "password": "secret123"}, false, http.StatusForbidden, nil)
	cli.doJSON(http.MethodPost, "/api/auth/login", map[string]string{"username": "admin", "password": "secret123"}, true, http.StatusOK, nil)

	// 2. Configure runtime (single source AudiusMusicClient, disable embed_metadata for deterministic offline audio).
	embedFalse := false
	cli.doJSON(http.MethodPut, "/api/config", map[string]any{
		"sources":        []string{"AudiusMusicClient"},
		"embed_metadata": embedFalse,
	}, true, http.StatusOK, nil)

	// Configure Navidrome + Library settings.
	cli.doJSON(http.MethodPut, "/api/library/navidrome", map[string]any{
		"enabled":   true,
		"url":       extSrv.URL,
		"username":  "navuser",
		"password":  "navpass",
		"auto_scan": true,
	}, true, http.StatusOK, nil)
	cli.doJSON(http.MethodPut, "/api/library/settings", map[string]any{
		"auto_scrape":          false,
		"auto_organize":        false,
		"library_dir":          "library",
		"folder_template":      "{album_artist}/{album}",
		"filename_template":    "{track_number} - {title}",
		"sources":              []string{"itunes", "musicbrainz", "lrclib"},
		"lyrics_mode":          "both",
		"cover_mode":           "both",
		"fields":               []string{"title", "artist", "album"},
		"backup_days":          7,
		"watch_interval":       180,
		"watch_stable_seconds": 30,
	}, true, http.StatusOK, nil)

	// 3. Search job creation + SSE stream + Last-Event-ID reconnect.
	var searchResp struct {
		ID string `json:"id"`
	}
	cli.doJSON(http.MethodPost, "/api/search/jobs", map[string]any{
		"keyword": "Morning Breeze",
		"sources": []string{"AudiusMusicClient"},
	}, true, http.StatusOK, &searchResp)
	if searchResp.ID == "" {
		t.Fatalf("expected non-empty search job id")
	}

	sseResp := cli.do(http.MethodGet, "/api/search/jobs/"+searchResp.ID+"/events", nil, nil)
	if sseResp.StatusCode != http.StatusOK {
		t.Fatalf("sse status=%d", sseResp.StatusCode)
	}
	type sseEvt struct {
		id   string
		ev   string
		data string
	}
	var evts []sseEvt
	sc := bufio.NewScanner(sseResp.Body)
	var cur sseEvt
	for sc.Scan() {
		line := sc.Text()
		if line == "" {
			if cur.ev != "" || cur.data != "" {
				evts = append(evts, cur)
				if cur.ev == "done" {
					break
				}
			}
			cur = sseEvt{}
			continue
		}
		switch {
		case strings.HasPrefix(line, "id: "):
			cur.id = strings.TrimPrefix(line, "id: ")
		case strings.HasPrefix(line, "event: "):
			cur.ev = strings.TrimPrefix(line, "event: ")
		case strings.HasPrefix(line, "data: "):
			cur.data = strings.TrimPrefix(line, "data: ")
		}
	}
	_ = sseResp.Body.Close()

	var trackSID, firstEventID string
	for _, e := range evts {
		if firstEventID == "" && e.id != "" {
			firstEventID = e.id
		}
		if e.ev == "track" && trackSID == "" {
			var tr model.TrackDTO
			if err := json.Unmarshal([]byte(e.data), &tr); err == nil {
				trackSID = tr.ID
			}
		}
	}
	if trackSID == "" || firstEventID == "" {
		t.Fatalf("expected track and event id from SSE, got evts=%+v", evts)
	}

	// Reconnect using Last-Event-ID and verify earlier event is not replayed.
	reconnResp := cli.do(http.MethodGet, "/api/search/jobs/"+searchResp.ID+"/events", nil, map[string]string{
		"Last-Event-ID": firstEventID,
	})
	var reconnIDs []string
	sc2 := bufio.NewScanner(reconnResp.Body)
	for sc2.Scan() {
		line := sc2.Text()
		if strings.HasPrefix(line, "id: ") {
			reconnIDs = append(reconnIDs, strings.TrimPrefix(line, "id: "))
		}
	}
	_ = reconnResp.Body.Close()
	if slices.Contains(reconnIDs, firstEventID) {
		t.Fatalf("Last-Event-ID %s should not be replayed on reconnect, got %v", firstEventID, reconnIDs)
	}

	// 4. Playback resolve + HTTP Range 206 streaming.
	var playDTO model.TrackDTO
	cli.doJSON(http.MethodPost, "/api/tracks/"+trackSID+"/playback", nil, true, http.StatusOK, &playDTO)
	if playDTO.Playback.State != "ready" || playDTO.Playback.URL == nil {
		t.Fatalf("expected ready playback, got %+v", playDTO.Playback)
	}
	streamResp := cli.do(http.MethodGet, *playDTO.Playback.URL, nil, map[string]string{"Range": "bytes=0-15"})
	streamChunk, _ := io.ReadAll(streamResp.Body)
	_ = streamResp.Body.Close()
	if streamResp.StatusCode != http.StatusPartialContent || len(streamChunk) != 16 || !bytes.HasPrefix(streamChunk, []byte("ID3")) {
		t.Fatalf("expected 206 range response with ID3 header, got status=%d len=%d", streamResp.StatusCode, len(streamChunk))
	}

	// 5. Download enqueue -> poll complete -> library organize job -> apply -> Navidrome scan -> pagination & 409.
	var dlList []model.DownloadJobDTO
	cli.doJSON(http.MethodPost, "/api/downloads", []map[string]any{{"track_id": trackSID, "quality_id": ""}}, true, http.StatusOK, &dlList)
	if len(dlList) != 1 {
		t.Fatalf("expected 1 download item, got %d", len(dlList))
	}
	dlID := dlList[0].ID

	var completedDL model.DownloadJobDTO
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		var cur []model.DownloadJobDTO
		cli.doJSON(http.MethodGet, "/api/downloads", nil, false, http.StatusOK, &cur)
		for _, d := range cur {
			if d.ID == dlID {
				completedDL = d
			}
		}
		if completedDL.Status == "complete" {
			break
		}
		if completedDL.Status == "failed" || completedDL.Status == "cancelled" {
			t.Fatalf("download ended unexpectedly: %+v", completedDL)
		}
		time.Sleep(50 * time.Millisecond)
	}
	if completedDL.Status != "complete" || completedDL.File == nil {
		t.Fatalf("download did not complete in time: %+v", completedDL)
	}

	// Organize via /api/library/jobs -> apply (verifies library pipeline, file commit, Navidrome startScan).
	absDLPath := ""
	if strings.HasPrefix(*completedDL.File, "asset:") {
		assetID := strings.TrimPrefix(*completedDL.File, "asset:")
		assetRec, ok, aErr := a.Store.GetAsset(ctx, assetID)
		if aErr != nil || !ok {
			t.Fatalf("expected downloaded asset %s in store (ok=%v err=%v)", assetID, ok, aErr)
		}
		for _, rt := range a.Files.Roots() {
			if rt.ID == assetRec.RootID {
				absDLPath = filepath.Join(rt.CanonicalPath, filepath.FromSlash(assetRec.RelPath))
				break
			}
		}
	}
	if absDLPath == "" {
		absDLPath = filepath.Join(a.AbsWorkDir, filepath.FromSlash(*completedDL.File))
	}
	var orgJob model.LibraryJobDTO
	cli.doJSON(http.MethodPost, "/api/library/jobs", map[string]any{
		"paths":  []string{absDLPath},
		"action": "organize",
	}, true, http.StatusOK, &orgJob)
	if orgJob.ID == "" {
		t.Fatalf("expected library job id")
	}
	deadline = time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		cli.doJSON(http.MethodGet, "/api/library/jobs/"+orgJob.ID, nil, false, http.StatusOK, &orgJob)
		if orgJob.Status == "preview_ready" || orgJob.Status == "needs_review" || orgJob.Status == "complete" {
			break
		}
		time.Sleep(40 * time.Millisecond)
	}
	if orgJob.Status == "preview_ready" || orgJob.Status == "needs_review" {
		cli.doJSON(http.MethodPost, "/api/library/jobs/"+orgJob.ID+"/apply", map[string]any{}, true, http.StatusOK, &orgJob)
		deadline = time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			cli.doJSON(http.MethodGet, "/api/library/jobs/"+orgJob.ID, nil, false, http.StatusOK, &orgJob)
			if orgJob.Status == "complete" || orgJob.Status == "failed" || orgJob.Status == "partial" {
				break
			}
			time.Sleep(40 * time.Millisecond)
		}
	}
	if orgJob.Status != "complete" {
		t.Fatalf("expected library job complete, got %+v", orgJob)
	}
	if navScanCount.Load() < 1 {
		t.Fatalf("expected Navidrome startScan to be triggered on library apply, got %d", navScanCount.Load())
	}

	// Check /api/library/files & local audio range streaming.
	var page model.LibraryFilesPageDTO
	cli.doJSON(http.MethodGet, "/api/library/files?limit=10", nil, false, http.StatusOK, &page)
	if len(page.Items) == 0 {
		t.Fatalf("expected indexed library asset in /api/library/files")
	}
	assetToken := page.Items[0].Path
	localStream := cli.do(http.MethodGet, "/api/files/audio?path="+assetToken, nil, map[string]string{"Range": "bytes=0-15"})
	localChunk, _ := io.ReadAll(localStream.Body)
	_ = localStream.Body.Close()
	if localStream.StatusCode != http.StatusPartialContent || len(localChunk) != 16 || !bytes.HasPrefix(localChunk, []byte("ID3")) {
		t.Fatalf("expected 206 from /api/files/audio, got status=%d len=%d", localStream.StatusCode, len(localChunk))
	}

	// Verify disk presence under musicRoot/library.
	var foundOnDisk bool
	_ = filepath.WalkDir(filepath.Join(musicRoot, "library"), func(p string, d os.DirEntry, wErr error) error {
		if wErr == nil && !d.IsDir() && strings.HasSuffix(strings.ToLower(p), ".mp3") {
			foundOnDisk = true
		}
		return nil
	})
	if !foundOnDisk {
		t.Fatalf("expected organized mp3 file on disk under %s", filepath.Join(musicRoot, "library"))
	}

	// Seed a second asset so limit=1 returns a cursor, then bump index_revision and assert 409.
	roots := a.Files.Roots()
	extraPath := filepath.Join(roots[0].CanonicalPath, "library", "extra.mp3")
	if err := os.WriteFile(extraPath, audioBytes, 0o644); err != nil {
		t.Fatal(err)
	}
	_, err = a.Store.UpsertAsset(ctx, store.AssetRecord{
		RootID:  roots[0].ID,
		RelPath: "library/extra.mp3",
		Title:   "Extra",
		Size:    int64(len(audioBytes)),
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	var p1 model.LibraryFilesPageDTO
	cli.doJSON(http.MethodGet, "/api/library/files?limit=1", nil, false, http.StatusOK, &p1)
	if p1.NextCursor == nil || *p1.NextCursor == "" {
		t.Fatalf("expected non-empty next_cursor with 2 assets and limit=1")
	}
	_, err = a.Store.UpsertAsset(ctx, store.AssetRecord{
		RootID:  roots[0].ID,
		RelPath: "library/extra2.mp3",
		Title:   "Extra2",
		Size:    int64(len(audioBytes)),
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	cli.doJSON(http.MethodGet, "/api/library/files?limit=1&cursor="+*p1.NextCursor, nil, false, http.StatusConflict, nil)

	// 6. AI Agent turn (search_music + create_playlist via operations.Registry, secrets redacted).
	const aiSecret = "sk-super-secret-agent-token"
	pubAISettings := cli.doJSON(http.MethodPut, "/api/agent/settings", map[string]any{
		"enabled":    true,
		"base_url":   extSrv.URL + "/v1",
		"model":      "gpt-test",
		"api_key":    aiSecret,
		"api_type":   "chat_completions",
		"proxy_mode": "direct",
	}, true, http.StatusOK, nil)
	if strings.Contains(string(pubAISettings), aiSecret) {
		t.Fatalf("agent settings response leaked secret")
	}
	var sess map[string]any
	cli.doJSON(http.MethodPost, "/api/agent/sessions", map[string]any{}, true, http.StatusOK, &sess)
	sessID, _ := sess["id"].(string)
	if sessID == "" {
		t.Fatalf("expected agent session id")
	}
	cli.doJSON(http.MethodPost, "/api/agent/sessions/"+sessID+"/messages", map[string]any{
		"message":       "搜一下 Morning Breeze 并创建名为 AI 歌单 的歌单",
		"allow_actions": true,
	}, true, http.StatusOK, nil)

	var sessStatus string
	var sessRaw []byte
	deadline = time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		sessRaw = cli.doJSON(http.MethodGet, "/api/agent/sessions/"+sessID, nil, false, http.StatusOK, &sess)
		sessStatus, _ = sess["status"].(string)
		if sessStatus == "complete" || sessStatus == "failed" || sessStatus == "needs_decision" {
			break
		}
		time.Sleep(30 * time.Millisecond)
	}
	if sessStatus != "complete" {
		t.Fatalf("expected agent session complete, got status=%s body=%s", sessStatus, string(sessRaw))
	}
	if strings.Contains(string(sessRaw), aiSecret) {
		t.Fatalf("agent session leaked API key")
	}
	var playlists []model.PlaylistSummaryDTO
	cli.doJSON(http.MethodGet, "/api/playlists", nil, false, http.StatusOK, &playlists)
	foundPlaylist := false
	for _, pl := range playlists {
		if pl.Name == "AI 歌单" {
			foundPlaylist = true
		}
	}
	if !foundPlaylist {
		t.Fatalf("expected AI-created playlist 'AI 歌单', got %+v", playlists)
	}

	// 7. Bot inbound callback + outbox delivery.
	cli.doJSON(http.MethodPut, "/api/bots/config", map[string]any{
		"public_url":                apiSrv.URL,
		"feishu_enabled":            true,
		"feishu_webhook":            extSrv.URL + "/open-apis/bot/v2/hook/test-hook",
		"feishu_bot_enabled":        true,
		"feishu_allowed_users":      "ou_test_user",
		"feishu_app_id":             "cli_test_app",
		"feishu_app_secret":         "cli_test_secret",
		"feishu_verification_token": "verify_tok",
		"feishu_encrypt_key":        "enc_key_test",
		"feishu_region":             "feishu",
		"feishu_api_url":            extSrv.URL,
	}, true, http.StatusOK, nil)

	ts := fmt.Sprintf("%d", time.Now().Unix())
	nonce := "nonce123"
	feishuBody, _ := json.Marshal(map[string]any{
		"header": map[string]any{
			"token":      "verify_tok",
			"app_id":     "cli_test_app",
			"event_type": "im.message.receive_v1",
		},
		"event": map[string]any{
			"sender": map[string]any{
				"sender_type": "user",
				"sender_id":   map[string]any{"open_id": "ou_test_user"},
			},
			"message": map[string]any{
				"message_id":   "om_inbound_1",
				"chat_type":    "p2p",
				"message_type": "text",
				"content":      `{"text":"/help"}`,
			},
		},
	})
	sigSum := sha256.Sum256(append([]byte(ts+nonce+"enc_key_test"), feishuBody...))
	feishuSig := hex.EncodeToString(sigSum[:])
	fReq, _ := http.NewRequest(http.MethodPost, apiSrv.URL+"/bot/feishu", bytes.NewReader(feishuBody))
	fReq.Header.Set("Content-Type", "application/json")
	fReq.Header.Set("X-Lark-Request-Timestamp", ts)
	fReq.Header.Set("X-Lark-Request-Nonce", nonce)
	fReq.Header.Set("X-Lark-Signature", feishuSig)
	fResp, err := cli.client.Do(fReq)
	if err != nil {
		t.Fatalf("post /bot/feishu: %v", err)
	}
	_ = fResp.Body.Close()
	if fResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 from /bot/feishu, got %d", fResp.StatusCode)
	}
	if err := a.Bot.DrainPending(ctx); err != nil {
		t.Fatalf("DrainPending: %v", err)
	}
	if err := a.Bot.EnqueueNotification(ctx, "feishu", "downloads", "e2e_notify_1", bot.OutboxPayload{Text: "下载完成通知"}); err != nil {
		t.Fatalf("EnqueueNotification: %v", err)
	}
	_ = a.Store.EnqueueOutbox(ctx, store.OutboxEnqueueParams{
		Channel:      "feishu",
		RecipientKey: "downloads",
		EventKey:     "e2e_webhook_check",
		PayloadJSON:  `{"text":"E2E 通知投递验证"}`,
	})
	if _, err := a.Bot.ProcessOutboxOnce(ctx, 10); err != nil {
		t.Fatalf("ProcessOutboxOnce: %v", err)
	}
	if feishuDMCount.Load() < 1 || feishuHookCount.Load() < 1 {
		t.Fatalf("expected Feishu DM (%d) and webhook (%d) deliveries", feishuDMCount.Load(), feishuHookCount.Load())
	}
	mockMu.Lock()
	lastHook := feishuHookLast
	mockMu.Unlock()
	if !strings.Contains(lastHook, "MeloKeep") {
		t.Fatalf("unexpected webhook payload: %s", lastHook)
	}
}

func createLegacySnapshotDir(t *testing.T, withBlockedOp bool) (legacyDir, cfgPath, envPath, dbPath string) {
	t.Helper()
	legacyDir = t.TempDir()
	dbPath = filepath.Join(legacyDir, "melokeep.db")
	songsDir := filepath.Join(legacyDir, "songs")
	if err := os.MkdirAll(songsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	songBytes, _ := json.Marshal(map[string]any{
		"source":          "AudiusMusicClient",
		"identifier":      "au-101",
		"root_source":     "AudiusMusicClient",
		"song_name":       "Morning Breeze",
		"singers":         "Open Horizon",
		"album":           "Open Air Sessions",
		"duration_s":      269.0,
		"ext":             "mp3",
		"bitrate":         320,
		"file_size_bytes": 4096,
	})
	if err := os.WriteFile(filepath.Join(songsDir, "trk_1.song"), songBytes, 0o644); err != nil {
		t.Fatal(err)
	}
	cfgPath = filepath.Join(legacyDir, "config.json")
	if err := os.WriteFile(cfgPath, []byte(`{"music_sources":["AudiusMusicClient"],"download_layout":"artist_album","lyrics_mode":"both"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	envPath = filepath.Join(legacyDir, ".env")
	if err := os.WriteFile(envPath, []byte("MELOKEEP_FILE_ROOT=melokeep_outputs\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	ldb, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer ldb.Close()
	if _, err := ldb.Exec(`CREATE TABLE records (kind TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(kind, id))`); err != nil {
		t.Fatal(err)
	}
	now := time.Now().Unix()
	inserts := [][3]string{
		{"favorite", "trk_1", `{"id":"trk_1"}`},
		{"collection", "col_1", `{"id":"col_1","name":"怀旧金曲","source":"local","tracks":[{"id":"trk_1"}]}`},
		{"download", "dl_1", `{"id":"dl_1","status":"complete","quality_id":"320","download_source":"AudiusMusicClient","bytes":4096,"track":{"id":"trk_1"}}`},
		{"library_setting", "config", `{"library_dir":"library"}`},
	}
	if withBlockedOp {
		inserts = append(inserts, [3]string{"library_operation", "op_blocked", `{"id":"op_blocked","kind":"move","state":"publishing"}`})
	}
	for _, row := range inserts {
		if _, err := ldb.Exec(`INSERT INTO records(kind, id, body, updated) VALUES(?, ?, ?, ?)`, row[0], row[1], row[2], now); err != nil {
			t.Fatal(err)
		}
	}
	return legacyDir, cfgPath, envPath, dbPath
}

func TestLegacyMigrationAndReadOnlySwitch(t *testing.T) {
	ctx := context.Background()

	// Part A: clean legacy migration -> read-only mode -> writable switch.
	legDir, cfgPath, envPath, dbPath := createLegacySnapshotDir(t, false)
	targetData := filepath.Join(t.TempDir(), "data")
	musicRoot := filepath.Join(t.TempDir(), "music")
	if err := os.MkdirAll(musicRoot, 0o755); err != nil {
		t.Fatal(err)
	}

	st, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(targetData)})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	dryRep, err := legacyimport.ImportLegacySnapshot(ctx, st, legacyimport.ImportOptions{
		LegacyDataDir: legDir,
		LegacyDBPath:  dbPath,
		ConfigPath:    cfgPath,
		EnvPath:       envPath,
		FileRoot:      musicRoot,
		DryRun:        true,
	})
	if err != nil {
		t.Fatalf("dry-run import: %v", err)
	}
	if !dryRep.DryRun || dryRep.BlocksWriteSwitch || dryRep.Counts.Collections != 2 {
		t.Fatalf("unexpected dry-run report: %+v", dryRep)
	}
	var dryCount int
	if err := st.Reader().QueryRowContext(ctx, `SELECT COUNT(*) FROM legacy_records`).Scan(&dryCount); err != nil || dryCount != 0 {
		t.Fatalf("dry-run must not persist legacy_records, got count=%d err=%v", dryCount, err)
	}

	rep, err := legacyimport.ImportLegacySnapshot(ctx, st, legacyimport.ImportOptions{
		LegacyDataDir: legDir,
		LegacyDBPath:  dbPath,
		ConfigPath:    cfgPath,
		EnvPath:       envPath,
		FileRoot:      musicRoot,
		DryRun:        false,
	})
	if err != nil {
		t.Fatalf("actual import: %v", err)
	}
	if rep.BlocksWriteSwitch {
		t.Fatalf("clean migration should not block write switch: %+v", rep)
	}
	chk, err := legacyimport.CheckIntegrity(ctx, st)
	if err != nil || !chk.OK {
		t.Fatalf("integrity check failed: ok=%v err=%v details=%+v", chk.OK, err, chk)
	}
	_ = st.Close()

	// Boot in --read-only mode: GET passes, POST/PUT mutations return 403.
	roApp, err := app.New(ctx, app.Config{
		DataDir:           targetData,
		MusicRoots:        []string{musicRoot},
		ReadOnly:          true,
		AllowTestLoopback: true,
	})
	if err != nil {
		t.Fatalf("read-only app.New: %v", err)
	}
	for _, getPath := range []string{"/api/config", "/api/library/files", "/api/playlists", "/api/downloads"} {
		req := httptest.NewRequest(http.MethodGet, getPath, nil)
		rec := httptest.NewRecorder()
		roApp.Handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("read-only GET %s status=%d body=%s", getPath, rec.Code, rec.Body.String())
		}
	}
	for _, mut := range []struct {
		method string
		path   string
		body   string
	}{
		{http.MethodPost, "/api/playlists", `{"name":"x"}`},
		{http.MethodPost, "/api/downloads", `[{"track_id":"trk_1"}]`},
		{http.MethodPut, "/api/config", `{"sources":["AudiusMusicClient"]}`},
	} {
		req := httptest.NewRequest(mut.method, mut.path, strings.NewReader(mut.body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		roApp.Handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusForbidden {
			t.Fatalf("read-only %s %s expected 403, got %d body=%s", mut.method, mut.path, rec.Code, rec.Body.String())
		}
	}
	_ = roApp.Close()

	// Switch clean data dir to writable mode -> succeeds.
	rwApp, err := app.New(ctx, app.Config{
		DataDir:           targetData,
		MusicRoots:        []string{musicRoot},
		ReadOnly:          false,
		AllowTestLoopback: true,
	})
	if err != nil {
		t.Fatalf("writable app.New after clean migration: %v", err)
	}
	_ = rwApp.Close()

	// Part B: blocked legacy migration (incomplete library_operation) -> read-only allowed, write switch blocked (409).
	bLegDir, bCfg, bEnv, bDB := createLegacySnapshotDir(t, true)
	bTargetData := filepath.Join(t.TempDir(), "data")
	bMusicRoot := filepath.Join(t.TempDir(), "music")
	if err := os.MkdirAll(bMusicRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	bStore, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(bTargetData)})
	if err != nil {
		t.Fatal(err)
	}
	bRep, err := legacyimport.ImportLegacySnapshot(ctx, bStore, legacyimport.ImportOptions{
		LegacyDataDir: bLegDir,
		LegacyDBPath:  bDB,
		ConfigPath:    bCfg,
		EnvPath:       bEnv,
		FileRoot:      bMusicRoot,
		DryRun:        false,
	})
	if err != nil {
		t.Fatal(err)
	}
	if !bRep.BlocksWriteSwitch {
		t.Fatalf("expected BlocksWriteSwitch=true when legacy_operation is publishing")
	}
	_ = bStore.Close()

	roBlocked, err := app.New(ctx, app.Config{
		DataDir:           bTargetData,
		MusicRoots:        []string{bMusicRoot},
		ReadOnly:          true,
		AllowTestLoopback: true,
	})
	if err != nil {
		t.Fatalf("expected read-only boot to succeed even with blocked write switch: %v", err)
	}
	_ = roBlocked.Close()

	_, err = app.New(ctx, app.Config{
		DataDir:           bTargetData,
		MusicRoots:        []string{bMusicRoot},
		ReadOnly:          false,
		AllowTestLoopback: true,
	})
	de, ok := model.AsDomainError(err)
	if err == nil || !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 Conflict blocking write switch, got err=%v", err)
	}
}

func TestLibraryIndex100kPaginationLatency(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	a, err := app.New(ctx, app.Config{
		DataDir:           filepath.Join(tmp, "data"),
		MusicRoots:        []string{filepath.Join(tmp, "music")},
		AllowTestLoopback: true,
		FFmpegPath:        "disabled",
		FFprobePath:       "disabled",
		FpcalcPath:        "disabled",
	})
	if err != nil {
		t.Fatalf("app.New: %v", err)
	}
	defer a.Close()

	rootID := a.Files.Roots()[0].ID
	const totalRows = 100000
	now := time.Now().UTC().UnixMilli()
	err = a.Store.WithTx(ctx, func(tx *sql.Tx) error {
		stmt, err := tx.PrepareContext(ctx, `
			INSERT INTO assets(
				id, root_id, rel_path, path_key, folder, name, status, editable,
				size, mtime_ns, revision, title, artist, album, duration_ms,
				metadata_json, locks_json, origin_json, indexed_generation, updated_at
			) VALUES(?, ?, ?, ?, ?, ?, 'active', 1, ?, ?, 1, ?, ?, ?, 180000, '{}', '[]', '{}', 1, ?)
		`)
		if err != nil {
			return err
		}
		defer stmt.Close()
		for i := 0; i < totalRows; i++ {
			id := fmt.Sprintf("a-%06d", i)
			folder := fmt.Sprintf("folder-%03d", i%200)
			name := fmt.Sprintf("track-%06d.mp3", i)
			rel := folder + "/" + name
			title := fmt.Sprintf("Song %06d", i)
			artist := fmt.Sprintf("Artist%04d", i%2000)
			album := fmt.Sprintf("Album%04d", i%1000)
			if _, err := stmt.ExecContext(ctx, id, rootID, rel, rel, folder, name, int64(1024+i), now+int64(i), title, artist, album, now+int64(i)); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		t.Fatalf("seed 100k assets: %v", err)
	}

	queryHTTP := func(rawURL string) (model.LibraryFilesPageDTO, time.Duration) {
		req := httptest.NewRequest(http.MethodGet, rawURL, nil)
		rec := httptest.NewRecorder()
		start := time.Now()
		a.Handler.ServeHTTP(rec, req)
		elapsed := time.Since(start)
		if rec.Code != http.StatusOK {
			t.Fatalf("GET %s status=%d body=%s", rawURL, rec.Code, rec.Body.String())
		}
		var page model.LibraryFilesPageDTO
		if err := json.Unmarshal(rec.Body.Bytes(), &page); err != nil {
			t.Fatalf("decode page: %v", err)
		}
		return page, elapsed
	}

	firstPage, d0 := queryHTTP("/api/library/files?limit=100")
	if len(firstPage.Items) != 100 || firstPage.NextCursor == nil {
		t.Fatalf("expected 100 items and non-nil next_cursor, got len=%d", len(firstPage.Items))
	}
	cursor := *firstPage.NextCursor

	var latencies []time.Duration
	latencies = append(latencies, d0)
	repURLs := []string{
		"/api/library/files?limit=100",
		"/api/library/files?limit=100&cursor=" + cursor,
		"/api/library/files?limit=100&folder=folder-042",
		"/api/library/files?limit=100&sort=path_key",
	}
	for round := 0; round < 10; round++ {
		for _, u := range repURLs {
			p, d := queryHTTP(u)
			if len(p.Items) == 0 {
				t.Fatalf("expected non-empty items for %s", u)
			}
			latencies = append(latencies, d)
		}
	}

	// Also measure & log unindexed search/sort paths for visibility.
	for _, u := range []string{
		"/api/library/files?limit=100&query=Artist0042",
		"/api/library/files?limit=100&sort=title",
		"/api/library/files?limit=100&sort=artist",
		"/api/library/files?limit=100&sort=updated_at",
	} {
		p, d := queryHTTP(u)
		if len(p.Items) == 0 {
			t.Fatalf("expected non-empty items for %s", u)
		}
		t.Logf("100k unindexed filter/sort %s: %s", u, d)
	}

	slices.Sort(latencies)
	p50 := latencies[len(latencies)/2]
	p95Idx := (len(latencies)*95 + 99) / 100
	if p95Idx >= len(latencies) {
		p95Idx = len(latencies) - 1
	}
	p95 := latencies[p95Idx]
	maxD := latencies[len(latencies)-1]
	t.Logf("100k library pagination latencies (n=%d): p50=%s p95=%s max=%s", len(latencies), p50, p95, maxD)
	if p95 >= 200*time.Millisecond {
		t.Fatalf("p95 latency %s exceeded 200ms target", p95)
	}
}

