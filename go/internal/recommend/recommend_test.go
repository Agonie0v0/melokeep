package recommend

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/navidrome"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type fakeAI struct {
	mu         sync.Mutex
	configured bool
	calls      int
	replyFn    func(callIdx int, sys, user string) (string, error)
}

func (f *fakeAI) DirectComplete(ctx context.Context, systemPromptText, userPromptText string) (string, bool, error) {
	f.mu.Lock()
	f.calls++
	idx := f.calls
	configured := f.configured
	fn := f.replyFn
	f.mu.Unlock()
	if !configured {
		return "", false, nil
	}
	if fn == nil {
		return "", true, nil
	}
	out, err := fn(idx, systemPromptText, userPromptText)
	return out, true, err
}

type fakeProvider struct {
	id         string
	engineName string
	searchFn   func(ctx context.Context, opts model.SearchOptions) ([]model.ProviderTrack, error)
}

func (p *fakeProvider) ID() string         { return p.id }
func (p *fakeProvider) EngineName() string { return p.engineName }
func (p *fakeProvider) Capabilities() model.Capability {
	return model.Capability{Tier: "plugin", Search: true}
}
func (p *fakeProvider) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	rows, err := p.searchFn(ctx, opts)
	if err != nil {
		return model.SearchSummary{}, err
	}
	count := 0
	for _, r := range rows {
		if opts.Limit > 0 && count >= opts.Limit {
			break
		}
		if emit != nil {
			if eErr := emit(r); eErr != nil {
				return model.SearchSummary{Count: count}, eErr
			}
		}
		count++
	}
	return model.SearchSummary{Count: count}, nil
}
func (p *fakeProvider) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	return nil, nil
}

type fakeCatalog struct {
	providers []model.Provider
}

func (c *fakeCatalog) Get(idOrEngine string) (model.Provider, bool) {
	for _, p := range c.providers {
		if strings.EqualFold(p.ID(), idOrEngine) || strings.EqualFold(p.EngineName(), idOrEngine) {
			return p, true
		}
	}
	return nil, false
}

func (c *fakeCatalog) AvailableSources() []model.AvailableSourceDTO {
	out := make([]model.AvailableSourceDTO, 0, len(c.providers))
	for _, p := range c.providers {
		out = append(out, model.AvailableSourceDTO{
			ID:         p.ID(),
			Label:      p.ID(),
			EngineName: p.EngineName(),
			Origin:     "plugin",
			Capability: &model.SourceCapabilityDTO{Search: true},
		})
	}
	return out
}

type fakeNavidrome struct {
	enabled bool
	starred []model.TrackDTO
}

func (n *fakeNavidrome) Settings() navidrome.Settings {
	return navidrome.Settings{Enabled: n.enabled}
}
func (n *fakeNavidrome) GetAlbums(ctx context.Context, offset, size int, sortBy string) (navidrome.AlbumsPageDTO, error) {
	return navidrome.AlbumsPageDTO{}, nil
}
func (n *fakeNavidrome) GetAlbum(ctx context.Context, id string) (navidrome.AlbumDetailDTO, error) {
	return navidrome.AlbumDetailDTO{}, nil
}
func (n *fakeNavidrome) GetStarredSongs(ctx context.Context, limit int) ([]model.TrackDTO, error) {
	return n.starred, nil
}

func openRecommendTestStore(t *testing.T) *store.Store {
	t.Helper()
	st, err := store.Open(context.Background(), store.Options{
		Path: filepath.Join(t.TempDir(), "go", "melokeep.db"),
	})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func drainJobEvents(t *testing.T, svc *Service, jobID string) ([]Event, DoneEventPayload) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	sub, err := svc.Subscribe(ctx, jobID, 0, "")
	if err != nil {
		t.Fatalf("subscribe: %v", err)
	}
	defer sub.Close()

	var events []Event
	var donePayload DoneEventPayload
	for {
		ev, err := sub.Next(ctx)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("sub.Next: %v", err)
		}
		if ev.Keepalive {
			continue
		}
		events = append(events, ev)
		if ev.Type == "done" {
			if uErr := json.Unmarshal(ev.Data, &donePayload); uErr != nil {
				t.Fatalf("unmarshal done: %v", uErr)
			}
		}
	}
	return events, donePayload
}

func TestScoreCandidateMatch(t *testing.T) {
	cov := "https://img.example.com/cover.jpg"
	exact := model.ProviderTrack{
		Title:    "晴天 (Live)",
		Artist:   "周杰伦",
		Duration: 269,
		Cover:    &cov,
		Variants: []model.MediaVariant{{VariantID: "v1", Lossless: true}},
	}
	if score, ok := ScoreCandidateMatch("晴天", "周杰伦", exact); !ok || score < 70 {
		t.Fatalf("expected high score for matching song (Live penalized moderately), got score=%d ok=%v", score, ok)
	}

	djCover := model.ProviderTrack{
		Title:    "晴天 (DJ版翻唱)",
		Artist:   "某某翻唱",
		Duration: 180,
	}
	if score, ok := ScoreCandidateMatch("晴天", "周杰伦", djCover); ok {
		t.Fatalf("expected DJ cover to be rejected, got score=%d ok=%v", score, ok)
	}

	wrongSong := model.ProviderTrack{
		Title:    "七里香",
		Artist:   "周杰伦",
		Duration: 299,
	}
	if score, ok := ScoreCandidateMatch("晴天", "周杰伦", wrongSong); ok {
		t.Fatalf("expected different title to be rejected, got score=%d ok=%v", score, ok)
	}
}

func TestRecordHistoryDedupeAndCap(t *testing.T) {
	st := openRecommendTestStore(t)
	now := time.Date(2026, 11, 26, 10, 0, 0, 0, time.UTC)
	svc := NewService(Options{
		Store: st,
		Now:   func() time.Time { return now },
	})
	defer svc.Close()

	ctx := context.Background()
	cnt, err := svc.RecordHistory(ctx, HistoryReportRequest{
		TrackID: "mk:1",
		Title:   "Midnight City",
		Artist:  "M83",
		Source:  "melokeep",
	})
	if err != nil || cnt != 1 {
		t.Fatalf("first RecordHistory: cnt=%d err=%v", cnt, err)
	}

	// Same song within 2 minutes deduplicates.
	now = now.Add(30 * time.Second)
	cnt, err = svc.RecordHistory(ctx, HistoryReportRequest{
		Title:    "Midnight City",
		Artist:   "M83",
		Genre:    "Synthwave",
		PlayedMS: 45000,
	})
	if err != nil || cnt != 1 {
		t.Fatalf("dedupe RecordHistory: cnt=%d err=%v", cnt, err)
	}

	list, err := svc.ListHistory(ctx, 10)
	if err != nil || len(list) != 1 || list[0].Genre != "Synthwave" || list[0].PlayedMS != 45000 {
		t.Fatalf("unexpected history list after dedupe: %+v err=%v", list, err)
	}

	// Cap at maxHistoryEntries (200).
	var batch []HistoryEntry
	for i := 0; i < 210; i++ {
		batch = append(batch, HistoryEntry{
			Title:    fmt.Sprintf("Track %d", i),
			Artist:   fmt.Sprintf("Artist %d", i),
			PlayedAt: now.Add(time.Duration(i+1) * time.Minute).UnixMilli(),
		})
	}
	cnt, err = svc.RecordHistory(ctx, HistoryReportRequest{Items: batch})
	if err != nil || cnt != maxHistoryEntries {
		t.Fatalf("expected history capped at %d, got cnt=%d err=%v", maxHistoryEntries, cnt, err)
	}
}

func TestDailyRecommendAIMode8020StreamCacheAndRefresh(t *testing.T) {
	st := openRecommendTestStore(t)
	ctx := context.Background()

	// Seed 10 favorite library tracks (should cap at 20% = 6 familiar library slots for target=30).
	var navStarred []model.TrackDTO
	for i := 1; i <= 10; i++ {
		navStarred = append(navStarred, model.TrackDTO{
			ID:     fmt.Sprintf("navidrome-fav-%d", i),
			Source: "navidrome",
			Title:  fmt.Sprintf("Library Fav %d", i),
			Artist: fmt.Sprintf("Fav Artist %d", i),
		})
	}
	// Add 10 history entries.
	var histItems []HistoryEntry
	for i := 1; i <= 10; i++ {
		histItems = append(histItems, HistoryEntry{
			Title:  fmt.Sprintf("Recent Song %d", i),
			Artist: fmt.Sprintf("Fav Artist %d", i),
			Genre:  "Dream Pop",
		})
	}

	ai := &fakeAI{
		configured: true,
		replyFn: func(callIdx int, sys, user string) (string, error) {
			var cands []candSpec
			offset := (callIdx - 1) * 100
			for i := 1; i <= 36; i++ {
				cands = append(cands, candSpec{
					Title:    fmt.Sprintf("Online Hit %d", offset+i),
					Artist:   fmt.Sprintf("Indie Band %d", offset+i),
					Reason:   fmt.Sprintf("与你常听的 Dream Pop 契合 #%d", offset+i),
					Category: "discover",
				})
			}
			for i := 1; i <= 8; i++ {
				cands = append(cands, candSpec{
					Title:    fmt.Sprintf("Library Fav %d", i),
					Artist:   fmt.Sprintf("Fav Artist %d", i),
					Reason:   "曲库常听红心延展",
					Category: "familiar",
				})
			}
			payload := map[string]any{
				"curator_note": fmt.Sprintf("今日为你精选第 %d 批梦幻流行与全网新声", callIdx),
				"style_tags":   []string{"#DreamPop", "合成器", "深夜循环"},
				"candidates":   cands,
			}
			raw, _ := json.Marshal(payload)
			return "```json\n" + string(raw) + "\n```", nil
		},
	}

	var searchCalls atomic.Int32
	prov := &fakeProvider{
		id:         "open-music",
		engineName: "open-music",
		searchFn: func(ctx context.Context, opts model.SearchOptions) ([]model.ProviderTrack, error) {
			searchCalls.Add(1)
			kw := strings.TrimSpace(opts.Keyword)
			// Parse "Online Hit N Indie Band N"
			if strings.HasPrefix(kw, "Online Hit ") {
				parts := strings.Split(kw, " ")
				if len(parts) >= 6 {
					num := parts[2]
					return []model.ProviderTrack{{
						SourceID: "src-" + num,
						Title:    "Online Hit " + num,
						Artist:   "Indie Band " + num,
						Album:    "Album " + num,
						Duration: 215,
					}}, nil
				}
			}
			return nil, nil
		},
	}

	fixedNow := time.Date(2026, 11, 26, 9, 0, 0, 0, time.Local)
	svc := NewService(Options{
		Store:     st,
		Providers: &fakeCatalog{providers: []model.Provider{prov}},
		AI:        ai,
		Navidrome: &fakeNavidrome{enabled: true, starred: navStarred},
		Now:       func() time.Time { return fixedNow },
	})
	defer svc.Close()

	if _, err := svc.RecordHistory(ctx, HistoryReportRequest{Items: histItems}); err != nil {
		t.Fatalf("RecordHistory: %v", err)
	}

	snap := config.NewSnapshot(config.RuntimeConfig{Sources: []string{"open-music"}}, config.DefaultResourceBudgets())
	started, err := svc.StartDaily(ctx, snap, DailyStartRequest{})
	if err != nil {
		t.Fatalf("StartDaily: %v", err)
	}
	if started.Status != "generating" || started.JobID == "" {
		t.Fatalf("expected generating job, got %+v", started)
	}

	events, done := drainJobEvents(t, svc, started.JobID)
	if len(done.Daily.Items) != 30 {
		t.Fatalf("expected 30 tracks, got %d", len(done.Daily.Items))
	}
	if done.Daily.Mode != "ai" || done.Daily.RefreshSeq != 1 {
		t.Fatalf("expected mode=ai seq=1, got mode=%s seq=%d", done.Daily.Mode, done.Daily.RefreshSeq)
	}
	if len(done.Daily.StyleTags) != 3 || done.Daily.StyleTags[0] != "DreamPop" {
		t.Fatalf("unexpected style_tags: %+v", done.Daily.StyleTags)
	}

	// Verify SSE item order matches final snapshot and check 80/20 split (24 discover + 6 familiar).
	var streamedIDs []string
	for _, ev := range events {
		if ev.Type == "item" {
			var ip ItemEventPayload
			if err := json.Unmarshal(ev.Data, &ip); err != nil {
				t.Fatalf("unmarshal item: %v", err)
			}
			streamedIDs = append(streamedIDs, ip.Item.Track.ID)
		}
	}
	if len(streamedIDs) != 30 {
		t.Fatalf("expected 30 SSE item events, got %d", len(streamedIDs))
	}
	familiarCnt, discoverCnt := 0, 0
	for i, it := range done.Daily.Items {
		if it.Track.ID != streamedIDs[i] {
			t.Fatalf("order mismatch at %d: sse=%s final=%s", i, streamedIDs[i], it.Track.ID)
		}
		if it.Category == "familiar" {
			familiarCnt++
		} else {
			discoverCnt++
		}
		// Verify online items are persisted in store for downstream playback/download.
		if !it.InLibrary {
			if _, _, ok, err := st.GetPublicRef(ctx, it.Track.ID); err != nil || !ok {
				t.Fatalf("missing PublicRef for online track %s: ok=%v err=%v", it.Track.ID, ok, err)
			}
			if it.Track.Playback.State != "pending" {
				t.Fatalf("expected pending lazy playback for online track, got %s", it.Track.Playback.State)
			}
		}
	}
	if familiarCnt != 6 || discoverCnt != 24 {
		t.Fatalf("expected 24 discover / 6 familiar (80/20), got discover=%d familiar=%d", discoverCnt, familiarCnt)
	}

	// Second StartDaily without Force must hit cache instantaneously (no new job, no AI call).
	prevSearchCalls := searchCalls.Load()
	cachedResp, err := svc.StartDaily(ctx, snap, DailyStartRequest{Force: false})
	if err != nil {
		t.Fatalf("cached StartDaily: %v", err)
	}
	if cachedResp.Status != "ready" || cachedResp.JobID != "" || len(cachedResp.Items) != 30 {
		t.Fatalf("expected instant cached ready response, got status=%s job=%s len=%d", cachedResp.Status, cachedResp.JobID, len(cachedResp.Items))
	}
	if searchCalls.Load() != prevSearchCalls {
		t.Fatalf("expected 0 provider searches on cache hit")
	}

	// Manual refresh (Force=true) must increment RefreshSeq and avoid repeating batch 1 tracks.
	batch1Keys := make(map[string]bool, 30)
	for _, it := range done.Daily.Items {
		batch1Keys[TrackNormKey(it.Track.Title, it.Track.Artist)] = true
	}
	refreshed, err := svc.StartDaily(ctx, snap, DailyStartRequest{Force: true})
	if err != nil {
		t.Fatalf("refresh StartDaily: %v", err)
	}
	if refreshed.Status != "generating" || refreshed.RefreshSeq != 2 {
		t.Fatalf("expected generating seq=2, got status=%s seq=%d", refreshed.Status, refreshed.RefreshSeq)
	}
	_, done2 := drainJobEvents(t, svc, refreshed.JobID)
	if len(done2.Daily.Items) != 30 {
		t.Fatalf("expected 30 tracks after refresh, got %d", len(done2.Daily.Items))
	}
	for _, it := range done2.Daily.Items {
		if batch1Keys[TrackNormKey(it.Track.Title, it.Track.Artist)] {
			t.Fatalf("refreshed batch repeated track from batch 1: %s - %s", it.Track.Title, it.Track.Artist)
		}
	}
}

func TestDailyRecommendRuleFallbackAndColdStart(t *testing.T) {
	st := openRecommendTestStore(t)
	ctx := context.Background()

	itunesSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		term := r.URL.Query().Get("term")
		var results []map[string]any
		for i := 1; i <= 10; i++ {
			results = append(results, map[string]any{
				"trackName":  fmt.Sprintf("%s Song %d", term, i),
				"artistName": fmt.Sprintf("%s Artist %d", term, i),
			})
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"results": results})
	}))
	defer itunesSrv.Close()

	prov := &fakeProvider{
		id:         "fallback-src",
		engineName: "fallback-src",
		searchFn: func(ctx context.Context, opts model.SearchOptions) ([]model.ProviderTrack, error) {
			kw := strings.TrimSpace(opts.Keyword)
			var out []model.ProviderTrack
			for i := 1; i <= 5; i++ {
				out = append(out, model.ProviderTrack{
					SourceID: kw + "-" + fmt.Sprint(i),
					Title:    kw,
					Artist:   "",
					Duration: 200,
				})
			}
			// If exact term contains " Song ", synthesize exact matching track for candidate.
			if idx := strings.Index(kw, " Song "); idx > 0 {
				parts := strings.SplitN(kw, " Song ", 2)
				rest := strings.Fields(parts[1])
				if len(rest) >= 1 {
					title := parts[0] + " Song " + rest[0]
					artist := parts[0] + " Artist " + rest[0]
					out = []model.ProviderTrack{{
						SourceID: title,
						Title:    title,
						Artist:   artist,
						Duration: 220,
					}}
				}
			}
			return out, nil
		},
	}

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()

	svc := NewService(Options{
		Store:           st,
		Providers:       &fakeCatalog{providers: []model.Provider{prov}},
		Transport:       pool,
		AI:              &fakeAI{configured: false},
		ITunesSearchURL: itunesSrv.URL,
	})
	defer svc.Close()

	snap := config.NewSnapshot(config.RuntimeConfig{Sources: []string{"fallback-src"}}, config.DefaultResourceBudgets())
	started, err := svc.StartDaily(ctx, snap, DailyStartRequest{})
	if err != nil {
		t.Fatalf("StartDaily cold start: %v", err)
	}
	_, done := drainJobEvents(t, svc, started.JobID)
	if done.Daily.Mode != "rule" {
		t.Fatalf("expected mode=rule when AI unconfigured, got %s", done.Daily.Mode)
	}
	if len(done.Daily.Items) != 30 {
		t.Fatalf("expected 30 tracks on cold-start rule mode, got %d", len(done.Daily.Items))
	}
}
