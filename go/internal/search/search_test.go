package search_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/search"
	"github.com/agonie0v0/melokeep/internal/store"
)

type mockProvider struct {
	id         string
	engineName string
	calls      atomic.Int64
	searchFn   func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error)
	resolveFn  func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error)
}

func (m *mockProvider) ID() string         { return m.id }
func (m *mockProvider) EngineName() string { return m.engineName }
func (m *mockProvider) Capabilities() model.Capability {
	return model.Capability{Tier: "native", Search: true}
}
func (m *mockProvider) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	m.calls.Add(1)
	return m.searchFn(ctx, opts, emit)
}
func (m *mockProvider) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	if m.resolveFn != nil {
		return m.resolveFn(ctx, track, policy)
	}
	return nil, nil
}

func openTestStore(t *testing.T) *store.Store {
	t.Helper()
	dbPath := store.DefaultDBPath(t.TempDir())
	st, err := store.Open(context.Background(), store.Options{Path: dbPath, ReadMaxOpen: 2})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func testSnapshot() config.Snapshot {
	rt := config.DefaultRuntimeConfig()
	rt.Sources = []string{"AudiusMusicClient", "FMAMusicClient"}
	rt.ResultsPerSource = 5
	rt.SearchTimeoutSeconds = 5
	return config.NewSnapshot(rt, config.DefaultResourceBudgets())
}

func readAllEvents(t *testing.T, ctx context.Context, sub *search.Subscription) []search.Event {
	t.Helper()
	var out []search.Event
	for {
		ev, err := sub.Next(ctx)
		if errors.Is(err, io.EOF) {
			return out
		}
		if err != nil {
			t.Fatalf("sub.Next unexpected error: %v", err)
		}
		if ev.Keepalive {
			continue
		}
		out = append(out, ev)
	}
}

func TestSearchEventsContractCompatibilityAndPersistBeforeEmit(t *testing.T) {
	st := openTestStore(t)
	contractPath := filepath.Join("..", "..", "..", "contracts", "v1", "sse", "search_events.json")
	rawContract, err := os.ReadFile(contractPath)
	if err != nil {
		t.Fatalf("read contract fixture: %v", err)
	}
	var fixture struct {
		EventsFull struct {
			Events []struct {
				Event string          `json:"event"`
				ID    int64           `json:"id"`
				Data  json.RawMessage `json:"data"`
			} `json:"events"`
		} `json:"events_full"`
	}
	if err := json.Unmarshal(rawContract, &fixture); err != nil {
		t.Fatalf("unmarshal fixture: %v", err)
	}
	if len(fixture.EventsFull.Events) != 5 {
		t.Fatalf("unexpected fixture event count: %d", len(fixture.EventsFull.Events))
	}

	sampleResolve := func(_ context.Context, track model.ProviderTrack, _ model.QualityPolicy) ([]model.ResolvedMedia, error) {
		sz := int64(32 << 20)
		br := 999
		return []model.ResolvedMedia{{
			VariantID: track.SourceID + ":flac:999:full",
			Variant: model.MediaVariant{
				VariantID:    track.SourceID + ":flac:999:full",
				Label:        "FLAC",
				Codec:        "flac",
				Bitrate:      &br,
				Lossless:     true,
				Availability: "verified",
				Evidence:     "probe",
				SizeBytes:    &sz,
			},
			URL:       "https://cdn.example.com/" + track.SourceID + ".flac",
			Codec:     "flac",
			Bitrate:   &br,
			SizeBytes: &sz,
		}}, nil
	}
	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			err := emit(model.ProviderTrack{
				PublicID: model.NewUUID(),
				Source:   "AudiusMusicClient",
				SourceID: "AudiusMusicClient_01",
				Title:    "Solar Wind",
				Artist:   "Open Horizon",
				Album:    "Open Air Sessions",
				Duration: 210.0,
			})
			return model.SearchSummary{Count: 1}, err
		},
		resolveFn: sampleResolve,
	}
	fma := &mockProvider{
		id:         "FMAMusicClient",
		engineName: "FMAMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			time.Sleep(15 * time.Millisecond) // deterministic ordering after AudiusMusicClient
			err := emit(model.ProviderTrack{
				PublicID: model.NewUUID(),
				Source:   "FMAMusicClient",
				SourceID: "FMAMusicClient_01",
				Title:    "Solar Wind",
				Artist:   "Open Horizon",
				Album:    "Open Air Sessions",
				Duration: 210.0,
			})
			return model.SearchSummary{Count: 1}, err
		},
		resolveFn: sampleResolve,
	}

	timings := search.DefaultTimings()
	timings.BatchPersistInterval = 5 * time.Millisecond
	svc := search.NewService(st, search.MapProviderLookup{
		"AudiusMusicClient": audius,
		"FMAMusicClient":    fma,
	}, config.DefaultResourceBudgets(), search.WithTimings(timings))
	defer svc.Close()

	actor := auth.WebAdminActor("admin")
	snap := testSnapshot()
	ctx := context.Background()

	// Validation errors
	if _, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "   ", Sources: []string{"AudiusMusicClient"}}); err == nil {
		t.Fatal("expected 400 for empty keyword")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest || de.Detail != "关键词或启用音源无效" {
		t.Fatalf("unexpected empty keyword error: %+v", err)
	}
	if _, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "Solar Wind", Sources: []string{"unknown_src"}}); err == nil {
		t.Fatal("expected 400 for unknown source")
	}
	if _, err := svc.Subscribe(ctx, "missing_job", 0, ""); err == nil {
		t.Fatal("expected 404 for unknown job")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusNotFound {
		t.Fatalf("unexpected 404 error: %+v", err)
	}

	// Idempotent delete on missing job
	if err := svc.Cancel(ctx, actor, "missing_job"); err != nil {
		t.Fatalf("expected nil cancel on missing job, got %v", err)
	}

	jobID, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "Solar Wind", Sources: []string{"AudiusMusicClient", "FMAMusicClient"}})
	if err != nil {
		t.Fatalf("CreateSearch: %v", err)
	}
	if _, err := svc.Subscribe(ctx, jobID, 0, "not-an-int"); err == nil {
		t.Fatal("expected 400 for invalid Last-Event-ID")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest || de.Detail != "事件序号无效" {
		t.Fatalf("unexpected bad cursor error: %+v", err)
	}

	sub, err := svc.Subscribe(ctx, jobID, 0, "")
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer sub.Close()

	events := readAllEvents(t, ctx, sub)
	if len(events) != 5 {
		t.Fatalf("expected 5 events, got %d", len(events))
	}
	for i, ev := range events {
		if ev.Seq != int64(i+1) {
			t.Fatalf("expected seq %d, got %d", i+1, ev.Seq)
		}
		if ev.Type == "track" {
			var dto model.TrackDTO
			if err := json.Unmarshal(ev.Data, &dto); err != nil {
				t.Fatalf("unmarshal track dto: %v", err)
			}
			if dto.Playback.State != "ready" || dto.Playback.URL == nil || *dto.Playback.URL != "/api/stream/"+dto.ID {
				t.Fatalf("unexpected playback dto: %+v", dto.Playback)
			}
			if len(dto.Qualities) != 1 || dto.SizeBytes == nil {
				t.Fatalf("expected resolved qualities and size on search track dto: %+v", dto)
			}
			if cached, ok := svc.CachedResolved(dto.ID, snap.Revision); !ok || len(cached) != 1 {
				t.Fatalf("expected cached resolved media for %q", dto.ID)
			}
			if _, _, ok, err := st.GetPublicRef(ctx, dto.ID); err != nil || !ok {
				t.Fatalf("expected public_ref %q to be persisted BEFORE SSE emit (ok=%v err=%v)", dto.ID, ok, err)
			}
		}
	}
	if events[4].Type != "done" {
		t.Fatalf("expected final event done, got %s", events[4].Type)
	}
	var donePayload search.DoneEventPayload
	_ = json.Unmarshal(events[4].Data, &donePayload)
	if donePayload.Cancelled {
		t.Fatal("expected cancelled=false")
	}
	wire := string(events[0].SSEBytes())
	if !strings.HasPrefix(wire, "id: 1\nevent: track\ndata: ") || !strings.HasSuffix(wire, "\n\n") {
		t.Fatalf("unexpected SSE wire format: %q", wire)
	}

	// Replay with Last-Event-ID: 1 -> receives events 2..5
	replaySub, err := svc.Subscribe(ctx, jobID, 0, "1")
	if err != nil {
		t.Fatalf("Subscribe replay: %v", err)
	}
	defer replaySub.Close()
	replayed := readAllEvents(t, ctx, replaySub)
	if len(replayed) != 4 || replayed[0].Seq != 2 || replayed[3].Seq != 5 {
		t.Fatalf("unexpected replay sequence: len=%d first=%v", len(replayed), replayed)
	}
}

func TestActiveJobLimitAndCompletedExpiry(t *testing.T) {
	st := openTestStore(t)
	gate := make(chan struct{})
	var counter atomic.Int64
	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			select {
			case <-gate:
			case <-ctx.Done():
				return model.SearchSummary{}, ctx.Err()
			}
			idx := counter.Add(1)
			_ = emit(model.ProviderTrack{
				PublicID: model.NewUUID(),
				Source:   "AudiusMusicClient",
				SourceID: fmt.Sprintf("id_%d", idx),
				Title:    opts.Keyword,
			})
			return model.SearchSummary{Count: 1}, nil
		},
	}

	nowVal := time.Now()
	var nowMu sync.Mutex
	nowFn := func() time.Time {
		nowMu.Lock()
		defer nowMu.Unlock()
		return nowVal
	}
	advance := func(d time.Duration) {
		nowMu.Lock()
		nowVal = nowVal.Add(d)
		nowMu.Unlock()
	}

	budgets := config.DefaultResourceBudgets()
	budgets.ActiveSearchJobs = 6
	budgets.SearchCompletedTTLSeconds = 10
	timings := search.DefaultTimings()
	timings.CompletedJobTTL = 10 * time.Second
	svc := search.NewService(st, search.MapProviderLookup{"AudiusMusicClient": audius}, budgets,
		search.WithTimings(timings), search.WithNow(nowFn))
	defer svc.Close()

	actor := auth.WebAdminActor("admin")
	snap := testSnapshot()
	ctx := context.Background()

	var jobIDs []string
	for i := 0; i < 6; i++ {
		id, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{
			Keyword: fmt.Sprintf("kw-%d", i),
			Sources: []string{"AudiusMusicClient"},
		})
		if err != nil {
			t.Fatalf("CreateSearch #%d: %v", i, err)
		}
		jobIDs = append(jobIDs, id)
	}
	if _, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "kw-7", Sources: []string{"AudiusMusicClient"}}); err == nil {
		t.Fatal("expected 429 on 7th concurrent job")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusTooManyRequests {
		t.Fatalf("expected 429, got %+v", err)
	}

	close(gate)
	for _, id := range jobIDs {
		sub, err := svc.Subscribe(ctx, id, 0, "")
		if err != nil {
			t.Fatalf("Subscribe %s: %v", id, err)
		}
		_ = readAllEvents(t, ctx, sub)
		sub.Close()
	}

	// Advance past CompletedJobTTL -> expired jobs return 404.
	advance(11 * time.Second)
	if _, err := svc.Subscribe(ctx, jobIDs[0], 0, ""); err == nil {
		t.Fatal("expected 404 after job expiry")
	}
}

func TestSingleflightSharingGraceTimersAndCache(t *testing.T) {
	st := openTestStore(t)
	started := make(chan struct{})
	release := make(chan struct{})
	cancelledUpstream := make(chan struct{}, 4)
	var calls atomic.Int64

	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			calls.Add(1)
			select {
			case started <- struct{}{}:
			default:
			}
			select {
			case <-release:
			case <-ctx.Done():
				cancelledUpstream <- struct{}{}
				return model.SearchSummary{}, ctx.Err()
			}
			_ = emit(model.ProviderTrack{
				PublicID: model.NewUUID(),
				Source:   "AudiusMusicClient",
				SourceID: "sf-1",
				Title:    opts.Keyword,
				Artist:   "Artist",
			})
			return model.SearchSummary{Count: 1}, nil
		},
	}

	timings := search.DefaultTimings()
	timings.NoSubscriberCancel = 80 * time.Millisecond
	timings.LastSubscriberGrace = 80 * time.Millisecond
	timings.BatchPersistInterval = 5 * time.Millisecond
	budgets := config.DefaultResourceBudgets()
	budgets.ActiveSearchJobs = 200
	budgets.SearchCompletedRetention = 250

	svc := search.NewService(st, search.MapProviderLookup{"AudiusMusicClient": audius}, budgets, search.WithTimings(timings))
	defer svc.Close()
	actor := auth.WebAdminActor("admin")
	snap := testSnapshot()
	ctx := context.Background()

	// 1. Singleflight sharing across 10 concurrent jobs (and cancelling 1 does NOT stop upstream).
	for _, n := range []int{1, 10, 100} {
		calls.Store(0)
		release = make(chan struct{})
		started = make(chan struct{}, 1)
		kw := fmt.Sprintf("shared-%d", n)

		ids := make([]string, n)
		subs := make([]*search.Subscription, n)
		for i := 0; i < n; i++ {
			id, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: kw, Sources: []string{"AudiusMusicClient"}, Refresh: i == 0})
			if err != nil {
				t.Fatalf("n=%d CreateSearch[%d]: %v", n, i, err)
			}
			ids[i] = id
			sub, err := svc.Subscribe(ctx, id, 0, "")
			if err != nil {
				t.Fatalf("n=%d Subscribe[%d]: %v", n, i, err)
			}
			subs[i] = sub
		}
		<-started
		if n > 1 {
			// Cancel first job; remaining n-1 jobs keep the single flight alive.
			_ = svc.Cancel(ctx, actor, ids[0])
			subs[0].Close()
		}
		close(release)
		startIdx := 0
		if n > 1 {
			startIdx = 1
		}
		for i := startIdx; i < n; i++ {
			evs := readAllEvents(t, ctx, subs[i])
			subs[i].Close()
			if len(evs) != 3 {
				t.Fatalf("n=%d sub[%d] expected 3 events, got %d", n, i, len(evs))
			}
		}
		if got := calls.Load(); got != 1 {
			t.Fatalf("n=%d expected 1 upstream singleflight call, got %d", n, got)
		}
	}

	// 2. Cache hit replays SSE without calling provider; Refresh=true bypasses cache.
	beforeCalls := calls.Load()
	cacheJob, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "shared-1", Sources: []string{"AudiusMusicClient"}})
	if err != nil {
		t.Fatalf("cache hit CreateSearch: %v", err)
	}
	csub, err := svc.Subscribe(ctx, cacheJob, 0, "")
	if err != nil {
		t.Fatalf("cache hit Subscribe: %v", err)
	}
	cevs := readAllEvents(t, ctx, csub)
	csub.Close()
	if len(cevs) != 3 || calls.Load() != beforeCalls {
		t.Fatalf("expected cache hit with 3 events and 0 new provider calls, got events=%d calls=%d", len(cevs), calls.Load()-beforeCalls)
	}

	// 3. Changing config revision misses cache.
	rt2 := snap.Runtime
	rt2.ResultsPerSource = 9
	snap2 := config.NewSnapshot(rt2, budgets)
	release = make(chan struct{})
	close(release)
	revJob, err := svc.CreateSearch(ctx, actor, snap2, search.CreateRequest{Keyword: "shared-1", Sources: []string{"AudiusMusicClient"}})
	if err != nil {
		t.Fatalf("new revision CreateSearch: %v", err)
	}
	rsub, _ := svc.Subscribe(ctx, revJob, 0, "")
	_ = readAllEvents(t, ctx, rsub)
	rsub.Close()
	if calls.Load() != beforeCalls+1 {
		t.Fatalf("expected new configRevision to trigger upstream search")
	}

	// 4. Last subscriber disconnect -> grace timer cancels upstream.
	release = make(chan struct{})
	started = make(chan struct{}, 1)
	orphanID, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "grace-cancel", Sources: []string{"AudiusMusicClient"}})
	if err != nil {
		t.Fatalf("CreateSearch grace-cancel: %v", err)
	}
	osub, _ := svc.Subscribe(ctx, orphanID, 0, "")
	<-started
	osub.Close()
	select {
	case <-cancelledUpstream:
	case <-time.After(time.Second):
		t.Fatal("expected upstream cancellation after LastSubscriberGrace")
	}

	// 5. No subscriber attached -> NoSubscriberCancel cancels upstream.
	started = make(chan struct{}, 1)
	if _, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "no-sub-cancel", Sources: []string{"AudiusMusicClient"}}); err != nil {
		t.Fatalf("CreateSearch no-sub-cancel: %v", err)
	}
	<-started
	select {
	case <-cancelledUpstream:
	case <-time.After(time.Second):
		t.Fatal("expected upstream cancellation after NoSubscriberCancel")
	}
}

func TestSlowSubscriberIsolationAndReconnect(t *testing.T) {
	st := openTestStore(t)
	const totalTracks = 25
	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			for i := 0; i < totalTracks; i++ {
				if err := emit(model.ProviderTrack{
					PublicID: model.NewUUID(),
					Source:   "AudiusMusicClient",
					SourceID: fmt.Sprintf("slow-%d", i),
					Title:    fmt.Sprintf("Track %d", i),
				}); err != nil {
					return model.SearchSummary{}, err
				}
				time.Sleep(2 * time.Millisecond)
			}
			return model.SearchSummary{Count: totalTracks}, nil
		},
	}
	timings := search.DefaultTimings()
	timings.BatchPersistInterval = 2 * time.Millisecond
	timings.BatchPersistSize = 5
	svc := search.NewService(st, search.MapProviderLookup{"AudiusMusicClient": audius},
		config.DefaultResourceBudgets(), search.WithTimings(timings), search.WithSubscriberBuffer(5))
	defer svc.Close()

	actor := auth.WebAdminActor("admin")
	snap := testSnapshot()
	ctx := context.Background()

	jobID, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "slow-test", Sources: []string{"AudiusMusicClient"}})
	if err != nil {
		t.Fatalf("CreateSearch: %v", err)
	}
	fastSub, _ := svc.Subscribe(ctx, jobID, 0, "")
	slowSub, _ := svc.Subscribe(ctx, jobID, 0, "")
	defer fastSub.Close()
	defer slowSub.Close()

	// Fast subscriber drains all 25 tracks + source + done = 27 events without blocking.
	fastEvs := readAllEvents(t, ctx, fastSub)
	if len(fastEvs) != totalTracks+2 {
		t.Fatalf("fast subscriber expected %d events, got %d", totalTracks+2, len(fastEvs))
	}

	// Slow subscriber never read during live emission -> gets ErrSlowSubscriber.
	if _, err := slowSub.Next(ctx); !errors.Is(err, search.ErrSlowSubscriber) {
		t.Fatalf("expected ErrSlowSubscriber, got %v", err)
	}

	// Reconnecting with Last-Event-ID recovers remaining events from bounded job log.
	reconnected, err := svc.Subscribe(ctx, jobID, 0, "10")
	if err != nil {
		t.Fatalf("reconnect Subscribe: %v", err)
	}
	defer reconnected.Close()
	missed := readAllEvents(t, ctx, reconnected)
	if len(missed) != (totalTracks+2)-10 || missed[0].Seq != 11 {
		t.Fatalf("expected 17 replayed events starting at 11, got len=%d first=%v", len(missed), missed[0].Seq)
	}
}

func TestEventLogBudgetCaps(t *testing.T) {
	st := openTestStore(t)
	bigTitle := strings.Repeat("A", 300)
	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			for i := 0; i < 100; i++ {
				if err := emit(model.ProviderTrack{
					PublicID: model.NewUUID(),
					Source:   "AudiusMusicClient",
					SourceID: fmt.Sprintf("cap-%d", i),
					Title:    bigTitle,
				}); err != nil {
					return model.SearchSummary{}, err
				}
			}
			return model.SearchSummary{Count: 100}, nil
		},
	}

	timings := search.DefaultTimings()
	timings.BatchPersistInterval = 2 * time.Millisecond
	timings.BatchPersistSize = 5
	// Per-job log cap 5 KiB (with 2 KiB terminal reserve) -> stops cleanly with budget source error + done.
	svc := search.NewService(st, search.MapProviderLookup{"AudiusMusicClient": audius},
		config.DefaultResourceBudgets(), search.WithTimings(timings), search.WithMaxJobLogBytes(5*1024))
	defer svc.Close()

	ctx := context.Background()
	jobID, err := svc.CreateSearch(ctx, auth.WebAdminActor("admin"), testSnapshot(), search.CreateRequest{
		Keyword: "budget",
		Sources: []string{"AudiusMusicClient"},
	})
	if err != nil {
		t.Fatalf("CreateSearch: %v", err)
	}
	sub, _ := svc.Subscribe(ctx, jobID, 0, "")
	defer sub.Close()

	evs := readAllEvents(t, ctx, sub)
	if len(evs) < 2 {
		t.Fatalf("expected capacity error + done events, got %d", len(evs))
	}
	last := evs[len(evs)-1]
	prev := evs[len(evs)-2]
	if last.Type != "done" || prev.Type != "source" {
		t.Fatalf("expected source failure before done, got prev=%s last=%s", prev.Type, last.Type)
	}
	var srcPayload search.SourceEventPayload
	_ = json.Unmarshal(prev.Data, &srcPayload)
	if srcPayload.Status.State != "failed" || !strings.Contains(srcPayload.Status.Detail, "容量上限") {
		t.Fatalf("expected readable capacity detail, got %+v", srcPayload)
	}
	sum, ok := svc.GetJob(ctx, jobID)
	if !ok || sum.State != search.JobCapacityExceeded {
		t.Fatalf("expected JobCapacityExceeded state, got %+v (ok=%v)", sum, ok)
	}

	// Single event exceeding 256 KiB triggers capacity abort cleanly.
	huge := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			_ = emit(model.ProviderTrack{
				PublicID: model.NewUUID(),
				Source:   "AudiusMusicClient",
				SourceID: "huge-1",
				Title:    strings.Repeat("X", 300*1024),
			})
			return model.SearchSummary{Count: 1}, nil
		},
	}
	svcHuge := search.NewService(st, search.MapProviderLookup{"AudiusMusicClient": huge},
		config.DefaultResourceBudgets(), search.WithTimings(timings), search.WithMaxComponentLogBytes(32<<20))
	defer svcHuge.Close()
	hID, err := svcHuge.CreateSearch(ctx, auth.WebAdminActor("admin"), testSnapshot(), search.CreateRequest{
		Keyword: "huge-event",
		Sources: []string{"AudiusMusicClient"},
	})
	if err != nil {
		t.Fatalf("CreateSearch huge: %v", err)
	}
	hSub, _ := svcHuge.Subscribe(ctx, hID, 0, "")
	hEvs := readAllEvents(t, ctx, hSub)
	hSub.Close()
	if len(hEvs) < 2 || hEvs[len(hEvs)-1].Type != "done" {
		t.Fatalf("expected terminal done for oversized single event, got %d events", len(hEvs))
	}
	if hSum, ok := svcHuge.GetJob(ctx, hID); !ok || hSum.State != search.JobCapacityExceeded {
		t.Fatalf("expected JobCapacityExceeded for oversized event, got %+v", hSum)
	}

	// Component-wide log budget triggers capacity abort when shared across jobs.
	svcComp := search.NewService(st, search.MapProviderLookup{"AudiusMusicClient": audius},
		config.DefaultResourceBudgets(), search.WithTimings(timings),
		search.WithMaxJobLogBytes(64*1024), search.WithMaxComponentLogBytes(4*1024))
	defer svcComp.Close()
	cID, err := svcComp.CreateSearch(ctx, auth.WebAdminActor("admin"), testSnapshot(), search.CreateRequest{
		Keyword: "comp-budget",
		Sources: []string{"AudiusMusicClient"},
	})
	if err != nil {
		t.Fatalf("CreateSearch comp: %v", err)
	}
	cSub, _ := svcComp.Subscribe(ctx, cID, 0, "")
	_ = readAllEvents(t, ctx, cSub)
	cSub.Close()
	if cSum, ok := svcComp.GetJob(ctx, cID); !ok || cSum.State != search.JobCapacityExceeded {
		t.Fatalf("expected JobCapacityExceeded on component budget, got %+v", cSum)
	}
}

func TestPartialSourceFailureKeepaliveAndNoCacheOnFailure(t *testing.T) {
	st := openTestStore(t)
	var failCalls atomic.Int64
	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			failCalls.Add(1)
			_ = emit(model.ProviderTrack{
				PublicID: model.NewUUID(),
				Source:   "AudiusMusicClient",
				SourceID: "partial-1",
				Title:    "Partial One",
			})
			time.Sleep(45 * time.Millisecond) // triggers keepalive first
			return model.SearchSummary{}, model.NewError(model.ErrDeadline, http.StatusGatewayTimeout, "timeout")
		},
	}
	fma := &mockProvider{
		id:         "FMAMusicClient",
		engineName: "FMAMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			time.Sleep(45 * time.Millisecond)
			_ = emit(model.ProviderTrack{
				PublicID: model.NewUUID(),
				Source:   "FMAMusicClient",
				SourceID: "ok-1",
				Title:    "OK One",
			})
			return model.SearchSummary{Count: 1}, nil
		},
	}

	timings := search.DefaultTimings()
	timings.KeepaliveInterval = 15 * time.Millisecond
	timings.BatchPersistInterval = 5 * time.Millisecond
	svc := search.NewService(st, search.MapProviderLookup{
		"AudiusMusicClient": audius,
		"FMAMusicClient":    fma,
	}, config.DefaultResourceBudgets(), search.WithTimings(timings))
	defer svc.Close()

	ctx := context.Background()
	snap := testSnapshot()
	actor := auth.WebAdminActor("admin")
	jobID, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{
		Keyword: "partial",
		Sources: []string{"AudiusMusicClient", "FMAMusicClient"},
	})
	if err != nil {
		t.Fatalf("CreateSearch: %v", err)
	}
	sub, _ := svc.Subscribe(ctx, jobID, 0, "")
	defer sub.Close()

	var keepalives int
	var evs []search.Event
	for {
		ev, err := sub.Next(ctx)
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatalf("sub.Next: %v", err)
		}
		if ev.Keepalive {
			keepalives++
			continue
		}
		evs = append(evs, ev)
	}
	if keepalives == 0 {
		t.Fatal("expected at least one keepalive frame")
	}
	sum, _ := svc.GetJob(ctx, jobID)
	if sum.State != search.JobPartial {
		t.Fatalf("expected JobPartial state, got %s", sum.State)
	}

	foundTimeoutDetail := false
	for _, ev := range evs {
		if ev.Type == "source" {
			var sp search.SourceEventPayload
			_ = json.Unmarshal(ev.Data, &sp)
			if sp.ID == "AudiusMusicClient" && sp.Status.State == "failed" && strings.Contains(sp.Status.Detail, "已返回 1 条结果") {
				foundTimeoutDetail = true
			}
		}
	}
	if !foundTimeoutDetail {
		t.Fatal("expected AudiusMusicClient source failed event preserving partial count detail")
	}

	// Failed AudiusMusicClient source must NOT be cached; second search invokes AudiusMusicClient again.
	job2, _ := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{Keyword: "partial", Sources: []string{"AudiusMusicClient"}})
	sub2, _ := svc.Subscribe(ctx, job2, 0, "")
	_ = readAllEvents(t, ctx, sub2)
	sub2.Close()
	if failCalls.Load() != 2 {
		t.Fatalf("expected failed source not to be cached (calls=%d)", failCalls.Load())
	}
}

func TestSearchPassesRequestContextSnapshotToProvider(t *testing.T) {
	st := openTestStore(t)
	var got provider.RequestContext
	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			got = provider.RequestContextFrom(ctx)
			return model.SearchSummary{Count: 0}, nil
		},
	}

	svc := search.NewService(st, search.MapProviderLookup{
		"AudiusMusicClient": audius,
	}, config.DefaultResourceBudgets())
	defer svc.Close()

	rt := config.DefaultRuntimeConfig()
	rt.Sources = []string{"AudiusMusicClient"}
	rt.ResultsPerSource = 5
	rt.SearchTimeoutSeconds = 5
	rt.ProxyURL = "http://127.0.0.1:7890"
	rt.ProxyScope = "all"
	rt.SourceSettings["AudiusMusicClient"] = model.SourceSettingDTO{
		Cookie: "audius_cookie=123",
		Proxy:  "http://127.0.0.1:7891",
	}
	rt.SourceSettings["FMAMusicClient"] = model.SourceSettingDTO{
		Cookie: "fma_cookie=456",
		Proxy:  "http://127.0.0.1:7892",
	}
	snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())

	ctx := context.Background()
	actor := auth.WebAdminActor("admin")
	jobID, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{
		Keyword: "context-test",
		Sources: []string{"AudiusMusicClient"},
	})
	if err != nil {
		t.Fatalf("CreateSearch: %v", err)
	}
	sub, err := svc.Subscribe(ctx, jobID, 0, "")
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	_ = readAllEvents(t, ctx, sub)
	sub.Close()

	if got.Config.Revision != snap.Revision {
		t.Fatalf("expected Config.Revision=%q, got %q", snap.Revision, got.Config.Revision)
	}
	if got.Config.Runtime.ProxyURL != rt.ProxyURL || got.Config.Runtime.ProxyScope != rt.ProxyScope {
		t.Fatalf("expected global proxy %q/%q, got %q/%q", rt.ProxyURL, rt.ProxyScope, got.Config.Runtime.ProxyURL, got.Config.Runtime.ProxyScope)
	}
	if got.Config.Runtime.SourceSettings["AudiusMusicClient"].Cookie != "audius_cookie=123" ||
		got.Config.Runtime.SourceSettings["AudiusMusicClient"].Proxy != "http://127.0.0.1:7891" {
		t.Fatalf("unexpected source settings in RequestContext: %+v", got.Config.Runtime.SourceSettings["AudiusMusicClient"])
	}
	if got.Config.Runtime.SourceSettings["FMAMusicClient"].Cookie != "fma_cookie=456" ||
		got.Config.Runtime.SourceSettings["FMAMusicClient"].Proxy != "http://127.0.0.1:7892" {
		t.Fatalf("unexpected isolated source settings for FMAMusicClient: %+v", got.Config.Runtime.SourceSettings["FMAMusicClient"])
	}
}

func TestImmediateCancelDoesNotRaceWaitGroup(t *testing.T) {
	st := openTestStore(t)
	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			<-ctx.Done()
			return model.SearchSummary{}, ctx.Err()
		},
	}
	svc := search.NewService(st, search.MapProviderLookup{
		"AudiusMusicClient": audius,
	}, config.DefaultResourceBudgets())
	defer svc.Close()

	ctx := context.Background()
	snap := testSnapshot()
	actor := auth.WebAdminActor("admin")
	for i := 0; i < 20; i++ {
		jobID, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{
			Keyword: fmt.Sprintf("race-%d", i),
			Sources: []string{"AudiusMusicClient"},
		})
		if err != nil {
			t.Fatalf("CreateSearch[%d]: %v", i, err)
		}
		if err := svc.Cancel(ctx, actor, jobID); err != nil {
			t.Fatalf("Cancel[%d]: %v", i, err)
		}
	}
}

func TestSearchPersistsLyricsAndKeepsTrackOnResolveFailure(t *testing.T) {
	st := openTestStore(t)
	br999 := 999
	sizeBytes := int64(30_000_000)
	audius := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			_ = emit(model.ProviderTrack{
				Source:   "AudiusMusicClient",
				SourceID: "mid-ok",
				Title:    "Morning Breeze",
				Artist:   "Open Horizon",
				Duration: 240,
			})
			_ = emit(model.ProviderTrack{
				Source:   "AudiusMusicClient",
				SourceID: "mid-fail",
				Title:    "Open Sky",
				Artist:   "Open Horizon",
				Duration: 250,
			})
			return model.SearchSummary{Count: 2}, nil
		},
		resolveFn: func(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
			if track.SourceID == "mid-fail" {
				return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "temp unavailable")
			}
			return []model.ResolvedMedia{
				{
					VariantID: "v_flac",
					Variant: model.MediaVariant{
						VariantID: "v_flac",
						Codec:     "flac",
						Lossless:  true,
						Bitrate:   &br999,
						SizeBytes: &sizeBytes,
					},
					URL:       "https://cdn.example.com/a.flac",
					Expiry:    time.Now().Add(time.Minute),
					Codec:     "flac",
					Bitrate:   &br999,
					SizeBytes: &sizeBytes,
					Lyrics:    "[00:01.00]Morning breeze lyric",
				},
			}, nil
		},
	}

	svc := search.NewService(st, search.MapProviderLookup{
		"AudiusMusicClient": audius,
	}, config.DefaultResourceBudgets())
	defer svc.Close()

	ctx := context.Background()
	snap := testSnapshot()
	actor := auth.WebAdminActor("admin")
	jobID, err := svc.CreateSearch(ctx, actor, snap, search.CreateRequest{
		Keyword: "Morning Breeze",
		Sources: []string{"AudiusMusicClient"},
	})
	if err != nil {
		t.Fatalf("CreateSearch: %v", err)
	}
	sub, err := svc.Subscribe(ctx, jobID, 0, "")
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	evts := readAllEvents(t, ctx, sub)
	sub.Close()

	var tracks []model.TrackDTO
	for _, ev := range evts {
		if ev.Type == "track" {
			var td model.TrackDTO
			if err := json.Unmarshal(ev.Data, &td); err == nil {
				tracks = append(tracks, td)
			}
		}
	}
	if len(tracks) != 2 {
		t.Fatalf("expected both resolved and unresolved search tracks preserved, got %d", len(tracks))
	}

	var okTrackID string
	for _, td := range tracks {
		if td.SourceID == "mid-ok" {
			okTrackID = td.ID
		}
	}
	rec, _, ok, err := st.GetProviderTrack(ctx, okTrackID)
	if err != nil || !ok {
		t.Fatalf("GetProviderTrack(%s): ok=%v err=%v", okTrackID, ok, err)
	}
	var meta struct {
		Lyrics string `json:"lyrics"`
	}
	_ = json.Unmarshal([]byte(rec.MetadataJSON), &meta)
	if meta.Lyrics != "[00:01.00]Morning breeze lyric" {
		t.Fatalf("expected lyrics persisted in MetadataJSON, got %q (raw=%s)", meta.Lyrics, rec.MetadataJSON)
	}
}

func TestSearchServiceLogsLifecycle(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	st, err := store.Open(ctx, store.Options{Path: filepath.Join(t.TempDir(), "search_logs.db")})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	defer st.Close()

	var logMu sync.Mutex
	var entries []string
	prov := &mockProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			provider.Log(ctx, "info", "engine", "Audius 模拟抓取")
			_ = emit(model.ProviderTrack{Source: "AudiusMusicClient", SourceID: "m1", RootSource: "AudiusMusicClient", Title: "晨风序曲", Artist: "林风"})
			return model.SearchSummary{Count: 1}, nil
		},
	}

	svc := search.NewService(
		st,
		search.MapProviderLookup{"AudiusMusicClient": prov},
		config.DefaultResourceBudgets(),
		search.WithLogger(func(level, message, category, taskID string) {
			logMu.Lock()
			entries = append(entries, level+"|"+category+"|"+taskID+"|"+message)
			logMu.Unlock()
		}),
	)
	defer svc.Close()

	snap := testSnapshot()
	jobID, err := svc.CreateSearch(ctx, auth.WebAdminActor("admin"), snap, search.CreateRequest{
		Keyword: "晨风序曲",
		Sources: []string{"AudiusMusicClient"},
		Limit:   5,
	})
	if err != nil {
		t.Fatalf("CreateSearch: %v", err)
	}
	sub, err := svc.Subscribe(ctx, jobID, 0, "")
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	_ = readAllEvents(t, ctx, sub)
	sub.Close()

	logMu.Lock()
	joined := strings.Join(entries, "\n")
	logMu.Unlock()
	for _, want := range []string{
		"info|search|" + jobID + "|开始搜索「晨风序曲」",
		"info|engine|" + jobID + "|Audius 模拟抓取",
		"warning|search|" + jobID + "|[AudiusMusicClient] 搜索完成：返回 1 首",
		"info|search|" + jobID + "|搜索结束「晨风序曲」",
	} {
		if !strings.Contains(joined, want) {
			t.Fatalf("missing expected log substring %q in:\n%s", want, joined)
		}
	}
}
