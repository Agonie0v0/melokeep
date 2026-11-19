package fault

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/agent"
	"github.com/agonie0v0/melokeep/internal/app"
	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/bot"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func makeValidMP3Bytes() []byte {
	buf := make([]byte, 128*1024)
	copy(buf[:10], []byte{'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})
	buf[10] = 0xFF
	buf[11] = 0xFB
	buf[12] = 0x90
	buf[13] = 0x00
	for i := 14; i < len(buf); i++ {
		buf[i] = byte(i % 251)
	}
	return buf
}

func newTestApp(t *testing.T, dataDir, musicRoot string, endpoints map[string]string) *app.App {
	t.Helper()
	a, err := app.New(context.Background(), app.Config{
		DataDir:           dataDir,
		MusicRoots:        []string{musicRoot},
		AllowTestLoopback: true,
		FFmpegPath:        "disabled",
		FFprobePath:       "disabled",
		FpcalcPath:        "disabled",
		ProviderEndpoints: endpoints,
	})
	if err != nil {
		t.Fatalf("app.New(%s): %v", dataDir, err)
	}
	return a
}

func TestFilesPhaseCrashRecovery(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")

	a := newTestApp(t, dataDir, musicRoot, nil)
	root := a.Files.Roots()[0]

	srcDir := filepath.Join(root.CanonicalPath, "Inbox")
	if err := os.MkdirAll(srcDir, 0o755); err != nil {
		t.Fatal(err)
	}
	srcPath := filepath.Join(srcDir, "track.mp3")
	srcLRC := filepath.Join(srcDir, "track.lrc")
	if err := os.WriteFile(srcPath, []byte("ORIGINAL_AUDIO_BYTES"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(srcLRC, []byte("[00:01.00]orig"), 0o644); err != nil {
		t.Fatal(err)
	}
	asset, err := a.Store.UpsertAsset(ctx, store.AssetRecord{
		RootID:   root.ID,
		RelPath:  "Inbox/track.mp3",
		PathKey:  files.ComputePathKey(root.CaseMode, "Inbox/track.mp3"),
		Folder:   "Inbox",
		Name:     "track.mp3",
		Status:   "active",
		Editable: true,
		Size:     int64(len("ORIGINAL_AUDIO_BYTES")),
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	_ = a.Close()

	// 1. Pre-commit crash phases (planned, prepared, publishing) -> paused, source intact, no target leak.
	sentinel := errors.New("simulated process crash")
	for _, phase := range []string{"planned", "prepared", "publishing"} {
		a = newTestApp(t, dataDir, musicRoot, nil)
		curAsset, ok, gErr := a.Store.GetAsset(ctx, asset.ID)
		if gErr != nil || !ok {
			t.Fatalf("get asset before phase %s: ok=%v err=%v", phase, ok, gErr)
		}
		dstFail := filepath.Join(root.CanonicalPath, "Artist", "crash-"+phase+".mp3")
		_, err := a.Files.Execute(ctx, files.OperationRequest{
			Kind:             "move",
			AssetID:          curAsset.ID,
			ExpectedRevision: curAsset.Revision,
			SourcePath:       srcPath,
			TargetPath:       dstFail,
			Mutator: func(_ context.Context, stage string) ([]files.SidecarOutput, error) {
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
			t.Fatalf("phase %s: expected sentinel error, got %v", phase, err)
		}
		_ = a.Close()

		// Reboot app.New -> triggers Files.RecoverOnStartup automatically.
		restarted := newTestApp(t, dataDir, musicRoot, nil)
		if restarted.Recovery.Files.NeedsAttentionOps != 0 || restarted.Recovery.Files.PausedOps == 0 {
			t.Fatalf("phase %s unexpected startup recovery: %+v", phase, restarted.Recovery.Files)
		}
		_ = restarted.Close()

		if got, _ := os.ReadFile(srcPath); string(got) != "ORIGINAL_AUDIO_BYTES" {
			t.Fatalf("phase %s corrupted original source: %q", phase, string(got))
		}
		if _, stErr := os.Lstat(dstFail); !errors.Is(stErr, os.ErrNotExist) {
			t.Fatalf("phase %s leaked target file %s", phase, dstFail)
		}
	}

	// 2. Crash right after DB commit ("committed") -> reboot rolls forward cleanup and preserves DB RelPath.
	a = newTestApp(t, dataDir, musicRoot, nil)
	curAsset, _, _ := a.Store.GetAsset(ctx, asset.ID)
	dstCommitted := filepath.Join(root.CanonicalPath, "Committed", "track.mp3")
	_, err = a.Files.Execute(ctx, files.OperationRequest{
		Kind:             "move",
		AssetID:          curAsset.ID,
		ExpectedRevision: curAsset.Revision,
		SourcePath:       srcPath,
		TargetPath:       dstCommitted,
		BackupDays:       7,
		HookAfterPhase: func(p string) error {
			if p == "committed" {
				return sentinel
			}
			return nil
		},
	})
	if !errors.Is(err, sentinel) {
		t.Fatalf("committed crash: expected sentinel, got %v", err)
	}
	_ = a.Close()

	a = newTestApp(t, dataDir, musicRoot, nil)
	if a.Recovery.Files.CleanedCommittedOps != 1 {
		t.Fatalf("expected CleanedCommittedOps=1 after committed crash, got %+v", a.Recovery.Files)
	}
	if _, stErr := os.Lstat(srcPath); !errors.Is(stErr, os.ErrNotExist) {
		t.Fatalf("expected old source cleaned up after committed roll-forward")
	}
	gotAsset, ok, _ := a.Store.GetAsset(ctx, asset.ID)
	if !ok || gotAsset.RelPath != "Committed/track.mp3" {
		t.Fatalf("expected committed RelPath=Committed/track.mp3, got %+v", gotAsset)
	}

	// 3. Normal move with new sidecar -> Restore reverts original and deletes newly created sidecar.
	dstRestorable := filepath.Join(root.CanonicalPath, "Moved", "track-new.mp3")
	newLRC := filepath.Join(root.CanonicalPath, "Moved", "track-new.lrc")
	res, err := a.Files.Execute(ctx, files.OperationRequest{
		Kind:             "move",
		AssetID:          gotAsset.ID,
		ExpectedRevision: gotAsset.Revision,
		SourcePath:       dstCommitted,
		TargetPath:       dstRestorable,
		BackupDays:       7,
		Mutator: func(_ context.Context, stage string) ([]files.SidecarOutput, error) {
			if err := os.WriteFile(stage, []byte("NEW_AUDIO_BYTES"), 0o644); err != nil {
				return nil, err
			}
			return []files.SidecarOutput{{
				Role:       "sidecar_lrc",
				TargetPath: newLRC,
				Data:       []byte("[00:02.00]generated-sidecar"),
			}}, nil
		},
	})
	if err != nil {
		t.Fatalf("normal move with sidecar: %v", err)
	}
	if got, _ := os.ReadFile(newLRC); string(got) != "[00:02.00]generated-sidecar" {
		t.Fatalf("expected new sidecar created, got %q", string(got))
	}
	restRes, err := a.Files.Restore(ctx, res.OperationID)
	if err != nil || restRes.State != "cleaned" {
		t.Fatalf("restore failed: res=%+v err=%v", restRes, err)
	}
	if got, _ := os.ReadFile(dstCommitted); string(got) != "ORIGINAL_AUDIO_BYTES" {
		t.Fatalf("expected restored original bytes, got %q", string(got))
	}
	if _, stErr := os.Lstat(dstRestorable); !errors.Is(stErr, os.ErrNotExist) {
		t.Fatalf("expected restored target file removed")
	}
	if _, stErr := os.Lstat(newLRC); !errors.Is(stErr, os.ErrNotExist) {
		t.Fatalf("expected newly created sidecar deleted on restore")
	}

	// 4. External file tamper after "file_published" crash -> needs_attention without overwriting external edit.
	_ = a.Close()
	tamperTmp := t.TempDir()
	tamperData := filepath.Join(tamperTmp, "data")
	tamperMusic := filepath.Join(tamperTmp, "music")
	ta := newTestApp(t, tamperData, tamperMusic, nil)
	tRoot := ta.Files.Roots()[0]
	tSrcPath := filepath.Join(tRoot.CanonicalPath, "a.mp3")
	dstTamper := filepath.Join(tRoot.CanonicalPath, "Tampered", "track.mp3")
	if err := os.WriteFile(tSrcPath, []byte("ORIG_FOR_TAMPER"), 0o644); err != nil {
		t.Fatal(err)
	}
	tAsset, err := ta.Store.UpsertAsset(ctx, store.AssetRecord{
		RootID:   tRoot.ID,
		RelPath:  "a.mp3",
		PathKey:  files.ComputePathKey(tRoot.CaseMode, "a.mp3"),
		Folder:   "",
		Name:     "a.mp3",
		Status:   "active",
		Editable: true,
		Size:     int64(len("ORIG_FOR_TAMPER")),
	}, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	_, err = ta.Files.Execute(ctx, files.OperationRequest{
		Kind:             "move",
		AssetID:          tAsset.ID,
		ExpectedRevision: tAsset.Revision,
		SourcePath:       tSrcPath,
		TargetPath:       dstTamper,
		Mutator: func(_ context.Context, stage string) ([]files.SidecarOutput, error) {
			return nil, os.WriteFile(stage, []byte("PUBLISHED_BY_MELOKEEP"), 0o644)
		},
		HookAfterPhase: func(p string) error {
			if p == "file_published" {
				return sentinel
			}
			return nil
		},
	})
	if !errors.Is(err, sentinel) {
		t.Fatalf("expected file_published sentinel, got %v", err)
	}
	_ = ta.Close()

	if err := os.WriteFile(dstTamper, []byte("EXTERNAL_PROCESS_EDIT"), 0o644); err != nil {
		t.Fatal(err)
	}
	tamperRestarted := newTestApp(t, tamperData, tamperMusic, nil)
	defer tamperRestarted.Close()
	if tamperRestarted.Recovery.Files.NeedsAttentionOps != 1 {
		t.Fatalf("expected NeedsAttentionOps=1 after external tamper, got %+v", tamperRestarted.Recovery.Files)
	}
	if got, _ := os.ReadFile(dstTamper); string(got) != "EXTERNAL_PROCESS_EDIT" {
		t.Fatalf("recovery overwrote externally modified target file: %q", string(got))
	}
}

func TestDownloadCancelDuringPublishingAndOrganizeFailure(t *testing.T) {
	ctx := context.Background()
	audioBytes := makeValidMP3Bytes()
	extSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/tracks/au-fault-1/stream":
			w.Header().Set("Content-Type", "audio/mpeg")
			http.ServeContent(w, r, "morning_breeze.mp3", time.Unix(1700000000, 0), bytes.NewReader(audioBytes))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer extSrv.Close()

	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	a := newTestApp(t, dataDir, musicRoot, map[string]string{
		"audius_track_base": extSrv.URL + "/v1/tracks",
	})
	defer a.Close()

	enteredPub := make(chan struct{})
	releasePub := make(chan struct{})
	var orgCalled atomic.Bool

	rtCfg := config.DefaultRuntimeConfig()
	rtCfg.EmbedMetadata = false
	rtCfg.Sources = []string{"AudiusMusicClient"}
	rtCfg.WorkDir = a.AbsWorkDir
	rtCfg.DataDir = a.AbsDataDir
	snap := config.NewSnapshot(rtCfg, a.Cfg.Budgets)

	svc := download.NewService(
		a.Store,
		a.Transport,
		a.Providers,
		a.Files,
		a.Media,
		a.Tags,
		download.Options{
			StagingRoot: filepath.Join(a.AbsDataDir, "go", "staging_fault"),
			Budgets:     a.Cfg.Budgets,
			SnapshotProvider: func() config.Snapshot {
				return snap
			},
			AutoOrganizeRunner: func(ctx context.Context, jobID string, dl model.Download) error {
				orgCalled.Store(true)
				return errors.New("intentional auto-organize failure")
			},
			TestHookBeforePublish: func(ctx context.Context, dl model.Download) {
				close(enteredPub)
				<-releasePub
			},
		},
	)
	defer svc.Close()

	trackID, err := a.Store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source:     "AudiusMusicClient",
		Locator:    "au-fault-1",
		RootSource: "AudiusMusicClient",
		Title:      "Morning Breeze",
		Artist:     "Open Horizon",
		Album:      "Open Air Sessions",
		DurationMS: 5000,
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	pubID := "pub-fault-au-1"
	if err := a.Store.PutPublicRef(ctx, model.PublicRef{
		PublicID: pubID,
		Kind:     "candidate",
		TargetID: trackID,
		Source:   "AudiusMusicClient",
		SourceID: "au-fault-1",
	}, ""); err != nil {
		t.Fatal(err)
	}

	actor := auth.WebAdminActor("admin")
	res, err := svc.Enqueue(ctx, actor, snap, download.EnqueueRequest{
		PublicID:       pubID,
		QualityID:      "",
		IdempotencyKey: "fault-dl-pub",
		RequestHash:    "fault-hash-1",
		AutoOrganize:   &download.AutoOrganizeConfig{Enabled: true, Action: "organize"},
	})
	if err != nil {
		t.Fatalf("Enqueue: %v", err)
	}

	select {
	case <-enteredPub:
	case <-time.After(10 * time.Second):
		cur, _, _ := svc.Get(ctx, res.Download.ID)
		t.Fatalf("timed out waiting for TestHookBeforePublish (status=%s detail=%q)", cur.Status, cur.Detail)
	}

	cancelDone := make(chan model.Download, 1)
	cancelErrCh := make(chan error, 1)
	go func() {
		dl, cErr := svc.Cancel(ctx, actor, res.Download.ID)
		cancelErrCh <- cErr
		cancelDone <- dl
	}()
	time.Sleep(50 * time.Millisecond)
	select {
	case <-cancelDone:
		t.Fatalf("Cancel returned prematurely while download was in publishing phase")
	default:
	}

	close(releasePub)
	if cErr := <-cancelErrCh; cErr != nil {
		t.Fatalf("Cancel returned error: %v", cErr)
	}
	finalDL := <-cancelDone
	if finalDL.Status != model.DownloadComplete || finalDL.File == nil {
		t.Fatalf("expected Cancel during publishing to settle as complete with file, got %+v", finalDL)
	}

	// Verify published target file is intact (matches full audioBytes, no partial/corrupt bytes).
	if !strings.HasPrefix(*finalDL.File, "asset:") {
		t.Fatalf("expected asset:<id> token, got %q", *finalDL.File)
	}
	assetRec, ok, aErr := a.Store.GetAsset(ctx, strings.TrimPrefix(*finalDL.File, "asset:"))
	if aErr != nil || !ok {
		t.Fatalf("GetAsset: ok=%v err=%v", ok, aErr)
	}
	targetPath := filepath.Join(a.Files.Roots()[0].CanonicalPath, filepath.FromSlash(assetRec.RelPath))
	diskBytes, err := os.ReadFile(targetPath)
	if err != nil {
		t.Fatalf("read published file %s: %v", targetPath, err)
	}
	if !bytes.Equal(diskBytes, audioBytes) {
		t.Fatalf("published target bytes mismatch: got=%d want=%d", len(diskBytes), len(audioBytes))
	}

	// Wait for failing AutoOrganizeRunner and confirm Download stays complete with failed Processing.
	deadline := time.Now().Add(5 * time.Second)
	var dto model.DownloadJobDTO
	for time.Now().Before(deadline) {
		if orgCalled.Load() {
			dto, err = svc.ToDTO(ctx, finalDL)
			if err == nil && dto.Processing != nil && dto.Processing.Status == "failed" {
				break
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !orgCalled.Load() {
		t.Fatalf("expected AutoOrganizeRunner to be invoked")
	}
	if dto.Status != "complete" || dto.Processing == nil || dto.Processing.Status != "failed" {
		t.Fatalf("expected Download=complete with Processing.Status=failed, got %+v", dto)
	}
}

func TestBotOutboxSendingCrashRecovery(t *testing.T) {
	ctx := context.Background()
	var tgCalls atomic.Int32
	var delivered []string
	tgSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tgCalls.Add(1)
		buf := new(bytes.Buffer)
		_, _ = buf.ReadFrom(r.Body)
		delivered = append(delivered, buf.String())
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"result":{"message_id":77}}`))
	}))
	defer tgSrv.Close()

	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")

	a := newTestApp(t, dataDir, musicRoot, nil)
	cfg := bot.DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.TGEnabled = true
	cfg.TGToken = "tg-fault-tok"
	cfg.TGAllowedUsers = "1001"
	cfg.TGAPIURL = tgSrv.URL
	if _, err := a.Bot.UpdateConfig(ctx, auth.WebAdminActor("admin"), cfg, nil); err != nil {
		t.Fatalf("UpdateConfig: %v", err)
	}

	if err := a.Store.EnqueueOutbox(ctx, store.OutboxEnqueueParams{
		ID:           "fault-sending-1",
		Channel:      bot.ChannelTelegram,
		RecipientKey: "1001",
		EventKey:     "ev-crash-1",
		PayloadJSON:  `{"text":"stuck-mid-send"}`,
	}); err != nil {
		t.Fatal(err)
	}
	claimed, err := a.Store.ClaimOutbox(ctx, 1)
	if err != nil || len(claimed) != 1 || claimed[0].ID != "fault-sending-1" {
		t.Fatalf("ClaimOutbox: claimed=%+v err=%v", claimed, err)
	}
	if err := a.Store.EnqueueOutbox(ctx, store.OutboxEnqueueParams{
		ID:           "fault-pending-2",
		Channel:      bot.ChannelTelegram,
		RecipientKey: "1001",
		EventKey:     "ev-fresh-2",
		PayloadJSON:  `{"text":"fresh-after-reboot"}`,
	}); err != nil {
		t.Fatal(err)
	}
	_ = a.Close()

	// Reboot app.New -> Bot.RecoverOnStartup runs automatically.
	restarted := newTestApp(t, dataDir, musicRoot, nil)
	defer restarted.Close()
	if restarted.Recovery.OutboxUnknown != 1 {
		t.Fatalf("expected Recovery.OutboxUnknown=1, got %d", restarted.Recovery.OutboxUnknown)
	}

	processed, err := restarted.Bot.ProcessOutboxOnce(ctx, 10)
	if err != nil || processed != 1 {
		t.Fatalf("ProcessOutboxOnce: processed=%d err=%v", processed, err)
	}
	if tgCalls.Load() != 1 || len(delivered) != 1 || !strings.Contains(delivered[0], "fresh-after-reboot") {
		t.Fatalf("expected only pending message delivered, got calls=%d delivered=%v", tgCalls.Load(), delivered)
	}
	var stuckState string
	if err := restarted.Store.Reader().QueryRowContext(ctx, `SELECT state FROM outbox WHERE id = 'fault-sending-1'`).Scan(&stuckState); err != nil {
		t.Fatal(err)
	}
	if stuckState != string(model.OutboxDeliveryUnknown) {
		t.Fatalf("expected fault-sending-1 state=%s, got %s", model.OutboxDeliveryUnknown, stuckState)
	}
}

func TestAgentStartedWriteCrashRecovery(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")

	a := newTestApp(t, dataDir, musicRoot, nil)
	sessID := model.NewUUID()
	crashed := agent.Session{
		ID:           sessID,
		Title:        "故障恢复会话",
		Status:       agent.StatusRunning,
		ActiveTurnID: "turn_fault",
		Messages:     []agent.SessionMessage{{Role: "user", Content: "创建一个歌单", CreatedAt: time.Now().UnixMilli()}},
		Events: []agent.ExecutionEvent{{
			ID:        "ev_fault",
			TurnID:    "turn_fault",
			Tool:      "create_playlist",
			Operation: "POST /api/playlists",
			Status:    "running",
			Detail:    "正在执行…",
		}},
		PendingWrite: &agent.PendingWrite{
			TurnID:         "turn_fault",
			ToolCallID:     "call_fault",
			ToolName:       "create_playlist",
			Operation:      "POST /api/playlists",
			IdempotencyKey: "ai:fault:turn_fault:call_fault",
			State:          "started",
		},
		ResumeTurn: &agent.TurnRequest{Message: "创建一个歌单"},
	}
	raw, err := json.Marshal(crashed)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.Store.PutDurableRecord(ctx, agent.NamespaceSession, sessID, string(raw), 0); err != nil {
		t.Fatal(err)
	}
	_ = a.Close()

	restarted := newTestApp(t, dataDir, musicRoot, nil)
	defer restarted.Close()

	recovered, err := restarted.Agent.GetSession(ctx, sessID)
	if err != nil {
		t.Fatalf("GetSession: %v", err)
	}
	if recovered.Status != agent.StatusNeedsDecision {
		t.Fatalf("expected StatusNeedsDecision, got %s", recovered.Status)
	}
	rec, ok, recErr := restarted.Store.GetDurableRecord(ctx, agent.NamespaceSession, sessID)
	if recErr != nil || !ok {
		t.Fatalf("GetDurableRecord: ok=%v err=%v", ok, recErr)
	}
	var persisted agent.Session
	if uErr := json.Unmarshal([]byte(rec.BodyJSON), &persisted); uErr != nil {
		t.Fatal(uErr)
	}
	if persisted.PendingWrite == nil || persisted.PendingWrite.State != "unknown" {
		t.Fatalf("expected persisted PendingWrite.State=unknown, got %+v", persisted.PendingWrite)
	}
	if len(recovered.Events) == 0 || recovered.Events[0].Status != "operation_unknown" {
		t.Fatalf("expected event status operation_unknown, got %+v", recovered.Events)
	}
	// Verify write was NOT blindly replayed (no playlists created).
	cols, err := restarted.Store.ListCollections(ctx, "", "playlist")
	if err != nil {
		t.Fatal(err)
	}
	if len(cols) != 0 {
		t.Fatalf("expected 0 playlists created on recovery, got %d", len(cols))
	}
	if _, err := restarted.Agent.Resume(ctx, auth.WebAdminActor("admin"), sessID); err == nil {
		t.Fatalf("expected Resume to reject session with unresolved unknown write")
	}
}

func TestOSInstanceLockDoubleStart(t *testing.T) {
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	lockPath := filepath.Join(dataDir, "go", "melokeep.lock")

	first := newTestApp(t, dataDir, musicRoot, nil)
	if _, err := os.Stat(lockPath); err != nil {
		_ = first.Close()
		t.Fatalf("expected lock file at %s: %v", lockPath, err)
	}

	_, err := app.New(context.Background(), app.Config{
		DataDir:           dataDir,
		MusicRoots:        []string{musicRoot},
		AllowTestLoopback: true,
		FFmpegPath:        "disabled",
		FFprobePath:       "disabled",
		FpcalcPath:        "disabled",
	})
	if err == nil {
		_ = first.Close()
		t.Fatalf("expected second app.New on same DataDir to fail while first holds lock")
	}
	if _, statErr := os.Stat(lockPath); statErr != nil {
		_ = first.Close()
		t.Fatalf("expected lock file %s to remain intact after failed second start: %v", lockPath, statErr)
	}

	if err := first.Close(); err != nil {
		t.Fatalf("first.Close: %v", err)
	}

	second := newTestApp(t, dataDir, musicRoot, nil)
	if err := second.Close(); err != nil {
		t.Fatalf("second.Close: %v", err)
	}
}
