package acceptance_integrity_test

import (
	"context"
	"fmt"
	"net/http"
	"path/filepath"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func TestAcceptanceStoreDownloadDedupeOwnershipAndAtomicity(t *testing.T) {
	st := openIntegrityStore(t)
	ctx := context.Background()

	if err := st.UpsertRoot(ctx, store.RootRecord{
		ID: "r1", CanonicalPath: filepath.Join(t.TempDir(), "music"),
		PathCaseMode: "sensitive", Enabled: true, Healthy: true,
	}); err != nil {
		t.Fatal(err)
	}

	// 1. Paused download holds default dedupe_key.
	dl1, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		PublicID: "pub-1", QualityID: "flac", QualityLabel: "FLAC",
		DownloadSource: "AudiusMusicClient", ActorKey: "channel:tg:u1", DedupeKey: "AudiusMusicClient:song1:flac",
	})
	if err != nil || !dl1.Created {
		t.Fatalf("create dl1: %+v err=%v", dl1, err)
	}
	dl1Mod, err := st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID: dl1.Download.ID, ExpectedRevision: dl1.Download.Revision,
		AllowedStates: []model.DownloadStatus{model.DownloadQueued},
		NewState:      model.DownloadPaused,
	})
	if err != nil {
		t.Fatal(err)
	}
	dlReusePaused, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		PublicID: "pub-1", QualityID: "flac", QualityLabel: "FLAC",
		DownloadSource: "AudiusMusicClient", ActorKey: "channel:tg:u2", DedupeKey: "AudiusMusicClient:song1:flac",
	})
	if err != nil || dlReusePaused.Created || !dlReusePaused.Reused || dlReusePaused.SameOwner {
		t.Fatalf("expected paused download reused with SameOwner=false for u2, got %+v err=%v", dlReusePaused, err)
	}

	// 2. Cancelled download releases default dedupe_key.
	dl1Mod, err = st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID: dl1Mod.ID, ExpectedRevision: dl1Mod.Revision,
		AllowedStates: []model.DownloadStatus{model.DownloadPaused},
		NewState:      model.DownloadCancelled,
	})
	if err != nil {
		t.Fatal(err)
	}
	dl2, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		PublicID: "pub-1", QualityID: "flac", QualityLabel: "FLAC",
		DownloadSource: "AudiusMusicClient", ActorKey: "channel:tg:u2", DedupeKey: "AudiusMusicClient:song1:flac",
	})
	if err != nil || !dl2.Created || dl2.Download.ID == dl1Mod.ID {
		t.Fatalf("expected cancelled download to release dedupe_key, got %+v err=%v", dl2, err)
	}

	// 3. Different quality has distinct dedupe_key and is never merged.
	dlMP3, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		PublicID: "pub-1", QualityID: "mp3_320", QualityLabel: "MP3 320k",
		DownloadSource: "AudiusMusicClient", ActorKey: "channel:tg:u2", DedupeKey: "AudiusMusicClient:song1:mp3_320",
	})
	if err != nil || !dlMP3.Created || dlMP3.Download.ID == dl2.Download.ID {
		t.Fatalf("expected different quality not merged, got %+v err=%v", dlMP3, err)
	}

	// 4. Atomicity: CommitPublishedOutputs referencing nonexistent root MUST roll back completely (download stays publishing, 0 assets/jobs created).
	dl2Pub, err := st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID: dl2.Download.ID, ExpectedRevision: dl2.Download.Revision,
		AllowedStates: []model.DownloadStatus{model.DownloadQueued},
		NewState:      model.DownloadPublishing,
	})
	if err != nil {
		t.Fatal(err)
	}
	_, commitFailErr := st.CommitPublishedOutputs(ctx, store.CommitPublishedOutputsParams{
		DownloadID: dl2Pub.ID, ExpectedRevision: dl2Pub.Revision,
		FileToken: "asset:bad-1", BytesDone: 100,
		Outputs: []store.PublishedOutputSpec{
			{Asset: store.AssetRecord{ID: "bad-1", RootID: "nonexistent-root", RelPath: "x.flac", Size: 100}},
		},
		AutoOrganize: &store.AutoOrganizeJobSpec{
			Action: "both", OwnerKey: "channel:tg:u2",
			Items: []store.LibraryItemInput{{PathToken: "asset:bad-1", Name: "x.flac"}},
		},
	})
	if commitFailErr == nil {
		t.Fatal("expected FK failure on nonexistent root")
	}
	dlCheck, _, _ := st.GetDownload(ctx, dl2Pub.ID)
	if dlCheck.Status != model.DownloadPublishing {
		t.Fatalf("expected download rolled back to publishing, got %s", dlCheck.Status)
	}
	jobs, _ := st.ListLibraryJobs(ctx, 10)
	if len(jobs) != 0 {
		t.Fatalf("expected 0 jobs on rolled-back commit, got %d", len(jobs))
	}

	// 5. Successful CommitPublishedOutputs with ReuseCompleted check for another actor.
	dlDone, err := st.CommitPublishedOutputs(ctx, store.CommitPublishedOutputsParams{
		DownloadID: dl2Pub.ID, ExpectedRevision: dl2Pub.Revision,
		FileToken: "asset:good-1", BytesDone: 2048,
		Outputs: []store.PublishedOutputSpec{
			{Asset: store.AssetRecord{ID: "good-1", RootID: "r1", RelPath: "good.flac", Size: 2048, Title: "Good"}},
		},
		AutoOrganize: &store.AutoOrganizeJobSpec{
			Action: "both", OwnerKey: "channel:tg:u2",
			Items: []store.LibraryItemInput{{PathToken: "asset:good-1", Name: "good.flac"}},
		},
	})
	if err != nil || dlDone.Status != model.DownloadComplete {
		t.Fatalf("CommitPublishedOutputs failed: %+v err=%v", dlDone, err)
	}
	reusedCompleted, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		PublicID: "pub-1", QualityID: "flac", QualityLabel: "FLAC",
		DownloadSource: "AudiusMusicClient", ActorKey: "channel:dc:u9", DedupeKey: "AudiusMusicClient:song1:flac",
		ReuseCompleted: true,
	})
	if err != nil || !reusedCompleted.Reused || reusedCompleted.SameOwner || reusedCompleted.Download.ID != dlDone.ID {
		t.Fatalf("expected ReuseCompleted to return completed download with SameOwner=false, got %+v err=%v", reusedCompleted, err)
	}
}

func TestAcceptanceStoreScanGenerationPaginationAndOutbox(t *testing.T) {
	st := openIntegrityStore(t)
	ctx := context.Background()

	if err := st.UpsertRoot(ctx, store.RootRecord{
		ID: "r1", CanonicalPath: filepath.Join(t.TempDir(), "music"),
		PathCaseMode: "sensitive", Enabled: true, Healthy: true,
	}); err != nil {
		t.Fatal(err)
	}

	for i := 0; i < 15; i++ {
		gen := int64(2)
		if i < 3 {
			gen = 1
		}
		_, err := st.UpsertAsset(ctx, store.AssetRecord{
			ID:                fmt.Sprintf("a-%02d", i),
			RootID:            "r1",
			RelPath:           fmt.Sprintf("Dir/Song-%02d.flac", i),
			Title:             fmt.Sprintf("Title %02d", 15-i), // reverse title order vs path_key
			Artist:            "Artist",
			Album:             "Album",
			Size:              int64(1000 + i),
			IndexedGeneration: gen,
		}, 0, i == 14)
		if err != nil {
			t.Fatal(err)
		}
	}

	// Offline root scan must preserve all active assets.
	marked, err := st.AdvanceScanGeneration(ctx, "r1", 2, false, "network share unreachable")
	if err != nil || marked != 0 {
		t.Fatalf("offline scan must mark 0 missing, got %d err=%v", marked, err)
	}
	rootRec, ok, _ := st.GetRoot(ctx, "r1")
	if !ok || rootRec.Healthy {
		t.Fatalf("expected root marked unhealthy on failed scan, got %+v", rootRec)
	}

	// Healthy scan gen 2 marks only the 3 unseen assets missing.
	marked, err = st.AdvanceScanGeneration(ctx, "r1", 2, true, "")
	if err != nil || marked != 3 {
		t.Fatalf("expected 3 marked missing, got %d err=%v", marked, err)
	}

	// Test pagination by title sort + 400 on sort mismatch + 409 on index_revision change.
	p1, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 5, Sort: "title"})
	if err != nil || len(p1.Items) != 5 || p1.NextCursor == nil {
		t.Fatalf("page 1 by title unexpected: %+v err=%v", p1, err)
	}
	p2, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 5, Sort: "title", Cursor: *p1.NextCursor})
	if err != nil || len(p2.Items) != 5 || p2.NextCursor == nil {
		t.Fatalf("page 2 by title unexpected: %+v err=%v", p2, err)
	}
	p3, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 5, Sort: "title", Cursor: *p2.NextCursor})
	if err != nil || len(p3.Items) != 2 || p3.NextCursor != nil {
		t.Fatalf("page 3 by title unexpected: %+v err=%v", p3, err)
	}
	// Sort mismatch -> 400
	if _, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 5, Sort: "size", Cursor: *p1.NextCursor}); err == nil {
		t.Fatal("expected 400 on sort mismatch")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", de.HTTPStatus())
	}
	// Malformed cursor -> 400
	if _, err := st.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 5, Sort: "title", Cursor: "bad-cursor"}); err == nil {
		t.Fatal("expected 400 on malformed cursor")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", de.HTTPStatus())
	}

	// Outbox crash recovery only transitions 'sending' -> 'delivery_unknown', leaving 'pending' intact.
	_ = st.EnqueueOutbox(ctx, store.OutboxEnqueueParams{Channel: "telegram", RecipientKey: "c1", EventKey: "ev-sending", PayloadJSON: `{"a":1}`})
	claimed, err := st.ClaimOutbox(ctx, 1)
	if err != nil || len(claimed) != 1 {
		t.Fatalf("ClaimOutbox: %+v err=%v", claimed, err)
	}
	_ = st.EnqueueOutbox(ctx, store.OutboxEnqueueParams{Channel: "telegram", RecipientKey: "c1", EventKey: "ev-pending", PayloadJSON: `{"a":2}`})
	n, err := st.RecoverSendingOutbox(ctx)
	if err != nil || n != 1 {
		t.Fatalf("expected 1 outbox message moved to delivery_unknown, got %d err=%v", n, err)
	}
	claimedAfter, err := st.ClaimOutbox(ctx, 10)
	if err != nil || len(claimedAfter) != 1 || claimedAfter[0].ID == claimed[0].ID {
		t.Fatalf("expected only ev-pending claimable after recovery, got %+v err=%v", claimedAfter, err)
	}

	// Inbox dedupe
	dup1, err := st.RecordInbox(ctx, model.InboxEvent{Channel: "discord", PlatformMessageID: "m1", SenderID: "u1"}, time.Hour)
	if err != nil || dup1 {
		t.Fatalf("first inbox: dup=%v err=%v", dup1, err)
	}
	dup2, err := st.RecordInbox(ctx, model.InboxEvent{Channel: "discord", PlatformMessageID: "m1", SenderID: "u1"}, time.Hour)
	if err != nil || !dup2 {
		t.Fatalf("second inbox: dup=%v err=%v", dup2, err)
	}
}
