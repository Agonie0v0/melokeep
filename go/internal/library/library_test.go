package library

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type memTagger struct {
	mu     sync.Mutex
	tags   map[string]helper.AudioTagData
	writes []string
}

func newMemTagger() *memTagger {
	return &memTagger{tags: make(map[string]helper.AudioTagData)}
}

func (m *memTagger) ReadTags(_ context.Context, path string) (helper.AudioTagData, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if d, ok := m.tags[path]; ok {
		return d, nil
	}
	return helper.AudioTagData{Path: path, Tags: map[string]string{}, Editable: true, Duration: 210}, nil
}

func (m *memTagger) BatchReadTags(ctx context.Context, paths []string) ([]helper.BatchTagItem, error) {
	out := make([]helper.BatchTagItem, len(paths))
	for i, p := range paths {
		d, _ := m.ReadTags(ctx, p)
		out[i] = helper.BatchTagItem{Path: p, OK: true, Data: &d}
	}
	return out, nil
}

func (m *memTagger) WriteTags(_ context.Context, req helper.WriteTagsRequest) (helper.AudioTagData, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, req.StagePath)
	d := helper.AudioTagData{
		Path:     req.StagePath,
		Tags:     cloneStringMap(req.Tags),
		Editable: true,
		Duration: 210,
	}
	if req.Lyrics != nil {
		d.Lyrics = *req.Lyrics
	}
	m.tags[req.StagePath] = d
	return d, nil
}

func setupLibraryTestEnvWithProviders(t *testing.T, tagger *memTagger, reg *provider.Registry, scraperOpts ScraperOptions, hook NavidromeRefreshHook, afterPhase func(string) error) (*store.Store, *files.Coordinator, *Service, files.Root) {
	t.Helper()
	ctx := context.Background()
	tmp := t.TempDir()
	rootDir := filepath.Join(tmp, "music")
	if err := os.MkdirAll(rootDir, 0o755); err != nil {
		t.Fatalf("mkdir root: %v", err)
	}
	st, err := store.Open(ctx, store.Options{Path: filepath.Join(tmp, "melokeep.db")})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	root, rec, err := files.RegisterRoot(rootDir, "root-1")
	if err != nil {
		t.Fatalf("register root: %v", err)
	}
	if err := st.UpsertRoot(ctx, rec); err != nil {
		t.Fatalf("upsert root: %v", err)
	}

	locks := files.NewAssetLockManager(2 * time.Second)
	coord := files.NewCoordinator(st, locks, []files.Root{root})
	var runner *media.Runner
	ffmpegPath := `E:\tool\ffmpeg\bin\ffmpeg.exe`
	if _, err := os.Stat(ffmpegPath); err == nil {
		runner = media.NewRunner(config.DefaultResourceBudgets(), ffmpegPath, "", "")
	}
	tagSvc := media.NewTagService(tagger, runner)
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	t.Cleanup(func() { pool.CloseAll() })
	scraper := NewScraper(pool, reg, runner, scraperOpts)
	svc := NewService(st, coord, tagSvc, runner, scraper, ServiceOptions{
		NavidromeHook:  hook,
		TestAfterPhase: afterPhase,
	})
	t.Cleanup(func() { _ = svc.Close() })
	return st, coord, svc, root
}

func setupLibraryTestEnv(t *testing.T, tagger *memTagger, scraperOpts ScraperOptions, hook NavidromeRefreshHook, afterPhase func(string) error) (*store.Store, *files.Coordinator, *Service, files.Root) {
	t.Helper()
	return setupLibraryTestEnvWithProviders(t, tagger, nil, scraperOpts, hook, afterPhase)
}

func TestLibraryPreviewReviewApplyAndRestoreChain(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	pngBytes := []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0}

	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ws/2/recording":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"recordings": []map[string]any{
					{
						"id":     "rec-1",
						"title":  "晨风序曲 (Live)", // different version forces needs_review or lower score
						"length": 269000,
						"artist-credit": []map[string]any{
							{"name": "林澈", "joinphrase": ""},
						},
						"releases": []map[string]any{
							{
								"id":    "11111111-1111-1111-1111-111111111111",
								"title": "海风随想",
								"date":  "2024-07-31",
								"artist-credit": []map[string]any{
									{"name": "林澈", "joinphrase": ""},
								},
							},
						},
					},
				},
			})
		case "/ws/2/release/11111111-1111-1111-1111-111111111111":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"title": "海风随想",
				"date":  "2024-07-31",
				"artist-credit": []map[string]any{
					{"name": "林澈", "joinphrase": ""},
				},
				"cover-art-archive": map[string]any{"front": true},
				"media": []map[string]any{
					{
						"position": 1,
						"tracks": []map[string]any{
							{"position": 3, "recording": map[string]any{"id": "rec-1"}},
						},
					},
				},
			})
		case "/release/11111111-1111-1111-1111-111111111111/front-500":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(pngBytes)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	tagger := newMemTagger()
	var navRefreshes atomic.Int32
	st, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{
		MusicBrainzBaseURL: srv.URL + "/ws/2",
		CoverArtBaseURL:    srv.URL,
		MBLimitInterval:    time.Millisecond,
	}, func(context.Context) error {
		navRefreshes.Add(1)
		return nil
	}, nil)

	srcDir := filepath.Join(root.CanonicalPath, "incoming")
	if err := os.MkdirAll(srcDir, 0o755); err != nil {
		t.Fatalf("mkdir incoming: %v", err)
	}
	srcAudio := filepath.Join(srcDir, "晨风序曲.flac")
	if _, err := files.WriteFileDurable(srcAudio, []byte("AUDIO-FLAC-PAYLOAD")); err != nil {
		t.Fatalf("write audio: %v", err)
	}
	srcLRC := filepath.Join(srcDir, "晨风序曲.lrc")
	if _, err := files.WriteFileDurable(srcLRC, []byte("[00:01.00]晨风序曲\n")); err != nil {
		t.Fatalf("write lrc: %v", err)
	}

	tagger.tags[srcAudio] = helper.AudioTagData{
		Path: srcAudio,
		Tags: map[string]string{
			"title":  "晨风序曲",
			"artist": "锁定歌手名",
		},
		Editable: true,
		Duration: 269,
	}

	// Pre-create asset with a locked "artist" field.
	asset, err := st.UpsertAsset(ctx, store.AssetRecord{
		ID:           model.NewUUID(),
		RootID:       root.ID,
		RelPath:      "incoming/晨风序曲.flac",
		PathKey:      "incoming/晨风序曲.flac",
		Status:       "active",
		Editable:     true,
		Size:         18,
		MTimeNS:      time.Now().UnixNano(),
		Title:        "晨风序曲",
		Artist:       "锁定歌手名",
		DurationMS:   269000,
		Metadata:     map[string]string{"title": "晨风序曲", "artist": "锁定歌手名"},
		LockedFields: []string{"artist"},
	}, 0, false)
	if err != nil {
		t.Fatalf("upsert asset: %v", err)
	}
	cfgSettings := DefaultSettings()
	cfgSettings.CoverMode = "embed"
	if _, err := svc.SaveSettings(ctx, admin, cfgSettings); err != nil {
		t.Fatalf("save settings: %v", err)
	}

	// Preview scrape+organize with MusicBrainz (low confidence due to "(Live)" mismatch -> needs_review).
	jobDTO, err := svc.Submit(ctx, admin, SubmitRequest{
		Paths:   []string{srcAudio},
		Action:  "both",
		Sources: []string{"musicbrainz"},
	})
	if err != nil {
		t.Fatalf("submit both: %v", err)
	}
	if jobDTO.Status != string(model.LibraryJobNeedsReview) {
		t.Fatalf("expected needs_review, got %s", jobDTO.Status)
	}
	if len(jobDTO.Items) != 1 || len(jobDTO.Items[0].Candidates) == 0 {
		t.Fatalf("expected 1 item with candidates, got %+v", jobDTO.Items)
	}

	// Human Review selects the candidate.
	itemID := jobDTO.Items[0].ID
	candID := jobDTO.Items[0].Candidates[0].ID
	reviewed, err := svc.Review(ctx, admin, jobDTO.ID, map[string]string{itemID: candID})
	if err != nil {
		t.Fatalf("review: %v", err)
	}
	if reviewed.Status != string(model.LibraryJobPreviewReady) {
		t.Fatalf("expected preview_ready after review, got %s", reviewed.Status)
	}
	// Locked artist field MUST NOT be overwritten by candidate ("林澈").
	if reviewed.Items[0].After["artist"] != "锁定歌手名" {
		t.Fatalf("expected locked artist preserved, got %q", reviewed.Items[0].After["artist"])
	}
	if reviewed.Items[0].After["album"] != "海风随想" || reviewed.Items[0].After["track_number"] != "3" {
		t.Fatalf("expected enriched album/track_number, got %+v", reviewed.Items[0].After)
	}

	// Apply the job.
	applied, err := svc.Apply(ctx, admin, jobDTO.ID)
	if err != nil {
		t.Fatalf("apply: %v", err)
	}
	if applied.Status != string(model.LibraryJobComplete) {
		t.Fatalf("expected complete, got %s (err=%s)", applied.Status, applied.Items[0].Error)
	}
	targetPath := applied.Items[0].Target
	if targetPath == srcAudio {
		t.Fatalf("expected file moved to library target, still %s", targetPath)
	}
	if _, err := os.Stat(targetPath); err != nil {
		t.Fatalf("expected target file present: %v", err)
	}
	if _, err := os.Stat(srcAudio); !os.IsNotExist(err) {
		t.Fatalf("expected old source moved away, stat err=%v", err)
	}
	targetLRC := filepath.Join(filepath.Dir(targetPath), "03 - 晨风序曲 (Live).lrc")
	if _, err := os.Stat(targetLRC); err != nil {
		t.Fatalf("expected companion lrc moved to %s: %v", targetLRC, err)
	}
	if navRefreshes.Load() != 1 {
		t.Fatalf("expected 1 navidrome refresh call, got %d", navRefreshes.Load())
	}

	// Verify asset record points to new location.
	updatedAsset, ok, err := st.GetAsset(ctx, asset.ID)
	if err != nil || !ok {
		t.Fatalf("get updated asset: ok=%v err=%v", ok, err)
	}
	if updatedAsset.Name == "晨风序曲.flac" {
		t.Fatalf("expected updated asset name, got %s", updatedAsset.Name)
	}

	// Restore via history chain.
	restoreRes, err := svc.Restore(ctx, admin, jobDTO.ID, itemID)
	if err != nil {
		t.Fatalf("restore: %v", err)
	}
	if restoreRes.OperationID == applied.Items[0].OperationID {
		t.Fatalf("restore must create a new file operation")
	}
	if _, err := os.Stat(srcAudio); err != nil {
		t.Fatalf("expected original audio restored at %s: %v", srcAudio, err)
	}
	if _, err := os.Stat(srcLRC); err != nil {
		t.Fatalf("expected original lrc restored at %s: %v", srcLRC, err)
	}
	if _, err := os.Stat(targetPath); !os.IsNotExist(err) {
		t.Fatalf("expected target removed after restore, err=%v", err)
	}
}

func TestLibraryPartialRetryCancelAndConflictProtection(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	tagger := newMemTagger()
	st, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, nil)

	f1 := filepath.Join(root.CanonicalPath, "a.flac")
	f2 := filepath.Join(root.CanonicalPath, "b.flac")
	_, _ = files.WriteFileDurable(f1, []byte("A"))
	_, _ = files.WriteFileDurable(f2, []byte("B"))
	tagger.tags[f1] = helper.AudioTagData{Path: f1, Tags: map[string]string{"title": "OldA"}, Editable: true}
	tagger.tags[f2] = helper.AudioTagData{Path: f2, Tags: map[string]string{"title": "OldB"}, Editable: true}

	job, err := svc.Submit(ctx, admin, SubmitRequest{
		Paths:   []string{f1, f2},
		Action:  "edit",
		Changes: map[string]string{"album": "NewAlbum"},
	})
	if err != nil {
		t.Fatalf("submit edit: %v", err)
	}

	// Simulate external modification to item 0's asset before Apply.
	jobMeta, err := svc.loadJobMeta(ctx, job.ID)
	if err != nil {
		t.Fatalf("loadJobMeta: %v", err)
	}
	a0, _, _ := st.GetAsset(ctx, jobMeta.Items[0].AssetID)
	if _, err := st.UpsertAsset(ctx, a0, a0.Revision, false); err != nil {
		t.Fatalf("bump revision: %v", err)
	}

	applied, err := svc.Apply(ctx, admin, job.ID)
	if err != nil {
		t.Fatalf("apply: %v", err)
	}
	if applied.Status != string(model.LibraryJobPartial) {
		t.Fatalf("expected partial, got %s", applied.Status)
	}
	if applied.Items[0].Status != string(model.LibraryItemFailed) || applied.Items[1].Status != string(model.LibraryItemComplete) {
		t.Fatalf("unexpected item statuses: %+v", applied.Items)
	}
	op2Before := applied.Items[1].OperationID
	writesBefore := len(tagger.writes)

	// Fix expected revision for item 0 to simulate refreshed conflict resolution, then Retry.
	// Item 1 (already complete) MUST NOT be re-executed.
	jm, _ := svc.loadJobMeta(ctx, job.ID)
	a0Latest, _, _ := st.GetAsset(ctx, jm.Items[0].AssetID)
	jm.Items[0].ExpectedRev = a0Latest.Revision
	_ = svc.saveJobMeta(ctx, jm)

	retried, err := svc.Retry(ctx, admin, job.ID)
	if err != nil {
		t.Fatalf("retry: %v", err)
	}
	if retried.Status != string(model.LibraryJobComplete) {
		t.Fatalf("expected complete after retry, got %s", retried.Status)
	}
	if retried.Items[1].OperationID != op2Before {
		t.Fatalf("completed item was re-applied on retry: %s != %s", retried.Items[1].OperationID, op2Before)
	}
	if len(tagger.writes) != writesBefore+1 {
		t.Fatalf("expected exactly 1 write on retry, got %d", len(tagger.writes)-writesBefore)
	}
}

func TestLibraryCancelStopsSubsequentItemsWithoutAbortingCurrent(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	tagger := newMemTagger()

	var svcRef *Service
	var hookOnce sync.Once
	var jobIDToCancel string
	_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, func(phase string) error {
		if phase == "backed_up" && jobIDToCancel != "" {
			hookOnce.Do(func() {
				_, _ = svcRef.Cancel(ctx, admin, jobIDToCancel)
			})
		}
		return nil
	})
	svcRef = svc

	f1 := filepath.Join(root.CanonicalPath, "c1.flac")
	f2 := filepath.Join(root.CanonicalPath, "c2.flac")
	_, _ = files.WriteFileDurable(f1, []byte("C1"))
	_, _ = files.WriteFileDurable(f2, []byte("C2"))
	tagger.tags[f1] = helper.AudioTagData{Path: f1, Tags: map[string]string{"title": "C1"}, Editable: true}
	tagger.tags[f2] = helper.AudioTagData{Path: f2, Tags: map[string]string{"title": "C2"}, Editable: true}

	job, err := svc.Submit(ctx, admin, SubmitRequest{
		Paths:   []string{f1, f2},
		Action:  "edit",
		Changes: map[string]string{"genre": "Pop"},
	})
	if err != nil {
		t.Fatalf("submit: %v", err)
	}
	jobIDToCancel = job.ID

	res, err := svc.Apply(ctx, admin, job.ID)
	if err != nil {
		t.Fatalf("apply: %v", err)
	}
	if res.Status != string(model.LibraryJobCancelled) {
		t.Fatalf("expected cancelled, got %s", res.Status)
	}
	if res.Items[0].Status != string(model.LibraryItemComplete) {
		t.Fatalf("in-flight item 0 should commit cleanly, got %s", res.Items[0].Status)
	}
	if res.Items[1].Status != string(model.LibraryItemPreviewReady) {
		t.Fatalf("subsequent item 1 should not start after cancel, got %s", res.Items[1].Status)
	}
}

func TestLibraryWatcherTwoCheckStabilityAndOfflineRoot(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	tagger := newMemTagger()
	st, coord, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, nil)

	incoming := filepath.Join(root.CanonicalPath, "incoming")
	libDir := filepath.Join(root.CanonicalPath, "library")
	_ = os.MkdirAll(incoming, 0o755)
	_ = os.MkdirAll(libDir, 0o755)

	// Reject overlapping watch and library dir.
	badSettings := DefaultSettings()
	badSettings.WatchEnabled = true
	badSettings.LibraryDir = "library"
	badSettings.WatchDirs = []string{"library/sub"}
	if _, err := svc.SaveSettings(ctx, admin, badSettings); err == nil {
		t.Fatalf("expected overlap validation error")
	}

	goodSettings := DefaultSettings()
	goodSettings.WatchEnabled = true
	goodSettings.LibraryDir = "library"
	goodSettings.WatchDirs = []string{"incoming"}
	goodSettings.WatchStableSeconds = 10
	goodSettings.WatchAction = "organize"
	goodSettings.WatchApply = true
	if _, err := svc.SaveSettings(ctx, admin, goodSettings); err != nil {
		t.Fatalf("save settings: %v", err)
	}

	song := filepath.Join(incoming, "track.flac")
	_, _ = files.WriteFileDurable(song, []byte("WATCH-TRACK"))
	tagger.tags[song] = helper.AudioTagData{
		Path: song,
		Tags: map[string]string{
			"title":        "新歌",
			"artist":       "歌手",
			"album":        "新专",
			"album_artist": "歌手",
			"track_number": "1",
		},
		Editable: true,
	}

	now := time.Unix(1700000000, 0)
	svc.Watcher().nowFn = func() time.Time { return now }

	// First scan only observes signature, must not submit yet.
	ws1, err := svc.Watcher().ScanNow(ctx)
	if err != nil {
		t.Fatalf("scan1: %v", err)
	}
	if ws1.Queued != 0 {
		t.Fatalf("expected 0 queued on first scan, got %d", ws1.Queued)
	}

	// Advance past WatchStableSeconds -> second scan submits and applies.
	now = now.Add(11 * time.Second)
	ws2, err := svc.Watcher().ScanNow(ctx)
	if err != nil {
		t.Fatalf("scan2: %v", err)
	}
	if ws2.Queued != 1 {
		t.Fatalf("expected 1 queued after stability window, got %d (detail=%s)", ws2.Queued, ws2.Detail)
	}
	moved := filepath.Join(libDir, "歌手", "新专", "01 - 新歌.flac")
	if _, err := os.Stat(moved); err != nil {
		t.Fatalf("expected watched file organized to %s: %v", moved, err)
	}

	// Offline root must be skipped safely and marked unhealthy without crashing or deleting assets.
	offlineRoot := files.Root{ID: "offline-root", CanonicalPath: filepath.Join(t.TempDir(), "missing-mount")}
	coord.UpdateRoots([]files.Root{offlineRoot})
	_ = st.UpsertRoot(ctx, store.RootRecord{
		ID:            offlineRoot.ID,
		CanonicalPath: offlineRoot.CanonicalPath,
		Enabled:       true,
	})
	ws3, err := svc.Watcher().ScanNow(ctx)
	if err != nil {
		t.Fatalf("offline scan should skip gracefully, got err=%v", err)
	}
	if len(ws3.Warnings) == 0 {
		t.Fatalf("expected warning for offline root")
	}
	rRec, ok, _ := st.GetRoot(ctx, offlineRoot.ID)
	if !ok || rRec.Healthy {
		t.Fatalf("expected offline root marked unhealthy, got %+v", rRec)
	}
}

func TestLibraryDuplicatesSplitAndAutoOrganizeDownload(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	tagger := newMemTagger()
	st, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, nil)

	d1 := filepath.Join(root.CanonicalPath, "d1.flac")
	d2 := filepath.Join(root.CanonicalPath, "d2.flac")
	_, _ = files.WriteFileDurable(d1, []byte("SAME-AUDIO-BYTES"))
	_, _ = files.WriteFileDurable(d2, []byte("SAME-AUDIO-BYTES"))

	dupJob, err := svc.Submit(ctx, admin, SubmitRequest{
		Paths:         []string{d1, d2},
		Action:        "duplicates",
		DuplicateMode: "file",
	})
	if err != nil {
		t.Fatalf("duplicates: %v", err)
	}
	if dupJob.Status != string(model.LibraryJobComplete) || len(dupJob.Duplicates) != 1 || len(dupJob.Duplicates[0]) != 2 {
		t.Fatalf("expected 1 duplicate group of 2 files, got %+v", dupJob)
	}

	// Test CUE split planning & execution if ffmpeg exists.
	cueText := "PERFORMER \"Artist\"\nTITLE \"Album\"\nFILE \"mix.wav\" WAVE\n  TRACK 01 AUDIO\n    TITLE \"Part One\"\n    INDEX 01 00:00:00\n  TRACK 02 AUDIO\n    TITLE \"Part Two\"\n    INDEX 01 00:01:00\n"
	if svc.runner != nil {
		mixPath := filepath.Join(root.CanonicalPath, "mix.wav")
		// Generate 2s test wav via minimal valid WAV header (8000Hz mono 16-bit * 2s = 32000 bytes).
		const dataBytes = 32000
		wav := make([]byte, 44+dataBytes)
		copy(wav[0:4], "RIFF")
		binary.LittleEndian.PutUint32(wav[4:8], 36+dataBytes)
		copy(wav[8:16], "WAVEfmt ")
		binary.LittleEndian.PutUint32(wav[16:20], 16)
		binary.LittleEndian.PutUint16(wav[20:22], 1)
		binary.LittleEndian.PutUint16(wav[22:24], 1)
		binary.LittleEndian.PutUint32(wav[24:28], 8000)
		binary.LittleEndian.PutUint32(wav[28:32], 16000)
		binary.LittleEndian.PutUint16(wav[32:34], 2)
		binary.LittleEndian.PutUint16(wav[34:36], 16)
		copy(wav[36:40], "data")
		binary.LittleEndian.PutUint32(wav[40:44], dataBytes)
		_, _ = files.WriteFileDurable(mixPath, wav)
		tagger.tags[mixPath] = helper.AudioTagData{Path: mixPath, Tags: map[string]string{"artist": "Artist"}, Editable: true, Duration: 2.0}

		splitJob, err := svc.Submit(ctx, admin, SubmitRequest{
			Paths:        []string{mixPath},
			Action:       "split",
			OutputFormat: "flac",
			CUE:          cueText,
		})
		if err != nil {
			t.Fatalf("submit split: %v", err)
		}
		appliedSplit, err := svc.Apply(ctx, admin, splitJob.ID)
		if err != nil {
			t.Fatalf("apply split: %v", err)
		}
		if appliedSplit.Status != string(model.LibraryJobComplete) {
			t.Fatalf("expected split complete, got %s (err=%s)", appliedSplit.Status, appliedSplit.Items[0].Error)
		}
		for _, out := range appliedSplit.Items[0].Outputs {
			if _, err := os.Stat(out.Path); err != nil {
				t.Fatalf("expected split output %s: %v", out.Path, err)
			}
		}
	}

	// Test AutoOrganizeDownload integration from store.CommitPublishedOutputs.
	dlSrc := filepath.Join(root.CanonicalPath, "downloads", "dl.flac")
	_ = os.MkdirAll(filepath.Dir(dlSrc), 0o755)
	_, _ = files.WriteFileDurable(dlSrc, []byte("DOWNLOADED-AUDIO"))
	tagger.tags[dlSrc] = helper.AudioTagData{
		Path: dlSrc,
		Tags: map[string]string{
			"title": "DLTitle", "artist": "DLArtist", "album": "DLAlbum", "album_artist": "DLArtist", "track_number": "1",
		},
		Editable: true,
	}
	trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		ID: "tr-1", Source: "ITunesMusicClient", Locator: "itunes-1", Title: "DLTitle", Artist: "DLArtist", Album: "DLAlbum", DurationMS: 180000,
	}, nil)
	if err != nil {
		t.Fatalf("upsert track: %v", err)
	}
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "pub-dl-1", Kind: "candidate", TargetID: trID, Source: "ITunesMusicClient", SourceID: "itunes-1"}, "")
	dlRes, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		ActorKey: admin.Key(), PublicID: "pub-dl-1", TrackID: trID, QualityID: "flac", QualityLabel: "FLAC",
		DedupeKey: "ITunesMusicClient|itunes-1|flac",
	})
	if err != nil {
		t.Fatalf("create download: %v", err)
	}
	dlPub, err := st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID:               dlRes.Download.ID,
		ExpectedRevision: dlRes.Download.Revision,
		AllowedStates:    []model.DownloadStatus{model.DownloadQueued},
		NewState:         model.DownloadPublishing,
	})
	if err != nil {
		t.Fatalf("cas download to publishing: %v", err)
	}
	orgJobID := model.NewUUID()
	dlDone, err := st.CommitPublishedOutputs(ctx, store.CommitPublishedOutputsParams{
		DownloadID:       dlPub.ID,
		ExpectedRevision: dlPub.Revision,
		Outputs: []store.PublishedOutputSpec{{
			Asset: store.AssetRecord{
				ID: model.NewUUID(), RootID: root.ID, RelPath: "downloads/dl.flac", PathKey: "downloads/dl.flac",
				Status: "active", Editable: true, Size: 16, MTimeNS: time.Now().UnixNano(),
				Title: "DLTitle", Artist: "DLArtist", Album: "DLAlbum",
			},
			Role: "primary",
		}},
		AutoOrganize: &store.AutoOrganizeJobSpec{
			JobID: orgJobID, Action: "organize", OwnerKey: admin.Key(),
		},
	})
	if err != nil {
		t.Fatalf("commit published outputs: %v", err)
	}
	if err := svc.AutoOrganizeDownload(ctx, orgJobID, dlDone); err != nil {
		t.Fatalf("auto organize download: %v", err)
	}
	orgDTO, err := svc.ToDTO(ctx, orgJobID)
	if err != nil {
		t.Fatalf("get org job: %v", err)
	}
	if orgDTO.Status != string(model.LibraryJobComplete) {
		t.Fatalf("expected auto organize complete, got %s", orgDTO.Status)
	}
	expectedLib := filepath.Join(root.CanonicalPath, "library", "DLArtist", "DLAlbum", "01 - DLTitle.flac")
	if _, err := os.Stat(expectedLib); err != nil {
		t.Fatalf("expected auto-organized file at %s: %v", expectedLib, err)
	}
}

func TestLibrarySearchItemCandidatesPersistedAcrossReloadAndReview(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "itunes") {
			term := r.URL.Query().Get("term")
			if strings.Contains(term, "Custom Manual Query") {
				_, _ = w.Write([]byte(`{"results":[{"trackId":9901,"trackName":"Matched Song","artistName":"Matched Artist","collectionName":"Matched Album","trackTimeMillis":180000,"releaseDate":"2024-01-01T00:00:00Z"}]}`))
				return
			}
			_, _ = w.Write([]byte(`{"results":[]}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	tagger := newMemTagger()
	_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{ITunesBaseURL: srv.URL + "/itunes"}, nil, nil)
	audio := filepath.Join(root.CanonicalPath, "unknown.wav")
	if err := os.WriteFile(audio, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644); err != nil {
		t.Fatal(err)
	}
	tagger.tags[audio] = helper.AudioTagData{
		Path:     audio,
		Editable: true,
		Tags:     map[string]string{"title": "Unknown", "artist": "Unknown"},
	}

	job, err := svc.Submit(ctx, admin, SubmitRequest{
		Action: "scrape", Paths: []string{audio}, Sources: []string{"itunes"},
	})
	if err != nil {
		t.Fatalf("Submit: %v", err)
	}
	if len(job.Items) != 1 || len(job.Items[0].Candidates) != 0 {
		t.Fatalf("expected 0 initial candidates, got %+v", job.Items)
	}
	itemID := job.Items[0].ID

	searched, err := svc.SearchItemCandidates(ctx, admin, job.ID, itemID, "Custom Manual Query", []string{"itunes"})
	if err != nil {
		t.Fatalf("SearchItemCandidates: %v", err)
	}
	if len(searched.Items[0].Candidates) != 1 {
		t.Fatalf("expected 1 persisted candidate, got %+v", searched.Items[0].Candidates)
	}
	candID := searched.Items[0].Candidates[0].ID

	// Reload from SQLite and verify Review accepts the newly searched candidate.
	reloaded, ok, err := svc.GetJob(ctx, job.ID)
	if err != nil || !ok || len(reloaded.Items[0].Candidates) != 1 {
		t.Fatalf("GetJob after SearchItemCandidates failed: ok=%v err=%v job=%+v", ok, err, reloaded)
	}
	reviewed, err := svc.Review(ctx, admin, job.ID, map[string]string{itemID: candID})
	if err != nil {
		t.Fatalf("Review after SearchItemCandidates failed: %v", err)
	}
	if reviewed.Items[0].Chosen == nil || *reviewed.Items[0].Chosen != candID {
		t.Fatalf("expected chosen=%s, got %+v", candID, reviewed.Items[0].Chosen)
	}
}

func TestLibrarySubmitByAssetTokenRelativeAndScopeDir(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	tagger := newMemTagger()
	st, coord, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, nil)

	dlDir := filepath.Join(root.CanonicalPath, "downloads")
	_ = os.MkdirAll(dlDir, 0o755)
	audio := filepath.Join(dlDir, "old-download.wav")
	if err := os.WriteFile(audio, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644); err != nil {
		t.Fatal(err)
	}
	tagger.tags[audio] = helper.AudioTagData{
		Path:     audio,
		Editable: true,
		Tags:     map[string]string{"title": "Old Song", "artist": "Old Artist", "album": "Old Album"},
	}

	// BackupDays=0 must be accepted by SaveSettings.
	cfg := DefaultSettings()
	cfg.BackupDays = 0
	saved, err := svc.SaveSettings(ctx, admin, cfg)
	if err != nil || saved.BackupDays != 0 {
		t.Fatalf("expected BackupDays=0 allowed, got %+v err=%v", saved, err)
	}

	// Register asset as indexed by catalog (/api/library/files returns path="asset:<id>").
	asset, err := st.UpsertAsset(ctx, store.AssetRecord{
		ID:       "asset-dl-1",
		RootID:   root.ID,
		RelPath:  "downloads/old-download.wav",
		PathKey:  files.ComputePathKey(root.CaseMode, "downloads/old-download.wav"),
		Folder:   "downloads",
		Name:     "old-download.wav",
		Status:   "active",
		Editable: true,
		Size:     16,
		Title:    "Old Song",
		Artist:   "Old Artist",
		Album:    "Old Album",
		Metadata: map[string]string{"title": "Old Song", "artist": "Old Artist", "album": "Old Album"},
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}

	// 1. Submit via "asset:<id>" token (exact frontend MusicLibrary flow).
	jobByToken, err := svc.Submit(ctx, admin, SubmitRequest{
		Action: "organize",
		Paths:  []string{"asset:" + asset.ID},
	})
	if err != nil {
		t.Fatalf("Submit via asset token failed: %v", err)
	}
	if len(jobByToken.Items) != 1 || jobByToken.Items[0].Target == "" {
		t.Fatalf("unexpected jobByToken items: %+v", jobByToken.Items)
	}

	// 2. Submit via relative path inside root.
	if _, err := svc.Submit(ctx, admin, SubmitRequest{
		Action: "organize",
		Paths:  []string{"downloads/old-download.wav"},
	}); err != nil {
		t.Fatalf("Submit via relative path failed: %v", err)
	}

	// 3. Submit via relative ScopeDir ("downloads") and empty ScopeDir ("" whole music root).
	relScope := "downloads"
	jobByScope, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:   "organize",
		ScopeDir: &relScope,
	})
	if err != nil || len(jobByScope.Items) != 1 {
		t.Fatalf("Submit via relative ScopeDir failed: job=%+v err=%v", jobByScope, err)
	}
	emptyScope := ""
	jobRoot, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:   "organize",
		ScopeDir: &emptyScope,
	})
	if err != nil || len(jobRoot.Items) != 1 {
		t.Fatalf("Submit via empty ScopeDir failed: job=%+v err=%v", jobRoot, err)
	}
	// Repeated preview submissions for the same path+action deduplicate in-place.
	allJobs, err := svc.ListJobs(ctx, 20)
	if err != nil || len(allJobs) != 1 || allJobs[0].ID != jobRoot.ID {
		t.Fatalf("expected deduplicated single preview job %s, got %+v err=%v", jobRoot.ID, allJobs, err)
	}

	// 4. Apply the deduplicated preview job and verify organized file moved cleanly with zero backups when BackupDays=0.
	applied, err := svc.Apply(ctx, admin, jobRoot.ID)
	if err != nil || applied.Status != string(model.LibraryJobComplete) {
		t.Fatalf("Apply failed: job=%+v err=%v", applied, err)
	}
	if _, err := os.Stat(applied.Items[0].Target); err != nil {
		t.Fatalf("expected organized target at %s: %v", applied.Items[0].Target, err)
	}
	if _, err := os.Lstat(filepath.Join(root.CanonicalPath, ".melokeep-history")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected no .melokeep-history created when BackupDays=0 (coord roots=%d)", len(coord.Roots()))
	}
}

func TestLibrarySubmitAutoApply(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	tagger := newMemTagger()
	_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, nil)

	audio := filepath.Join(root.CanonicalPath, "auto-song.wav")
	if err := os.WriteFile(audio, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644); err != nil {
		t.Fatal(err)
	}
	tagger.tags[audio] = helper.AudioTagData{
		Path:     audio,
		Editable: true,
		Tags:     map[string]string{"title": "晨风序曲", "artist": "林澈", "album": "海风随想"},
	}

	job, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:    "organize",
		Paths:     []string{audio},
		AutoApply: true,
	})
	if err != nil {
		t.Fatalf("Submit AutoApply failed: %v", err)
	}
	if job.Status != string(model.LibraryJobComplete) {
		t.Fatalf("expected immediate complete status with AutoApply=true, got %s (%+v)", job.Status, job)
	}
	if _, err := os.Stat(job.Items[0].Target); err != nil {
		t.Fatalf("expected organized file written immediately at %s: %v", job.Items[0].Target, err)
	}
}

func TestLibraryLoggerReceivesOrganizeAndPurgeEvents(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	tmp := t.TempDir()
	rootDir := filepath.Join(tmp, "music")
	_ = os.MkdirAll(rootDir, 0o755)
	st, err := store.Open(ctx, store.Options{Path: filepath.Join(tmp, "melokeep.db")})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	root, rec, err := files.RegisterRoot(rootDir, "r-log")
	if err != nil {
		t.Fatal(err)
	}
	_ = st.UpsertRoot(ctx, rec)
	coord := files.NewCoordinator(st, files.NewAssetLockManager(2*time.Second), []files.Root{root})
	tagger := newMemTagger()
	tagSvc := media.NewTagService(tagger, nil)
	var logged []string
	var mu sync.Mutex
	svc := NewService(st, coord, tagSvc, nil, nil, ServiceOptions{
		Logger: func(level, message, category, _ string) {
			mu.Lock()
			logged = append(logged, level+":"+category+":"+message)
			mu.Unlock()
		},
	})
	defer svc.Close()

	audio := filepath.Join(rootDir, "log-song.wav")
	_ = os.WriteFile(audio, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644)
	tagger.tags[audio] = helper.AudioTagData{
		Path:     audio,
		Editable: true,
		Tags:     map[string]string{"title": "LogSong", "artist": "LogArtist", "album": "LogAlbum"},
	}

	if _, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:    "organize",
		Paths:     []string{audio},
		AutoApply: true,
	}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := svc.PurgeBackups(ctx, admin); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	joined := strings.Join(logged, "\n")
	mu.Unlock()
	for _, want := range []string{":library:提交曲库任务", ":library:开始应用曲库整理", ":library:曲库整理结束", ":library:已清空历史备份"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("expected %q in library logs, got:\n%s", want, joined)
		}
	}
}

func TestLibraryPreviewDeduplication(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	otherUser := auth.ChannelActor("telegram", "u-other", auth.PermRead, auth.PermLocalWrite)
	tagger := newMemTagger()
	emptySrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/ws/2/"):
			_, _ = w.Write([]byte(`{"recordings":[]}`))
		case strings.HasPrefix(r.URL.Path, "/itunes"):
			_, _ = w.Write([]byte(`{"results":[]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer emptySrv.Close()
	st, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{
		MusicBrainzBaseURL: emptySrv.URL + "/ws/2",
		ITunesBaseURL:      emptySrv.URL + "/itunes",
		CoverArtBaseURL:    emptySrv.URL,
		LRCLIBBaseURL:      emptySrv.URL + "/api",
		MBLimitInterval:    time.Millisecond,
	}, nil, nil)

	f1 := filepath.Join(root.CanonicalPath, "incoming", "track-a.flac")
	f2 := filepath.Join(root.CanonicalPath, "incoming", "track-b.flac")
	_ = os.MkdirAll(filepath.Dir(f1), 0o755)
	_, _ = files.WriteFileDurable(f1, []byte("AUDIO-A-DATA"))
	_, _ = files.WriteFileDurable(f2, []byte("AUDIO-B-DATA"))
	tagger.tags[f1] = helper.AudioTagData{
		Path: f1, Editable: true,
		Tags: map[string]string{"title": "Song A", "artist": "Artist A", "album": "Album A"},
	}
	tagger.tags[f2] = helper.AudioTagData{
		Path: f2, Editable: true,
		Tags: map[string]string{"title": "Song B", "artist": "Artist B", "album": "Album B"},
	}

	// 1. Same path + same action in preview mode deduplicates previous preview_ready/needs_review job.
	j1, err := svc.Submit(ctx, admin, SubmitRequest{Action: "both", Paths: []string{f1}})
	if err != nil {
		t.Fatalf("submit j1: %v", err)
	}
	j2, err := svc.Submit(ctx, admin, SubmitRequest{Action: "both", Paths: []string{f1}})
	if err != nil {
		t.Fatalf("submit j2: %v", err)
	}
	if _, ok, err := svc.GetJob(ctx, j1.ID); err != nil || ok {
		t.Fatalf("expected j1 deduplicated away, got ok=%v err=%v", ok, err)
	}

	// 2. Different action on same path does NOT remove j2.
	jScrape, err := svc.Submit(ctx, admin, SubmitRequest{Action: "scrape", Paths: []string{f1}})
	if err != nil {
		t.Fatalf("submit jScrape: %v", err)
	}
	if _, ok, err := svc.GetJob(ctx, j2.ID); err != nil || !ok {
		t.Fatalf("expected j2 preserved for different action, ok=%v err=%v", ok, err)
	}

	// 3. Different ownerKey does NOT remove admin's preview jobs.
	jOther, err := svc.Submit(ctx, otherUser, SubmitRequest{Action: "both", Paths: []string{f1}})
	if err != nil {
		t.Fatalf("submit jOther: %v", err)
	}
	if _, ok, err := svc.GetJob(ctx, j2.ID); err != nil || !ok {
		t.Fatalf("expected admin j2 preserved when otherUser submits, ok=%v err=%v", ok, err)
	}

	// 4. SourceDownloadID matches regardless of action difference, replacing older unapplied preview.
	trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		ID: "tr-dedup", Source: "ITunesMusicClient", Locator: "loc-dedup", Title: "Song B", Artist: "Artist B",
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "pub-dedup", Kind: "candidate", TargetID: trID, Source: "ITunesMusicClient", SourceID: "loc-dedup"}, "")
	dlRes, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		ActorKey: admin.Key(), PublicID: "pub-dedup", TrackID: trID, QualityID: "flac", DedupeKey: "ITunesMusicClient|loc-dedup|flac",
	})
	if err != nil {
		t.Fatal(err)
	}
	jDL1, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:           "scrape",
		Paths:            []string{f2},
		SourceDownloadID: dlRes.Download.ID,
	})
	if err != nil {
		t.Fatalf("submit jDL1: %v", err)
	}
	jDL2, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:           "both",
		Paths:            []string{f2},
		SourceDownloadID: dlRes.Download.ID,
	})
	if err != nil {
		t.Fatalf("submit jDL2: %v", err)
	}
	if _, ok, err := svc.GetJob(ctx, jDL1.ID); err != nil || ok {
		t.Fatalf("expected jDL1 replaced via SourceDownloadID, ok=%v err=%v", ok, err)
	}
	if _, ok, err := svc.GetJob(ctx, jDL2.ID); err != nil || !ok {
		t.Fatalf("expected jDL2 present, ok=%v err=%v", ok, err)
	}

	// 5. Completed job is NEVER deleted by a subsequent preview submission.
	jOrg, err := svc.Submit(ctx, admin, SubmitRequest{Action: "organize", Paths: []string{f1}})
	if err != nil {
		t.Fatalf("submit jOrg: %v", err)
	}
	applied, err := svc.Apply(ctx, admin, jOrg.ID)
	if err != nil || applied.Status != string(model.LibraryJobComplete) {
		t.Fatalf("apply jOrg: status=%s err=%v", applied.Status, err)
	}
	newPath := applied.Items[0].Target
	if _, err := svc.Submit(ctx, admin, SubmitRequest{Action: "organize", Paths: []string{newPath}}); err != nil {
		t.Fatalf("submit preview after apply: %v", err)
	}
	if _, ok, err := svc.GetJob(ctx, jOrg.ID); err != nil || !ok {
		t.Fatalf("expected completed jOrg preserved, ok=%v err=%v", ok, err)
	}

	// Ensure unrelated jobs created above remain accounted for without accidental deletion.
	for _, keepID := range []string{j2.ID, jScrape.ID, jOther.ID, jDL2.ID, jOrg.ID} {
		if _, ok, err := svc.GetJob(ctx, keepID); err != nil || !ok {
			t.Fatalf("expected job %s to remain present, ok=%v err=%v", keepID, ok, err)
		}
	}
}

func TestLibraryDeleteAndBatchDeletePreservesFilesAndAudit(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	userA := auth.ChannelActor("telegram", "user-a", auth.PermRead, auth.PermLocalWrite)
	userB := auth.ChannelActor("telegram", "user-b", auth.PermRead, auth.PermLocalWrite)
	tagger := newMemTagger()
	st, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, nil)

	audioPath := filepath.Join(root.CanonicalPath, "keep-audio.flac")
	audioBytes := []byte("ORIGINAL-LOSSLESS-BYTES")
	if _, err := files.WriteFileDurable(audioPath, audioBytes); err != nil {
		t.Fatal(err)
	}
	lrcPath := filepath.Join(root.CanonicalPath, "keep-audio.lrc")
	if _, err := files.WriteFileDurable(lrcPath, []byte("[00:00.00]Lyrics\n")); err != nil {
		t.Fatal(err)
	}
	tagger.tags[audioPath] = helper.AudioTagData{
		Path: audioPath, Editable: true,
		Tags: map[string]string{"title": "Keep", "artist": "Tester", "album": "TestAlbum"},
	}

	// Apply an edit job so a file_operations audit record is linked to job_id.
	appliedJob, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:  "edit",
		Paths:   []string{audioPath},
		Changes: map[string]string{"genre": "Rock"},
	})
	if err != nil {
		t.Fatalf("submit edit: %v", err)
	}
	appliedJob, err = svc.Apply(ctx, admin, appliedJob.ID)
	if err != nil || appliedJob.Status != string(model.LibraryJobComplete) {
		t.Fatalf("apply edit: status=%s err=%v", appliedJob.Status, err)
	}
	opID := appliedJob.Items[0].OperationID
	if opID == "" {
		t.Fatalf("expected file operation id recorded on applied item")
	}

	// Delete non-existent job -> 404.
	if err := svc.DeleteJob(ctx, admin, "missing-job-id"); err == nil {
		t.Fatalf("expected 404 on missing job")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusNotFound {
		t.Fatalf("expected 404 domain error, got %v", err)
	}

	// Non-owner cannot delete admin's job -> 403.
	if err := svc.DeleteJob(ctx, userA, appliedJob.ID); err == nil {
		t.Fatalf("expected 403 for non-owner")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusForbidden {
		t.Fatalf("expected 403 domain error, got %v", err)
	}

	// Delete appliedJob -> succeeds, removes job/items/meta, detaches file_operations.job_id, preserves audio & lrc on disk.
	if err := svc.DeleteJob(ctx, admin, appliedJob.ID); err != nil {
		t.Fatalf("DeleteJob appliedJob: %v", err)
	}
	if _, ok, err := svc.GetJob(ctx, appliedJob.ID); err != nil || ok {
		t.Fatalf("expected appliedJob deleted, ok=%v err=%v", ok, err)
	}
	var itemCnt, metaCnt int
	_ = st.Reader().QueryRowContext(ctx, `SELECT COUNT(*) FROM library_items WHERE job_id = ?`, appliedJob.ID).Scan(&itemCnt)
	_ = st.Reader().QueryRowContext(ctx, `SELECT COUNT(*) FROM durable_records WHERE namespace = 'library_job_meta' AND id = ?`, appliedJob.ID).Scan(&metaCnt)
	if itemCnt != 0 || metaCnt != 0 {
		t.Fatalf("expected items=0 and meta=0 after delete, got items=%d meta=%d", itemCnt, metaCnt)
	}
	var opJobID string
	var opState string
	if err := st.Reader().QueryRowContext(ctx, `SELECT COALESCE(job_id,''), state FROM file_operations WHERE id = ?`, opID).Scan(&opJobID, &opState); err != nil {
		t.Fatalf("expected file_operations audit row preserved: %v", err)
	}
	if opJobID != "" || opState == "" {
		t.Fatalf("expected file_operations.job_id NULL and state kept, got job_id=%q state=%q", opJobID, opState)
	}

	// Create running + preview + finished jobs to test 409 and BatchDeleteJobs.
	jobQueued, err := st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "job-queued", Action: "both", State: model.LibraryJobQueued, OwnerKey: admin.Key(),
		Items: []store.LibraryItemInput{{ItemID: "i-q", OriginalPath: audioPath, Name: "keep-audio.flac"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	jobApplying, err := st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "job-applying", Action: "both", State: model.LibraryJobApplying, OwnerKey: admin.Key(),
		Items: []store.LibraryItemInput{{ItemID: "i-a", OriginalPath: audioPath, Name: "keep-audio.flac"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	jobPreview, err := st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "job-preview", Action: "both", State: model.LibraryJobPreviewReady, OwnerKey: admin.Key(),
		Items: []store.LibraryItemInput{{ItemID: "i-p", OriginalPath: audioPath, Name: "keep-audio.flac"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	jobFailed, err := st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "job-failed", Action: "both", State: model.LibraryJobFailed, OwnerKey: admin.Key(),
		Items: []store.LibraryItemInput{{ItemID: "i-f", OriginalPath: audioPath, Name: "keep-audio.flac"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	jobCancelled, err := st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "job-cancelled", Action: "both", State: model.LibraryJobCancelled, OwnerKey: admin.Key(),
		Items: []store.LibraryItemInput{{ItemID: "i-c", OriginalPath: audioPath, Name: "keep-audio.flac"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	jobUserAFinished, err := st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "job-usera-done", Action: "both", State: model.LibraryJobComplete, OwnerKey: userA.Key(),
		Items: []store.LibraryItemInput{{ItemID: "i-ua", OriginalPath: audioPath, Name: "keep-audio.flac"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	jobUserBFinished, err := st.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID: "job-userb-done", Action: "both", State: model.LibraryJobComplete, OwnerKey: userB.Key(),
		Items: []store.LibraryItemInput{{ItemID: "i-ub", OriginalPath: audioPath, Name: "keep-audio.flac"}},
	})
	if err != nil {
		t.Fatal(err)
	}

	// Single delete of running job -> 409.
	if err := svc.DeleteJob(ctx, admin, jobQueued.ID); err == nil {
		t.Fatalf("expected 409 for queued job")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 conflict, got %v", err)
	}

	// Batch delete by IDs: deduplicates IDs, skips running jobs, deletes non-running jobs.
	deletedIDs, skippedRunning, err := svc.BatchDeleteJobs(ctx, admin, []string{jobPreview.ID, jobQueued.ID, jobApplying.ID, jobPreview.ID}, "")
	if err != nil {
		t.Fatalf("BatchDeleteJobs by ids: %v", err)
	}
	if len(deletedIDs) != 1 || deletedIDs[0] != jobPreview.ID {
		t.Fatalf("unexpected deletedIDs: %v", deletedIDs)
	}
	if len(skippedRunning) != 2 || skippedRunning[0] != jobQueued.ID || skippedRunning[1] != jobApplying.ID {
		t.Fatalf("unexpected skippedRunning: %v", skippedRunning)
	}

	// Non-admin mode="finished" only deletes that user's finished jobs.
	deletedUserA, skippedUserA, err := svc.BatchDeleteJobs(ctx, userA, nil, "finished")
	if err != nil {
		t.Fatalf("BatchDeleteJobs finished userA: %v", err)
	}
	if len(deletedUserA) != 1 || deletedUserA[0] != jobUserAFinished.ID || len(skippedUserA) != 0 {
		t.Fatalf("unexpected userA finished delete: deleted=%v skipped=%v", deletedUserA, skippedUserA)
	}
	if _, ok, _ := svc.GetJob(ctx, jobUserBFinished.ID); !ok {
		t.Fatalf("expected userB job untouched by userA finished cleanup")
	}
	if _, ok, _ := svc.GetJob(ctx, jobFailed.ID); !ok {
		t.Fatalf("expected admin failed job untouched by userA finished cleanup")
	}

	// Admin mode="finished" cleans remaining finished jobs while preserving running jobs.
	deletedFinished, skippedFinished, err := svc.BatchDeleteJobs(ctx, admin, nil, "finished")
	if err != nil {
		t.Fatalf("BatchDeleteJobs finished admin: %v", err)
	}
	if len(deletedFinished) != 3 || len(skippedFinished) != 0 {
		t.Fatalf("expected 3 finished jobs deleted (%s, %s, %s), got deleted=%v skipped=%v",
			jobFailed.ID, jobCancelled.ID, jobUserBFinished.ID, deletedFinished, skippedFinished)
	}
	for _, runningID := range []string{jobQueued.ID, jobApplying.ID} {
		if _, ok, _ := svc.GetJob(ctx, runningID); !ok {
			t.Fatalf("expected running job %s preserved", runningID)
		}
	}

	// Original audio & LRC files on disk must be untouched.
	gotAudio, err := os.ReadFile(audioPath)
	if err != nil || string(gotAudio) != string(audioBytes) {
		t.Fatalf("expected original audio intact on disk, got len=%d err=%v", len(gotAudio), err)
	}
	if _, err := os.Stat(lrcPath); err != nil {
		t.Fatalf("expected companion lrc intact on disk: %v", err)
	}
}

type mockMetadataLyricsPlugin struct {
	id           string
	caps         model.Capability
	metaCalls    atomic.Int32
	searchCalls  atomic.Int32
	lyricsCalls  atomic.Int32
	resolveCalls atomic.Int32
	metaItems    []provider.MetadataItem
	lyricsText   string
}

func (m *mockMetadataLyricsPlugin) ID() string                     { return m.id }
func (m *mockMetadataLyricsPlugin) EngineName() string             { return m.id }
func (m *mockMetadataLyricsPlugin) Capabilities() model.Capability { return m.caps }

func (m *mockMetadataLyricsPlugin) Search(_ context.Context, _ model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	m.searchCalls.Add(1)
	if len(m.metaItems) > 0 {
		it := m.metaItems[0]
		var cov *string
		if it.CoverURL != "" {
			cov = &it.CoverURL
		}
		_ = emit(model.ProviderTrack{
			Source:   m.id,
			SourceID: it.SourceID,
			Title:    it.Title,
			Artist:   it.Artist,
			Album:    it.Album,
			Duration: it.Duration,
			Cover:    cov,
		})
	}
	return model.SearchSummary{Count: 1}, nil
}

func (m *mockMetadataLyricsPlugin) Resolve(_ context.Context, _ model.ProviderTrack, _ model.QualityPolicy) ([]model.ResolvedMedia, error) {
	m.resolveCalls.Add(1)
	return nil, errors.New("resolve must not be called during metadata scraping")
}

func (m *mockMetadataLyricsPlugin) SearchMetadata(_ context.Context, _ string, _, _, _ string, _ int) ([]provider.MetadataItem, error) {
	m.metaCalls.Add(1)
	return m.metaItems, nil
}

func (m *mockMetadataLyricsPlugin) Lyrics(_ context.Context, _ model.ProviderTrack) (string, error) {
	m.lyricsCalls.Add(1)
	return m.lyricsText, nil
}

type mockBasicSearchPlugin struct {
	id          string
	caps        model.Capability
	searchCalls atomic.Int32
	tracks      []model.ProviderTrack
}

func (m *mockBasicSearchPlugin) ID() string                     { return m.id }
func (m *mockBasicSearchPlugin) EngineName() string             { return m.id }
func (m *mockBasicSearchPlugin) Capabilities() model.Capability { return m.caps }
func (m *mockBasicSearchPlugin) Search(_ context.Context, _ model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	m.searchCalls.Add(1)
	for _, tr := range m.tracks {
		if err := emit(tr); err != nil {
			break
		}
	}
	return model.SearchSummary{Count: len(m.tracks)}, nil
}
func (m *mockBasicSearchPlugin) Resolve(_ context.Context, _ model.ProviderTrack, _ model.QualityPolicy) ([]model.ResolvedMedia, error) {
	return nil, nil
}

func TestLibraryLRCLIBGetSearchFallbackAndMusicBrainzMerge(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	snap := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())

	t.Run("get exact match prefers syncedLyrics", func(t *testing.T) {
		var getCalls, searchCalls atomic.Int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if ua := r.Header.Get("User-Agent"); ua != "MeloKeep/3.0" {
				t.Errorf("unexpected User-Agent: %q", ua)
			}
			switch r.URL.Path {
			case "/api/get":
				getCalls.Add(1)
				q := r.URL.Query()
				if q.Get("track_name") != "Morning Breeze" || q.Get("artist_name") != "Open Collective" || q.Get("album_name") != "Dawn Horizons" || q.Get("duration") != "215" {
					t.Errorf("unexpected /api/get query: %s", r.URL.RawQuery)
				}
				_ = json.NewEncoder(w).Encode(map[string]any{
					"id":           4201,
					"trackName":    "Morning Breeze",
					"artistName":   "Open Collective",
					"albumName":    "Dawn Horizons",
					"duration":     215.0,
					"plainLyrics":  "Plain fallback line",
					"syncedLyrics": "[00:05.00]Synced morning breeze",
				})
			case "/api/search":
				searchCalls.Add(1)
				_ = json.NewEncoder(w).Encode([]any{})
			default:
				http.NotFound(w, r)
			}
		}))
		defer srv.Close()

		pool := transport.NewPool(transport.WithAllowTestLoopback(true))
		defer pool.CloseAll()
		sc := NewScraper(pool, nil, nil, ScraperOptions{LRCLIBBaseURL: srv.URL + "/api"})
		cands, warns := sc.Search(ctx, snap, ScrapeReference{
			Title: "Morning Breeze", Artist: "Open Collective", Album: "Dawn Horizons", Duration: 214.8,
		}, []string{"lrclib"})
		if len(warns) != 0 || len(cands) != 1 {
			t.Fatalf("expected 1 candidate without warnings, got cands=%+v warns=%v", cands, warns)
		}
		if cands[0].Source != "lrclib" || cands[0].SourceID != "4201" || cands[0].Lyrics != "[00:05.00]Synced morning breeze" {
			t.Fatalf("unexpected lrclib candidate: %+v", cands[0])
		}
		if getCalls.Load() != 1 || searchCalls.Load() != 0 {
			t.Fatalf("expected /get=1 and /search=0, got get=%d search=%d", getCalls.Load(), searchCalls.Load())
		}
	})

	t.Run("get 404 or empty lyrics falls back to search max 3", func(t *testing.T) {
		var mode atomic.Int32 // 0 -> 404 on /get, 1 -> 200 empty lyrics on /get
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/api/get":
				if mode.Load() == 0 {
					w.WriteHeader(http.StatusNotFound)
					return
				}
				_ = json.NewEncoder(w).Encode(map[string]any{
					"id":           99,
					"trackName":    "Open Sky",
					"artistName":   "Studio Band",
					"instrumental": true,
					"plainLyrics":  "",
					"syncedLyrics": "",
				})
			case "/api/search":
				if q := r.URL.Query().Get("q"); q != "Open Sky Studio Band" {
					t.Errorf("unexpected /api/search q=%q", q)
				}
				_ = json.NewEncoder(w).Encode([]map[string]any{
					{"id": 1, "trackName": "Open Sky", "artistName": "Studio Band", "albumName": "Empty", "plainLyrics": "", "syncedLyrics": ""},
					{"id": 2, "trackName": "Open Sky", "artistName": "Studio Band", "albumName": "First", "duration": 180.0, "plainLyrics": "plain two", "syncedLyrics": "[00:01.00]synced two"},
					{"id": 3, "trackName": "Open Sky", "artistName": "Studio Band", "albumName": "Second", "duration": 181.0, "plainLyrics": "plain three", "syncedLyrics": ""},
					{"id": 4, "trackName": "Open Sky", "artistName": "Studio Band", "albumName": "Third", "duration": 182.0, "plainLyrics": "plain four", "syncedLyrics": "[00:03.00]synced four"},
					{"id": 5, "trackName": "Open Sky", "artistName": "Studio Band", "albumName": "Fourth", "duration": 183.0, "plainLyrics": "plain five"},
				})
			default:
				http.NotFound(w, r)
			}
		}))
		defer srv.Close()

		pool := transport.NewPool(transport.WithAllowTestLoopback(true))
		defer pool.CloseAll()
		sc := NewScraper(pool, nil, nil, ScraperOptions{LRCLIBBaseURL: srv.URL + "/api"})

		cands, warns := sc.Search(ctx, snap, ScrapeReference{Title: "Open Sky", Artist: "Studio Band"}, []string{"lrclib"})
		if len(warns) != 0 || len(cands) != 3 {
			t.Fatalf("expected 3 fallback candidates on 404, got len=%d warns=%v", len(cands), warns)
		}
		if cands[0].Lyrics != "[00:01.00]synced two" || cands[1].Lyrics != "plain three" || cands[2].Lyrics != "[00:03.00]synced four" {
			t.Fatalf("unexpected fallback lyrics order: %+v", cands)
		}

		mode.Store(1)
		cands2, warns2 := sc.Search(ctx, snap, ScrapeReference{Title: "Open Sky", Artist: "Studio Band"}, []string{"lrclib"})
		if len(warns2) != 0 || len(cands2) != 3 {
			t.Fatalf("expected 3 fallback candidates on empty /get lyrics, got len=%d warns=%v", len(cands2), warns2)
		}
	})

	t.Run("musicbrainz itunes and lrclib merge clears warnings", func(t *testing.T) {
		var lrcAlbum atomic.Value
		lrcAlbum.Store("Dawn Horizons")
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/ws/2/recording":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"recordings": []map[string]any{
						{
							"id":     "mb-rec-1",
							"title":  "Morning Breeze",
							"length": 200000,
							"artist-credit": []map[string]any{
								{"name": "Open Collective", "joinphrase": ""},
							},
							"releases": []map[string]any{
								{
									"id":    "22222222-2222-2222-2222-222222222222",
									"title": "Dawn Horizons",
									"date":  "2025-02-01",
									"artist-credit": []map[string]any{
										{"name": "Open Collective", "joinphrase": ""},
									},
								},
							},
						},
					},
				})
			case "/ws/2/release/22222222-2222-2222-2222-222222222222":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"title": "Dawn Horizons",
					"date":  "2025-02-01",
					"artist-credit": []map[string]any{
						{"name": "Open Collective", "joinphrase": ""},
					},
					"cover-art-archive": map[string]any{"front": false},
					"media": []map[string]any{
						{
							"position": 1,
							"tracks": []map[string]any{
								{"position": 2, "recording": map[string]any{"id": "mb-rec-1"}},
							},
						},
					},
				})
			case "/itunes/search":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"results": []map[string]any{
						{
							"trackId":         7788,
							"trackName":       "Morning Breeze",
							"artistName":      "Open Collective",
							"collectionName":  "Dawn Horizons",
							"releaseDate":     "2025-02-01T00:00:00Z",
							"trackNumber":     2,
							"discNumber":      1,
							"trackTimeMillis": 200000,
						},
					},
				})
			case "/api/get":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"id":           8801,
					"trackName":    "Morning Breeze",
					"artistName":   "Open Collective",
					"albumName":    lrcAlbum.Load().(string),
					"duration":     200.0,
					"syncedLyrics": "[00:10.00]Sunrise over open hills",
				})
			default:
				http.NotFound(w, r)
			}
		}))
		defer srv.Close()

		tagger := newMemTagger()
		_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{
			MusicBrainzBaseURL: srv.URL + "/ws/2",
			ITunesBaseURL:      srv.URL + "/itunes",
			CoverArtBaseURL:    srv.URL,
			LRCLIBBaseURL:      srv.URL + "/api",
			MBLimitInterval:    time.Millisecond,
		}, nil, nil)

		audio := filepath.Join(root.CanonicalPath, "morning.flac")
		if _, err := files.WriteFileDurable(audio, []byte("AUDIO-MORNING")); err != nil {
			t.Fatal(err)
		}
		tagger.tags[audio] = helper.AudioTagData{
			Path:     audio,
			Editable: true,
			Duration: 200,
			Tags:     map[string]string{"title": "Morning Breeze", "artist": "Open Collective", "album": "Dawn Horizons"},
		}

		// Case 1: musicbrainz + lrclib
		job, err := svc.Submit(ctx, admin, SubmitRequest{
			Action:  "scrape",
			Paths:   []string{audio},
			Sources: []string{"musicbrainz", "lrclib"},
		})
		if err != nil {
			t.Fatalf("submit scrape: %v", err)
		}
		if job.Status != string(model.LibraryJobPreviewReady) {
			t.Fatalf("expected preview_ready, got %s (warnings=%v)", job.Status, job.Items[0].Warnings)
		}
		for _, w := range job.Items[0].Warnings {
			if strings.Contains(w, "MusicBrainz 不提供歌词") || strings.Contains(w, "iTunes 不提供歌词") {
				t.Fatalf("expected no-lyrics warning cleared after LRCLIB merge, got %v", job.Items[0].Warnings)
			}
		}
		jm, err := svc.loadJobMeta(ctx, job.ID)
		if err != nil {
			t.Fatal(err)
		}
		if jm.Items[0].LyricsText == nil || *jm.Items[0].LyricsText != "[00:10.00]Sunrise over open hills" {
			t.Fatalf("expected LRCLIB lyrics merged, got %v", jm.Items[0].LyricsText)
		}
		if chosenID := *job.Items[0].Chosen; chosenID == "" {
			t.Fatal("expected chosen candidate")
		} else {
			var foundFieldSource string
			for _, c := range job.Items[0].Candidates {
				if c.ID == chosenID {
					foundFieldSource = c.FieldSources["lyrics"]
				}
			}
			// If chosen candidate itself was lrclib or musicbrainz enriched from lrclib, lyrics must be present.
			if foundFieldSource == "" && jm.Items[0].Candidates[0].Source != "lrclib" {
				t.Fatalf("expected lyrics field source recorded, candidates=%+v", job.Items[0].Candidates)
			}
		}

		// Case 2: default sources (itunes + musicbrainz + lrclib) with empty album on LRCLIB still merges lyrics and clears warnings.
		lrcAlbum.Store("")
		job2, err := svc.Submit(ctx, admin, SubmitRequest{
			Action: "scrape",
			Paths:  []string{audio},
		})
		if err != nil {
			t.Fatalf("submit default scrape: %v", err)
		}
		if job2.Status != string(model.LibraryJobPreviewReady) {
			t.Fatalf("expected preview_ready on default sources, got %s", job2.Status)
		}
		for _, w := range job2.Items[0].Warnings {
			if strings.Contains(w, "MusicBrainz 不提供歌词") || strings.Contains(w, "iTunes 不提供歌词") {
				t.Fatalf("expected no-lyrics warnings cleared with default sources, got %v", job2.Items[0].Warnings)
			}
		}
		jm2, err := svc.loadJobMeta(ctx, job2.ID)
		if err != nil {
			t.Fatal(err)
		}
		if jm2.Items[0].LyricsText == nil || *jm2.Items[0].LyricsText != "[00:10.00]Sunrise over open hills" {
			t.Fatalf("expected LRCLIB lyrics merged on default sources, got %v", jm2.Items[0].LyricsText)
		}

		applied, err := svc.Apply(ctx, admin, job2.ID)
		if err != nil || applied.Status != string(model.LibraryJobComplete) {
			t.Fatalf("apply scrape failed: status=%s err=%v", applied.Status, err)
		}
		lrcOut, err := os.ReadFile(filepath.Join(root.CanonicalPath, "morning.lrc"))
		if err != nil || string(lrcOut) != "[00:10.00]Sunrise over open hills" {
			t.Fatalf("expected sidecar lrc written, got %q err=%v", string(lrcOut), err)
		}
	})
}

func TestLibraryDynamicPluginMetadataAndLyricsNeverCallsResolve(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")

	// Nil-safe degradation checks.
	var nilScraper *Scraper
	if got := nilScraper.SourceCatalog(); len(got) != 4 {
		t.Fatalf("expected 4 built-in sources on nil scraper, got %d", len(got))
	}
	if !nilScraper.ValidSourceID("lrclib") || nilScraper.ValidSourceID("unknown-plugin") {
		t.Fatalf("unexpected ValidSourceID on nil scraper")
	}
	if nilScraper.SourceLabel("lrclib") != "LRCLIB" || !nilScraper.SourceFields("lrclib")["lyrics"] {
		t.Fatalf("unexpected SourceLabel/SourceFields on nil scraper")
	}

	reg, err := provider.NewRegistry(nil)
	if err != nil {
		t.Fatal(err)
	}

	metaPlugin := &mockMetadataLyricsPlugin{
		id: "open-meta-plugin",
		caps: model.Capability{
			Tier:     "plugin",
			Note:     "开放结构化元数据插件",
			Search:   true,
			Metadata: true,
			Lyrics:   true,
		},
		metaItems: []provider.MetadataItem{
			{
				SourceID:    "meta-101",
				Title:       "Crystal Stream",
				Artist:      "Alpine Duo",
				Album:       "Valley Echoes",
				AlbumArtist: "Alpine Duo",
				Date:        "2025-05-12",
				TrackNumber: "4",
				DiscNumber:  "1",
				Duration:    195,
				Lyrics:      "", // leave empty so enrichOne must invoke LyricsProvider.Lyrics
			},
		},
		lyricsText: "[00:02.00]Water flows across the valley",
	}
	lyricsOnlyPlugin := &mockMetadataLyricsPlugin{
		id: "open-lyrics-only",
		caps: model.Capability{
			Tier:   "plugin",
			Note:   "纯歌词插件",
			Lyrics: true,
		},
		lyricsText: "[00:03.00]Lyrics-only plugin line",
	}
	basicSearchPlugin := &mockBasicSearchPlugin{
		id: "open-search-plugin",
		caps: model.Capability{
			Tier:   "plugin",
			Note:   "基础搜索插件",
			Search: true,
		},
		tracks: []model.ProviderTrack{
			{
				Source:   "open-search-plugin",
				SourceID: "sr-1",
				Title:    "Crystal Stream",
				Artist:   "Alpine Duo",
				Album:    "Valley Echoes",
				Duration: 195,
			},
		},
	}
	playlistOnlyPlugin := &mockBasicSearchPlugin{
		id:   "playlist-only-plugin",
		caps: model.Capability{Tier: "plugin", Playlist: true},
	}

	for _, entry := range []provider.Entry{
		{Provider: metaPlugin, Meta: provider.Metadata{Label: "Open Meta", Description: "动态元数据插件"}},
		{Provider: lyricsOnlyPlugin, Meta: provider.Metadata{Label: "Open Lyrics Only", Description: "仅歌词插件"}},
		{Provider: basicSearchPlugin, Meta: provider.Metadata{Label: "Open Search", Description: "基础搜索插件"}},
		{Provider: playlistOnlyPlugin, Meta: provider.Metadata{Label: "Playlist Only", Description: "仅歌单插件"}},
	} {
		if err := reg.RegisterPlugin(entry); err != nil {
			t.Fatalf("RegisterPlugin %s: %v", entry.Provider.ID(), err)
		}
	}

	mbSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ws/2/recording":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"recordings": []map[string]any{
					{
						"id":     "mb-rec-9",
						"title":  "Crystal Stream",
						"length": 195000,
						"artist-credit": []map[string]any{
							{"name": "Alpine Duo", "joinphrase": ""},
						},
						"releases": []map[string]any{
							{
								"id":    "33333333-3333-3333-3333-333333333333",
								"title": "Valley Echoes",
								"date":  "2025-05-12",
								"artist-credit": []map[string]any{
									{"name": "Alpine Duo", "joinphrase": ""},
								},
							},
						},
					},
				},
			})
		case "/ws/2/release/33333333-3333-3333-3333-333333333333":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"title":             "Valley Echoes",
				"date":              "2025-05-12",
				"cover-art-archive": map[string]any{"front": false},
			})
		default:
			http.NotFound(w, r)
		}
	}))
	defer mbSrv.Close()

	tagger := newMemTagger()
	_, _, svc, root := setupLibraryTestEnvWithProviders(t, tagger, reg, ScraperOptions{
		MusicBrainzBaseURL: mbSrv.URL + "/ws/2",
		CoverArtBaseURL:    mbSrv.URL,
		MBLimitInterval:    time.Millisecond,
	}, nil, nil)

	catalog := svc.SourceCatalog()
	if len(catalog) != 7 { // 4 built-in + 3 eligible plugins (playlist-only excluded)
		t.Fatalf("expected 7 metadata catalog sources, got %d: %+v", len(catalog), catalog)
	}
	if svc.scraper.ValidSourceID("playlist-only-plugin") {
		t.Fatalf("playlist-only plugin must not be included in metadata catalog")
	}
	if svc.scraper.SourceLabel("open-meta-plugin") != "Open Meta" {
		t.Fatalf("unexpected plugin label: %q", svc.scraper.SourceLabel("open-meta-plugin"))
	}
	metaFields := svc.scraper.SourceFields("open-meta-plugin")
	for _, wantField := range []string{"title", "artist", "album", "album_artist", "date", "track_number", "disc_number", "cover", "lyrics"} {
		if !metaFields[wantField] {
			t.Fatalf("expected field %q on open-meta-plugin, got %+v", wantField, metaFields)
		}
	}
	searchFields := svc.scraper.SourceFields("open-search-plugin")
	if !searchFields["title"] || searchFields["album_artist"] || searchFields["lyrics"] {
		t.Fatalf("unexpected fields for search-only plugin: %+v", searchFields)
	}
	lyricsOnlyFields := svc.scraper.SourceFields("open-lyrics-only")
	if !lyricsOnlyFields["lyrics"] || len(lyricsOnlyFields) != 1 {
		t.Fatalf("unexpected fields for lyrics-only plugin: %+v", lyricsOnlyFields)
	}

	audio := filepath.Join(root.CanonicalPath, "crystal.flac")
	if _, err := files.WriteFileDurable(audio, []byte("AUDIO-CRYSTAL")); err != nil {
		t.Fatal(err)
	}
	tagger.tags[audio] = helper.AudioTagData{
		Path:     audio,
		Editable: true,
		Duration: 195,
		Tags:     map[string]string{"title": "Crystal Stream", "artist": "Alpine Duo", "album": "Valley Echoes"},
	}

	// 1. MetadataProvider + LyricsProvider plugin scrape must NOT invoke Search or Resolve.
	job, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:  "scrape",
		Paths:   []string{audio},
		Sources: []string{"open-meta-plugin"},
	})
	if err != nil {
		t.Fatalf("submit plugin scrape: %v", err)
	}
	if job.Status != string(model.LibraryJobPreviewReady) {
		t.Fatalf("expected preview_ready, got %s (warnings=%v)", job.Status, job.Items[0].Warnings)
	}
	if metaPlugin.metaCalls.Load() != 1 {
		t.Fatalf("expected SearchMetadata called once, got %d", metaPlugin.metaCalls.Load())
	}
	if metaPlugin.searchCalls.Load() != 0 {
		t.Fatalf("expected Search not called when MetadataProvider succeeded, got %d", metaPlugin.searchCalls.Load())
	}
	if metaPlugin.lyricsCalls.Load() != 1 {
		t.Fatalf("expected LyricsProvider.Lyrics called once, got %d", metaPlugin.lyricsCalls.Load())
	}
	if metaPlugin.resolveCalls.Load() != 0 {
		t.Fatalf("Resolve MUST NEVER be called during lyrics enrichment, got %d", metaPlugin.resolveCalls.Load())
	}
	if job.Items[0].After["album_artist"] != "Alpine Duo" || job.Items[0].After["track_number"] != "4" || job.Items[0].After["date"] != "2025-05-12" {
		t.Fatalf("unexpected metadata mapped from plugin: %+v", job.Items[0].After)
	}
	jm, err := svc.loadJobMeta(ctx, job.ID)
	if err != nil {
		t.Fatal(err)
	}
	if jm.Items[0].LyricsText == nil || *jm.Items[0].LyricsText != "[00:02.00]Water flows across the valley" {
		t.Fatalf("expected plugin lyrics populated, got %v", jm.Items[0].LyricsText)
	}

	// 2. Basic Search-only plugin falls back to Provider.Search.
	jobSearch, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:  "scrape",
		Paths:   []string{audio},
		Sources: []string{"open-search-plugin"},
	})
	if err != nil || len(jobSearch.Items[0].Candidates) != 1 || basicSearchPlugin.searchCalls.Load() != 1 {
		t.Fatalf("expected Search fallback on basic search plugin, err=%v calls=%d", err, basicSearchPlugin.searchCalls.Load())
	}

	// 3. MusicBrainz + lyrics-only plugin merges lyrics without calling Resolve.
	jobMerge, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:  "scrape",
		Paths:   []string{audio},
		Sources: []string{"musicbrainz", "open-lyrics-only"},
	})
	if err != nil {
		t.Fatalf("submit musicbrainz + lyrics-only plugin: %v", err)
	}
	if lyricsOnlyPlugin.resolveCalls.Load() != 0 {
		t.Fatalf("Resolve MUST NEVER be called on lyrics-only plugin, got %d", lyricsOnlyPlugin.resolveCalls.Load())
	}
	jmMerge, err := svc.loadJobMeta(ctx, jobMerge.ID)
	if err != nil {
		t.Fatal(err)
	}
	if jmMerge.Items[0].LyricsText == nil || *jmMerge.Items[0].LyricsText != "[00:03.00]Lyrics-only plugin line" {
		t.Fatalf("expected lyrics-only plugin lyrics merged, got %v", jmMerge.Items[0].LyricsText)
	}
	for _, w := range jobMerge.Items[0].Warnings {
		if strings.Contains(w, "MusicBrainz 不提供歌词") {
			t.Fatalf("expected MusicBrainz warning cleared after plugin lyrics merge, got %v", jobMerge.Items[0].Warnings)
		}
	}
}

func TestLibraryLegacySettingsSourceFilteringAndValidation(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")

	def := DefaultSettings()
	wantDefaults := []string{"itunes", "musicbrainz", "lrclib"}
	if !slices.Equal(def.Sources, wantDefaults) {
		t.Fatalf("DefaultSettings().Sources = %v, want %v", def.Sources, wantDefaults)
	}

	reg, err := provider.NewRegistry(nil)
	if err != nil {
		t.Fatal(err)
	}
	var pluginIDs []string
	for i := 1; i <= 9; i++ {
		id := fmt.Sprintf("plugin-meta-%d", i)
		pluginIDs = append(pluginIDs, id)
		if err := reg.RegisterPlugin(provider.Entry{
			Provider: &mockBasicSearchPlugin{
				id:   id,
				caps: model.Capability{Tier: "plugin", Search: true},
			},
			Meta: provider.Metadata{Label: fmt.Sprintf("Plugin %d", i)},
		}); err != nil {
			t.Fatal(err)
		}
	}

	tagger := newMemTagger()
	st, _, svc, root := setupLibraryTestEnvWithProviders(t, tagger, reg, ScraperOptions{}, nil, nil)

	// 1. All obsolete sources in DB -> silently falls back to default ["itunes", "musicbrainz", "lrclib"].
	legacyAllInvalid := `{"library_dir":"library","folder_template":"{album_artist}/{album}","filename_template":"{track_number} - {title}","sources":["legacy-removed-a","legacy-removed-b"],"lyrics_mode":"both","cover_mode":"both","fields":["title"]}`
	if _, err := st.PutSetting(ctx, settingsNamespace, legacyAllInvalid, 0); err != nil {
		t.Fatal(err)
	}
	loaded1, err := svc.Settings(ctx)
	if err != nil {
		t.Fatalf("Settings() failed on legacy all-invalid sources: %v", err)
	}
	if !slices.Equal(loaded1.Sources, wantDefaults) {
		t.Fatalf("expected fallback to %v, got %v", wantDefaults, loaded1.Sources)
	}

	// 2. Mixed obsolete + valid sources in DB -> silently filters obsolete sources while keeping valid ones.
	legacyMixed := `{"library_dir":"library","folder_template":"{album_artist}/{album}","filename_template":"{track_number} - {title}","sources":["legacy-removed-a","musicbrainz","lrclib","legacy-removed-b"],"lyrics_mode":"both","cover_mode":"both","fields":["title"]}`
	if _, err := st.PutSetting(ctx, settingsNamespace, legacyMixed, 0); err != nil {
		t.Fatal(err)
	}
	loaded2, err := svc.Settings(ctx)
	if err != nil {
		t.Fatalf("Settings() failed on legacy mixed sources: %v", err)
	}
	if !slices.Equal(loaded2.Sources, []string{"musicbrainz", "lrclib"}) {
		t.Fatalf("expected filtered [musicbrainz lrclib], got %v", loaded2.Sources)
	}

	// 3. Explicit SaveSettings with invalid source ID MUST return 400 validation error.
	bad := DefaultSettings()
	bad.Sources = []string{"itunes", "nonexistent-source"}
	if _, err := svc.SaveSettings(ctx, admin, bad); err == nil {
		t.Fatal("expected SaveSettings to reject invalid source ID")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 domain error, got %v", err)
	}

	// 4. SaveSettings allows up to 12 sources (4 built-ins + 8 plugins) and rejects 13.
	twelveSources := append([]string{"musicbrainz", "itunes", "acoustid", "lrclib"}, pluginIDs[:8]...)
	good12 := DefaultSettings()
	good12.Sources = twelveSources
	saved12, err := svc.SaveSettings(ctx, admin, good12)
	if err != nil || len(saved12.Sources) != 12 {
		t.Fatalf("expected 12 sources accepted, got len=%d err=%v", len(saved12.Sources), err)
	}
	tooMany13 := DefaultSettings()
	tooMany13.Sources = append(twelveSources, pluginIDs[8])
	if _, err := svc.SaveSettings(ctx, admin, tooMany13); err == nil {
		t.Fatal("expected SaveSettings to reject 13 sources")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 on 13 sources, got %v", err)
	}

	// 5. Submit with invalid source returns 400.
	audio := filepath.Join(root.CanonicalPath, "validate.flac")
	_, _ = files.WriteFileDurable(audio, []byte("AUDIO-VAL"))
	if _, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:  "scrape",
		Paths:   []string{audio},
		Sources: []string{"nonexistent-source"},
	}); err == nil {
		t.Fatal("expected Submit to reject invalid scrape source")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 on Submit invalid source, got %v", err)
	}
}

func pollLibraryJobUntil(t *testing.T, svc *Service, ctx context.Context, jobID string, timeout time.Duration, pred func(model.LibraryJobDTO) bool) model.LibraryJobDTO {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		job, ok, err := svc.GetJob(ctx, jobID)
		if err != nil {
			t.Fatalf("GetJob(%s): %v", jobID, err)
		}
		if ok && pred(job) {
			return job
		}
		time.Sleep(15 * time.Millisecond)
	}
	job, _, _ := svc.GetJob(ctx, jobID)
	t.Fatalf("timed out waiting for job %s condition; last state=%s detail=%q", jobID, job.Status, job.Detail)
	return model.LibraryJobDTO{}
}

func TestLibraryReviewExistingAndSkippedDeriveComplete(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "itunes") {
			_, _ = w.Write([]byte(`{"results":[{"trackId":1001,"trackName":"Different Title (Live)","artistName":"Other Artist","collectionName":"Other Album","trackTimeMillis":210000}]}`))
			return
		}
		http.NotFound(w, r)
	}))
	defer srv.Close()

	tagger := newMemTagger()
	_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{ITunesBaseURL: srv.URL + "/itunes"}, nil, nil)

	incoming := filepath.Join(root.CanonicalPath, "incoming")
	_ = os.MkdirAll(incoming, 0o755)
	fBoth := filepath.Join(incoming, "keep-both.flac")
	fSkip := filepath.Join(incoming, "skip-me.flac")
	fScrape := filepath.Join(incoming, "keep-scrape.flac")
	for _, p := range []string{fBoth, fSkip, fScrape} {
		if _, err := files.WriteFileDurable(p, []byte("AUDIO-PAYLOAD")); err != nil {
			t.Fatal(err)
		}
	}
	origTagsBoth := map[string]string{
		"title":        "原曲标题",
		"artist":       "原歌手",
		"album":        "原专辑",
		"album_artist": "原歌手",
		"track_number": "2",
	}
	tagger.tags[fBoth] = helper.AudioTagData{Path: fBoth, Editable: true, Duration: 210, Tags: cloneStringMap(origTagsBoth)}
	tagger.tags[fSkip] = helper.AudioTagData{Path: fSkip, Editable: true, Duration: 210, Tags: map[string]string{"title": "SkipTrack", "artist": "SkipArtist"}}
	tagger.tags[fScrape] = helper.AudioTagData{Path: fScrape, Editable: true, Duration: 210, Tags: cloneStringMap(origTagsBoth)}

	// 1. Action "both": Review("existing") keeps original tags & computes target path; skipped item does not force partial.
	jobBoth, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:  "both",
		Paths:   []string{fBoth, fSkip},
		Sources: []string{"itunes"},
	})
	if err != nil {
		t.Fatalf("submit both: %v", err)
	}
	if jobBoth.Status != string(model.LibraryJobNeedsReview) {
		t.Fatalf("expected needs_review, got %s", jobBoth.Status)
	}
	reviewed, err := svc.Review(ctx, admin, jobBoth.ID, map[string]string{
		jobBoth.Items[0].ID: "Existing",
		jobBoth.Items[1].ID: "",
	})
	if err != nil {
		t.Fatalf("Review existing: %v", err)
	}
	if reviewed.Status != string(model.LibraryJobPreviewReady) {
		t.Fatalf("expected preview_ready after Review(existing + skip), got %s", reviewed.Status)
	}
	if reviewed.Items[0].Chosen == nil || *reviewed.Items[0].Chosen != "existing" {
		t.Fatalf("expected chosen=\"existing\", got %+v", reviewed.Items[0].Chosen)
	}
	if !mapsEqual(reviewed.Items[0].After, origTagsBoth) {
		t.Fatalf("expected original tags preserved, got before=%+v after=%+v", reviewed.Items[0].Before, reviewed.Items[0].After)
	}
	wantTarget := filepath.Join(root.CanonicalPath, "library", "原歌手", "原专辑", "02 - 原曲标题.flac")
	if reviewed.Items[0].Target != wantTarget {
		t.Fatalf("expected target %s, got %s", wantTarget, reviewed.Items[0].Target)
	}
	if reviewed.Items[1].Status != string(model.LibraryItemSkipped) {
		t.Fatalf("expected item 1 skipped, got %s", reviewed.Items[1].Status)
	}

	applied, err := svc.Apply(ctx, admin, jobBoth.ID)
	if err != nil {
		t.Fatalf("Apply after Review(existing): %v", err)
	}
	if applied.Status != string(model.LibraryJobComplete) {
		t.Fatalf("expected complete (not partial) when items are complete+skipped, got %s (%s)", applied.Status, applied.Detail)
	}
	if _, err := os.Stat(wantTarget); err != nil {
		t.Fatalf("expected organized file at %s: %v", wantTarget, err)
	}

	// 2. Action "scrape": Review("existing") keeps original path and succeeds on Apply.
	jobScrape, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:  "scrape",
		Paths:   []string{fScrape},
		Sources: []string{"itunes"},
	})
	if err != nil {
		t.Fatalf("submit scrape: %v", err)
	}
	revScrape, err := svc.Review(ctx, admin, jobScrape.ID, map[string]string{
		jobScrape.Items[0].ID: "existing",
	})
	if err != nil {
		t.Fatalf("Review existing scrape: %v", err)
	}
	if revScrape.Status != string(model.LibraryJobPreviewReady) || revScrape.Items[0].Target != fScrape {
		t.Fatalf("unexpected scrape review result: %+v", revScrape)
	}
	appScrape, err := svc.Apply(ctx, admin, jobScrape.ID)
	if err != nil || appScrape.Status != string(model.LibraryJobComplete) {
		t.Fatalf("apply existing scrape failed: status=%s err=%v", appScrape.Status, err)
	}
	// Re-applying already complete job returns current DTO cleanly without error.
	reapplied, err := svc.Apply(ctx, admin, jobScrape.ID)
	if err != nil || reapplied.Status != string(model.LibraryJobComplete) {
		t.Fatalf("expected idempotent Apply on complete job, got status=%s err=%v", reapplied.Status, err)
	}

	// 3. All skipped items -> deriveFinalJobStatus must return complete.
	allSkipped := []persistedItemMeta{{Status: model.LibraryItemSkipped}, {Status: model.LibraryItemSkipped}}
	if got := deriveFinalJobStatus(allSkipped, ""); got != model.LibraryJobComplete {
		t.Fatalf("expected deriveFinalJobStatus(all skipped) = complete, got %s", got)
	}
}

func TestLibraryApplyRejectsEmptyNeedsReviewSubmission(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"results":[]}`))
	}))
	defer srv.Close()

	tagger := newMemTagger()
	_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{ITunesBaseURL: srv.URL + "/itunes"}, nil, nil)
	audio := filepath.Join(root.CanonicalPath, "unmatched.flac")
	_, _ = files.WriteFileDurable(audio, []byte("AUDIO-UNMATCHED"))
	tagger.tags[audio] = helper.AudioTagData{Path: audio, Editable: true, Tags: map[string]string{"title": "Unmatched"}}

	job, err := svc.Submit(ctx, admin, SubmitRequest{
		Action:  "scrape",
		Paths:   []string{audio},
		Sources: []string{"itunes"},
	})
	if err != nil {
		t.Fatalf("submit: %v", err)
	}
	if job.Status != string(model.LibraryJobNeedsReview) {
		t.Fatalf("expected needs_review, got %s", job.Status)
	}

	if _, err := svc.Apply(ctx, admin, job.ID); err == nil {
		t.Fatalf("expected Apply on needs_review job to fail")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrInvalidInput || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 ErrInvalidInput, got %v", err)
	}
	if _, err := svc.ApplyAsync(ctx, admin, job.ID); err == nil {
		t.Fatalf("expected ApplyAsync on needs_review job to fail")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrInvalidInput || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 ErrInvalidInput from ApplyAsync, got %v", err)
	}
	after, ok, err := svc.GetJob(ctx, job.ID)
	if err != nil || !ok || after.Status != string(model.LibraryJobNeedsReview) {
		t.Fatalf("expected job to stay needs_review, got ok=%v err=%v status=%s", ok, err, after.Status)
	}
}

func TestLibrarySubmitAsyncLifecycleAndCancelScanning(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")

	t.Run("completes background scan with logs and target assignment", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if strings.Contains(r.URL.Path, "itunes") {
				term := r.URL.Query().Get("term")
				title := strings.TrimSpace(strings.TrimSuffix(term, "ArtistAsync"))
				_ = json.NewEncoder(w).Encode(map[string]any{
					"results": []map[string]any{{
						"trackId":         2001,
						"trackName":       title,
						"artistName":      "ArtistAsync",
						"collectionName":  "AsyncAlbum",
						"trackNumber":     1,
						"trackTimeMillis": 180000,
						"releaseDate":     "2025-01-01T00:00:00Z",
					}},
				})
				return
			}
			http.NotFound(w, r)
		}))
		defer srv.Close()

		tmp := t.TempDir()
		rootDir := filepath.Join(tmp, "music")
		_ = os.MkdirAll(rootDir, 0o755)
		st, err := store.Open(ctx, store.Options{Path: filepath.Join(tmp, "melokeep.db")})
		if err != nil {
			t.Fatal(err)
		}
		defer st.Close()
		root, rec, err := files.RegisterRoot(rootDir, "r-async")
		if err != nil {
			t.Fatal(err)
		}
		_ = st.UpsertRoot(ctx, rec)
		coord := files.NewCoordinator(st, files.NewAssetLockManager(2*time.Second), []files.Root{root})
		tagger := newMemTagger()
		tagSvc := media.NewTagService(tagger, nil)
		pool := transport.NewPool(transport.WithAllowTestLoopback(true))
		defer pool.CloseAll()
		scraper := NewScraper(pool, nil, nil, ScraperOptions{ITunesBaseURL: srv.URL + "/itunes"})

		var logMu sync.Mutex
		var logs []string
		svc := NewService(st, coord, tagSvc, nil, scraper, ServiceOptions{
			Logger: func(level, message, category, taskID string) {
				logMu.Lock()
				logs = append(logs, level+":"+taskID+":"+message)
				logMu.Unlock()
			},
		})
		defer svc.Close()

		paths := make([]string, 0, 6)
		for i := 1; i <= 6; i++ {
			p := filepath.Join(root.CanonicalPath, fmt.Sprintf("song-%d.flac", i))
			_, _ = files.WriteFileDurable(p, []byte("AUDIO-ASYNC"))
			title := fmt.Sprintf("SongAsync%d", i)
			tagger.tags[p] = helper.AudioTagData{
				Path:     p,
				Editable: true,
				Duration: 180,
				Tags:     map[string]string{"title": title, "artist": "ArtistAsync", "album": "AsyncAlbum"},
			}
			paths = append(paths, p)
		}

		initial, err := svc.SubmitAsync(ctx, admin, SubmitRequest{
			Action:  "both",
			Paths:   paths,
			Sources: []string{"itunes"},
		})
		if err != nil {
			t.Fatalf("SubmitAsync: %v", err)
		}
		if initial.Status != string(model.LibraryJobScanning) {
			t.Fatalf("expected immediate status=scanning, got %s", initial.Status)
		}
		if initial.Total != 6 || initial.Processed != 0 {
			t.Fatalf("expected total=6 processed=0, got total=%d processed=%d", initial.Total, initial.Processed)
		}
		if !strings.Contains(initial.Detail, "正在扫描分析（0 / 6）") {
			t.Fatalf("unexpected initial detail: %q", initial.Detail)
		}

		finished := pollLibraryJobUntil(t, svc, ctx, initial.ID, 5*time.Second, func(j model.LibraryJobDTO) bool {
			return j.Status != string(model.LibraryJobScanning) && j.Status != string(model.LibraryJobQueued)
		})
		if finished.Status != string(model.LibraryJobPreviewReady) {
			t.Fatalf("expected preview_ready after scan, got %s (detail=%s)", finished.Status, finished.Detail)
		}
		if len(finished.Items) != 6 {
			t.Fatalf("expected 6 items, got %d", len(finished.Items))
		}
		for _, it := range finished.Items {
			if it.Status != string(model.LibraryItemPreviewReady) || it.Target == it.OriginalPath {
				t.Fatalf("expected item ready with organized target, got %+v", it)
			}
		}

		logMu.Lock()
		joined := strings.Join(logs, "\n")
		logMu.Unlock()
		if !strings.Contains(joined, "已提交曲库任务") || !strings.Contains(joined, "曲库扫描分析完成") {
			t.Fatalf("expected submit & scan completion logs, got:\n%s", joined)
		}
	})

	t.Run("duplicates via SubmitAsync reaches complete", func(t *testing.T) {
		tagger := newMemTagger()
		_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, nil)
		d1 := filepath.Join(root.CanonicalPath, "dup1.flac")
		d2 := filepath.Join(root.CanonicalPath, "dup2.flac")
		_, _ = files.WriteFileDurable(d1, []byte("SAME-BYTES"))
		_, _ = files.WriteFileDurable(d2, []byte("SAME-BYTES"))

		job, err := svc.SubmitAsync(ctx, admin, SubmitRequest{
			Action:        "duplicates",
			Paths:         []string{d1, d2},
			DuplicateMode: "file",
		})
		if err != nil {
			t.Fatalf("SubmitAsync duplicates: %v", err)
		}
		if job.Status != string(model.LibraryJobScanning) {
			t.Fatalf("expected immediate scanning, got %s", job.Status)
		}
		done := pollLibraryJobUntil(t, svc, ctx, job.ID, 5*time.Second, func(j model.LibraryJobDTO) bool {
			return j.Status != string(model.LibraryJobScanning)
		})
		if done.Status != string(model.LibraryJobComplete) || len(done.Duplicates) != 1 || len(done.Duplicates[0]) != 2 {
			t.Fatalf("expected duplicates complete with 1 group of 2, got %+v", done)
		}
	})

	t.Run("Cancel interrupts active scanning", func(t *testing.T) {
		startedCh := make(chan struct{})
		var once sync.Once
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			once.Do(func() { close(startedCh) })
			<-r.Context().Done()
		}))
		defer srv.Close()

		tagger := newMemTagger()
		_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{ITunesBaseURL: srv.URL + "/itunes"}, nil, nil)
		paths := make([]string, 0, 4)
		for i := 1; i <= 4; i++ {
			p := filepath.Join(root.CanonicalPath, fmt.Sprintf("cancel-%d.flac", i))
			_, _ = files.WriteFileDurable(p, []byte("CANCEL-PAYLOAD"))
			tagger.tags[p] = helper.AudioTagData{Path: p, Editable: true, Tags: map[string]string{"title": fmt.Sprintf("Cancel%d", i), "artist": "Art"}}
			paths = append(paths, p)
		}

		job, err := svc.SubmitAsync(ctx, admin, SubmitRequest{
			Action:  "scrape",
			Paths:   paths,
			Sources: []string{"itunes"},
		})
		if err != nil {
			t.Fatalf("SubmitAsync: %v", err)
		}
		select {
		case <-startedCh:
		case <-time.After(3 * time.Second):
			t.Fatal("timed out waiting for scraper HTTP call to start")
		}

		if _, err := svc.Cancel(ctx, admin, job.ID); err != nil {
			t.Fatalf("Cancel scanning job: %v", err)
		}
		cancelled := pollLibraryJobUntil(t, svc, ctx, job.ID, 3*time.Second, func(j model.LibraryJobDTO) bool {
			return j.Status == string(model.LibraryJobCancelled)
		})
		if cancelled.Status != string(model.LibraryJobCancelled) {
			t.Fatalf("expected cancelled status, got %s", cancelled.Status)
		}
	})

	t.Run("AutoApply via SubmitAsync organizes automatically and Close aborts in-flight scan", func(t *testing.T) {
		tagger := newMemTagger()
		_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, nil)
		p := filepath.Join(root.CanonicalPath, "auto-async.flac")
		_, _ = files.WriteFileDurable(p, []byte("AUTO-ASYNC"))
		tagger.tags[p] = helper.AudioTagData{
			Path:     p,
			Editable: true,
			Tags:     map[string]string{"title": "AutoAsync", "artist": "AutoArtist", "album": "AutoAlbum", "track_number": "1"},
		}
		job, err := svc.SubmitAsync(ctx, admin, SubmitRequest{
			Action:    "organize",
			Paths:     []string{p},
			AutoApply: true,
		})
		if err != nil {
			t.Fatalf("SubmitAsync AutoApply: %v", err)
		}
		done := pollLibraryJobUntil(t, svc, ctx, job.ID, 5*time.Second, func(j model.LibraryJobDTO) bool {
			return j.Status == string(model.LibraryJobComplete) || j.Status == string(model.LibraryJobFailed)
		})
		if done.Status != string(model.LibraryJobComplete) {
			t.Fatalf("expected complete after SubmitAsync AutoApply, got %s", done.Status)
		}
		if _, err := os.Stat(done.Items[0].Target); err != nil {
			t.Fatalf("expected file moved to %s: %v", done.Items[0].Target, err)
		}

		// Verify Close() cancels and waits for an in-flight SubmitAsync without leaking or writing after DB close.
		startedCh := make(chan struct{})
		var once sync.Once
		blockSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			once.Do(func() { close(startedCh) })
			<-r.Context().Done()
		}))
		defer blockSrv.Close()

		tmp := t.TempDir()
		rootDir2 := filepath.Join(tmp, "music")
		_ = os.MkdirAll(rootDir2, 0o755)
		st2, err := store.Open(ctx, store.Options{Path: filepath.Join(tmp, "close.db")})
		if err != nil {
			t.Fatal(err)
		}
		defer st2.Close()
		r2, rec2, err := files.RegisterRoot(rootDir2, "r-close")
		if err != nil {
			t.Fatal(err)
		}
		_ = st2.UpsertRoot(ctx, rec2)
		coord2 := files.NewCoordinator(st2, files.NewAssetLockManager(2*time.Second), []files.Root{r2})
		pool2 := transport.NewPool(transport.WithAllowTestLoopback(true))
		defer pool2.CloseAll()
		tagSvc2 := media.NewTagService(newMemTagger(), nil)
		svc2 := NewService(st2, coord2, tagSvc2, nil, NewScraper(pool2, nil, nil, ScraperOptions{ITunesBaseURL: blockSrv.URL + "/itunes"}), ServiceOptions{})

		p2 := filepath.Join(r2.CanonicalPath, "close-check.flac")
		_, _ = files.WriteFileDurable(p2, []byte("CLOSE-CHECK"))
		if _, err := svc2.SubmitAsync(ctx, admin, SubmitRequest{
			Action:  "scrape",
			Paths:   []string{p2},
			Sources: []string{"itunes"},
		}); err != nil {
			t.Fatalf("SubmitAsync close test: %v", err)
		}
		select {
		case <-startedCh:
		case <-time.After(3 * time.Second):
			t.Fatal("timed out waiting for blocked scan")
		}
		closeDone := make(chan error, 1)
		go func() { closeDone <- svc2.Close() }()
		select {
		case err := <-closeDone:
			if err != nil {
				t.Fatalf("svc2.Close: %v", err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("svc2.Close() blocked on active background scan")
		}
	})
}

func TestLibraryApplyAsyncBackgroundCompletionAndCancel(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")

	t.Run("ApplyAsync executes in background and triggers NavidromeHook", func(t *testing.T) {
		tagger := newMemTagger()
		var navCalls atomic.Int32
		_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, func(context.Context) error {
			navCalls.Add(1)
			return nil
		}, nil)

		paths := make([]string, 0, 6)
		for i := 1; i <= 6; i++ {
			p := filepath.Join(root.CanonicalPath, "incoming", fmt.Sprintf("org-%d.flac", i))
			_ = os.MkdirAll(filepath.Dir(p), 0o755)
			_, _ = files.WriteFileDurable(p, []byte("ORG-DATA"))
			tagger.tags[p] = helper.AudioTagData{
				Path:     p,
				Editable: true,
				Tags: map[string]string{
					"title":        fmt.Sprintf("AsyncTrack%d", i),
					"artist":       "AsyncArtist",
					"album":        "AsyncAlbum",
					"album_artist": "AsyncArtist",
					"track_number": fmt.Sprintf("%d", i),
				},
			}
			paths = append(paths, p)
		}

		preview, err := svc.Submit(ctx, admin, SubmitRequest{
			Action: "organize",
			Paths:  paths,
		})
		if err != nil {
			t.Fatalf("Submit organize: %v", err)
		}

		applying, err := svc.ApplyAsync(ctx, admin, preview.ID)
		if err != nil {
			t.Fatalf("ApplyAsync: %v", err)
		}
		if applying.Status != string(model.LibraryJobApplying) || applying.Detail != "正在后台写入曲库更改" {
			t.Fatalf("expected immediate applying state, got status=%s detail=%q", applying.Status, applying.Detail)
		}

		done := pollLibraryJobUntil(t, svc, ctx, preview.ID, 5*time.Second, func(j model.LibraryJobDTO) bool {
			return j.Status == string(model.LibraryJobComplete) || j.Status == string(model.LibraryJobFailed) || j.Status == string(model.LibraryJobPartial)
		})
		if done.Status != string(model.LibraryJobComplete) {
			t.Fatalf("expected complete after ApplyAsync, got %s (detail=%s)", done.Status, done.Detail)
		}
		for _, it := range done.Items {
			if it.Status != string(model.LibraryItemComplete) {
				t.Fatalf("expected item complete, got %+v", it)
			}
			if _, err := os.Stat(it.Target); err != nil {
				t.Fatalf("expected organized file at %s: %v", it.Target, err)
			}
		}
		if navCalls.Load() != 1 {
			t.Fatalf("expected NavidromeHook called once, got %d", navCalls.Load())
		}
	})

	t.Run("Cancel stops ApplyAsync between items", func(t *testing.T) {
		tagger := newMemTagger()
		var svcRef *Service
		var hookOnce sync.Once
		var jobIDToCancel string
		_, _, svc, root := setupLibraryTestEnv(t, tagger, ScraperOptions{}, nil, func(phase string) error {
			if phase == "backed_up" && jobIDToCancel != "" {
				hookOnce.Do(func() {
					_, _ = svcRef.Cancel(ctx, admin, jobIDToCancel)
				})
			}
			return nil
		})
		svcRef = svc

		f1 := filepath.Join(root.CanonicalPath, "ac1.flac")
		f2 := filepath.Join(root.CanonicalPath, "ac2.flac")
		_, _ = files.WriteFileDurable(f1, []byte("AC1"))
		_, _ = files.WriteFileDurable(f2, []byte("AC2"))
		tagger.tags[f1] = helper.AudioTagData{Path: f1, Tags: map[string]string{"title": "AC1"}, Editable: true}
		tagger.tags[f2] = helper.AudioTagData{Path: f2, Tags: map[string]string{"title": "AC2"}, Editable: true}

		job, err := svc.Submit(ctx, admin, SubmitRequest{
			Paths:   []string{f1, f2},
			Action:  "edit",
			Changes: map[string]string{"genre": "Electronic"},
		})
		if err != nil {
			t.Fatalf("submit: %v", err)
		}
		jobIDToCancel = job.ID

		if _, err := svc.ApplyAsync(ctx, admin, job.ID); err != nil {
			t.Fatalf("ApplyAsync: %v", err)
		}
		cancelled := pollLibraryJobUntil(t, svc, ctx, job.ID, 5*time.Second, func(j model.LibraryJobDTO) bool {
			return j.Status == string(model.LibraryJobCancelled)
		})
		if cancelled.Items[0].Status != string(model.LibraryItemComplete) {
			t.Fatalf("expected item 0 complete, got %s", cancelled.Items[0].Status)
		}
		if cancelled.Items[1].Status != string(model.LibraryItemPreviewReady) {
			t.Fatalf("expected item 1 untouched, got %s", cancelled.Items[1].Status)
		}
	})
}
