package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

type RootRecord struct {
	ID               string
	CanonicalPath    string
	PathCaseMode     string // sensitive | insensitive
	CapabilitiesJSON string
	Enabled          bool
	ScanGeneration   int64
	Healthy          bool
	LastError        string
	UpdatedAt        int64
}

func (s *Store) UpsertRoot(ctx context.Context, r RootRecord) error {
	if r.ID == "" {
		r.ID = model.NewUUID()
	}
	if r.PathCaseMode == "" {
		r.PathCaseMode = "sensitive"
	}
	if r.CapabilitiesJSON == "" {
		r.CapabilitiesJSON = "{}"
	}
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO roots(id, canonical_path, path_case_mode, capabilities_json, enabled, scan_generation, healthy, last_error, updated_at)
			VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(canonical_path) DO UPDATE SET
				path_case_mode = excluded.path_case_mode,
				capabilities_json = excluded.capabilities_json,
				enabled = excluded.enabled,
				healthy = excluded.healthy,
				last_error = excluded.last_error,
				updated_at = excluded.updated_at
		`, r.ID, r.CanonicalPath, r.PathCaseMode, r.CapabilitiesJSON, boolToInt(r.Enabled), r.ScanGeneration, boolToInt(r.Healthy), r.LastError, now)
		return err
	})
}

func (s *Store) GetRoot(ctx context.Context, id string) (RootRecord, bool, error) {
	var r RootRecord
	var enabled, healthy int
	err := s.reader.QueryRowContext(ctx, `
		SELECT id, canonical_path, path_case_mode, capabilities_json, enabled, scan_generation, healthy, last_error, updated_at
		FROM roots WHERE id = ?
	`, id).Scan(&r.ID, &r.CanonicalPath, &r.PathCaseMode, &r.CapabilitiesJSON, &enabled, &r.ScanGeneration, &healthy, &r.LastError, &r.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return RootRecord{}, false, nil
	}
	if err != nil {
		return RootRecord{}, false, err
	}
	r.Enabled = enabled == 1
	r.Healthy = healthy == 1
	return r, true, nil
}

func (s *Store) GetRootByPath(ctx context.Context, canonicalPath string) (RootRecord, bool, error) {
	var r RootRecord
	var enabled, healthy int
	err := s.reader.QueryRowContext(ctx, `
		SELECT id, canonical_path, path_case_mode, capabilities_json, enabled, scan_generation, healthy, last_error, updated_at
		FROM roots WHERE canonical_path = ?
	`, canonicalPath).Scan(&r.ID, &r.CanonicalPath, &r.PathCaseMode, &r.CapabilitiesJSON, &enabled, &r.ScanGeneration, &healthy, &r.LastError, &r.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return RootRecord{}, false, nil
	}
	if err != nil {
		return RootRecord{}, false, err
	}
	r.Enabled = enabled == 1
	r.Healthy = healthy == 1
	return r, true, nil
}

func (s *Store) ListRoots(ctx context.Context) ([]RootRecord, error) {
	rows, err := s.reader.QueryContext(ctx, `
		SELECT id, canonical_path, path_case_mode, capabilities_json, enabled, scan_generation, healthy, last_error, updated_at
		FROM roots ORDER BY canonical_path ASC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []RootRecord
	for rows.Next() {
		var r RootRecord
		var enabled, healthy int
		if err := rows.Scan(&r.ID, &r.CanonicalPath, &r.PathCaseMode, &r.CapabilitiesJSON, &enabled, &r.ScanGeneration, &healthy, &r.LastError, &r.UpdatedAt); err != nil {
			return nil, err
		}
		r.Enabled = enabled == 1
		r.Healthy = healthy == 1
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) SetRootHealth(ctx context.Context, rootID string, healthy bool, lastError string) error {
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			UPDATE roots SET healthy = ?, last_error = ?, updated_at = ? WHERE id = ?
		`, boolToInt(healthy), lastError, now, rootID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "曲库根目录不存在")
		}
		return nil
	})
}

func (s *Store) CountActiveAssetsByRoot(ctx context.Context, rootID string) (int64, error) {
	var count int64
	err := s.reader.QueryRowContext(ctx, `
		SELECT COUNT(1) FROM assets WHERE root_id = ? AND status = 'active'
	`, rootID).Scan(&count)
	return count, err
}

func (s *Store) SetRootEnabled(ctx context.Context, rootID string, enabled bool, lastError string) error {
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			UPDATE roots SET enabled = ?, last_error = ?, updated_at = ? WHERE id = ?
		`, boolToInt(enabled), lastError, now, rootID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "曲库根目录不存在")
		}
		return nil
	})
}

// AdvanceScanGeneration finalizes a root scan. Only when completeWithoutErrors is true
// does it advance scan_generation and mark unseen assets (indexed_generation < generation) as missing.
func (s *Store) AdvanceScanGeneration(ctx context.Context, rootID string, generation int64, completeWithoutErrors bool, scanErr string) (int64, error) {
	now := time.Now().UTC().UnixMilli()
	var markedMissing int64
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		if !completeWithoutErrors {
			_, err := tx.ExecContext(ctx, `
				UPDATE roots SET healthy = 0, last_error = ?, updated_at = ? WHERE id = ?
			`, scanErr, now, rootID)
			return err
		}
		res, err := tx.ExecContext(ctx, `
			UPDATE roots
			SET scan_generation = ?, healthy = 1, last_error = '', updated_at = ?
			WHERE id = ? AND scan_generation <= ?
		`, generation, now, rootID, generation)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrConflict, http.StatusConflict, "扫描代数已过期或根目录不存在")
		}
		mRes, err := tx.ExecContext(ctx, `
			UPDATE assets
			SET status = 'missing', revision = revision + 1, updated_at = ?
			WHERE root_id = ? AND status = 'active' AND indexed_generation < ?
		`, now, rootID, generation)
		if err != nil {
			return err
		}
		markedMissing, _ = mRes.RowsAffected()
		if markedMissing > 0 {
			if _, err := incrementIndexRevisionTx(ctx, tx, nil, now); err != nil {
				return err
			}
		}
		return nil
	})
	return markedMissing, err
}

type ProviderTrackRecord struct {
	ID           string
	Source       string
	Locator      string
	RootSource   string
	Title        string
	Artist       string
	Album        string
	DurationMS   int64
	MetadataJSON string
	ProviderJSON string
	Revision     int64
	DeletedAt    *int64
	UpdatedAt    int64
}

func (s *Store) UpsertProviderTrack(ctx context.Context, tr ProviderTrackRecord, variants []model.MediaVariant) (string, error) {
	if tr.Source == "" || tr.Locator == "" {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "曲目来源和定位符不能为空")
	}
	if tr.ID == "" {
		tr.ID = model.NewUUID()
	}
	if tr.MetadataJSON == "" {
		tr.MetadataJSON = "{}"
	}
	if tr.ProviderJSON == "" {
		tr.ProviderJSON = "{}"
	}
	now := time.Now().UTC().UnixMilli()
	var trackID string
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		trackID, err = upsertProviderTrackTx(ctx, tx, tr, variants, now)
		return err
	})
	return trackID, err
}

func upsertProviderTrackTx(ctx context.Context, tx *sql.Tx, tr ProviderTrackRecord, variants []model.MediaVariant, now int64) (string, error) {
	var trackID string
	err := tx.QueryRowContext(ctx, `
		INSERT INTO provider_tracks(id, source, locator, root_source, title, artist, album, duration_ms, metadata_json, provider_data_json, revision, updated_at)
		VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
		ON CONFLICT(source, locator) DO UPDATE SET
			root_source = excluded.root_source,
			title = excluded.title,
			artist = excluded.artist,
			album = excluded.album,
			duration_ms = excluded.duration_ms,
			metadata_json = excluded.metadata_json,
			provider_data_json = excluded.provider_data_json,
			revision = provider_tracks.revision + 1,
			deleted_at = NULL,
			updated_at = excluded.updated_at
		RETURNING id
	`, tr.ID, tr.Source, tr.Locator, tr.RootSource, tr.Title, tr.Artist, tr.Album, tr.DurationMS, tr.MetadataJSON, tr.ProviderJSON, now).Scan(&trackID)
	if err != nil {
		return "", err
	}
	for _, v := range variants {
		if err := upsertMediaVariantTx(ctx, tx, trackID, v, now); err != nil {
			return "", err
		}
	}
	return trackID, nil
}

func upsertMediaVariantTx(ctx context.Context, tx *sql.Tx, trackID string, v model.MediaVariant, now int64) error {
	varID := v.VariantID
	if varID == "" {
		varID = model.NewUUID()
	}
	varKey := v.VariantID
	if v.UpstreamID != "" || v.Codec != "" || v.Label != "" {
		brVal := 0
		if v.Bitrate != nil {
			brVal = *v.Bitrate
		}
		varKey = fmt.Sprintf("%s|%s|%s|%s|%d|%v", v.UpstreamID, v.Label, v.Codec, v.Protocol, brVal, v.PreviewOnly)
	}
	if varKey == "" {
		varKey = varID
	}
	// Guard against primary key collisions when distinct tracks or distinct variant_keys
	// supply an already-allocated VariantID.
	var conflict int
	err := tx.QueryRowContext(ctx, `
		SELECT 1 FROM media_variants
		WHERE id = ? AND NOT (track_id = ? AND variant_key = ?)
		LIMIT 1
	`, varID, trackID, varKey).Scan(&conflict)
	if err == nil {
		derivedSum := sha256.Sum256([]byte(trackID + "|" + varKey))
		candidateID := "var_" + hex.EncodeToString(derivedSum[:8])
		var stillConflict int
		if err2 := tx.QueryRowContext(ctx, `
			SELECT 1 FROM media_variants
			WHERE id = ? AND NOT (track_id = ? AND variant_key = ?)
			LIMIT 1
		`, candidateID, trackID, varKey).Scan(&stillConflict); err2 == nil {
			varID = model.NewUUID()
		} else {
			varID = candidateID
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	var bitVal any
	if v.Bitrate != nil {
		bitVal = *v.Bitrate
	}
	var sizeVal any
	if v.SizeBytes != nil {
		sizeVal = *v.SizeBytes
	}
	_, err = tx.ExecContext(ctx, `
		INSERT INTO media_variants(
			id, track_id, variant_key, upstream_id, label, codec, container, bitrate,
			lossless, availability, evidence, protocol, size_bytes, duration_ms, preview_only, metadata_json, updated_at
		) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?)
		ON CONFLICT(track_id, variant_key) DO UPDATE SET
			upstream_id = excluded.upstream_id,
			label = excluded.label,
			codec = excluded.codec,
			bitrate = excluded.bitrate,
			lossless = excluded.lossless,
			availability = excluded.availability,
			evidence = excluded.evidence,
			protocol = excluded.protocol,
			size_bytes = excluded.size_bytes,
			duration_ms = excluded.duration_ms,
			preview_only = excluded.preview_only,
			updated_at = excluded.updated_at
	`, varID, trackID, varKey, v.UpstreamID, v.Label, v.Codec, v.Codec, bitVal,
		boolToInt(v.Lossless), v.Availability, v.Evidence, v.Protocol, sizeVal, v.Duration, boolToInt(v.PreviewOnly), now)
	return err
}

func (s *Store) GetProviderTrack(ctx context.Context, id string) (ProviderTrackRecord, []model.MediaVariant, bool, error) {
	var tr ProviderTrackRecord
	var del sql.NullInt64
	err := s.reader.QueryRowContext(ctx, `
		SELECT id, source, locator, root_source, title, artist, album, duration_ms, metadata_json, provider_data_json, revision, deleted_at, updated_at
		FROM provider_tracks WHERE id = ?
	`, id).Scan(&tr.ID, &tr.Source, &tr.Locator, &tr.RootSource, &tr.Title, &tr.Artist, &tr.Album, &tr.DurationMS, &tr.MetadataJSON, &tr.ProviderJSON, &tr.Revision, &del, &tr.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return ProviderTrackRecord{}, nil, false, nil
	}
	if err != nil {
		return ProviderTrackRecord{}, nil, false, err
	}
	if del.Valid {
		tr.DeletedAt = &del.Int64
	}
	variants, err := s.ListMediaVariants(ctx, tr.ID)
	if err != nil {
		return ProviderTrackRecord{}, nil, false, err
	}
	return tr, variants, true, nil
}

func (s *Store) ListMediaVariants(ctx context.Context, trackID string) ([]model.MediaVariant, error) {
	rows, err := s.reader.QueryContext(ctx, `
		SELECT id, upstream_id, label, codec, bitrate, lossless, availability, evidence, preview_only, protocol, size_bytes, duration_ms
		FROM media_variants WHERE track_id = ?
		ORDER BY preview_only ASC, lossless DESC, COALESCE(bitrate, 0) DESC, COALESCE(size_bytes, 0) DESC, id ASC
	`, trackID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.MediaVariant
	for rows.Next() {
		var v model.MediaVariant
		var br, sz sql.NullInt64
		var lossless, prev int
		if err := rows.Scan(&v.VariantID, &v.UpstreamID, &v.Label, &v.Codec, &br, &lossless, &v.Availability, &v.Evidence, &prev, &v.Protocol, &sz, &v.Duration); err != nil {
			return nil, err
		}
		if br.Valid {
			b := int(br.Int64)
			v.Bitrate = &b
		}
		if sz.Valid {
			sval := sz.Int64
			v.SizeBytes = &sval
		}
		v.Lossless = lossless == 1
		v.PreviewOnly = prev == 1
		out = append(out, v)
	}
	return out, rows.Err()
}

func (s *Store) PutPublicRef(ctx context.Context, ref model.PublicRef, qualityHint string) error {
	if ref.PublicID == "" || ref.Kind == "" || ref.TargetID == "" {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "公开引用参数不完整")
	}
	now := ref.CreatedAt
	if now <= 0 {
		now = time.Now().UTC().UnixMilli()
	}
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		return putPublicRefTx(ctx, tx, ref, qualityHint, now)
	})
}

func putPublicRefTx(ctx context.Context, tx *sql.Tx, ref model.PublicRef, qualityHint string, now int64) error {
	switch ref.Kind {
	case "candidate", "track":
		var exists int
		if err := tx.QueryRowContext(ctx, `SELECT 1 FROM provider_tracks WHERE id = ?`, ref.TargetID).Scan(&exists); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return model.NewError(model.ErrNotFound, http.StatusNotFound, "引用的平台曲目不存在")
			}
			return err
		}
	case "asset":
		var exists int
		if err := tx.QueryRowContext(ctx, `SELECT 1 FROM assets WHERE id = ?`, ref.TargetID).Scan(&exists); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return model.NewError(model.ErrNotFound, http.StatusNotFound, "引用的本地资产不存在")
			}
			return err
		}
	case "navidrome":
	default:
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的公开引用类型")
	}
	_, err := tx.ExecContext(ctx, `
		INSERT INTO public_refs(public_id, kind, target_id, source, source_id, quality_hint, revision, created_at)
		VALUES(?, ?, ?, ?, ?, ?, 1, ?)
		ON CONFLICT(public_id) DO UPDATE SET
			kind = excluded.kind,
			target_id = excluded.target_id,
			source = excluded.source,
			source_id = excluded.source_id,
			quality_hint = excluded.quality_hint,
			revision = public_refs.revision + 1
	`, ref.PublicID, ref.Kind, ref.TargetID, ref.Source, ref.SourceID, qualityHint, now)
	return err
}

func (s *Store) GetPublicRef(ctx context.Context, publicID string) (model.PublicRef, string, bool, error) {
	var ref model.PublicRef
	var qualityHint string
	err := s.reader.QueryRowContext(ctx, `
		SELECT public_id, kind, target_id, source, source_id, quality_hint, revision, created_at
		FROM public_refs WHERE public_id = ?
	`, publicID).Scan(&ref.PublicID, &ref.Kind, &ref.TargetID, &ref.Source, &ref.SourceID, &qualityHint, &ref.Revision, &ref.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return model.PublicRef{}, "", false, nil
	}
	if err != nil {
		return model.PublicRef{}, "", false, err
	}
	return ref, qualityHint, true, nil
}

type AssetRecord struct {
	ID                string
	RootID            string
	RelPath           string
	PathKey           string
	Folder            string
	Name              string
	Status            string // active | missing | deleted
	Editable          bool
	Size              int64
	MTimeNS           int64
	Revision          int64
	Title             string
	Artist            string
	Album             string
	DurationMS        int64
	Metadata          map[string]string
	LockedFields      []string
	OriginJSON        string
	IndexedGeneration int64
	UpdatedAt         int64
}

// UpsertAsset inserts or updates an asset. When expectedRevision > 0, it enforces CAS
// so stale background scans cannot overwrite newer tag/file commits.
func (s *Store) UpsertAsset(ctx context.Context, a AssetRecord, expectedRevision int64, bumpIndexRev bool) (AssetRecord, error) {
	if a.ID == "" {
		a.ID = model.NewUUID()
	}
	if a.Status == "" {
		a.Status = "active"
	}
	if a.PathKey == "" {
		a.PathKey = filepath.ToSlash(a.RelPath)
	}
	if a.Name == "" {
		a.Name = filepath.Base(a.RelPath)
	}
	if a.Folder == "" {
		dir := filepath.ToSlash(filepath.Dir(a.RelPath))
		a.Folder = dir
	}
	if a.OriginJSON == "" {
		a.OriginJSON = "{}"
	}
	metaJSON, _ := json.Marshal(nonNilMap(a.Metadata))
	locksJSON, _ := json.Marshal(nonNilSlice(a.LockedFields))
	now := time.Now().UTC().UnixMilli()

	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		var err error
		a, err = upsertAssetTx(ctx, tx, a, string(metaJSON), string(locksJSON), expectedRevision, bumpIndexRev, now)
		return err
	})
	return a, err
}

func upsertAssetTx(ctx context.Context, tx *sql.Tx, a AssetRecord, metaJSON, locksJSON string, expectedRevision int64, bumpIndexRev bool, now int64) (AssetRecord, error) {
	// Release any deleted tombstone holding the destination (root_id, path_key) without deleting
	// the historical row (which may still be referenced by file_backups).
	if _, err := tx.ExecContext(ctx, `
		UPDATE assets
		SET path_key = CASE WHEN instr(path_key, '#deleted:') = 0 THEN path_key || '#deleted:' || id ELSE path_key END,
		    updated_at = ?
		WHERE root_id = ? AND path_key = ? AND status = 'deleted'
	`, now, a.RootID, a.PathKey); err != nil {
		return a, err
	}
	if expectedRevision > 0 {
		res, err := tx.ExecContext(ctx, `
			UPDATE assets SET
				root_id = ?, rel_path = ?, path_key = ?, folder = ?, name = ?, status = ?,
				editable = ?, size = ?, mtime_ns = ?, revision = revision + 1,
				title = ?, artist = ?, album = ?, duration_ms = ?,
				metadata_json = ?, locks_json = ?, origin_json = ?, indexed_generation = ?, updated_at = ?
			WHERE id = ? AND revision = ?
		`, a.RootID, a.RelPath, a.PathKey, a.Folder, a.Name, a.Status,
			boolToInt(a.Editable), a.Size, a.MTimeNS, a.Title, a.Artist, a.Album, a.DurationMS,
			metaJSON, locksJSON, a.OriginJSON, a.IndexedGeneration, now, a.ID, expectedRevision)
		if err != nil {
			return a, err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return a, model.NewError(model.ErrConflict, http.StatusConflict, "资产版本已变化")
		}
		a.Revision = expectedRevision + 1
		a.UpdatedAt = now
	} else {
		err := tx.QueryRowContext(ctx, `
			INSERT INTO assets(
				id, root_id, rel_path, path_key, folder, name, status, editable,
				size, mtime_ns, revision, title, artist, album, duration_ms,
				metadata_json, locks_json, origin_json, indexed_generation, updated_at
			) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(root_id, path_key) DO UPDATE SET
				rel_path = excluded.rel_path,
				folder = excluded.folder,
				name = excluded.name,
				status = excluded.status,
				editable = excluded.editable,
				size = excluded.size,
				mtime_ns = excluded.mtime_ns,
				revision = assets.revision + 1,
				title = excluded.title,
				artist = excluded.artist,
				album = excluded.album,
				duration_ms = excluded.duration_ms,
				metadata_json = excluded.metadata_json,
				locks_json = excluded.locks_json,
				origin_json = excluded.origin_json,
				indexed_generation = MAX(assets.indexed_generation, excluded.indexed_generation),
				updated_at = excluded.updated_at
			RETURNING id, revision
		`, a.ID, a.RootID, a.RelPath, a.PathKey, a.Folder, a.Name, a.Status, boolToInt(a.Editable),
			a.Size, a.MTimeNS, a.Title, a.Artist, a.Album, a.DurationMS,
			metaJSON, locksJSON, a.OriginJSON, a.IndexedGeneration, now).Scan(&a.ID, &a.Revision)
		if err != nil {
			return a, err
		}
		a.UpdatedAt = now
	}
	if bumpIndexRev {
		if _, err := incrementIndexRevisionTx(ctx, tx, nil, now); err != nil {
			return a, err
		}
	}
	return a, nil
}

func (s *Store) GetAsset(ctx context.Context, id string) (AssetRecord, bool, error) {
	var a AssetRecord
	var editable int
	var metaJSON, locksJSON string
	err := s.reader.QueryRowContext(ctx, `
		SELECT id, root_id, rel_path, path_key, folder, name, status, editable,
		       size, mtime_ns, revision, title, artist, album, duration_ms,
		       metadata_json, locks_json, origin_json, indexed_generation, updated_at
		FROM assets WHERE id = ?
	`, id).Scan(
		&a.ID, &a.RootID, &a.RelPath, &a.PathKey, &a.Folder, &a.Name, &a.Status, &editable,
		&a.Size, &a.MTimeNS, &a.Revision, &a.Title, &a.Artist, &a.Album, &a.DurationMS,
		&metaJSON, &locksJSON, &a.OriginJSON, &a.IndexedGeneration, &a.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return AssetRecord{}, false, nil
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	a.Editable = editable == 1
	_ = json.Unmarshal([]byte(metaJSON), &a.Metadata)
	_ = json.Unmarshal([]byte(locksJSON), &a.LockedFields)
	return a, true, nil
}

// GetAssetByRootAndPathKey looks up an active (non-deleted) asset by its root ID and normalized path_key.
func (s *Store) GetAssetByRootAndPathKey(ctx context.Context, rootID, pathKey string) (AssetRecord, bool, error) {
	var id string
	err := s.reader.QueryRowContext(ctx, `
		SELECT id FROM assets WHERE root_id = ? AND path_key = ? AND status != 'deleted'
	`, rootID, pathKey).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return AssetRecord{}, false, nil
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	return s.GetAsset(ctx, id)
}

func (s *Store) AddAssetAlias(ctx context.Context, aliasToken, assetID string) error {
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO asset_aliases(alias_token, asset_id) VALUES(?, ?)
			ON CONFLICT(alias_token) DO UPDATE SET asset_id = excluded.asset_id
		`, aliasToken, assetID)
		return err
	})
}

func (s *Store) ResolveAssetAlias(ctx context.Context, aliasToken string) (AssetRecord, bool, error) {
	var assetID string
	err := s.reader.QueryRowContext(ctx, `SELECT asset_id FROM asset_aliases WHERE alias_token = ?`, aliasToken).Scan(&assetID)
	if errors.Is(err, sql.ErrNoRows) {
		return AssetRecord{}, false, nil
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	return s.GetAsset(ctx, assetID)
}

func (s *Store) UpdateAssetLocks(ctx context.Context, assetID string, locks []string, expectedRevision int64) error {
	locksJSON, _ := json.Marshal(nonNilSlice(locks))
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		var res sql.Result
		var err error
		if expectedRevision > 0 {
			res, err = tx.ExecContext(ctx, `
				UPDATE assets SET locks_json = ?, revision = revision + 1, updated_at = ?
				WHERE id = ? AND revision = ?
			`, string(locksJSON), now, assetID, expectedRevision)
		} else {
			res, err = tx.ExecContext(ctx, `
				UPDATE assets SET locks_json = ?, revision = revision + 1, updated_at = ?
				WHERE id = ?
			`, string(locksJSON), now, assetID)
		}
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrConflict, http.StatusConflict, "资产锁更新冲突或资产不存在")
		}
		return nil
	})
}

func (s *Store) SoftDeleteAsset(ctx context.Context, assetID string) error {
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			UPDATE assets
			SET status = 'deleted',
			    path_key = CASE WHEN instr(path_key, '#deleted:') = 0 THEN path_key || '#deleted:' || id ELSE path_key END,
			    revision = revision + 1,
			    updated_at = ?
			WHERE id = ?
		`, now, assetID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "资产不存在")
		}
		_, err = incrementIndexRevisionTx(ctx, tx, nil, now)
		return err
	})
}

func escapeSQLiteLike(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return r.Replace(s)
}

func folderPathKeyPrefix(caseMode, folder string) string {
	clean := strings.Trim(strings.TrimSpace(filepath.ToSlash(folder)), "/")
	if caseMode == "insensitive" {
		return strings.ToLower(clean)
	}
	return clean
}

// ListAssetsByFolderPrefix returns all non-deleted assets located inside folder or its subdirectories.
func (s *Store) ListAssetsByFolderPrefix(ctx context.Context, rootID, caseMode, folder string) ([]AssetRecord, error) {
	prefixKey := folderPathKeyPrefix(caseMode, folder)
	if prefixKey == "" || prefixKey == "." {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持按根目录前缀查询")
	}
	pattern := escapeSQLiteLike(prefixKey) + "/%"
	rows, err := s.reader.QueryContext(ctx, `
		SELECT id, root_id, rel_path, path_key, folder, name, status, editable,
		       size, mtime_ns, revision, title, artist, album, duration_ms,
		       metadata_json, locks_json, origin_json, indexed_generation, updated_at
		FROM assets
		WHERE root_id = ? AND status != 'deleted' AND path_key LIKE ? ESCAPE '\'
		ORDER BY path_key ASC
	`, rootID, pattern)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AssetRecord
	for rows.Next() {
		var a AssetRecord
		var editable int
		var metaJSON, locksJSON string
		if err := rows.Scan(
			&a.ID, &a.RootID, &a.RelPath, &a.PathKey, &a.Folder, &a.Name, &a.Status, &editable,
			&a.Size, &a.MTimeNS, &a.Revision, &a.Title, &a.Artist, &a.Album, &a.DurationMS,
			&metaJSON, &locksJSON, &a.OriginJSON, &a.IndexedGeneration, &a.UpdatedAt,
		); err != nil {
			return nil, err
		}
		a.Editable = editable == 1
		_ = json.Unmarshal([]byte(metaJSON), &a.Metadata)
		_ = json.Unmarshal([]byte(locksJSON), &a.LockedFields)
		out = append(out, a)
	}
	return out, rows.Err()
}

// SoftDeleteAssetsByFolder marks all non-deleted assets under folder as deleted and returns their IDs.
func (s *Store) SoftDeleteAssetsByFolder(ctx context.Context, rootID, caseMode, folder string) ([]string, error) {
	assets, err := s.ListAssetsByFolderPrefix(ctx, rootID, caseMode, folder)
	if err != nil {
		return nil, err
	}
	if len(assets) == 0 {
		return nil, nil
	}
	now := time.Now().UTC().UnixMilli()
	prefixKey := folderPathKeyPrefix(caseMode, folder)
	pattern := escapeSQLiteLike(prefixKey) + "/%"
	ids := make([]string, 0, len(assets))
	for _, a := range assets {
		ids = append(ids, a.ID)
	}
	err = s.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `
			UPDATE assets
			SET status = 'deleted',
			    path_key = CASE WHEN instr(path_key, '#deleted:') = 0 THEN path_key || '#deleted:' || id ELSE path_key END,
			    revision = revision + 1,
			    updated_at = ?
			WHERE root_id = ? AND status != 'deleted' AND path_key LIKE ? ESCAPE '\'
		`, now, rootID, pattern); err != nil {
			return err
		}
		_, err := incrementIndexRevisionTx(ctx, tx, nil, now)
		return err
	})
	if err != nil {
		return nil, err
	}
	return ids, nil
}

func suffixAfterFolderSegs(relSlashed, cleanOld string, caseInsensitive bool) (string, bool) {
	cleanOld = strings.Trim(cleanOld, "/")
	relClean := strings.Trim(strings.TrimSpace(relSlashed), "/")
	if cleanOld == "" {
		return "", false
	}
	oldSegs := strings.Split(cleanOld, "/")
	relSegs := strings.Split(relClean, "/")
	if len(relSegs) <= len(oldSegs) {
		return "", false
	}
	for i, seg := range oldSegs {
		if caseInsensitive {
			if !strings.EqualFold(relSegs[i], seg) {
				return "", false
			}
		} else if relSegs[i] != seg {
			return "", false
		}
	}
	return strings.Join(relSegs[len(oldSegs):], "/"), true
}

// RelocateFolderAssets updates rel_path, path_key, and folder for all active assets moved from oldFolder to newFolder.
func (s *Store) RelocateFolderAssets(ctx context.Context, oldRootID, oldCaseMode, oldFolder, newRootID, newCaseMode, newFolder string) error {
	assets, err := s.ListAssetsByFolderPrefix(ctx, oldRootID, oldCaseMode, oldFolder)
	if err != nil {
		return err
	}
	cleanOld := strings.Trim(strings.TrimSpace(filepath.ToSlash(oldFolder)), "/")
	cleanNew := strings.Trim(strings.TrimSpace(filepath.ToSlash(newFolder)), "/")
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		if len(assets) == 0 {
			return nil
		}
		for _, a := range assets {
			relSlashed := filepath.ToSlash(a.RelPath)
			suffix, ok := suffixAfterFolderSegs(relSlashed, cleanOld, oldCaseMode == "insensitive")
			if !ok {
				continue
			}
			nextRel := suffix
			if cleanNew != "" {
				nextRel = cleanNew + "/" + suffix
			}
			nextFolder := filepath.ToSlash(filepath.Dir(nextRel))
			if nextFolder == "." {
				nextFolder = ""
			}
			nextKey := filepath.ToSlash(filepath.Clean(nextRel))
			if newCaseMode == "insensitive" {
				nextKey = strings.ToLower(nextKey)
			}
			// Rename any stale deleted tombstone at target key instead of deleting it.
			if _, err := tx.ExecContext(ctx, `
				UPDATE assets
				SET path_key = CASE WHEN instr(path_key, '#deleted:') = 0 THEN path_key || '#deleted:' || id ELSE path_key END,
				    updated_at = ?
				WHERE root_id = ? AND path_key = ? AND status = 'deleted'
			`, now, newRootID, nextKey); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `
				UPDATE assets
				SET root_id = ?, rel_path = ?, path_key = ?, folder = ?, revision = revision + 1, updated_at = ?
				WHERE id = ?
			`, newRootID, nextRel, nextKey, nextFolder, now, a.ID); err != nil {
				return err
			}
		}
		_, err := incrementIndexRevisionTx(ctx, tx, nil, now)
		return err
	})
}

type LibraryFilesQuery struct {
	Limit  int
	Cursor string
	Query  string
	Root   string
	Folder string
	Sort   string // path_key (default) | title | artist | album | size | duration | updated_at
}

func normalizeSortColumn(sortField string) (string, string, error) {
	switch strings.TrimSpace(sortField) {
	case "", "path_key", "path":
		return "path_key", "a.path_key", nil
	case "title":
		return "title", "a.title", nil
	case "artist":
		return "artist", "a.artist", nil
	case "album":
		return "album", "a.album", nil
	case "size":
		return "size", "a.size", nil
	case "duration":
		return "duration", "a.duration_ms", nil
	case "updated_at":
		return "updated_at", "a.updated_at", nil
	default:
		return "", "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的排序字段")
	}
}

// QueryLibraryFiles serves GET /api/library/files keyset pagination.
// Invalid cursor or filter/sort mismatch returns 400; stale index_revision returns 409.
func (s *Store) QueryLibraryFiles(ctx context.Context, q LibraryFilesQuery) (model.LibraryFilesPageDTO, error) {
	limit := q.Limit
	if limit <= 0 {
		limit = 100
	}
	if limit > 500 {
		limit = 500
	}
	sortName, sortCol, err := normalizeSortColumn(q.Sort)
	if err != nil {
		return model.LibraryFilesPageDTO{}, err
	}
	cleanQuery := strings.TrimSpace(q.Query)
	cleanRoot := strings.TrimSpace(q.Root)
	cleanFolder := strings.Trim(strings.TrimSpace(filepath.ToSlash(q.Folder)), "/")
	filterFolderKey := cleanFolder
	if cleanRoot != "" {
		filterFolderKey = cleanRoot + "\x00" + cleanFolder
	}
	filtersHash := model.FiltersHash(cleanQuery, filterFolderKey)

	var out model.LibraryFilesPageDTO
	err = s.WithReadTx(ctx, func(tx *sql.Tx) error {
		idxState := LibraryIndexState{Revision: 1, Indexing: false}
		var rawIdx string
		if err := tx.QueryRowContext(ctx, `SELECT body_json FROM settings WHERE namespace = ?`, NamespaceLibraryIndex).Scan(&rawIdx); err == nil {
			_ = json.Unmarshal([]byte(rawIdx), &idxState)
			if idxState.Revision <= 0 {
				idxState.Revision = 1
			}
		} else if !errors.Is(err, sql.ErrNoRows) {
			return err
		}

		var hasCursor bool
		var cursorSortVal any
		var cursorID string
		if q.Cursor != "" {
			cur, err := model.DecodePaginationCursor(q.Cursor, sortName, filtersHash, idxState.Revision)
			if err != nil {
				return err
			}
			hasCursor = true
			cursorID = cur.LastID
			var anchorStr string
			var anchorInt int64
			if sortName == "size" || sortName == "updated_at" || sortName == "duration" {
				err = tx.QueryRowContext(ctx, fmt.Sprintf(`SELECT %s FROM assets a WHERE a.id = ?`, sortCol), cursorID).Scan(&anchorInt)
				cursorSortVal = anchorInt
			} else {
				err = tx.QueryRowContext(ctx, fmt.Sprintf(`SELECT %s FROM assets a WHERE a.id = ?`, sortCol), cursorID).Scan(&anchorStr)
				cursorSortVal = anchorStr
			}
			if errors.Is(err, sql.ErrNoRows) {
				return model.NewError(model.ErrConflict, http.StatusConflict, "分页游标已失效，请重新加载列表")
			}
			if err != nil {
				return err
			}
		}

		type rootSnap struct {
			id            string
			canonicalPath string
			caseMode      string
		}
		var allRoots []rootSnap
		rootsByID := make(map[string]rootSnap)
		rRows, rErr := tx.QueryContext(ctx, `
			SELECT id, canonical_path, path_case_mode
			FROM roots
			ORDER BY canonical_path ASC, id ASC
		`)
		if rErr != nil {
			return rErr
		}
		for rRows.Next() {
			var rs rootSnap
			if sErr := rRows.Scan(&rs.id, &rs.canonicalPath, &rs.caseMode); sErr != nil {
				_ = rRows.Close()
				return sErr
			}
			allRoots = append(allRoots, rs)
			rootsByID[rs.id] = rs
		}
		if err := rRows.Close(); err != nil {
			return err
		}
		if err := rRows.Err(); err != nil {
			return err
		}

		// Use covering index assets_root_status_folder_id via recursive CTE (loose index scan)
		// to list distinct active folders per root without scanning 100k table rows.
		var folders []model.LibraryFolderDTO
		for _, rs := range allRoots {
			if err := collectRootFoldersTx(ctx, tx, rs.id, rs.canonicalPath, &folders); err != nil {
				return err
			}
		}

		targetRoots := allRoots
		if cleanRoot != "" {
			cleanRootNative := filepath.Clean(cleanRoot)
			targetRoots = make([]rootSnap, 0, len(allRoots))
			for _, rs := range allRoots {
				if rs.id == cleanRoot || rs.canonicalPath == cleanRootNative ||
					(rs.caseMode == "insensitive" && strings.EqualFold(rs.canonicalPath, cleanRootNative)) {
					targetRoots = append(targetRoots, rs)
				}
			}
			if len(targetRoots) == 0 {
				out = model.LibraryFilesPageDTO{
					Items:         []model.LibraryFileDTO{},
					IndexRevision: idxState.Revision,
					Indexing:      idxState.Indexing,
					Folders:       folders,
				}
				return nil
			}
		}

		var clauses []string
		var args []any
		clauses = append(clauses, `a.status = 'active'`)
		if cleanRoot != "" {
			if len(targetRoots) == 1 {
				clauses = append(clauses, `a.root_id = ?`)
				args = append(args, targetRoots[0].id)
			} else {
				placeholders := make([]string, len(targetRoots))
				for i, rs := range targetRoots {
					placeholders[i] = "?"
					args = append(args, rs.id)
				}
				clauses = append(clauses, fmt.Sprintf(`a.root_id IN (%s)`, strings.Join(placeholders, ", ")))
			}
		}
		if cleanFolder == "." {
			clauses = append(clauses, `(a.folder = '' OR a.folder = '.')`)
		} else if cleanFolder != "" && len(targetRoots) > 0 {
			hasInsensitive, hasSensitive := false, false
			for _, rs := range targetRoots {
				if rs.caseMode == "insensitive" {
					hasInsensitive = true
				} else {
					hasSensitive = true
				}
			}
			if !hasSensitive {
				lowerPrefix := strings.ToLower(cleanFolder)
				clauses = append(clauses, `(a.path_key >= ? AND a.path_key < ?)`)
				args = append(args, lowerPrefix+"/", lowerPrefix+"0")
			} else if !hasInsensitive {
				clauses = append(clauses, `(a.path_key >= ? AND a.path_key < ?)`)
				args = append(args, cleanFolder+"/", cleanFolder+"0")
			} else {
				var branchClauses []string
				for _, rs := range targetRoots {
					prefix := cleanFolder
					if rs.caseMode == "insensitive" {
						prefix = strings.ToLower(cleanFolder)
					}
					branchClauses = append(branchClauses, `(a.root_id = ? AND a.path_key >= ? AND a.path_key < ?)`)
					args = append(args, rs.id, prefix+"/", prefix+"0")
				}
				clauses = append(clauses, "("+strings.Join(branchClauses, " OR ")+")")
			}
		}
		if cleanQuery != "" {
			pattern := "%" + escapeSQLiteLike(cleanQuery) + "%"
			clauses = append(clauses, `(a.title LIKE ? ESCAPE '\' OR a.artist LIKE ? ESCAPE '\' OR a.album LIKE ? ESCAPE '\' OR a.name LIKE ? ESCAPE '\' OR a.rel_path LIKE ? ESCAPE '\')`)
			args = append(args, pattern, pattern, pattern, pattern, pattern)
		}
		orderDir := "ASC"
		cmpOp := ">"
		if sortName == "duration" {
			orderDir = "DESC"
			cmpOp = "<"
		}
		if hasCursor {
			clauses = append(clauses, fmt.Sprintf(`((%s %s ?) OR (%s = ? AND a.id > ?))`, sortCol, cmpOp, sortCol))
			args = append(args, cursorSortVal, cursorSortVal, cursorID)
		}

		querySQL := fmt.Sprintf(`
			SELECT a.id, a.root_id, a.rel_path, a.path_key, a.folder, a.name, a.size, a.mtime_ns, a.editable,
			       a.title, a.artist, a.album, a.duration_ms, a.metadata_json, a.locks_json
			FROM assets a
			WHERE %s
			ORDER BY %s %s, a.id ASC
			LIMIT ?
		`, strings.Join(clauses, " AND "), sortCol, orderDir)
		args = append(args, limit+1)

		rows, err := tx.QueryContext(ctx, querySQL, args...)
		if err != nil {
			return err
		}
		defer rows.Close()

		type pageRow struct {
			id  string
			dto model.LibraryFileDTO
		}
		var collected []pageRow
		for rows.Next() {
			var id, rootID, relPath, pathKey, folder, name, title, artist, album, metaJSON, locksJSON string
			var size, mtimeNS, durMS int64
			var editableInt int
			if err := rows.Scan(&id, &rootID, &relPath, &pathKey, &folder, &name, &size, &mtimeNS, &editableInt, &title, &artist, &album, &durMS, &metaJSON, &locksJSON); err != nil {
				return err
			}
			rs, ok := rootsByID[rootID]
			if !ok {
				continue
			}
			rootPath := rs.canonicalPath
			var meta map[string]string
			var locks []string
			_ = json.Unmarshal([]byte(metaJSON), &meta)
			_ = json.Unmarshal([]byte(locksJSON), &locks)
			hasCover := false
			var brPtr *int
			if meta != nil {
				hasCover = meta["_has_cover"] == "1"
				if rawBR := strings.TrimSpace(meta["_bitrate"]); rawBR != "" {
					if br, perr := strconv.Atoi(rawBR); perr == nil && br > 0 {
						if br > 10000 {
							br = (br + 500) / 1000
						}
						brPtr = &br
					}
				}
				delete(meta, "_has_cover")
				delete(meta, "_bitrate")
			}
			editable := editableInt == 1
			token := "asset:" + id
			szCopy := size
			ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(name)), ".")
			audioURL := "/api/files/audio?path=" + url.QueryEscape(token)
			detail := "音乐库文件"
			var coverURL *string
			if hasCover {
				cu := fmt.Sprintf("/api/files/cover?path=%s&v=%d", url.QueryEscape(token), mtimeNS)
				coverURL = &cu
			}
			collected = append(collected, pageRow{
				id: id,
				dto: model.LibraryFileDTO{
					Path:         token,
					Name:         name,
					Size:         size,
					Root:         rootPath,
					Folder:       folder,
					Editable:     &editable,
					Metadata:     meta,
					LockedFields: locks,
					Track: &model.TrackDTO{
						ID:        token,
						Source:    "local",
						SourceID:  id,
						Title:     title,
						Artist:    artist,
						Album:     album,
						Duration:  float64(durMS) / 1000.0,
						Cover:     coverURL,
						SizeBytes: &szCopy,
						Qualities: []model.QualityDTO{},
						Playback: model.PlaybackDTO{
							State:   "ready",
							URL:     &audioURL,
							Codec:   &ext,
							Bitrate: brPtr,
							Detail:  &detail,
						},
					},
				},
			})
		}
		if err := rows.Err(); err != nil {
			return err
		}

		var nextCursor *string
		if len(collected) > limit {
			collected = collected[:limit]
			last := collected[len(collected)-1]
			tok, err := (model.PaginationCursor{
				SortField:     sortName,
				LastID:        last.id,
				FiltersHash:   filtersHash,
				IndexRevision: idxState.Revision,
			}).Encode()
			if err != nil {
				return err
			}
			nextCursor = &tok
		}

		items := make([]model.LibraryFileDTO, len(collected))
		for i, r := range collected {
			items[i] = r.dto
		}
		out = model.LibraryFilesPageDTO{
			Items:         items,
			NextCursor:    nextCursor,
			IndexRevision: idxState.Revision,
			Indexing:      idxState.Indexing,
			Folders:       folders,
		}
		return nil
	})
	return out, err
}

func collectRootFoldersTx(ctx context.Context, tx *sql.Tx, rootID, rootPath string, out *[]model.LibraryFolderDTO) error {
	rows, err := tx.QueryContext(ctx, `
		WITH RECURSIVE distinct_folders(folder) AS (
			SELECT MIN(folder) FROM assets WHERE root_id = ? AND status = 'active'
			UNION ALL
			SELECT (
				SELECT MIN(a.folder)
				FROM assets a
				WHERE a.root_id = ? AND a.status = 'active' AND a.folder > df.folder
			)
			FROM distinct_folders df
			WHERE df.folder IS NOT NULL
		)
		SELECT folder FROM distinct_folders WHERE folder IS NOT NULL
	`, rootID, rootID)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var fDir string
		if err := rows.Scan(&fDir); err != nil {
			return err
		}
		*out = append(*out, model.LibraryFolderDTO{Root: rootPath, Folder: fDir})
	}
	return rows.Err()
}

func nonNilMap(m map[string]string) map[string]string {
	if m == nil {
		return map[string]string{}
	}
	return m
}

func nonNilSlice(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}
