package store_test

import (
	"context"
	"fmt"
	"net/http"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
	"github.com/agonie0v0/melokeep/internal/store"
)

func openTestStore(t *testing.T) *store.Store {
	t.Helper()
	ctx := context.Background()
	dbPath := store.DefaultDBPath(t.TempDir())
	st, err := store.Open(ctx, store.Options{Path: dbPath, ReadMaxOpen: 3})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func TestStorePragmasAndReadOnlyPool(t *testing.T) {
	st := openTestStore(t)
	if _, err := st.Reader().Exec(`INSERT INTO settings(namespace,body_json,revision,updated_at) VALUES('x','{}',1,1)`); err == nil {
		t.Fatal("expected reader pool with query_only=ON to reject writes")
	}
	if _, err := st.Writer().Exec(`INSERT INTO assets(id,root_id,rel_path,path_key,status,size,mtime_ns,updated_at) VALUES('a','missing-root','x.mp3','x.mp3','active',1,1,1)`); err == nil {
		t.Fatal("expected foreign_keys=ON to reject missing root_id")
	}
}

func TestIdempotencyWithOperationsRegistry(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	reg := operations.NewRegistry(store.NewIdempotencyStore(st, time.Hour))
	_ = reg.RegisterSpec(operations.Spec{
		ID:          "test.write",
		Method:      "POST",
		Path:        "/api/test",
		Description: "test write",
		Write:       true,
		Effect:      operations.EffectLocalWrite,
		Idempotency: operations.IdempotencyRequired,
	})
	calls := 0
	_ = reg.RegisterHandler("test.write", func(_ context.Context, req operations.Request) (any, error) {
		calls++
		return map[string]any{"calls": calls, "val": req.Payload["val"], "token": "super-secret"}, nil
	})

	actor := auth.WebAdminActor("admin")
	res1, err := reg.Dispatch(ctx, operations.Request{
		Operation:      "test.write",
		Actor:          actor,
		IdempotencyKey: "k1",
		Payload:        map[string]any{"val": "a"},
	})
	if err != nil {
		t.Fatalf("first dispatch: %v", err)
	}
	if m := res1.(map[string]any); m["token"] == "super-secret" {
		t.Fatal("expected secret token to be redacted")
	}

	// Same key + same payload returns cached result without calling handler again.
	res2, err := reg.Dispatch(ctx, operations.Request{
		Operation:      "test.write",
		Actor:          actor,
		IdempotencyKey: "k1",
		Payload:        map[string]any{"val": "a"},
	})
	if err != nil {
		t.Fatalf("replay dispatch: %v", err)
	}
	if calls != 1 || res2.(map[string]any)["calls"].(float64) != 1 {
		t.Fatalf("expected cached replay, calls=%d res2=%v", calls, res2)
	}

	// Same key + different payload returns 409.
	_, err = reg.Dispatch(ctx, operations.Request{
		Operation:      "test.write",
		Actor:          actor,
		IdempotencyKey: "k1",
		Payload:        map[string]any{"val": "b"},
	})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 conflict, got: %v", err)
	}

	// force_new without intent_nonce returns 400.
	_, err = reg.Dispatch(ctx, operations.Request{
		Operation:      "test.write",
		Actor:          actor,
		IdempotencyKey: "k2",
		ForceNew:       true,
		Payload:        map[string]any{"val": "a"},
	})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 for force_new missing intent_nonce, got: %v", err)
	}
}

func TestDownloadDedupeForceNewCASAndCommitOutputs(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	if err := st.UpsertRoot(ctx, store.RootRecord{
		ID:            "root1",
		CanonicalPath: filepath.Join(t.TempDir(), "music"),
		PathCaseMode:  "insensitive",
		Enabled:       true,
		Healthy:       true,
	}); err != nil {
		t.Fatalf("UpsertRoot: %v", err)
	}

	// Concurrent CreateDownload with the same default dedupe_key should only create one active job.
	var wg sync.WaitGroup
	results := make([]store.CreateDownloadResult, 8)
	errs := make([]error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			results[idx], errs[idx] = st.CreateDownload(ctx, store.CreateDownloadParams{
				PublicID:       "pub-1",
				QualityID:      "flac",
				QualityLabel:   "FLAC",
				DownloadSource: "audius",
				ActorKey:       fmt.Sprintf("channel:tg:u%d", idx%2),
				DedupeKey:      "audius:song1:flac:rev1",
				Detail:         "queued",
			})
		}(i)
	}
	wg.Wait()

	createdCount := 0
	var firstID string
	for i := 0; i < 8; i++ {
		if errs[i] != nil {
			t.Fatalf("CreateDownload %d err: %v", i, errs[i])
		}
		if results[i].Created {
			createdCount++
		}
		if firstID == "" {
			firstID = results[i].Download.ID
		} else if results[i].Download.ID != firstID {
			t.Fatalf("expected deduped download ID %s, got %s", firstID, results[i].Download.ID)
		}
	}
	if createdCount != 1 {
		t.Fatalf("expected exactly 1 created download, got %d", createdCount)
	}

	// force_new without intent_nonce fails 400.
	_, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		PublicID:  "pub-1",
		ActorKey:  "web_admin:admin",
		DedupeKey: "audius:song1:flac:rev1",
		ForceNew:  true,
	})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 for missing intent_nonce, got: %v", err)
	}

	// force_new with intent_nonce allows a separate parallel download.
	forced, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		PublicID:    "pub-1",
		ActorKey:    "web_admin:admin",
		DedupeKey:   "audius:song1:flac:rev1",
		ForceNew:    true,
		IntentNonce: "nonce-1",
	})
	if err != nil || !forced.Created || forced.Download.ID == firstID {
		t.Fatalf("expected separate forced download, got: %+v err=%v", forced, err)
	}

	// CAS update and stale revision rejection.
	attempt1 := 1
	dl2, err := st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID:               firstID,
		ExpectedRevision: 1,
		AllowedStates:    []model.DownloadStatus{model.DownloadQueued},
		NewState:         model.DownloadPublishing,
		Attempt:          &attempt1,
	})
	if err != nil || dl2.Revision != 2 || dl2.Status != model.DownloadPublishing {
		t.Fatalf("CAS transition failed: %+v err=%v", dl2, err)
	}
	_, err = st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID:               firstID,
		ExpectedRevision: 1, // stale!
		AllowedStates:    []model.DownloadStatus{model.DownloadQueued, model.DownloadPublishing},
		NewState:         model.DownloadComplete,
	})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 for stale CAS revision, got: %v", err)
	}

	// CommitPublishedOutputs atomically creates asset, download_outputs, complete status, index rev bump, and auto-organize job.
	total := int64(1024000)
	completed, err := st.CommitPublishedOutputs(ctx, store.CommitPublishedOutputsParams{
		DownloadID:       firstID,
		ExpectedRevision: dl2.Revision,
		FileToken:        "asset:a-1",
		Detail:           "下载完成",
		BytesDone:        total,
		BytesTotal:       &total,
		Outputs: []store.PublishedOutputSpec{
			{
				Asset: store.AssetRecord{
					ID:       "a-1",
					RootID:   "root1",
					RelPath:  "Artist/Song.flac",
					Editable: true,
					Size:     total,
					Title:    "Song",
					Artist:   "Artist",
					Album:    "Album",
				},
				AliasToken: "legacy-token-1",
			},
		},
		AutoOrganize: &store.AutoOrganizeJobSpec{
			Action: "both",
			Detail: "待整理",
			Items: []store.LibraryItemInput{
				{PathToken: "asset:a-1", Name: "Song.flac", OriginalPath: "Artist/Song.flac"},
			},
		},
	})
	if err != nil || completed.Status != model.DownloadComplete {
		t.Fatalf("CommitPublishedOutputs failed: %+v err=%v", completed, err)
	}
	jobs, err := st.ListLibraryJobs(ctx, 10)
	if err != nil || len(jobs) != 1 {
		t.Fatalf("expected 1 auto-organize job, got len=%d err=%v", len(jobs), err)
	}
	idx, err := st.GetLibraryIndexState(ctx)
	if err != nil || idx.Revision < 2 {
		t.Fatalf("expected incremented index revision, got %+v err=%v", idx, err)
	}
}

func TestScanGenerationAndKeysetPagination(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	if err := st.UpsertRoot(ctx, store.RootRecord{
		ID:            "r1",
		CanonicalPath: filepath.Join(t.TempDir(), "music"),
		PathCaseMode:  "sensitive",
		Enabled:       true,
		Healthy:       true,
	}); err != nil {
		t.Fatalf("UpsertRoot: %v", err)
	}

	for i := 0; i < 25; i++ {
		gen := int64(1)
		if i < 5 {
			gen = 1 // will become missing on gen 2
		} else {
			gen = 2
		}
		_, err := st.UpsertAsset(ctx, store.AssetRecord{
			ID:                fmt.Sprintf("asset-%03d", i),
			RootID:            "r1",
			RelPath:           fmt.Sprintf("Folder%d/Track-%03d.flac", i%3, i),
			Editable:          true,
			Size:              int64(1000 + i),
			Title:             fmt.Sprintf("Track %03d", i),
			Artist:            fmt.Sprintf("Artist %d", i%3),
			Album:             "Test Album",
			IndexedGeneration: gen,
		}, 0, i == 24)
		if err != nil {
			t.Fatalf("UpsertAsset %d: %v", i, err)
		}
	}

	// Failed scan (NAS offline) MUST NOT mark assets missing.
	marked, err := st.AdvanceScanGeneration(ctx, "r1", 2, false, "NAS offline")
	if err != nil || marked != 0 {
		t.Fatalf("expected 0 marked on failed scan, got marked=%d err=%v", marked, err)
	}

	// Healthy scan gen 2 marks the 5 unseen assets missing.
	marked, err = st.AdvanceScanGeneration(ctx, "r1", 2, true, "")
	if err != nil || marked != 5 {
		t.Fatalf("expected 5 marked missing, got marked=%d err=%v", marked, err)
	}

	// Keyset pagination across 20 active assets (page size 8 -> 8 + 8 + 4).
	p1, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 8})
	if err != nil || len(p1.Items) != 8 || p1.NextCursor == nil {
		t.Fatalf("page 1 unexpected: len=%d cursor=%v err=%v", len(p1.Items), p1.NextCursor, err)
	}
	p2, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 8, Cursor: *p1.NextCursor})
	if err != nil || len(p2.Items) != 8 || p2.NextCursor == nil {
		t.Fatalf("page 2 unexpected: len=%d cursor=%v err=%v", len(p2.Items), p2.NextCursor, err)
	}
	p3, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 8, Cursor: *p2.NextCursor})
	if err != nil || len(p3.Items) != 4 || p3.NextCursor != nil {
		t.Fatalf("page 3 unexpected: len=%d cursor=%v err=%v", len(p3.Items), p3.NextCursor, err)
	}

	// Filter mismatch with cursor -> 400.
	_, err = st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 8, Cursor: *p1.NextCursor, Query: "Track"})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 for filter mismatch, got: %v", err)
	}

	// Index revision change -> 409.
	if _, err := st.SetLibraryIndexing(ctx, false, true); err != nil {
		t.Fatalf("SetLibraryIndexing: %v", err)
	}
	_, err = st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 8, Cursor: *p1.NextCursor})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 for stale index_revision, got: %v", err)
	}
}

func TestRecoveryWorkAndOutboxCrashSafety(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	op, err := st.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation: model.FileOperation{Kind: "edit", State: "planned"},
		Files: []store.OperationFileSpec{
			{Ordinal: 0, Role: "audio", SourceRefJSON: `{"rel":"a.mp3"}`, TargetRefJSON: `{"rel":"a.mp3"}`, Phase: "planned"},
		},
	})
	if err != nil {
		t.Fatalf("CreateFileOperation: %v", err)
	}
	if err := st.RecordOperationFilePhase(ctx, op.ID, 0, "staged", "sha256:abc", `{"ino":1}`); err != nil {
		t.Fatalf("RecordOperationFilePhase: %v", err)
	}

	work, err := st.LoadRecoveryWork(ctx)
	if err != nil || len(work.FileOperations) != 1 || work.FileOperations[0].Files[0].Phase != "staged" {
		t.Fatalf("unexpected recovery work: %+v err=%v", work, err)
	}

	// Outbox crash while sending transitions to delivery_unknown.
	if err := st.EnqueueOutbox(ctx, store.OutboxEnqueueParams{
		Channel: "telegram", RecipientKey: "123", EventKey: "dl:1:complete", PayloadJSON: `{"text":"done"}`,
	}); err != nil {
		t.Fatalf("EnqueueOutbox: %v", err)
	}
	claimed, err := st.ClaimOutbox(ctx, 10)
	if err != nil || len(claimed) != 1 {
		t.Fatalf("ClaimOutbox: %+v err=%v", claimed, err)
	}
	n, err := st.RecoverSendingOutbox(ctx)
	if err != nil || n != 1 {
		t.Fatalf("RecoverSendingOutbox: n=%d err=%v", n, err)
	}

	// Inbox dedupe.
	dup1, err := st.RecordInbox(ctx, model.InboxEvent{Channel: "telegram", PlatformMessageID: "msg-1", SenderID: "u1"}, time.Hour)
	if err != nil || dup1 {
		t.Fatalf("first RecordInbox: dup=%v err=%v", dup1, err)
	}
	dup2, err := st.RecordInbox(ctx, model.InboxEvent{Channel: "telegram", PlatformMessageID: "msg-1", SenderID: "u1"}, time.Hour)
	if err != nil || !dup2 {
		t.Fatalf("duplicate RecordInbox: dup=%v err=%v", dup2, err)
	}
}

func TestQueryLibraryFilesDurationSortAndRootFolderFilter(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	root := store.RootRecord{
		ID: "root-dur", CanonicalPath: filepath.Join(t.TempDir(), "music"), PathCaseMode: "insensitive", Enabled: true,
	}
	if err := st.UpsertRoot(ctx, root); err != nil {
		t.Fatalf("UpsertRoot: %v", err)
	}
	for _, item := range []store.AssetRecord{
		{ID: "a-short", RootID: root.ID, RelPath: "short.wav", PathKey: "short.wav", Folder: "", Name: "short.wav", Status: "active", Editable: true, Size: 100, MTimeNS: 1, Title: "Short", DurationMS: 30_000},
		{ID: "a-long", RootID: root.ID, RelPath: "long.wav", PathKey: "long.wav", Folder: "", Name: "long.wav", Status: "active", Editable: true, Size: 300, MTimeNS: 1, Title: "Long", DurationMS: 240_000},
		{ID: "a-sub", RootID: root.ID, RelPath: "sub/mid.wav", PathKey: "sub/mid.wav", Folder: "sub", Name: "mid.wav", Status: "active", Editable: true, Size: 200, MTimeNS: 1, Title: "Mid", DurationMS: 120_000},
	} {
		if _, err := st.UpsertAsset(ctx, item, 0, true); err != nil {
			t.Fatalf("UpsertAsset %s: %v", item.ID, err)
		}
	}

	p1, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 2, Sort: "duration"})
	if err != nil {
		t.Fatalf("QueryLibraryFiles duration p1: %v", err)
	}
	if len(p1.Items) != 2 || p1.Items[0].Track.Title != "Long" || p1.Items[1].Track.Title != "Mid" || p1.NextCursor == nil {
		t.Fatalf("unexpected duration p1: %+v", p1)
	}
	p2, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 2, Sort: "duration", Cursor: *p1.NextCursor})
	if err != nil {
		t.Fatalf("QueryLibraryFiles duration p2: %v", err)
	}
	if len(p2.Items) != 1 || p2.Items[0].Track.Title != "Short" || p2.NextCursor != nil {
		t.Fatalf("unexpected duration p2: %+v", p2)
	}

	rootOnly, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 10, Folder: ".", Sort: "title"})
	if err != nil {
		t.Fatalf("QueryLibraryFiles root folder: %v", err)
	}
	if len(rootOnly.Items) != 2 {
		t.Fatalf("expected 2 root-only items, got %d", len(rootOnly.Items))
	}
	for _, it := range rootOnly.Items {
		if it.Folder != "" && it.Folder != "." {
			t.Fatalf("expected root folder only, got folder=%q", it.Folder)
		}
	}
}

func TestUpsertMediaVariantCollisionSafety(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	br := 5400

	// Same track with two distinct UpstreamIDs / variant keys accidentally sharing VariantID.
	track1ID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source: "audius", Locator: "001", RootSource: "AudiusMusicClient", Title: "Song A", Artist: "Artist",
	}, []model.MediaVariant{
		{VariantID: "var_shared", UpstreamID: "up_a", Label: "FLAC 无损", Codec: "flac", Bitrate: &br, Lossless: true, Availability: "verified"},
		{VariantID: "var_shared", UpstreamID: "up_b", Label: "FLAC 无损", Codec: "flac", Bitrate: &br, Lossless: true, Availability: "verified"},
	})
	if err != nil {
		t.Fatalf("same-track colliding VariantID failed: %v", err)
	}
	vars1, err := st.ListMediaVariants(ctx, track1ID)
	if err != nil || len(vars1) != 2 {
		t.Fatalf("expected 2 variants for track1, got %d err=%v", len(vars1), err)
	}
	if vars1[0].VariantID == vars1[1].VariantID {
		t.Fatalf("expected distinct stored variant IDs, got %+v", vars1)
	}

	// Distinct track from another source sharing the same VariantID must not collide or steal ownership.
	track2ID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source: "fma", Locator: "001", RootSource: "FMAMusicClient", Title: "Song B", Artist: "Artist",
	}, []model.MediaVariant{
		{VariantID: "var_shared", UpstreamID: "up_a", Label: "FLAC 无损", Codec: "flac", Bitrate: &br, Lossless: true, Availability: "verified"},
	})
	if err != nil {
		t.Fatalf("cross-track colliding VariantID failed: %v", err)
	}
	vars2, err := st.ListMediaVariants(ctx, track2ID)
	if err != nil || len(vars2) != 1 || vars2[0].VariantID == "var_shared" {
		t.Fatalf("expected reassigned unique variant ID for track2, got %+v err=%v", vars2, err)
	}
}
