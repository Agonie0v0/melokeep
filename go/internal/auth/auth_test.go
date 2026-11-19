package auth_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func TestCSRFAndSessionCookie(t *testing.T) {
	reqNoCSRF := httptest.NewRequest(http.MethodPut, "/api/config", nil)
	err := auth.CheckCSRF(reqNoCSRF)
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != 403 || de.Detail != "请求来源校验失败" {
		t.Fatalf("expected 403 CSRF error, got %v", err)
	}
	reqCSRF := httptest.NewRequest(http.MethodPut, "/api/config", nil)
	reqCSRF.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	if err := auth.CheckCSRF(reqCSRF); err != nil {
		t.Fatalf("expected valid CSRF header, got %v", err)
	}

	sm := auth.NewSessionManager()
	if _, err := sm.ValidateRequest(reqCSRF); err == nil {
		t.Fatal("expected 401 when cookie is absent")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != 401 {
		t.Fatalf("expected 401, got %d", de.HTTPStatus())
	}

	tok, err := sm.Create("admin")
	if err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	auth.SetSessionCookie(rec, tok, false)
	cookies := rec.Result().Cookies()
	if len(cookies) != 1 || cookies[0].Name != "melokeep_session" || !cookies[0].HttpOnly || cookies[0].Path != "/api" || cookies[0].SameSite != http.SameSiteStrictMode {
		t.Fatalf("unexpected session cookie attributes: %+v", cookies)
	}

	reqAuth := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	reqAuth.AddCookie(cookies[0])
	actor, err := sm.ValidateRequest(reqAuth)
	if err != nil || !actor.Authenticated || !actor.Has(auth.PermAdmin) {
		t.Fatalf("expected valid admin actor, got %+v err=%v", actor, err)
	}
}

func TestLoginRateLimiter8FailuresPer300s(t *testing.T) {
	limiter := auth.NewLoginRateLimiter()
	for i := 0; i < 8; i++ {
		if err := limiter.Allow("127.0.0.1"); err != nil {
			t.Fatalf("unexpected limit at attempt %d: %v", i+1, err)
		}
		limiter.RecordFailure("127.0.0.1")
	}
	err := limiter.Allow("127.0.0.1")
	de, ok := model.AsDomainError(err)
	if !ok || de.HTTPStatus() != 429 || de.RetryAfter != 300 || de.Detail != "登录失败次数过多，请 5 分钟后再试" {
		t.Fatalf("expected 429 rate limit with RetryAfter=300, got %v", err)
	}
	limiter.Reset("127.0.0.1")
	if err := limiter.Allow("127.0.0.1"); err != nil {
		t.Fatalf("expected reset to clear rate limit, got %v", err)
	}
}

func TestDeviceTokenLifecycleAndBearerPriority(t *testing.T) {
	ctx := context.Background()
	sm := auth.NewSessionManager()

	rawTok, rec, err := sm.CreateDeviceToken(ctx, "admin", "iPhone 15")
	if err != nil {
		t.Fatalf("CreateDeviceToken: %v", err)
	}
	if len(rec.ID) != 16 || rawTok == "" || rec.TokenHash == rawTok {
		t.Fatalf("unexpected device token metadata: raw=%q rec=%+v", rawTok, rec)
	}
	ttl := rec.ExpiresAt - rec.CreatedAt
	if ttl != int64(auth.DefaultDeviceTokenTTL.Seconds()) {
		t.Fatalf("expected 90d ttl, got %d", ttl)
	}

	// Bearer takes precedence over cookie: an invalid Bearer must reject even with valid cookie.
	cookieTok, err := sm.Create("admin")
	if err != nil {
		t.Fatal(err)
	}
	badBearerReq := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	badBearerReq.Header.Set("Authorization", "Bearer invalid-token")
	badBearerReq.AddCookie(&http.Cookie{Name: auth.SessionCookieName, Value: cookieTok})
	if _, err := sm.ValidateRequest(badBearerReq); err == nil {
		t.Fatal("expected invalid Bearer to fail even when valid cookie is present")
	}

	validBearerReq := httptest.NewRequest(http.MethodGet, "/api/config", nil)
	validBearerReq.Header.Set("Authorization", "bearer "+rawTok)
	actor, err := sm.ValidateRequest(validBearerReq)
	if err != nil || !actor.Authenticated || actor.UserID != "admin" {
		t.Fatalf("expected valid Bearer actor, got %+v err=%v", actor, err)
	}

	list, err := sm.ListDeviceTokens(ctx, "admin")
	if err != nil || len(list) != 1 || list[0].ID != rec.ID {
		t.Fatalf("ListDeviceTokens: %+v err=%v", list, err)
	}

	revoked, err := sm.RevokeBearerToken(ctx, rawTok)
	if err != nil || !revoked {
		t.Fatalf("RevokeBearerToken: revoked=%v err=%v", revoked, err)
	}
	if _, ok, err := sm.ValidateBearerToken(ctx, rawTok); err != nil || ok {
		t.Fatalf("expected revoked token invalid, ok=%v err=%v", ok, err)
	}

	// Verify 90-day expiry cleanup.
	baseNow := time.Unix(1700000000, 0).UTC()
	sm.SetClock(func() time.Time { return baseNow })
	expRaw, _, err := sm.CreateDeviceToken(ctx, "admin", "Tablet")
	if err != nil {
		t.Fatal(err)
	}
	sm.SetClock(func() time.Time { return baseNow.Add(auth.DefaultDeviceTokenTTL - time.Second) })
	if _, ok, err := sm.ValidateBearerToken(ctx, expRaw); err != nil || !ok {
		t.Fatalf("expected token valid right before 90d expiry, ok=%v err=%v", ok, err)
	}
	sm.SetClock(func() time.Time { return baseNow.Add(auth.DefaultDeviceTokenTTL) })
	if _, ok, err := sm.ValidateBearerToken(ctx, expRaw); err != nil || ok {
		t.Fatalf("expected token expired at 90d boundary, ok=%v err=%v", ok, err)
	}
}

func TestMediaTokenSignVerifyAndCrossRestart(t *testing.T) {
	ctx := context.Background()
	dbPath := store.DefaultDBPath(t.TempDir())
	st, err := store.Open(ctx, store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	defer st.Close()

	baseNow := time.Unix(1700000000, 0).UTC()
	sm1 := auth.NewSessionManagerWithStore(st)
	sm1.SetClock(func() time.Time { return baseNow })
	tok, exp := sm1.SignMediaToken("alice", 0)
	parts := strings.Split(tok, ".")
	if len(parts) != 2 || parts[0] != strconv.FormatInt(exp, 10) || len(parts[1]) != 64 {
		t.Fatalf("unexpected media token format: %q exp=%d", tok, exp)
	}
	if exp != baseNow.Add(auth.DefaultMediaTokenTTL).Unix() {
		t.Fatalf("expected 12h default media ttl, got exp=%d", exp)
	}

	uid, ok := sm1.VerifyMediaToken(tok)
	if !ok || uid != "alice" {
		t.Fatalf("VerifyMediaToken failed: uid=%q ok=%v", uid, ok)
	}

	// Tampered timestamp or signature must fail.
	tamperedExp := strconv.FormatInt(exp+10, 10) + "." + parts[1]
	if _, ok := sm1.VerifyMediaToken(tamperedExp); ok {
		t.Fatal("expected tampered expiry to fail verification")
	}
	tamperedSig := parts[0] + "." + strings.Repeat("0", 64)
	if _, ok := sm1.VerifyMediaToken(tamperedSig); ok {
		t.Fatal("expected tampered signature to fail verification")
	}

	// Expired token must fail when clock passes expiry.
	sm1.SetClock(func() time.Time { return baseNow.Add(auth.DefaultMediaTokenTTL) })
	if _, ok := sm1.VerifyMediaToken(tok); ok {
		t.Fatal("expected expired media token to fail verification")
	}

	// Simulate restart with a fresh SessionManager bound to the same persistent store.
	sm1.SetClock(func() time.Time { return baseNow })
	freshTok, _ := sm1.SignMediaToken("alice", time.Hour)
	sm2 := auth.NewSessionManagerWithStore(st)
	sm2.SetClock(func() time.Time { return baseNow.Add(30 * time.Minute) })
	uid2, ok2 := sm2.VerifyMediaToken(freshTok)
	if !ok2 || uid2 != "alice" {
		t.Fatalf("expected cross-restart media token verification to succeed, got uid=%q ok=%v", uid2, ok2)
	}

	// In-memory manager with distinct random secret must reject token.
	memOnly := auth.NewSessionManager()
	memOnly.SetClock(func() time.Time { return baseNow })
	if _, ok := memOnly.VerifyMediaToken(freshTok); ok {
		t.Fatal("expected isolated in-memory manager to reject token")
	}
}
