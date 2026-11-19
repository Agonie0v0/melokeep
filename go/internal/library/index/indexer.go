package index

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

type ScanStats struct {
	RootID          string
	Generation      int64
	ScannedFiles    int
	UnchangedFiles  int
	UpdatedOrNew    int
	SkippedLocked   int
	MarkedMissing   int64
	Healthy         bool
	StructuralError string
}

type ScanOptions struct {
	RepairMissingCovers bool
}

type existingAssetSnap struct {
	id           string
	size         int64
	mtimeNS      int64
	revision     int64
	status       string
	hasCoverMeta bool
}

type Indexer struct {
	store *store.Store
	locks *files.AssetLockManager
	tags  *media.TagService
}

func NewIndexer(st *store.Store, locks *files.AssetLockManager, tags *media.TagService) *Indexer {
	if locks == nil {
		locks = files.NewAssetLockManager(5 * time.Second)
	}
	if tags == nil {
		tags = media.NewTagService(nil, nil)
	}
	return &Indexer{
		store: st,
		locks: locks,
		tags:  tags,
	}
}

// QueryFiles serves paginated /api/library/files reads without touching audio bytes on disk.
func (idx *Indexer) QueryFiles(ctx context.Context, q store.LibraryFilesQuery) (model.LibraryFilesPageDTO, error) {
	return idx.store.QueryLibraryFiles(ctx, q)
}

// ScanRoot incrementally indexes a single music root.
// If the root is offline/unreadable or any structural traversal error occurs, it marks the root unhealthy
// and preserves all existing assets without marking them missing.
func (idx *Indexer) ScanRoot(ctx context.Context, rootRec store.RootRecord) (ScanStats, error) {
	return idx.ScanRootWithOptions(ctx, rootRec, ScanOptions{})
}

// ScanRootWithOptions incrementally indexes a single music root with optional metadata backfill.
func (idx *Indexer) ScanRootWithOptions(ctx context.Context, rootRec store.RootRecord, opts ScanOptions) (ScanStats, error) {
	stats := ScanStats{
		RootID:     rootRec.ID,
		Generation: rootRec.ScanGeneration + 1,
		Healthy:    true,
	}

	_, _ = idx.store.SetLibraryIndexing(ctx, true, false)
	defer func() {
		_, _ = idx.store.SetLibraryIndexing(ctx, false, stats.UpdatedOrNew > 0)
	}()

	idxState, _ := idx.store.GetLibraryIndexState(ctx)
	rootBackfilled := idxState.CoverBackfillRoots != nil && idxState.CoverBackfillRoots[rootRec.ID]
	needCoverBackfill := opts.RepairMissingCovers || !rootBackfilled

	canon, err := files.CanonicalizeRoot(rootRec.CanonicalPath)
	if err != nil {
		stats.Healthy = false
		stats.StructuralError = err.Error()
		_ = idx.store.SetRootHealth(ctx, rootRec.ID, false, stats.StructuralError)
		return stats, nil
	}
	if _, err := os.ReadDir(canon); err != nil {
		stats.Healthy = false
		stats.StructuralError = err.Error()
		_ = idx.store.SetRootHealth(ctx, rootRec.ID, false, stats.StructuralError)
		return stats, nil
	}

	existingMap, err := idx.loadExistingByPathKey(ctx, rootRec.ID)
	if err != nil {
		return stats, err
	}

	type pendingRead struct {
		absPath  string
		relPath  string
		pathKey  string
		folder   string
		name     string
		size     int64
		mtimeNS  int64
		existing *existingAssetSnap
	}
	var unchangedIDs []string
	var coverRepairIDs []string
	var toRead []pendingRead
	var walkErrMsg string

	walkErr := filepath.WalkDir(canon, func(p string, d fs.DirEntry, wErr error) error {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if wErr != nil {
			walkErrMsg = fmt.Sprintf("目录遍历异常(%s): %v", p, wErr)
			return fs.SkipAll
		}
		name := d.Name()
		if p != canon && (files.InternalDirs[name] || strings.HasPrefix(name, ".melokeep-")) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		info, iErr := os.Lstat(p)
		if iErr != nil {
			walkErrMsg = fmt.Sprintf("读取文件属性失败(%s): %v", p, iErr)
			return fs.SkipAll
		}
		isLink, lErr := files.IsReparseOrSymlink(p, info)
		if lErr != nil || isLink {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() {
			return nil
		}
		ext := strings.ToLower(filepath.Ext(name))
		if !files.AudioExtensions[ext] {
			return nil
		}

		rel, rErr := filepath.Rel(canon, p)
		if rErr != nil {
			return nil
		}
		slashedRel := filepath.ToSlash(rel)
		pathKey := files.ComputePathKey(rootRec.PathCaseMode, slashedRel)
		folder := filepath.ToSlash(filepath.Dir(slashedRel))
		if folder == "." {
			folder = ""
		}

		stats.ScannedFiles++
		mtimeNS := info.ModTime().UnixNano()
		size := info.Size()

		if prev, ok := existingMap[pathKey]; ok {
			if idx.locks.IsWriteActive(prev.id) {
				stats.SkippedLocked++
				unchangedIDs = append(unchangedIDs, prev.id)
				return nil
			}
			if prev.status == "active" && prev.size == size && prev.mtimeNS == mtimeNS {
				if needCoverBackfill && !prev.hasCoverMeta && media.HasLocalCover(p) {
					coverRepairIDs = append(coverRepairIDs, prev.id)
				} else {
					stats.UnchangedFiles++
				}
				unchangedIDs = append(unchangedIDs, prev.id)
				return nil
			}
			prevCopy := prev
			toRead = append(toRead, pendingRead{
				absPath: p, relPath: slashedRel, pathKey: pathKey, folder: folder, name: name,
				size: size, mtimeNS: mtimeNS, existing: &prevCopy,
			})
			return nil
		}

		toRead = append(toRead, pendingRead{
			absPath: p, relPath: slashedRel, pathKey: pathKey, folder: folder, name: name,
			size: size, mtimeNS: mtimeNS,
		})
		return nil
	})

	if walkErr != nil && walkErrMsg == "" {
		walkErrMsg = walkErr.Error()
	}
	if walkErrMsg != "" {
		stats.Healthy = false
		stats.StructuralError = walkErrMsg
		_, _ = idx.store.AdvanceScanGeneration(ctx, rootRec.ID, stats.Generation, false, walkErrMsg)
		return stats, nil
	}

	// Bump indexed_generation in short batches for unchanged files without rewriting tags.
	if len(unchangedIDs) > 0 {
		if err := idx.markGenerationBatch(ctx, unchangedIDs, stats.Generation); err != nil {
			return stats, err
		}
	}
	if len(coverRepairIDs) > 0 {
		if repaired, err := idx.repairAssetCoverBatch(ctx, coverRepairIDs); err != nil {
			return stats, err
		} else {
			stats.UpdatedOrNew += repaired
		}
	}

	// Read changed/new files via fast Go probe first, falling back to bounded batch_read_tags (<=200).
	if len(toRead) > 0 {
		var needHelperIdx []int
		probed := make([]*helper.AudioTagData, len(toRead))
		for i, item := range toRead {
			if d, ok := media.ProbeBasicTags(item.absPath); ok {
				probed[i] = &d
			} else {
				needHelperIdx = append(needHelperIdx, i)
			}
		}
		if len(needHelperIdx) > 0 {
			paths := make([]string, len(needHelperIdx))
			for k, idxPos := range needHelperIdx {
				paths[k] = toRead[idxPos].absPath
			}
			batchItems := idx.tags.BatchReadMetadata(ctx, paths)
			for k, idxPos := range needHelperIdx {
				if k < len(batchItems) && batchItems[k].OK && batchItems[k].Data != nil {
					probed[idxPos] = batchItems[k].Data
				}
			}
		}

		for i, item := range toRead {
			if item.existing != nil && idx.locks.IsWriteActive(item.existing.id) {
				stats.SkippedLocked++
				continue
			}
			tagData := probed[i]
			if tagData == nil {
				stem := strings.TrimSuffix(item.name, filepath.Ext(item.name))
				tagData = &helper.AudioTagData{
					Tags:     map[string]string{"title": stem},
					Editable: media.EditableExtensions[strings.ToLower(filepath.Ext(item.name))],
				}
			}
			metaCopy := make(map[string]string, len(tagData.Tags)+2)
			for k, v := range tagData.Tags {
				metaCopy[k] = v
			}
			hasCover := tagData.HasCover || media.HasLocalCover(item.absPath)
			if hasCover {
				metaCopy["_has_cover"] = "1"
			}
			if tagData.Bitrate != nil && *tagData.Bitrate > 0 {
				metaCopy["_bitrate"] = strconv.Itoa(*tagData.Bitrate)
			}
			rec := store.AssetRecord{
				RootID:            rootRec.ID,
				RelPath:           item.relPath,
				PathKey:           item.pathKey,
				Folder:            item.folder,
				Name:              item.name,
				Status:            "active",
				Editable:          tagData.Editable,
				Size:              item.size,
				MTimeNS:           item.mtimeNS,
				Title:             nonEmptyOr(tagData.Tags["title"], strings.TrimSuffix(item.name, filepath.Ext(item.name))),
				Artist:            tagData.Tags["artist"],
				Album:             tagData.Tags["album"],
				DurationMS:        int64(tagData.Duration * 1000),
				Metadata:          metaCopy,
				IndexedGeneration: stats.Generation,
			}
			var expRev int64
			if item.existing != nil {
				rec.ID = item.existing.id
				expRev = item.existing.revision
			}
			up, upErr := idx.store.UpsertAsset(ctx, rec, expRev, false)
			if upErr != nil {
				// Skip on revision conflict so stale scan never overwrites newer file/tag commit.
				if de, ok := model.AsDomainError(upErr); ok && de.Code == model.ErrConflict {
					stats.SkippedLocked++
					continue
				}
				return stats, upErr
			}
			_ = idx.store.PutPublicRef(ctx, model.PublicRef{
				PublicID: "asset:" + up.ID,
				Kind:     "asset",
				TargetID: up.ID,
				Source:   "local",
				SourceID: up.ID,
			}, "")
			stats.UpdatedOrNew++
		}
	}

	markedMissing, err := idx.store.AdvanceScanGeneration(ctx, rootRec.ID, stats.Generation, true, "")
	if err != nil {
		return stats, err
	}
	stats.MarkedMissing = markedMissing
	if !rootBackfilled {
		_ = idx.store.MarkLibraryRootCoverBackfilled(ctx, rootRec.ID)
	}
	return stats, nil
}

func (idx *Indexer) loadExistingByPathKey(ctx context.Context, rootID string) (map[string]existingAssetSnap, error) {
	out := make(map[string]existingAssetSnap)
	err := idx.store.WithReadTx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `
			SELECT id, path_key, size, mtime_ns, revision, status, metadata_json
			FROM assets WHERE root_id = ? AND status != 'deleted'
		`, rootID)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var pk, metaJSON string
			var snap existingAssetSnap
			if err := rows.Scan(&snap.id, &pk, &snap.size, &snap.mtimeNS, &snap.revision, &snap.status, &metaJSON); err != nil {
				return err
			}
			if strings.Contains(metaJSON, "_has_cover") {
				var meta map[string]string
				if json.Unmarshal([]byte(metaJSON), &meta) == nil {
					snap.hasCoverMeta = meta["_has_cover"] == "1"
				}
			}
			out[pk] = snap
		}
		return rows.Err()
	})
	return out, err
}

func (idx *Indexer) repairAssetCoverBatch(ctx context.Context, assetIDs []string) (int, error) {
	const chunkSize = 100
	now := time.Now().UTC().UnixMilli()
	updated := 0
	for start := 0; start < len(assetIDs); start += chunkSize {
		end := start + chunkSize
		if end > len(assetIDs) {
			end = len(assetIDs)
		}
		chunk := assetIDs[start:end]
		if err := idx.store.WithTx(ctx, func(tx *sql.Tx) error {
			for _, id := range chunk {
				var rawMeta string
				if err := tx.QueryRowContext(ctx, `SELECT metadata_json FROM assets WHERE id = ?`, id).Scan(&rawMeta); err != nil {
					continue
				}
				meta := make(map[string]string)
				if rawMeta != "" {
					_ = json.Unmarshal([]byte(rawMeta), &meta)
				}
				if meta["_has_cover"] == "1" {
					continue
				}
				meta["_has_cover"] = "1"
				encoded, _ := json.Marshal(meta)
				if _, err := tx.ExecContext(ctx, `
					UPDATE assets SET metadata_json = ?, updated_at = ? WHERE id = ?
				`, string(encoded), now, id); err != nil {
					return err
				}
				updated++
			}
			return nil
		}); err != nil {
			return updated, err
		}
	}
	return updated, nil
}

func (idx *Indexer) markGenerationBatch(ctx context.Context, assetIDs []string, gen int64) error {
	const chunkSize = 200
	now := time.Now().UTC().UnixMilli()
	for start := 0; start < len(assetIDs); start += chunkSize {
		end := start + chunkSize
		if end > len(assetIDs) {
			end = len(assetIDs)
		}
		chunk := assetIDs[start:end]
		if err := idx.store.WithTx(ctx, func(tx *sql.Tx) error {
			placeholders := strings.TrimSuffix(strings.Repeat("?,", len(chunk)), ",")
			args := make([]any, 0, len(chunk)+2)
			args = append(args, gen, now)
			for _, id := range chunk {
				args = append(args, id)
			}
			_, err := tx.ExecContext(ctx, fmt.Sprintf(`
				UPDATE assets SET indexed_generation = MAX(indexed_generation, ?), updated_at = ?
				WHERE id IN (%s)
			`, placeholders), args...)
			return err
		}); err != nil {
			return err
		}
	}
	return nil
}

func nonEmptyOr(val, fallback string) string {
	if strings.TrimSpace(val) != "" {
		return val
	}
	return fallback
}

var _ = json.Marshal
