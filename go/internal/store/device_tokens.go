package store

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

const NamespaceAuthDeviceToken = "auth_device_token"

type storedDeviceToken struct {
	ID         string `json:"id"`
	TokenHash  string `json:"token_hash"`
	UserID     string `json:"user_id"`
	DeviceName string `json:"device_name"`
	CreatedAt  int64  `json:"created_at"`
	LastUsedAt int64  `json:"last_used_at"`
	ExpiresAt  int64  `json:"expires_at"`
}

func (r storedDeviceToken) toModel() model.DeviceToken {
	return model.DeviceToken{
		ID:         r.ID,
		TokenHash:  r.TokenHash,
		UserID:     r.UserID,
		DeviceName: r.DeviceName,
		CreatedAt:  r.CreatedAt,
		LastUsedAt: r.LastUsedAt,
		ExpiresAt:  r.ExpiresAt,
	}
}

func storedDeviceTokenFromModel(rec model.DeviceToken) storedDeviceToken {
	return storedDeviceToken{
		ID:         rec.ID,
		TokenHash:  rec.TokenHash,
		UserID:     rec.UserID,
		DeviceName: rec.DeviceName,
		CreatedAt:  rec.CreatedAt,
		LastUsedAt: rec.LastUsedAt,
		ExpiresAt:  rec.ExpiresAt,
	}
}

// PutDeviceToken persists a hashed device token record into durable_records.
func (s *Store) PutDeviceToken(ctx context.Context, rec model.DeviceToken) error {
	if strings.TrimSpace(rec.ID) == "" || strings.TrimSpace(rec.TokenHash) == "" {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "设备令牌标识或摘要不能为空")
	}
	raw, err := json.Marshal(storedDeviceTokenFromModel(rec))
	if err != nil {
		return err
	}
	_, err = s.PutDurableRecord(ctx, NamespaceAuthDeviceToken, rec.ID, string(raw), 0)
	return err
}

// GetDeviceToken retrieves a persisted device token record by ID.
func (s *Store) GetDeviceToken(ctx context.Context, id string) (model.DeviceToken, bool, error) {
	if strings.TrimSpace(id) == "" {
		return model.DeviceToken{}, false, nil
	}
	rec, ok, err := s.GetDurableRecord(ctx, NamespaceAuthDeviceToken, id)
	if err != nil || !ok {
		return model.DeviceToken{}, false, err
	}
	var stored storedDeviceToken
	if err := json.Unmarshal([]byte(rec.BodyJSON), &stored); err != nil {
		return model.DeviceToken{}, false, err
	}
	return stored.toModel(), true, nil
}

// FindDeviceTokenByHash looks up a device token record matching tokenHash in constant time.
func (s *Store) FindDeviceTokenByHash(ctx context.Context, tokenHash string) (model.DeviceToken, bool, error) {
	tokenHash = strings.TrimSpace(tokenHash)
	if tokenHash == "" {
		return model.DeviceToken{}, false, nil
	}
	rows, err := s.reader.QueryContext(ctx, `
		SELECT body_json FROM durable_records WHERE namespace = ?
	`, NamespaceAuthDeviceToken)
	if err != nil {
		return model.DeviceToken{}, false, err
	}
	defer rows.Close()

	var matched model.DeviceToken
	var found bool
	targetBytes := []byte(tokenHash)
	for rows.Next() {
		var bodyJSON string
		if err := rows.Scan(&bodyJSON); err != nil {
			return model.DeviceToken{}, false, err
		}
		var stored storedDeviceToken
		if json.Unmarshal([]byte(bodyJSON), &stored) != nil || stored.TokenHash == "" {
			continue
		}
		if len(stored.TokenHash) == len(targetBytes) && subtle.ConstantTimeCompare([]byte(stored.TokenHash), targetBytes) == 1 {
			matched = stored.toModel()
			found = true
		}
	}
	if err := rows.Err(); err != nil {
		return model.DeviceToken{}, false, err
	}
	return matched, found, nil
}

// TouchDeviceToken updates LastUsedAt for an existing device token when newer.
func (s *Store) TouchDeviceToken(ctx context.Context, id string, lastUsedAt int64) error {
	if strings.TrimSpace(id) == "" {
		return nil
	}
	nowMS := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		var bodyJSON string
		err := tx.QueryRowContext(ctx, `
			SELECT body_json FROM durable_records WHERE namespace = ? AND id = ?
		`, NamespaceAuthDeviceToken, id).Scan(&bodyJSON)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		var stored storedDeviceToken
		if err := json.Unmarshal([]byte(bodyJSON), &stored); err != nil {
			return nil
		}
		if lastUsedAt <= stored.LastUsedAt {
			return nil
		}
		stored.LastUsedAt = lastUsedAt
		raw, err := json.Marshal(stored)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `
			UPDATE durable_records
			SET body_json = ?, revision = revision + 1, updated_at = ?
			WHERE namespace = ? AND id = ?
		`, string(raw), nowMS, NamespaceAuthDeviceToken, id)
		return err
	})
}

// ListDeviceTokens returns persisted device tokens (filtered by userID when non-empty).
func (s *Store) ListDeviceTokens(ctx context.Context, userID string) ([]model.DeviceToken, error) {
	rows, err := s.reader.QueryContext(ctx, `
		SELECT body_json FROM durable_records WHERE namespace = ?
	`, NamespaceAuthDeviceToken)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]model.DeviceToken, 0)
	for rows.Next() {
		var bodyJSON string
		if err := rows.Scan(&bodyJSON); err != nil {
			return nil, err
		}
		var stored storedDeviceToken
		if json.Unmarshal([]byte(bodyJSON), &stored) != nil || stored.ID == "" {
			continue
		}
		if userID != "" && stored.UserID != userID {
			continue
		}
		out = append(out, stored.toModel())
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt != out[j].CreatedAt {
			return out[i].CreatedAt > out[j].CreatedAt
		}
		return out[i].ID < out[j].ID
	})
	return out, nil
}

// DeleteDeviceToken removes a persisted device token by ID and reports whether it existed.
func (s *Store) DeleteDeviceToken(ctx context.Context, id string) (bool, error) {
	if strings.TrimSpace(id) == "" {
		return false, nil
	}
	var deleted bool
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			DELETE FROM durable_records WHERE namespace = ? AND id = ?
		`, NamespaceAuthDeviceToken, id)
		if err != nil {
			return err
		}
		n, _ := res.RowsAffected()
		deleted = n > 0
		return nil
	})
	return deleted, err
}

type authMediaSecretPayload struct {
	SecretHex string   `json:"secret_hex"`
	Users     []string `json:"users,omitempty"`
}

func parseAuthMediaSecretPayload(bodyJSON string) ([]byte, []string, bool) {
	var p authMediaSecretPayload
	if json.Unmarshal([]byte(bodyJSON), &p) != nil || len(p.SecretHex) != 64 {
		return nil, nil, false
	}
	raw, err := hex.DecodeString(p.SecretHex)
	if err != nil || len(raw) != 32 {
		return nil, nil, false
	}
	return raw, p.Users, true
}

// EnsureAuthMediaSecret loads or atomically creates the persistent 32-byte HMAC secret in settings.
func (s *Store) EnsureAuthMediaSecret(ctx context.Context) ([]byte, error) {
	if rec, ok, err := s.GetSetting(ctx, NamespaceAuthMediaSecret); err != nil {
		return nil, err
	} else if ok {
		if secret, _, valid := parseAuthMediaSecretPayload(rec.BodyJSON); valid {
			return secret, nil
		}
	}

	var secret []byte
	nowMS := time.Now().UTC().UnixMilli()
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		var existingJSON string
		err := tx.QueryRowContext(ctx, `
			SELECT body_json FROM settings WHERE namespace = ?
		`, NamespaceAuthMediaSecret).Scan(&existingJSON)
		if err == nil {
			if existingSecret, _, valid := parseAuthMediaSecretPayload(existingJSON); valid {
				secret = existingSecret
				return nil
			}
		} else if !errors.Is(err, sql.ErrNoRows) {
			return err
		}

		buf := make([]byte, 32)
		if _, rErr := rand.Read(buf); rErr != nil {
			return rErr
		}
		payload, mErr := json.Marshal(authMediaSecretPayload{
			SecretHex: hex.EncodeToString(buf),
		})
		if mErr != nil {
			return mErr
		}
		if _, eErr := tx.ExecContext(ctx, `
			INSERT INTO settings(namespace, body_json, revision, updated_at)
			VALUES(?, ?, 1, ?)
			ON CONFLICT(namespace) DO UPDATE SET
				body_json = excluded.body_json,
				revision = settings.revision + 1,
				updated_at = excluded.updated_at
		`, NamespaceAuthMediaSecret, string(payload), nowMS); eErr != nil {
			return eErr
		}
		secret = buf
		return nil
	})
	return secret, err
}

// AuthMediaSecretUsers returns the persisted list of user IDs that have been issued media tokens.
func (s *Store) AuthMediaSecretUsers(ctx context.Context) ([]string, error) {
	rec, ok, err := s.GetSetting(ctx, NamespaceAuthMediaSecret)
	if err != nil || !ok {
		return nil, err
	}
	_, users, valid := parseAuthMediaSecretPayload(rec.BodyJSON)
	if !valid {
		return nil, nil
	}
	return users, nil
}

// RememberAuthMediaUser ensures userID is recorded alongside the persisted media secret for restart-safe verification.
func (s *Store) RememberAuthMediaUser(ctx context.Context, userID string) error {
	if _, err := s.EnsureAuthMediaSecret(ctx); err != nil {
		return err
	}
	nowMS := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		var bodyJSON string
		err := tx.QueryRowContext(ctx, `
			SELECT body_json FROM settings WHERE namespace = ?
		`, NamespaceAuthMediaSecret).Scan(&bodyJSON)
		if err != nil {
			return err
		}
		var p authMediaSecretPayload
		if err := json.Unmarshal([]byte(bodyJSON), &p); err != nil || len(p.SecretHex) != 64 {
			return nil
		}
		for _, u := range p.Users {
			if u == userID {
				return nil
			}
		}
		if len(p.Users) >= 32 {
			p.Users = append(p.Users[1:], userID)
		} else {
			p.Users = append(p.Users, userID)
		}
		raw, err := json.Marshal(p)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `
			UPDATE settings SET body_json = ?, revision = revision + 1, updated_at = ?
			WHERE namespace = ?
		`, string(raw), nowMS, NamespaceAuthMediaSecret)
		return err
	})
}
