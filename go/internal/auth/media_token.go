package auth

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
	"time"
)

type mediaUserStore interface {
	AuthMediaSecretUsers(ctx context.Context) ([]string, error)
	RememberAuthMediaUser(ctx context.Context, userID string) error
}

func computeMediaHMAC(secret []byte, expUnix int64, userID string) []byte {
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(fmt.Sprintf("%d:%s", expUnix, userID)))
	return mac.Sum(nil)
}

func appendUniqueUser(dst []string, userID string) ([]string, bool) {
	for _, existing := range dst {
		if existing == userID {
			return dst, false
		}
	}
	if len(dst) >= 64 {
		dst = append(dst[1:], userID)
	} else {
		dst = append(dst, userID)
	}
	return dst, true
}

func (m *SessionManager) ensureMediaSecretAndUsers(ctx context.Context, refreshFromStore bool) ([]byte, []string) {
	if ctx == nil {
		ctx = context.Background()
	}
	m.mu.RLock()
	cachedSecret := m.mediaSecret
	cachedUsers := append([]string(nil), m.mediaUsers...)
	st := m.store
	m.mu.RUnlock()

	if len(cachedSecret) == 32 && !refreshFromStore {
		return cachedSecret, cachedUsers
	}

	var loadedSecret []byte
	var loadedUsers []string
	if st != nil {
		if sec, err := st.EnsureAuthMediaSecret(ctx); err == nil && len(sec) == 32 {
			loadedSecret = sec
		}
		if us, ok := st.(mediaUserStore); ok {
			if persistedUsers, err := us.AuthMediaSecretUsers(ctx); err == nil {
				loadedUsers = append(loadedUsers, persistedUsers...)
			}
		}
		if tokens, err := st.ListDeviceTokens(ctx, ""); err == nil {
			for _, rec := range tokens {
				loadedUsers, _ = appendUniqueUser(loadedUsers, rec.UserID)
			}
		}
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.mediaSecret) != 32 {
		if len(loadedSecret) == 32 {
			m.mediaSecret = loadedSecret
		} else {
			buf := make([]byte, 32)
			_, _ = rand.Read(buf)
			m.mediaSecret = buf
		}
	}
	for _, u := range loadedUsers {
		m.mediaUsers, _ = appendUniqueUser(m.mediaUsers, u)
	}
	secretCopy := append([]byte(nil), m.mediaSecret...)
	usersCopy := append([]string(nil), m.mediaUsers...)
	return secretCopy, usersCopy
}

func (m *SessionManager) rememberMediaUser(ctx context.Context, userID string) {
	m.mu.Lock()
	var added bool
	m.mediaUsers, added = appendUniqueUser(m.mediaUsers, userID)
	st := m.store
	m.mu.Unlock()

	if added && userID != "" && st != nil {
		if us, ok := st.(mediaUserStore); ok {
			_ = us.RememberAuthMediaUser(ctx, userID)
		}
	}
}

// SignMediaToken generates a short-lived HMAC-SHA256 media token in the format "<expUnix>.<hmacHex>".
// When ttl <= 0, DefaultMediaTokenTTL (12h) is used.
func (m *SessionManager) SignMediaToken(userID string, ttl time.Duration) (string, int64) {
	if ttl <= 0 {
		ttl = DefaultMediaTokenTTL
	}
	userID = strings.TrimSpace(userID)
	ctx := context.Background()
	secret, _ := m.ensureMediaSecretAndUsers(ctx, false)
	m.rememberMediaUser(ctx, userID)

	expiresAt := m.currentTime().UTC().Add(ttl).Unix()
	sig := computeMediaHMAC(secret, expiresAt, userID)
	return fmt.Sprintf("%d.%s", expiresAt, hex.EncodeToString(sig)), expiresAt
}

// VerifyMediaToken verifies a media token formatted as "<expUnix>.<hmacHex>" and returns the signed userID.
func (m *SessionManager) VerifyMediaToken(token string) (string, bool) {
	token = strings.TrimSpace(token)
	if token == "" {
		return "", false
	}
	dot := strings.IndexByte(token, '.')
	if dot <= 0 || dot >= len(token)-1 {
		return "", false
	}
	expStr := token[:dot]
	sigHex := token[dot+1:]
	if strings.ContainsRune(sigHex, '.') || len(sigHex) != 64 {
		return "", false
	}
	expUnix, err := strconv.ParseInt(expStr, 10, 64)
	if err != nil || expUnix <= 0 {
		return "", false
	}
	if expUnix <= m.currentTime().UTC().Unix() {
		return "", false
	}
	sigBytes, err := hex.DecodeString(sigHex)
	if err != nil || len(sigBytes) != sha256.Size {
		return "", false
	}

	ctx := context.Background()
	matchUsers := func(secret []byte, users []string) (string, bool) {
		for _, u := range users {
			expected := computeMediaHMAC(secret, expUnix, u)
			if hmac.Equal(expected, sigBytes) {
				return u, true
			}
		}
		for _, fallback := range []string{"admin", "anonymous", ""} {
			expected := computeMediaHMAC(secret, expUnix, fallback)
			if hmac.Equal(expected, sigBytes) {
				return fallback, true
			}
		}
		return "", false
	}

	secret, users := m.ensureMediaSecretAndUsers(ctx, false)
	if uid, ok := matchUsers(secret, users); ok {
		return uid, true
	}

	// Refresh known users/secret from store once in case another instance issued a token.
	m.mu.RLock()
	hasStore := m.store != nil
	m.mu.RUnlock()
	if hasStore {
		secret, users = m.ensureMediaSecretAndUsers(ctx, true)
		if uid, ok := matchUsers(secret, users); ok {
			return uid, true
		}
	}
	return "", false
}
