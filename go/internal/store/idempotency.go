package store

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
)

// IdempotencyStore persists operation_requests in SQLite for operations.Registry.
type IdempotencyStore struct {
	store *Store
	ttl   time.Duration
}

func NewIdempotencyStore(s *Store, ttl time.Duration) *IdempotencyStore {
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	return &IdempotencyStore{store: s, ttl: ttl}
}

func (i *IdempotencyStore) Lookup(ctx context.Context, actorKey, operationID, key string) (operations.IdempotencyRecord, bool, error) {
	var rec operations.IdempotencyRecord
	var resultRef string
	var expiresAt int64
	err := i.store.reader.QueryRowContext(ctx, `
		SELECT request_hash, result_ref, created_at, expires_at
		FROM operation_requests
		WHERE owner_key = ? AND operation = ? AND idempotency_key = ?
	`, actorKey, operationID, key).Scan(&rec.PayloadHash, &resultRef, &rec.CreatedAt, &expiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return operations.IdempotencyRecord{}, false, nil
	}
	if err != nil {
		return operations.IdempotencyRecord{}, false, err
	}
	now := time.Now().UTC().UnixMilli()
	if expiresAt > 0 && expiresAt < now {
		return operations.IdempotencyRecord{}, false, nil
	}
	rec.ActorKey = actorKey
	rec.OperationID = operationID
	rec.IdempotencyKey = key
	rec.ResultJSON = []byte(resultRef)
	return rec, true, nil
}

func (i *IdempotencyStore) Save(ctx context.Context, rec operations.IdempotencyRecord) error {
	createdAt := rec.CreatedAt
	if createdAt <= 0 {
		createdAt = time.Now().UTC().UnixMilli()
	} else if createdAt < 1_000_000_000_000 {
		// operations.Registry supplies seconds; normalize to UTC Unix ms.
		createdAt *= 1000
	}
	expiresAt := createdAt + i.ttl.Milliseconds()
	now := time.Now().UTC().UnixMilli()
	return i.store.WithTx(ctx, func(tx *sql.Tx) error {
		var existingHash string
		var existingExpires int64
		qErr := tx.QueryRowContext(ctx, `
			SELECT request_hash, expires_at
			FROM operation_requests
			WHERE owner_key = ? AND operation = ? AND idempotency_key = ?
		`, rec.ActorKey, rec.OperationID, rec.IdempotencyKey).Scan(&existingHash, &existingExpires)
		if qErr == nil && (existingExpires == 0 || existingExpires >= now) {
			if existingHash != rec.PayloadHash {
				return model.NewError(model.ErrConflict, http.StatusConflict, "幂等键冲突：请求参数与此前提交不一致")
			}
			return nil
		}
		if qErr != nil && !errors.Is(qErr, sql.ErrNoRows) {
			return qErr
		}
		_, err := tx.ExecContext(ctx, `
			INSERT INTO operation_requests(owner_key, operation, idempotency_key, request_hash, result_ref, created_at, expires_at)
			VALUES(?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(owner_key, operation, idempotency_key) DO UPDATE SET
				request_hash = excluded.request_hash,
				result_ref = excluded.result_ref,
				created_at = excluded.created_at,
				expires_at = excluded.expires_at
		`, rec.ActorKey, rec.OperationID, rec.IdempotencyKey, rec.PayloadHash, string(rec.ResultJSON), createdAt, expiresAt)
		return err
	})
}

func (i *IdempotencyStore) PruneExpired(ctx context.Context, nowMS int64) (int64, error) {
	if nowMS <= 0 {
		nowMS = time.Now().UTC().UnixMilli()
	}
	var deleted int64
	err := i.store.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `DELETE FROM operation_requests WHERE expires_at > 0 AND expires_at < ?`, nowMS)
		if err != nil {
			return err
		}
		deleted, _ = res.RowsAffected()
		return nil
	})
	return deleted, err
}
