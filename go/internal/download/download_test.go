package download

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type stubProvider struct {
	id      string
	resolve func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error)
}

func (p *stubProvider) ID() string         { return p.id }
func (p *stubProvider) EngineName() string { return "StubMusicClient" }
func (p *stubProvider) Capabilities() model.Capability {
	return model.Capability{Tier: "native", Search: true}
}
func (p *stubProvider) Search(context.Context, model.SearchOptions, func(model.ProviderTrack) error) (model.SearchSummary, error) {
	return model.SearchSummary{}, nil
}
func (p *stubProvider) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	return p.resolve(ctx, track, policy)
}

func findTestFFmpeg() string {
	if env := strings.TrimSpace(os.Getenv("MELOKEEP_FFMPEG")); env != "" {
		if p, err := exec.LookPath(env); err == nil {
			return p
		}
	}
	if p, err := exec.LookPath("ffmpeg"); err == nil {
		return p
	}
	if runtime.GOOS == "windows" {
		const winCandidate = `E:\tool\ffmpeg\bin\ffmpeg.exe`
		if _, err := os.Stat(winCandidate); err == nil {
			return winCandidate
		}
	}
	return ""
}

func sampleWAVBytes() []byte {
	// 0.3s 44.1kHz 16-bit mono PCM WAV: valid decodable audio even without ffmpeg.
	const (
		sampleRate    = 44100
		channels      = 1
		bitsPerSample = 16
		numSamples    = sampleRate * 3 / 10
		dataSize      = numSamples * channels * (bitsPerSample / 8)
	)
	buf := make([]byte, 44+dataSize)
	copy(buf[0:4], "RIFF")
	binary.LittleEndian.PutUint32(buf[4:8], uint32(36+dataSize))
	copy(buf[8:12], "WAVE")
	copy(buf[12:16], "fmt ")
	binary.LittleEndian.PutUint32(buf[16:20], 16)
	binary.LittleEndian.PutUint16(buf[20:22], 1) // PCM
	binary.LittleEndian.PutUint16(buf[22:24], channels)
	binary.LittleEndian.PutUint32(buf[24:28], sampleRate)
	binary.LittleEndian.PutUint32(buf[28:32], sampleRate*channels*(bitsPerSample/8))
	binary.LittleEndian.PutUint16(buf[32:34], channels*(bitsPerSample/8))
	binary.LittleEndian.PutUint16(buf[34:36], bitsPerSample)
	copy(buf[36:40], "data")
	binary.LittleEndian.PutUint32(buf[40:44], dataSize)
	return buf
}

func sampleMP3Bytes(t *testing.T) []byte {
	t.Helper()
	if ffmpegPath := findTestFFmpeg(); ffmpegPath != "" {
		outPath := filepath.Join(t.TempDir(), "sample.mp3")
		cmd := exec.Command(ffmpegPath, "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.3", "-c:a", "libmp3lame", "-b:a", "128k", outPath)
		if out, err := cmd.CombinedOutput(); err == nil {
			if b, rErr := os.ReadFile(outPath); rErr == nil && len(b) > 0 {
				return b
			}
		} else {
			t.Logf("ffmpeg sample mp3 fallback: %v %s", err, string(out))
		}
	}
	return sampleWAVBytes()
}

func setupDownloadEnv(t *testing.T, prov model.Provider, optsModifier func(*Options)) (*store.Store, *Service, string, string) {
	t.Helper()
	tmp := t.TempDir()
	dbPath := filepath.Join(tmp, "go", "melokeep.db")
	st, err := store.Open(context.Background(), store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	musicRoot := filepath.Join(tmp, "music")
	if err := os.MkdirAll(musicRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	canon, err := files.CanonicalizeRoot(musicRoot)
	if err != nil {
		t.Fatal(err)
	}
	caps, mode, _ := files.ProbeCapabilities(canon)
	rootID := model.NewUUID()
	if err := st.UpsertRoot(context.Background(), store.RootRecord{
		ID:            rootID,
		CanonicalPath: canon,
		PathCaseMode:  mode,
		Enabled:       true,
		Healthy:       true,
	}); err != nil {
		t.Fatal(err)
	}

	locks := files.NewAssetLockManager(2 * time.Second)
	coords := files.NewCoordinator(st, locks, []files.Root{{
		ID:            rootID,
		CanonicalPath: canon,
		CaseMode:      mode,
		Capabilities:  caps,
	}})

	reg, err := provider.NewRegistry([]provider.Entry{{Provider: prov}})
	if err != nil {
		t.Fatal(err)
	}
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	t.Cleanup(pool.CloseAll)

	ffmpegPath := findTestFFmpeg()
	budgets := config.DefaultResourceBudgets()
	budgets.DownloadConcurrency = 2
	runner := media.NewRunner(budgets, ffmpegPath, "", "")

	opts := Options{
		StagingRoot: filepath.Join(tmp, "partial"),
		Budgets:     budgets,
		BackoffBase: 20 * time.Millisecond,
		BackoffMax:  80 * time.Millisecond,
		MaxAttempts: 3,
	}
	if optsModifier != nil {
		optsModifier(&opts)
	}
	svc := NewService(st, pool, reg, coords, runner, nil, opts)
	t.Cleanup(func() { _ = svc.Close() })
	return st, svc, canon, rootID
}

func seedTrack(t *testing.T, st *store.Store, source, locator, title, artist string) string {
	t.Helper()
	br320 := 320
	br999 := 999
	variants := []model.MediaVariant{
		{VariantID: locator + "_mp3_320", UpstreamID: "mp3_320", Label: "320kbps MP3", Codec: "mp3", Bitrate: &br320, Availability: "full"},
		{VariantID: locator + "_flac_sq", UpstreamID: "flac_sq", Label: "FLAC", Codec: "flac", Bitrate: &br999, Lossless: true, Availability: "full"},
	}
	trackID, err := st.UpsertProviderTrack(context.Background(), store.ProviderTrackRecord{
		Source:       source,
		Locator:      locator,
		RootSource:   "StubMusicClient",
		Title:        title,
		Artist:       artist,
		Album:        "TestAlbum",
		DurationMS:   120000,
		MetadataJSON: `{"lyrics":"[00:00.00]test lyric"}`,
	}, variants)
	if err != nil {
		t.Fatal(err)
	}
	pubID := "pub-" + locator
	if err := st.PutPublicRef(context.Background(), model.PublicRef{
		PublicID: pubID,
		Kind:     "candidate",
		TargetID: trackID,
		Source:   source,
		SourceID: locator,
	}, locator+"_mp3_320"); err != nil {
		t.Fatal(err)
	}
	return pubID
}

func waitForStatus(t *testing.T, svc *Service, dlID string, want model.DownloadStatus, timeout time.Duration) model.Download {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		dl, ok, err := svc.Get(context.Background(), dlID)
		if err == nil && ok && dl.Status == want {
			return dl
		}
		time.Sleep(15 * time.Millisecond)
	}
	dl, _, _ := svc.Get(context.Background(), dlID)
	t.Fatalf("timed out waiting for status %s (last=%s detail=%q)", want, dl.Status, dl.Detail)
	return model.Download{}
}

func TestDownloadRange206_416_IfRangeAndTailTruncation(t *testing.T) {
	payload := append([]byte("ID3\x03\x00\x00\x00\x00\x00\x00"), bytes.Repeat([]byte("MP3FRAME"), 8192)...)
	wantSum := sha256.Sum256(payload)
	var currentETag atomic.Value
	currentETag.Store(`"etag-v1"`)
	var seenRange atomic.Bool

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		etag := currentETag.Load().(string)
		w.Header().Set("ETag", etag)
		w.Header().Set("Content-Type", "audio/mpeg")
		rng := r.Header.Get("Range")
		ifRange := r.Header.Get("If-Range")
		if rng == "" || (ifRange != "" && ifRange != etag) {
			w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(payload)
			return
		}
		seenRange.Store(true)
		var start int
		_, _ = fmt.Sscanf(rng, "bytes=%d-", &start)
		if start >= len(payload) {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", len(payload)))
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, len(payload)-1, len(payload)))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(payload[start:])
	}))
	defer srv.Close()

	prov := &stubProvider{
		id: "AudiusMusicClient",
		resolve: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			return []model.ResolvedMedia{{
				VariantID: "mp3_320",
				Variant:   track.Variants[0],
				URL:       srv.URL + "/track.mp3",
				Codec:     "mp3",
			}}, nil
		},
	}

	st, svc, _, _ := setupDownloadEnv(t, prov, nil)
	ctx := context.Background()
	actor := auth.WebAdminActor("admin")
	snap := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())
	pubID := seedTrack(t, st, "AudiusMusicClient", "t-range", "RangeSong", "Tester")

	// 1. Direct transferHTTP tests: tail truncation before resume (206).
	dl := model.Download{ID: "dl-trunc", Attempt: 1}
	partPath := filepath.Join(t.TempDir(), "dl-trunc.part")
	durable := int64(len(payload) / 2)
	corruptTail := append(append([]byte(nil), payload[:durable]...), []byte("GARBAGE-TAIL-AFTER-CRASH")...)
	if err := os.WriteFile(partPath, corruptTail, 0o600); err != nil {
		t.Fatal(err)
	}
	// Create DB download row for FK on transfer_checkpoints.
	_, err := st.CreateDownload(ctx, store.CreateDownloadParams{
		ID: "dl-trunc", PublicID: pubID, QualityID: "mp3_320", QualityLabel: "320",
		DownloadSource: "AudiusMusicClient", ActorKey: actor.Key(), DedupeKey: "k-trunc",
	})
	if err != nil {
		t.Fatal(err)
	}
	urlFP := fmt.Sprintf("%x", sha256.Sum256([]byte(srv.URL+"/track.mp3")))
	tot := int64(len(payload))
	if err := st.UpsertTransferCheckpoint(ctx, model.TransferCheckpoint{
		DownloadID: "dl-trunc", Attempt: 1, TempPath: filepath.Base(partPath), Bytes: durable, TotalBytes: &tot,
	}, urlFP, "ETag", `"etag-v1"`, false); err != nil {
		t.Fatal(err)
	}
	rc := provider.RequestContext{Config: snap}
	n, _, _, err := transferHTTP(ctx, svc.pool, st, dl, model.ResolvedMedia{URL: srv.URL + "/track.mp3"}, rc, partPath, 10<<20, 10*time.Second, nil)
	if err != nil {
		t.Fatalf("transferHTTP resume failed: %v", err)
	}
	if !seenRange.Load() || n != tot {
		t.Fatalf("expected 206 resume to %d, got n=%d seenRange=%v", tot, n, seenRange.Load())
	}
	gotBytes, _ := os.ReadFile(partPath)
	if sha256.Sum256(gotBytes) != wantSum {
		t.Fatalf("resumed payload hash mismatch after tail truncation")
	}

	// 2. Test 416 complete verification when disk already has full file.
	seenRange.Store(false)
	if err := st.UpsertTransferCheckpoint(ctx, model.TransferCheckpoint{
		DownloadID: "dl-trunc", Attempt: 1, TempPath: filepath.Base(partPath), Bytes: tot, TotalBytes: &tot,
	}, urlFP, "ETag", `"etag-v1"`, false); err != nil {
		t.Fatal(err)
	}
	n2, _, _, err := transferHTTP(ctx, svc.pool, st, dl, model.ResolvedMedia{URL: srv.URL + "/track.mp3"}, rc, partPath, 10<<20, 10*time.Second, nil)
	if err != nil || n2 != tot || !seenRange.Load() {
		t.Fatalf("expected 416 shortcut completion, err=%v n2=%d", err, n2)
	}

	// 3. Test If-Range mismatch triggers clean 200 restart.
	currentETag.Store(`"etag-v2"`)
	if err := os.WriteFile(partPath, bytes.Repeat([]byte("OLD"), 100), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := st.UpsertTransferCheckpoint(ctx, model.TransferCheckpoint{
		DownloadID: "dl-trunc", Attempt: 1, TempPath: filepath.Base(partPath), Bytes: 300, TotalBytes: &tot,
	}, urlFP, "ETag", `"etag-v1"`, false); err != nil {
		t.Fatal(err)
	}
	n3, _, _, err := transferHTTP(ctx, svc.pool, st, dl, model.ResolvedMedia{URL: srv.URL + "/track.mp3"}, rc, partPath, 10<<20, 10*time.Second, nil)
	if err != nil || n3 != tot {
		t.Fatalf("expected 200 restart on If-Range mismatch, err=%v n3=%d", err, n3)
	}
	got3, _ := os.ReadFile(partPath)
	if sha256.Sum256(got3) != wantSum {
		t.Fatalf("restarted payload mismatch")
	}
}

func TestDownloadRetryPauseCancelDedupeForceNewAndAutoOrganize(t *testing.T) {
	payload := sampleMP3Bytes(t)
	var reqCount atomic.Int32
	slowStarted := make(chan struct{}, 4)
	releaseSlow := make(chan struct{})

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "flaky") {
			c := reqCount.Add(1)
			if c == 1 {
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			}
		}
		if strings.Contains(r.URL.Path, "slow") {
			w.Header().Set("ETag", `"slow-v1"`)
			w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(payload[:len(payload)/2])
			if fl, ok := w.(http.Flusher); ok {
				fl.Flush()
			}
			select {
			case slowStarted <- struct{}{}:
			default:
			}
			select {
			case <-releaseSlow:
				_, _ = w.Write(payload[len(payload)/2:])
			case <-r.Context().Done():
			}
			return
		}
		w.Header().Set("ETag", `"ok-v1"`)
		w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
	}))
	defer srv.Close()
	defer close(releaseSlow)

	prov := &stubProvider{
		id: "AudiusMusicClient",
		resolve: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			path := "/ok.mp3"
			if track.SourceID == "flaky-1" {
				path = "/flaky.mp3"
			} else if track.SourceID == "slow-1" {
				path = "/slow.mp3"
			}
			for _, v := range track.Variants {
				if v.VariantID == policy.PreferredVariantID || v.UpstreamID == policy.PreferredVariantID || policy.PreferredVariantID == "" {
					return []model.ResolvedMedia{{VariantID: v.VariantID, Variant: v, URL: srv.URL + path, Codec: v.Codec}}, nil
				}
			}
			return nil, errors.New("no variant")
		},
	}

	var orgCalled atomic.Bool
	st, svc, canon, _ := setupDownloadEnv(t, prov, func(o *Options) {
		o.AutoOrganizeRunner = func(ctx context.Context, jobID string, dl model.Download) error {
			orgCalled.Store(true)
			return errors.New("organize failed intentionally")
		}
	})
	ctx := context.Background()
	actor := auth.WebAdminActor("admin")
	otherActor := auth.ChannelActor("telegram", "u2", auth.PermRead, auth.PermLocalWrite)
	snap := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())

	// 1. Flaky download retries and finishes complete even when AutoOrganize fails.
	pubFlaky := seedTrack(t, st, "AudiusMusicClient", "flaky-1", "FlakySong", "ArtistA")
	res1, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{
		PublicID:       pubFlaky,
		QualityID:      "mp3_320",
		IdempotencyKey: "idem-flaky",
		RequestHash:    "h1",
		AutoOrganize:   &AutoOrganizeConfig{Enabled: true, Action: "both"},
	})
	if err != nil {
		t.Fatalf("enqueue flaky: %v", err)
	}
	done1 := waitForStatus(t, svc, res1.Download.ID, model.DownloadComplete, 5*time.Second)
	if done1.Attempt < 2 || done1.File == nil {
		t.Fatalf("expected retry attempt >= 2 and published file token, got attempt=%d file=%v", done1.Attempt, done1.File)
	}
	// Wait briefly for async AutoOrganizeRunner to fail and verify download remains complete.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && !orgCalled.Load() {
		time.Sleep(10 * time.Millisecond)
	}
	dto, err := svc.ToDTO(ctx, done1)
	if err != nil {
		t.Fatal(err)
	}
	if dto.Status != "complete" || dto.Processing == nil {
		t.Fatalf("expected complete DTO with independent processing, got %+v", dto)
	}
	// Check sidecar .lrc was published alongside audio.
	lrcPath := filepath.Join(canon, "AudiusMusicClient", "FlakySong - ArtistA.lrc")
	if _, err := os.Stat(lrcPath); err != nil {
		t.Fatalf("expected sidecar lrc at %s: %v", lrcPath, err)
	}

	// 2. Concurrent duplicate enqueue reuses active/complete; different quality creates separate job.
	var wg sync.WaitGroup
	pubOK := seedTrack(t, st, "AudiusMusicClient", "ok-1", "DedupeSong", "ArtistB")
	results := make([]store.CreateDownloadResult, 5)
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			r, e := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pubOK, QualityID: "mp3_320"})
			if e == nil {
				results[idx] = r
			}
		}(i)
	}
	wg.Wait()
	ids := map[string]bool{}
	for _, r := range results {
		if r.Download.ID != "" {
			ids[r.Download.ID] = true
		}
	}
	if len(ids) != 1 {
		t.Fatalf("expected 1 deduped download id, got %v", ids)
	}
	waitForStatus(t, svc, results[0].Download.ID, model.DownloadComplete, 5*time.Second)

	// Different quality creates distinct download.
	resDiffQ, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pubOK, QualityID: "flac_sq"})
	if err != nil || !resDiffQ.Created || resDiffQ.Download.ID == results[0].Download.ID {
		t.Fatalf("different quality should create separate task, err=%v res=%+v", err, resDiffQ)
	}
	waitForStatus(t, svc, resDiffQ.Download.ID, model.DownloadComplete, 5*time.Second)

	// 3. force_new=true requires intent_nonce (400), with nonce creates new task.
	if _, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pubOK, QualityID: "mp3_320", ForceNew: true}); err == nil {
		t.Fatalf("expected 400 when force_new=true without intent_nonce")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != 400 {
		t.Fatalf("expected 400 error, got %v", err)
	}
	resForce, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{
		PublicID: pubOK, QualityID: "mp3_320", ForceNew: true, IntentNonce: "nonce-1",
	})
	if err != nil || !resForce.Created || resForce.Download.ID == results[0].Download.ID {
		t.Fatalf("expected force_new with nonce to create distinct task, err=%v res=%+v", err, resForce)
	}
	waitForStatus(t, svc, resForce.Download.ID, model.DownloadComplete, 5*time.Second)

	// 4. Pause & Cancel + ownership check.
	pubSlow := seedTrack(t, st, "AudiusMusicClient", "slow-1", "SlowSong", "ArtistC")
	resSlow, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pubSlow, QualityID: "mp3_320"})
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-slowStarted:
	case <-time.After(10 * time.Second):
		dlNow, _, _ := svc.Get(ctx, resSlow.Download.ID)
		t.Fatalf("timed out waiting for slow stream to start (status=%s detail=%q)", dlNow.Status, dlNow.Detail)
	}
	// Wait until initial chunk is received and checkpointed/reported.
	chunkDeadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(chunkDeadline) {
		if cur, ok, _ := svc.Get(ctx, resSlow.Download.ID); ok && cur.Bytes > 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if _, err := svc.Pause(ctx, otherActor, resSlow.Download.ID); err == nil {
		t.Fatalf("expected non-owner pause to fail with 403")
	}
	paused, err := svc.Pause(ctx, actor, resSlow.Download.ID)
	if err != nil || paused.Status != model.DownloadPaused {
		t.Fatalf("expected paused status, err=%v status=%s", err, paused.Status)
	}
	if cp, ok, err := st.GetTransferCheckpoint(ctx, resSlow.Download.ID); err != nil || !ok || cp.Checkpoint.Bytes == 0 {
		t.Fatalf("expected durable checkpoint bytes > 0 on pause, ok=%v cp=%+v err=%v", ok, cp, err)
	}
	cancelled, err := svc.Cancel(ctx, actor, resSlow.Download.ID)
	if err != nil || cancelled.Status != model.DownloadCancelled {
		t.Fatalf("expected cancelled, err=%v status=%s", err, cancelled.Status)
	}
	if _, err := os.Stat(svc.partPath(resSlow.Download.ID)); !os.IsNotExist(err) {
		t.Fatalf("expected .part file removed on cancel")
	}
}

func TestDownloadCancelDuringPublishingWaitsAndPreservesCommittedAsset(t *testing.T) {
	payload := sampleMP3Bytes(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
	}))
	defer srv.Close()

	prov := &stubProvider{
		id: "AudiusMusicClient",
		resolve: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			return []model.ResolvedMedia{{VariantID: "mp3_320", Variant: track.Variants[0], URL: srv.URL + "/p.mp3", Codec: "mp3"}}, nil
		},
	}
	enteredPub := make(chan struct{})
	releasePub := make(chan struct{})
	st, svc, _, _ := setupDownloadEnv(t, prov, func(o *Options) {
		o.TestHookBeforePublish = func(ctx context.Context, dl model.Download) {
			close(enteredPub)
			<-releasePub
		}
	})
	ctx := context.Background()
	actor := auth.WebAdminActor("admin")
	snap := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())
	pubID := seedTrack(t, st, "AudiusMusicClient", "pub-1", "PubSong", "ArtistD")

	res, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pubID, QualityID: "mp3_320"})
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-enteredPub:
	case <-time.After(10 * time.Second):
		dlNow, _, _ := svc.Get(ctx, res.Download.ID)
		t.Fatalf("timed out waiting for TestHookBeforePublish (status=%s detail=%q)", dlNow.Status, dlNow.Detail)
	}

	cancelDone := make(chan model.Download, 1)
	go func() {
		dl, _ := svc.Cancel(ctx, actor, res.Download.ID)
		cancelDone <- dl
	}()
	time.Sleep(40 * time.Millisecond)
	select {
	case <-cancelDone:
		t.Fatalf("cancel returned before publishing transaction finished")
	default:
	}
	close(releasePub)
	final := <-cancelDone
	if final.Status != model.DownloadComplete {
		t.Fatalf("expected publishing commit to complete cleanly instead of cancelling committed asset, got %s", final.Status)
	}

	// Stale CAS update with old revision must be rejected.
	if _, err := st.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID:               res.Download.ID,
		ExpectedRevision: 1,
		AllowedStates:    []model.DownloadStatus{model.DownloadDownloading, model.DownloadComplete},
		NewState:         model.DownloadFailed,
	}); err == nil {
		t.Fatalf("expected stale worker revision CAS to fail")
	}
}

func TestDownloadHLSAES128AndMap(t *testing.T) {
	ffmpegPath := findTestFFmpeg()
	if ffmpegPath == "" {
		t.Skip("ffmpeg not available on host")
	}
	// Generate a tiny valid MPEG-TS AAC audio segment via ffmpeg.
	tmp := t.TempDir()
	rawTS := filepath.Join(tmp, "seg.ts")
	cmd := exec.Command(ffmpegPath, "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.4", "-c:a", "aac", "-f", "mpegts", rawTS)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate ts: %v %s", err, string(out))
	}
	plainSeg, err := os.ReadFile(rawTS)
	if err != nil {
		t.Fatal(err)
	}

	aesKey := []byte("0123456789abcdef")
	iv := []byte("fedcba9876543210")
	pad := aes.BlockSize - (len(plainSeg) % aes.BlockSize)
	padded := append(append([]byte(nil), plainSeg...), bytes.Repeat([]byte{byte(pad)}, pad)...)
	block, _ := aes.NewCipher(aesKey)
	encSeg := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(encSeg, padded)

	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/index.m3u8":
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			fmt.Fprintf(w, "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-KEY:METHOD=AES-128,URI=\"%s/key.bin\",IV=0x%x\n#EXTINF:0.4,\n%s/seg0.ts\n#EXT-X-ENDLIST\n", srv.URL, iv, srv.URL)
		case "/key.bin":
			_, _ = w.Write(aesKey)
		case "/seg0.ts":
			_, _ = w.Write(encSeg)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	prov := &stubProvider{
		id: "AudiusMusicClient",
		resolve: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			return []model.ResolvedMedia{{
				VariantID: "aac_hls",
				Variant:   model.MediaVariant{VariantID: "aac_hls", Label: "AAC HLS", Codec: "m4a", Protocol: "hls"},
				URL:       srv.URL + "/index.m3u8",
				Codec:     "m4a",
			}}, nil
		},
	}
	st, svc, canon, _ := setupDownloadEnv(t, prov, nil)
	ctx := context.Background()
	actor := auth.WebAdminActor("admin")
	snap := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())
	pubID := seedTrack(t, st, "AudiusMusicClient", "hls-1", "HLSSong", "ArtistHLS")

	res, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pubID, QualityID: "aac_hls"})
	if err != nil {
		t.Fatal(err)
	}
	done := waitForStatus(t, svc, res.Download.ID, model.DownloadComplete, 10*time.Second)
	if done.File == nil {
		t.Fatalf("expected published HLS file")
	}
	outFile := filepath.Join(canon, "AudiusMusicClient", "HLSSong - ArtistHLS.m4a")
	if stInfo, err := os.Stat(outFile); err != nil || stInfo.Size() == 0 {
		t.Fatalf("expected muxed m4a output at %s, err=%v", outFile, err)
	}
}

func TestDownloadRejectsCorruptAudioWhenFFmpegAvailable(t *testing.T) {
	if findTestFFmpeg() == "" {
		t.Skip("ffmpeg/ffprobe not available on host")
	}
	corrupt := append([]byte("ID3\x03\x00\x00\x00\x00\x00\x00"), bytes.Repeat([]byte("MP3FRAME"), 512)...)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(len(corrupt)))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(corrupt)
	}))
	defer srv.Close()

	prov := &stubProvider{
		id: "AudiusMusicClient",
		resolve: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			return []model.ResolvedMedia{{
				VariantID: "mp3_320",
				Variant:   track.Variants[0],
				URL:       srv.URL + "/corrupt.mp3",
				Codec:     "mp3",
			}}, nil
		},
	}
	st, svc, _, _ := setupDownloadEnv(t, prov, nil)
	ctx := context.Background()
	actor := auth.WebAdminActor("admin")
	snap := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())
	pubID := seedTrack(t, st, "AudiusMusicClient", "corrupt-1", "CorruptSong", "ArtistX")

	res, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pubID, QualityID: "mp3_320"})
	if err != nil {
		t.Fatal(err)
	}
	done := waitForStatus(t, svc, res.Download.ID, model.DownloadFailed, 10*time.Second)
	if done.File != nil {
		t.Fatalf("corrupt audio must not publish file, got %v", done.File)
	}
}

func TestToDTOPreservesSizeMetadataAndVariantFallback(t *testing.T) {
	prov := &stubProvider{
		id: "StubMusicClient",
		resolve: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			return nil, errors.New("unused")
		},
	}
	st, svc, _, _ := setupDownloadEnv(t, prov, nil)
	ctx := context.Background()

	// 1. MetadataJSON carries size_bytes and size_label.
	trackID1, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source:       "StubMusicClient",
		Locator:      "loc-meta-size",
		RootSource:   "StubMusicClient",
		Title:        "SizeMetaTrack",
		Artist:       "ArtistA",
		Album:        "AlbumA",
		DurationMS:   180000,
		MetadataJSON: `{"size_bytes":24576000,"size_label":"23.4 MB"}`,
	}, []model.MediaVariant{{VariantID: "v_flac", Label: "FLAC", Codec: "flac", Lossless: true, Availability: "full"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := st.PutPublicRef(ctx, model.PublicRef{
		PublicID: "pub-meta-size",
		Kind:     "candidate",
		TargetID: trackID1,
		Source:   "StubMusicClient",
		SourceID: "loc-meta-size",
	}, "v_flac"); err != nil {
		t.Fatal(err)
	}
	dto1, err := svc.ToDTO(ctx, model.Download{
		ID:             "dl-meta-1",
		PublicID:       "pub-meta-size",
		QualityID:      "v_flac",
		DownloadSource: "StubMusicClient",
		Status:         model.DownloadQueued,
	})
	if err != nil {
		t.Fatal(err)
	}
	if dto1.Track.SizeBytes == nil || *dto1.Track.SizeBytes != 24576000 {
		t.Fatalf("expected SizeBytes=24576000 from MetadataJSON, got %v", dto1.Track.SizeBytes)
	}
	if dto1.Track.SizeLabel == nil || *dto1.Track.SizeLabel != "23.4 MB" {
		t.Fatalf("expected SizeLabel=23.4 MB from MetadataJSON, got %v", dto1.Track.SizeLabel)
	}

	// 2. Fallback to variant SizeBytes when MetadataJSON has no size.
	varSz := int64(9876543)
	trackID2, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source:       "StubMusicClient",
		Locator:      "loc-variant-size",
		RootSource:   "StubMusicClient",
		Title:        "VariantSizeTrack",
		Artist:       "ArtistB",
		Album:        "AlbumB",
		DurationMS:   200000,
		MetadataJSON: `{}`,
	}, []model.MediaVariant{{VariantID: "v_mp3", Label: "320k", Codec: "mp3", SizeBytes: &varSz, Availability: "full"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := st.PutPublicRef(ctx, model.PublicRef{
		PublicID: "pub-variant-size",
		Kind:     "candidate",
		TargetID: trackID2,
		Source:   "StubMusicClient",
		SourceID: "loc-variant-size",
	}, "v_mp3"); err != nil {
		t.Fatal(err)
	}
	dto2, err := svc.ToDTO(ctx, model.Download{
		ID:             "dl-var-1",
		PublicID:       "pub-variant-size",
		QualityID:      "v_mp3",
		DownloadSource: "StubMusicClient",
		Status:         model.DownloadQueued,
	})
	if err != nil {
		t.Fatal(err)
	}
	if dto2.Track.SizeBytes == nil || *dto2.Track.SizeBytes != varSz {
		t.Fatalf("expected SizeBytes=%d fallback from variant, got %v", varSz, dto2.Track.SizeBytes)
	}
}

func TestDownloadRejectsGoDefaultUAAndUsesBrowserUA(t *testing.T) {
	payload := sampleMP3Bytes(t)
	coverBytes := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F'}
	var audioUA, audioReferer, emptyHdr, coverUA atomic.Value

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ua := r.Header.Get("User-Agent")
		if ua == "" || strings.Contains(ua, "Go-http-client") {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		if r.URL.Path == "/cover.jpg" {
			coverUA.Store(ua)
			w.Header().Set("Content-Type", "image/jpeg")
			w.Header().Set("Content-Length", strconv.Itoa(len(coverBytes)))
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(coverBytes)
			return
		}
		audioUA.Store(ua)
		audioReferer.Store(r.Header.Get("Referer"))
		if vals := r.Header.Values("X-Empty"); len(vals) > 0 {
			emptyHdr.Store(strings.Join(vals, ","))
		} else {
			emptyHdr.Store("")
		}
		w.Header().Set("Content-Type", "audio/mpeg")
		w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
	}))
	defer srv.Close()

	prov := &stubProvider{
		id: "AudiusMusicClient",
		resolve: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			return []model.ResolvedMedia{{
				VariantID: "mp3_320",
				Variant:   track.Variants[0],
				URL:       srv.URL + "/audio.mp3",
				Codec:     "mp3",
				Headers: map[string]string{
					"User-Agent": "   ",
					"X-Empty":    "",
					"Referer":    "https://music.163.com/",
				},
			}}, nil
		},
	}

	st, svc, _, _ := setupDownloadEnv(t, prov, nil)
	ctx := context.Background()
	actor := auth.WebAdminActor("admin")
	snap := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())

	br320 := 320
	metaJSON := fmt.Sprintf(`{"cover":%q}`, srv.URL+"/cover.jpg")
	trackID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
		Source:       "AudiusMusicClient",
		Locator:      "ua-check-1",
		RootSource:   "StubMusicClient",
		Title:        "UASong",
		Artist:       "ArtistUA",
		Album:        "AlbumUA",
		DurationMS:   120000,
		MetadataJSON: metaJSON,
	}, []model.MediaVariant{{VariantID: "ua_mp3_320", UpstreamID: "mp3_320", Label: "320kbps MP3", Codec: "mp3", Bitrate: &br320, Availability: "full"}})
	if err != nil {
		t.Fatal(err)
	}
	pubID := "pub-ua-check-1"
	if err := st.PutPublicRef(ctx, model.PublicRef{
		PublicID: pubID, Kind: "candidate", TargetID: trackID, Source: "AudiusMusicClient", SourceID: "ua-check-1",
	}, "ua_mp3_320"); err != nil {
		t.Fatal(err)
	}

	res, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pubID, QualityID: "mp3_320"})
	if err != nil {
		t.Fatalf("enqueue: %v", err)
	}
	done := waitForStatus(t, svc, res.Download.ID, model.DownloadComplete, 5*time.Second)
	if done.File == nil {
		t.Fatalf("expected downloaded file token, got nil")
	}
	if got, _ := audioUA.Load().(string); got != provider.DefaultUserAgent {
		t.Fatalf("expected audio User-Agent %q, got %q", provider.DefaultUserAgent, got)
	}
	if got, _ := audioReferer.Load().(string); got != "https://music.163.com/" {
		t.Fatalf("expected Referer preserved, got %q", got)
	}
	if got, _ := emptyHdr.Load().(string); got != "" {
		t.Fatalf("expected empty X-Empty header skipped, got %q", got)
	}
	if got, _ := coverUA.Load().(string); got != provider.DefaultUserAgent {
		t.Fatalf("expected cover User-Agent %q, got %q", provider.DefaultUserAgent, got)
	}

	// Verify downloadHLS also injects DefaultUserAgent and preserves 403 status as ErrAuthRequired.
	hlsSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("User-Agent") != provider.DefaultUserAgent {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if len(r.Header.Values("X-Blank")) > 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusForbidden)
	}))
	defer hlsSrv.Close()
	_, _, hlsErr := downloadHLS(
		ctx, svc.pool, svc.runner,
		model.ResolvedMedia{
			URL:     hlsSrv.URL + "/index.m3u8",
			Headers: map[string]string{"User-Agent": "", "X-Blank": "  "},
		},
		provider.RequestContext{Config: snap},
		filepath.Join(t.TempDir(), "hls"),
		"hls-ua-test",
		10<<20,
		5*time.Second,
		nil,
	)
	de, ok := model.AsDomainError(hlsErr)
	if !ok || de.Status != http.StatusForbidden || de.Code != model.ErrAuthRequired {
		t.Fatalf("expected HLS 403 ErrAuthRequired after sending DefaultUserAgent, got %v", hlsErr)
	}
}

func TestDownloadFallbackToNextCandidateOn403AndUpdatesBytesTotal(t *testing.T) {
	flacPayload := sampleMP3Bytes(t)
	mp3Payload := sampleWAVBytes()
	var hitOrder []string
	var hitMu sync.Mutex

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hitMu.Lock()
		hitOrder = append(hitOrder, r.URL.Path)
		hitMu.Unlock()
		switch r.URL.Path {
		case "/primary-403.flac":
			w.WriteHeader(http.StatusForbidden)
		case "/primary-404.flac":
			w.WriteHeader(http.StatusNotFound)
		case "/alt-410.flac":
			w.WriteHeader(http.StatusGone)
		case "/fallback-mp3.mp3":
			w.Header().Set("Content-Type", "audio/mpeg")
			w.Header().Set("Content-Length", strconv.Itoa(len(mp3Payload)))
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(mp3Payload)
		case "/fallback-flac.flac":
			w.Header().Set("Content-Type", "audio/flac")
			w.Header().Set("Content-Length", strconv.Itoa(len(flacPayload)))
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(flacPayload)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	var logMu sync.Mutex
	var logs []string
	var scenario atomic.Int32
	primarySize := int64(999999)
	flacFallbackSize := int64(len(flacPayload))
	mp3FallbackSize := int64(len(mp3Payload))

	prov := &stubProvider{
		id: "AudiusMusicClient",
		resolve: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			switch scenario.Load() {
			case 0:
				return []model.ResolvedMedia{
					{
						VariantID: "flac_sq",
						Variant:   model.MediaVariant{VariantID: "flac_sq", Label: "FLAC", Codec: "flac", Lossless: true},
						URL:       srv.URL + "/primary-403.flac",
						Codec:     "flac",
						SizeBytes: &primarySize,
					},
					{
						VariantID: "mp3_320",
						Variant:   model.MediaVariant{VariantID: "mp3_320", Label: "MP3", Codec: "mp3", Lossless: false},
						URL:       srv.URL + "/fallback-mp3.mp3",
						Codec:     "mp3",
						SizeBytes: &mp3FallbackSize,
					},
					{
						VariantID: "flac_alt",
						Variant:   model.MediaVariant{VariantID: "flac_alt", Label: "FLAC Alt", Codec: "flac", Lossless: true},
						URL:       srv.URL + "/fallback-flac.flac",
						Codec:     "flac",
						SizeBytes: &flacFallbackSize,
					},
				}, nil
			case 1:
				return []model.ResolvedMedia{
					{
						VariantID: "flac_sq",
						Variant:   model.MediaVariant{VariantID: "flac_sq", Label: "FLAC", Codec: "flac", Lossless: true},
						URL:       srv.URL + "/primary-404.flac",
						Codec:     "flac",
						SizeBytes: &primarySize,
					},
					{
						VariantID: "flac_alt_410",
						Variant:   model.MediaVariant{VariantID: "flac_alt_410", Label: "FLAC 410", Codec: "flac", Lossless: true},
						URL:       srv.URL + "/alt-410.flac",
						Codec:     "flac",
					},
					{
						VariantID: "mp3_320",
						Variant:   model.MediaVariant{VariantID: "mp3_320", Label: "MP3", Codec: "mp3", Lossless: false},
						URL:       srv.URL + "/fallback-mp3.mp3",
						Codec:     "mp3",
						SizeBytes: &mp3FallbackSize,
					},
				}, nil
			default:
				return []model.ResolvedMedia{
					{
						VariantID: "flac_sq",
						Variant:   model.MediaVariant{VariantID: "flac_sq", Label: "FLAC", Codec: "flac", Lossless: true},
						URL:       srv.URL + "/primary-403.flac",
						Codec:     "flac",
					},
					{
						VariantID: "flac_alt",
						Variant:   model.MediaVariant{VariantID: "flac_alt", Label: "FLAC Alt", Codec: "flac", Lossless: true},
						URL:       srv.URL + "/alt-410.flac",
						Codec:     "flac",
					},
				}, nil
			}
		},
	}

	st, svc, _, _ := setupDownloadEnv(t, prov, func(o *Options) {
		o.MaxAttempts = 1
		o.Logger = func(level, message, category, taskID string) {
			logMu.Lock()
			logs = append(logs, level+":"+message)
			logMu.Unlock()
		}
	})
	ctx := context.Background()
	actor := auth.WebAdminActor("admin")
	snap := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())

	// Case 1: primary FLAC returns 403 -> prioritizes same Lossless/Codec FLAC fallback over MP3.
	pub1 := seedTrack(t, st, "AudiusMusicClient", "fb-403", "FallbackSong", "ArtistFB")
	res1, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pub1, QualityID: "flac_sq"})
	if err != nil {
		t.Fatal(err)
	}
	done1 := waitForStatus(t, svc, res1.Download.ID, model.DownloadComplete, 5*time.Second)
	if done1.TotalBytes == nil || *done1.TotalBytes != flacFallbackSize {
		t.Fatalf("expected TotalBytes=%d from fallback FLAC, got %v", flacFallbackSize, done1.TotalBytes)
	}
	hitMu.Lock()
	gotHits1 := append([]string(nil), hitOrder...)
	hitOrder = nil
	hitMu.Unlock()
	if len(gotHits1) != 2 || gotHits1[0] != "/primary-403.flac" || gotHits1[1] != "/fallback-flac.flac" {
		t.Fatalf("expected fallback order [/primary-403.flac /fallback-flac.flac], got %v", gotHits1)
	}
	logMu.Lock()
	joinedLogs := strings.Join(logs, "\n")
	logMu.Unlock()
	if !strings.Contains(joinedLogs, "warning:首选下载地址返回 403，尝试同次解析的其他候选地址") {
		t.Fatalf("expected 403 fallback warning log, got:\n%s", joinedLogs)
	}

	// Case 2: 404 -> 410 -> 200 across multiple candidates.
	scenario.Store(1)
	pub2 := seedTrack(t, st, "AudiusMusicClient", "fb-404-410", "Fallback404Song", "ArtistFB")
	res2, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pub2, QualityID: "flac_sq"})
	if err != nil {
		t.Fatal(err)
	}
	done2 := waitForStatus(t, svc, res2.Download.ID, model.DownloadComplete, 5*time.Second)
	if done2.TotalBytes == nil || *done2.TotalBytes != mp3FallbackSize {
		t.Fatalf("expected TotalBytes=%d from third candidate, got %v", mp3FallbackSize, done2.TotalBytes)
	}
	hitMu.Lock()
	gotHits2 := append([]string(nil), hitOrder...)
	hitOrder = nil
	hitMu.Unlock()
	if len(gotHits2) != 3 || gotHits2[0] != "/primary-404.flac" || gotHits2[1] != "/alt-410.flac" || gotHits2[2] != "/fallback-mp3.mp3" {
		t.Fatalf("expected 404->410->200 hit order, got %v", gotHits2)
	}

	// Case 3: all candidates fail -> finishError transitions task to failed when MaxAttempts=1.
	scenario.Store(2)
	pub3 := seedTrack(t, st, "AudiusMusicClient", "fb-all-fail", "AllFailSong", "ArtistFB")
	res3, err := svc.Enqueue(ctx, actor, snap, EnqueueRequest{PublicID: pub3, QualityID: "flac_sq"})
	if err != nil {
		t.Fatal(err)
	}
	done3 := waitForStatus(t, svc, res3.Download.ID, model.DownloadFailed, 5*time.Second)
	if done3.File != nil {
		t.Fatalf("expected no file on total failure, got %v", done3.File)
	}
}
