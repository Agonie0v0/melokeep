package app_test

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/agent"
	"github.com/agonie0v0/melokeep/internal/app"
	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/bot"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
	"github.com/agonie0v0/melokeep/internal/provider/catalog"
	"github.com/agonie0v0/melokeep/internal/provider/plugin"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

func writeMinimalWAV(t *testing.T, path string) []byte {
	t.Helper()
	// Minimal valid 44-byte PCM WAV header + 16 zero samples
	buf := []byte{
		'R', 'I', 'F', 'F', 52, 0, 0, 0, 'W', 'A', 'V', 'E',
		'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 1, 0,
		0x44, 0xAC, 0, 0, 0x88, 0x58, 0x01, 0, 2, 0, 16, 0,
		'd', 'a', 't', 'a', 16, 0, 0, 0,
		0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
	}
	if err := os.WriteFile(path, buf, 0o644); err != nil {
		t.Fatalf("write wav: %v", err)
	}
	return buf
}

func TestReadOnlyModePreservesMusicRootsAndCrashRecoveryState(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(musicRoot, 0o755); err != nil {
		t.Fatal(err)
	}

	songPath := filepath.Join(musicRoot, "track.wav")
	origBytes := writeMinimalWAV(t, songPath)
	pendingTmpPath := filepath.Join(musicRoot, ".melokeep-pending.tmp")
	if err := os.WriteFile(pendingTmpPath, []byte("pending-bytes"), 0o644); err != nil {
		t.Fatal(err)
	}

	st, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(dataDir)})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	stageRef, _ := json.Marshal(map[string]string{"abs_path": pendingTmpPath})
	if _, err := st.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation: model.FileOperation{
			ID:    "op-ro-1",
			Kind:  "move",
			State: "prepared",
		},
		Files: []store.OperationFileSpec{
			{
				Ordinal:      0,
				Role:         "audio",
				StageRefJSON: string(stageRef),
				Phase:        "prepared",
			},
		},
	}); err != nil {
		t.Fatalf("CreateFileOperation: %v", err)
	}
	if err := st.EnqueueOutbox(ctx, store.OutboxEnqueueParams{
		Channel:      "telegram",
		RecipientKey: "123",
		EventKey:     "ro-test-1",
		PayloadJSON:  `{"text":"hi"}`,
	}); err != nil {
		t.Fatalf("EnqueueOutbox: %v", err)
	}
	if _, err := st.ClaimOutbox(ctx, 10); err != nil {
		t.Fatalf("ClaimOutbox: %v", err)
	}
	sess := agent.Session{
		ID:           "sess-ro-1",
		Title:        "RO Session",
		Status:       agent.StatusRunning,
		ActiveTurnID: "turn-1",
		PendingWrite: &agent.PendingWrite{
			TurnID:         "turn-1",
			ToolCallID:     "call-1",
			ToolName:       "enqueue_download",
			Operation:      "POST /api/downloads",
			IdempotencyKey: "k-ro-1",
			State:          "started",
		},
	}
	sessRaw, _ := json.Marshal(sess)
	if _, err := st.PutDurableRecord(ctx, agent.NamespaceSession, sess.ID, string(sessRaw), 0); err != nil {
		t.Fatalf("PutDurableRecord: %v", err)
	}
	_ = st.Close()

	a, err := app.New(ctx, app.Config{
		DataDir:    dataDir,
		MusicRoots: []string{musicRoot},
		ReadOnly:   true,
	})
	if err != nil {
		t.Fatalf("app.New read-only: %v", err)
	}
	defer a.Close()

	if a.Recovery.Files.RecoveredOps != 0 || a.Recovery.OutboxUnknown != 0 {
		t.Fatalf("read-only mode must not execute startup recovery mutations: %+v", a.Recovery)
	}
	entries, err := os.ReadDir(musicRoot)
	if err != nil {
		t.Fatal(err)
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name())
	}
	if len(names) != 2 {
		t.Fatalf("expected musicRoot untouched (2 entries), got %v", names)
	}
	gotBytes, err := os.ReadFile(songPath)
	if err != nil || !bytes.Equal(gotBytes, origBytes) {
		t.Fatalf("song bytes modified or missing: err=%v", err)
	}
	if _, err := os.Stat(pendingTmpPath); err != nil {
		t.Fatalf("pending temp file removed during read-only startup: %v", err)
	}

	var opState string
	if err := a.Store.Reader().QueryRowContext(ctx, `SELECT state FROM file_operations WHERE id = 'op-ro-1'`).Scan(&opState); err != nil {
		t.Fatal(err)
	}
	if opState != "prepared" {
		t.Fatalf("expected file_operation state 'prepared' untouched, got %q", opState)
	}
	var outboxState string
	if err := a.Store.Reader().QueryRowContext(ctx, `SELECT state FROM outbox LIMIT 1`).Scan(&outboxState); err != nil {
		t.Fatal(err)
	}
	if outboxState != "sending" {
		t.Fatalf("expected outbox state 'sending' untouched, got %q", outboxState)
	}
	rec, ok, err := a.Store.GetDurableRecord(ctx, agent.NamespaceSession, sess.ID)
	if err != nil || !ok {
		t.Fatalf("GetDurableRecord agent session: ok=%v err=%v", ok, err)
	}
	var loadedSess agent.Session
	if err := json.Unmarshal([]byte(rec.BodyJSON), &loadedSess); err != nil {
		t.Fatal(err)
	}
	if loadedSess.Status != agent.StatusRunning || loadedSess.PendingWrite == nil || loadedSess.PendingWrite.State != "started" {
		t.Fatalf("expected agent session untouched in read-only mode, got %+v", loadedSess)
	}

	// Verify HTTP read-only middleware blocks writes and serves read endpoints.
	for _, tc := range []struct {
		method string
		path   string
		body   string
	}{
		{http.MethodPost, "/api/downloads", `[{"track_id":"x"}]`},
		{http.MethodPut, "/api/config", `{}`},
		{http.MethodDelete, "/api/files?path=track.wav", ""},
		{http.MethodPost, "/api/library/jobs", `{"action":"organize","paths":["track.wav"]}`},
	} {
		req := httptest.NewRequest(tc.method, tc.path, bytes.NewBufferString(tc.body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		a.Handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusForbidden {
			t.Fatalf("%s %s in read-only mode returned %d, want 403: %s", tc.method, tc.path, rec.Code, rec.Body.String())
		}
	}

	healthRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(healthRec, httptest.NewRequest(http.MethodGet, "/api/health", nil))
	if healthRec.Code != http.StatusOK {
		t.Fatalf("GET /api/health failed: %d", healthRec.Code)
	}
	var health map[string]any
	if err := json.Unmarshal(healthRec.Body.Bytes(), &health); err != nil || health["ok"] != true {
		t.Fatalf("unexpected /api/health body: %s", healthRec.Body.String())
	}

	filesRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(filesRec, httptest.NewRequest(http.MethodGet, "/api/library/files", nil))
	if filesRec.Code != http.StatusOK {
		t.Fatalf("GET /api/library/files failed in read-only mode: %d %s", filesRec.Code, filesRec.Body.String())
	}
}

func TestWriteModeBlockedByLegacyMigrationGuard(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(musicRoot, 0o755); err != nil {
		t.Fatal(err)
	}

	st, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(dataDir)})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().UnixMilli()
	err = st.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO import_runs(id, source_fingerprint, state, report_json, created_at, completed_at)
			VALUES('imp-block-1', 'fp-1', 'completed', '{"blocks_write_switch":true}', ?, ?)
		`, now, now)
		return err
	})
	_ = st.Close()
	if err != nil {
		t.Fatal(err)
	}

	_, wErr := app.New(ctx, app.Config{
		DataDir:    dataDir,
		MusicRoots: []string{musicRoot},
		ReadOnly:   false,
	})
	de, ok := model.AsDomainError(wErr)
	if !ok || de.Code != model.ErrConflict {
		t.Fatalf("expected ErrConflict blocking write-mode startup, got %v", wErr)
	}

	roApp, roErr := app.New(ctx, app.Config{
		DataDir:    dataDir,
		MusicRoots: []string{musicRoot},
		ReadOnly:   true,
	})
	if roErr != nil {
		t.Fatalf("expected read-only startup to succeed despite migration block, got %v", roErr)
	}
	_ = roApp.Close()
}

func TestFavoritesSplitBrainFixedBetweenHTTPAndOperations(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")

	a, err := app.New(ctx, app.Config{
		DataDir:    dataDir,
		MusicRoots: []string{musicRoot},
		ReadOnly:   false,
	})
	if err != nil {
		t.Fatalf("app.New: %v", err)
	}
	defer a.Close()

	trID, err := a.Store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source:     "AudiusMusicClient",
		Locator:    "fav-1",
		RootSource: "AudiusMusicClient",
		Title:      "Fav Song",
		Artist:     "Tester",
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	pubID := "fav-pub-1"
	if err := a.Store.PutPublicRef(ctx, model.PublicRef{
		PublicID: pubID,
		Kind:     "candidate",
		TargetID: trID,
		Source:   "AudiusMusicClient",
		SourceID: "fav-1",
	}, ""); err != nil {
		t.Fatal(err)
	}

	putReq := httptest.NewRequest(http.MethodPut, "/api/favorites/"+pubID, nil)
	putReq.Header.Set("X-MeloKeep-Request", "1")
	putRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(putRec, putReq)
	if putRec.Code != http.StatusOK {
		t.Fatalf("PUT /api/favorites failed: %d %s", putRec.Code, putRec.Body.String())
	}

	res, err := a.Operations.Dispatch(ctx, operations.Request{
		Operation: "GET /api/favorites",
		Actor:     auth.WebAdminActor("web"),
	})
	if err != nil {
		t.Fatalf("Operations.Dispatch(GET /api/favorites): %v", err)
	}
	tracks, ok := res.([]model.TrackDTO)
	if !ok || len(tracks) != 1 || tracks[0].ID != pubID {
		t.Fatalf("expected operations GET /api/favorites to match HTTP favorites (%q), got %#v", pubID, res)
	}
}

func TestAll98OperationsBoundAndNavidromeDeleteFlow(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	sharedDir := filepath.Join(musicRoot, "Shared")
	if err := os.MkdirAll(sharedDir, 0o755); err != nil {
		t.Fatal(err)
	}
	songPath := filepath.Join(sharedDir, "track.wav")
	rawWAV := writeMinimalWAV(t, songPath)

	var scanCalled int
	navSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/rest/getSong.view":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status": "ok",
					"song": map[string]any{
						"id":    "nav-song-1",
						"title": "Shared Track",
						"path":  "/remote-music/track.wav",
						"size":  len(rawWAV),
					},
				},
			})
		case "/rest/startScan.view":
			scanCalled++
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subsonic-response": map[string]any{
					"status":     "ok",
					"scanStatus": map[string]any{"scanning": true, "count": 1},
				},
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer navSrv.Close()

	a, err := app.New(ctx, app.Config{
		DataDir:           dataDir,
		MusicRoots:        []string{musicRoot},
		AllowTestLoopback: true,
		ReadOnly:          false,
	})
	if err != nil {
		t.Fatalf("app.New: %v", err)
	}
	defer a.Close()

	specs := a.Operations.ListSpecs()
	if len(specs) != 98 {
		t.Fatalf("expected 98 registered operations, got %d", len(specs))
	}
	for _, sp := range specs {
		if !a.Operations.HasHandler(sp.ID) {
			t.Fatalf("operation %s has no registered handler", sp.ID)
		}
	}

	// Verify representative dispatch via in-process router on an operation not manually mapped earlier.
	logsOut, err := a.Operations.Dispatch(ctx, operations.Request{
		Operation: "GET /api/logs",
		Actor:     auth.WebAdminActor("web"),
	})
	if err != nil {
		t.Fatalf("Dispatch GET /api/logs failed: %v", err)
	}
	if _, ok := logsOut.(map[string]any); !ok {
		t.Fatalf("expected map response for GET /api/logs, got %#v", logsOut)
	}

	// Save Navidrome settings with delete enabled and verify delete preview + execution.
	if _, err := a.Operations.Dispatch(ctx, operations.Request{
		Operation: "PUT /api/library/navidrome",
		Actor:     auth.WebAdminActor("web"),
		Payload: map[string]any{
			"enabled":           true,
			"url":               navSrv.URL,
			"username":          "admin",
			"password":          "secret",
			"delete_enabled":    true,
			"music_root":        sharedDir,
			"server_music_root": "/remote-music",
		},
	}); err != nil {
		t.Fatalf("save navidrome config failed: %v", err)
	}

	capRes, err := a.Operations.Dispatch(ctx, operations.Request{
		Operation: "GET /api/navidrome/capabilities",
		Actor:     auth.WebAdminActor("web"),
	})
	if err != nil {
		t.Fatalf("GET /api/navidrome/capabilities: %v", err)
	}
	capMap, _ := capRes.(map[string]any)
	if capMap["delete_songs"] != true {
		t.Fatalf("expected delete_songs=true when shared root valid, got %#v", capMap)
	}

	prevRes, err := a.Operations.Dispatch(ctx, operations.Request{
		Operation: "GET /api/navidrome/songs/{ident}/deletion",
		Actor:     auth.WebAdminActor("web"),
		Payload:   map[string]any{"ident": "nav-song-1"},
	})
	if err != nil {
		t.Fatalf("GET /api/navidrome/songs/{ident}/deletion: %v", err)
	}
	prevMap, _ := prevRes.(map[string]any)
	rev, _ := prevMap["revision"].(string)
	p, _ := prevMap["path"].(string)
	if rev == "" || !strings.EqualFold(filepath.Clean(p), filepath.Clean(songPath)) {
		t.Fatalf("unexpected delete preview: %#v", prevMap)
	}

	delRes, err := a.Operations.Dispatch(ctx, operations.Request{
		Operation: "DELETE /api/navidrome/songs/{ident}",
		Actor:     auth.WebAdminActor("web"),
		Payload:   map[string]any{"ident": "nav-song-1", "revision": rev},
	})
	if err != nil {
		t.Fatalf("DELETE /api/navidrome/songs/{ident}: %v", err)
	}
	delMap, _ := delRes.(map[string]any)
	if delMap["deleted"] != true || scanCalled == 0 {
		t.Fatalf("expected Navidrome delete + scan trigger, got res=%#v scanCalled=%d", delMap, scanCalled)
	}
	if _, statErr := os.Stat(songPath); !os.IsNotExist(statErr) {
		t.Fatalf("expected shared song file deleted, got err=%v", statErr)
	}
}

func TestLoadConfigAuthEnvSecretsAndHalfConfigRejection(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	_ = os.MkdirAll(dataDir, 0o755)
	_ = os.MkdirAll(musicRoot, 0o755)

	// 1. Half-configured credentials must be rejected on startup.
	halfCfg := app.LoadConfig([]string{"MELOKEEP_USER=admin"}, app.ConfigOverrides{
		DataDir:   dataDir,
		MusicRoot: musicRoot,
	})
	if halfCfg.ConfigError == "" {
		t.Fatal("expected ConfigError for half-configured MELOKEEP_USER without MELOKEEP_PASS")
	}
	if _, err := app.New(ctx, halfCfg); err == nil {
		t.Fatal("expected app.New to reject half-configured credentials")
	}

	// 2. MELOKEEP_USER + MELOKEEP_PASS_FILE enables auth and enforces login/logout.
	passFile := filepath.Join(tmp, "pass.txt")
	if err := os.WriteFile(passFile, []byte("secret-pass\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := app.LoadConfig([]string{
		"MELOKEEP_USER=admin",
		"MELOKEEP_PASS_FILE=" + passFile,
	}, app.ConfigOverrides{
		DataDir:   dataDir,
		MusicRoot: musicRoot,
	})
	if cfg.ConfigError != "" {
		t.Fatalf("unexpected ConfigError: %s", cfg.ConfigError)
	}
	if cfg.AuthUser != "admin" || cfg.AuthPass != "secret-pass" {
		t.Fatalf("unexpected resolved credentials: user=%q pass=%q", cfg.AuthUser, cfg.AuthPass)
	}

	a, err := app.New(ctx, cfg)
	if err != nil {
		t.Fatalf("app.New with valid MELOKEEP_USER/MELOKEEP_PASS_FILE: %v", err)
	}
	defer a.Close()

	// Unauthenticated /api/config must fail with 401, and /api/auth/status must report required=true, authenticated=false.
	stReq := httptest.NewRequest(http.MethodGet, "/api/auth/status", nil)
	stRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(stRec, stReq)
	if stRec.Code != http.StatusOK || !strings.Contains(stRec.Body.String(), `"required":true`) || !strings.Contains(stRec.Body.String(), `"authenticated":false`) {
		t.Fatalf("unexpected auth status before login: code=%d body=%s", stRec.Code, stRec.Body.String())
	}

	cfgReq := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	cfgRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(cfgRec, cfgReq)
	if cfgRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 before login, got %d", cfgRec.Code)
	}

	loginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass"}`))
	loginReq.Header.Set("Content-Type", "application/json")
	loginReq.Header.Set("X-MeloKeep-Request", "1")
	loginRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(loginRec, loginReq)
	if loginRec.Code != http.StatusOK {
		t.Fatalf("login failed: code=%d body=%s", loginRec.Code, loginRec.Body.String())
	}
	var sessionCookie *http.Cookie
	for _, c := range loginRec.Result().Cookies() {
		if c.Name == auth.SessionCookieName && c.Value != "" {
			sessionCookie = c
		}
	}
	if sessionCookie == nil {
		t.Fatalf("expected non-empty %s cookie after login, got %+v", auth.SessionCookieName, loginRec.Result().Cookies())
	}

	authedReq := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	authedReq.AddCookie(sessionCookie)
	authedRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(authedRec, authedReq)
	if authedRec.Code != http.StatusOK {
		t.Fatalf("expected 200 with session cookie, got %d", authedRec.Code)
	}

	logoutReq := httptest.NewRequest(http.MethodPost, "/api/auth/logout", nil)
	logoutReq.AddCookie(sessionCookie)
	logoutReq.Header.Set("X-MeloKeep-Request", "1")
	logoutRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(logoutRec, logoutReq)
	if logoutRec.Code != http.StatusOK {
		t.Fatalf("logout failed: %d", logoutRec.Code)
	}

	afterLogoutReq := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	afterLogoutReq.AddCookie(sessionCookie)
	afterLogoutRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(afterLogoutRec, afterLogoutReq)
	if afterLogoutRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 after logout, got %d", afterLogoutRec.Code)
	}
}

func TestNavidromeSavePreservesCredentialsAcrossNullSave(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	_ = os.MkdirAll(musicRoot, 0o755)

	cfg := app.LoadConfig(nil, app.ConfigOverrides{
		DataDir:   dataDir,
		MusicRoot: musicRoot,
	})
	a, err := app.New(ctx, cfg)
	if err != nil {
		t.Fatalf("app.New failed: %v", err)
	}
	defer a.Close()

	putNav := func(payload string) model.NavidromeConfigDTO {
		req := httptest.NewRequest(http.MethodPut, "/api/library/navidrome", strings.NewReader(payload))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		a.Handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("PUT /api/library/navidrome failed: status=%d body=%s", rec.Code, rec.Body.String())
		}
		var dto model.NavidromeConfigDTO
		if err := json.Unmarshal(rec.Body.Bytes(), &dto); err != nil {
			t.Fatalf("decode dto: %v", err)
		}
		return dto
	}

	// Step 1: User saves Navidrome config with password and api_key.
	saved := putNav(`{"enabled":true,"url":"https://nav.example.com","username":"alice","password":"secret-password","api_key":"secret-key","auto_scan":true}`)
	if !saved.HasPassword || !saved.HasAPIKey {
		t.Fatalf("expected credentials saved, got %+v", saved)
	}

	// Step 2: User immediately clicks "保存并测试连接" without re-typing secrets (frontend sends null).
	reSaved := putNav(`{"enabled":true,"url":"https://nav.example.com","username":"alice","password":null,"api_key":null,"auto_scan":true}`)
	if !reSaved.HasPassword || !reSaved.HasAPIKey {
		t.Fatalf("null password/api_key must preserve existing credentials, got %+v", reSaved)
	}
	s := a.Navidrome.Settings()
	if s.Password != "secret-password" || s.APIKey != "secret-key" {
		t.Fatalf("credentials wiped after null save: pass=%q key=%q", s.Password, s.APIKey)
	}

	// Step 3: Omitting password/api_key also preserves credentials.
	omitted := putNav(`{"enabled":true,"url":"https://nav.example.com","username":"alice","auto_scan":false}`)
	if !omitted.HasPassword || !omitted.HasAPIKey {
		t.Fatalf("omitted secrets must preserve existing credentials, got %+v", omitted)
	}

	// Step 4: Explicit empty string + clear flags clears credentials.
	cleared := putNav(`{"enabled":true,"url":"https://nav.example.com","username":"alice","password":"","api_key":"","clear_password":true,"clear_api_key":true}`)
	if cleared.HasPassword || cleared.HasAPIKey {
		t.Fatalf("expected credentials cleared, got %+v", cleared)
	}
}

func TestBotSourcesReuseWebConfigAndOrder(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	cfg := app.LoadConfig(nil, app.ConfigOverrides{
		DataDir:   filepath.Join(tmp, "data"),
		MusicRoot: filepath.Join(tmp, "music"),
	})
	a, err := app.New(ctx, cfg)
	if err != nil {
		t.Fatalf("app.New failed: %v", err)
	}
	defer a.Close()

	// All providers in registry exceed 2, but Web default Sources should strictly constrain Bot search sources.
	totalAvail := len(a.Providers.AvailableSources())
	if totalAvail < 7 {
		t.Fatalf("expected built-in provider registry to have at least 7 sources, got %d", totalAvail)
	}

	// Set Web sources explicitly via PUT /api/config to ["AudiusMusicClient", "FMAMusicClient"] (only 2 enabled).
	req := httptest.NewRequest(http.MethodPut, "/api/config", strings.NewReader(`{"sources":["AudiusMusicClient","FMAMusicClient"]}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-MeloKeep-Request", "1")
	rec := httptest.NewRecorder()
	a.Handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("PUT /api/config failed: %d %s", rec.Code, rec.Body.String())
	}

	// Enable TG bot with blank bot_sources (follow Web config).
	tgReq := httptest.NewRequest(http.MethodPut, "/api/bots/config", strings.NewReader(`{"tg_enabled":true,"tg_token":"123456:tok","tg_allowed_users":"1001","bot_sources":""}`))
	tgReq.Header.Set("Content-Type", "application/json")
	tgReq.Header.Set("X-MeloKeep-Request", "1")
	tgRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(tgRec, tgReq)
	if tgRec.Code != http.StatusOK {
		t.Fatalf("PUT /api/bots/config failed: %d %s", tgRec.Code, tgRec.Body.String())
	}

	// Trigger TG inbound search message and inspect stored bot_search & outbox buttons.
	if err := a.Bot.ExecuteInbound(ctx, bot.InboundMessage{
		ID:            "feedface12345678",
		Channel:       bot.ChannelTelegram,
		User:          "1001",
		PlatformMsgID: "tg-msg-1",
		Text:          "Clear Sky",
		CreatedAt:     time.Now().Unix(),
	}); err != nil {
		t.Fatalf("ExecuteInbound failed: %v", err)
	}

	var payloadJSON string
	if err := a.Store.Reader().QueryRowContext(ctx, `
		SELECT payload_json FROM outbox WHERE channel = 'tg' AND recipient_key = '1001' ORDER BY created_at ASC LIMIT 1
	`).Scan(&payloadJSON); err != nil {
		t.Fatalf("query outbox failed: %v", err)
	}
	var payload bot.OutboxPayload
	if err := json.Unmarshal([]byte(payloadJSON), &payload); err != nil {
		t.Fatalf("decode OutboxPayload failed: %v", err)
	}
	var srcTexts []string
	for _, row := range payload.Buttons {
		for _, btn := range row {
			if strings.HasPrefix(btn.CallbackData, "/source ") {
				srcTexts = append(srcTexts, btn.Text)
			}
		}
	}
	if len(srcTexts) != 2 || !strings.Contains(srcTexts[0], "Audius") || srcTexts[1] != "FMA" {
		t.Fatalf("expected bot TG source buttons to reuse Web config ['Audius','FMA'], got %v", srcTexts)
	}
}

func TestBotResolveDownloadFileAssetTokenAndOrganizedMove(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	cfg := app.LoadConfig(nil, app.ConfigOverrides{
		DataDir:   dataDir,
		MusicRoot: musicRoot,
	})
	a, err := app.New(ctx, cfg)
	if err != nil {
		t.Fatalf("app.New failed: %v", err)
	}
	defer a.Close()

	// Configure Bot public_url so signed links can be issued.
	botReq := httptest.NewRequest(http.MethodPut, "/api/bots/config", strings.NewReader(`{"public_url":"https://music.example.com","tg_enabled":true,"tg_token":"123456:tok","tg_allowed_users":"1001"}`))
	botReq.Header.Set("Content-Type", "application/json")
	botReq.Header.Set("X-MeloKeep-Request", "1")
	botRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(botRec, botReq)
	if botRec.Code != http.StatusOK {
		t.Fatalf("PUT /api/bots/config failed: %d %s", botRec.Code, botRec.Body.String())
	}

	// Create provider track, public ref, and queued download row.
	trID, err := a.Store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source:     "AudiusMusicClient",
		Locator:    "bot-asset-track-1",
		RootSource: "AudiusMusicClient",
		Title:      "Demo Track",
		Artist:     "Open Artist",
		Album:      "Demo Album",
	}, nil)
	if err != nil {
		t.Fatalf("UpsertProviderTrack: %v", err)
	}
	pubID := "pub-bot-asset-1"
	if err := a.Store.PutPublicRef(ctx, model.PublicRef{
		PublicID: pubID,
		Kind:     "candidate",
		TargetID: trID,
		Source:   "AudiusMusicClient",
		SourceID: "bot-asset-track-1",
	}, ""); err != nil {
		t.Fatalf("PutPublicRef: %v", err)
	}
	dlRes, err := a.Store.CreateDownload(ctx, store.CreateDownloadParams{
		ID:             "dl-bot-asset-1",
		TrackID:        trID,
		PublicID:       pubID,
		QualityID:      "mp3",
		QualityLabel:   "MP3",
		DownloadSource: "AudiusMusicClient",
		ActorKey:       "tg:1001",
		DedupeKey:      "dedupe-bot-asset-1",
	})
	if err != nil {
		t.Fatalf("CreateDownload: %v", err)
	}

	pubDL, err := a.Store.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID:               dlRes.Download.ID,
		ExpectedRevision: dlRes.Download.Revision,
		AllowedStates:    []model.DownloadStatus{model.DownloadQueued},
		NewState:         model.DownloadPublishing,
	})
	if err != nil {
		t.Fatalf("CompareAndSetDownload: %v", err)
	}

	stagePath := filepath.Join(dataDir, "staging", "dl-bot-asset-1.wav")
	if err := os.MkdirAll(filepath.Dir(stagePath), 0o755); err != nil {
		t.Fatalf("mkdir staging: %v", err)
	}
	wantBytes := writeMinimalWAV(t, stagePath)
	publishedTarget := filepath.Join(musicRoot, "Open Artist - Demo Track.wav")

	pubResult, err := a.Files.Execute(ctx, files.OperationRequest{
		Kind:       "download_publish",
		SourcePath: stagePath,
		TargetPath: publishedTarget,
		NewTitle:   "Demo Track",
		NewArtist:  "Open Artist",
		NewAlbum:   "Demo Album",
		Editable:   true,
		DownloadPublish: &files.DownloadPublishSpec{
			DownloadID:       pubDL.ID,
			ExpectedRevision: pubDL.Revision,
			TrackID:          trID,
			PublicID:         pubID,
			Source:           "AudiusMusicClient",
			SourceID:         "bot-asset-track-1",
			ActorKey:         "tg:1001",
		},
	})
	if err != nil {
		t.Fatalf("download_publish failed: %v", err)
	}

	dlRow, ok, err := a.Store.GetDownload(ctx, dlRes.Download.ID)
	if err != nil || !ok {
		t.Fatalf("GetDownload: ok=%v err=%v", ok, err)
	}
	if dlRow.File == nil || !strings.HasPrefix(*dlRow.File, "asset:") {
		t.Fatalf("expected file token to use asset:<id>, got %#v", dlRow.File)
	}

	links := a.Bot.Links()
	if links == nil {
		t.Fatal("expected Bot.Links() to be initialized")
	}
	signedURL, err := links.Create(ctx, model.DownloadJobDTO{
		ID:     dlRow.ID,
		Status: "complete",
		File:   dlRow.File,
		Track:  model.TrackDTO{Title: "Demo Track", Artist: "Open Artist"},
	}, time.Now())
	if err != nil {
		t.Fatalf("Links.Create failed: %v", err)
	}
	reqPath := strings.TrimPrefix(signedURL, "https://music.example.com")

	getReq := httptest.NewRequest(http.MethodGet, reqPath, nil)
	getRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(getRec, getReq)
	if getRec.Code != http.StatusOK {
		t.Fatalf("signed download before move failed: %d %s", getRec.Code, getRec.Body.String())
	}
	if !bytes.Equal(getRec.Body.Bytes(), wantBytes) {
		t.Fatalf("downloaded bytes mismatch before move: got %d want %d", getRec.Body.Len(), len(wantBytes))
	}

	// Simulate scrape/organize moving the asset into a nested folder.
	if pubResult.Asset == nil {
		t.Fatal("expected download_publish result to include Asset")
	}
	curAsset, ok, err := a.Store.GetAsset(ctx, pubResult.Asset.ID)
	if err != nil || !ok {
		t.Fatalf("GetAsset: ok=%v err=%v", ok, err)
	}
	movedTarget := filepath.Join(musicRoot, "Open Artist", "Demo Album", "Demo Track.wav")
	if _, err := a.Files.Execute(ctx, files.OperationRequest{
		Kind:             "move",
		AssetID:          curAsset.ID,
		ExpectedRevision: curAsset.Revision,
		SourcePath:       publishedTarget,
		TargetPath:       movedTarget,
		NewTitle:         "Demo Track",
		NewArtist:        "Open Artist",
		NewAlbum:         "Demo Album",
		Editable:         true,
	}); err != nil {
		t.Fatalf("move organized asset failed: %v", err)
	}

	// Re-issuing a link with the unchanged asset:<id> token resolves the organized target path.
	movedSignedURL, err := links.Create(ctx, model.DownloadJobDTO{
		ID:     dlRow.ID,
		Status: "complete",
		File:   dlRow.File,
		Track:  model.TrackDTO{Title: "Demo Track", Artist: "Open Artist"},
	}, time.Now())
	if err != nil {
		t.Fatalf("Links.Create after move failed: %v", err)
	}
	movedReqPath := strings.TrimPrefix(movedSignedURL, "https://music.example.com")
	getMovedReq := httptest.NewRequest(http.MethodGet, movedReqPath, nil)
	getMovedRec := httptest.NewRecorder()
	a.Handler.ServeHTTP(getMovedRec, getMovedReq)
	if getMovedRec.Code != http.StatusOK {
		t.Fatalf("signed download after move failed: %d %s", getMovedRec.Code, getMovedRec.Body.String())
	}
	if !bytes.Equal(getMovedRec.Body.Bytes(), wantBytes) {
		t.Fatalf("downloaded bytes mismatch after move: got %d want %d", getMovedRec.Body.Len(), len(wantBytes))
	}
}

func TestLibraryCoverVisibleAfterDownloadPublishAndOrganize(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	cfg := app.LoadConfig(nil, app.ConfigOverrides{
		DataDir:   dataDir,
		MusicRoot: musicRoot,
	})
	a, err := app.New(ctx, cfg)
	if err != nil {
		t.Fatalf("app.New failed: %v", err)
	}
	defer a.Close()

	// Build a minimal FLAC containing a valid embedded PNG PICTURE block.
	pngBytes := []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 1, 0, 0, 0, 1}
	var picBuf bytes.Buffer
	picBuf.Write([]byte{0, 0, 0, 3}) // picture type = front cover
	mime := "image/png"
	picBuf.Write([]byte{0, 0, 0, byte(len(mime))})
	picBuf.WriteString(mime)
	picBuf.Write([]byte{0, 0, 0, 0}) // description length = 0
	picBuf.Write(make([]byte, 16))   // width, height, depth, colors
	picBuf.Write([]byte{0, 0, 0, byte(len(pngBytes))})
	picBuf.Write(pngBytes)
	pic := picBuf.Bytes()
	var flacBuf bytes.Buffer
	flacBuf.WriteString("fLaC")
	flacBuf.Write([]byte{0x80 | 6, byte(len(pic) >> 16), byte(len(pic) >> 8), byte(len(pic))})
	flacBuf.Write(pic)

	trID, err := a.Store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source:     "AudiusMusicClient",
		Locator:    "lib-cover-track-1",
		RootSource: "AudiusMusicClient",
		Title:      "Sample Song",
		Artist:     "Sample Artist",
		Album:      "Sample Album",
	}, nil)
	if err != nil {
		t.Fatalf("UpsertProviderTrack: %v", err)
	}
	pubID := "pub-lib-cover-1"
	if err := a.Store.PutPublicRef(ctx, model.PublicRef{
		PublicID: pubID,
		Kind:     "candidate",
		TargetID: trID,
		Source:   "AudiusMusicClient",
		SourceID: "lib-cover-track-1",
	}, ""); err != nil {
		t.Fatalf("PutPublicRef: %v", err)
	}
	dlRes, err := a.Store.CreateDownload(ctx, store.CreateDownloadParams{
		ID:             "dl-lib-cover-1",
		TrackID:        trID,
		PublicID:       pubID,
		QualityID:      "flac",
		QualityLabel:   "FLAC",
		DownloadSource: "AudiusMusicClient",
		ActorKey:       "web:admin",
		DedupeKey:      "dedupe-lib-cover-1",
	})
	if err != nil {
		t.Fatalf("CreateDownload: %v", err)
	}
	pubDL, err := a.Store.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID:               dlRes.Download.ID,
		ExpectedRevision: dlRes.Download.Revision,
		AllowedStates:    []model.DownloadStatus{model.DownloadQueued},
		NewState:         model.DownloadPublishing,
	})
	if err != nil {
		t.Fatalf("CompareAndSetDownload: %v", err)
	}

	stagePath := filepath.Join(dataDir, "staging", "dl-lib-cover-1.flac")
	if err := os.MkdirAll(filepath.Dir(stagePath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(stagePath, flacBuf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	publishedTarget := filepath.Join(musicRoot, "Sample Song.flac")

	// Intentionally pass NewMetadata without _has_cover (exact download_publish behavior).
	pubResult, err := a.Files.Execute(ctx, files.OperationRequest{
		Kind:       "download_publish",
		SourcePath: stagePath,
		TargetPath: publishedTarget,
		NewTitle:   "Sample Song",
		NewArtist:  "Sample Artist",
		NewAlbum:   "Sample Album",
		Editable:   true,
		NewMetadata: map[string]string{
			"title":  "Sample Song",
			"artist": "Sample Artist",
			"album":  "Sample Album",
		},
		DownloadPublish: &files.DownloadPublishSpec{
			DownloadID:       pubDL.ID,
			ExpectedRevision: pubDL.Revision,
			TrackID:          trID,
			PublicID:         pubID,
			Source:           "AudiusMusicClient",
			SourceID:         "lib-cover-track-1",
			ActorKey:         "web:admin",
		},
	})
	if err != nil {
		t.Fatalf("download_publish failed: %v", err)
	}

	assertCoverVisible := func(stage string) {
		t.Helper()
		listReq := httptest.NewRequest(http.MethodGet, "/api/library/files?limit=20", nil)
		listRec := httptest.NewRecorder()
		a.Handler.ServeHTTP(listRec, listReq)
		if listRec.Code != http.StatusOK {
			t.Fatalf("%s GET /api/library/files failed: %d %s", stage, listRec.Code, listRec.Body.String())
		}
		var page model.LibraryFilesPageDTO
		if err := json.Unmarshal(listRec.Body.Bytes(), &page); err != nil {
			t.Fatalf("%s decode LibraryFilesPageDTO failed: %v", stage, err)
		}
		if len(page.Items) != 1 || page.Items[0].Track == nil || page.Items[0].Track.Cover == nil || *page.Items[0].Track.Cover == "" {
			t.Fatalf("%s expected Track.Cover URL immediately without manual rescan, got %#v", stage, page.Items)
		}
		covReq := httptest.NewRequest(http.MethodGet, *page.Items[0].Track.Cover, nil)
		covRec := httptest.NewRecorder()
		a.Handler.ServeHTTP(covRec, covReq)
		if covRec.Code != http.StatusOK || !bytes.Equal(covRec.Body.Bytes(), pngBytes) {
			t.Fatalf("%s GET %s failed: code=%d len=%d", stage, *page.Items[0].Track.Cover, covRec.Code, covRec.Body.Len())
		}
	}

	assertCoverVisible("after download_publish")

	// Simulate auto-organize moving the file and replacing NewMetadata without _has_cover.
	curAsset, ok, err := a.Store.GetAsset(ctx, pubResult.Asset.ID)
	if err != nil || !ok {
		t.Fatalf("GetAsset: ok=%v err=%v", ok, err)
	}
	organizedTarget := filepath.Join(musicRoot, "library", "Sample Artist", "Sample Album", "01 - Sample Song.flac")
	if err := os.MkdirAll(filepath.Dir(organizedTarget), 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := a.Files.Execute(ctx, files.OperationRequest{
		Kind:             "move",
		AssetID:          curAsset.ID,
		ExpectedRevision: curAsset.Revision,
		SourcePath:       publishedTarget,
		TargetPath:       organizedTarget,
		NewTitle:         "Sample Song",
		NewArtist:        "Sample Artist",
		NewAlbum:         "Sample Album",
		Editable:         true,
		NewMetadata: map[string]string{
			"title":        "Sample Song",
			"artist":       "Sample Artist",
			"album":        "Sample Album",
			"track_number": "1",
		},
	}); err != nil {
		t.Fatalf("move organized asset failed: %v", err)
	}

	assertCoverVisible("after auto-organize move")
}

func TestStartupToleratesInvalidStoredBotConfigAndPlaceholderPassword(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	_ = os.MkdirAll(dataDir, 0o755)
	_ = os.MkdirAll(musicRoot, 0o755)

	st, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(dataDir)})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	rawInvalidBot := `{"tg_enabled":true,"tg_token":"legacy-tok","tg_allowed_users":""}`
	if _, err := st.PutDurableRecord(ctx, "bot_setting", "config", rawInvalidBot, 0); err != nil {
		t.Fatalf("PutDurableRecord: %v", err)
	}
	_ = st.Close()

	cfg := app.LoadConfig([]string{
		"MELOKEEP_USER=admin",
		"MELOKEEP_PASS=change_me_please",
	}, app.ConfigOverrides{
		DataDir:   dataDir,
		MusicRoot: musicRoot,
	})
	cfg.AllowTestLoopback = true
	if cfg.ConfigError != "" {
		t.Fatalf("expected placeholder password to warn rather than block startup, got ConfigError=%q", cfg.ConfigError)
	}
	if cfg.ConfigWarning == "" {
		t.Fatal("expected non-empty ConfigWarning for placeholder password")
	}

	a, err := app.New(ctx, cfg)
	if err != nil {
		t.Fatalf("expected app.New to degrade gracefully instead of crashing on invalid stored bot config, got %v", err)
	}
	defer a.Close()

	if a.Bot.Config().TGEnabled {
		t.Fatal("expected fallback bot config to disable invalid TGEnabled channel at runtime")
	}
	rec, ok, err := a.Store.GetDurableRecord(ctx, "bot_setting", "config")
	if err != nil || !ok || rec.BodyJSON != rawInvalidBot {
		t.Fatalf("expected original DB bot_setting record to remain intact for UI edit, ok=%v err=%v", ok, err)
	}
}

func TestAppDeviceTokenAndMediaTokenAcrossRestart(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	_ = os.MkdirAll(dataDir, 0o755)
	_ = os.MkdirAll(musicRoot, 0o755)

	wavBytes := writeMinimalWAV(t, filepath.Join(musicRoot, "sample.wav"))

	cfg := app.LoadConfig([]string{
		"MELOKEEP_USER=admin",
		"MELOKEEP_PASS=app-secret-123",
	}, app.ConfigOverrides{
		DataDir:   dataDir,
		MusicRoot: musicRoot,
	})

	a1, err := app.New(ctx, cfg)
	if err != nil {
		t.Fatalf("app.New #1: %v", err)
	}
	loginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"app-secret-123","client_type":"app","device_name":"PixelTest"}`))
	loginReq.Header.Set("Content-Type", "application/json")
	loginReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	loginRec := httptest.NewRecorder()
	a1.Handler.ServeHTTP(loginRec, loginReq)
	if loginRec.Code != http.StatusOK {
		_ = a1.Close()
		t.Fatalf("app login failed: %d %s", loginRec.Code, loginRec.Body.String())
	}
	var loginResp struct {
		Token      string `json:"token"`
		TokenID    string `json:"token_id"`
		MediaToken string `json:"media_token"`
	}
	if err := json.Unmarshal(loginRec.Body.Bytes(), &loginResp); err != nil || loginResp.Token == "" || loginResp.MediaToken == "" {
		_ = a1.Close()
		t.Fatalf("unexpected app login payload: %s err=%v", loginRec.Body.String(), err)
	}
	_ = a1.Close()

	// Restart app instance on the same data directory and verify persisted device & media tokens.
	a2, err := app.New(ctx, cfg)
	if err != nil {
		t.Fatalf("app.New #2: %v", err)
	}
	defer a2.Close()

	devReq := httptest.NewRequest(http.MethodGet, "/api/auth/devices", nil)
	devReq.Header.Set("Authorization", "Bearer "+loginResp.Token)
	devRec := httptest.NewRecorder()
	a2.Handler.ServeHTTP(devRec, devReq)
	if devRec.Code != http.StatusOK || !strings.Contains(devRec.Body.String(), "PixelTest") {
		t.Fatalf("expected persisted device across restart, got code=%d body=%s", devRec.Code, devRec.Body.String())
	}

	audioReq := httptest.NewRequest(http.MethodGet, "/api/files/audio?path=sample.wav&media_token="+loginResp.MediaToken, nil)
	audioRec := httptest.NewRecorder()
	a2.Handler.ServeHTTP(audioRec, audioReq)
	if audioRec.Code != http.StatusOK || !bytes.Equal(audioRec.Body.Bytes(), wavBytes) {
		t.Fatalf("expected persisted media_token to stream audio across restart, code=%d len=%d", audioRec.Code, audioRec.Body.Len())
	}
}

func startupPluginScript(id string) string {
	return strings.ReplaceAll(`
module.exports = {
	manifest: {
		apiVersion: 1,
		id: "__ID__",
		name: "Startup Demo",
		version: "1.0.0",
		capabilities: { search: true },
	},
	search(ctx, opts) {
		return [{ sourceId: "s-1", title: "Startup Song", artist: "Open Artist" }];
	},
	resolve(ctx, track, policy) { return []; },
};
`, "__ID__", id)
}

func putRuntimeSources(t *testing.T, dataDir string, sources []string) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(dataDir)})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	defer st.Close()
	rt := config.DefaultRuntimeConfig()
	rt.Sources = sources
	raw, err := json.Marshal(rt)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.PutSetting(ctx, store.NamespaceRuntimeConfig, string(raw), 0); err != nil {
		t.Fatalf("PutSetting runtime config: %v", err)
	}
}

func TestStartupHotLoadsPluginsAndSanitizesLegacySources(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	pluginDir := filepath.Join(dataDir, "go", "plugins")
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		t.Fatal(err)
	}

	// Seed: one enabled plugin, one disabled plugin, one plugin whose script is later corrupted.
	st, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(dataDir)})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	pool := transport.NewPool()
	seedReg, err := catalog.NewFullRegistry(catalog.Options{Pool: pool})
	if err != nil {
		t.Fatal(err)
	}
	mgr := plugin.NewManager(st, pool, seedReg, pluginDir, nil)
	disabled := false
	for _, req := range []plugin.InstallRequest{
		{Code: startupPluginScript("startup-demo")},
		{Code: startupPluginScript("startup-off"), Enabled: &disabled},
		{Code: startupPluginScript("startup-broken")},
	} {
		if _, err := mgr.Install(ctx, req); err != nil {
			t.Fatalf("seed Install: %v", err)
		}
	}
	_ = st.Close()
	if err := os.WriteFile(filepath.Join(pluginDir, "startup-broken.js"), []byte("not a plugin ((("), 0o600); err != nil {
		t.Fatal(err)
	}

	// Legacy runtime config: retired sources + a plugin source + duplicates + a built-in source.
	putRuntimeSources(t, dataDir, []string{"RetiredLegacyClient", "startup-demo", "AudiusMusicClient", "startup-demo"})

	a, err := app.New(ctx, app.Config{DataDir: dataDir, MusicRoots: []string{musicRoot}})
	if err != nil {
		t.Fatalf("app.New with installed plugins must not fail: %v", err)
	}
	if a.Plugins == nil {
		t.Fatal("expected App.Plugins to be initialized")
	}
	if _, ok := a.Providers.Get("startup-demo"); !ok {
		t.Fatal("enabled plugin must be hot-registered before startup completes")
	}
	if _, ok := a.Providers.Get("startup-off"); ok {
		t.Fatal("disabled plugin must not be registered")
	}
	if _, ok := a.Providers.Get("startup-broken"); ok {
		t.Fatal("plugin with a corrupted script must not be registered")
	}
	wantSources := []string{"startup-demo", "AudiusMusicClient"}
	if got := a.Snapshot().Runtime.Sources; !reflect.DeepEqual(got, wantSources) {
		t.Fatalf("expected legacy sources filtered to %v, got %v", wantSources, got)
	}

	// Plugin API is wired into HTTP and reports the isolated load failure.
	req := httptest.NewRequest(http.MethodGet, "/api/plugins", nil)
	rec := httptest.NewRecorder()
	a.Handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/plugins failed: %d %s", rec.Code, rec.Body.String())
	}
	var listResp struct {
		Plugins []model.ProviderPluginDTO `json:"plugins"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &listResp); err != nil || len(listResp.Plugins) != 3 {
		t.Fatalf("expected 3 plugins in list, err=%v body=%s", err, rec.Body.String())
	}
	for _, p := range listResp.Plugins {
		if p.ID == "startup-broken" && p.LastError == "" {
			t.Fatalf("expected last_error recorded for broken plugin: %+v", p)
		}
		if p.ID == "startup-demo" && p.LastError != "" {
			t.Fatalf("healthy plugin must not carry last_error: %+v", p)
		}
	}
	_ = a.Close()

	// Legacy config referencing only retired sources falls back to compliant defaults.
	putRuntimeSources(t, dataDir, []string{"RetiredLegacyClient", "AnotherRetiredClient"})
	a2, err := app.New(ctx, app.Config{DataDir: dataDir, MusicRoots: []string{musicRoot}})
	if err != nil {
		t.Fatalf("app.New with legacy config: %v", err)
	}
	defer a2.Close()
	if got, want := a2.Snapshot().Runtime.Sources, config.DefaultRuntimeConfig().Sources; !reflect.DeepEqual(got, want) {
		t.Fatalf("expected fallback to default sources %v, got %v", want, got)
	}
	foundLog := false
	for _, e := range a2.Logs.List() {
		if strings.Contains(e.Message, "默认音源") {
			foundLog = true
		}
	}
	if !foundLog {
		t.Fatal("expected a runtime log entry about falling back to default sources")
	}
}
