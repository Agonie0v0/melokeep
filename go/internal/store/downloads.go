package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

type CreateDownloadParams struct {
	ID             string
	TrackID        string
	VariantID      string
	PublicID       string
	QualityID      string
	QualityLabel   string
	DownloadSource string
	ActorKey       string
	DedupeKey      string
	ForceNew       bool
	IntentNonce    string
	ReuseCompleted bool
	Detail         string
	SettingsJSON   string
	// Optional transport-level idempotency persisted in the same transaction
	Operation      string
	IdempotencyKey string
	RequestHash    string
	IdempotencyTTL time.Duration
}

type CreateDownloadResult struct {
	Download  model.Download
	Created   bool
	Reused    bool
	SameOwner bool
}

// CreateDownload atomically enforces transport idempotency (operation_requests) and
// business active/completed dedupe without holding network or file I/O.
func (s *Store) CreateDownload(ctx context.Context, p CreateDownloadParams) (CreateDownloadResult, error) {
	if p.ActorKey == "" || p.DedupeKey == "" || p.PublicID == "" {
		return CreateDownloadResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "下载创建参数不完整")
	}
	if p.ForceNew && strings.TrimSpace(p.IntentNonce) == "" {
		return CreateDownloadResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "force_new 为 true 时必须提供非空 intent_nonce")
	}
	if p.ID == "" {
		p.ID = model.NewUUID()
	}
	if p.SettingsJSON == "" {
		p.SettingsJSON = "{}"
	}
	intentScope := "default"
	if nonce := strings.TrimSpace(p.IntentNonce); nonce != "" {
		intentScope = "force:" + nonce
	}
	now := time.Now().UTC().UnixMilli()
	ttl := p.IdempotencyTTL
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}

	var res CreateDownloadResult
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		if p.IdempotencyKey != "" && p.Operation != "" {
			var existingHash, resultRef string
			var expiresAt int64
			err := tx.QueryRowContext(ctx, `
				SELECT request_hash, result_ref, expires_at
				FROM operation_requests
				WHERE owner_key = ? AND operation = ? AND idempotency_key = ?
			`, p.ActorKey, p.Operation, p.IdempotencyKey).Scan(&existingHash, &resultRef, &expiresAt)
			if err == nil && (expiresAt == 0 || expiresAt >= now) {
				if existingHash != p.RequestHash {
					return model.NewError(model.ErrConflict, http.StatusConflict, "幂等键冲突：请求参数与此前提交不一致")
				}
				var cached CreateDownloadResult
				if json.Unmarshal([]byte(resultRef), &cached) == nil && cached.Download.ID != "" {
					dl, ok, gErr := getDownloadTx(ctx, tx, cached.Download.ID)
					if gErr != nil {
						return gErr
					}
					if ok {
						res = CreateDownloadResult{
							Download:  dl,
							Created:   false,
							Reused:    cached.Reused,
							SameOwner: dl.ActorKey == p.ActorKey,
						}
						return nil
					}
				}
			} else if err != nil && !errors.Is(err, sql.ErrNoRows) {
				return err
			}
		}

		if intentScope == "default" {
			var activeID string
			err := tx.QueryRowContext(ctx, `
				SELECT id FROM downloads
				WHERE dedupe_key = ? AND intent_scope = 'default'
				  AND state IN ('queued','resolving','downloading','verifying','publishing','pause_requested','paused','retry_wait')
				LIMIT 1
			`, p.DedupeKey).Scan(&activeID)
			if err == nil {
				dl, _, gErr := getDownloadTx(ctx, tx, activeID)
				if gErr != nil {
					return gErr
				}
				res = CreateDownloadResult{
					Download:  dl,
					Created:   false,
					Reused:    true,
					SameOwner: dl.ActorKey == p.ActorKey,
				}
				return persistDownloadIdempotencyTx(ctx, tx, p, res, now, ttl)
			} else if !errors.Is(err, sql.ErrNoRows) {
				return err
			}

			if p.ReuseCompleted {
				var compID string
				err := tx.QueryRowContext(ctx, `
					SELECT d.id
					FROM downloads d
					JOIN download_outputs o ON o.download_id = d.id
					JOIN assets a ON a.id = o.asset_id
					WHERE d.dedupe_key = ? AND d.state = 'complete' AND a.status = 'active'
					ORDER BY d.updated_at DESC
					LIMIT 1
				`, p.DedupeKey).Scan(&compID)
				if err == nil {
					dl, _, gErr := getDownloadTx(ctx, tx, compID)
					if gErr != nil {
						return gErr
					}
					res = CreateDownloadResult{
						Download:  dl,
						Created:   false,
						Reused:    true,
						SameOwner: dl.ActorKey == p.ActorKey,
					}
					return persistDownloadIdempotencyTx(ctx, tx, p, res, now, ttl)
				} else if !errors.Is(err, sql.ErrNoRows) {
					return err
				}
			}
		}

		var trackArg, variantArg any
		if p.TrackID != "" {
			trackArg = p.TrackID
		}
		if p.VariantID != "" {
			variantArg = p.VariantID
		}
		_, err := tx.ExecContext(ctx, `
			INSERT INTO downloads(
				id, track_id, variant_id, public_id, quality_id, quality_label, download_source,
				owner_key, dedupe_key, intent_scope, state, attempt, bytes_done, bytes_total,
				cancel_requested, revision, detail, file_token, intent_nonce, next_attempt_at,
				error_json, settings_json, created_at, updated_at
			) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'queued', 0, 0, NULL, 0, 1, ?, NULL, ?, 0, '{}', ?, ?, ?)
		`, p.ID, trackArg, variantArg, p.PublicID, p.QualityID, p.QualityLabel, p.DownloadSource,
			p.ActorKey, p.DedupeKey, intentScope, p.Detail, strings.TrimSpace(p.IntentNonce), p.SettingsJSON, now, now)
		if err != nil {
			if isUniqueConstraintErr(err) {
				return model.NewError(model.ErrConflict, http.StatusConflict, "存在重复的活动下载意图")
			}
			return err
		}
		dl, _, gErr := getDownloadTx(ctx, tx, p.ID)
		if gErr != nil {
			return gErr
		}
		res = CreateDownloadResult{
			Download:  dl,
			Created:   true,
			Reused:    false,
			SameOwner: true,
		}
		return persistDownloadIdempotencyTx(ctx, tx, p, res, now, ttl)
	})
	return res, err
}

func persistDownloadIdempotencyTx(ctx context.Context, tx *sql.Tx, p CreateDownloadParams, res CreateDownloadResult, now int64, ttl time.Duration) error {
	if p.IdempotencyKey == "" || p.Operation == "" {
		return nil
	}
	encoded, _ := json.Marshal(res)
	_, err := tx.ExecContext(ctx, `
		INSERT INTO operation_requests(owner_key, operation, idempotency_key, request_hash, result_ref, created_at, expires_at)
		VALUES(?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(owner_key, operation, idempotency_key) DO UPDATE SET
			request_hash = excluded.request_hash,
			result_ref = excluded.result_ref,
			created_at = excluded.created_at,
			expires_at = excluded.expires_at
	`, p.ActorKey, p.Operation, p.IdempotencyKey, p.RequestHash, string(encoded), now, now+ttl.Milliseconds())
	return err
}

func getDownloadTx(ctx context.Context, q interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, id string) (model.Download, bool, error) {
	var d model.Download
	var state string
	var total sql.NullInt64
	var fileTok sql.NullString
	err := q.QueryRowContext(ctx, `
		SELECT id, owner_key, public_id, quality_id, quality_label, download_source,
		       state, revision, attempt, bytes_done, bytes_total, detail, file_token,
		       intent_nonce, next_attempt_at, created_at, updated_at
		FROM downloads WHERE id = ?
	`, id).Scan(
		&d.ID, &d.ActorKey, &d.PublicID, &d.QualityID, &d.QualityLabel, &d.DownloadSource,
		&state, &d.Revision, &d.Attempt, &d.Bytes, &total, &d.Detail, &fileTok,
		&d.IntentNonce, &d.NextAttemptAt, &d.CreatedAt, &d.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return model.Download{}, false, nil
	}
	if err != nil {
		return model.Download{}, false, err
	}
	d.Status = model.DownloadStatus(state)
	if total.Valid {
		val := total.Int64
		d.TotalBytes = &val
	}
	if fileTok.Valid {
		val := fileTok.String
		d.File = &val
	}
	return d, true, nil
}

func (s *Store) GetDownload(ctx context.Context, id string) (model.Download, bool, error) {
	return getDownloadTx(ctx, s.reader, id)
}

func (s *Store) ListDownloads(ctx context.Context, limit int) ([]model.Download, error) {
	if limit <= 0 {
		limit = 200
	}
	if limit > 2000 {
		limit = 2000
	}
	rows, err := s.reader.QueryContext(ctx, `
		SELECT id, owner_key, public_id, quality_id, quality_label, download_source,
		       state, revision, attempt, bytes_done, bytes_total, detail, file_token,
		       intent_nonce, next_attempt_at, created_at, updated_at
		FROM downloads
		ORDER BY created_at DESC, id DESC
		LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.Download
	for rows.Next() {
		var d model.Download
		var state string
		var total sql.NullInt64
		var fileTok sql.NullString
		if err := rows.Scan(
			&d.ID, &d.ActorKey, &d.PublicID, &d.QualityID, &d.QualityLabel, &d.DownloadSource,
			&state, &d.Revision, &d.Attempt, &d.Bytes, &total, &d.Detail, &fileTok,
			&d.IntentNonce, &d.NextAttemptAt, &d.CreatedAt, &d.UpdatedAt,
		); err != nil {
			return nil, err
		}
		d.Status = model.DownloadStatus(state)
		if total.Valid {
			v := total.Int64
			d.TotalBytes = &v
		}
		if fileTok.Valid {
			v := fileTok.String
			d.File = &v
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

type CASDownloadParams struct {
	ID               string
	ExpectedRevision int64
	AllowedStates    []model.DownloadStatus
	NewState         model.DownloadStatus
	Attempt          *int
	BytesDone        *int64
	BytesTotal       *int64
	CancelRequested  *bool
	Detail           *string
	FileToken        *string
	NextAttemptAt    *int64
	ErrorJSON        *string
}

// CompareAndSetDownload performs optimistic CAS on download state/revision/attempt.
func (s *Store) CompareAndSetDownload(ctx context.Context, p CASDownloadParams) (model.Download, error) {
	if p.ID == "" || len(p.AllowedStates) == 0 || p.NewState == "" {
		return model.Download{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "CAS 下载更新参数不完整")
	}
	now := time.Now().UTC().UnixMilli()
	var updated model.Download
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		cur, ok, err := getDownloadTx(ctx, tx, p.ID)
		if err != nil {
			return err
		}
		if !ok {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "下载任务不存在")
		}
		if cur.Revision != p.ExpectedRevision {
			return model.NewError(model.ErrConflict, http.StatusConflict, "下载任务版本已变更，忽略过期更新")
		}

		setParts := []string{`state = ?`, `revision = revision + 1`, `updated_at = ?`}
		args := []any{string(p.NewState), now}
		if p.Attempt != nil {
			setParts = append(setParts, `attempt = ?`)
			args = append(args, *p.Attempt)
		}
		if p.BytesDone != nil {
			setParts = append(setParts, `bytes_done = ?`)
			args = append(args, *p.BytesDone)
		}
		if p.BytesTotal != nil {
			setParts = append(setParts, `bytes_total = ?`)
			args = append(args, *p.BytesTotal)
		}
		if p.CancelRequested != nil {
			setParts = append(setParts, `cancel_requested = ?`)
			args = append(args, boolToInt(*p.CancelRequested))
		}
		if p.Detail != nil {
			setParts = append(setParts, `detail = ?`)
			args = append(args, *p.Detail)
		}
		if p.FileToken != nil {
			setParts = append(setParts, `file_token = ?`)
			args = append(args, *p.FileToken)
		}
		if p.NextAttemptAt != nil {
			setParts = append(setParts, `next_attempt_at = ?`)
			args = append(args, *p.NextAttemptAt)
		}
		if p.ErrorJSON != nil {
			setParts = append(setParts, `error_json = ?`)
			args = append(args, *p.ErrorJSON)
		}

		placeholders := make([]string, len(p.AllowedStates))
		args = append(args, p.ID, p.ExpectedRevision)
		for i, st := range p.AllowedStates {
			placeholders[i] = "?"
			args = append(args, string(st))
		}

		q := fmt.Sprintf(
			`UPDATE downloads SET %s WHERE id = ? AND revision = ? AND state IN (%s)`,
			strings.Join(setParts, ", "),
			strings.Join(placeholders, ", "),
		)
		res, err := tx.ExecContext(ctx, q, args...)
		if err != nil {
			if isUniqueConstraintErr(err) {
				return model.NewError(model.ErrConflict, http.StatusConflict, "活动下载去重冲突")
			}
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrConflict, http.StatusConflict, "下载任务状态不允许当前转换")
		}
		updated, _, err = getDownloadTx(ctx, tx, p.ID)
		return err
	})
	return updated, err
}

func (s *Store) DeleteDownloadRecord(ctx context.Context, id string) error {
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		cur, ok, err := getDownloadTx(ctx, tx, id)
		if err != nil {
			return err
		}
		if !ok {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "下载任务不存在")
		}
		switch cur.Status {
		case model.DownloadQueued, model.DownloadResolving, model.DownloadDownloading, model.DownloadVerifying, model.DownloadPublishing:
			return model.NewError(model.ErrConflict, http.StatusConflict, "请先取消或暂停下载，等待任务停止后再移除记录")
		}
		_, _ = tx.ExecContext(ctx, `DELETE FROM transfer_checkpoints WHERE download_id = ?`, id)
		_, _ = tx.ExecContext(ctx, `DELETE FROM download_outputs WHERE download_id = ?`, id)
		_, _ = tx.ExecContext(ctx, `UPDATE library_jobs SET source_download_id = NULL WHERE source_download_id = ?`, id)
		_, err = tx.ExecContext(ctx, `DELETE FROM downloads WHERE id = ?`, id)
		return err
	})
}

func (s *Store) UpsertTransferCheckpoint(ctx context.Context, cp model.TransferCheckpoint, urlFingerprint, validatorType, validator string, complete bool) error {
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		var totalVal any
		if cp.TotalBytes != nil {
			totalVal = *cp.TotalBytes
		}
		_, err := tx.ExecContext(ctx, `
			INSERT INTO transfer_checkpoints(
				download_id, attempt, part_rel_path, url_fingerprint, validator_type, validator,
				expected_length, durable_bytes, complete, updated_at
			) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(download_id) DO UPDATE SET
				attempt = excluded.attempt,
				part_rel_path = excluded.part_rel_path,
				url_fingerprint = excluded.url_fingerprint,
				validator_type = excluded.validator_type,
				validator = excluded.validator,
				expected_length = excluded.expected_length,
				durable_bytes = excluded.durable_bytes,
				complete = excluded.complete,
				updated_at = excluded.updated_at
		`, cp.DownloadID, cp.Attempt, cp.TempPath, urlFingerprint, validatorType, validator,
			totalVal, cp.Bytes, boolToInt(complete), now)
		return err
	})
}

type CheckpointRecord struct {
	Checkpoint     model.TransferCheckpoint
	URLFingerprint string
	ValidatorType  string
	Validator      string
	Complete       bool
}

func (s *Store) GetTransferCheckpoint(ctx context.Context, downloadID string) (CheckpointRecord, bool, error) {
	var rec CheckpointRecord
	var total sql.NullInt64
	var complete int
	err := s.reader.QueryRowContext(ctx, `
		SELECT download_id, attempt, part_rel_path, url_fingerprint, validator_type, validator,
		       expected_length, durable_bytes, complete, updated_at
		FROM transfer_checkpoints WHERE download_id = ?
	`, downloadID).Scan(
		&rec.Checkpoint.DownloadID, &rec.Checkpoint.Attempt, &rec.Checkpoint.TempPath,
		&rec.URLFingerprint, &rec.ValidatorType, &rec.Validator,
		&total, &rec.Checkpoint.Bytes, &complete, &rec.Checkpoint.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return CheckpointRecord{}, false, nil
	}
	if err != nil {
		return CheckpointRecord{}, false, err
	}
	if total.Valid {
		v := total.Int64
		rec.Checkpoint.TotalBytes = &v
	}
	rec.Complete = complete == 1
	return rec, true, nil
}

type PublishedOutputSpec struct {
	Asset      AssetRecord
	AliasToken string
	Role       string
	LyricsPath string
	CoverPath  string
}

type AutoOrganizeJobSpec struct {
	JobID        string
	Action       string
	SettingsJSON string
	OptionsJSON  string
	Detail       string
	OwnerKey     string
	Items        []LibraryItemInput
}

type CommitPublishedOutputsParams struct {
	DownloadID       string
	ExpectedRevision int64
	FileToken        string
	Detail           string
	BytesDone        int64
	BytesTotal       *int64
	FileOperationID  string
	Outputs          []PublishedOutputSpec
	AutoOrganize     *AutoOrganizeJobSpec
	Outbox           *OutboxEnqueueParams
}

// CommitPublishedOutputs commits published assets, download_outputs, download completion,
// index revision bump, and optional auto-organize LibraryJob in a single short SQLite transaction.
func (s *Store) CommitPublishedOutputs(ctx context.Context, p CommitPublishedOutputsParams) (model.Download, error) {
	if p.DownloadID == "" || len(p.Outputs) == 0 {
		return model.Download{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "提交发布输出参数不完整")
	}
	now := time.Now().UTC().UnixMilli()
	var done model.Download
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		cur, ok, err := getDownloadTx(ctx, tx, p.DownloadID)
		if err != nil {
			return err
		}
		if !ok {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "下载任务不存在")
		}
		if cur.Revision != p.ExpectedRevision {
			return model.NewError(model.ErrConflict, http.StatusConflict, "下载任务版本已变更")
		}
		switch cur.Status {
		case model.DownloadPublishing, model.DownloadVerifying, model.DownloadDownloading:
		default:
			return model.NewError(model.ErrConflict, http.StatusConflict, "下载任务当前状态不能提交发布结果")
		}

		assetIDs := make([]string, len(p.Outputs))
		for idx, out := range p.Outputs {
			a := out.Asset
			if a.ID == "" {
				a.ID = model.NewUUID()
			}
			if a.Status == "" {
				a.Status = "active"
			}
			if a.PathKey == "" {
				a.PathKey = a.RelPath
			}
			if a.OriginJSON == "" {
				a.OriginJSON = "{}"
			}
			metaJSON, _ := json.Marshal(nonNilMap(a.Metadata))
			locksJSON, _ := json.Marshal(nonNilSlice(a.LockedFields))
			a, err = upsertAssetTx(ctx, tx, a, string(metaJSON), string(locksJSON), 0, false, now)
			if err != nil {
				return err
			}
			assetIDs[idx] = a.ID

			alias := out.AliasToken
			if alias == "" {
				alias = "asset:" + a.ID
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO asset_aliases(alias_token, asset_id) VALUES(?, ?)
				ON CONFLICT(alias_token) DO UPDATE SET asset_id = excluded.asset_id
			`, alias, a.ID); err != nil {
				return err
			}
			if err := putPublicRefTx(ctx, tx, model.PublicRef{
				PublicID:  alias,
				Kind:      "asset",
				TargetID:  a.ID,
				Source:    "local",
				SourceID:  a.ID,
				CreatedAt: now,
			}, "", now); err != nil {
				return err
			}

			role := out.Role
			if role == "" {
				role = "audio"
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO download_outputs(download_id, ordinal, asset_id, role, rel_path, lyrics_path, cover_path, created_at)
				VALUES(?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(download_id, ordinal) DO UPDATE SET
					asset_id = excluded.asset_id,
					role = excluded.role,
					rel_path = excluded.rel_path,
					lyrics_path = excluded.lyrics_path,
					cover_path = excluded.cover_path
			`, p.DownloadID, idx, a.ID, role, a.RelPath, out.LyricsPath, out.CoverPath, now); err != nil {
				return err
			}
		}

		var totalArg any
		if p.BytesTotal != nil {
			totalArg = *p.BytesTotal
		}
		res, err := tx.ExecContext(ctx, `
			UPDATE downloads
			SET state = 'complete', file_token = ?, detail = ?, bytes_done = ?, bytes_total = ?,
			    revision = revision + 1, updated_at = ?
			WHERE id = ? AND revision = ?
		`, p.FileToken, p.Detail, p.BytesDone, totalArg, now, p.DownloadID, p.ExpectedRevision)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrConflict, http.StatusConflict, "下载完成提交并发冲突")
		}
		_, _ = tx.ExecContext(ctx, `UPDATE transfer_checkpoints SET complete = 1, updated_at = ? WHERE download_id = ?`, now, p.DownloadID)

		if p.FileOperationID != "" {
			if _, err := tx.ExecContext(ctx, `
				UPDATE file_operations SET state = 'committed', committed_at = ?, updated_at = ? WHERE id = ?
			`, now, now, p.FileOperationID); err != nil {
				return err
			}
		}

		if _, err := incrementIndexRevisionTx(ctx, tx, nil, now); err != nil {
			return err
		}

		if p.AutoOrganize != nil {
			job := p.AutoOrganize
			if job.JobID == "" {
				job.JobID = model.NewUUID()
			}
			if job.SettingsJSON == "" {
				job.SettingsJSON = "{}"
			}
			if job.OptionsJSON == "" {
				job.OptionsJSON = "{}"
			}
			if job.OwnerKey == "" {
				job.OwnerKey = cur.ActorKey
			}
			_, err := tx.ExecContext(ctx, `
				INSERT INTO library_jobs(
					id, source_download_id, action, state, settings_json, options_json,
					warnings_json, detail, total, processed, revision, owner_key, created_at, updated_at
				) VALUES(?, ?, ?, 'queued', ?, ?, '[]', ?, ?, 0, 1, ?, ?, ?)
				ON CONFLICT(source_download_id) WHERE source_download_id IS NOT NULL DO NOTHING
			`, job.JobID, p.DownloadID, job.Action, job.SettingsJSON, job.OptionsJSON, job.Detail, len(job.Items), job.OwnerKey, now, now)
			if err != nil {
				return err
			}
			var actualJobID string
			if err := tx.QueryRowContext(ctx, `SELECT id FROM library_jobs WHERE source_download_id = ?`, p.DownloadID).Scan(&actualJobID); err != nil {
				return err
			}
			if actualJobID == job.JobID {
				for ord, it := range job.Items {
					if it.AssetID == "" && ord < len(assetIDs) {
						it.AssetID = assetIDs[ord]
					}
					if err := insertLibraryItemTx(ctx, tx, actualJobID, ord, it); err != nil {
						return err
					}
				}
			}
		}

		if p.Outbox != nil {
			if err := enqueueOutboxTx(ctx, tx, *p.Outbox, now); err != nil {
				return err
			}
		}

		done, _, err = getDownloadTx(ctx, tx, p.DownloadID)
		return err
	})
	return done, err
}
