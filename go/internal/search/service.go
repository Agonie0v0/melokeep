package search

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
)

var (
	// ErrSlowSubscriber is returned to a stalled subscriber when its lag exceeds the buffer.
	ErrSlowSubscriber = model.NewError(model.ErrTransport, http.StatusRequestTimeout, "客户端接收过慢，已断开连接")
)

const (
	defaultMaxSingleEventBytes = 256 << 10 // 256 KiB
	defaultComponentLogBytes   = 32 << 20  // 32 MiB
	defaultJobLogBytes         = 8 << 20   // 8 MiB
	defaultSubscriberBuffer    = 128
	terminalReserveBytes       = 2048
	terminalReserveEvents      = 2
	eventFrameOverheadBytes    = 64
)

type resolvedMediaEntry struct {
	Media     []model.ResolvedMedia
	ExpiresAt time.Time
}

// Service coordinates singleflight searches, bounded SSE event logs, caching, and store persistence.
type Service struct {
	store                *store.Store
	providers            ProviderLookup
	budgets              config.ResourceBudgets
	timings              Timings
	maxSingleEventBytes  int
	maxJobLogBytes       int64
	maxComponentLogBytes int64
	subscriberBuffer     int
	now                  func() time.Time
	logger               provider.LogFunc

	providerSem chan struct{}
	baseCtx     context.Context
	cancelAll   context.CancelFunc

	totalLogBytes atomic.Int64

	resolveMu    sync.Mutex
	resolveCache map[string]resolvedMediaEntry

	mu      sync.Mutex
	jobs    map[string]*searchJob
	jobSeq  []string
	flights map[string]*searchFlight
	cache   *resultCache
	closed  bool
}

// NewService constructs a search Service with bounded budgets and background lifecycle.
func NewService(st *store.Store, providers ProviderLookup, budgets config.ResourceBudgets, opts ...Option) *Service {
	if budgets.ActiveSearchJobs <= 0 {
		budgets = config.DefaultResourceBudgets()
	}
	provConc := budgets.ProviderConcurrency
	if provConc <= 0 {
		provConc = 4
	}
	baseCtx, cancelAll := context.WithCancel(context.Background())
	s := &Service{
		store:                st,
		providers:            providers,
		budgets:              budgets,
		timings:              DefaultTimings(),
		maxSingleEventBytes:  defaultMaxSingleEventBytes,
		maxJobLogBytes:       int64(budgets.SearchJobMaxBytes),
		maxComponentLogBytes: defaultComponentLogBytes,
		subscriberBuffer:     defaultSubscriberBuffer,
		now:                  time.Now,
		providerSem:          make(chan struct{}, provConc),
		baseCtx:              baseCtx,
		cancelAll:            cancelAll,
		jobs:                 make(map[string]*searchJob),
		flights:              make(map[string]*searchFlight),
		resolveCache:         make(map[string]resolvedMediaEntry),
	}
	if budgets.SearchCompletedTTLSeconds > 0 {
		s.timings.CompletedJobTTL = time.Duration(budgets.SearchCompletedTTLSeconds) * time.Second
	}
	if budgets.SearchCacheTTLSeconds > 0 {
		s.timings.CacheTTL = time.Duration(budgets.SearchCacheTTLSeconds) * time.Second
	}
	if s.maxJobLogBytes <= 0 {
		s.maxJobLogBytes = defaultJobLogBytes
	}
	if budgets.SearchLogBudgetBytes > 0 && budgets.SearchLogBudgetBytes != (16<<20) {
		s.maxComponentLogBytes = int64(budgets.SearchLogBudgetBytes)
	}
	for _, opt := range opts {
		if opt != nil {
			opt(s)
		}
	}
	cacheMaxBytes := int64(budgets.SearchCacheMaxBytes)
	if cacheMaxBytes <= 0 {
		cacheMaxBytes = 8 << 20
	}
	s.cache = newResultCache(s.timings.CacheTTL, budgets.SearchCacheMaxEntries, cacheMaxBytes, s.now)
	return s
}

// Close cancels all active flights and timers cleanly.
func (s *Service) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	jobs := make([]*searchJob, 0, len(s.jobs))
	for _, j := range s.jobs {
		jobs = append(jobs, j)
	}
	s.mu.Unlock()

	s.cancelAll()
	for _, j := range jobs {
		j.stopTimers()
	}
	return nil
}

type resolvedSource struct {
	publicID   string
	engineName string
	provider   model.Provider
}

// CreateSearch validates inputs and resource limits, then starts a singleflight-backed search job.
func (s *Service) CreateSearch(ctx context.Context, actor auth.Actor, snap config.Snapshot, req CreateRequest) (string, error) {
	if !actor.Authenticated {
		return "", model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if s.providers == nil {
		return "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "音源注册表未初始化")
	}
	keyword := strings.TrimSpace(req.Keyword)
	rawSources := req.Sources
	if len(rawSources) == 0 {
		rawSources = snap.Runtime.Sources
	}
	if keyword == "" || len(rawSources) == 0 {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "关键词或启用音源无效")
	}

	seen := make(map[string]bool, len(rawSources))
	sources := make([]resolvedSource, 0, len(rawSources))
	for _, raw := range rawSources {
		eng := config.CanonicalEngineName(raw)
		if eng == "" {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "关键词或启用音源无效")
		}
		p, ok := s.providers.Get(eng)
		if !ok {
			p, ok = s.providers.Get(strings.TrimSpace(raw))
		}
		if !ok || p == nil {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "关键词或启用音源无效")
		}
		canonEng := p.EngineName()
		if canonEng == "" {
			canonEng = eng
		}
		if seen[canonEng] {
			continue
		}
		seen[canonEng] = true
		pub := config.PublicSourceID(p.ID())
		if pub == "" {
			pub = config.PublicSourceID(canonEng)
		}
		sources = append(sources, resolvedSource{
			publicID:   pub,
			engineName: canonEng,
			provider:   p,
		})
	}
	if len(sources) == 0 {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "关键词或启用音源无效")
	}

	limit := req.Limit
	if limit <= 0 {
		limit = snap.Runtime.ResultsPerSource
	}
	if limit <= 0 {
		limit = 5
	}
	if limit > 50 {
		limit = 50
	}

	now := s.now()
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "搜索服务已关闭")
	}
	s.pruneCompletedLocked(now)

	activeCount := 0
	for _, j := range s.jobs {
		if !j.isDone() {
			activeCount++
		}
	}
	maxActive := s.budgets.ActiveSearchJobs
	if maxActive <= 0 {
		maxActive = 6
	}
	if activeCount >= maxActive {
		s.mu.Unlock()
		return "", model.NewError(model.ErrRateLimited, http.StatusTooManyRequests, "同时搜索过多，请先停止旧搜索")
	}

	maxRetain := s.budgets.SearchCompletedRetention
	if maxRetain <= 0 {
		maxRetain = 32
	}
	for len(s.jobs) >= maxRetain {
		if !s.evictOldestCompletedLocked() {
			break
		}
	}

	jobID := model.NewUUID()
	jobCtx, jobCancel := context.WithCancel(s.baseCtx)
	job := &searchJob{
		svc:       s,
		id:        jobID,
		actorKey:  actor.Key(),
		keyword:   keyword,
		state:     JobRunning,
		createdAt: now,
		updatedAt: now,
		broadcast: make(chan struct{}),
		subs:      make(map[uint64]*Subscription),
		ctx:       jobCtx,
		cancel:    jobCancel,
		wg:        sync.WaitGroup{},
	}
	job.wg.Add(1)
	job.idleTimer = time.AfterFunc(s.timings.NoSubscriberCancel, func() {
		s.cancelIfNoSubscribers(jobID)
	})
	s.jobs[jobID] = job
	s.jobSeq = append(s.jobSeq, jobID)
	s.mu.Unlock()

	go s.runJob(job, snap, sources, keyword, limit, req.Refresh)
	sourceIDs := make([]string, 0, len(sources))
	for _, src := range sources {
		sourceIDs = append(sourceIDs, src.publicID)
	}
	s.log("info", "search", jobID, fmt.Sprintf("开始搜索「%s」· 音源=%s · 每源目标=%d · 强制刷新=%v", keyword, strings.Join(sourceIDs, ","), limit, req.Refresh))
	return jobID, nil
}

func (s *Service) log(level, category, taskID, message string) {
	if s.logger != nil && message != "" {
		s.logger(level, message, category, taskID)
	}
}

// GetJob returns a point-in-time summary of a search job.
func (s *Service) GetJob(ctx context.Context, jobID string) (JobSummary, bool) {
	now := s.now()
	s.mu.Lock()
	s.pruneCompletedLocked(now)
	job, ok := s.jobs[jobID]
	s.mu.Unlock()
	if !ok {
		return JobSummary{}, false
	}
	return job.summary(), true
}

// Cancel stops an active search job; missing or already finished jobs return nil idempotently.
func (s *Service) Cancel(ctx context.Context, actor auth.Actor, jobID string) error {
	if !actor.Authenticated {
		return model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	s.mu.Lock()
	job, ok := s.jobs[jobID]
	s.mu.Unlock()
	if !ok {
		return nil
	}
	s.cancelJob(job, true)
	return nil
}

// Subscribe attaches an SSE consumer to a job, supporting `after` and `Last-Event-ID` cursors.
func (s *Service) Subscribe(ctx context.Context, jobID string, after int64, lastEventID string) (*Subscription, error) {
	if after < 0 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "事件序号无效")
	}
	cursor := after
	if strings.TrimSpace(lastEventID) != "" {
		parsed, err := strconv.ParseInt(strings.TrimSpace(lastEventID), 10, 64)
		if err != nil || parsed < 0 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "事件序号无效")
		}
		if parsed > cursor {
			cursor = parsed
		}
	}

	now := s.now()
	s.mu.Lock()
	s.pruneCompletedLocked(now)
	job, ok := s.jobs[jobID]
	s.mu.Unlock()
	if !ok {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "搜索已过期，请重新搜索")
	}

	return job.addSubscriber(cursor), nil
}

func (s *Service) pruneCompletedLocked(now time.Time) {
	if s.timings.CompletedJobTTL <= 0 {
		return
	}
	kept := s.jobSeq[:0]
	for _, id := range s.jobSeq {
		j, ok := s.jobs[id]
		if !ok {
			continue
		}
		if j.shouldExpire(now, s.timings.CompletedJobTTL) {
			s.deleteJobLocked(j)
			continue
		}
		kept = append(kept, id)
	}
	s.jobSeq = kept
}

func (s *Service) evictOldestCompletedLocked() bool {
	for idx, id := range s.jobSeq {
		j, ok := s.jobs[id]
		if !ok {
			continue
		}
		if j.isDone() && j.subscriberCount() == 0 {
			s.deleteJobLocked(j)
			s.jobSeq = append(s.jobSeq[:idx], s.jobSeq[idx+1:]...)
			return true
		}
	}
	for idx, id := range s.jobSeq {
		j, ok := s.jobs[id]
		if !ok {
			continue
		}
		if j.isDone() {
			s.deleteJobLocked(j)
			s.jobSeq = append(s.jobSeq[:idx], s.jobSeq[idx+1:]...)
			return true
		}
	}
	return false
}

func (s *Service) deleteJobLocked(j *searchJob) {
	j.stopTimers()
	j.cancel()
	bytes := j.drainLogBytes()
	if bytes > 0 {
		s.totalLogBytes.Add(-bytes)
	}
	delete(s.jobs, j.id)
}

func (s *Service) cancelIfNoSubscribers(jobID string) {
	s.mu.Lock()
	job, ok := s.jobs[jobID]
	s.mu.Unlock()
	if !ok {
		return
	}
	if job.subscriberCount() == 0 && !job.isDone() {
		s.cancelJob(job, true)
	}
}

func (s *Service) cancelJob(job *searchJob, markCancelled bool) {
	job.mu.Lock()
	if job.done {
		job.mu.Unlock()
		return
	}
	if markCancelled {
		job.cancelled = true
	}
	cancel := job.cancel
	job.mu.Unlock()

	job.stopTimers()
	if cancel != nil {
		cancel()
	}
	job.wg.Wait()
	job.finishTerminal(s.now())
}

func (s *Service) runJob(job *searchJob, snap config.Snapshot, sources []resolvedSource, keyword string, limit int, refresh bool) {
	defer job.wg.Done()
	var srcWG sync.WaitGroup
	for _, src := range sources {
		src := src
		srcWG.Add(1)
		go func() {
			defer srcWG.Done()
			s.runJobSource(job, snap, src, keyword, limit, refresh)
		}()
	}
	srcWG.Wait()
	job.stopTimers()
	finishedAt := s.now()
	termState, okCnt, failCnt, elapsedMs := job.previewTerminal(finishedAt)
	lvl := "info"
	if termState == JobFailed || termState == JobCapacityExceeded {
		lvl = "error"
	} else if termState == JobPartial || termState == JobCancelled {
		lvl = "warning"
	}
	s.log(lvl, "search", job.id, fmt.Sprintf("搜索结束「%s」· 状态=%s · 成功音源=%d · 失败音源=%d · 耗时=%dms", keyword, termState, okCnt, failCnt, elapsedMs))
	job.finishTerminal(finishedAt)
}

func (s *Service) runJobSource(job *searchJob, snap config.Snapshot, src resolvedSource, keyword string, limit int, refresh bool) {
	fk := makeFlightKey(snap.Revision, src.engineName, keyword, limit)
	dk := fk.digest()

	s.mu.Lock()
	if refresh {
		s.cache.Invalidate(fk)
	} else if cachedTracks, hit := s.cache.Get(fk); hit {
		s.mu.Unlock()
		if err := s.persistTracks(job.ctx, cachedTracks); err != nil {
			if job.ctx.Err() != nil {
				return
			}
			s.log("error", "search", job.id, fmt.Sprintf("[%s] 缓存曲目持久化失败: %v", src.publicID, err))
			job.emitSource(src.publicID, "failed", 0, "曲目持久化失败")
			return
		}
		emitted := 0
		for _, tr := range cachedTracks {
			if job.ctx.Err() != nil || job.isDone() {
				return
			}
			dto := model.TrackDTOFromDomain(tr, model.PlaybackDTOFromResolvedTrack(tr, ""))
			if ok := job.emitTrack(dto); !ok {
				return
			}
			emitted++
		}
		s.log("info", "search", job.id, fmt.Sprintf("[%s] 命中搜索缓存：返回 %d 首", src.publicID, emitted))
		job.emitSource(src.publicID, "complete", emitted, "")
		return
	}

	fl, exists := s.flights[dk]
	if !exists || fl.isDone() {
		flCtx, flCancel := context.WithCancel(s.baseCtx)
		fl = &searchFlight{
			svc:          s,
			key:          fk,
			digest:       dk,
			src:          src,
			limit:        limit,
			logTaskID:    job.id,
			startedAt:    s.now(),
			ctx:          flCtx,
			cancel:       flCancel,
			subscribers:  make(map[string]*flightConsumer),
			notifySignal: make(chan struct{}),
			doneCh:       make(chan struct{}),
		}
		s.flights[dk] = fl
		go fl.run(snap)
	}
	_ = fl.attach(job.id)
	s.mu.Unlock()

	defer s.detachFlight(fl, job.id)

	emittedIDs := make(map[string]bool)
	cursor := 0
	for {
		tracks, done, flErr, waitCh := fl.snapshotFrom(cursor)
		for _, tr := range tracks {
			if job.ctx.Err() != nil || job.isDone() {
				return
			}
			dto := model.TrackDTOFromDomain(tr, model.PlaybackDTOFromResolvedTrack(tr, ""))
			if !job.emitTrack(dto) {
				return
			}
			emittedIDs[dto.ID] = true
			cursor++
		}
		if done {
			if job.ctx.Err() != nil {
				return
			}
			rawCnt, resCnt, dur := fl.stats()
			if flErr != nil {
				detail := classifySourceError(flErr, len(emittedIDs))
				s.log("error", "search", job.id, fmt.Sprintf("[%s] 搜索失败（已返回 %d 首，预解析 %d 首，耗时 %dms）：%s (%v)", src.publicID, len(emittedIDs), resCnt, dur.Milliseconds(), detail, flErr))
				job.emitSource(src.publicID, "failed", len(emittedIDs), detail)
			} else {
				lvl := "info"
				note := ""
				if len(emittedIDs) == 0 {
					lvl = "warning"
					note = " · 上游未返回匹配曲目"
				} else if int(rawCnt) < limit {
					lvl = "warning"
					note = fmt.Sprintf(" · 上游仅返回 %d/%d 首", rawCnt, limit)
				}
				s.log(lvl, "search", job.id, fmt.Sprintf("[%s] 搜索完成：返回 %d 首，预解析可用 %d 首，耗时 %dms%s", src.publicID, len(emittedIDs), resCnt, dur.Milliseconds(), note))
				job.emitSource(src.publicID, "complete", len(emittedIDs), "")
			}
			return
		}
		select {
		case <-job.ctx.Done():
			return
		case <-waitCh:
		}
	}
}

func (s *Service) detachFlight(fl *searchFlight, jobID string) {
	var cancel context.CancelFunc
	s.mu.Lock()
	fl.mu.Lock()
	delete(fl.subscribers, jobID)
	empty := len(fl.subscribers) == 0
	done := fl.done
	if empty && !done {
		cancel = fl.cancel
		if cur, ok := s.flights[fl.digest]; ok && cur == fl {
			delete(s.flights, fl.digest)
		}
	}
	fl.mu.Unlock()
	s.mu.Unlock()

	if cancel != nil {
		cancel()
	}
}

func (s *Service) putResolvedCache(sid, revision string, media []model.ResolvedMedia) {
	if sid == "" || len(media) == 0 {
		return
	}
	hasURL := false
	for _, rm := range media {
		if strings.TrimSpace(rm.URL) != "" {
			hasURL = true
			break
		}
	}
	if !hasURL {
		return
	}
	cacheKey := sid + "|" + revision
	now := s.now()
	copied := make([]model.ResolvedMedia, len(media))
	copy(copied, media)
	s.resolveMu.Lock()
	if s.resolveCache == nil {
		s.resolveCache = make(map[string]resolvedMediaEntry)
	}
	if len(s.resolveCache) > 512 {
		for k, v := range s.resolveCache {
			if !v.ExpiresAt.After(now) {
				delete(s.resolveCache, k)
			}
		}
	}
	s.resolveCache[cacheKey] = resolvedMediaEntry{
		Media:     copied,
		ExpiresAt: now.Add(5 * time.Minute),
	}
	s.resolveMu.Unlock()
}

// ClearCache discards all cached resolved playback media entries.
func (s *Service) ClearCache() {
	s.resolveMu.Lock()
	clear(s.resolveCache)
	s.resolveMu.Unlock()
}

// CachedResolved returns resolved media cached during search for (sid, revision).
func (s *Service) CachedResolved(sid, revision string) ([]model.ResolvedMedia, bool) {
	if sid == "" {
		return nil, false
	}
	cacheKey := sid + "|" + revision
	now := s.now()
	s.resolveMu.Lock()
	defer s.resolveMu.Unlock()
	ent, ok := s.resolveCache[cacheKey]
	if !ok {
		return nil, false
	}
	if !ent.ExpiresAt.After(now) {
		delete(s.resolveCache, cacheKey)
		return nil, false
	}
	out := make([]model.ResolvedMedia, len(ent.Media))
	copy(out, ent.Media)
	return out, true
}

func (s *Service) persistTracks(ctx context.Context, tracks []model.ProviderTrack) error {
	if s.store == nil || len(tracks) == 0 {
		return nil
	}
	for i := range tracks {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		tr := &tracks[i]
		if tr.PublicID == "" {
			tr.PublicID = model.NewUUID()
		}
		locator := tr.SourceID
		if locator == "" {
			locator = tr.PublicID
		}
		metaJSON := "{}"
		if rawMeta, mErr := json.Marshal(map[string]any{
			"cover":          tr.Cover,
			"size_bytes":     tr.SizeBytes,
			"size_label":     tr.SizeLabel,
			"episodes":       tr.Episodes,
			"playlist_index": tr.PlaylistIndex,
			"lyrics":         tr.Lyrics,
		}); mErr == nil {
			metaJSON = string(rawMeta)
		}
		rec := store.ProviderTrackRecord{
			ID:           tr.PublicID,
			Source:       tr.Source,
			Locator:      locator,
			RootSource:   tr.RootSource,
			Title:        tr.Title,
			Artist:       tr.Artist,
			Album:        tr.Album,
			DurationMS:   int64(tr.Duration * 1000),
			MetadataJSON: metaJSON,
			ProviderJSON: "{}",
		}
		trackID, err := s.store.UpsertProviderTrack(ctx, rec, tr.Variants)
		if err != nil {
			return err
		}
		if err := s.store.PutPublicRef(ctx, model.PublicRef{
			PublicID: tr.PublicID,
			Kind:     "candidate",
			TargetID: trackID,
			Source:   tr.Source,
			SourceID: tr.SourceID,
		}, ""); err != nil {
			return err
		}
		dto := model.TrackDTOFromDomain(*tr, model.PlaybackDTOFromResolvedTrack(*tr, ""))
		if rawDTO, dErr := json.Marshal(dto); dErr == nil {
			_, _ = s.store.PutDurableRecord(ctx, "track_dto", dto.ID, string(rawDTO), 0)
		}
	}
	return nil
}

func (s *Service) tryReserveGlobalBytes(delta int64, terminal bool) bool {
	ceiling := s.maxComponentLogBytes
	if !terminal {
		ceiling -= terminalReserveBytes
	}
	for {
		cur := s.totalLogBytes.Load()
		if cur+delta > ceiling {
			return false
		}
		if s.totalLogBytes.CompareAndSwap(cur, cur+delta) {
			return true
		}
	}
}

func classifySourceError(err error, emittedCount int) string {
	if errors.Is(err, context.DeadlineExceeded) {
		if emittedCount > 0 {
			return fmt.Sprintf("已返回 %d 条结果，剩余超时", emittedCount)
		}
		return "原音源搜索超时，可稍后重试"
	}
	if de, ok := model.AsDomainError(err); ok {
		if de.Code == model.ErrDeadline && emittedCount > 0 {
			return fmt.Sprintf("已返回 %d 条结果，剩余超时", emittedCount)
		}
		if strings.TrimSpace(de.Detail) != "" {
			return de.Detail
		}
	}
	return "原音源搜索失败，可检查音源设置后重试"
}

type flightConsumer struct {
	jobID string
}

type searchFlight struct {
	svc       *Service
	key       flightKey
	digest    string
	src       resolvedSource
	limit     int
	logTaskID string
	startedAt time.Time
	ctx       context.Context
	cancel    context.CancelFunc

	rawCount      atomic.Int32
	resolvedCount atomic.Int32

	mu           sync.Mutex
	subscribers  map[string]*flightConsumer
	tracks       []model.ProviderTrack
	notifySignal chan struct{}
	done         bool
	err          error
	elapsed      time.Duration
	doneCh       chan struct{}
}

func (f *searchFlight) stats() (raw int32, resolved int32, elapsed time.Duration) {
	f.mu.Lock()
	elapsed = f.elapsed
	f.mu.Unlock()
	return f.rawCount.Load(), f.resolvedCount.Load(), elapsed
}

func (f *searchFlight) isDone() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.done
}

func (f *searchFlight) attach(jobID string) *flightConsumer {
	f.mu.Lock()
	defer f.mu.Unlock()
	c := &flightConsumer{jobID: jobID}
	f.subscribers[jobID] = c
	return c
}

func (f *searchFlight) snapshotFrom(cursor int) ([]model.ProviderTrack, bool, error, <-chan struct{}) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var slice []model.ProviderTrack
	if cursor < len(f.tracks) {
		slice = make([]model.ProviderTrack, len(f.tracks)-cursor)
		copy(slice, f.tracks[cursor:])
	}
	return slice, f.done, f.err, f.notifySignal
}

func (f *searchFlight) publishBatch(batch []model.ProviderTrack) error {
	if len(batch) == 0 {
		return nil
	}
	if f.ctx.Err() != nil {
		return f.ctx.Err()
	}
	if err := f.svc.persistTracks(f.ctx, batch); err != nil {
		return err
	}
	f.mu.Lock()
	f.tracks = append(f.tracks, batch...)
	close(f.notifySignal)
	f.notifySignal = make(chan struct{})
	f.mu.Unlock()
	return nil
}

func (f *searchFlight) run(snap config.Snapshot) {
	defer close(f.doneCh)

	timeoutSec := snap.Runtime.SearchTimeoutSeconds
	if timeoutSec <= 0 {
		timeoutSec = 30
	}
	// Decouple per-source execution budget (Search + concurrent Resolve stages)
	// from single-HTTP request timeout so multi-step sources don't clip mid-flight.
	budgetSec := timeoutSec * 3
	if budgetSec < 90 {
		budgetSec = 90
	}
	// Acquire bounded provider concurrency slot.
	select {
	case f.svc.providerSem <- struct{}{}:
		defer func() { <-f.svc.providerSem }()
	case <-f.ctx.Done():
		f.finish(f.ctx.Err())
		return
	}

	runCtx, runCancel := context.WithTimeout(f.ctx, time.Duration(budgetSec)*time.Second)
	defer runCancel()

	var batchMu sync.Mutex
	var pending []model.ProviderTrack
	var persistErr error

	flush := func() error {
		batchMu.Lock()
		if persistErr != nil {
			err := persistErr
			batchMu.Unlock()
			return err
		}
		if len(pending) == 0 {
			batchMu.Unlock()
			return nil
		}
		batch := pending
		pending = nil
		batchMu.Unlock()

		if err := f.publishBatch(batch); err != nil {
			batchMu.Lock()
			persistErr = err
			batchMu.Unlock()
			return err
		}
		return nil
	}

	stopFlusher := make(chan struct{})
	flusherDone := make(chan struct{})
	go func() {
		defer close(flusherDone)
		ticker := time.NewTicker(f.svc.timings.BatchPersistInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				_ = flush()
			case <-stopFlusher:
				return
			case <-runCtx.Done():
				return
			}
		}
	}()

	opts := model.SearchOptions{
		Keyword: f.key.Keyword,
		Limit:   f.limit,
	}
	reqCtx := provider.WithRequestContext(runCtx, provider.RequestContext{
		Config:  snap,
		TaskID:  f.logTaskID,
		LogFunc: f.svc.logger,
	})
	workers := snap.Runtime.DownloadConcurrency
	if workers < 3 {
		workers = 4
	} else if workers > 6 {
		workers = 6
	}
	resolveSem := make(chan struct{}, workers)
	var resolveWG sync.WaitGroup

	_, searchErr := f.src.provider.Search(reqCtx, opts, func(tr model.ProviderTrack) error {
		if runCtx.Err() != nil {
			return runCtx.Err()
		}
		f.rawCount.Add(1)
		tr.Source = f.src.publicID
		if tr.RootSource == "" {
			tr.RootSource = f.src.engineName
		}
		if tr.PublicID == "" {
			tr.PublicID = model.NewUUID()
		}
		select {
		case resolveSem <- struct{}{}:
		case <-runCtx.Done():
			return runCtx.Err()
		}
		resolveWG.Add(1)
		go func(cand model.ProviderTrack) {
			defer func() {
				<-resolveSem
				resolveWG.Done()
			}()
			if f.ctx.Err() != nil {
				return
			}
			if runCtx.Err() == nil {
				resolved, rErr := f.src.provider.Resolve(reqCtx, cand, model.QualityPolicy{AllowPreview: true})
				if rErr == nil && len(resolved) > 0 {
					f.resolvedCount.Add(1)
					model.ApplyResolvedMedia(&cand, resolved)
					f.svc.putResolvedCache(cand.PublicID, snap.Revision, resolved)
				} else if rErr != nil {
					f.svc.log("debug", "search", f.logTaskID, fmt.Sprintf("[%s] 「%s」预解析未命中可用地址: %v", f.src.publicID, cand.Title, rErr))
				}
			}
			if f.ctx.Err() != nil {
				return
			}
			batchMu.Lock()
			if persistErr != nil {
				batchMu.Unlock()
				return
			}
			pending = append(pending, cand)
			shouldFlush := len(pending) >= f.svc.timings.BatchPersistSize
			batchMu.Unlock()
			if shouldFlush {
				_ = flush()
			}
		}(tr)
		return nil
	})

	resolveWG.Wait()
	close(stopFlusher)
	<-flusherDone

	if flushErr := flush(); flushErr != nil && searchErr == nil {
		searchErr = flushErr
	}
	f.finish(searchErr)
}

func (f *searchFlight) finish(err error) {
	f.svc.mu.Lock()
	f.mu.Lock()
	f.done = true
	f.err = err
	if !f.startedAt.IsZero() {
		f.elapsed = f.svc.now().Sub(f.startedAt)
	}
	if err == nil && len(f.tracks) > 0 && f.ctx.Err() == nil {
		f.svc.cache.Put(f.key, f.tracks)
	}
	if cur, ok := f.svc.flights[f.digest]; ok && cur == f {
		delete(f.svc.flights, f.digest)
	}
	close(f.notifySignal)
	f.notifySignal = make(chan struct{})
	f.mu.Unlock()
	f.svc.mu.Unlock()
}

type searchJob struct {
	svc       *Service
	id        string
	actorKey  string
	keyword   string
	createdAt time.Time

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu               sync.Mutex
	state            JobState
	done             bool
	cancelled        bool
	capacityExceeded bool
	completedAt      time.Time
	updatedAt        time.Time
	events           []Event
	bytesUsed        int64
	sourceFailed     int
	sourceSuccess    int
	broadcast        chan struct{}
	subs             map[uint64]*Subscription
	nextSubID        uint64
	idleTimer        *time.Timer
	graceTimer       *time.Timer
}

func (j *searchJob) stopTimers() {
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.idleTimer != nil {
		j.idleTimer.Stop()
		j.idleTimer = nil
	}
	if j.graceTimer != nil {
		j.graceTimer.Stop()
		j.graceTimer = nil
	}
}

func (j *searchJob) isDone() bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.done
}

func (j *searchJob) subscriberCount() int {
	j.mu.Lock()
	defer j.mu.Unlock()
	return len(j.subs)
}

func (j *searchJob) sourceSuccessCount() int {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.sourceSuccess
}

func (j *searchJob) sourceFailedCount() int {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.sourceFailed
}

func (j *searchJob) shouldExpire(now time.Time, ttl time.Duration) bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	if !j.done || j.completedAt.IsZero() {
		return false
	}
	if len(j.subs) > 0 {
		return false
	}
	return !j.completedAt.Add(ttl).After(now)
}

func (j *searchJob) drainLogBytes() int64 {
	j.mu.Lock()
	defer j.mu.Unlock()
	b := j.bytesUsed
	j.bytesUsed = 0
	j.events = nil
	return b
}

func (j *searchJob) summary() JobSummary {
	j.mu.Lock()
	defer j.mu.Unlock()
	return JobSummary{
		ID:          j.id,
		State:       j.state,
		Done:        j.done,
		Cancelled:   j.cancelled,
		EventCount:  len(j.events),
		LogBytes:    j.bytesUsed,
		Subscribers: len(j.subs),
		CreatedAt:   j.createdAt,
		UpdatedAt:   j.updatedAt,
	}
}

func (j *searchJob) emitTrack(dto model.TrackDTO) bool {
	raw, err := json.Marshal(dto)
	if err != nil {
		return true
	}
	return j.appendEvent("track", raw, false)
}

func (j *searchJob) emitSource(publicID, state string, count int, detail string) {
	c := count
	payload := SourceEventPayload{
		ID: publicID,
		Status: model.SourceStatusDTO{
			State:  state,
			Count:  &c,
			Detail: detail,
		},
	}
	raw, _ := json.Marshal(payload)
	j.mu.Lock()
	if state == "complete" {
		j.sourceSuccess++
	} else if state == "failed" {
		j.sourceFailed++
	}
	j.mu.Unlock()
	_ = j.appendEvent("source", raw, false)
}

func (j *searchJob) appendEvent(evType string, data []byte, terminal bool) bool {
	if !terminal && len(data) > j.svc.maxSingleEventBytes {
		j.triggerCapacityAbort("单条搜索事件超过 256 KiB 上限，已终止搜索")
		return false
	}
	evCost := int64(len(data)) + eventFrameOverheadBytes

	j.mu.Lock()
	if j.done {
		j.mu.Unlock()
		return false
	}
	if !terminal {
		maxEvents := j.svc.budgets.SearchJobMaxEvents
		if maxEvents <= 0 {
			maxEvents = 4096
		}
		if len(j.events)+1 > maxEvents-terminalReserveEvents ||
			j.bytesUsed+evCost > j.svc.maxJobLogBytes-terminalReserveBytes ||
			!j.svc.tryReserveGlobalBytes(evCost, false) {
			j.mu.Unlock()
			j.triggerCapacityAbort("搜索事件日志达到容量上限，已终止剩余搜索")
			return false
		}
	} else {
		j.svc.totalLogBytes.Add(evCost)
	}

	seq := int64(len(j.events) + 1)
	ev := Event{
		Seq:  seq,
		Type: evType,
		Data: json.RawMessage(append([]byte(nil), data...)),
	}
	j.events = append(j.events, ev)
	j.bytesUsed += evCost
	j.updatedAt = j.svc.now()
	j.checkSlowSubscribersLocked()
	j.broadcastLocked()
	j.mu.Unlock()
	return true
}

func (j *searchJob) triggerCapacityAbort(reason string) {
	j.mu.Lock()
	if j.done || j.capacityExceeded {
		j.mu.Unlock()
		return
	}
	j.capacityExceeded = true
	j.state = JobCapacityExceeded
	cancel := j.cancel
	j.mu.Unlock()

	if cancel != nil {
		cancel()
	}

	c := 0
	srcPayload, _ := json.Marshal(SourceEventPayload{
		ID: "_budget",
		Status: model.SourceStatusDTO{
			State:  "failed",
			Count:  &c,
			Detail: reason,
		},
	})
	_ = j.appendEvent("source", srcPayload, true)
	j.finishTerminal(j.svc.now())
}

func (j *searchJob) resolveTerminalStateLocked() JobState {
	if j.capacityExceeded {
		return JobCapacityExceeded
	}
	switch {
	case j.cancelled:
		return JobCancelled
	case j.sourceFailed > 0 && j.sourceSuccess > 0:
		return JobPartial
	case j.sourceFailed > 0 && j.sourceSuccess == 0:
		return JobFailed
	default:
		return JobComplete
	}
}

func (j *searchJob) previewTerminal(now time.Time) (JobState, int, int, int64) {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.resolveTerminalStateLocked(), j.sourceSuccess, j.sourceFailed, now.Sub(j.createdAt).Milliseconds()
}

func (j *searchJob) finishTerminal(now time.Time) {
	j.mu.Lock()
	if j.done {
		j.mu.Unlock()
		return
	}
	cancelled := j.cancelled || j.capacityExceeded
	donePayload, _ := json.Marshal(DoneEventPayload{Cancelled: cancelled})
	evCost := int64(len(donePayload)) + eventFrameOverheadBytes
	j.svc.totalLogBytes.Add(evCost)

	seq := int64(len(j.events) + 1)
	j.events = append(j.events, Event{
		Seq:  seq,
		Type: "done",
		Data: donePayload,
	})
	j.bytesUsed += evCost
	j.done = true
	j.completedAt = now
	j.updatedAt = now
	j.state = j.resolveTerminalStateLocked()
	if j.idleTimer != nil {
		j.idleTimer.Stop()
		j.idleTimer = nil
	}
	if j.graceTimer != nil {
		j.graceTimer.Stop()
		j.graceTimer = nil
	}
	j.broadcastLocked()
	j.mu.Unlock()
}

func (j *searchJob) checkSlowSubscribersLocked() {
	limit := j.svc.subscriberBuffer
	if limit <= 0 {
		limit = defaultSubscriberBuffer
	}
	total := len(j.events)
	for id, sub := range j.subs {
		base := sub.readIdx
		if sub.liveFloor > base {
			base = sub.liveFloor
		}
		if total-base > limit {
			sub.slowDisconnected = true
			delete(j.subs, id)
		}
	}
	if !j.done && len(j.subs) == 0 && j.graceTimer == nil {
		jobID := j.id
		j.graceTimer = time.AfterFunc(j.svc.timings.LastSubscriberGrace, func() {
			j.svc.cancelIfNoSubscribers(jobID)
		})
	}
}

func (j *searchJob) broadcastLocked() {
	close(j.broadcast)
	j.broadcast = make(chan struct{})
}

func (j *searchJob) addSubscriber(afterSeq int64) *Subscription {
	j.mu.Lock()
	defer j.mu.Unlock()

	if j.idleTimer != nil {
		j.idleTimer.Stop()
		j.idleTimer = nil
	}
	if j.graceTimer != nil {
		j.graceTimer.Stop()
		j.graceTimer = nil
	}

	startIdx := 0
	for startIdx < len(j.events) && j.events[startIdx].Seq <= afterSeq {
		startIdx++
	}

	j.nextSubID++
	sub := &Subscription{
		id:        j.nextSubID,
		job:       j,
		readIdx:   startIdx,
		liveFloor: len(j.events),
	}
	j.subs[sub.id] = sub
	return sub
}

// Subscription streams SSE events from a search job without blocking upstream providers.
type Subscription struct {
	id               uint64
	job              *searchJob
	readIdx          int
	liveFloor        int
	slowDisconnected bool
	closeOnce        sync.Once
}

// Next returns the next SSE event, a keepalive frame after KeepaliveInterval, io.EOF at completion,
// or ErrSlowSubscriber if the consumer fell behind the live buffer.
func (sub *Subscription) Next(ctx context.Context) (Event, error) {
	for {
		sub.job.mu.Lock()
		if sub.slowDisconnected {
			sub.job.mu.Unlock()
			return Event{}, ErrSlowSubscriber
		}
		if sub.readIdx < len(sub.job.events) {
			ev := sub.job.events[sub.readIdx]
			sub.readIdx++
			sub.job.mu.Unlock()
			return ev, nil
		}
		if sub.job.done {
			sub.job.mu.Unlock()
			return Event{}, io.EOF
		}
		waitCh := sub.job.broadcast
		sub.job.mu.Unlock()

		timer := time.NewTimer(sub.job.svc.timings.KeepaliveInterval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return Event{}, ctx.Err()
		case <-waitCh:
			timer.Stop()
		case <-timer.C:
			return Event{Keepalive: true}, nil
		}
	}
}

// Close detaches the subscriber; if it was the last subscriber on a live job, starts the 10s grace timer.
func (sub *Subscription) Close() {
	sub.closeOnce.Do(func() {
		j := sub.job
		j.mu.Lock()
		delete(j.subs, sub.id)
		if !j.done && len(j.subs) == 0 && j.graceTimer == nil {
			jobID := j.id
			j.graceTimer = time.AfterFunc(j.svc.timings.LastSubscriberGrace, func() {
				j.svc.cancelIfNoSubscribers(jobID)
			})
		}
		j.mu.Unlock()
	})
}
