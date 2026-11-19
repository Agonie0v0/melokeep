package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

// ProviderPluginRecord persists an installed provider plugin and its configuration.
type ProviderPluginRecord struct {
	ID                 string
	Enabled            bool
	SourceURL          string
	SHA256             string
	ManifestJSON       string
	SettingsJSON       string
	AllowedOriginsJSON string
	LastError          string
	CreatedAt          int64
	UpdatedAt          int64
}

// ListProviderPlugins returns all persisted provider plugin records ordered by creation time and ID.
func (s *Store) ListProviderPlugins(ctx context.Context) ([]ProviderPluginRecord, error) {
	rows, err := s.reader.QueryContext(ctx, `
		SELECT id, enabled, source_url, sha256, manifest_json, settings_json, allowed_origins_json, last_error, created_at, updated_at
		FROM provider_plugins
		ORDER BY created_at ASC, id ASC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]ProviderPluginRecord, 0)
	for rows.Next() {
		var rec ProviderPluginRecord
		var enabled int
		if err := rows.Scan(
			&rec.ID,
			&enabled,
			&rec.SourceURL,
			&rec.SHA256,
			&rec.ManifestJSON,
			&rec.SettingsJSON,
			&rec.AllowedOriginsJSON,
			&rec.LastError,
			&rec.CreatedAt,
			&rec.UpdatedAt,
		); err != nil {
			return nil, err
		}
		rec.Enabled = enabled == 1
		out = append(out, rec)
	}
	return out, rows.Err()
}

// GetProviderPlugin returns a persisted provider plugin record by ID.
func (s *Store) GetProviderPlugin(ctx context.Context, id string) (ProviderPluginRecord, bool, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return ProviderPluginRecord{}, false, nil
	}
	var rec ProviderPluginRecord
	var enabled int
	err := s.reader.QueryRowContext(ctx, `
		SELECT id, enabled, source_url, sha256, manifest_json, settings_json, allowed_origins_json, last_error, created_at, updated_at
		FROM provider_plugins
		WHERE id = ?
	`, id).Scan(
		&rec.ID,
		&enabled,
		&rec.SourceURL,
		&rec.SHA256,
		&rec.ManifestJSON,
		&rec.SettingsJSON,
		&rec.AllowedOriginsJSON,
		&rec.LastError,
		&rec.CreatedAt,
		&rec.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return ProviderPluginRecord{}, false, nil
	}
	if err != nil {
		return ProviderPluginRecord{}, false, err
	}
	rec.Enabled = enabled == 1
	return rec, true, nil
}

// UpsertProviderPlugin inserts or updates a provider plugin record atomically.
func (s *Store) UpsertProviderPlugin(ctx context.Context, rec ProviderPluginRecord) error {
	rec.ID = strings.TrimSpace(rec.ID)
	if rec.ID == "" {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件 ID 不能为空")
	}
	if strings.TrimSpace(rec.ManifestJSON) == "" {
		rec.ManifestJSON = "{}"
	}
	if strings.TrimSpace(rec.SettingsJSON) == "" {
		rec.SettingsJSON = "{}"
	}
	if strings.TrimSpace(rec.AllowedOriginsJSON) == "" {
		rec.AllowedOriginsJSON = "[]"
	}
	if !json.Valid([]byte(rec.ManifestJSON)) || !json.Valid([]byte(rec.SettingsJSON)) || !json.Valid([]byte(rec.AllowedOriginsJSON)) {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件配置 JSON 格式无效")
	}
	now := time.Now().UTC().UnixMilli()
	if rec.CreatedAt <= 0 {
		rec.CreatedAt = now
	}
	if rec.UpdatedAt <= 0 {
		rec.UpdatedAt = now
	}

	return s.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO provider_plugins(
				id, enabled, source_url, sha256, manifest_json, settings_json, allowed_origins_json, last_error, created_at, updated_at
			)
			VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(id) DO UPDATE SET
				enabled = excluded.enabled,
				source_url = excluded.source_url,
				sha256 = excluded.sha256,
				manifest_json = excluded.manifest_json,
				settings_json = excluded.settings_json,
				allowed_origins_json = excluded.allowed_origins_json,
				last_error = excluded.last_error,
				updated_at = excluded.updated_at
		`,
			rec.ID,
			boolToInt(rec.Enabled),
			rec.SourceURL,
			rec.SHA256,
			rec.ManifestJSON,
			rec.SettingsJSON,
			rec.AllowedOriginsJSON,
			rec.LastError,
			rec.CreatedAt,
			rec.UpdatedAt,
		)
		return err
	})
}

// DeleteProviderPlugin removes a provider plugin record by ID and reports whether it existed.
func (s *Store) DeleteProviderPlugin(ctx context.Context, id string) (bool, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return false, nil
	}
	var deleted bool
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `DELETE FROM provider_plugins WHERE id = ?`, id)
		if err != nil {
			return err
		}
		n, _ := res.RowsAffected()
		deleted = n > 0
		return nil
	})
	return deleted, err
}
