package index

import (
	"context"
	"encoding/binary"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

type countingTagger struct {
	batchCalls int
}

func (c *countingTagger) ReadTags(_ context.Context, path string) (helper.AudioTagData, error) {
	return helper.AudioTagData{Path: path, Tags: map[string]string{"title": "T"}, Editable: true}, nil
}
func (c *countingTagger) BatchReadTags(_ context.Context, paths []string) ([]helper.BatchTagItem, error) {
	c.batchCalls++
	out := make([]helper.BatchTagItem, len(paths))
	for i, p := range paths {
		out[i] = helper.BatchTagItem{
			Path: p, OK: true,
			Data: &helper.AudioTagData{Path: p, Tags: map[string]string{"title": filepath.Base(p), "artist": "Tester"}, Editable: true, Duration: 10},
		}
	}
	return out, nil
}
func (c *countingTagger) WriteTags(_ context.Context, req helper.WriteTagsRequest) (helper.AudioTagData, error) {
	return helper.AudioTagData{}, nil
}

// writeMinimalWAV writes a valid 44-byte WAV header + 1600 bytes of silence so ProbeBasicTags parses it natively.
func writeMinimalWAV(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 44+1600)
	copy(buf[0:4], "RIFF")
	binary.LittleEndian.PutUint32(buf[4:8], uint32(36+1600))
	copy(buf[8:12], "WAVE")
	copy(buf[12:16], "fmt ")
	binary.LittleEndian.PutUint32(buf[16:20], 16)
	binary.LittleEndian.PutUint16(buf[20:22], 1)
	binary.LittleEndian.PutUint16(buf[22:24], 2)
	binary.LittleEndian.PutUint32(buf[24:28], 8000)
	binary.LittleEndian.PutUint32(buf[28:32], 32000)
	binary.LittleEndian.PutUint16(buf[32:34], 4)
	binary.LittleEndian.PutUint16(buf[34:36], 16)
	copy(buf[36:40], "data")
	binary.LittleEndian.PutUint32(buf[40:44], 1600)
	if err := os.WriteFile(path, buf, 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestIncrementalScanGenerationRootOfflineAndPagination(t *testing.T) {
	ctx := context.Background()
	dbPath := filepath.Join(t.TempDir(), "go", "melokeep.db")
	st, err := store.Open(ctx, store.Options{Path: dbPath})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	rootDir := filepath.Join(t.TempDir(), "music")
	_ = os.MkdirAll(rootDir, 0o755)
	_, rootRec, err := files.RegisterRoot(rootDir, "")
	if err != nil {
		t.Fatal(err)
	}
	if err := st.UpsertRoot(ctx, rootRec); err != nil {
		t.Fatal(err)
	}

	wav1 := filepath.Join(rootRec.CanonicalPath, "Pop", "a.wav")
	mp3 := filepath.Join(rootRec.CanonicalPath, "Pop", "b.mp3")
	ignored := filepath.Join(rootRec.CanonicalPath, ".melokeep-history", "bak.wav")
	writeMinimalWAV(t, wav1)
	writeMinimalWAV(t, ignored)
	_ = os.WriteFile(mp3, []byte("ID3_PLACEHOLDER"), 0o644)

	tagger := &countingTagger{}
	locks := files.NewAssetLockManager(5 * time.Second)
	idx := NewIndexer(st, locks, media.NewTagService(tagger, nil))

	// 1. Initial scan: a.wav probed natively, b.mp3 batched via Tagger, .melokeep-history ignored.
	stats1, err := idx.ScanRoot(ctx, rootRec)
	if err != nil {
		t.Fatal(err)
	}
	if stats1.ScannedFiles != 2 || stats1.UpdatedOrNew != 2 || tagger.batchCalls != 1 {
		t.Fatalf("unexpected initial scan stats: %+v (batchCalls=%d)", stats1, tagger.batchCalls)
	}

	// 2. Rescan unchanged files: must skip tag reading completely.
	rootRec, _, _ = st.GetRoot(ctx, rootRec.ID)
	stats2, err := idx.ScanRoot(ctx, rootRec)
	if err != nil {
		t.Fatal(err)
	}
	if stats2.UnchangedFiles != 2 || stats2.UpdatedOrNew != 0 || tagger.batchCalls != 1 {
		t.Fatalf("expected incremental skip on unchanged files, got %+v (batchCalls=%d)", stats2, tagger.batchCalls)
	}

	// 3. Pagination & index_revision 409 check.
	page1, err := idx.QueryFiles(ctx, store.LibraryFilesQuery{Limit: 1})
	if err != nil || len(page1.Items) != 1 || page1.NextCursor == nil {
		t.Fatalf("unexpected page1: %+v (%v)", page1, err)
	}
	// Filter mismatch -> 400.
	if _, err := idx.QueryFiles(ctx, store.LibraryFilesQuery{Limit: 1, Cursor: *page1.NextCursor, Query: "other"}); err == nil {
		t.Fatalf("expected 400 on filter mismatch")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400, got %v", err)
	}

	// 4. Lock one asset for write and add a new file; locked asset must be skipped cleanly.
	pageAll, _ := idx.QueryFiles(ctx, store.LibraryFilesQuery{Limit: 10})
	lockedAssetID := pageAll.Items[0].Track.SourceID
	wRel, err := locks.AcquireWrite(ctx, lockedAssetID)
	if err != nil {
		t.Fatal(err)
	}
	wav2 := filepath.Join(rootRec.CanonicalPath, "Rock", "c.wav")
	writeMinimalWAV(t, wav2)
	rootRec, _, _ = st.GetRoot(ctx, rootRec.ID)
	stats3, err := idx.ScanRoot(ctx, rootRec)
	wRel()
	if err != nil || stats3.SkippedLocked != 1 || stats3.UpdatedOrNew != 1 {
		t.Fatalf("expected 1 skipped locked asset and 1 new file, got %+v (%v)", stats3, err)
	}

	// Stale cursor from page1 after index_revision changed -> 409.
	if _, err := idx.QueryFiles(ctx, store.LibraryFilesQuery{Limit: 1, Cursor: *page1.NextCursor}); err == nil {
		t.Fatalf("expected 409 on stale index_revision")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409, got %v", err)
	}

	// 5. Removing c.wav and completing full scan marks it missing.
	_ = os.Remove(wav2)
	rootRec, _, _ = st.GetRoot(ctx, rootRec.ID)
	stats4, err := idx.ScanRoot(ctx, rootRec)
	if err != nil || stats4.MarkedMissing != 1 {
		t.Fatalf("expected 1 asset marked missing, got %+v (%v)", stats4, err)
	}

	// 6. Unreachable/offline root MUST NOT mark remaining active assets missing!
	offlineRoot := rootRec
	offlineRoot.CanonicalPath = filepath.Join(t.TempDir(), "unmounted_nas")
	stats5, err := idx.ScanRoot(ctx, offlineRoot)
	if err != nil || stats5.Healthy || stats5.MarkedMissing != 0 {
		t.Fatalf("expected unhealthy root without marking missing, got %+v (%v)", stats5, err)
	}
	pageAfterOffline, _ := idx.QueryFiles(ctx, store.LibraryFilesQuery{Limit: 10})
	if len(pageAfterOffline.Items) != 2 {
		t.Fatalf("expected remaining 2 active assets preserved when root offline, got %d", len(pageAfterOffline.Items))
	}
}

func TestIndexerBackfillsMissingCoverAndDetectsSidecarCover(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(t.TempDir())})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	rawRoot := filepath.Join(t.TempDir(), "music")
	_ = os.MkdirAll(rawRoot, 0o755)
	_, rootRec, err := files.RegisterRoot(rawRoot, "")
	if err != nil {
		t.Fatal(err)
	}
	if err := st.UpsertRoot(ctx, rootRec); err != nil {
		t.Fatal(err)
	}

	album1 := filepath.Join(rootRec.CanonicalPath, "album1")
	album2 := filepath.Join(rootRec.CanonicalPath, "album2")
	_ = os.MkdirAll(album1, 0o755)
	_ = os.MkdirAll(album2, 0o755)

	song1 := filepath.Join(album1, "song.wav")
	song2 := filepath.Join(album2, "plain.wav")
	writeMinimalWAV(t, song1)
	writeMinimalWAV(t, song2)
	if err := os.WriteFile(filepath.Join(album1, "cover.jpg"), []byte{0xff, 0xd8, 0xff, 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0xff, 0xd9}, 0o644); err != nil {
		t.Fatal(err)
	}

	idx := NewIndexer(st, files.NewAssetLockManager(2*time.Second), media.NewTagService(&countingTagger{}, nil))
	if _, err := idx.ScanRoot(ctx, rootRec); err != nil {
		t.Fatalf("initial ScanRoot failed: %v", err)
	}

	coverByName := func() map[string]*string {
		p, qErr := idx.QueryFiles(ctx, store.LibraryFilesQuery{Limit: 20})
		if qErr != nil {
			t.Fatalf("QueryFiles: %v", qErr)
		}
		m := make(map[string]*string, len(p.Items))
		for _, it := range p.Items {
			m[it.Name] = it.Track.Cover
		}
		return m
	}

	covs := coverByName()
	if covs["song.wav"] == nil || *covs["song.wav"] == "" {
		t.Fatalf("expected sidecar cover URL on song.wav, got %v", covs["song.wav"])
	}
	if covs["plain.wav"] != nil {
		t.Fatalf("expected nil cover on plain.wav without cover, got %v", *covs["plain.wav"])
	}

	// Simulate legacy DB rows whose metadata_json lost _has_cover while size & mtime_ns already match disk.
	if _, err := st.Writer().ExecContext(ctx, `UPDATE assets SET metadata_json = '{}' WHERE name = 'song.wav'`); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Writer().ExecContext(ctx, `DELETE FROM settings WHERE namespace = ?`, store.NamespaceLibraryIndex); err != nil {
		t.Fatal(err)
	}

	rootRec, _, _ = st.GetRoot(ctx, rootRec.ID)
	statsBackfill, err := idx.ScanRoot(ctx, rootRec)
	if err != nil {
		t.Fatalf("backfill ScanRoot failed: %v", err)
	}
	if statsBackfill.UpdatedOrNew != 1 {
		t.Fatalf("expected 1 legacy asset repaired via cover backfill, got %+v", statsBackfill)
	}
	if covs = coverByName(); covs["song.wav"] == nil || *covs["song.wav"] == "" {
		t.Fatalf("expected song.wav cover restored after backfill, got %v", covs["song.wav"])
	}
	idxState, err := st.GetLibraryIndexState(ctx)
	if err != nil || !idxState.CoverBackfillRoots[rootRec.ID] {
		t.Fatalf("expected CoverBackfillRoots[%s]=true, got %+v err=%v", rootRec.ID, idxState, err)
	}

	// Add cover.jpg for album2 at runtime: routine ScanRoot skips unchanged plain.wav,
	// whereas ScanRootWithOptions{RepairMissingCovers: true} picks up the new cover.
	if err := os.WriteFile(filepath.Join(album2, "cover.jpg"), []byte{0xff, 0xd8, 0xff, 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0xff, 0xd9}, 0o644); err != nil {
		t.Fatal(err)
	}
	rootRec, _, _ = st.GetRoot(ctx, rootRec.ID)
	routineStats, err := idx.ScanRoot(ctx, rootRec)
	if err != nil {
		t.Fatalf("routine ScanRoot failed: %v", err)
	}
	if routineStats.UpdatedOrNew != 0 {
		t.Fatalf("expected routine ScanRoot after backfill not to re-probe, got %+v", routineStats)
	}
	manualStats, err := idx.ScanRootWithOptions(ctx, rootRec, ScanOptions{RepairMissingCovers: true})
	if err != nil {
		t.Fatalf("manual repair ScanRootWithOptions failed: %v", err)
	}
	if manualStats.UpdatedOrNew != 1 {
		t.Fatalf("expected manual repair ScanRootWithOptions to detect 1 new cover, got %+v", manualStats)
	}
	if covs = coverByName(); covs["plain.wav"] == nil || *covs["plain.wav"] == "" {
		t.Fatalf("expected plain.wav cover after manual scan, got %v", covs["plain.wav"])
	}
}
