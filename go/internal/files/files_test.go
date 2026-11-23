package files

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func openTestStore(t *testing.T) *store.Store {
	t.Helper()
	dbPath := filepath.Join(t.TempDir(), "go", "melokeep.db")
	st, err := store.Open(context.Background(), store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func registerTestRoot(t *testing.T, st *store.Store, dir string) Root {
	t.Helper()
	root, rec, err := RegisterRoot(dir, "")
	if err != nil {
		t.Fatalf("register root: %v", err)
	}
	if err := st.UpsertRoot(context.Background(), rec); err != nil {
		t.Fatalf("upsert root: %v", err)
	}
	return root
}

func TestPathValidationPrefixAndInternalProtection(t *testing.T) {
	base := t.TempDir()
	musicDir := filepath.Join(base, "music")
	siblingDir := filepath.Join(base, "music_evil")
	if err := os.MkdirAll(musicDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(siblingDir, 0o755); err != nil {
		t.Fatal(err)
	}
	evilFile := filepath.Join(siblingDir, "hack.flac")
	if err := os.WriteFile(evilFile, []byte("evil"), 0o644); err != nil {
		t.Fatal(err)
	}

	canon, err := CanonicalizeRoot(musicDir)
	if err != nil {
		t.Fatal(err)
	}
	root := Root{ID: "r1", CanonicalPath: canon, CaseMode: "sensitive"}

	// Sibling directory sharing string prefix must be rejected.
	if _, err := ValidatePath([]Root{root}, evilFile, ValidateOptions{MustExist: true}); err == nil {
		t.Fatalf("expected sibling prefix directory escape to be rejected")
	}

	// Internal directories must be rejected when AllowInternal=false.
	internal := filepath.Join(canon, ".melokeep-history", "op1", "song.flac")
	if err := os.MkdirAll(filepath.Dir(internal), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(internal, []byte("bak"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := ValidatePath([]Root{root}, internal, ValidateOptions{MustExist: true}); err == nil {
		t.Fatalf("expected internal history path to be rejected")
	}

	// Valid audio path inside root succeeds.
	good := filepath.Join(canon, "Artist", "song.flac")
	if err := os.MkdirAll(filepath.Dir(good), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(good, []byte("flacdata"), 0o644); err != nil {
		t.Fatal(err)
	}
	vp, err := ValidatePath([]Root{root}, good, ValidateOptions{MustExist: true, AudioOnly: true})
	if err != nil {
		t.Fatalf("valid path failed: %v", err)
	}
	if vp.RelPath != "Artist/song.flac" || vp.Folder != "Artist" {
		t.Fatalf("unexpected ValidatedPath: %+v", vp)
	}
}

func TestLinkNoReplaceDoesNotOverwriteExisting(t *testing.T) {
	dir := t.TempDir()
	stage := filepath.Join(dir, "stage.flac")
	target := filepath.Join(dir, "target.flac")
	if err := os.WriteFile(stage, []byte("new"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(target, []byte("existing"), 0o644); err != nil {
		t.Fatal(err)
	}
	err := LinkNoReplace(stage, target)
	de, ok := model.AsDomainError(err)
	if !ok || de.Code != model.ErrConflict {
		t.Fatalf("expected ErrConflict on existing target, got %v", err)
	}
	got, _ := os.ReadFile(target)
	if string(got) != "existing" {
		t.Fatalf("existing target was overwritten")
	}
}

func TestAssetLockManagerReadWriteSemantics(t *testing.T) {
	locks := NewAssetLockManager(120 * time.Millisecond)

	rel1, err := locks.AcquireRead("a1")
	if err != nil {
		t.Fatal(err)
	}
	rel2, err := locks.AcquireRead("a1")
	if err != nil {
		t.Fatal(err)
	}

	// While readers hold the asset, AcquireWrite times out with retryable ErrConflict without killing readers.
	wErr := func() error {
		_, err := locks.AcquireWrite(context.Background(), "a1")
		return err
	}()
	de, ok := model.AsDomainError(wErr)
	if !ok || de.Code != model.ErrConflict || !de.Retryable {
		t.Fatalf("expected retryable conflict when playback active, got %v", wErr)
	}

	rel1()
	rel2()

	wRel, err := locks.AcquireWrite(context.Background(), "a1")
	if err != nil {
		t.Fatalf("expected write lock after readers released: %v", err)
	}
	if !locks.IsWriteActive("a1") {
		t.Fatalf("expected IsWriteActive true")
	}
	if _, rErr := locks.AcquireRead("a1"); rErr == nil {
		t.Fatalf("expected AcquireRead to fail while writer active")
	}
	wRel()
	if locks.IsWriteActive("a1") {
		t.Fatalf("expected IsWriteActive false after release")
	}
}

func TestCoordinatorMoveEditRestoreAndCrashRecoveryMatrix(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	rootDir := filepath.Join(t.TempDir(), "library")
	if err := os.MkdirAll(rootDir, 0o755); err != nil {
		t.Fatal(err)
	}
	root := registerTestRoot(t, st, rootDir)
	coord := NewCoordinator(st, NewAssetLockManager(200*time.Millisecond), []Root{root})

	srcPath := filepath.Join(root.CanonicalPath, "Inbox", "track.flac")
	lrcPath := filepath.Join(root.CanonicalPath, "Inbox", "track.lrc")
	if err := os.MkdirAll(filepath.Dir(srcPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(srcPath, []byte("ORIGINAL_AUDIO_BYTES"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(lrcPath, []byte("[00:01.00]orig"), 0o644); err != nil {
		t.Fatal(err)
	}

	asset, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID:   root.ID,
		RelPath:  "Inbox/track.flac",
		PathKey:  ComputePathKey(root.CaseMode, "Inbox/track.flac"),
		Folder:   "Inbox",
		Name:     "track.flac",
		Status:   "active",
		Editable: true,
		Size:     20,
		Title:    "OldTitle",
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}

	// 1. Fault-injection at each phase before DB commit: planned, prepared, file_published.
	for _, phase := range []string{"planned", "prepared", "file_published"} {
		sentinel := errors.New("simulated crash at " + phase)
		dstFail := filepath.Join(root.CanonicalPath, "Artist", "track-"+phase+".flac")
		_, err := coord.Execute(ctx, OperationRequest{
			Kind:             "move",
			AssetID:          asset.ID,
			ExpectedRevision: asset.Revision,
			SourcePath:       srcPath,
			TargetPath:       dstFail,
			Mutator: func(_ context.Context, stage string) ([]SidecarOutput, error) {
				if err := os.WriteFile(stage, []byte("MUTATED_AUDIO_BYTES"), 0o644); err != nil {
					return nil, err
				}
				return nil, nil
			},
			HookAfterPhase: func(p string) error {
				if p == phase {
					return sentinel
				}
				return nil
			},
		})
		if !errors.Is(err, sentinel) {
			t.Fatalf("phase %s: expected sentinel crash, got %v", phase, err)
		}

		rep, rErr := coord.RecoverOnStartup(ctx)
		if rErr != nil {
			t.Fatalf("phase %s recover: %v", phase, rErr)
		}
		if rep.NeedsAttentionOps != 0 || rep.PausedOps == 0 {
			t.Fatalf("phase %s unexpected recovery report: %+v", phase, rep)
		}
		raw, _ := os.ReadFile(srcPath)
		if string(raw) != "ORIGINAL_AUDIO_BYTES" {
			t.Fatalf("phase %s corrupted source bytes: %q", phase, string(raw))
		}
		if _, stErr := os.Lstat(dstFail); !errors.Is(stErr, os.ErrNotExist) {
			t.Fatalf("phase %s leaked target file %s", phase, dstFail)
		}
	}

	// 2. Normal move + tag mutation + sidecar + restore.
	dstOK := filepath.Join(root.CanonicalPath, "Indie", "track-new.flac")
	res, err := coord.Execute(ctx, OperationRequest{
		Kind:             "move",
		AssetID:          asset.ID,
		ExpectedRevision: asset.Revision,
		SourcePath:       srcPath,
		TargetPath:       dstOK,
		NewTitle:         "NewTitle",
		BackupDays:       7,
		Mutator: func(_ context.Context, stage string) ([]SidecarOutput, error) {
			if err := os.WriteFile(stage, []byte("UPDATED_AUDIO_BYTES"), 0o644); err != nil {
				return nil, err
			}
			return nil, nil
		},
	})
	if err != nil {
		t.Fatalf("normal move failed: %v", err)
	}
	if _, err := os.Lstat(srcPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected old source removed after move")
	}
	if got, _ := os.ReadFile(dstOK); string(got) != "UPDATED_AUDIO_BYTES" {
		t.Fatalf("unexpected moved audio: %q", string(got))
	}
	if gotLrc, _ := os.ReadFile(filepath.Join(root.CanonicalPath, "Indie", "track-new.lrc")); string(gotLrc) != "[00:01.00]orig" {
		t.Fatalf("expected sidecar moved cleanly, got %q", string(gotLrc))
	}

	// Restore reverts back to Inbox/track.flac with ORIGINAL_AUDIO_BYTES.
	restRes, err := coord.Restore(ctx, res.OperationID)
	if err != nil {
		t.Fatalf("restore failed: %v", err)
	}
	if restRes.State != "cleaned" {
		t.Fatalf("expected restore state cleaned, got %s", restRes.State)
	}
	if got, _ := os.ReadFile(srcPath); string(got) != "ORIGINAL_AUDIO_BYTES" {
		t.Fatalf("restore did not recover original bytes: %q", string(got))
	}
	if _, err := os.Lstat(dstOK); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("restore did not clean moved target")
	}
}

func TestRecoveryProtectsExternallyModifiedTarget(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	rootDir := filepath.Join(t.TempDir(), "library")
	_ = os.MkdirAll(rootDir, 0o755)
	root := registerTestRoot(t, st, rootDir)
	coord := NewCoordinator(st, nil, []Root{root})

	srcPath := filepath.Join(root.CanonicalPath, "a.flac")
	dstPath := filepath.Join(root.CanonicalPath, "b.flac")
	_ = os.WriteFile(srcPath, []byte("ORIG"), 0o644)

	sentinel := errors.New("crash after file_published")
	_, _ = coord.Execute(ctx, OperationRequest{
		Kind:       "move",
		SourcePath: srcPath,
		TargetPath: dstPath,
		Mutator: func(_ context.Context, stage string) ([]SidecarOutput, error) {
			return nil, os.WriteFile(stage, []byte("NEW"), 0o644)
		},
		HookAfterPhase: func(p string) error {
			if p == "file_published" {
				return sentinel
			}
			return nil
		},
	})

	// External process edits dstPath after crash!
	_ = os.WriteFile(dstPath, []byte("EXTERNAL_EDIT"), 0o644)

	rep, err := coord.RecoverOnStartup(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if rep.NeedsAttentionOps != 1 {
		t.Fatalf("expected 1 needs_attention op on external tamper, got %+v", rep)
	}
	if got, _ := os.ReadFile(dstPath); string(got) != "EXTERNAL_EDIT" {
		t.Fatalf("recovery overwrote externally modified file!")
	}
}

func TestRecoveryCommittedRollsForwardCleanup(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	rootDir := filepath.Join(t.TempDir(), "library")
	_ = os.MkdirAll(rootDir, 0o755)
	root := registerTestRoot(t, st, rootDir)
	coord := NewCoordinator(st, nil, []Root{root})

	srcPath := filepath.Join(root.CanonicalPath, "old.flac")
	dstPath := filepath.Join(root.CanonicalPath, "new.flac")
	_ = os.WriteFile(srcPath, []byte("DATA"), 0o644)

	asset, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID: root.ID, RelPath: "old.flac", PathKey: ComputePathKey(root.CaseMode, "old.flac"),
		Folder: "", Name: "old.flac", Status: "active", Editable: true, Size: 4,
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}

	sentinel := errors.New("crash right after DB commit")
	_, _ = coord.Execute(ctx, OperationRequest{
		Kind:             "move",
		AssetID:          asset.ID,
		ExpectedRevision: asset.Revision,
		SourcePath:       srcPath,
		TargetPath:       dstPath,
		HookAfterPhase: func(p string) error {
			if p == "committed" {
				return sentinel
			}
			return nil
		},
	})

	rep, err := coord.RecoverOnStartup(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if rep.CleanedCommittedOps != 1 {
		t.Fatalf("expected committed op to roll forward cleanup, got %+v", rep)
	}
	if _, err := os.Lstat(srcPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected old source cleaned up after committed recovery")
	}
	gotAsset, ok, _ := st.GetAsset(ctx, asset.ID)
	if !ok || gotAsset.RelPath != "new.flac" {
		t.Fatalf("committed DB mapping must not revert, got %+v", gotAsset)
	}
}

func TestWindowsOpenHandlePauseProtection(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Windows sharing violation test only runs on Windows")
	}
	ctx := context.Background()
	st := openTestStore(t)
	rootDir := filepath.Join(t.TempDir(), "library")
	_ = os.MkdirAll(rootDir, 0o755)
	root := registerTestRoot(t, st, rootDir)
	coord := NewCoordinator(st, nil, []Root{root})
	coord.maxRetries = 2

	target := filepath.Join(root.CanonicalPath, "playing.flac")
	_ = os.WriteFile(target, []byte("PLAYING_ORIGINAL"), 0o644)

	// Open without FILE_SHARE_DELETE to simulate external media player lock on Windows.
	h, err := os.Open(target)
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()

	_, execErr := coord.Execute(ctx, OperationRequest{
		Kind:       "edit",
		SourcePath: target,
		TargetPath: target,
		Mutator: func(_ context.Context, stage string) ([]SidecarOutput, error) {
			return nil, os.WriteFile(stage, []byte("NEW_TAGS"), 0o644)
		},
	})
	de, ok := model.AsDomainError(execErr)
	if !ok || de.Code != model.ErrConflict || !de.Retryable {
		t.Fatalf("expected retryable ErrConflict when Windows file handle busy, got %v", execErr)
	}
	if got, _ := os.ReadFile(target); string(got) != "PLAYING_ORIGINAL" {
		t.Fatalf("original file was altered despite busy lock")
	}
}

func TestDownloadPublishAndBackupExpiry(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	rootDir := filepath.Join(t.TempDir(), "library")
	_ = os.MkdirAll(rootDir, 0o755)
	root := registerTestRoot(t, st, rootDir)
	coord := NewCoordinator(st, nil, []Root{root})

	trID, _ := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source: "AudiusMusicClient", Locator: "audius-1", Title: "晨风序曲", Artist: "独立乐手",
	}, nil)
	_ = st.PutPublicRef(ctx, model.PublicRef{PublicID: "cand-1", Kind: "track", TargetID: trID}, "")
	dlRes, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		ActorKey: "web_admin:1", PublicID: "cand-1", TrackID: trID,
		QualityID: "flac", QualityLabel: "FLAC", DedupeKey: "AudiusMusicClient:audius-1:flac",
	})
	if err != nil {
		t.Fatal(err)
	}
	dlPub, err := st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID:               dlRes.Download.ID,
		ExpectedRevision: dlRes.Download.Revision,
		AllowedStates:    []model.DownloadStatus{model.DownloadQueued},
		NewState:         model.DownloadPublishing,
	})
	if err != nil {
		t.Fatal(err)
	}

	tmpPart := filepath.Join(t.TempDir(), "dl.part")
	_ = os.WriteFile(tmpPart, []byte("FLAC_BYTES"), 0o644)
	dstPath := filepath.Join(root.CanonicalPath, "AudiusMusicClient", "晨风序曲 - 独立乐手.flac")
	lrcDst := filepath.Join(root.CanonicalPath, "AudiusMusicClient", "晨风序曲 - 独立乐手.lrc")

	res, err := coord.Execute(ctx, OperationRequest{
		Kind:       "download_publish",
		SourcePath: tmpPart,
		TargetPath: dstPath,
		NewTitle:   "晨风序曲",
		NewArtist:  "独立乐手",
		Editable:   true,
		DownloadPublish: &DownloadPublishSpec{
			DownloadID:       dlPub.ID,
			ExpectedRevision: dlPub.Revision,
			TrackID:          trID,
			PublicID:         "cand-1",
			Source:           "AudiusMusicClient",
			SourceID:         "audius-1",
			ActorKey:         "web_admin:1",
			SidecarOutputs: []SidecarOutput{
				{Role: "sidecar_lrc", TargetPath: lrcDst, Data: []byte("[00:00]晨风序曲")},
			},
			AutoOrganize: &store.AutoOrganizeJobSpec{
				Action:   "both",
				OwnerKey: "web_admin:1",
				Items:    []store.LibraryItemInput{{Name: "晨风序曲 - 独立乐手.flac", OriginalPath: dstPath, TargetPath: dstPath}},
			},
		},
	})
	if err != nil {
		t.Fatalf("download_publish failed: %v", err)
	}
	if res.State != "cleaned" || res.Asset == nil {
		t.Fatalf("unexpected download_publish result: %+v", res)
	}
	dlAfter, ok, _ := st.GetDownload(ctx, dlRes.Download.ID)
	if !ok || dlAfter.Status != model.DownloadComplete {
		t.Fatalf("expected download complete, got %+v", dlAfter)
	}
	if _, err := os.Stat(lrcDst); err != nil {
		t.Fatalf("expected sidecar lrc published: %v", err)
	}

	// Create expired backup op and verify ExpireBackups cleans it up.
	expiredOp, err := st.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation: model.FileOperation{Kind: "edit", State: "cleaned"},
		ExpiresAt: func() *int64 { v := time.Now().UTC().Add(-time.Hour).UnixMilli(); return &v }(),
	})
	if err != nil {
		t.Fatal(err)
	}
	expDir := filepath.Join(root.CanonicalPath, ".melokeep-history", expiredOp.ID)
	_ = os.MkdirAll(expDir, 0o755)
	_ = os.WriteFile(filepath.Join(expDir, "old.flac"), []byte("bak"), 0o644)
	n, err := coord.ExpireBackups(ctx, time.Now())
	if err != nil || n != 1 {
		t.Fatalf("expected 1 expired backup cleaned, got %d (%v)", n, err)
	}
	if _, err := os.Lstat(expDir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected expired backup dir removed")
	}
}

func TestCoordinatorHistoryDirDisableBackupAndPurge(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	rootDir := filepath.Join(tmp, "music")
	historyDir := filepath.Join(tmp, "data", "go", "history")
	if err := os.MkdirAll(rootDir, 0o755); err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(ctx, store.Options{Path: filepath.Join(tmp, "melokeep.db")})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	root, rec, err := RegisterRoot(rootDir, "r-hist")
	if err != nil {
		t.Fatal(err)
	}
	_ = st.UpsertRoot(ctx, rec)
	coord := NewCoordinator(st, NewAssetLockManager(2*time.Second), []Root{root})
	coord.SetHistoryDir(historyDir)

	// 1. With backups enabled + SetHistoryDir, backups stay outside musicRoot and Restore still works.
	srcPath := filepath.Join(rootDir, "raw.flac")
	_ = os.WriteFile(srcPath, []byte("original-flac-bytes"), 0o644)
	dstPath := filepath.Join(rootDir, "library", "organized.flac")
	_ = os.MkdirAll(filepath.Dir(dstPath), 0o755)
	res, err := coord.Execute(ctx, OperationRequest{
		Kind:       "move",
		SourcePath: srcPath,
		TargetPath: dstPath,
		BackupDays: 7,
		Mutator: func(_ context.Context, stagePath string) ([]SidecarOutput, error) {
			return nil, os.WriteFile(stagePath, []byte("tagged-flac-bytes"), 0o644)
		},
	})
	if err != nil {
		t.Fatalf("Execute with historyDir: %v", err)
	}
	if _, err := os.Lstat(filepath.Join(rootDir, ".melokeep-history")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected no .melokeep-history inside musicRoot when historyDir is configured")
	}
	if _, err := os.Stat(filepath.Join(historyDir, res.OperationID, "raw.flac")); err != nil {
		t.Fatalf("expected backup inside data historyDir: %v", err)
	}
	if _, err := coord.Restore(ctx, res.OperationID); err != nil {
		t.Fatalf("Restore from historyDir failed: %v", err)
	}
	if got, _ := os.ReadFile(srcPath); string(got) != "original-flac-bytes" {
		t.Fatalf("unexpected restored bytes: %q", string(got))
	}

	// 2. DisableBackup leaves zero history artifacts for both move and in-place edit.
	entriesBefore, _ := os.ReadDir(historyDir)
	dstNoBak := filepath.Join(rootDir, "library", "no-backup.flac")
	if _, err := coord.Execute(ctx, OperationRequest{
		Kind:          "move",
		SourcePath:    srcPath,
		TargetPath:    dstNoBak,
		DisableBackup: true,
	}); err != nil {
		t.Fatalf("Execute move DisableBackup: %v", err)
	}
	if _, err := coord.Execute(ctx, OperationRequest{
		Kind:          "edit",
		SourcePath:    dstNoBak,
		TargetPath:    dstNoBak,
		DisableBackup: true,
		Mutator: func(_ context.Context, stagePath string) ([]SidecarOutput, error) {
			return nil, os.WriteFile(stagePath, []byte("edited-in-place"), 0o644)
		},
	}); err != nil {
		t.Fatalf("Execute edit DisableBackup: %v", err)
	}
	entriesAfter, _ := os.ReadDir(historyDir)
	if len(entriesAfter) != len(entriesBefore) {
		t.Fatalf("expected no extra backup retained when DisableBackup=true, before=%d after=%d", len(entriesBefore), len(entriesAfter))
	}

	// 3. PurgeAllBackups clears historyDir and empty legacy .processing/.partial/.melokeep-history dirs.
	_ = os.MkdirAll(filepath.Join(rootDir, ".processing", "empty-sub"), 0o755)
	_ = os.MkdirAll(filepath.Join(rootDir, ".partial"), 0o755)
	removed, freed, err := coord.PurgeAllBackups(ctx)
	if err != nil || removed < 1 || freed == 0 {
		t.Fatalf("PurgeAllBackups unexpected result: removed=%d freed=%d err=%v", removed, freed, err)
	}
	if rem, _ := os.ReadDir(historyDir); len(rem) != 0 {
		t.Fatalf("expected empty historyDir after purge, got %d entries", len(rem))
	}
	for _, legacyDir := range []string{".processing", ".partial", ".melokeep-history"} {
		if _, err := os.Lstat(filepath.Join(rootDir, legacyDir)); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("expected empty legacy dir %s removed", legacyDir)
		}
	}
}

func TestResolveManagedDir(t *testing.T) {
	base := t.TempDir()
	rootDir := filepath.Join(base, "music")
	nestedRootDir := filepath.Join(rootDir, "vip")
	outsideDir := filepath.Join(base, "music_outside")
	if err := os.MkdirAll(nestedRootDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(outsideDir, 0o755); err != nil {
		t.Fatal(err)
	}
	canonRoot, err := CanonicalizeRoot(rootDir)
	if err != nil {
		t.Fatal(err)
	}
	canonNested, err := CanonicalizeRoot(nestedRootDir)
	if err != nil {
		t.Fatal(err)
	}
	roots := []Root{
		{ID: "r-main", CanonicalPath: canonRoot, CaseMode: "sensitive"},
		{ID: "r-vip", CanonicalPath: canonNested, CaseMode: "sensitive"},
	}

	// 1. Empty roots -> ErrUnavailable.
	if _, _, err := ResolveManagedDir(nil, "incoming", ""); err == nil {
		t.Fatal("expected error for empty roots")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrUnavailable {
		t.Fatalf("expected ErrUnavailable, got %v", err)
	}

	// 2. Empty / blank raw -> ErrInvalidInput.
	if _, _, err := ResolveManagedDir(roots, "   ", canonRoot); err == nil {
		t.Fatal("expected error for blank path")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrInvalidInput {
		t.Fatalf("expected ErrInvalidInput, got %v", err)
	}

	// 3. Root directory itself (absolute and ".").
	for _, raw := range []string{canonRoot, "."} {
		matched, resolved, err := ResolveManagedDir(roots, raw, canonRoot)
		if err != nil {
			t.Fatalf("ResolveManagedDir(%q) failed: %v", raw, err)
		}
		if matched.ID != "r-main" || resolved != canonRoot {
			t.Fatalf("ResolveManagedDir(%q) = (%s, %q), want (r-main, %q)", raw, matched.ID, resolved, canonRoot)
		}
	}

	// 4. Relative subdirectory & nested relative path (empty defaultRoot falls back to roots[0]).
	for _, tc := range []struct {
		raw      string
		wantRoot string
		wantDir  string
	}{
		{"待刮削", "r-main", filepath.Join(canonRoot, "待刮削")},
		{"incoming/new", "r-main", filepath.Join(canonRoot, "incoming", "new")},
		{filepath.Join(canonRoot, "待刮削", "batch"), "r-main", filepath.Join(canonRoot, "待刮削", "batch")},
		{filepath.Join(canonNested, "lossless"), "r-vip", filepath.Join(canonNested, "lossless")},
		{"vip/lossless", "r-vip", filepath.Join(canonNested, "lossless")},
	} {
		matched, resolved, err := ResolveManagedDir(roots, tc.raw, "")
		if err != nil {
			t.Fatalf("ResolveManagedDir(%q) unexpected error: %v", tc.raw, err)
		}
		if matched.ID != tc.wantRoot || resolved != tc.wantDir {
			t.Fatalf("ResolveManagedDir(%q) = (%s, %q), want (%s, %q)", tc.raw, matched.ID, resolved, tc.wantRoot, tc.wantDir)
		}
	}

	// 5. Existing valid directory succeeds.
	existingSub := filepath.Join(canonRoot, "existing_sub")
	if err := os.MkdirAll(existingSub, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, resolved, err := ResolveManagedDir(roots, "existing_sub", canonRoot); err != nil || resolved != existingSub {
		t.Fatalf("expected existing subdir %q, got %q err=%v", existingSub, resolved, err)
	}

	// 6. Traversal and outside-root paths must fail with ErrInvalidInput.
	for _, bad := range []string{
		"../music_outside",
		"sub/../../music_outside",
		filepath.Join(canonRoot, "..", "music_outside"),
		outsideDir,
	} {
		if _, _, err := ResolveManagedDir(roots, bad, canonRoot); err == nil {
			t.Fatalf("expected traversal/outside path %q to be rejected", bad)
		} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrInvalidInput {
			t.Fatalf("expected ErrInvalidInput for %q, got %v", bad, err)
		}
	}

	// 7. Internal .melokeep-* directories must fail with ErrForbidden.
	for _, internal := range []string{
		".melokeep-history",
		".melokeep-stage",
		"sub/.melokeep-tmp",
	} {
		if _, _, err := ResolveManagedDir(roots, internal, canonRoot); err == nil {
			t.Fatalf("expected internal path %q to be rejected", internal)
		} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrForbidden {
			t.Fatalf("expected ErrForbidden for %q, got %v", internal, err)
		}
	}

	// 8. Existing regular file at target path must fail with ErrInvalidInput.
	regularFile := filepath.Join(canonRoot, "not_a_dir.txt")
	if err := os.WriteFile(regularFile, []byte("file"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := ResolveManagedDir(roots, "not_a_dir.txt", canonRoot); err == nil {
		t.Fatal("expected regular file target to be rejected")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrInvalidInput {
		t.Fatalf("expected ErrInvalidInput for regular file, got %v", err)
	}
}
