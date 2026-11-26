package recommend

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/navidrome"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/search"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const (
	nsRecommend    = "recommend"
	idHistory      = "history"
	idDailyCurrent = "daily_current"
	idSeenRecent   = "seen_recent"

	defaultTargetCount     = 30
	maxHistoryEntries      = 200
	historyDedupeWindowMS  = 120_000
	seenRetention          = 7 * 24 * time.Hour
	maxSeenEntries         = 500
	defaultKeepalive       = 10 * time.Second
	defaultJobRetention    = 16
	defaultMatchWorkers    = 4
	defaultCandidateWait   = 8 * time.Second
	defaultAITimeout       = 35 * time.Second
	defaultNavTimeout      = 4 * time.Second
	defaultOverallJobLimit = 90 * time.Second
	defaultITunesURL       = "https://itunes.apple.com/search"
)

// ErrSlowSubscriber indicates an SSE subscriber fell behind the live buffer.
var ErrSlowSubscriber = errors.New("客户端接收过慢，已断开推荐流")

// AICompleter executes a single stateless LLM completion without mutating chat sessions.
type AICompleter interface {
	DirectComplete(ctx context.Context, systemPromptText, userPromptText string) (string, bool, error)
}

// NavidromeReader queries user listening signals from Navidrome when enabled.
type NavidromeReader interface {
	Settings() navidrome.Settings
	GetAlbums(ctx context.Context, offset, size int, sortBy string) (navidrome.AlbumsPageDTO, error)
	GetAlbum(ctx context.Context, id string) (navidrome.AlbumDetailDTO, error)
	GetStarredSongs(ctx context.Context, limit int) ([]model.TrackDTO, error)
}

// ProviderCatalog looks up registered music providers.
type ProviderCatalog interface {
	Get(idOrEngine string) (model.Provider, bool)
	AvailableSources() []model.AvailableSourceDTO
}

// Options configures the recommendation service.
type Options struct {
	Store            *store.Store
	Providers        ProviderCatalog
	Transport        *transport.Pool
	AI               AICompleter
	Navidrome        NavidromeReader
	SnapshotProvider func() config.Snapshot
	Logger           provider.LogFunc
	ITunesSearchURL  string
	Now              func() time.Time
	TargetCount      int
	Keepalive        time.Duration
}

// Service coordinates listening history tracking, AI/rule candidate generation,
// multi-source online track matching, and SSE streaming for daily 30-track recommendations.
type Service struct {
	store           *store.Store
	providers       ProviderCatalog
	transport       *transport.Pool
	ai              AICompleter
	navidrome       NavidromeReader
	snapshotFn      func() config.Snapshot
	logger          provider.LogFunc
	itunesURL       string
	now             func() time.Time
	targetCount     int
	keepalivePeriod time.Duration

	baseCtx    context.Context
	baseCancel context.CancelFunc

	dataMu    sync.Mutex
	mu        sync.Mutex
	closed    bool
	jobs      map[string]*recommendJob
	jobSeq    []string
	activeJob *recommendJob
	wg        sync.WaitGroup
}

// NewService constructs a recommendation Service.
func NewService(opts Options) *Service {
	nowFn := opts.Now
	if nowFn == nil {
		nowFn = time.Now
	}
	target := opts.TargetCount
	if target <= 0 {
		target = defaultTargetCount
	}
	itunesURL := strings.TrimSpace(opts.ITunesSearchURL)
	if itunesURL == "" {
		itunesURL = defaultITunesURL
	}
	keepalive := opts.Keepalive
	if keepalive <= 0 {
		keepalive = defaultKeepalive
	}
	snapFn := opts.SnapshotProvider
	if snapFn == nil {
		snapFn = func() config.Snapshot {
			return config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())
		}
	}
	baseCtx, baseCancel := context.WithCancel(context.Background())
	return &Service{
		store:           opts.Store,
		providers:       opts.Providers,
		transport:       opts.Transport,
		ai:              opts.AI,
		navidrome:       opts.Navidrome,
		snapshotFn:      snapFn,
		logger:          opts.Logger,
		itunesURL:       itunesURL,
		now:             nowFn,
		targetCount:     target,
		keepalivePeriod: keepalive,
		baseCtx:         baseCtx,
		baseCancel:      baseCancel,
		jobs:            make(map[string]*recommendJob),
	}
}

func (s *Service) log(level, message, taskID string) {
	if s != nil && s.logger != nil && message != "" {
		s.logger(level, message, "recommend", taskID)
	}
}

// Close cancels active recommendation jobs and waits for background workers to exit.
func (s *Service) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	for _, j := range s.jobs {
		j.requestCancel()
	}
	s.mu.Unlock()
	s.baseCancel()
	s.wg.Wait()
	return nil
}

func (s *Service) todayKey() string {
	return s.now().In(time.Local).Format("2006-01-02")
}

// RecordHistory appends one or more playback events to durable listening history.
func (s *Service) RecordHistory(ctx context.Context, req HistoryReportRequest) (int, error) {
	if s.store == nil {
		return 0, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "存储服务未初始化")
	}
	var incoming []HistoryEntry
	if len(req.Items) > 0 {
		incoming = append(incoming, req.Items...)
	}
	if strings.TrimSpace(req.Title) != "" {
		incoming = append(incoming, HistoryEntry{
			TrackID:    req.TrackID,
			Title:      req.Title,
			Artist:     req.Artist,
			Album:      req.Album,
			Genre:      req.Genre,
			Source:     req.Source,
			DurationMS: req.DurationMS,
			PlayedMS:   req.PlayedMS,
			PlayedAt:   req.PlayedAt,
		})
	}
	nowMS := s.now().UTC().UnixMilli()
	valid := make([]HistoryEntry, 0, len(incoming))
	for _, e := range incoming {
		e.Title = strings.TrimSpace(e.Title)
		if e.Title == "" {
			continue
		}
		e.Artist = strings.TrimSpace(e.Artist)
		e.Album = strings.TrimSpace(e.Album)
		e.Genre = strings.TrimSpace(e.Genre)
		e.Source = strings.TrimSpace(e.Source)
		e.TrackID = strings.TrimSpace(e.TrackID)
		if e.PlayedAt <= 0 {
			e.PlayedAt = nowMS
		}
		valid = append(valid, e)
	}
	if len(valid) == 0 {
		return 0, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "播放历史曲目标题不能为空")
	}

	s.dataMu.Lock()
	defer s.dataMu.Unlock()

	existing, err := s.loadHistoryLocked(ctx)
	if err != nil {
		return 0, err
	}
	for _, e := range valid {
		key := TrackNormKey(e.Title, e.Artist)
		merged := false
		if key != "" {
			for i := range existing {
				if TrackNormKey(existing[i].Title, existing[i].Artist) != key {
					continue
				}
				diff := existing[i].PlayedAt - e.PlayedAt
				if diff < 0 {
					diff = -diff
				}
				if diff <= historyDedupeWindowMS {
					if e.PlayedAt > existing[i].PlayedAt {
						existing[i].PlayedAt = e.PlayedAt
					}
					if e.PlayedMS > existing[i].PlayedMS {
						existing[i].PlayedMS = e.PlayedMS
					}
					if e.DurationMS > existing[i].DurationMS {
						existing[i].DurationMS = e.DurationMS
					}
					if existing[i].TrackID == "" {
						existing[i].TrackID = e.TrackID
					}
					if existing[i].Album == "" {
						existing[i].Album = e.Album
					}
					if existing[i].Genre == "" {
						existing[i].Genre = e.Genre
					}
					if existing[i].Source == "" {
						existing[i].Source = e.Source
					}
					merged = true
					break
				}
			}
		}
		if !merged {
			existing = append(existing, e)
		}
	}
	sort.SliceStable(existing, func(i, j int) bool {
		return existing[i].PlayedAt > existing[j].PlayedAt
	})
	if len(existing) > maxHistoryEntries {
		existing = existing[:maxHistoryEntries]
	}
	raw, err := json.Marshal(existing)
	if err != nil {
		return 0, err
	}
	if _, err := s.store.PutDurableRecord(ctx, nsRecommend, idHistory, string(raw), 0); err != nil {
		return 0, err
	}
	return len(existing), nil
}

// ListHistory returns up to limit newest listening history records.
func (s *Service) ListHistory(ctx context.Context, limit int) ([]HistoryEntry, error) {
	if s.store == nil {
		return []HistoryEntry{}, nil
	}
	if limit <= 0 || limit > maxHistoryEntries {
		limit = 50
	}
	s.dataMu.Lock()
	list, err := s.loadHistoryLocked(ctx)
	s.dataMu.Unlock()
	if err != nil {
		return nil, err
	}
	if len(list) > limit {
		list = list[:limit]
	}
	return list, nil
}

func (s *Service) loadHistoryLocked(ctx context.Context) ([]HistoryEntry, error) {
	rec, ok, err := s.store.GetDurableRecord(ctx, nsRecommend, idHistory)
	if err != nil || !ok || strings.TrimSpace(rec.BodyJSON) == "" {
		return []HistoryEntry{}, err
	}
	var items []HistoryEntry
	if json.Unmarshal([]byte(rec.BodyJSON), &items) != nil || items == nil {
		return []HistoryEntry{}, nil
	}
	return items, nil
}

type seenRecord struct {
	Key    string `json:"key"`
	SeenAt int64  `json:"seen_at"`
}

func (s *Service) loadSeenLocked(ctx context.Context) map[string]int64 {
	out := make(map[string]int64)
	if s.store == nil {
		return out
	}
	rec, ok, err := s.store.GetDurableRecord(ctx, nsRecommend, idSeenRecent)
	if err != nil || !ok || strings.TrimSpace(rec.BodyJSON) == "" {
		return out
	}
	var rows []seenRecord
	if json.Unmarshal([]byte(rec.BodyJSON), &rows) != nil {
		return out
	}
	cutoff := s.now().UTC().Add(-seenRetention).UnixMilli()
	for _, r := range rows {
		if r.Key != "" && r.SeenAt >= cutoff {
			out[r.Key] = r.SeenAt
		}
	}
	return out
}

func (s *Service) recordSeenKeys(ctx context.Context, keys []string) {
	if s.store == nil || len(keys) == 0 {
		return
	}
	nowMS := s.now().UTC().UnixMilli()
	s.dataMu.Lock()
	defer s.dataMu.Unlock()
	m := s.loadSeenLocked(ctx)
	for _, k := range keys {
		if k != "" {
			m[k] = nowMS
		}
	}
	rows := make([]seenRecord, 0, len(m))
	for k, ts := range m {
		rows = append(rows, seenRecord{Key: k, SeenAt: ts})
	}
	sort.Slice(rows, func(i, j int) bool {
		return rows[i].SeenAt > rows[j].SeenAt
	})
	if len(rows) > maxSeenEntries {
		rows = rows[:maxSeenEntries]
	}
	if raw, err := json.Marshal(rows); err == nil {
		_, _ = s.store.PutDurableRecord(ctx, nsRecommend, idSeenRecent, string(raw), 0)
	}
}

func (s *Service) loadDailyCached(ctx context.Context) (DailyResponseDTO, bool) {
	if s.store == nil {
		return DailyResponseDTO{}, false
	}
	rec, ok, err := s.store.GetDurableRecord(ctx, nsRecommend, idDailyCurrent)
	if err != nil || !ok || strings.TrimSpace(rec.BodyJSON) == "" {
		return DailyResponseDTO{}, false
	}
	var dto DailyResponseDTO
	if json.Unmarshal([]byte(rec.BodyJSON), &dto) != nil {
		return DailyResponseDTO{}, false
	}
	if dto.StyleTags == nil {
		dto.StyleTags = []string{}
	}
	if dto.Items == nil {
		dto.Items = []DailyItemDTO{}
	}
	return dto, true
}

func (s *Service) saveDailyCached(ctx context.Context, dto DailyResponseDTO) {
	if s.store == nil {
		return
	}
	if raw, err := json.Marshal(dto); err == nil {
		_, _ = s.store.PutDurableRecord(ctx, nsRecommend, idDailyCurrent, string(raw), 0)
	}
}

// GetDaily returns the active generating snapshot if a job is running for today,
// or the cached ready snapshot for today, or an empty status snapshot.
func (s *Service) GetDaily(ctx context.Context) (DailyResponseDTO, error) {
	today := s.todayKey()
	s.mu.Lock()
	active := s.activeJob
	s.mu.Unlock()
	if active != nil && !active.isDone() && active.date == today {
		return active.snapshot(), nil
	}
	if cached, ok := s.loadDailyCached(ctx); ok && cached.Date == today && len(cached.Items) > 0 {
		cached.Status = "ready"
		cached.JobID = ""
		return cached, nil
	}
	return DailyResponseDTO{
		Date:        today,
		TargetCount: s.targetCount,
		Status:      "empty",
		StyleTags:   []string{},
		Items:       []DailyItemDTO{},
	}, nil
}

// StartDaily returns today's cached recommendation immediately when !req.Force,
// or starts/attaches a streaming generation job and returns its snapshot with JobID.
func (s *Service) StartDaily(ctx context.Context, snap config.Snapshot, req DailyStartRequest) (DailyResponseDTO, error) {
	today := s.todayKey()
	cached, hasCached := s.loadDailyCached(ctx)

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return DailyResponseDTO{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "推荐服务已关闭")
	}

	if !req.Force {
		if s.activeJob != nil && !s.activeJob.isDone() && s.activeJob.date == today {
			snapDTO := s.activeJob.snapshot()
			s.mu.Unlock()
			return snapDTO, nil
		}
		if !hasCached && s.activeJob != nil && s.activeJob.isDone() && s.activeJob.date == today {
			s.mu.Unlock()
			cached, hasCached = s.loadDailyCached(ctx)
			s.mu.Lock()
			if s.closed {
				s.mu.Unlock()
				return DailyResponseDTO{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "推荐服务已关闭")
			}
			if s.activeJob != nil && !s.activeJob.isDone() && s.activeJob.date == today {
				snapDTO := s.activeJob.snapshot()
				s.mu.Unlock()
				return snapDTO, nil
			}
		}
		if hasCached && cached.Date == today && len(cached.Items) > 0 {
			s.mu.Unlock()
			cached.Status = "ready"
			cached.JobID = ""
			return cached, nil
		}
	}

	var prevActive *recommendJob
	nextSeq := 1
	if hasCached && cached.Date == today {
		nextSeq = cached.RefreshSeq + 1
	}
	if s.activeJob != nil && s.activeJob.date == today && s.activeJob.refreshSeq >= nextSeq {
		nextSeq = s.activeJob.refreshSeq + 1
	}
	if req.Force && s.activeJob != nil && !s.activeJob.isDone() {
		prevActive = s.activeJob
	}
	s.mu.Unlock()

	if prevActive != nil {
		prevActive.requestCancel()
	}
	if req.Force && hasCached && len(cached.Items) > 0 {
		seenKeys := make([]string, 0, len(cached.Items))
		for _, it := range cached.Items {
			if k := TrackNormKey(it.Track.Title, it.Track.Artist); k != "" {
				seenKeys = append(seenKeys, k)
			}
		}
		s.recordSeenKeys(ctx, seenKeys)
	}

	jobCtx, jobCancel := context.WithTimeout(s.baseCtx, defaultOverallJobLimit)
	job := newRecommendJob(s, model.NewUUID(), today, nextSeq, s.targetCount, jobCtx, jobCancel)

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		jobCancel()
		return DailyResponseDTO{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "推荐服务已关闭")
	}
	for len(s.jobSeq) >= defaultJobRetention {
		evicted := false
		for idx, jid := range s.jobSeq {
			if j, ok := s.jobs[jid]; ok && j.isDone() {
				delete(s.jobs, jid)
				s.jobSeq = append(s.jobSeq[:idx], s.jobSeq[idx+1:]...)
				evicted = true
				break
			}
		}
		if !evicted {
			break
		}
	}
	s.jobs[job.id] = job
	s.jobSeq = append(s.jobSeq, job.id)
	s.activeJob = job
	s.wg.Add(1)
	s.mu.Unlock()

	s.log("info", fmt.Sprintf("开始生成每日推荐（日期=%s, 批次=%d, 强制刷新=%v）", today, nextSeq, req.Force), job.id)
	go func() {
		defer s.wg.Done()
		s.runJob(job, snap, req)
	}()

	return job.snapshot(), nil
}

// Subscribe attaches an SSE consumer to a recommendation job, replaying events after cursor.
func (s *Service) Subscribe(ctx context.Context, jobID string, after int64, lastEventID string) (*Subscription, error) {
	if after < 0 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "事件序号无效")
	}
	cursor := after
	if trimmed := strings.TrimSpace(lastEventID); trimmed != "" {
		v, err := strconv.ParseInt(trimmed, 10, 64)
		if err != nil || v < 0 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "事件序号无效")
		}
		if v > cursor {
			cursor = v
		}
	}
	s.mu.Lock()
	job, ok := s.jobs[strings.TrimSpace(jobID)]
	s.mu.Unlock()
	if !ok {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "推荐任务不存在或已过期")
	}
	return job.addSubscriber(cursor), nil
}

// Cancel stops a running recommendation job idempotently.
func (s *Service) Cancel(jobID string) error {
	s.mu.Lock()
	job, ok := s.jobs[strings.TrimSpace(jobID)]
	s.mu.Unlock()
	if !ok {
		return nil
	}
	job.requestCancel()
	return nil
}

// --- Taste Profiling & Candidate Generation ---

type tasteProfile struct {
	topTracks    []string
	topArtists   []string
	topGenres    []string
	favSummary   []string
	libraryIndex map[string]model.TrackDTO
	libraryPool  []model.TrackDTO
	seenSet      map[string]bool
	seenLabels   []string
}

type candSpec struct {
	Title    string `json:"title"`
	Artist   string `json:"artist"`
	Reason   string `json:"reason"`
	Category string `json:"category"` // "discover" | "familiar"
}

func (s *Service) buildTasteAndLibrary(ctx context.Context, req DailyStartRequest) tasteProfile {
	prof := tasteProfile{
		libraryIndex: make(map[string]model.TrackDTO),
		seenSet:      make(map[string]bool),
	}
	artistScore := make(map[string]float64)
	artistLabel := make(map[string]string)
	genreScore := make(map[string]float64)
	genreLabel := make(map[string]string)
	trackScore := make(map[string]float64)
	trackLabel := make(map[string]string)

	addArtist := func(artist string, w float64) {
		p := PrimaryArtist(artist)
		if p == "" {
			return
		}
		artistScore[p] += w
		if _, ok := artistLabel[p]; !ok {
			artistLabel[p] = strings.TrimSpace(artist)
		}
	}
	addGenre := func(genre string, w float64) {
		g := strings.TrimSpace(genre)
		if g == "" || strings.EqualFold(g, "local") || strings.EqualFold(g, "navidrome") || strings.EqualFold(g, "melokeep") {
			return
		}
		key := strings.ToLower(g)
		genreScore[key] += w
		if _, ok := genreLabel[key]; !ok {
			genreLabel[key] = g
		}
	}
	addTrackTaste := func(title, artist string, w float64) {
		k := TrackNormKey(title, artist)
		if k == "" {
			return
		}
		trackScore[k] += w
		if _, ok := trackLabel[k]; !ok {
			if strings.TrimSpace(artist) != "" {
				trackLabel[k] = fmt.Sprintf("%s - %s", strings.TrimSpace(title), strings.TrimSpace(artist))
			} else {
				trackLabel[k] = strings.TrimSpace(title)
			}
		}
		addArtist(artist, w)
	}
	addLibraryTrack := func(dto model.TrackDTO) {
		k := TrackNormKey(dto.Title, dto.Artist)
		if k == "" {
			return
		}
		if _, exists := prof.libraryIndex[k]; !exists {
			prof.libraryIndex[k] = dto
			prof.libraryPool = append(prof.libraryPool, dto)
		}
	}

	now := s.now().UTC()
	if s.store != nil {
		s.dataMu.Lock()
		hist, _ := s.loadHistoryLocked(ctx)
		seenMap := s.loadSeenLocked(ctx)
		s.dataMu.Unlock()

		for _, h := range hist {
			ageDays := now.Sub(time.UnixMilli(h.PlayedAt)).Hours() / 24.0
			if ageDays < 0 {
				ageDays = 0
			}
			w := 1.0 / (1.0 + ageDays*0.35)
			addTrackTaste(h.Title, h.Artist, w)
			addGenre(h.Genre, w*0.8)
		}
		for k := range seenMap {
			prof.seenSet[k] = true
			if parts := strings.SplitN(k, "|", 2); len(parts) == 2 && len(prof.seenLabels) < 40 {
				prof.seenLabels = append(prof.seenLabels, parts[0]+" - "+parts[1])
			}
		}

		if favRecs, err := s.store.ListDurableRecords(ctx, "favorite", 500); err == nil {
			for _, rec := range favRecs {
				var dto model.TrackDTO
				if json.Unmarshal([]byte(rec.BodyJSON), &dto) == nil && strings.TrimSpace(dto.Title) != "" {
					addLibraryTrack(dto)
					addTrackTaste(dto.Title, dto.Artist, 1.6)
					if len(prof.favSummary) < 12 {
						prof.favSummary = append(prof.favSummary, fmt.Sprintf("%s - %s", dto.Title, dto.Artist))
					}
				}
			}
		}

		if page, err := s.store.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 120, Sort: "updated_at"}); err == nil {
			for _, item := range page.Items {
				if item.Track != nil && strings.TrimSpace(item.Track.Title) != "" {
					addLibraryTrack(*item.Track)
					if g := strings.TrimSpace(item.Metadata["genre"]); g != "" {
						addGenre(g, 0.7)
					}
				}
			}
		}
	}

	for _, seed := range req.Seeds {
		if strings.TrimSpace(seed.Title) == "" && strings.TrimSpace(seed.Artist) == "" {
			continue
		}
		w := seed.Weight
		if w <= 0 {
			w = 1.0
		}
		if strings.TrimSpace(seed.Title) != "" {
			addTrackTaste(seed.Title, seed.Artist, w)
		} else {
			addArtist(seed.Artist, w)
		}
		addGenre(seed.Genre, w*0.8)
	}

	if s.navidrome != nil && s.navidrome.Settings().Enabled {
		navCtx, cancel := context.WithTimeout(ctx, defaultNavTimeout)
		if starred, err := s.navidrome.GetStarredSongs(navCtx, 25); err == nil {
			for _, tr := range starred {
				addLibraryTrack(tr)
				addTrackTaste(tr.Title, tr.Artist, 1.5)
				if len(prof.favSummary) < 12 {
					prof.favSummary = append(prof.favSummary, fmt.Sprintf("%s - %s", tr.Title, tr.Artist))
				}
			}
		}
		if albums, err := s.navidrome.GetAlbums(navCtx, 0, 5, "recent"); err == nil {
			limit := len(albums.Albums)
			if limit > 4 {
				limit = 4
			}
			for i := 0; i < limit; i++ {
				if detail, aErr := s.navidrome.GetAlbum(navCtx, albums.Albums[i].ID); aErr == nil {
					for j, tr := range detail.Tracks {
						addLibraryTrack(tr)
						if j < 3 {
							addTrackTaste(tr.Title, tr.Artist, 0.75)
						}
					}
				}
			}
		}
		cancel()
	}

	prof.topTracks = topKeysByScore(trackScore, trackLabel, 30)
	prof.topArtists = topKeysByScore(artistScore, artistLabel, 12)
	prof.topGenres = topKeysByScore(genreScore, genreLabel, 8)
	return prof
}

func topKeysByScore(scores map[string]float64, labels map[string]string, limit int) []string {
	type pair struct {
		key   string
		score float64
	}
	list := make([]pair, 0, len(scores))
	for k, sc := range scores {
		list = append(list, pair{key: k, score: sc})
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].score == list[j].score {
			return list[i].key < list[j].key
		}
		return list[i].score > list[j].score
	})
	if len(list) > limit {
		list = list[:limit]
	}
	out := make([]string, 0, len(list))
	for _, item := range list {
		lbl := strings.TrimSpace(labels[item.key])
		if lbl != "" {
			out = append(out, lbl)
		}
	}
	return out
}

type aiCurateJSON struct {
	CuratorNote string     `json:"curator_note"`
	StyleTags   []string   `json:"style_tags"`
	Candidates  []candSpec `json:"candidates"`
}

func parseAICurateJSON(raw string) (aiCurateJSON, bool) {
	cleaned := strings.TrimSpace(raw)
	if cleaned == "" {
		return aiCurateJSON{}, false
	}
	if strings.HasPrefix(cleaned, "```") {
		if idx := strings.IndexByte(cleaned, '\n'); idx >= 0 {
			cleaned = cleaned[idx+1:]
		}
		if end := strings.LastIndex(cleaned, "```"); end >= 0 {
			cleaned = cleaned[:end]
		}
		cleaned = strings.TrimSpace(cleaned)
	}
	start := strings.IndexByte(cleaned, '{')
	end := strings.LastIndexByte(cleaned, '}')
	if start >= 0 && end > start {
		cleaned = cleaned[start : end+1]
	}
	var parsed aiCurateJSON
	if err := json.Unmarshal([]byte(cleaned), &parsed); err != nil {
		return aiCurateJSON{}, false
	}
	return parsed, len(parsed.Candidates) > 0
}

var coldStartQueries = []struct {
	Query string
	Tag   string
}{
	{Query: "Lo-Fi Chill", Tag: "Lo-Fi律动"},
	{Query: "Indie Pop", Tag: "独立流行"},
	{Query: "City Pop", Tag: "都市复古"},
	{Query: "Acoustic Folk", Tag: "原声民谣"},
	{Query: "Ambient Electronic", Tag: "氛围电音"},
	{Query: "Cinematic Piano", Tag: "钢琴器乐"},
}

type curationPlan struct {
	Mode        string
	CuratorNote string
	StyleTags   []string
	Candidates  []candSpec
}

func (s *Service) generateCandidates(ctx context.Context, snap config.Snapshot, prof tasteProfile, req DailyStartRequest) curationPlan {
	plan := curationPlan{
		Mode:      "rule",
		StyleTags: []string{},
	}
	candSeen := make(map[string]bool)
	appendCandidate := func(c candSpec) {
		c.Title = strings.TrimSpace(c.Title)
		c.Artist = strings.TrimSpace(c.Artist)
		c.Reason = strings.TrimSpace(c.Reason)
		if c.Title == "" {
			return
		}
		k := TrackNormKey(c.Title, c.Artist)
		if k == "" || prof.seenSet[k] || candSeen[k] {
			return
		}
		candSeen[k] = true
		if c.Category != "familiar" {
			c.Category = "discover"
		}
		if _, inLib := prof.libraryIndex[k]; inLib && c.Category == "discover" {
			// Skip already-owned library tracks from "discover" candidates so online discovery stays fresh.
			return
		}
		if c.Reason == "" {
			if c.Category == "familiar" {
				c.Reason = "来自你的熟悉曲风延伸"
			} else {
				c.Reason = "符合你近期的听歌口味"
			}
		}
		plan.Candidates = append(plan.Candidates, c)
	}

	// 1. Primary AI curation when AI service is configured.
	if s.ai != nil {
		aiCtx, cancel := context.WithTimeout(ctx, defaultAITimeout)
		sysPrompt := "你是 MeloKeep 每日私人音乐策展引擎。请根据用户最近的听歌风格，生成每日推荐歌单。仅输出合法 JSON，不要包含 Markdown 代码块或任何额外解释。"
		var userB strings.Builder
		userB.WriteString("请基于以下听歌画像，挑选 48 首适合当日循环的歌曲（约 38 首 category=\"discover\" 的全网新歌/同风探索，约 10 首 category=\"familiar\" 的常听歌手冷门佳作或熟悉延伸）：\n")
		if len(prof.topTracks) > 0 {
			userB.WriteString("最近常听曲目：")
			userB.WriteString(strings.Join(prof.topTracks, "；"))
			userB.WriteByte('\n')
		}
		if len(prof.topArtists) > 0 {
			userB.WriteString("常听歌手：")
			userB.WriteString(strings.Join(prof.topArtists, "、"))
			userB.WriteByte('\n')
		}
		if len(prof.topGenres) > 0 {
			userB.WriteString("偏爱风格：")
			userB.WriteString(strings.Join(prof.topGenres, "、"))
			userB.WriteByte('\n')
		}
		if len(prof.favSummary) > 0 {
			userB.WriteString("收藏参考：")
			userB.WriteString(strings.Join(prof.favSummary, "；"))
			userB.WriteByte('\n')
		}
		if len(prof.topTracks) == 0 && len(prof.topArtists) == 0 && len(prof.favSummary) == 0 {
			userB.WriteString("当前为冷启动新用户，请跨流派精选华语独立、欧美流行、Lo-Fi、City Pop、民谣与电影感器乐的优质代表作。\n")
		}
		if req.Force {
			userB.WriteString("提示：本次为用户手动点击「换一批」，请换一批不同角度的歌曲。\n")
		}
		if len(prof.seenLabels) > 0 {
			userB.WriteString("近7天已推过（请勿重复）：")
			userB.WriteString(strings.Join(prof.seenLabels, "；"))
			userB.WriteByte('\n')
		}
		userB.WriteString(`严格返回 JSON 格式：{"curator_note":"不超过28字的今日导语","style_tags":["标签1","标签2","标签3"],"candidates":[{"title":"歌名","artist":"歌手","reason":"不超过16字推荐理由","category":"discover"}]}`)

		if rawReply, configured, err := s.ai.DirectComplete(aiCtx, sysPrompt, userB.String()); err == nil && configured {
			if parsed, ok := parseAICurateJSON(rawReply); ok {
				for _, c := range parsed.Candidates {
					appendCandidate(c)
				}
				if len(plan.Candidates) >= 8 {
					plan.Mode = "ai"
					plan.CuratorNote = strings.TrimSpace(parsed.CuratorNote)
					for _, tg := range parsed.StyleTags {
						cleanTag := strings.TrimSpace(strings.TrimPrefix(tg, "#"))
						if cleanTag != "" && len(plan.StyleTags) < 3 {
							plan.StyleTags = append(plan.StyleTags, cleanTag)
						}
					}
				}
			}
		} else if err != nil {
			s.log("warning", "AI 推荐生成失败，已自动降级为规则引擎: "+err.Error(), "")
		}
		cancel()
	}

	// 2. Rule / public iTunes catalog fallback & top-up when AI is unconfigured or short.
	desiredPool := s.targetCount + 18
	if len(plan.Candidates) < desiredPool {
		var queries []struct {
			q      string
			reason string
		}
		for i, art := range prof.topArtists {
			if i >= 4 {
				break
			}
			queries = append(queries, struct {
				q      string
				reason string
			}{
				q:      art,
				reason: fmt.Sprintf("延伸自你常听的 %s", art),
			})
		}
		for i, gen := range prof.topGenres {
			if i >= 2 {
				break
			}
			queries = append(queries, struct {
				q      string
				reason string
			}{
				q:      gen,
				reason: fmt.Sprintf("契合你的 %s 风格偏好", gen),
			})
		}
		if len(queries) < 3 {
			for _, cs := range coldStartQueries {
				queries = append(queries, struct {
					q      string
					reason string
				}{
					q:      cs.Query,
					reason: fmt.Sprintf("精选%s风格推荐", cs.Tag),
				})
			}
		}

		// Offset query order on manual refresh so consecutive refreshes explore different seeds.
		if req.Force && len(queries) > 1 {
			rng := rand.New(rand.NewSource(s.now().UnixNano()))
			rng.Shuffle(len(queries), func(i, j int) {
				queries[i], queries[j] = queries[j], queries[i]
			})
		}

		for i, qSpec := range queries {
			if len(plan.Candidates) >= desiredPool || i >= 5 || ctx.Err() != nil {
				break
			}
			rows := s.queryITunesCatalog(ctx, snap, qSpec.q, 12)
			for _, row := range rows {
				appendCandidate(candSpec{
					Title:    row.Title,
					Artist:   row.Artist,
					Reason:   qSpec.reason,
					Category: "discover",
				})
				if len(plan.Candidates) >= desiredPool {
					break
				}
			}
		}

		// Mix in familiar library candidates as safety net.
		for _, lt := range prof.libraryPool {
			appendCandidate(candSpec{
				Title:    lt.Title,
				Artist:   lt.Artist,
				Reason:   "曲库私藏·随时重温",
				Category: "familiar",
			})
			if len(plan.Candidates) >= desiredPool+10 {
				break
			}
		}
	}

	if plan.CuratorNote == "" {
		if len(prof.topArtists) > 0 {
			plan.CuratorNote = fmt.Sprintf("结合你近期常听的 %s 等风格，精选今日全网好歌", prof.topArtists[0])
		} else {
			plan.CuratorNote = "每日 30 首全网发现与私藏延展，开启今日好心情"
		}
	}
	if len(plan.StyleTags) == 0 {
		for _, g := range prof.topGenres {
			if len(plan.StyleTags) < 3 {
				plan.StyleTags = append(plan.StyleTags, g)
			}
		}
		for _, cs := range coldStartQueries {
			if len(plan.StyleTags) >= 3 {
				break
			}
			plan.StyleTags = append(plan.StyleTags, cs.Tag)
		}
	}
	return plan
}

type catalogCandidate struct {
	Title  string
	Artist string
}

func (s *Service) queryITunesCatalog(ctx context.Context, snap config.Snapshot, term string, limit int) []catalogCandidate {
	if s.transport == nil || strings.TrimSpace(term) == "" {
		return nil
	}
	qCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()

	endpoint := s.itunesURL + "?term=" + url.QueryEscape(strings.TrimSpace(term)) + "&media=music&entity=song&limit=" + strconv.Itoa(limit)
	req, err := http.NewRequestWithContext(qCtx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil
	}
	req.Header.Set("User-Agent", provider.DefaultUserAgent)

	rc := provider.RequestContext{Config: snap}
	proxyURL, _, _ := provider.SourceRequestOptions(rc, "ITunesMusicClient")
	resp, err := s.transport.Do(qCtx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         proxyURL,
		Timeout:          6 * time.Second,
		MaxResponseBytes: 2 << 20,
		Idempotent:       true,
	})
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil
	}
	var parsed struct {
		Results []struct {
			TrackName  string `json:"trackName"`
			ArtistName string `json:"artistName"`
		} `json:"results"`
	}
	if json.Unmarshal(body, &parsed) != nil {
		return nil
	}
	out := make([]catalogCandidate, 0, len(parsed.Results))
	for _, r := range parsed.Results {
		if strings.TrimSpace(r.TrackName) != "" {
			out = append(out, catalogCandidate{
				Title:  strings.TrimSpace(r.TrackName),
				Artist: strings.TrimSpace(r.ArtistName),
			})
		}
	}
	return out
}

// --- Execution & Multi-Source Online Matching ---

type activeProvider struct {
	publicID   string
	engineName string
	prov       model.Provider
}

func (s *Service) resolveSearchProviders(snap config.Snapshot) []activeProvider {
	if s.providers == nil {
		return nil
	}
	seen := make(map[string]bool)
	var out []activeProvider
	tryAdd := func(id string) {
		eng := config.CanonicalEngineName(id)
		p, ok := s.providers.Get(eng)
		if !ok {
			p, ok = s.providers.Get(strings.TrimSpace(id))
		}
		if !ok || p == nil || !p.Capabilities().Search {
			return
		}
		canon := p.EngineName()
		if canon == "" {
			canon = p.ID()
		}
		if seen[canon] {
			return
		}
		seen[canon] = true
		pub := config.PublicSourceID(p.ID())
		if pub == "" {
			pub = config.PublicSourceID(canon)
		}
		out = append(out, activeProvider{
			publicID:   pub,
			engineName: canon,
			prov:       p,
		})
	}
	for _, raw := range snap.Runtime.Sources {
		tryAdd(raw)
	}
	if len(out) == 0 {
		for _, avail := range s.providers.AvailableSources() {
			if avail.Capability == nil || avail.Capability.Search {
				tryAdd(avail.EngineName)
			}
		}
	}
	return out
}

func (s *Service) persistMatchedTrack(ctx context.Context, tr model.ProviderTrack) (model.TrackDTO, error) {
	if tr.PublicID == "" {
		tr.PublicID = model.NewUUID()
	}
	locator := strings.TrimSpace(tr.SourceID)
	if locator == "" {
		locator = tr.PublicID
	}
	if s.store != nil {
		metaJSON := "{}"
		if rawMeta, mErr := json.Marshal(map[string]any{
			"cover":          tr.Cover,
			"size_bytes":     tr.SizeBytes,
			"size_label":     tr.SizeLabel,
			"episodes":       tr.Episodes,
			"playlist_index": tr.PlaylistIndex,
			"lyrics":         tr.Lyrics,
			"pay_required":   tr.PayRequired,
		}); mErr == nil {
			metaJSON = string(rawMeta)
		}
		trackID, err := s.store.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
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
		}, tr.Variants)
		if err != nil {
			return model.TrackDTO{}, err
		}
		if err := s.store.PutPublicRef(ctx, model.PublicRef{
			PublicID: tr.PublicID,
			Kind:     "candidate",
			TargetID: trackID,
			Source:   tr.Source,
			SourceID: tr.SourceID,
		}, ""); err != nil {
			return model.TrackDTO{}, err
		}
	}
	playback := search.PendingTrackPlayback()
	if len(tr.Variants) > 0 {
		playback = model.PlaybackDTOFromResolvedTrack(tr, "")
	}
	dto := model.TrackDTOFromDomain(tr, playback)
	if s.store != nil {
		if rawDTO, dErr := json.Marshal(dto); dErr == nil {
			_, _ = s.store.PutDurableRecord(ctx, "track_dto", dto.ID, string(rawDTO), 0)
		}
	}
	return dto, nil
}

func (s *Service) searchBestProviderMatch(ctx context.Context, snap config.Snapshot, providers []activeProvider, cand candSpec) (model.ProviderTrack, int, bool) {
	if len(providers) == 0 {
		return model.ProviderTrack{}, 0, false
	}
	keyword := strings.TrimSpace(cand.Title + " " + cand.Artist)
	if keyword == "" {
		return model.ProviderTrack{}, 0, false
	}
	candCtx, cancel := context.WithTimeout(ctx, defaultCandidateWait)
	defer cancel()

	type scoredTrack struct {
		tr    model.ProviderTrack
		score int
	}
	ch := make(chan scoredTrack, len(providers)*5)
	var wg sync.WaitGroup

	for _, ap := range providers {
		wg.Add(1)
		go func(src activeProvider) {
			defer wg.Done()
			reqCtx := provider.WithRequestContext(candCtx, provider.RequestContext{
				Config:  snap,
				LogFunc: s.logger,
			})
			_, _ = src.prov.Search(reqCtx, model.SearchOptions{
				Keyword: keyword,
				Limit:   5,
			}, func(tr model.ProviderTrack) error {
				if candCtx.Err() != nil {
					return candCtx.Err()
				}
				tr.Source = src.publicID
				if tr.RootSource == "" {
					tr.RootSource = src.engineName
				}
				if tr.PublicID == "" {
					tr.PublicID = model.NewUUID()
				}
				if score, ok := ScoreCandidateMatch(cand.Title, cand.Artist, tr); ok {
					select {
					case ch <- scoredTrack{tr: tr, score: score}:
					case <-candCtx.Done():
					}
				}
				return nil
			})
		}(ap)
	}

	wg.Wait()
	close(ch)

	var best scoredTrack
	found := false
	for st := range ch {
		if !found || st.score > best.score {
			best = st
			found = true
		}
	}
	return best.tr, best.score, found
}

func (s *Service) runJob(job *recommendJob, snap config.Snapshot, req DailyStartRequest) {
	defer func() {
		if job.ctx.Err() != nil && job.isCancelled() {
			job.finishTerminal(s.now(), true, "")
		}
	}()

	job.emitStatus("analyzing", "正在分析你最近的听歌品味…", 0)
	prof := s.buildTasteAndLibrary(job.ctx, req)
	if job.ctx.Err() != nil {
		job.finishTerminal(s.now(), true, "")
		return
	}

	job.emitStatus("curating", "正在挑选今日全网推荐曲目…", 0)
	plan := s.generateCandidates(job.ctx, snap, prof, req)
	if job.ctx.Err() != nil {
		job.finishTerminal(s.now(), true, "")
		return
	}

	job.setMeta(plan.Mode, plan.CuratorNote, plan.StyleTags)
	job.emitMeta()
	job.emitStatus("matching", "正在全网多源匹配曲目…", 0)

	providers := s.resolveSearchProviders(snap)
	target := job.targetCount
	familiarCap := target / 5 // 20% familiar / library extensions
	if target >= 5 && familiarCap == 0 {
		familiarCap = 1
	}

	var matchMu sync.Mutex
	emittedKeys := make(map[string]bool)
	familiarCount := 0
	discoverCount := 0

	tryEmitItem := func(item DailyItemDTO) (added bool, reached bool) {
		normKey := TrackNormKey(item.Track.Title, item.Track.Artist)
		if normKey == "" {
			return false, false
		}
		matchMu.Lock()
		defer matchMu.Unlock()
		if job.currentCount() >= target {
			return false, true
		}
		if emittedKeys[normKey] {
			return false, false
		}
		if item.Category == "familiar" && familiarCount >= familiarCap && len(providers) > 0 {
			if item.InLibrary {
				return false, false
			}
			item.Category = "discover"
		}
		if item.Category == "discover" && item.InLibrary && familiarCount >= familiarCap && len(providers) > 0 {
			return false, false
		}
		emittedKeys[normKey] = true
		if item.Category == "familiar" {
			familiarCount++
		} else {
			discoverCount++
		}
		idx := job.appendItem(item)
		matched := idx + 1
		reached = matched >= target

		job.emitItem(idx, item)
		job.emitStatus("matching", fmt.Sprintf("已为您匹配 %d/%d 首好歌…", matched, target), matched)
		return true, reached
	}

	// Step 1: Quickly seed up to familiarCap items from library tracks matching familiar candidates or library pool.
	var onlineQueue []candSpec
	for _, cand := range plan.Candidates {
		k := TrackNormKey(cand.Title, cand.Artist)
		if cand.Category == "familiar" {
			if libDTO, ok := prof.libraryIndex[k]; ok && !prof.seenSet[k] {
				_, reached := tryEmitItem(DailyItemDTO{
					Track:     libDTO,
					Reason:    cand.Reason,
					Category:  "familiar",
					InLibrary: true,
				})
				if reached {
					break
				}
				continue
			}
		}
		onlineQueue = append(onlineQueue, cand)
	}
	if familiarCount < familiarCap && len(prof.libraryPool) > 0 {
		// Prefer unseen library tracks matching user's favorite/top artists.
		favArtists := make(map[string]bool, len(prof.topArtists))
		for _, a := range prof.topArtists {
			if pa := PrimaryArtist(a); pa != "" {
				favArtists[pa] = true
			}
		}
		for pass := 0; pass < 2 && familiarCount < familiarCap; pass++ {
			for _, lt := range prof.libraryPool {
				if familiarCount >= familiarCap {
					break
				}
				lk := TrackNormKey(lt.Title, lt.Artist)
				if lk == "" || prof.seenSet[lk] {
					continue
				}
				if pass == 0 && len(favArtists) > 0 && !favArtists[PrimaryArtist(lt.Artist)] {
					continue
				}
				reason := "曲库私藏·今日重温"
				if pass == 0 {
					reason = fmt.Sprintf("来自你常听的 %s", strings.TrimSpace(lt.Artist))
				}
				_, _ = tryEmitItem(DailyItemDTO{
					Track:     lt,
					Reason:    reason,
					Category:  "familiar",
					InLibrary: true,
				})
			}
		}
	}

	// Step 2: Match discover (and non-library familiar) candidates concurrently against online providers.
	if len(providers) > 0 && job.currentCount() < target && len(onlineQueue) > 0 {
		matchCtx, matchCancel := context.WithCancel(job.ctx)
		candCh := make(chan candSpec, len(onlineQueue))
		for _, c := range onlineQueue {
			candCh <- c
		}
		close(candCh)

		var workersWG sync.WaitGroup
		for w := 0; w < defaultMatchWorkers; w++ {
			workersWG.Add(1)
			go func() {
				defer workersWG.Done()
				for cand := range candCh {
					if matchCtx.Err() != nil || job.currentCount() >= target {
						return
					}
					tr, _, ok := s.searchBestProviderMatch(matchCtx, snap, providers, cand)
					if !ok || matchCtx.Err() != nil {
						continue
					}
					dto, pErr := s.persistMatchedTrack(matchCtx, tr)
					if pErr != nil {
						continue
					}
					nk := TrackNormKey(dto.Title, dto.Artist)
					_, inLib := prof.libraryIndex[nk]
					_, reached := tryEmitItem(DailyItemDTO{
						Track:     dto,
						Reason:    cand.Reason,
						Category:  cand.Category,
						InLibrary: inLib,
					})
					if reached {
						matchCancel()
						return
					}
				}
			}()
		}
		workersWG.Wait()
		matchCancel()
	}

	// Step 3: Direct style-tag discovery backfill on enabled online providers if still below target.
	if len(providers) > 0 && job.currentCount() < target && job.ctx.Err() == nil {
		var discoverySeeds []struct {
			keyword string
			reason  string
		}
		for _, tg := range plan.StyleTags {
			discoverySeeds = append(discoverySeeds, struct {
				keyword string
				reason  string
			}{keyword: tg, reason: fmt.Sprintf("全网 %s 风格发现", tg)})
		}
		for _, art := range prof.topArtists {
			discoverySeeds = append(discoverySeeds, struct {
				keyword string
				reason  string
			}{keyword: art, reason: fmt.Sprintf("全网搜寻 %s 相关佳作", art)})
		}
		for _, cs := range coldStartQueries {
			discoverySeeds = append(discoverySeeds, struct {
				keyword string
				reason  string
			}{keyword: cs.Query, reason: fmt.Sprintf("全网发现·%s", cs.Tag)})
		}

		for _, ds := range discoverySeeds {
			if job.currentCount() >= target || job.ctx.Err() != nil {
				break
			}
			for _, ap := range providers {
				if job.currentCount() >= target || job.ctx.Err() != nil {
					break
				}
				searchCtx, cancel := context.WithTimeout(job.ctx, defaultCandidateWait)
				reqCtx := provider.WithRequestContext(searchCtx, provider.RequestContext{
					Config:  snap,
					LogFunc: s.logger,
				})
				var found []model.ProviderTrack
				_, _ = ap.prov.Search(reqCtx, model.SearchOptions{
					Keyword: ds.keyword,
					Limit:   10,
				}, func(tr model.ProviderTrack) error {
					tr.Source = ap.publicID
					if tr.RootSource == "" {
						tr.RootSource = ap.engineName
					}
					if tr.PublicID == "" {
						tr.PublicID = model.NewUUID()
					}
					found = append(found, tr)
					return nil
				})
				cancel()
				for _, tr := range found {
					if job.currentCount() >= target || job.ctx.Err() != nil {
						break
					}
					nk := TrackNormKey(tr.Title, tr.Artist)
					if nk == "" || prof.seenSet[nk] || emittedKeys[nk] {
						continue
					}
					dto, pErr := s.persistMatchedTrack(job.ctx, tr)
					if pErr != nil {
						continue
					}
					_, inLib := prof.libraryIndex[nk]
					_, _ = tryEmitItem(DailyItemDTO{
						Track:     dto,
						Reason:    ds.reason,
						Category:  "discover",
						InLibrary: inLib,
					})
				}
			}
		}
	}

	// Step 4: Last-resort library backfill if online sources could not reach target.
	if job.currentCount() < target && len(prof.libraryPool) > 0 && job.ctx.Err() == nil {
		for _, lt := range prof.libraryPool {
			if job.currentCount() >= target {
				break
			}
			lk := TrackNormKey(lt.Title, lt.Artist)
			if lk == "" || emittedKeys[lk] {
				continue
			}
			matchMu.Lock()
			if emittedKeys[lk] || job.currentCount() >= target {
				matchMu.Unlock()
				continue
			}
			emittedKeys[lk] = true
			idx := job.appendItem(DailyItemDTO{
				Track:     lt,
				Reason:    "曲库私藏·每日补充",
				Category:  "familiar",
				InLibrary: true,
			})
			matched := idx + 1
			matchMu.Unlock()
			job.emitItem(idx, job.itemAt(idx))
			job.emitStatus("matching", fmt.Sprintf("已为您匹配 %d/%d 首好歌…", matched, target), matched)
		}
	}

	if job.ctx.Err() != nil {
		job.finishTerminal(s.now(), true, "")
		return
	}

	// Commit SQLite cache and seen history BEFORE finishing the job so SSE consumers
	// observing "done" immediately see cached ready state on subsequent requests.
	finishedAt := s.now()
	finalSnap := job.previewTerminalSnapshot(finishedAt)
	if len(finalSnap.Items) > 0 {
		s.saveDailyCached(s.baseCtx, finalSnap)
		seenKeys := make([]string, 0, len(finalSnap.Items))
		for _, it := range finalSnap.Items {
			if k := TrackNormKey(it.Track.Title, it.Track.Artist); k != "" {
				seenKeys = append(seenKeys, k)
			}
		}
		s.recordSeenKeys(s.baseCtx, seenKeys)
	}
	finalSnap = job.finishTerminal(finishedAt, false, "")
	s.log("info", fmt.Sprintf("每日推荐生成完成：日期=%s, 批次=%d, 模式=%s, 共 %d 首", finalSnap.Date, finalSnap.RefreshSeq, finalSnap.Mode, len(finalSnap.Items)), job.id)
}

// --- Job & SSE Subscription Management ---

type recommendJob struct {
	svc         *Service
	id          string
	date        string
	refreshSeq  int
	targetCount int
	ctx         context.Context
	cancel      context.CancelFunc

	mu          sync.Mutex
	mode        string
	curatorNote string
	styleTags   []string
	items       []DailyItemDTO
	events      []Event
	done        bool
	cancelled   bool
	errMsg      string
	generatedAt int64
	broadcast   chan struct{}
	subs        map[uint64]*Subscription
	nextSubID   uint64
}

func newRecommendJob(svc *Service, id, date string, refreshSeq, targetCount int, ctx context.Context, cancel context.CancelFunc) *recommendJob {
	return &recommendJob{
		svc:         svc,
		id:          id,
		date:        date,
		refreshSeq:  refreshSeq,
		targetCount: targetCount,
		ctx:         ctx,
		cancel:      cancel,
		styleTags:   []string{},
		items:       []DailyItemDTO{},
		broadcast:   make(chan struct{}),
		subs:        make(map[uint64]*Subscription),
	}
}

func (j *recommendJob) requestCancel() {
	j.mu.Lock()
	if !j.done {
		j.cancelled = true
	}
	j.mu.Unlock()
	j.cancel()
}

func (j *recommendJob) isDone() bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.done
}

func (j *recommendJob) isCancelled() bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.cancelled
}

func (j *recommendJob) currentCount() int {
	j.mu.Lock()
	defer j.mu.Unlock()
	return len(j.items)
}

func (j *recommendJob) appendItem(it DailyItemDTO) int {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.items = append(j.items, it)
	return len(j.items) - 1
}

func (j *recommendJob) previewTerminalSnapshot(now time.Time) DailyResponseDTO {
	j.mu.Lock()
	defer j.mu.Unlock()
	snap := j.snapshotLocked()
	snap.JobID = ""
	snap.GeneratedAt = now.UTC().UnixMilli()
	if snap.Error != "" {
		snap.Status = "error"
	} else if len(snap.Items) > 0 {
		snap.Status = "ready"
	} else {
		snap.Status = "empty"
	}
	return snap
}

func (j *recommendJob) itemAt(idx int) DailyItemDTO {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.items[idx]
}

func (j *recommendJob) setMeta(mode, note string, tags []string) {
	j.mu.Lock()
	j.mode = mode
	j.curatorNote = note
	j.styleTags = append([]string(nil), tags...)
	j.mu.Unlock()
}

func (j *recommendJob) snapshot() DailyResponseDTO {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.snapshotLocked()
}

func (j *recommendJob) snapshotLocked() DailyResponseDTO {
	status := "generating"
	jobID := j.id
	if j.done {
		jobID = ""
		switch {
		case j.errMsg != "":
			status = "error"
		case len(j.items) > 0:
			status = "ready"
		default:
			status = "empty"
		}
	}
	tagsCopy := make([]string, len(j.styleTags))
	copy(tagsCopy, j.styleTags)
	itemsCopy := make([]DailyItemDTO, len(j.items))
	copy(itemsCopy, j.items)
	return DailyResponseDTO{
		Date:        j.date,
		RefreshSeq:  j.refreshSeq,
		TargetCount: j.targetCount,
		Status:      status,
		JobID:       jobID,
		Mode:        j.mode,
		CuratorNote: j.curatorNote,
		StyleTags:   tagsCopy,
		Items:       itemsCopy,
		GeneratedAt: j.generatedAt,
		Error:       j.errMsg,
	}
}

func (j *recommendJob) appendEvent(evType string, payload any) {
	raw, err := json.Marshal(payload)
	if err != nil {
		return
	}
	j.mu.Lock()
	if j.done {
		j.mu.Unlock()
		return
	}
	seq := int64(len(j.events) + 1)
	j.events = append(j.events, Event{
		Seq:  seq,
		Type: evType,
		Data: raw,
	})
	close(j.broadcast)
	j.broadcast = make(chan struct{})
	j.mu.Unlock()
}

func (j *recommendJob) emitStatus(stage, message string, matched int) {
	j.appendEvent("status", StatusEventPayload{
		Stage:   stage,
		Message: message,
		Matched: matched,
		Target:  j.targetCount,
	})
}

func (j *recommendJob) emitMeta() {
	j.mu.Lock()
	payload := MetaEventPayload{
		Date:        j.date,
		RefreshSeq:  j.refreshSeq,
		Mode:        j.mode,
		CuratorNote: j.curatorNote,
		StyleTags:   append([]string(nil), j.styleTags...),
		TargetCount: j.targetCount,
	}
	j.mu.Unlock()
	j.appendEvent("meta", payload)
}

func (j *recommendJob) emitItem(index int, item DailyItemDTO) {
	j.appendEvent("item", ItemEventPayload{
		Index: index,
		Item:  item,
	})
}

func (j *recommendJob) finishTerminal(now time.Time, cancelled bool, errMsg string) DailyResponseDTO {
	j.mu.Lock()
	if j.done {
		snap := j.snapshotLocked()
		j.mu.Unlock()
		return snap
	}
	j.done = true
	if cancelled {
		j.cancelled = true
	}
	if errMsg != "" {
		j.errMsg = errMsg
	}
	j.generatedAt = now.UTC().UnixMilli()
	snap := j.snapshotLocked()

	if errMsg != "" {
		if raw, err := json.Marshal(ErrorEventPayload{Detail: errMsg}); err == nil {
			seq := int64(len(j.events) + 1)
			j.events = append(j.events, Event{Seq: seq, Type: "error", Data: raw})
		}
	}
	if raw, err := json.Marshal(DoneEventPayload{Daily: snap, Cancelled: j.cancelled}); err == nil {
		seq := int64(len(j.events) + 1)
		j.events = append(j.events, Event{Seq: seq, Type: "done", Data: raw})
	}
	close(j.broadcast)
	j.broadcast = make(chan struct{})
	j.mu.Unlock()
	return snap
}

func (j *recommendJob) addSubscriber(afterSeq int64) *Subscription {
	j.mu.Lock()
	defer j.mu.Unlock()
	startIdx := 0
	for startIdx < len(j.events) && j.events[startIdx].Seq <= afterSeq {
		startIdx++
	}
	j.nextSubID++
	sub := &Subscription{
		id:      j.nextSubID,
		job:     j,
		readIdx: startIdx,
	}
	j.subs[sub.id] = sub
	return sub
}

// Subscription streams SSE events from a recommendation generation job.
type Subscription struct {
	id        uint64
	job       *recommendJob
	readIdx   int
	closeOnce sync.Once
}

// Next returns the next SSE event, a keepalive tick, or io.EOF once the job finishes and drains.
func (sub *Subscription) Next(ctx context.Context) (Event, error) {
	for {
		sub.job.mu.Lock()
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

		timer := time.NewTimer(sub.job.svc.keepalivePeriod)
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

// Close detaches the subscriber from the job.
func (sub *Subscription) Close() {
	sub.closeOnce.Do(func() {
		sub.job.mu.Lock()
		delete(sub.job.subs, sub.id)
		sub.job.mu.Unlock()
	})
}
