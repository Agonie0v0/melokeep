package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"net/http"
	"reflect"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

const (
	SessionCookieName     = "melokeep_session"
	SessionMaxAgeSeconds  = 43200 // 12h, matches contracts/v1/http/samples.json
	DefaultDeviceTokenTTL = 90 * 24 * time.Hour
	DefaultMediaTokenTTL  = 12 * time.Hour
	LoginFailureLimit     = 8
	LoginWindow           = 300 * time.Second
	MaxActiveSessions     = 1024
	MaxRateLimitKeys      = 4096
)

// DeviceTokenStore abstracts optional durable storage for device bearer tokens
// and the HMAC secret used for short-lived media tokens.
type DeviceTokenStore interface {
	PutDeviceToken(ctx context.Context, rec model.DeviceToken) error
	GetDeviceToken(ctx context.Context, id string) (model.DeviceToken, bool, error)
	FindDeviceTokenByHash(ctx context.Context, tokenHash string) (model.DeviceToken, bool, error)
	TouchDeviceToken(ctx context.Context, id string, lastUsedAt int64) error
	ListDeviceTokens(ctx context.Context, userID string) ([]model.DeviceToken, error)
	DeleteDeviceToken(ctx context.Context, id string) (bool, error)
	EnsureAuthMediaSecret(ctx context.Context) ([]byte, error)
}

type sessionEntry struct {
	UserID    string
	ExpiresAt time.Time
}

// SessionManager tracks active web sessions in memory and manages persistent device tokens
// plus HMAC-SHA256 media tokens with an optional durable store.
type SessionManager struct {
	mu           sync.RWMutex
	sessions     map[string]sessionEntry
	deviceTokens map[string]model.DeviceToken
	store        DeviceTokenStore
	mediaSecret  []byte
	mediaUsers   []string
	now          func() time.Time
}

func NewSessionManager() *SessionManager {
	return NewSessionManagerWithStore(nil)
}

func normalizeDeviceTokenStore(st DeviceTokenStore) DeviceTokenStore {
	if st == nil {
		return nil
	}
	v := reflect.ValueOf(st)
	if v.Kind() == reflect.Ptr && v.IsNil() {
		return nil
	}
	return st
}

// NewSessionManagerWithStore creates a SessionManager backed by st (or in-memory fallback when nil).
func NewSessionManagerWithStore(st DeviceTokenStore) *SessionManager {
	return &SessionManager{
		sessions:     make(map[string]sessionEntry),
		deviceTokens: make(map[string]model.DeviceToken),
		store:        normalizeDeviceTokenStore(st),
		now:          time.Now,
	}
}

// SetClock overrides the clock used for token issuance and expiry checks (nil restores time.Now).
func (m *SessionManager) SetClock(now func() time.Time) {
	if m == nil {
		return
	}
	if now == nil {
		now = time.Now
	}
	m.mu.Lock()
	m.now = now
	m.mu.Unlock()
}

func (m *SessionManager) currentTime() time.Time {
	m.mu.RLock()
	fn := m.now
	m.mu.RUnlock()
	if fn == nil {
		return time.Now()
	}
	return fn()
}

// SetStoreIfNil attaches a durable store if none was configured yet.
func (m *SessionManager) SetStoreIfNil(st DeviceTokenStore) {
	st = normalizeDeviceTokenStore(st)
	if m == nil || st == nil {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.store == nil {
		m.store = st
		m.mediaSecret = nil
		m.mediaUsers = nil
	}
}

func (m *SessionManager) Create(userID string) (string, error) {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	token := hex.EncodeToString(b[:])
	now := m.currentTime()
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked(now)
	for len(m.sessions) >= MaxActiveSessions {
		var oldestKey string
		var oldestExp time.Time
		for k, v := range m.sessions {
			if oldestKey == "" || v.ExpiresAt.Before(oldestExp) {
				oldestKey = k
				oldestExp = v.ExpiresAt
			}
		}
		if oldestKey == "" {
			break
		}
		delete(m.sessions, oldestKey)
	}
	m.sessions[token] = sessionEntry{
		UserID:    userID,
		ExpiresAt: now.Add(time.Duration(SessionMaxAgeSeconds) * time.Second),
	}
	return token, nil
}

func (m *SessionManager) Validate(token string) (string, bool) {
	if token == "" {
		return "", false
	}
	now := m.currentTime()
	m.mu.RLock()
	entry, ok := m.sessions[token]
	m.mu.RUnlock()
	if !ok || now.After(entry.ExpiresAt) {
		if ok {
			m.Delete(token)
		}
		return "", false
	}
	return entry.UserID, true
}

// ExtractBearerToken extracts a non-empty Bearer token from the Authorization header.
func ExtractBearerToken(r *http.Request) (string, bool) {
	if r == nil {
		return "", false
	}
	raw := strings.TrimSpace(r.Header.Get("Authorization"))
	if raw == "" {
		return "", false
	}
	parts := strings.Fields(raw)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		return "", false
	}
	tok := strings.TrimSpace(parts[1])
	if tok == "" {
		return "", false
	}
	return tok, true
}

func hashTokenHex(rawToken string) string {
	sum := sha256.Sum256([]byte(rawToken))
	return hex.EncodeToString(sum[:])
}

func normalizeDeviceName(name string) string {
	cleaned := strings.TrimSpace(name)
	if cleaned == "" {
		return "MeloKeep App"
	}
	runes := []rune(cleaned)
	if len(runes) > 128 {
		return string(runes[:128])
	}
	return cleaned
}

// CreateDeviceToken issues a new 90-day bearer token, storing only its SHA-256 hash.
func (m *SessionManager) CreateDeviceToken(ctx context.Context, userID, deviceName string) (string, model.DeviceToken, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	var idBytes [8]byte
	if _, err := rand.Read(idBytes[:]); err != nil {
		return "", model.DeviceToken{}, err
	}
	var tokBytes [32]byte
	if _, err := rand.Read(tokBytes[:]); err != nil {
		return "", model.DeviceToken{}, err
	}
	rawToken := hex.EncodeToString(tokBytes[:])
	now := m.currentTime().UTC().Unix()
	rec := model.DeviceToken{
		ID:         hex.EncodeToString(idBytes[:]),
		TokenHash:  hashTokenHex(rawToken),
		UserID:     strings.TrimSpace(userID),
		DeviceName: normalizeDeviceName(deviceName),
		CreatedAt:  now,
		LastUsedAt: now,
		ExpiresAt:  now + int64(DefaultDeviceTokenTTL.Seconds()),
	}

	m.mu.RLock()
	st := m.store
	m.mu.RUnlock()

	if st != nil {
		if err := st.PutDeviceToken(ctx, rec); err != nil {
			return "", model.DeviceToken{}, err
		}
		return rawToken, rec, nil
	}

	m.mu.Lock()
	m.deviceTokens[rec.ID] = rec
	m.mu.Unlock()
	return rawToken, rec, nil
}

// ValidateBearerToken verifies a raw bearer token, rejecting expired tokens and refreshing LastUsedAt.
func (m *SessionManager) ValidateBearerToken(ctx context.Context, rawToken string) (model.DeviceToken, bool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	rawToken = strings.TrimSpace(rawToken)
	if rawToken == "" {
		return model.DeviceToken{}, false, nil
	}
	tokenHash := hashTokenHex(rawToken)
	now := m.currentTime().UTC().Unix()

	m.mu.RLock()
	st := m.store
	m.mu.RUnlock()

	if st != nil {
		rec, ok, err := st.FindDeviceTokenByHash(ctx, tokenHash)
		if err != nil || !ok {
			return model.DeviceToken{}, false, err
		}
		if rec.ExpiresAt <= now {
			_, _ = st.DeleteDeviceToken(ctx, rec.ID)
			return model.DeviceToken{}, false, nil
		}
		if now > rec.LastUsedAt {
			_ = st.TouchDeviceToken(ctx, rec.ID, now)
			rec.LastUsedAt = now
		}
		return rec, true, nil
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	targetBytes := []byte(tokenHash)
	for id, rec := range m.deviceTokens {
		if len(rec.TokenHash) == len(targetBytes) && subtle.ConstantTimeCompare([]byte(rec.TokenHash), targetBytes) == 1 {
			if rec.ExpiresAt <= now {
				delete(m.deviceTokens, id)
				return model.DeviceToken{}, false, nil
			}
			if now > rec.LastUsedAt {
				rec.LastUsedAt = now
				m.deviceTokens[id] = rec
			}
			return rec, true, nil
		}
	}
	return model.DeviceToken{}, false, nil
}

// ListDeviceTokens returns active (non-expired) device tokens, filtered by userID when non-empty.
func (m *SessionManager) ListDeviceTokens(ctx context.Context, userID string) ([]model.DeviceToken, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	now := m.currentTime().UTC().Unix()

	m.mu.RLock()
	st := m.store
	m.mu.RUnlock()

	if st != nil {
		all, err := st.ListDeviceTokens(ctx, userID)
		if err != nil {
			return nil, err
		}
		out := make([]model.DeviceToken, 0, len(all))
		for _, rec := range all {
			if rec.ExpiresAt <= now {
				_, _ = st.DeleteDeviceToken(ctx, rec.ID)
				continue
			}
			out = append(out, rec)
		}
		return out, nil
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]model.DeviceToken, 0)
	for id, rec := range m.deviceTokens {
		if rec.ExpiresAt <= now {
			delete(m.deviceTokens, id)
			continue
		}
		if userID != "" && rec.UserID != userID {
			continue
		}
		out = append(out, rec)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt != out[j].CreatedAt {
			return out[i].CreatedAt > out[j].CreatedAt
		}
		return out[i].ID < out[j].ID
	})
	return out, nil
}

// GetDeviceToken retrieves a single device token record by ID.
func (m *SessionManager) GetDeviceToken(ctx context.Context, id string) (model.DeviceToken, bool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	id = strings.TrimSpace(id)
	if id == "" {
		return model.DeviceToken{}, false, nil
	}
	m.mu.RLock()
	st := m.store
	m.mu.RUnlock()

	if st != nil {
		return st.GetDeviceToken(ctx, id)
	}
	m.mu.RLock()
	rec, ok := m.deviceTokens[id]
	m.mu.RUnlock()
	return rec, ok, nil
}

// DeleteDeviceToken revokes a device token by ID.
func (m *SessionManager) DeleteDeviceToken(ctx context.Context, id string) (bool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	id = strings.TrimSpace(id)
	if id == "" {
		return false, nil
	}
	m.mu.RLock()
	st := m.store
	m.mu.RUnlock()

	if st != nil {
		return st.DeleteDeviceToken(ctx, id)
	}
	m.mu.Lock()
	_, ok := m.deviceTokens[id]
	if ok {
		delete(m.deviceTokens, id)
	}
	m.mu.Unlock()
	return ok, nil
}

// RevokeBearerToken revokes the device token matching rawToken.
func (m *SessionManager) RevokeBearerToken(ctx context.Context, rawToken string) (bool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	rawToken = strings.TrimSpace(rawToken)
	if rawToken == "" {
		return false, nil
	}
	tokenHash := hashTokenHex(rawToken)

	m.mu.RLock()
	st := m.store
	m.mu.RUnlock()

	if st != nil {
		rec, ok, err := st.FindDeviceTokenByHash(ctx, tokenHash)
		if err != nil || !ok {
			return false, err
		}
		return st.DeleteDeviceToken(ctx, rec.ID)
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	targetBytes := []byte(tokenHash)
	for id, rec := range m.deviceTokens {
		if len(rec.TokenHash) == len(targetBytes) && subtle.ConstantTimeCompare([]byte(rec.TokenHash), targetBytes) == 1 {
			delete(m.deviceTokens, id)
			return true, nil
		}
	}
	return false, nil
}

func (m *SessionManager) ValidateRequest(r *http.Request) (Actor, error) {
	if r == nil {
		return UnauthenticatedActor(), model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if bearer, ok := ExtractBearerToken(r); ok {
		rec, valid, err := m.ValidateBearerToken(r.Context(), bearer)
		if err != nil || !valid {
			return UnauthenticatedActor(), model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
		}
		return WebAdminActor(rec.UserID), nil
	}
	cookie, err := r.Cookie(SessionCookieName)
	if err != nil {
		return UnauthenticatedActor(), model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	userID, ok := m.Validate(cookie.Value)
	if !ok {
		return UnauthenticatedActor(), model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	return WebAdminActor(userID), nil
}

func (m *SessionManager) Delete(token string) {
	if token == "" {
		return
	}
	m.mu.Lock()
	delete(m.sessions, token)
	m.mu.Unlock()
}

func (m *SessionManager) pruneLocked(now time.Time) {
	for k, v := range m.sessions {
		if now.After(v.ExpiresAt) {
			delete(m.sessions, k)
		}
	}
}

func SetSessionCookie(w http.ResponseWriter, token string, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    token,
		Path:     "/api",
		MaxAge:   SessionMaxAgeSeconds,
		HttpOnly: true,
		Secure:   secure,
		SameSite: http.SameSiteStrictMode,
	})
}

func ClearSessionCookie(w http.ResponseWriter, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    "",
		Path:     "/api",
		MaxAge:   0,
		Expires:  time.Unix(0, 0).UTC(),
		HttpOnly: true,
		Secure:   secure,
		SameSite: http.SameSiteStrictMode,
	})
}

// VerifyCredentials compares username and password in constant time.
func VerifyCredentials(expectedUser, expectedPass, user, pass string) bool {
	uOK := subtle.ConstantTimeCompare([]byte(expectedUser), []byte(user)) == 1
	pOK := subtle.ConstantTimeCompare([]byte(expectedPass), []byte(pass)) == 1
	return uOK && pOK
}

// LoginRateLimiter enforces 8 failed attempts per 300 seconds per source key.
type LoginRateLimiter struct {
	mu       sync.Mutex
	failures map[string][]time.Time
	limit    int
	window   time.Duration
	now      func() time.Time
}

func NewLoginRateLimiter() *LoginRateLimiter {
	return &LoginRateLimiter{
		failures: make(map[string][]time.Time),
		limit:    LoginFailureLimit,
		window:   LoginWindow,
		now:      time.Now,
	}
}

func (l *LoginRateLimiter) Allow(key string) error {
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	kept := l.pruneLocked(key, now)
	if len(kept) >= l.limit {
		retryAfter := int(l.window.Seconds())
		if retryAfter <= 0 {
			retryAfter = 300
		}
		return &model.DomainError{
			Code:       model.ErrRateLimited,
			Status:     http.StatusTooManyRequests,
			Detail:     "登录失败次数过多，请 5 分钟后再试",
			RetryAfter: retryAfter,
			Retryable:  true,
		}
	}
	return nil
}

func (l *LoginRateLimiter) RecordFailure(key string) {
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	kept := l.pruneLocked(key, now)
	if _, exists := l.failures[key]; !exists && len(l.failures) >= MaxRateLimitKeys {
		for k := range l.failures {
			l.pruneLocked(k, now)
		}
		for len(l.failures) >= MaxRateLimitKeys {
			var oldestKey string
			var oldestAt time.Time
			for k, ts := range l.failures {
				last := now
				if len(ts) > 0 {
					last = ts[len(ts)-1]
				}
				if oldestKey == "" || last.Before(oldestAt) {
					oldestKey = k
					oldestAt = last
				}
			}
			if oldestKey == "" {
				break
			}
			delete(l.failures, oldestKey)
		}
	}
	l.failures[key] = append(kept, now)
}

func (l *LoginRateLimiter) Reset(key string) {
	l.mu.Lock()
	delete(l.failures, key)
	l.mu.Unlock()
}

func (l *LoginRateLimiter) pruneLocked(key string, now time.Time) []time.Time {
	cutoff := now.Add(-l.window)
	items := l.failures[key]
	kept := items[:0]
	for _, t := range items {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) == 0 {
		delete(l.failures, key)
		return nil
	}
	l.failures[key] = kept
	return kept
}
