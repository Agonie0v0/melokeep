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

type LibraryItemInput struct {
	ItemID                string
	AssetID               string
	PathToken             string
	Name                  string
	OriginalPath          string
	TargetPath            string
	State                 model.LibraryItemStatus
	ExpectedRevision      int64
	BeforeJSON            string
	ProposedJSON          string
	SelectedCandidateJSON string
	LocksJSON             string
	WarningsJSON          string
}

type CreateLibraryPreviewParams struct {
	JobID            string
	SourceDownloadID string
	Action           string
	State            model.LibraryJobStatus
	SettingsJSON     string
	OptionsJSON      string
	WarningsJSON     string
	Detail           string
	OwnerKey         string
	Items            []LibraryItemInput
}

func insertLibraryItemTx(ctx context.Context, tx *sql.Tx, jobID string, ord int, it LibraryItemInput) error {
	if it.ItemID == "" {
		it.ItemID = model.NewUUID()
	}
	if it.State == "" {
		it.State = model.LibraryItemQueued
	}
	if it.ExpectedRevision <= 0 {
		it.ExpectedRevision = 1
	}
	if it.BeforeJSON == "" {
		it.BeforeJSON = "{}"
	}
	if it.ProposedJSON == "" {
		it.ProposedJSON = "{}"
	}
	if it.SelectedCandidateJSON == "" {
		it.SelectedCandidateJSON = "null"
	}
	if it.LocksJSON == "" {
		it.LocksJSON = "[]"
	}
	if it.WarningsJSON == "" {
		it.WarningsJSON = "[]"
	}
	var assetArg any
	if it.AssetID != "" {
		assetArg = it.AssetID
	}
	_, err := tx.ExecContext(ctx, `
		INSERT INTO library_items(
			job_id, ordinal, item_id, asset_id, path_token, name, original_path, target_path,
			state, expected_revision, before_json, proposed_json, selected_candidate_json,
			locks_json, warnings_json, error_json, operation_id
		) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', '')
	`, jobID, ord, it.ItemID, assetArg, it.PathToken, it.Name, it.OriginalPath, it.TargetPath,
		string(it.State), it.ExpectedRevision, it.BeforeJSON, it.ProposedJSON, it.SelectedCandidateJSON,
		it.LocksJSON, it.WarningsJSON)
	return err
}

func (s *Store) CreateLibraryPreview(ctx context.Context, p CreateLibraryPreviewParams) (model.LibraryJob, error) {
	if p.Action == "" || p.OwnerKey == "" {
		return model.LibraryJob{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "整理任务参数不完整")
	}
	if p.JobID == "" {
		p.JobID = model.NewUUID()
	}
	if p.State == "" {
		p.State = model.LibraryJobPreviewReady
	}
	if p.SettingsJSON == "" {
		p.SettingsJSON = "{}"
	}
	if p.OptionsJSON == "" {
		p.OptionsJSON = "{}"
	}
	if p.WarningsJSON == "" {
		p.WarningsJSON = "[]"
	}
	now := time.Now().UTC().UnixMilli()
	var srcDl any
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		if p.SourceDownloadID != "" {
			var dlExists int
			if qErr := tx.QueryRowContext(ctx, `SELECT 1 FROM downloads WHERE id = ?`, p.SourceDownloadID).Scan(&dlExists); qErr == nil && dlExists == 1 {
				srcDl = p.SourceDownloadID
				// Detach source_download_id from non-running historical jobs so manual re-organize
				// can link to the download without tripping the unique source_download_id index.
				if _, uErr := tx.ExecContext(ctx, `
					UPDATE library_jobs
					SET source_download_id = NULL
					WHERE source_download_id = ? AND state NOT IN ('queued', 'scanning', 'applying')
				`, p.SourceDownloadID); uErr != nil {
					return uErr
				}
			}
		}
		_, err := tx.ExecContext(ctx, `
			INSERT INTO library_jobs(
				id, source_download_id, action, state, settings_json, options_json,
				warnings_json, detail, total, processed, revision, owner_key, created_at, updated_at
			) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 1, ?, ?, ?)
		`, p.JobID, srcDl, p.Action, string(p.State), p.SettingsJSON, p.OptionsJSON, p.WarningsJSON, p.Detail, len(p.Items), p.OwnerKey, now, now)
		if err != nil {
			if isUniqueConstraintErr(err) {
				return model.NewError(model.ErrConflict, http.StatusConflict, "整理任务已存在")
			}
			return err
		}
		for ord, it := range p.Items {
			if err := insertLibraryItemTx(ctx, tx, p.JobID, ord, it); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return model.LibraryJob{}, err
	}
	var warns []string
	_ = json.Unmarshal([]byte(p.WarningsJSON), &warns)
	return model.LibraryJob{
		ID:        p.JobID,
		Action:    p.Action,
		Status:    p.State,
		CreatedAt: now,
		UpdatedAt: now,
		Total:     len(p.Items),
		Processed: 0,
		Detail:    p.Detail,
		Warnings:  nonNilSlice(warns),
	}, nil
}

func (s *Store) GetLibraryJob(ctx context.Context, jobID string) (model.LibraryJob, []model.LibraryItem, bool, error) {
	var j model.LibraryJob
	var state, warnsJSON string
	err := s.reader.QueryRowContext(ctx, `
		SELECT id, action, state, created_at, updated_at, total, processed, detail, warnings_json
		FROM library_jobs WHERE id = ?
	`, jobID).Scan(&j.ID, &j.Action, &state, &j.CreatedAt, &j.UpdatedAt, &j.Total, &j.Processed, &j.Detail, &warnsJSON)
	if errors.Is(err, sql.ErrNoRows) {
		return model.LibraryJob{}, nil, false, nil
	}
	if err != nil {
		return model.LibraryJob{}, nil, false, err
	}
	j.Status = model.LibraryJobStatus(state)
	_ = json.Unmarshal([]byte(warnsJSON), &j.Warnings)
	j.Warnings = nonNilSlice(j.Warnings)

	rows, err := s.reader.QueryContext(ctx, `
		SELECT item_id, COALESCE(asset_id,''), path_token, name, original_path, target_path, state, locks_json, warnings_json, operation_id
		FROM library_items WHERE job_id = ? ORDER BY ordinal ASC
	`, jobID)
	if err != nil {
		return model.LibraryJob{}, nil, false, err
	}
	defer rows.Close()
	var items []model.LibraryItem
	for rows.Next() {
		var it model.LibraryItem
		var st, locksJSON, iWarnsJSON string
		it.JobID = jobID
		if err := rows.Scan(&it.ID, &it.AssetID, &it.Path, &it.Name, &it.OriginalPath, &it.Target, &st, &locksJSON, &iWarnsJSON, &it.OperationID); err != nil {
			return model.LibraryJob{}, nil, false, err
		}
		it.Status = model.LibraryItemStatus(st)
		_ = json.Unmarshal([]byte(locksJSON), &it.Locks)
		_ = json.Unmarshal([]byte(iWarnsJSON), &it.Warnings)
		items = append(items, it)
	}
	return j, items, true, rows.Err()
}

func (s *Store) ListLibraryJobs(ctx context.Context, limit int) ([]model.LibraryJob, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.reader.QueryContext(ctx, `
		SELECT id, action, state, created_at, updated_at, total, processed, detail, warnings_json
		FROM library_jobs
		ORDER BY created_at DESC, id DESC
		LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var jobs []model.LibraryJob
	for rows.Next() {
		var j model.LibraryJob
		var st, warnsJSON string
		if err := rows.Scan(&j.ID, &j.Action, &st, &j.CreatedAt, &j.UpdatedAt, &j.Total, &j.Processed, &j.Detail, &warnsJSON); err != nil {
			return nil, err
		}
		j.Status = model.LibraryJobStatus(st)
		_ = json.Unmarshal([]byte(warnsJSON), &j.Warnings)
		j.Warnings = nonNilSlice(j.Warnings)
		jobs = append(jobs, j)
	}
	return jobs, rows.Err()
}

type LibraryJobRecord struct {
	model.LibraryJob
	OwnerKey         string
	SourceDownloadID string
	OptionsJSON      string
}

func (s *Store) GetLibraryJobRecord(ctx context.Context, jobID string) (LibraryJobRecord, bool, error) {
	var rec LibraryJobRecord
	var state, warnsJSON string
	err := s.reader.QueryRowContext(ctx, `
		SELECT id, action, state, created_at, updated_at, total, processed, detail, warnings_json,
		       owner_key, COALESCE(source_download_id,''), options_json
		FROM library_jobs WHERE id = ?
	`, jobID).Scan(
		&rec.ID, &rec.Action, &state, &rec.CreatedAt, &rec.UpdatedAt, &rec.Total, &rec.Processed, &rec.Detail, &warnsJSON,
		&rec.OwnerKey, &rec.SourceDownloadID, &rec.OptionsJSON,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return LibraryJobRecord{}, false, nil
	}
	if err != nil {
		return LibraryJobRecord{}, false, err
	}
	rec.Status = model.LibraryJobStatus(state)
	_ = json.Unmarshal([]byte(warnsJSON), &rec.Warnings)
	rec.Warnings = nonNilSlice(rec.Warnings)
	return rec, true, nil
}

func (s *Store) ListLibraryJobsByOwnerAndStates(ctx context.Context, ownerKey string, states []string) ([]LibraryJobRecord, error) {
	var conds []string
	var args []any
	if ownerKey != "" {
		conds = append(conds, "owner_key = ?")
		args = append(args, ownerKey)
	}
	if len(states) > 0 {
		placeholders := make([]string, len(states))
		for i, st := range states {
			placeholders[i] = "?"
			args = append(args, st)
		}
		conds = append(conds, "state IN ("+strings.Join(placeholders, ",")+")")
	}
	query := `
		SELECT id, action, state, created_at, updated_at, total, processed, detail, warnings_json,
		       owner_key, COALESCE(source_download_id,''), options_json
		FROM library_jobs`
	if len(conds) > 0 {
		query += " WHERE " + strings.Join(conds, " AND ")
	}
	query += " ORDER BY created_at DESC, id DESC"

	rows, err := s.reader.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []LibraryJobRecord
	for rows.Next() {
		var rec LibraryJobRecord
		var st, warnsJSON string
		if err := rows.Scan(
			&rec.ID, &rec.Action, &st, &rec.CreatedAt, &rec.UpdatedAt, &rec.Total, &rec.Processed, &rec.Detail, &warnsJSON,
			&rec.OwnerKey, &rec.SourceDownloadID, &rec.OptionsJSON,
		); err != nil {
			return nil, err
		}
		rec.Status = model.LibraryJobStatus(st)
		_ = json.Unmarshal([]byte(warnsJSON), &rec.Warnings)
		rec.Warnings = nonNilSlice(rec.Warnings)
		out = append(out, rec)
	}
	return out, rows.Err()
}

// DeleteLibraryJobs deletes library job rows and their items/metadata inside one transaction
// while keeping file_operations audit/backup records (detaching job_id) and never touching disk files.
func (s *Store) DeleteLibraryJobs(ctx context.Context, ids []string) (int, error) {
	cleanIDs := make([]string, 0, len(ids))
	seen := make(map[string]struct{}, len(ids))
	for _, raw := range ids {
		id := strings.TrimSpace(raw)
		if id == "" {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		cleanIDs = append(cleanIDs, id)
	}
	if len(cleanIDs) == 0 {
		return 0, nil
	}

	deleted := 0
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		for _, id := range cleanIDs {
			if _, err := tx.ExecContext(ctx, `UPDATE file_operations SET job_id = NULL WHERE job_id = ?`, id); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `DELETE FROM library_items WHERE job_id = ?`, id); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `DELETE FROM durable_records WHERE namespace = 'library_job_meta' AND id = ?`, id); err != nil {
				return err
			}
			res, err := tx.ExecContext(ctx, `DELETE FROM library_jobs WHERE id = ?`, id)
			if err != nil {
				return err
			}
			if n, _ := res.RowsAffected(); n > 0 {
				deleted += int(n)
			}
		}
		return nil
	})
	if err != nil {
		return 0, err
	}
	return deleted, nil
}

type OperationFileSpec struct {
	Ordinal       int
	Role          string
	SourceRefJSON string
	TargetRefJSON string
	StageRefJSON  string
	BackupRefJSON string
	BeforeHash    string
	AfterHash     string
	Phase         string
	IdentityJSON  string
}

type CreateFileOperationParams struct {
	Operation       model.FileOperation
	JobID           string
	ItemOrdinal     *int
	AssetID         string
	BeforeAssetJSON string
	AfterAssetJSON  string
	ExpiresAt       *int64
	Files           []OperationFileSpec
}

func (s *Store) CreateFileOperation(ctx context.Context, p CreateFileOperationParams) (model.FileOperation, error) {
	op := p.Operation
	if op.ID == "" {
		op.ID = model.NewUUID()
	}
	if op.State == "" {
		op.State = "planned"
	}
	if p.BeforeAssetJSON == "" {
		p.BeforeAssetJSON = "{}"
	}
	if p.AfterAssetJSON == "" {
		p.AfterAssetJSON = "{}"
	}
	now := time.Now().UTC().UnixMilli()
	op.CreatedAt = now
	op.UpdatedAt = now

	var jobArg, ordArg, assetArg, expArg any
	if p.JobID != "" {
		jobArg = p.JobID
	}
	if p.ItemOrdinal != nil {
		ordArg = *p.ItemOrdinal
	}
	if p.AssetID != "" {
		assetArg = p.AssetID
	}
	if p.ExpiresAt != nil {
		expArg = *p.ExpiresAt
	}

	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO file_operations(
				id, job_id, item_ordinal, asset_id, kind, state,
				before_asset_json, after_asset_json, error_json, created_at, updated_at, expires_at
			) VALUES(?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, ?, ?)
		`, op.ID, jobArg, ordArg, assetArg, op.Kind, op.State, p.BeforeAssetJSON, p.AfterAssetJSON, now, now, expArg)
		if err != nil {
			return err
		}
		for idx, f := range p.Files {
			ord := f.Ordinal
			if ord == 0 && idx > 0 {
				ord = idx
			}
			if err := upsertOperationFileTx(ctx, tx, op.ID, ord, f); err != nil {
				return err
			}
		}
		return nil
	})
	return op, err
}

func upsertOperationFileTx(ctx context.Context, tx *sql.Tx, opID string, ord int, f OperationFileSpec) error {
	if f.SourceRefJSON == "" {
		f.SourceRefJSON = "{}"
	}
	if f.TargetRefJSON == "" {
		f.TargetRefJSON = "{}"
	}
	if f.StageRefJSON == "" {
		f.StageRefJSON = "{}"
	}
	if f.BackupRefJSON == "" {
		f.BackupRefJSON = "{}"
	}
	if f.IdentityJSON == "" {
		f.IdentityJSON = "{}"
	}
	if f.Phase == "" {
		f.Phase = "planned"
	}
	_, err := tx.ExecContext(ctx, `
		INSERT INTO operation_files(
			operation_id, ordinal, role, source_ref_json, target_ref_json, stage_ref_json, backup_ref_json,
			before_hash, after_hash, phase, identity_json
		) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(operation_id, ordinal) DO UPDATE SET
			stage_ref_json = excluded.stage_ref_json,
			backup_ref_json = excluded.backup_ref_json,
			before_hash = excluded.before_hash,
			after_hash = excluded.after_hash,
			phase = excluded.phase,
			identity_json = excluded.identity_json
	`, opID, ord, f.Role, f.SourceRefJSON, f.TargetRefJSON, f.StageRefJSON, f.BackupRefJSON,
		f.BeforeHash, f.AfterHash, f.Phase, f.IdentityJSON)
	return err
}

func (s *Store) RecordOperationFilePhase(ctx context.Context, operationID string, ordinal int, phase, afterHash, identityJSON string) error {
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			UPDATE operation_files
			SET phase = ?,
			    after_hash = CASE WHEN ? != '' THEN ? ELSE after_hash END,
			    identity_json = CASE WHEN ? != '' THEN ? ELSE identity_json END
			WHERE operation_id = ? AND ordinal = ?
		`, phase, afterHash, afterHash, identityJSON, identityJSON, operationID, ordinal)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "文件操作项不存在")
		}
		_, err = tx.ExecContext(ctx, `UPDATE file_operations SET updated_at = ? WHERE id = ?`, now, operationID)
		return err
	})
}

func (s *Store) SetFileOperationState(ctx context.Context, operationID, state, errorJSON string) error {
	now := time.Now().UTC().UnixMilli()
	if errorJSON == "" {
		errorJSON = "{}"
	}
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			UPDATE file_operations SET state = ?, error_json = ?, updated_at = ? WHERE id = ?
		`, state, errorJSON, now, operationID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "文件操作不存在")
		}
		return nil
	})
}

type CommitFileOperationParams struct {
	OperationID      string
	UpdatedAsset     *AssetRecord
	ExpectedRevision int64
	NewAliasToken    string
	JobID            string
	ItemID           string
	ItemStatus       model.LibraryItemStatus
	JobStatus        model.LibraryJobStatus
	Outbox           *OutboxEnqueueParams
}

// CommitFileOperation atomically updates asset mapping/locks/revision, library item/job status,
// file_operation -> 'committed', index_revision, and optional outbox notification.
func (s *Store) CommitFileOperation(ctx context.Context, p CommitFileOperationParams) error {
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		if p.UpdatedAsset != nil {
			a := *p.UpdatedAsset
			metaJSON, _ := json.Marshal(nonNilMap(a.Metadata))
			locksJSON, _ := json.Marshal(nonNilSlice(a.LockedFields))
			updated, err := upsertAssetTx(ctx, tx, a, string(metaJSON), string(locksJSON), p.ExpectedRevision, true, now)
			if err != nil {
				return err
			}
			if p.NewAliasToken != "" {
				if _, err := tx.ExecContext(ctx, `
					INSERT INTO asset_aliases(alias_token, asset_id) VALUES(?, ?)
					ON CONFLICT(alias_token) DO UPDATE SET asset_id = excluded.asset_id
				`, p.NewAliasToken, updated.ID); err != nil {
					return err
				}
			}
		}

		if p.JobID != "" && p.ItemID != "" {
			st := p.ItemStatus
			if st == "" {
				st = model.LibraryItemComplete
			}
			if _, err := tx.ExecContext(ctx, `
				UPDATE library_items SET state = ?, operation_id = ? WHERE job_id = ? AND item_id = ?
			`, string(st), p.OperationID, p.JobID, p.ItemID); err != nil {
				return err
			}
			if p.JobStatus != "" {
				if _, err := tx.ExecContext(ctx, `
					UPDATE library_jobs SET state = ?, processed = processed + 1, updated_at = ? WHERE id = ?
				`, string(p.JobStatus), now, p.JobID); err != nil {
					return err
				}
			}
		}

		res, err := tx.ExecContext(ctx, `
			UPDATE file_operations SET state = 'committed', committed_at = ?, updated_at = ? WHERE id = ?
		`, now, now, p.OperationID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "文件操作不存在")
		}

		if p.Outbox != nil {
			if err := enqueueOutboxTx(ctx, tx, *p.Outbox, now); err != nil {
				return err
			}
		}
		return nil
	})
}

type RecoveryOperation struct {
	Operation       model.FileOperation
	AssetID         string
	JobID           string
	BeforeAssetJSON string
	AfterAssetJSON  string
	Files           []OperationFileSpec
}

type RecoveryDownload struct {
	Download   model.Download
	Checkpoint *CheckpointRecord
}

type RecoveryWork struct {
	FileOperations       []RecoveryOperation
	InterruptedDownloads []RecoveryDownload
}

// LoadRecoveryWork loads all unfinished file operations and interrupted downloads on startup.
func (s *Store) LoadRecoveryWork(ctx context.Context) (RecoveryWork, error) {
	var work RecoveryWork
	opRows, err := s.reader.QueryContext(ctx, `
		SELECT id, COALESCE(job_id,''), COALESCE(asset_id,''), kind, state,
		       before_asset_json, after_asset_json, created_at, updated_at
		FROM file_operations
		WHERE state IN ('planned', 'prepared', 'publishing', 'committed', 'rolling_back', 'needs_attention')
		ORDER BY created_at ASC, id ASC
	`)
	if err != nil {
		return RecoveryWork{}, err
	}
	for opRows.Next() {
		var r RecoveryOperation
		if err := opRows.Scan(
			&r.Operation.ID, &r.JobID, &r.AssetID, &r.Operation.Kind, &r.Operation.State,
			&r.BeforeAssetJSON, &r.AfterAssetJSON, &r.Operation.CreatedAt, &r.Operation.UpdatedAt,
		); err != nil {
			_ = opRows.Close()
			return RecoveryWork{}, err
		}
		work.FileOperations = append(work.FileOperations, r)
	}
	if err := opRows.Err(); err != nil {
		_ = opRows.Close()
		return RecoveryWork{}, err
	}
	_ = opRows.Close()

	for idx := range work.FileOperations {
		opID := work.FileOperations[idx].Operation.ID
		fRows, err := s.reader.QueryContext(ctx, `
			SELECT ordinal, role, source_ref_json, target_ref_json, stage_ref_json, backup_ref_json,
			       before_hash, after_hash, phase, identity_json
			FROM operation_files WHERE operation_id = ? ORDER BY ordinal ASC
		`, opID)
		if err != nil {
			return RecoveryWork{}, err
		}
		for fRows.Next() {
			var f OperationFileSpec
			if err := fRows.Scan(
				&f.Ordinal, &f.Role, &f.SourceRefJSON, &f.TargetRefJSON, &f.StageRefJSON, &f.BackupRefJSON,
				&f.BeforeHash, &f.AfterHash, &f.Phase, &f.IdentityJSON,
			); err != nil {
				_ = fRows.Close()
				return RecoveryWork{}, err
			}
			work.FileOperations[idx].Files = append(work.FileOperations[idx].Files, f)
		}
		_ = fRows.Close()
	}

	dlRows, err := s.reader.QueryContext(ctx, `
		SELECT id, owner_key, public_id, quality_id, quality_label, download_source,
		       state, revision, attempt, bytes_done, bytes_total, detail, file_token,
		       intent_nonce, next_attempt_at, created_at, updated_at
		FROM downloads
		WHERE state IN ('queued','resolving','downloading','verifying','publishing','pause_requested','cancel_requested')
		ORDER BY created_at ASC, id ASC
	`)
	if err != nil {
		return RecoveryWork{}, err
	}
	defer dlRows.Close()
	for dlRows.Next() {
		var d model.Download
		var state string
		var total sql.NullInt64
		var fileTok sql.NullString
		if err := dlRows.Scan(
			&d.ID, &d.ActorKey, &d.PublicID, &d.QualityID, &d.QualityLabel, &d.DownloadSource,
			&state, &d.Revision, &d.Attempt, &d.Bytes, &total, &d.Detail, &fileTok,
			&d.IntentNonce, &d.NextAttemptAt, &d.CreatedAt, &d.UpdatedAt,
		); err != nil {
			return RecoveryWork{}, err
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
		rd := RecoveryDownload{Download: d}
		if cp, ok, err := s.GetTransferCheckpoint(ctx, d.ID); err == nil && ok {
			rd.Checkpoint = &cp
		}
		work.InterruptedDownloads = append(work.InterruptedDownloads, rd)
	}
	return work, dlRows.Err()
}
