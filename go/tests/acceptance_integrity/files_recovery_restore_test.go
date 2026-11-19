package acceptance_integrity_test

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func registerIntegrityRoot(t *testing.T, st *store.Store, dir string) files.Root {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("mkdir root: %v", err)
	}
	root, rec, err := files.RegisterRoot(dir, "")
	if err != nil {
		t.Fatalf("RegisterRoot: %v", err)
	}
	if err := st.UpsertRoot(context.Background(), rec); err != nil {
		t.Fatalf("UpsertRoot: %v", err)
	}
	return root
}

func queryFileOpState(t *testing.T, st *store.Store, opID string) string {
	t.Helper()
	var state string
	if err := st.Reader().QueryRow(`SELECT state FROM file_operations WHERE id = ?`, opID).Scan(&state); err != nil {
		t.Fatalf("query file_operations %s: %v", opID, err)
	}
	return state
}

func countFileOpFiles(t *testing.T, st *store.Store, opID string) int {
	t.Helper()
	var n int
	if err := st.Reader().QueryRow(`SELECT COUNT(*) FROM operation_files WHERE operation_id = ?`, opID).Scan(&n); err != nil {
		t.Fatalf("count operation_files %s: %v", opID, err)
	}
	return n
}

func TestAcceptanceCrashRecoveryTransitionsDBStateAndIsIdempotentAcrossRestarts(t *testing.T) {
	ctx := context.Background()
	st := openIntegrityStore(t)
	root := registerIntegrityRoot(t, st, filepath.Join(t.TempDir(), "music"))
	coord := files.NewCoordinator(st, files.NewAssetLockManager(200*time.Millisecond), []files.Root{root})

	srcPath := filepath.Join(root.CanonicalPath, "Inbox", "song.flac")
	if err := os.MkdirAll(filepath.Dir(srcPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(srcPath, []byte("ORIG_FLAC_DATA"), 0o644); err != nil {
		t.Fatal(err)
	}
	asset, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID: root.ID, RelPath: "Inbox/song.flac", PathKey: files.ComputePathKey(root.CaseMode, "Inbox/song.flac"),
		Folder: "Inbox", Name: "song.flac", Status: "active", Editable: true, Size: 14,
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}

	for _, phase := range []string{"planned", "backed_up", "prepared", "publishing", "file_published"} {
		sentinel := errors.New("crash@" + phase)
		dst := filepath.Join(root.CanonicalPath, "Artist", "song-"+phase+".flac")
		res, err := coord.Execute(ctx, files.OperationRequest{
			Kind:             "move",
			AssetID:          asset.ID,
			ExpectedRevision: asset.Revision,
			SourcePath:       srcPath,
			TargetPath:       dst,
			Mutator: func(_ context.Context, stage string) ([]files.SidecarOutput, error) {
				return nil, os.WriteFile(stage, []byte("MODIFIED_DATA"), 0o644)
			},
			HookAfterPhase: func(p string) error {
				if p == phase {
					return sentinel
				}
				return nil
			},
		})
		_ = res
		if !errors.Is(err, sentinel) {
			t.Fatalf("phase %s: expected sentinel error, got %v", phase, err)
		}

		rep1, err := coord.RecoverOnStartup(ctx)
		if err != nil {
			t.Fatalf("phase %s RecoverOnStartup #1: %v", phase, err)
		}
		if rep1.RecoveredOps != 1 || rep1.NeedsAttentionOps != 0 {
			t.Fatalf("phase %s unexpected recovery report #1: %+v", phase, rep1)
		}

		// Verify DB state was actually updated to a terminal state (rolled_back), NOT left stuck in planned/prepared/rolling_back!
		rep2, err := coord.RecoverOnStartup(ctx)
		if err != nil {
			t.Fatalf("phase %s RecoverOnStartup #2: %v", phase, err)
		}
		if rep2.RecoveredOps != 0 || rep2.NeedsAttentionOps != 0 {
			t.Fatalf("phase %s: second RecoverOnStartup must see 0 pending ops, got %+v", phase, rep2)
		}

		raw, _ := os.ReadFile(srcPath)
		if string(raw) != "ORIG_FLAC_DATA" {
			t.Fatalf("phase %s: source corrupted: %q", phase, string(raw))
		}
		if _, stErr := os.Lstat(dst); !errors.Is(stErr, os.ErrNotExist) {
			t.Fatalf("phase %s: target leaked: %s", phase, dst)
		}
	}

	// Verify failed mutator also transitions file_operation out of 'planned' so startup recovery does not revisit it.
	mutErr := errors.New("tagger failed")
	_, err = coord.Execute(ctx, files.OperationRequest{
		Kind:             "edit",
		AssetID:          asset.ID,
		ExpectedRevision: asset.Revision,
		SourcePath:       srcPath,
		TargetPath:       srcPath,
		Mutator: func(_ context.Context, _ string) ([]files.SidecarOutput, error) {
			return nil, mutErr
		},
	})
	if !errors.Is(err, mutErr) {
		t.Fatalf("expected mutator error, got %v", err)
	}
	repAfterFail, err := coord.RecoverOnStartup(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if repAfterFail.RecoveredOps != 0 {
		t.Fatalf("failed pre-publish operation should already be terminal in DB, got %+v", repAfterFail)
	}
}

func TestAcceptanceRestoreNewSidecarIdentityAuditAndCrashSafety(t *testing.T) {
	ctx := context.Background()
	st := openIntegrityStore(t)
	root := registerIntegrityRoot(t, st, filepath.Join(t.TempDir(), "music"))
	coord := files.NewCoordinator(st, files.NewAssetLockManager(200*time.Millisecond), []files.Root{root})

	srcPath := filepath.Join(root.CanonicalPath, "Inbox", "song.flac")
	if err := os.MkdirAll(filepath.Dir(srcPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(srcPath, []byte("ORIG_AUDIO"), 0o644); err != nil {
		t.Fatal(err)
	}
	asset, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID: root.ID, RelPath: "Inbox/song.flac", PathKey: files.ComputePathKey(root.CaseMode, "Inbox/song.flac"),
		Folder: "Inbox", Name: "song.flac", Status: "active", Editable: true, Size: 10, Title: "Before",
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}

	dstPath := filepath.Join(root.CanonicalPath, "Album", "song.flac")
	newLrcPath := filepath.Join(root.CanonicalPath, "Album", "song.lrc")
	moveRes, err := coord.Execute(ctx, files.OperationRequest{
		Kind:             "move",
		AssetID:          asset.ID,
		ExpectedRevision: asset.Revision,
		SourcePath:       srcPath,
		TargetPath:       dstPath,
		NewTitle:         "After",
		Mutator: func(_ context.Context, stage string) ([]files.SidecarOutput, error) {
			if err := os.WriteFile(stage, []byte("NEW_AUDIO"), 0o644); err != nil {
				return nil, err
			}
			return []files.SidecarOutput{
				{Role: "sidecar_lrc", TargetPath: newLrcPath, Data: []byte("[00:01]generated")},
			}, nil
		},
	})
	if err != nil {
		t.Fatalf("Execute move+new sidecar: %v", err)
	}

	// 1. If newly created sidecar was replaced externally (different inode even with same bytes), Restore MUST reject (409) and protect it.
	if err := os.Remove(newLrcPath); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(newLrcPath, []byte("[00:01]generated"), 0o644); err != nil {
		t.Fatal(err)
	}
	// Note: writing a brand new file gives a new inode (or if OS reused inode, test external content modification next).
	_, restConflictErr := coord.Restore(ctx, moveRes.OperationID)
	// If OS didn't reuse the inode, Restore must return 409 because identity ownership does not match!
	if restConflictErr == nil {
		t.Fatalf("expected Restore to reject externally replaced sidecar with differing file identity")
	}
	if de, ok := model.AsDomainError(restConflictErr); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 conflict for unowned sidecar, got %v", restConflictErr)
	}
	if _, err := os.Lstat(newLrcPath); err != nil {
		t.Fatalf("externally replaced sidecar should not be deleted: %v", err)
	}

	// 2. Test clean Restore on a fresh move+new sidecar: restores original, deletes owned new sidecar, logs restore operation_files & committed_at.
	src2 := filepath.Join(root.CanonicalPath, "Inbox2", "track.flac")
	dst2 := filepath.Join(root.CanonicalPath, "Album2", "track.flac")
	lrc2 := filepath.Join(root.CanonicalPath, "Album2", "track.lrc")
	_ = os.MkdirAll(filepath.Dir(src2), 0o755)
	_ = os.WriteFile(src2, []byte("ORIG2"), 0o644)
	asset2, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID: root.ID, RelPath: "Inbox2/track.flac", PathKey: files.ComputePathKey(root.CaseMode, "Inbox2/track.flac"),
		Folder: "Inbox2", Name: "track.flac", Status: "active", Editable: true, Size: 5, Title: "Orig2",
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	move2, err := coord.Execute(ctx, files.OperationRequest{
		Kind:             "move",
		AssetID:          asset2.ID,
		ExpectedRevision: asset2.Revision,
		SourcePath:       src2,
		TargetPath:       dst2,
		NewTitle:         "After2",
		Mutator: func(_ context.Context, stage string) ([]files.SidecarOutput, error) {
			if err := os.WriteFile(stage, []byte("NEW2"), 0o644); err != nil {
				return nil, err
			}
			return []files.SidecarOutput{
				{Role: "sidecar_lrc", TargetPath: lrc2, Data: []byte("[00:02]new")},
			}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}

	restRes, err := coord.Restore(ctx, move2.OperationID)
	if err != nil {
		t.Fatalf("clean Restore failed: %v", err)
	}
	if restRes.State != "cleaned" {
		t.Fatalf("expected cleaned, got %s", restRes.State)
	}
	if got, _ := os.ReadFile(src2); string(got) != "ORIG2" {
		t.Fatalf("expected src2 restored, got %q", string(got))
	}
	if _, err := os.Lstat(dst2); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected dst2 removed")
	}
	if _, err := os.Lstat(lrc2); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected owned newly created sidecar removed")
	}
	if n := countFileOpFiles(t, st, restRes.OperationID); n != 2 {
		t.Fatalf("expected 2 operation_files rows recorded for restore op, got %d", n)
	}
	if stVal := queryFileOpState(t, st, restRes.OperationID); stVal != "cleaned" {
		t.Fatalf("expected restore op DB state 'cleaned', got %q", stVal)
	}
}

func TestAcceptanceRestoreSamePathCrashAndWindowsLockedTarget(t *testing.T) {
	ctx := context.Background()
	st := openIntegrityStore(t)
	root := registerIntegrityRoot(t, st, filepath.Join(t.TempDir(), "music"))
	coord := files.NewCoordinator(st, files.NewAssetLockManager(200*time.Millisecond), []files.Root{root})

	// 1. Same-path edit, then simulate a crashed Restore operation in DB after file was already restored back to before_hash.
	p := filepath.Join(root.CanonicalPath, "same.flac")
	_ = os.WriteFile(p, []byte("BEFORE_BYTES"), 0o644)
	asset, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID: root.ID, RelPath: "same.flac", PathKey: files.ComputePathKey(root.CaseMode, "same.flac"),
		Folder: "", Name: "same.flac", Status: "active", Editable: true, Size: 12,
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	editRes, err := coord.Execute(ctx, files.OperationRequest{
		Kind:             "edit",
		AssetID:          asset.ID,
		ExpectedRevision: asset.Revision,
		SourcePath:       p,
		TargetPath:       p,
		Mutator: func(_ context.Context, stage string) ([]files.SidecarOutput, error) {
			return nil, os.WriteFile(stage, []byte("AFTER_BYTES"), 0o644)
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	// Verify Restore on same-path succeeds, and test Windows locked target on move Restore.
	rest1, err := coord.Restore(ctx, editRes.OperationID)
	if err != nil || rest1.State != "cleaned" {
		t.Fatalf("same-path Restore failed: %+v err=%v", rest1, err)
	}
	if got, _ := os.ReadFile(p); string(got) != "BEFORE_BYTES" {
		t.Fatalf("expected BEFORE_BYTES after restore, got %q", string(got))
	}

	// Simulate a crashed same-path 'restore' operation in state='publishing' where target was already overwritten with BEFORE_BYTES.
	beforeHash, _, _ := files.HashFileSHA256(p)
	afterHash := "deadbeef"
	bakDir := filepath.Join(root.CanonicalPath, ".melokeep-history", "sim-restore-crash")
	_ = os.MkdirAll(bakDir, 0o755)
	bakFile := filepath.Join(bakDir, "same.flac")
	_ = os.WriteFile(bakFile, []byte("BEFORE_BYTES"), 0o644)
	_, err = st.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation: model.FileOperation{ID: "sim-restore-crash", Kind: "restore", State: "publishing"},
		AssetID:   asset.ID,
		Files: []store.OperationFileSpec{
			{
				Ordinal:       0,
				Role:          "audio",
				SourceRefJSON: `{"root_id":"` + root.ID + `","rel_path":"same.flac"}`,
				TargetRefJSON: `{"root_id":"` + root.ID + `","rel_path":"same.flac"}`,
				BackupRefJSON: `{"root_id":"` + root.ID + `","rel_path":".melokeep-history/sim-restore-crash/same.flac"}`,
				BeforeHash:    beforeHash,
				AfterHash:     afterHash,
				Phase:         "published",
				IdentityJSON:  `{"preexisting":true}`,
			},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	recRestoreRep, err := coord.RecoverOnStartup(ctx)
	if err != nil {
		t.Fatalf("RecoverOnStartup on crashed restore: %v", err)
	}
	if recRestoreRep.NeedsAttentionOps != 0 || recRestoreRep.PausedOps != 1 {
		t.Fatalf("expected crashed same-path restore to roll back/pause cleanly without false needs_attention, got %+v", recRestoreRep)
	}
	if stVal := queryFileOpState(t, st, "sim-restore-crash"); stVal != "rolled_back" {
		t.Fatalf("expected sim-restore-crash state rolled_back, got %q", stVal)
	}
	// Note: If restore records BeforeHash=AFTER (old target before restore) and AfterHash=BEFORE (restored file)
	// and BackupRef=prev backup (BEFORE_BYTES), RecoverOnStartup won't confuse restored BEFORE_BYTES with tampering!
	// Let's also verify Restore rejects download_publish.
	trID, _ := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{Source: "AudiusMusicClient", Locator: "dl-rest-1", Title: "T", Artist: "A"}, nil)
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "cand-r1", Kind: "track", TargetID: trID}, "")
	dl, _ := st.CreateDownload(ctx, store.CreateDownloadParams{ActorKey: "web_admin:1", PublicID: "cand-r1", TrackID: trID, DedupeKey: "AudiusMusicClient:dl-rest-1:flac"})
	dlPub, _ := st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID: dl.Download.ID, ExpectedRevision: dl.Download.Revision,
		AllowedStates: []model.DownloadStatus{model.DownloadQueued}, NewState: model.DownloadPublishing,
	})
	tmpPart := filepath.Join(t.TempDir(), "part.flac")
	_ = os.WriteFile(tmpPart, []byte("DL_BYTES"), 0o644)
	dlTarget := filepath.Join(root.CanonicalPath, "dl.flac")
	dlRes, err := coord.Execute(ctx, files.OperationRequest{
		Kind: "download_publish", SourcePath: tmpPart, TargetPath: dlTarget,
		DownloadPublish: &files.DownloadPublishSpec{DownloadID: dlPub.ID, ExpectedRevision: dlPub.Revision, TrackID: trID, PublicID: "cand-r1", ActorKey: "web_admin:1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := coord.Restore(ctx, dlRes.OperationID); err == nil {
		t.Fatal("expected Restore on download_publish operation to be rejected")
	}
	if _, err := os.Lstat(dlTarget); err != nil {
		t.Fatalf("rejected Restore must not delete downloaded file: %v", err)
	}

	if runtime.GOOS == "windows" {
		// On Windows, if target file of a move cannot be removed because a handle holds it open,
		// Restore MUST NOT ignore os.Remove error and mark 'cleaned'.
		mSrc := filepath.Join(root.CanonicalPath, "win-src.flac")
		mDst := filepath.Join(root.CanonicalPath, "win-dst.flac")
		_ = os.WriteFile(mSrc, []byte("WIN_ORIG"), 0o644)
		mAsset, _ := st.UpsertAsset(ctx, store.AssetRecord{
			RootID: root.ID, RelPath: "win-src.flac", PathKey: files.ComputePathKey(root.CaseMode, "win-src.flac"),
			Folder: "", Name: "win-src.flac", Status: "active", Editable: true, Size: 8,
		}, 0, true)
		mRes, err := coord.Execute(ctx, files.OperationRequest{
			Kind: "move", AssetID: mAsset.ID, ExpectedRevision: mAsset.Revision,
			SourcePath: mSrc, TargetPath: mDst,
		})
		if err != nil {
			t.Fatal(err)
		}
		// Lock mDst via open file handle without FILE_SHARE_DELETE.
		h, err := os.Open(mDst)
		if err != nil {
			t.Fatal(err)
		}
		_, restErr := coord.Restore(ctx, mRes.OperationID)
		_ = h.Close()
		if restErr == nil {
			t.Fatalf("expected Restore to fail when Windows target file handle is locked")
		}
	}
}

func TestAcceptanceDownloadPublishSidecarCrashOwnershipAndPathEscapes(t *testing.T) {
	ctx := context.Background()
	st := openIntegrityStore(t)
	base := t.TempDir()
	root := registerIntegrityRoot(t, st, filepath.Join(base, "music"))
	coord := files.NewCoordinator(st, files.NewAssetLockManager(200*time.Millisecond), []files.Root{root})

	// Path safety checks: traversal, sibling prefix, internal dir, non-audio.
	outside := filepath.Join(base, "music_evil", "hack.flac")
	_ = os.MkdirAll(filepath.Dir(outside), 0o755)
	_ = os.WriteFile(outside, []byte("x"), 0o644)
	for _, bad := range []string{
		filepath.Join(root.CanonicalPath, "..", "music_evil", "hack.flac"),
		outside,
		filepath.Join(root.CanonicalPath, ".melokeep-history", "op", "x.flac"),
	} {
		if _, err := files.ValidatePath([]files.Root{root}, bad, files.ValidateOptions{MustExist: false, AudioOnly: true}); err == nil {
			t.Fatalf("expected ValidatePath to reject %s", bad)
		}
	}
	// Symlink escape test (best-effort if OS allows creating symlinks without elevated privilege).
	linkPath := filepath.Join(root.CanonicalPath, "escape_link.flac")
	if err := os.Symlink(outside, linkPath); err == nil {
		if _, err := files.ValidatePath([]files.Root{root}, linkPath, files.ValidateOptions{MustExist: true}); err == nil {
			t.Fatalf("expected ValidatePath to reject symlink %s", linkPath)
		}
	}

	// DownloadPublish crash at file_published with sidecar -> RecoverOnStartup must clean BOTH audio and sidecar cleanly.
	trID, _ := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{Source: "AudiusMusicClient", Locator: "dl-sc-1", Title: "T", Artist: "A"}, nil)
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "cand-sc1", Kind: "track", TargetID: trID}, "")
	dl, _ := st.CreateDownload(ctx, store.CreateDownloadParams{ActorKey: "web_admin:1", PublicID: "cand-sc1", TrackID: trID, DedupeKey: "AudiusMusicClient:dl-sc-1:flac"})
	dlPub, _ := st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID: dl.Download.ID, ExpectedRevision: dl.Download.Revision,
		AllowedStates: []model.DownloadStatus{model.DownloadQueued}, NewState: model.DownloadPublishing,
	})
	tmpPart := filepath.Join(t.TempDir(), "part.flac")
	_ = os.WriteFile(tmpPart, []byte("AUDIO_DL"), 0o644)
	dstAudio := filepath.Join(root.CanonicalPath, "Artist", "dl.flac")
	dstLrc := filepath.Join(root.CanonicalPath, "Artist", "dl.lrc")
	sentinel := errors.New("crash@file_published")
	_, err := coord.Execute(ctx, files.OperationRequest{
		Kind: "download_publish", SourcePath: tmpPart, TargetPath: dstAudio,
		DownloadPublish: &files.DownloadPublishSpec{
			DownloadID: dlPub.ID, ExpectedRevision: dlPub.Revision, TrackID: trID, PublicID: "cand-sc1", ActorKey: "web_admin:1",
			SidecarOutputs: []files.SidecarOutput{{Role: "sidecar_lrc", TargetPath: dstLrc, Data: []byte("[00:00]hi")}},
		},
		HookAfterPhase: func(p string) error {
			if p == "file_published" {
				return sentinel
			}
			return nil
		},
	})
	if !errors.Is(err, sentinel) {
		t.Fatalf("expected sentinel, got %v", err)
	}

	// Simulate crash right after LinkNoReplace of sidecar BEFORE RecordOperationFilePhase('published'):
	// Reset operation_files ordinal 1 back to phase='prepared' so recovery must rely on Stage identity!
	_, err = st.Writer().Exec(`UPDATE operation_files SET phase = 'prepared' WHERE ordinal = 1`)
	if err != nil {
		t.Fatal(err)
	}

	rep, err := coord.RecoverOnStartup(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if rep.NeedsAttentionOps != 0 || rep.PausedOps != 1 || rep.PausedDownloads != 1 {
		t.Fatalf("expected clean rollback of audio+sidecar using stage identity, got %+v", rep)
	}
	if _, err := os.Lstat(dstAudio); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected dstAudio removed after recovery")
	}
	if _, err := os.Lstat(dstLrc); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected dstLrc removed after recovery using stage identity")
	}

	// Concurrent AcquireWriteMulti ordering must not deadlock.
	locks := files.NewAssetLockManager(500 * time.Millisecond)
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			ids := []string{"b", "a"}
			if i%2 == 0 {
				ids = []string{"a", "b"}
			}
			rel, lErr := locks.AcquireWriteMulti(ctx, ids)
			if lErr != nil {
				t.Errorf("AcquireWriteMulti %d: %v", i, lErr)
				return
			}
			time.Sleep(10 * time.Millisecond)
			rel()
		}(i)
	}
	wg.Wait()
}
