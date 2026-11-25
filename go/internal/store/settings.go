package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

const (
	NamespaceRuntimeConfig   = "runtime_config"
	NamespaceLibraryConfig   = "library_setting"
	NamespaceNavidrome       = "navidrome"
	NamespaceLibraryIndex    = "library_index"
	NamespaceUIPreferences   = "ui_preferences"
	NamespaceAuthMediaSecret = "auth_media_secret"
	NamespaceSystemUpdate    = "system_update"
)

type SettingRecord struct {
	Namespace string
	BodyJSON  string
	Revision  int64
	UpdatedAt int64
}

type LibraryIndexState struct {
	Revision           int64           `json:"revision"`
	Indexing           bool            `json:"indexing"`
	CoverBackfillRoots map[string]bool `json:"cover_backfill_roots,omitempty"`
}

func (s *Store) GetSetting(ctx context.Context, namespace string) (SettingRecord, bool, error) {
	var rec SettingRecord
	rec.Namespace = namespace
	err := s.reader.QueryRowContext(ctx, `
		SELECT body_json, revision, updated_at
		FROM settings
		WHERE namespace = ?
	`, namespace).Scan(&rec.BodyJSON, &rec.Revision, &rec.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return SettingRecord{}, false, nil
	}
	if err != nil {
		return SettingRecord{}, false, err
	}
	return rec, true, nil
}

// PutSetting writes a settings namespace. If expectedRevision > 0, it performs CAS.
func (s *Store) PutSetting(ctx context.Context, namespace, bodyJSON string, expectedRevision int64) (int64, error) {
	if !json.Valid([]byte(bodyJSON)) {
		return 0, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "配置 JSON 无效")
	}
	now := time.Now().UTC().UnixMilli()
	var newRev int64
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		if expectedRevision > 0 {
			res, err := tx.ExecContext(ctx, `
				UPDATE settings
				SET body_json = ?, revision = revision + 1, updated_at = ?
				WHERE namespace = ? AND revision = ?
			`, bodyJSON, now, namespace, expectedRevision)
			if err != nil {
				return err
			}
			n, _ := res.RowsAffected()
			if n == 0 {
				return model.NewError(model.ErrConflict, http.StatusConflict, "配置版本冲突，请刷新后重试")
			}
			newRev = expectedRevision + 1
			return nil
		}
		err := tx.QueryRowContext(ctx, `
			INSERT INTO settings(namespace, body_json, revision, updated_at)
			VALUES(?, ?, 1, ?)
			ON CONFLICT(namespace) DO UPDATE SET
				body_json = excluded.body_json,
				revision = settings.revision + 1,
				updated_at = excluded.updated_at
			RETURNING revision
		`, namespace, bodyJSON, now).Scan(&newRev)
		return err
	})
	return newRev, err
}

func (s *Store) GetLibraryIndexState(ctx context.Context) (LibraryIndexState, error) {
	rec, ok, err := s.GetSetting(ctx, NamespaceLibraryIndex)
	if err != nil {
		return LibraryIndexState{}, err
	}
	if !ok {
		return LibraryIndexState{Revision: 1, Indexing: false}, nil
	}
	var state LibraryIndexState
	if err := json.Unmarshal([]byte(rec.BodyJSON), &state); err != nil {
		return LibraryIndexState{}, err
	}
	if state.Revision <= 0 {
		state.Revision = 1
	}
	return state, nil
}

func incrementIndexRevisionTx(ctx context.Context, tx *sql.Tx, indexing *bool, nowMS int64) (LibraryIndexState, error) {
	var raw string
	err := tx.QueryRowContext(ctx, `SELECT body_json FROM settings WHERE namespace = ?`, NamespaceLibraryIndex).Scan(&raw)
	state := LibraryIndexState{Revision: 1, Indexing: false}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return state, err
	}
	if err == nil {
		_ = json.Unmarshal([]byte(raw), &state)
		if state.Revision <= 0 {
			state.Revision = 1
		}
	}
	state.Revision++
	if indexing != nil {
		state.Indexing = *indexing
	}
	body, _ := json.Marshal(state)
	_, err = tx.ExecContext(ctx, `
		INSERT INTO settings(namespace, body_json, revision, updated_at)
		VALUES(?, ?, 1, ?)
		ON CONFLICT(namespace) DO UPDATE SET
			body_json = excluded.body_json,
			revision = settings.revision + 1,
			updated_at = excluded.updated_at
	`, NamespaceLibraryIndex, string(body), nowMS)
	return state, err
}

func (s *Store) SetLibraryIndexing(ctx context.Context, indexing bool, bumpRevision bool) (LibraryIndexState, error) {
	var out LibraryIndexState
	now := time.Now().UTC().UnixMilli()
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		var raw string
		err := tx.QueryRowContext(ctx, `SELECT body_json FROM settings WHERE namespace = ?`, NamespaceLibraryIndex).Scan(&raw)
		out = LibraryIndexState{Revision: 1, Indexing: indexing}
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if err == nil {
			_ = json.Unmarshal([]byte(raw), &out)
			if out.Revision <= 0 {
				out.Revision = 1
			}
			out.Indexing = indexing
		}
		if bumpRevision {
			out.Revision++
		}
		body, _ := json.Marshal(out)
		_, err = tx.ExecContext(ctx, `
			INSERT INTO settings(namespace, body_json, revision, updated_at)
			VALUES(?, ?, 1, ?)
			ON CONFLICT(namespace) DO UPDATE SET
				body_json = excluded.body_json,
				revision = settings.revision + 1,
				updated_at = excluded.updated_at
		`, NamespaceLibraryIndex, string(body), now)
		return err
	})
	return out, err
}

func (s *Store) MarkLibraryRootCoverBackfilled(ctx context.Context, rootID string) error {
	if rootID == "" {
		return nil
	}
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		var raw string
		err := tx.QueryRowContext(ctx, `SELECT body_json FROM settings WHERE namespace = ?`, NamespaceLibraryIndex).Scan(&raw)
		out := LibraryIndexState{Revision: 1}
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if err == nil {
			_ = json.Unmarshal([]byte(raw), &out)
			if out.Revision <= 0 {
				out.Revision = 1
			}
		}
		if out.CoverBackfillRoots != nil && out.CoverBackfillRoots[rootID] {
			return nil
		}
		if out.CoverBackfillRoots == nil {
			out.CoverBackfillRoots = make(map[string]bool)
		}
		out.CoverBackfillRoots[rootID] = true
		body, _ := json.Marshal(out)
		_, err = tx.ExecContext(ctx, `
			INSERT INTO settings(namespace, body_json, revision, updated_at)
			VALUES(?, ?, 1, ?)
			ON CONFLICT(namespace) DO UPDATE SET
				body_json = excluded.body_json,
				revision = settings.revision + 1,
				updated_at = excluded.updated_at
		`, NamespaceLibraryIndex, string(body), now)
		return err
	})
}
