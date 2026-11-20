package httpapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

type stubNavidromeMediaGateway struct {
	coverBytes  []byte
	streamBytes []byte
}

func (g *stubNavidromeMediaGateway) GetPublicConfig(context.Context) (model.NavidromeConfigDTO, error) {
	return model.NavidromeConfigDTO{Enabled: true}, nil
}
func (g *stubNavidromeMediaGateway) SaveConfig(context.Context, auth.Actor, map[string]any) (model.NavidromeConfigDTO, error) {
	return model.NavidromeConfigDTO{}, nil
}
func (g *stubNavidromeMediaGateway) Capabilities(context.Context) (map[string]any, error) {
	return map[string]any{"enabled": true}, nil
}
func (g *stubNavidromeMediaGateway) DeletePreview(context.Context, string) (map[string]any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) DeleteSong(context.Context, string, string) (map[string]any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) ListPlaylists(context.Context) (any, error) { return nil, nil }
func (g *stubNavidromeMediaGateway) GetPlaylist(context.Context, string) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) CreatePlaylist(context.Context, auth.Actor, map[string]any) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) RenamePlaylist(context.Context, auth.Actor, string, map[string]any) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) DeletePlaylist(context.Context, auth.Actor, string) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) AddPlaylistTracks(context.Context, auth.Actor, string, map[string]any) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) RemovePlaylistTracks(context.Context, auth.Actor, string, map[string]any) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) ListSongs(context.Context, int, int) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) GetSong(context.Context, string) (model.TrackDTO, error) {
	return model.TrackDTO{}, nil
}
func (g *stubNavidromeMediaGateway) ListAlbums(context.Context, int, int, string) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) GetAlbum(context.Context, string) (any, error) { return nil, nil }
func (g *stubNavidromeMediaGateway) ListArtists(context.Context) (any, error)      { return nil, nil }
func (g *stubNavidromeMediaGateway) GetArtist(context.Context, string) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) Search(context.Context, string, int) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) ProxyCover(w http.ResponseWriter, r *http.Request, _ string) error {
	w.Header().Set("Content-Type", "image/png")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(g.coverBytes)
	}
	return nil
}
func (g *stubNavidromeMediaGateway) ProxyStream(w http.ResponseWriter, r *http.Request, _ string) error {
	http.ServeContent(w, r, "nav.wav", time.Unix(1700000000, 0), bytes.NewReader(g.streamBytes))
	return nil
}
func (g *stubNavidromeMediaGateway) OpenDownload(_ context.Context, _ string, _ string) (int, http.Header, io.ReadCloser, error) {
	hdr := http.Header{
		"Content-Type":   {"audio/wav"},
		"Content-Length": {strconv.Itoa(len(g.streamBytes))},
	}
	return http.StatusOK, hdr, io.NopCloser(bytes.NewReader(g.streamBytes)), nil
}
func (g *stubNavidromeMediaGateway) TestConnection(context.Context) (any, error) { return nil, nil }
func (g *stubNavidromeMediaGateway) ScanStatus(context.Context) (any, error)     { return nil, nil }
func (g *stubNavidromeMediaGateway) StartScan(context.Context, auth.Actor) (any, error) {
	return nil, nil
}
func (g *stubNavidromeMediaGateway) GetLyrics(context.Context, string, string, string) string {
	return ""
}

func makeAuthDependencies(st *store.Store, user, pass string, authEnabled bool) Dependencies {
	deps := Dependencies{
		Store:          st,
		Sessions:       auth.NewSessionManagerWithStore(st),
		RateLimiter:    auth.NewLoginRateLimiter(),
		AuthEnabled:    authEnabled,
		AuthUserDigest: sha256.Sum256([]byte(user)),
		AuthPassDigest: sha256.Sum256([]byte(pass)),
	}
	return deps
}

func TestWebLoginUnchangedResponse(t *testing.T) {
	for _, authEnabled := range []bool{false, true} {
		srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
			d.AuthEnabled = authEnabled
			d.AuthUserDigest = sha256.Sum256([]byte("admin"))
			d.AuthPassDigest = sha256.Sum256([]byte("secret"))
		})
		req := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret"}`))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("authEnabled=%v web login status=%d body=%s", authEnabled, rec.Code, rec.Body.String())
		}
		var payload map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
			t.Fatalf("decode login response: %v", err)
		}
		if len(payload) != 1 || payload["success"] != true {
			t.Fatalf("authEnabled=%v expected exact {\"success\":true}, got %v", authEnabled, payload)
		}
	}
}

func TestAppLoginPersistenceDevicesAndLogoutRevoke(t *testing.T) {
	// 1. Auth disabled + client_type=app allows empty username/password.
	srvNoAuth, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.AuthEnabled = false
	})
	noAuthReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"client_type":"app","device_name":"Android"}`))
	noAuthReq.Header.Set("Content-Type", "application/json")
	noAuthReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	noAuthRec := httptest.NewRecorder()
	srvNoAuth.ServeHTTP(noAuthRec, noAuthReq)
	if noAuthRec.Code != http.StatusOK {
		t.Fatalf("no-auth app login failed: %d %s", noAuthRec.Code, noAuthRec.Body.String())
	}
	var noAuthResp map[string]any
	_ = json.Unmarshal(noAuthRec.Body.Bytes(), &noAuthResp)
	if noAuthResp["success"] != true || noAuthResp["auth_enabled"] != false || noAuthResp["token"] != "" || noAuthResp["media_token"] != "" {
		t.Fatalf("unexpected no-auth app login response: %+v", noAuthResp)
	}

	// 2. Auth enabled + client_type=app issues persistent device token + media_token.
	tmp := t.TempDir()
	dbPath := filepath.Join(tmp, "melokeep.db")
	ctx := context.Background()
	st, err := store.Open(ctx, store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	defer st.Close()

	srv1, err := NewServer(makeAuthDependencies(st, "admin", "secret-pass", true))
	if err != nil {
		t.Fatalf("NewServer 1: %v", err)
	}

	// Wrong password fails with 401.
	badLogin := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"wrong","client_type":"app","device_name":"Pixel 9"}`))
	badLogin.Header.Set("Content-Type", "application/json")
	badLogin.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	badRec := httptest.NewRecorder()
	srv1.ServeHTTP(badRec, badLogin)
	if badRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for bad app password, got %d", badRec.Code)
	}

	// Valid app login succeeds.
	loginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass","client_type":"app","device_name":"Pixel 9"}`))
	loginReq.Header.Set("Content-Type", "application/json")
	loginReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	loginRec := httptest.NewRecorder()
	srv1.ServeHTTP(loginRec, loginReq)
	if loginRec.Code != http.StatusOK {
		t.Fatalf("app login failed: %d %s", loginRec.Code, loginRec.Body.String())
	}
	var loginResp struct {
		Success             bool   `json:"success"`
		AuthEnabled         bool   `json:"auth_enabled"`
		Token               string `json:"token"`
		TokenID             string `json:"token_id"`
		ExpiresAt           int64  `json:"expires_at"`
		MediaToken          string `json:"media_token"`
		MediaTokenExpiresAt int64  `json:"media_token_expires_at"`
	}
	if err := json.Unmarshal(loginRec.Body.Bytes(), &loginResp); err != nil {
		t.Fatalf("decode app login response: %v", err)
	}
	if !loginResp.Success || !loginResp.AuthEnabled || loginResp.Token == "" || len(loginResp.TokenID) != 16 || loginResp.ExpiresAt <= 0 || loginResp.MediaToken == "" || loginResp.MediaTokenExpiresAt <= 0 {
		t.Fatalf("unexpected app login payload: %+v", loginResp)
	}

	// Verify DB never stores raw bearer token in durable_records.
	dbRow, ok, err := st.GetDurableRecord(ctx, store.NamespaceAuthDeviceToken, loginResp.TokenID)
	if err != nil || !ok {
		t.Fatalf("GetDurableRecord: ok=%v err=%v", ok, err)
	}
	if strings.Contains(dbRow.BodyJSON, loginResp.Token) {
		t.Fatalf("durable_records must never contain plaintext token!")
	}

	// 3. Simulate server restart by reopening SQLite store and building a fresh Server/SessionManager.
	_ = st.Close()
	stRestarted, err := store.Open(ctx, store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("reopen store: %v", err)
	}
	defer stRestarted.Close()

	srv2, err := NewServer(makeAuthDependencies(stRestarted, "admin", "secret-pass", true))
	if err != nil {
		t.Fatalf("NewServer 2: %v", err)
	}

	// /api/auth/status with persisted Bearer returns authenticated=true and media_token.
	statusReq := httptest.NewRequest(http.MethodGet, "/api/auth/status", nil)
	statusReq.Header.Set("Authorization", "Bearer "+loginResp.Token)
	statusRec := httptest.NewRecorder()
	srv2.ServeHTTP(statusRec, statusReq)
	if statusRec.Code != http.StatusOK {
		t.Fatalf("GET /api/auth/status failed: %d %s", statusRec.Code, statusRec.Body.String())
	}
	var statusResp map[string]any
	_ = json.Unmarshal(statusRec.Body.Bytes(), &statusResp)
	if statusResp["authenticated"] != true || statusResp["required"] != true || statusResp["media_token"] == "" {
		t.Fatalf("unexpected auth status payload with Bearer after restart: %+v", statusResp)
	}

	// POST /api/auth/media-token without CSRF header (Bearer exempt) refreshes media_token.
	mtReq := httptest.NewRequest(http.MethodPost, "/api/auth/media-token", nil)
	mtReq.Header.Set("Authorization", "Bearer "+loginResp.Token)
	mtRec := httptest.NewRecorder()
	srv2.ServeHTTP(mtRec, mtReq)
	if mtRec.Code != http.StatusOK {
		t.Fatalf("POST /api/auth/media-token failed: %d %s", mtRec.Code, mtRec.Body.String())
	}
	var mtResp struct {
		MediaToken string `json:"media_token"`
		ExpiresAt  int64  `json:"expires_at"`
	}
	_ = json.Unmarshal(mtRec.Body.Bytes(), &mtResp)
	if mtResp.MediaToken == "" || mtResp.ExpiresAt <= 0 {
		t.Fatalf("invalid media-token response: %+v", mtResp)
	}

	// Login second device to test listing + selective delete + logout revoke.
	login2Req := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass","client_type":"app","device_name":"iPad"}`))
	login2Req.Header.Set("Content-Type", "application/json")
	login2Req.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	login2Rec := httptest.NewRecorder()
	srv2.ServeHTTP(login2Rec, login2Req)
	var login2Resp struct {
		Token   string `json:"token"`
		TokenID string `json:"token_id"`
	}
	_ = json.Unmarshal(login2Rec.Body.Bytes(), &login2Resp)

	devReq := httptest.NewRequest(http.MethodGet, "/api/auth/devices", nil)
	devReq.Header.Set("Authorization", "Bearer "+loginResp.Token)
	devRec := httptest.NewRecorder()
	srv2.ServeHTTP(devRec, devReq)
	if devRec.Code != http.StatusOK {
		t.Fatalf("GET /api/auth/devices failed: %d %s", devRec.Code, devRec.Body.String())
	}
	if strings.Contains(devRec.Body.String(), "token_hash") {
		t.Fatalf("devices endpoint leaked token_hash: %s", devRec.Body.String())
	}
	var devList struct {
		Devices []DeviceTokenDTO `json:"devices"`
	}
	_ = json.Unmarshal(devRec.Body.Bytes(), &devList)
	if len(devList.Devices) != 2 {
		t.Fatalf("expected 2 devices, got %+v", devList.Devices)
	}

	// Delete second device via DELETE /api/auth/devices/{ident} without CSRF (Bearer auth).
	delReq := httptest.NewRequest(http.MethodDelete, "/api/auth/devices/"+login2Resp.TokenID, nil)
	delReq.Header.Set("Authorization", "Bearer "+loginResp.Token)
	delRec := httptest.NewRecorder()
	srv2.ServeHTTP(delRec, delReq)
	if delRec.Code != http.StatusOK {
		t.Fatalf("DELETE device failed: %d %s", delRec.Code, delRec.Body.String())
	}
	// Second device token must now be 401.
	chk2Req := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	chk2Req.Header.Set("Authorization", "Bearer "+login2Resp.Token)
	chk2Rec := httptest.NewRecorder()
	srv2.ServeHTTP(chk2Rec, chk2Req)
	if chk2Rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for deleted device token, got %d", chk2Rec.Code)
	}
	// Deleting non-existent ID returns 404.
	del404Req := httptest.NewRequest(http.MethodDelete, "/api/auth/devices/"+login2Resp.TokenID, nil)
	del404Req.Header.Set("Authorization", "Bearer "+loginResp.Token)
	del404Rec := httptest.NewRecorder()
	srv2.ServeHTTP(del404Rec, del404Req)
	if del404Rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 deleting absent device, got %d", del404Rec.Code)
	}

	// Logout with Bearer revokes first device token.
	logoutReq := httptest.NewRequest(http.MethodPost, "/api/auth/logout", nil)
	logoutReq.Header.Set("Authorization", "Bearer "+loginResp.Token)
	logoutRec := httptest.NewRecorder()
	srv2.ServeHTTP(logoutRec, logoutReq)
	if logoutRec.Code != http.StatusOK {
		t.Fatalf("POST /api/auth/logout with Bearer failed: %d %s", logoutRec.Code, logoutRec.Body.String())
	}
	chk1Req := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	chk1Req.Header.Set("Authorization", "Bearer "+loginResp.Token)
	chk1Rec := httptest.NewRecorder()
	srv2.ServeHTTP(chk1Rec, chk1Req)
	if chk1Rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 after logout Bearer revocation, got %d", chk1Rec.Code)
	}
}

func TestBearerExemptsCSRFWhileCookieStillRequiresIt(t *testing.T) {
	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.AuthEnabled = true
		d.AuthUserDigest = sha256.Sum256([]byte("admin"))
		d.AuthPassDigest = sha256.Sum256([]byte("secret-pass"))
	})

	// Web cookie login.
	wLogin := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass"}`))
	wLogin.Header.Set("Content-Type", "application/json")
	wLogin.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	wRec := httptest.NewRecorder()
	srv.ServeHTTP(wRec, wLogin)
	var sessCookie *http.Cookie
	for _, c := range wRec.Result().Cookies() {
		if c.Name == auth.SessionCookieName && c.Value != "" {
			sessCookie = c
		}
	}
	if sessCookie == nil {
		t.Fatal("missing session cookie")
	}

	// Cookie without CSRF header on state-changing request -> 403.
	noCsrfReq := httptest.NewRequest(http.MethodPut, "/api/config", strings.NewReader(`{"sources":["AudiusMusicClient"]}`))
	noCsrfReq.Header.Set("Content-Type", "application/json")
	noCsrfReq.AddCookie(sessCookie)
	noCsrfRec := httptest.NewRecorder()
	srv.ServeHTTP(noCsrfRec, noCsrfReq)
	if noCsrfRec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 without CSRF for cookie auth, got %d %s", noCsrfRec.Code, noCsrfRec.Body.String())
	}

	// Invalid Bearer without CSRF (even with valid cookie) -> 401 (never falls back to cookie).
	badBearerReq := httptest.NewRequest(http.MethodPut, "/api/config", strings.NewReader(`{"sources":["AudiusMusicClient"]}`))
	badBearerReq.Header.Set("Content-Type", "application/json")
	badBearerReq.Header.Set("Authorization", "Bearer bogus-token")
	badBearerReq.AddCookie(sessCookie)
	badBearerRec := httptest.NewRecorder()
	srv.ServeHTTP(badBearerRec, badBearerReq)
	if badBearerRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for bogus Bearer even with cookie, got %d %s", badBearerRec.Code, badBearerRec.Body.String())
	}

	// Valid App Bearer without CSRF -> 200.
	aLogin := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass","client_type":"app"}`))
	aLogin.Header.Set("Content-Type", "application/json")
	aLogin.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	aRec := httptest.NewRecorder()
	srv.ServeHTTP(aRec, aLogin)
	var aResp struct {
		Token string `json:"token"`
	}
	_ = json.Unmarshal(aRec.Body.Bytes(), &aResp)

	okReq := httptest.NewRequest(http.MethodPut, "/api/config", strings.NewReader(`{"sources":["AudiusMusicClient"]}`))
	okReq.Header.Set("Content-Type", "application/json")
	okReq.Header.Set("Authorization", "Bearer "+aResp.Token)
	okRec := httptest.NewRecorder()
	srv.ServeHTTP(okRec, okReq)
	if okRec.Code != http.StatusOK {
		t.Fatalf("expected 200 with Bearer and no CSRF header, got %d %s", okRec.Code, okRec.Body.String())
	}
}

func TestCORSAllowlistAndOptionsPreflight(t *testing.T) {
	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.AuthEnabled = true
		d.AuthUserDigest = sha256.Sum256([]byte("admin"))
		d.AuthPassDigest = sha256.Sum256([]byte("secret"))
	})

	allowedOrigins := []string{
		"capacitor://localhost",
		"capacitor://localhost:8080",
		"ionic://localhost",
		"ionic://localhost:8100",
		"http://localhost",
		"http://localhost:5173",
		"https://localhost",
		"https://localhost:8443",
		"http://127.0.0.1",
		"http://127.0.0.1:5173",
	}
	for _, origin := range allowedOrigins {
		req := httptest.NewRequest(http.MethodOptions, "/api/config", nil)
		req.Header.Set("Origin", origin)
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		if rec.Code != http.StatusNoContent {
			t.Fatalf("OPTIONS %s expected 204, got %d", origin, rec.Code)
		}
		if got := rec.Header().Get("Access-Control-Allow-Origin"); got != origin {
			t.Fatalf("Origin %s: ACAO=%q", origin, got)
		}
		if rec.Header().Get("Vary") != "Origin" || rec.Header().Get("Access-Control-Allow-Credentials") != "true" {
			t.Fatalf("Origin %s missing Vary or Allow-Credentials: %v", origin, rec.Header())
		}
		if !strings.Contains(rec.Header().Get("Access-Control-Allow-Headers"), "Authorization") ||
			!strings.Contains(rec.Header().Get("Access-Control-Allow-Headers"), "Range") {
			t.Fatalf("Origin %s missing expected Allow-Headers: %q", origin, rec.Header().Get("Access-Control-Allow-Headers"))
		}
		if !strings.Contains(rec.Header().Get("Access-Control-Expose-Headers"), "Content-Range") ||
			!strings.Contains(rec.Header().Get("Access-Control-Expose-Headers"), "Accept-Ranges") {
			t.Fatalf("Origin %s missing expected Expose-Headers: %q", origin, rec.Header().Get("Access-Control-Expose-Headers"))
		}
	}

	disallowedOrigins := []string{
		"https://evil.example.com",
		"http://localhost.evil.com",
		"http://127.0.0.1.evil.com",
		"file://localhost",
		"null",
	}
	for _, origin := range disallowedOrigins {
		req := httptest.NewRequest(http.MethodOptions, "/api/health", nil)
		req.Header.Set("Origin", origin)
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		if rec.Code != http.StatusNoContent {
			t.Fatalf("OPTIONS %s expected 204, got %d", origin, rec.Code)
		}
		if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
			t.Fatalf("disallowed origin %s got ACAO=%q", origin, got)
		}
	}
}

func TestMediaTokenStreamingAndRejection(t *testing.T) {
	audioData := []byte("RIFF0123456789WAVEfmt media-token-test-bytes")
	coverPNG := []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3, 4}
	navStub := &stubNavidromeMediaGateway{
		coverBytes:  coverPNG,
		streamBytes: audioData,
	}

	var assetRelPath = "Album/song.wav"
	srv, st, canonRoot := newTestServer(t, func(d *Dependencies, root string) {
		d.AuthEnabled = true
		d.AuthUserDigest = sha256.Sum256([]byte("admin"))
		d.AuthPassDigest = sha256.Sum256([]byte("secret-pass"))
		d.Navidrome = navStub

		songAbs := filepath.Join(root, "Album", "song.wav")
		if err := os.MkdirAll(filepath.Dir(songAbs), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(songAbs, audioData, 0o644); err != nil {
			t.Fatal(err)
		}
		coverAbs := filepath.Join(root, "Album", "cover.png")
		if err := os.WriteFile(coverAbs, coverPNG, 0o644); err != nil {
			t.Fatal(err)
		}
	})

	ctx := context.Background()
	if _, err := st.UpsertAsset(ctx, store.AssetRecord{
		ID:       "asset-mt-1",
		RootID:   "root-1",
		RelPath:  assetRelPath,
		PathKey:  files.ComputePathKey("insensitive", assetRelPath),
		Folder:   "Album",
		Name:     "song.wav",
		Status:   "active",
		Editable: true,
		Size:     int64(len(audioData)),
		MTimeNS:  1,
		Title:    "Song",
	}, 0, true); err != nil {
		t.Fatalf("UpsertAsset: %v", err)
	}
	if err := st.PutPublicRef(ctx, model.PublicRef{
		PublicID: "pub-asset-mt-1",
		Kind:     "asset",
		TargetID: "asset-mt-1",
		Source:   "local",
	}, ""); err != nil {
		t.Fatalf("PutPublicRef: %v", err)
	}
	_ = canonRoot

	// Obtain media_token via app login.
	loginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass","client_type":"app"}`))
	loginReq.Header.Set("Content-Type", "application/json")
	loginReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	loginRec := httptest.NewRecorder()
	srv.ServeHTTP(loginRec, loginReq)
	var loginResp struct {
		MediaToken string `json:"media_token"`
	}
	_ = json.Unmarshal(loginRec.Body.Bytes(), &loginResp)
	mt := url.QueryEscape(loginResp.MediaToken)

	// 1. /api/files/audio GET with Range -> 206 Partial Content.
	rangeReq := httptest.NewRequest(http.MethodGet, "/api/files/audio?path=asset:asset-mt-1&media_token="+mt, nil)
	rangeReq.Header.Set("Range", "bytes=0-9")
	rangeRec := httptest.NewRecorder()
	srv.ServeHTTP(rangeRec, rangeReq)
	if rangeRec.Code != http.StatusPartialContent || rangeRec.Body.String() != string(audioData[:10]) {
		t.Fatalf("Range audio failed: code=%d body=%q hdr=%v", rangeRec.Code, rangeRec.Body.String(), rangeRec.Header())
	}

	// 2. /api/files/audio HEAD -> 200 OK with no body.
	headReq := httptest.NewRequest(http.MethodHead, "/api/files/audio?path=asset:asset-mt-1&media_token="+mt, nil)
	headRec := httptest.NewRecorder()
	srv.ServeHTTP(headRec, headReq)
	if headRec.Code != http.StatusOK || headRec.Body.Len() != 0 {
		t.Fatalf("HEAD audio failed: code=%d len=%d", headRec.Code, headRec.Body.Len())
	}

	// 3. /api/files/cover GET & HEAD -> 200 OK.
	covReq := httptest.NewRequest(http.MethodGet, "/api/files/cover?path=asset:asset-mt-1&media_token="+mt, nil)
	covRec := httptest.NewRecorder()
	srv.ServeHTTP(covRec, covReq)
	if covRec.Code != http.StatusOK || !bytes.Equal(covRec.Body.Bytes(), coverPNG) {
		t.Fatalf("cover failed: code=%d len=%d", covRec.Code, covRec.Body.Len())
	}

	// 4. /api/files/content GET -> 200 OK.
	cntReq := httptest.NewRequest(http.MethodGet, "/api/files/content?path=asset:asset-mt-1&media_token="+mt, nil)
	cntRec := httptest.NewRecorder()
	srv.ServeHTTP(cntRec, cntReq)
	if cntRec.Code != http.StatusOK || !bytes.Equal(cntRec.Body.Bytes(), audioData) {
		t.Fatalf("content failed: code=%d len=%d", cntRec.Code, cntRec.Body.Len())
	}

	// 5. /api/stream/{sid} GET -> 200 OK.
	strReq := httptest.NewRequest(http.MethodGet, "/api/stream/pub-asset-mt-1?media_token="+mt, nil)
	strRec := httptest.NewRecorder()
	srv.ServeHTTP(strRec, strReq)
	if strRec.Code != http.StatusOK || !bytes.Equal(strRec.Body.Bytes(), audioData) {
		t.Fatalf("stream failed: code=%d len=%d", strRec.Code, strRec.Body.Len())
	}

	// 6. /api/navidrome/stream/{ident} with Range and /api/navidrome/cover/{ident} -> allowed.
	navStrReq := httptest.NewRequest(http.MethodGet, "/api/navidrome/stream/s1?media_token="+mt, nil)
	navStrReq.Header.Set("Range", "bytes=4-7")
	navStrRec := httptest.NewRecorder()
	srv.ServeHTTP(navStrRec, navStrReq)
	if navStrRec.Code != http.StatusPartialContent || navStrRec.Body.String() != string(audioData[4:8]) {
		t.Fatalf("navidrome stream failed: code=%d body=%q", navStrRec.Code, navStrRec.Body.String())
	}

	navCovReq := httptest.NewRequest(http.MethodGet, "/api/navidrome/cover/c1?media_token="+mt, nil)
	navCovRec := httptest.NewRecorder()
	srv.ServeHTTP(navCovRec, navCovReq)
	if navCovRec.Code != http.StatusOK || !bytes.Equal(navCovRec.Body.Bytes(), coverPNG) {
		t.Fatalf("navidrome cover failed: code=%d", navCovRec.Code)
	}

	// 7. Non-media endpoints MUST reject media_token with 401.
	cfgReq := httptest.NewRequest(http.MethodGet, "/api/config?media_token="+mt, nil)
	cfgRec := httptest.NewRecorder()
	srv.ServeHTTP(cfgRec, cfgReq)
	if cfgRec.Code != http.StatusUnauthorized {
		t.Fatalf("non-media endpoint must reject media_token with 401, got %d", cfgRec.Code)
	}

	// 8. Tampered or expired media_token MUST fail with 401.
	badTokReq := httptest.NewRequest(http.MethodGet, "/api/files/audio?path=asset:asset-mt-1&media_token=9999999999.0000", nil)
	badTokRec := httptest.NewRecorder()
	srv.ServeHTTP(badTokRec, badTokReq)
	if badTokRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for tampered media_token, got %d", badTokRec.Code)
	}

	baseNow := time.Now().UTC()
	srv.deps.Sessions.SetClock(func() time.Time { return baseNow })
	expiredTok, _ := srv.deps.Sessions.SignMediaToken("admin", 5*time.Second)
	srv.deps.Sessions.SetClock(func() time.Time { return baseNow.Add(10 * time.Second) })
	expReq := httptest.NewRequest(http.MethodGet, "/api/files/audio?path=asset:asset-mt-1&media_token="+url.QueryEscape(expiredTok), nil)
	expRec := httptest.NewRecorder()
	srv.ServeHTTP(expRec, expReq)
	if expRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for expired media_token, got %d", expRec.Code)
	}
}

func TestNilStoreGracefulDegradation(t *testing.T) {
	srv, err := NewServer(Dependencies{
		Store:          nil,
		AuthEnabled:    true,
		AuthUserDigest: sha256.Sum256([]byte("admin")),
		AuthPassDigest: sha256.Sum256([]byte("secret")),
	})
	if err != nil {
		t.Fatalf("NewServer with nil Store: %v", err)
	}

	loginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret","client_type":"app","device_name":"InMem"}`))
	loginReq.Header.Set("Content-Type", "application/json")
	loginReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	loginRec := httptest.NewRecorder()
	srv.ServeHTTP(loginRec, loginReq)
	if loginRec.Code != http.StatusOK {
		t.Fatalf("nil-store app login failed: %d %s", loginRec.Code, loginRec.Body.String())
	}
	var resp struct {
		Token      string `json:"token"`
		TokenID    string `json:"token_id"`
		MediaToken string `json:"media_token"`
	}
	_ = json.Unmarshal(loginRec.Body.Bytes(), &resp)
	if resp.Token == "" || resp.TokenID == "" || resp.MediaToken == "" {
		t.Fatalf("unexpected nil-store login tokens: %+v", resp)
	}

	devReq := httptest.NewRequest(http.MethodGet, "/api/auth/devices", nil)
	devReq.Header.Set("Authorization", "Bearer "+resp.Token)
	devRec := httptest.NewRecorder()
	srv.ServeHTTP(devRec, devReq)
	if devRec.Code != http.StatusOK {
		t.Fatalf("nil-store GET /api/auth/devices failed: %d %s", devRec.Code, devRec.Body.String())
	}

	if uid, ok := srv.deps.Sessions.VerifyMediaToken(resp.MediaToken); !ok || uid != "admin" {
		t.Fatalf("nil-store media token verify failed: uid=%q ok=%v", uid, ok)
	}
}
