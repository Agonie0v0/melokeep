package download

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type taskSpec struct {
	downloadID         string
	snap               config.Snapshot
	allowPreview       bool
	allowExternalWrite bool
	rootID             string
	autoOrganize       *AutoOrganizeConfig
	outbox             *store.OutboxEnqueueParams
}

type taskControl struct {
	mu           sync.Mutex
	cancelFn     context.CancelFunc
	pauseReq     bool
	cancelReq    bool
	inPublishing bool
	pubDoneCh    chan struct{}
	doneCh       chan struct{}
}

func (tc *taskControl) setCancel(fn context.CancelFunc) {
	tc.mu.Lock()
	tc.cancelFn = fn
	tc.mu.Unlock()
}

func (tc *taskControl) requestPause() {
	tc.mu.Lock()
	tc.pauseReq = true
	fn := tc.cancelFn
	tc.mu.Unlock()
	if fn != nil {
		fn()
	}
}

func (tc *taskControl) requestCancel() {
	tc.mu.Lock()
	tc.cancelReq = true
	fn := tc.cancelFn
	tc.mu.Unlock()
	if fn != nil {
		fn()
	}
}

func (tc *taskControl) flags() (pauseReq, cancelReq bool) {
	tc.mu.Lock()
	defer tc.mu.Unlock()
	return tc.pauseReq, tc.cancelReq
}

func (tc *taskControl) enterPublishing() chan struct{} {
	tc.mu.Lock()
	defer tc.mu.Unlock()
	tc.inPublishing = true
	if tc.pubDoneCh == nil {
		tc.pubDoneCh = make(chan struct{})
	}
	return tc.pubDoneCh
}

func (tc *taskControl) publishingWaitCh() chan struct{} {
	tc.mu.Lock()
	defer tc.mu.Unlock()
	if !tc.inPublishing {
		return nil
	}
	return tc.pubDoneCh
}

type Service struct {
	store       *store.Store
	pool        *transport.Pool
	providers   ProviderLookup
	coords      *files.Coordinator
	runner      *media.Runner
	tags        *media.TagService
	opts        Options
	stagingRoot string
	maxQueued   int
	concurrency int
	maxWorkers  int

	baseCtx    context.Context
	baseCancel context.CancelFunc
	queue      chan string
	slotWakeCh chan struct{}
	wg         sync.WaitGroup

	mu          sync.Mutex
	closed      bool
	activeSlots int
	specs       map[string]taskSpec
	controls    map[string]*taskControl
	timers      map[string]*time.Timer
}

func NewService(
	st *store.Store,
	pool *transport.Pool,
	providers ProviderLookup,
	coords *files.Coordinator,
	runner *media.Runner,
	tags *media.TagService,
	opts Options,
) *Service {
	if opts.Budgets.DownloadConcurrency <= 0 {
		opts.Budgets = config.DefaultResourceBudgets()
	}
	conc := opts.Budgets.DownloadConcurrency
	maxConc := opts.Budgets.MaxDownloadConcurrency
	if maxConc <= 0 {
		maxConc = 4
	}
	if conc > maxConc {
		conc = maxConc
	}
	if conc < 1 {
		conc = 1
	}
	maxQueued := opts.Budgets.MaxQueuedDownloads
	if maxQueued <= 0 {
		maxQueued = 1000
	}
	if opts.BackoffBase <= 0 {
		opts.BackoffBase = DefaultBackoffBase
	}
	if opts.BackoffMax <= 0 {
		opts.BackoffMax = DefaultBackoffMax
	}
	if opts.MaxAttempts <= 0 {
		opts.MaxAttempts = DefaultMaxAttempts
	}
	if opts.TransferTimeout <= 0 {
		opts.TransferTimeout = DefaultTransferTime
	}
	if opts.MaxAudioBytes <= 0 {
		opts.MaxAudioBytes = DefaultMaxAudioBytes
	}
	staging := strings.TrimSpace(opts.StagingRoot)
	if staging == "" {
		staging = filepath.Join(os.TempDir(), "melokeep-partial")
	}
	_ = os.MkdirAll(staging, 0o755)

	baseCtx, baseCancel := context.WithCancel(context.Background())
	s := &Service{
		store:       st,
		pool:        pool,
		providers:   providers,
		coords:      coords,
		runner:      runner,
		tags:        tags,
		opts:        opts,
		stagingRoot: staging,
		maxQueued:   maxQueued,
		concurrency: conc,
		maxWorkers:  maxConc,
		baseCtx:     baseCtx,
		baseCancel:  baseCancel,
		queue:       make(chan string, maxQueued),
		slotWakeCh:  make(chan struct{}, maxConc),
		specs:       make(map[string]taskSpec),
		controls:    make(map[string]*taskControl),
		timers:      make(map[string]*time.Timer),
	}
	for i := 0; i < maxConc; i++ {
		s.wg.Add(1)
		go s.workerLoop()
	}
	return s
}

func (s *Service) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	for _, t := range s.timers {
		t.Stop()
	}
	clear(s.timers)
	for _, tc := range s.controls {
		tc.requestPause()
	}
	s.mu.Unlock()

	s.baseCancel()
	s.wg.Wait()
	return nil
}

func (s *Service) Enqueue(ctx context.Context, actor auth.Actor, snap config.Snapshot, req EnqueueRequest) (store.CreateDownloadResult, error) {
	if !actor.Authenticated {
		return store.CreateDownloadResult{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if !actor.Has(auth.PermLocalWrite) {
		return store.CreateDownloadResult{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "当前账号缺少下载写入权限")
	}
	if strings.TrimSpace(req.PublicID) == "" {
		return store.CreateDownloadResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少曲目公开标识")
	}
	if req.ForceNew && strings.TrimSpace(req.IntentNonce) == "" {
		return store.CreateDownloadResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "force_new 为 true 时必须提供非空 intent_nonce")
	}
	s.mu.Lock()
	closed := s.closed
	queuedLen := len(s.queue)
	s.mu.Unlock()
	if closed {
		return store.CreateDownloadResult{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "下载服务正在关闭")
	}
	if queuedLen >= s.maxQueued {
		return store.CreateDownloadResult{}, model.NewError(model.ErrRateLimited, http.StatusTooManyRequests, "下载队列已满，请稍后再试")
	}

	ref, qualityHint, ok, err := s.store.GetPublicRef(ctx, req.PublicID)
	if err != nil {
		return store.CreateDownloadResult{}, err
	}
	if !ok || (ref.Kind != "candidate" && ref.Kind != "track") {
		return store.CreateDownloadResult{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到对应的曲目候选记录")
	}
	tr, variants, ok, err := s.store.GetProviderTrack(ctx, ref.TargetID)
	if err != nil {
		return store.CreateDownloadResult{}, err
	}
	if !ok {
		return store.CreateDownloadResult{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "曲目记录不存在")
	}

	qualityID := strings.TrimSpace(req.QualityID)
	if qualityID == "" {
		qualityID = qualityHint
	}
	qualityLabel := strings.TrimSpace(req.QualityLabel)
	var variantID string
	for _, v := range variants {
		if qualityID == "" && !req.AllowPreview && v.PreviewOnly {
			continue
		}
		if qualityID == "" || v.VariantID == qualityID || v.UpstreamID == qualityID {
			variantID = v.VariantID
			if qualityID == "" {
				qualityID = v.VariantID
			}
			if qualityLabel == "" {
				qualityLabel = v.Label
			}
			break
		}
	}
	if qualityLabel == "" {
		qualityLabel = qualityID
	}

	dedupeKey := computeDedupeKey(tr.Source, tr.Locator, qualityID, snap.Revision)
	reuseCompleted := true
	if req.ReuseCompleted != nil {
		reuseCompleted = *req.ReuseCompleted
	}
	if req.ForceNew || strings.TrimSpace(req.IntentNonce) != "" {
		reuseCompleted = false
	}

	allowExtWrite := !actor.ReadOnlyAI && (actor.Has(auth.PermExternalWrite) || actor.Has(auth.PermLocalWrite))
	settingsBytes, _ := json.Marshal(map[string]any{
		"config_revision":      snap.Revision,
		"layout":               snap.Runtime.DownloadLayout,
		"lyrics_mode":          snap.Runtime.LyricsMode,
		"embed_metadata":       snap.Runtime.EmbedMetadata,
		"allow_preview":        req.AllowPreview,
		"allow_external_write": allowExtWrite,
		"root_id":              req.RootID,
		"auto_organize":        req.AutoOrganize,
	})
	reqHash := strings.TrimSpace(req.RequestHash)
	if reqHash == "" {
		sum := sha256.Sum256([]byte(fmt.Sprintf("%s\x00%s\x00%s\x00%v\x00%s", req.PublicID, qualityID, req.RootID, req.AllowPreview, req.IntentNonce)))
		reqHash = hex.EncodeToString(sum[:])
	}

	res, err := s.store.CreateDownload(ctx, store.CreateDownloadParams{
		TrackID:        tr.ID,
		VariantID:      variantID,
		PublicID:       req.PublicID,
		QualityID:      qualityID,
		QualityLabel:   qualityLabel,
		DownloadSource: config.PublicSourceID(tr.Source),
		ActorKey:       actor.Key(),
		DedupeKey:      dedupeKey,
		ForceNew:       req.ForceNew,
		IntentNonce:    req.IntentNonce,
		ReuseCompleted: reuseCompleted,
		Detail:         "已加入下载队列",
		SettingsJSON:   string(settingsBytes),
		Operation:      "download:enqueue",
		IdempotencyKey: req.IdempotencyKey,
		RequestHash:    reqHash,
	})
	if err != nil {
		return store.CreateDownloadResult{}, err
	}

	if res.Created {
		s.log("info", res.Download.ID, fmt.Sprintf("加入下载队列：《%s》- %s (%s · %s)", tr.Title, tr.Artist, config.PublicSourceID(tr.Source), qualityLabel))
		spec := taskSpec{
			downloadID:         res.Download.ID,
			snap:               snap,
			allowPreview:       req.AllowPreview,
			allowExternalWrite: allowExtWrite,
			rootID:             req.RootID,
			autoOrganize:       req.AutoOrganize,
			outbox:             req.Outbox,
		}
		if err := s.dispatch(res.Download.ID, spec); err != nil {
			detail := err.Error()
			s.log("error", res.Download.ID, fmt.Sprintf("下载任务派发失败：《%s》: %s", tr.Title, detail))
			_, _ = s.store.CompareAndSetDownload(context.Background(), store.CASDownloadParams{
				ID:               res.Download.ID,
				ExpectedRevision: res.Download.Revision,
				AllowedStates:    []model.DownloadStatus{model.DownloadQueued},
				NewState:         model.DownloadFailed,
				Detail:           &detail,
			})
			return store.CreateDownloadResult{}, err
		}
	}
	return res, nil
}

func (s *Service) log(level, taskID, message string) {
	if s.opts.Logger != nil && message != "" {
		s.opts.Logger(level, message, "download", taskID)
	}
}

func (s *Service) Pause(ctx context.Context, actor auth.Actor, downloadID string) (model.Download, error) {
	for attempt := 0; attempt < 5; attempt++ {
		dl, err := s.authorizeControl(ctx, actor, downloadID)
		if err != nil {
			return model.Download{}, err
		}
		switch dl.Status {
		case model.DownloadPaused:
			return dl, nil
		case model.DownloadComplete, model.DownloadCancelled, model.DownloadFailed:
			return model.Download{}, model.NewError(model.ErrConflict, http.StatusConflict, "当前任务状态不支持暂停")
		case model.DownloadQueued, model.DownloadRetryWait:
			s.stopTimer(downloadID)
			detail := "下载已暂停"
			updated, casErr := s.store.CompareAndSetDownload(ctx, store.CASDownloadParams{
				ID:               downloadID,
				ExpectedRevision: dl.Revision,
				AllowedStates:    []model.DownloadStatus{model.DownloadQueued, model.DownloadRetryWait},
				NewState:         model.DownloadPaused,
				Detail:           &detail,
			})
			if casErr == nil {
				return updated, nil
			}
			err = casErr
		case model.DownloadPublishing:
			s.waitPublishing(downloadID)
			cur, ok, gErr := s.store.GetDownload(ctx, downloadID)
			if gErr != nil {
				return model.Download{}, gErr
			}
			if !ok {
				return model.Download{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "下载任务不存在")
			}
			return cur, nil
		default:
			detail := "正在暂停下载"
			_, casErr := s.store.CompareAndSetDownload(ctx, store.CASDownloadParams{
				ID:               downloadID,
				ExpectedRevision: dl.Revision,
				AllowedStates:    []model.DownloadStatus{model.DownloadResolving, model.DownloadDownloading, model.DownloadVerifying, model.DownloadPauseRequested},
				NewState:         model.DownloadPauseRequested,
				Detail:           &detail,
			})
			if casErr == nil {
				s.signalPause(downloadID)
				s.waitWorkerOrPublishing(downloadID)
				cur, _, gErr := s.store.GetDownload(ctx, downloadID)
				return cur, gErr
			}
			err = casErr
		}
		if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrConflict {
			return model.Download{}, err
		}
	}
	cur, ok, gErr := s.store.GetDownload(ctx, downloadID)
	if gErr == nil && ok {
		if cur.Status == model.DownloadPublishing {
			s.waitPublishing(downloadID)
			cur, _, _ = s.store.GetDownload(ctx, downloadID)
		}
		if cur.Status == model.DownloadPaused || cur.Status == model.DownloadPauseRequested || cur.Status == model.DownloadComplete {
			return cur, nil
		}
	}
	return model.Download{}, model.NewError(model.ErrConflict, http.StatusConflict, "下载状态并发变化，暂停失败，请重试")
}

func (s *Service) Cancel(ctx context.Context, actor auth.Actor, downloadID string) (model.Download, error) {
	for attempt := 0; attempt < 5; attempt++ {
		dl, err := s.authorizeControl(ctx, actor, downloadID)
		if err != nil {
			return model.Download{}, err
		}
		switch dl.Status {
		case model.DownloadCancelled:
			s.deleteSpec(downloadID)
			return dl, nil
		case model.DownloadComplete:
			return model.Download{}, model.NewError(model.ErrConflict, http.StatusConflict, "已完成的下载不能取消")
		case model.DownloadQueued, model.DownloadPaused, model.DownloadRetryWait, model.DownloadFailed:
			s.stopTimer(downloadID)
			detail := "下载已取消"
			cancelReq := true
			updated, casErr := s.store.CompareAndSetDownload(ctx, store.CASDownloadParams{
				ID:               downloadID,
				ExpectedRevision: dl.Revision,
				AllowedStates:    []model.DownloadStatus{model.DownloadQueued, model.DownloadPaused, model.DownloadRetryWait, model.DownloadFailed},
				NewState:         model.DownloadCancelled,
				CancelRequested:  &cancelReq,
				Detail:           &detail,
			})
			if casErr == nil {
				s.cleanupPartial(downloadID)
				s.deleteSpec(downloadID)
				return updated, nil
			}
			err = casErr
		case model.DownloadPublishing:
			s.waitPublishing(downloadID)
			cur, ok, gErr := s.store.GetDownload(ctx, downloadID)
			if gErr != nil {
				return model.Download{}, gErr
			}
			if !ok {
				return model.Download{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "下载任务不存在")
			}
			return cur, nil
		default:
			detail := "正在取消下载"
			cancelReq := true
			_, casErr := s.store.CompareAndSetDownload(ctx, store.CASDownloadParams{
				ID:               downloadID,
				ExpectedRevision: dl.Revision,
				AllowedStates:    []model.DownloadStatus{model.DownloadResolving, model.DownloadDownloading, model.DownloadVerifying, model.DownloadPauseRequested, model.DownloadCancelRequested},
				NewState:         model.DownloadCancelRequested,
				CancelRequested:  &cancelReq,
				Detail:           &detail,
			})
			if casErr == nil {
				s.signalCancel(downloadID)
				s.waitWorkerOrPublishing(downloadID)
				s.deleteSpec(downloadID)
				cur, _, gErr := s.store.GetDownload(ctx, downloadID)
				return cur, gErr
			}
			err = casErr
		}
		if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrConflict {
			return model.Download{}, err
		}
	}
	cur, ok, gErr := s.store.GetDownload(ctx, downloadID)
	if gErr == nil && ok {
		if cur.Status == model.DownloadPublishing {
			s.waitPublishing(downloadID)
			cur, _, _ = s.store.GetDownload(ctx, downloadID)
		}
		if cur.Status == model.DownloadCancelled || cur.Status == model.DownloadCancelRequested || cur.Status == model.DownloadComplete {
			return cur, nil
		}
	}
	return model.Download{}, model.NewError(model.ErrConflict, http.StatusConflict, "下载状态并发变化，取消失败，请重试")
}

func (s *Service) Resume(ctx context.Context, actor auth.Actor, downloadID string, snap config.Snapshot) (model.Download, error) {
	return s.resumeOrRetry(ctx, actor, downloadID, snap, false)
}

func (s *Service) Retry(ctx context.Context, actor auth.Actor, downloadID string, snap config.Snapshot) (model.Download, error) {
	return s.resumeOrRetry(ctx, actor, downloadID, snap, true)
}

func (s *Service) resumeOrRetry(ctx context.Context, actor auth.Actor, downloadID string, snap config.Snapshot, allowCancelled bool) (model.Download, error) {
	dl, err := s.authorizeControl(ctx, actor, downloadID)
	if err != nil {
		return model.Download{}, err
	}
	if dl.Status == model.DownloadComplete {
		return model.Download{}, model.NewError(model.ErrConflict, http.StatusConflict, "下载已完成，无需重复重试")
	}
	allowed := []model.DownloadStatus{model.DownloadPaused, model.DownloadFailed, model.DownloadRetryWait}
	if allowCancelled {
		allowed = append(allowed, model.DownloadCancelled)
	}
	if dl.Status == model.DownloadCancelled {
		// Cancelled tasks discard old checkpoints before re-queuing.
		s.cleanupPartial(downloadID)
		_ = s.store.UpsertTransferCheckpoint(ctx, model.TransferCheckpoint{
			DownloadID: downloadID,
			Attempt:    dl.Attempt,
			TempPath:   filepath.Base(s.partPath(downloadID)),
		}, "", "", "", false)
	}
	detail := "重新加入下载队列"
	cancelReq := false
	var nextRetry int64
	updated, err := s.store.CompareAndSetDownload(ctx, store.CASDownloadParams{
		ID:               downloadID,
		ExpectedRevision: dl.Revision,
		AllowedStates:    allowed,
		NewState:         model.DownloadQueued,
		CancelRequested:  &cancelReq,
		NextAttemptAt:    &nextRetry,
		Detail:           &detail,
	})
	if err != nil {
		return model.Download{}, err
	}
	spec := s.specForRetry(ctx, downloadID, snap)
	if err := s.dispatch(downloadID, spec); err != nil {
		return model.Download{}, err
	}
	return updated, nil
}

func (s *Service) Delete(ctx context.Context, actor auth.Actor, downloadID string) error {
	if _, err := s.authorizeControl(ctx, actor, downloadID); err != nil {
		return err
	}
	if err := s.store.DeleteDownloadRecord(ctx, downloadID); err != nil {
		return err
	}
	s.stopTimer(downloadID)
	s.cleanupPartial(downloadID)
	s.deleteSpec(downloadID)
	return nil
}

func (s *Service) deleteSpec(downloadID string) {
	s.mu.Lock()
	delete(s.specs, downloadID)
	s.mu.Unlock()
}

func (s *Service) Get(ctx context.Context, downloadID string) (model.Download, bool, error) {
	return s.store.GetDownload(ctx, downloadID)
}

func (s *Service) List(ctx context.Context, limit int) ([]model.Download, error) {
	return s.store.ListDownloads(ctx, limit)
}

func parsePositiveInt64(v any) *int64 {
	var n int64
	switch val := v.(type) {
	case json.Number:
		if parsed, err := val.Int64(); err == nil {
			n = parsed
		} else if f, err := val.Float64(); err == nil {
			n = int64(f)
		}
	case float64:
		n = int64(val)
	case int64:
		n = val
	case int:
		n = int64(val)
	case string:
		if parsed, err := strconv.ParseInt(strings.TrimSpace(val), 10, 64); err == nil {
			n = parsed
		}
	}
	if n <= 0 {
		return nil
	}
	return &n
}

// ToDTO projects a stored Download into the legacy-compatible DownloadJobDTO,
// including independent Processing state when an auto-organize LibraryJob exists.
func (s *Service) ToDTO(ctx context.Context, dl model.Download) (model.DownloadJobDTO, error) {
	trackDTO := model.TrackDTO{
		ID:       dl.PublicID,
		Source:   dl.DownloadSource,
		Playback: model.PlaybackDTO{State: "pending", PreviewOnly: false},
	}
	if ref, defaultVariantID, ok, err := s.store.GetPublicRef(ctx, dl.PublicID); err == nil && ok {
		if tr, variants, tOk, tErr := s.store.GetProviderTrack(ctx, ref.TargetID); tErr == nil && tOk {
			var cover *string
			var sizeBytes *int64
			var sizeLabel *string
			var meta map[string]any
			if tr.MetadataJSON != "" {
				dec := json.NewDecoder(strings.NewReader(tr.MetadataJSON))
				dec.UseNumber()
				_ = dec.Decode(&meta)
			}
			if c, ok := meta["cover"].(string); ok && c != "" {
				cover = &c
			}
			if meta != nil {
				sizeBytes = parsePositiveInt64(meta["size_bytes"])
				if sl, ok := meta["size_label"].(string); ok {
					if trimmed := strings.TrimSpace(sl); trimmed != "" {
						sizeLabel = &trimmed
					}
				}
			}
			if sizeBytes == nil {
				for _, v := range variants {
					if (v.VariantID == dl.QualityID || v.UpstreamID == dl.QualityID || v.VariantID == defaultVariantID) &&
						v.SizeBytes != nil && *v.SizeBytes > 0 {
						val := *v.SizeBytes
						sizeBytes = &val
						break
					}
				}
			}
			if sizeBytes == nil {
				for _, v := range variants {
					if v.SizeBytes != nil && *v.SizeBytes > 0 {
						val := *v.SizeBytes
						sizeBytes = &val
						break
					}
				}
			}
			if sizeBytes == nil && dl.TotalBytes != nil && *dl.TotalBytes > 0 {
				val := *dl.TotalBytes
				sizeBytes = &val
			}
			trackDTO = model.TrackDTOFromDomain(model.ProviderTrack{
				PublicID:   dl.PublicID,
				Source:     config.PublicSourceID(tr.Source),
				SourceID:   tr.Locator,
				RootSource: tr.RootSource,
				Title:      tr.Title,
				Artist:     tr.Artist,
				Album:      tr.Album,
				Duration:   float64(tr.DurationMS) / 1000.0,
				Cover:      cover,
				SizeBytes:  sizeBytes,
				SizeLabel:  sizeLabel,
				Variants:   variants,
			}, model.PlaybackDTO{State: "pending", PreviewOnly: false})
		}
	}

	var proc *model.DownloadProcessingDTO
	var jobID, jobState, jobDetail string
	err := s.store.Reader().QueryRowContext(ctx, `
		SELECT id, state, detail FROM library_jobs WHERE source_download_id = ? LIMIT 1
	`, dl.ID).Scan(&jobID, &jobState, &jobDetail)
	if err == nil {
		proc = &model.DownloadProcessingDTO{
			ID:     jobID,
			Status: jobState,
			Detail: jobDetail,
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return model.DownloadJobDTO{}, err
	}

	return model.DownloadJobDTO{
		ID:             dl.ID,
		Track:          trackDTO,
		QualityID:      dl.QualityID,
		QualityLabel:   dl.QualityLabel,
		DownloadSource: dl.DownloadSource,
		Status:         model.LegacyDownloadStatus(dl.Status),
		Bytes:          dl.Bytes,
		TotalBytes:     dl.TotalBytes,
		Detail:         dl.Detail,
		File:           dl.File,
		Processing:     proc,
	}, nil
}

func (s *Service) authorizeControl(ctx context.Context, actor auth.Actor, downloadID string) (model.Download, error) {
	if !actor.Authenticated {
		return model.Download{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	dl, ok, err := s.store.GetDownload(ctx, downloadID)
	if err != nil {
		return model.Download{}, err
	}
	if !ok {
		return model.Download{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "下载任务不存在")
	}
	if dl.ActorKey != actor.Key() && !actor.Has(auth.PermAdmin) {
		return model.Download{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "只能控制自己发起的下载任务")
	}
	return dl, nil
}

func (s *Service) dispatch(downloadID string, spec taskSpec) error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "下载服务已关闭")
	}
	s.specs[downloadID] = spec
	s.mu.Unlock()

	select {
	case s.queue <- downloadID:
		return nil
	default:
		return model.NewError(model.ErrRateLimited, http.StatusTooManyRequests, "下载队列已满，请稍后再试")
	}
}

func (s *Service) currentConcurrencyLimitLocked() int {
	limit := s.concurrency
	if s.opts.SnapshotProvider != nil {
		if dc := s.opts.SnapshotProvider().Runtime.DownloadConcurrency; dc > 0 {
			limit = dc
		}
	}
	if limit < 1 {
		limit = 1
	}
	if s.maxWorkers > 0 && limit > s.maxWorkers {
		limit = s.maxWorkers
	}
	return limit
}

func (s *Service) acquireSlot() bool {
	for {
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			return false
		}
		limit := s.currentConcurrencyLimitLocked()
		if s.activeSlots < limit {
			s.activeSlots++
			s.mu.Unlock()
			return true
		}
		s.mu.Unlock()
		select {
		case <-s.baseCtx.Done():
			return false
		case <-s.slotWakeCh:
		case <-time.After(time.Second):
		}
	}
}

func (s *Service) releaseSlot() {
	s.mu.Lock()
	if s.activeSlots > 0 {
		s.activeSlots--
	}
	s.mu.Unlock()
	select {
	case s.slotWakeCh <- struct{}{}:
	default:
	}
}

func (s *Service) workerLoop() {
	defer s.wg.Done()
	for {
		if !s.acquireSlot() {
			return
		}
		var dlID string
		var ok bool
		select {
		case <-s.baseCtx.Done():
			s.releaseSlot()
			return
		case dlID, ok = <-s.queue:
			if !ok {
				s.releaseSlot()
				return
			}
		}
		s.executeJob(dlID)
		s.releaseSlot()
	}
}

func (s *Service) executeJob(dlID string) {
	s.mu.Lock()
	spec, hasSpec := s.specs[dlID]
	if s.closed {
		s.mu.Unlock()
		return
	}
	runCtx, cancel := context.WithCancel(s.baseCtx)
	tc := &taskControl{
		cancelFn: cancel,
		doneCh:   make(chan struct{}),
	}
	s.controls[dlID] = tc
	s.mu.Unlock()

	defer func() {
		cancel()
		close(tc.doneCh)
		s.mu.Lock()
		if s.controls[dlID] == tc {
			delete(s.controls, dlID)
		}
		s.mu.Unlock()
	}()

	cur, ok, err := s.store.GetDownload(s.baseCtx, dlID)
	if err != nil || !ok || cur.Status != model.DownloadQueued {
		return
	}
	if !hasSpec {
		var snap config.Snapshot
		if s.opts.SnapshotProvider != nil {
			snap = s.opts.SnapshotProvider()
		} else {
			snap = config.NewSnapshot(config.DefaultRuntimeConfig(), s.opts.Budgets)
		}
		spec = s.specForRetry(s.baseCtx, dlID, snap)
	}

	nextAttempt := cur.Attempt + 1
	resolvingDetail := "正在解析可用音质与媒体地址"
	dl, err := s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
		ID:               dlID,
		ExpectedRevision: cur.Revision,
		AllowedStates:    []model.DownloadStatus{model.DownloadQueued},
		NewState:         model.DownloadResolving,
		Attempt:          &nextAttempt,
		Detail:           &resolvingDetail,
	})
	if err != nil {
		return
	}

	if s.checkPauseOrCancel(tc, dl, spec) {
		return
	}

	trRec, variants, ref, err := s.loadTrackForDownload(s.baseCtx, dl)
	if err != nil {
		s.finishError(tc, dl, spec, err)
		return
	}
	prov, ok := s.providers.Get(trRec.Source)
	if !ok {
		s.finishError(tc, dl, spec, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "对应音源未加载"))
		return
	}

	pTrack := domainTrackFromRecord(ref.PublicID, trRec, variants)
	rc := provider.RequestContext{
		Config:             spec.snap,
		Account:            transport.AccountKey{Source: trRec.Source, AccountID: dl.ActorKey},
		TaskID:             dlID,
		LogFunc:            s.opts.Logger,
		AllowExternalWrite: spec.allowExternalWrite,
	}
	resolveCtx := provider.WithRequestContext(runCtx, rc)
	resolvedList, err := prov.Resolve(resolveCtx, pTrack, model.QualityPolicy{
		PreferredVariantID: dl.QualityID,
		AllowPreview:       spec.allowPreview,
	})
	var chosen model.ResolvedMedia
	if err == nil {
		chosen, ok = pickResolvedMedia(resolvedList, dl.QualityID, spec.allowPreview)
	}
	if (!ok || err != nil) && strings.TrimSpace(dl.QualityID) != "" {
		if fbList, fbErr := prov.Resolve(resolveCtx, pTrack, model.QualityPolicy{
			PreferredVariantID: "",
			AllowPreview:       spec.allowPreview,
		}); fbErr == nil {
			if fbChosen, fbOK := pickResolvedMedia(fbList, "", spec.allowPreview); fbOK {
				chosen, ok = fbChosen, true
				err = nil
			}
		}
	}
	if err != nil {
		s.finishError(tc, dl, spec, err)
		return
	}
	if !ok {
		s.finishError(tc, dl, spec, model.NewError(model.ErrNotFound, http.StatusNotFound, "未解析到可用的完整音频地址"))
		return
	}
	s.log("info", dlID, fmt.Sprintf("解析下载音质：《%s》 · %s (%s)", trRec.Title, chosen.Variant.Label, chosen.Codec))

	if s.checkPauseOrCancel(tc, dl, spec) {
		return
	}

	dlDetail := "开始下载音频"
	dl, err = s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
		ID:               dlID,
		ExpectedRevision: dl.Revision,
		AllowedStates:    []model.DownloadStatus{model.DownloadResolving},
		NewState:         model.DownloadDownloading,
		BytesTotal:       chosen.SizeBytes,
		Detail:           &dlDetail,
	})
	if err != nil {
		return
	}

	partPath := s.partPath(dlID)
	var bytesWritten int64
	var totalBytes *int64
	var mime string
	isHLS := strings.EqualFold(chosen.Variant.Protocol, "hls") || strings.Contains(strings.ToLower(chosen.URL), ".m3u8")

	progressHook := func(done int64, total *int64, detail string) {
		updated, casErr := s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
			ID:               dlID,
			ExpectedRevision: dl.Revision,
			AllowedStates:    []model.DownloadStatus{model.DownloadDownloading},
			NewState:         model.DownloadDownloading,
			BytesDone:        &done,
			BytesTotal:       total,
			Detail:           &detail,
		})
		if casErr == nil {
			dl = updated
		}
	}

	if isHLS {
		var hlsPath string
		hlsPath, bytesWritten, err = downloadHLS(
			runCtx, s.pool, s.runner, chosen, rc,
			filepath.Join(s.stagingRoot, dlID), dlID,
			s.opts.MaxAudioBytes, s.opts.TransferTimeout, progressHook,
		)
		if err == nil {
			partPath = hlsPath
			bt := bytesWritten
			totalBytes = &bt
		}
	} else {
		bytesWritten, totalBytes, mime, err = transferHTTP(
			runCtx, s.pool, s.store, dl, chosen, rc,
			partPath, s.opts.MaxAudioBytes, s.opts.TransferTimeout, progressHook,
		)
	}
	if err != nil {
		s.finishError(tc, dl, spec, err)
		return
	}

	if s.checkPauseOrCancel(tc, dl, spec) {
		return
	}

	verifyDetail := "正在校验音频完整性"
	dl, err = s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
		ID:               dlID,
		ExpectedRevision: dl.Revision,
		AllowedStates:    []model.DownloadStatus{model.DownloadDownloading},
		NewState:         model.DownloadVerifying,
		BytesDone:        &bytesWritten,
		BytesTotal:       totalBytes,
		Detail:           &verifyDetail,
	})
	if err != nil {
		return
	}

	durationMS := trRec.DurationMS
	if s.runner != nil && s.runner.Available("ffprobe") {
		if insp, iErr := s.runner.InspectAudio(runCtx, partPath); iErr == nil {
			if insp.Duration > 0 {
				durationMS = int64(insp.Duration * 1000)
			}
		} else if !errors.Is(iErr, context.Canceled) {
			// Non-cancel inspection failure rejects corrupt downloads.
			if de, ok := model.AsDomainError(iErr); ok && de.Code != model.ErrUnavailable {
				s.finishError(tc, dl, spec, iErr)
				return
			}
		}
	}

	var metaMap map[string]any
	if trRec.MetadataJSON != "" {
		_ = json.Unmarshal([]byte(trRec.MetadataJSON), &metaMap)
	}
	lyricsText, _ := metaMap["lyrics"].(string)
	if strings.TrimSpace(chosen.Lyrics) != "" {
		lyricsText = chosen.Lyrics
	}
	coverURL, _ := metaMap["cover"].(string)
	var coverData []byte
	var coverMime string
	if coverURL != "" {
		if d, m, cErr := fetchBoundedAsset(runCtx, s.pool, coverURL, rc); cErr == nil {
			coverData, coverMime = d, m
		}
	}

	codec := chosen.Codec
	if codec == "" {
		codec = chosen.Variant.Codec
	}
	ext := detectAudioExt(partPath, mime, codec)
	if isHLS {
		ext = filepath.Ext(partPath)
	}

	if s.checkPauseOrCancel(tc, dl, spec) {
		return
	}

	pubDetail := "正在发布到曲库"
	dl, err = s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
		ID:               dlID,
		ExpectedRevision: dl.Revision,
		AllowedStates:    []model.DownloadStatus{model.DownloadVerifying},
		NewState:         model.DownloadPublishing,
		Detail:           &pubDetail,
	})
	if err != nil {
		return
	}

	pubDone := tc.enterPublishing()
	if s.opts.TestHookBeforePublish != nil {
		s.opts.TestHookBeforePublish(s.baseCtx, dl)
	}

	var pubErr error
	var createdJobID string
	func() {
		roots := s.coords.Roots()
		if len(roots) == 0 {
			pubErr = model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "未配置可写音乐根目录")
			return
		}
		targetRoot := roots[0]
		if spec.rootID != "" {
			for _, r := range roots {
				if r.ID == spec.rootID {
					targetRoot = r
					break
				}
			}
		}
		targetPath := TargetAudioPath(targetRoot.CanonicalPath, spec.snap.Runtime.DownloadLayout, pTrack, ext, dlID)
		sidecars := BuildSidecars(targetPath, spec.snap.Runtime.LyricsMode, lyricsText, coverData, coverMime)

		var autoSpec *store.AutoOrganizeJobSpec
		if spec.autoOrganize != nil && spec.autoOrganize.Enabled {
			jobID := model.NewUUID()
			createdJobID = jobID
			action := spec.autoOrganize.Action
			if action == "" {
				action = "both"
			}
			autoSpec = &store.AutoOrganizeJobSpec{
				JobID:        jobID,
				Action:       action,
				SettingsJSON: spec.autoOrganize.SettingsJSON,
				OptionsJSON:  spec.autoOrganize.OptionsJSON,
				Detail:       spec.autoOrganize.Detail,
				OwnerKey:     dl.ActorKey,
				Items: []store.LibraryItemInput{
					{
						ItemID:       model.NewUUID(),
						Name:         filepath.Base(targetPath),
						OriginalPath: targetPath,
						TargetPath:   targetPath,
						State:        model.LibraryItemQueued,
					},
				},
			}
		}

		var mutator files.StageMutator
		if spec.snap.Runtime.EmbedMetadata && s.tags != nil && media.EditableExtensions[strings.ToLower(ext)] {
			mutator = func(mCtx context.Context, stageAudioPath string) ([]files.SidecarOutput, error) {
				req := helper.WriteTagsRequest{
					AllowedStageRoot: targetRoot.CanonicalPath,
					StagePath:        stageAudioPath,
					Tags: map[string]string{
						"title":  trRec.Title,
						"artist": trRec.Artist,
						"album":  trRec.Album,
					},
				}
				mode := strings.ToLower(strings.TrimSpace(spec.snap.Runtime.LyricsMode))
				if (mode == "" || mode == "both" || mode == "embed") && strings.TrimSpace(lyricsText) != "" {
					req.Lyrics = &lyricsText
				}
				if len(coverData) > 0 {
					covExt := ".jpg"
					if strings.Contains(strings.ToLower(coverMime), "png") {
						covExt = ".png"
					}
					covTmp := filepath.Join(filepath.Dir(stageAudioPath), ".melokeep-cov-"+dlID+covExt)
					if wErr := os.WriteFile(covTmp, coverData, 0o600); wErr == nil {
						defer os.Remove(covTmp)
						req.Cover = &helper.CoverSpec{Path: covTmp, Mime: coverMime}
					}
				}
				if _, tErr := s.tags.WriteStagedTags(mCtx, req); tErr != nil {
					return nil, tErr
				}
				return nil, nil
			}
		}

		_, pubErr = s.coords.Execute(s.baseCtx, files.OperationRequest{
			Kind:          "download_publish",
			SourcePath:    partPath,
			TargetPath:    targetPath,
			NewTitle:      trRec.Title,
			NewArtist:     trRec.Artist,
			NewAlbum:      trRec.Album,
			NewDurationMS: durationMS,
			Editable:      media.EditableExtensions[strings.ToLower(ext)],
			NewMetadata: map[string]string{
				"title":  trRec.Title,
				"artist": trRec.Artist,
				"album":  trRec.Album,
			},
			Mutator:        mutator,
			HookAfterPhase: s.opts.TestPublishHook,
			DownloadPublish: &files.DownloadPublishSpec{
				DownloadID:       dl.ID,
				ExpectedRevision: dl.Revision,
				TrackID:          trRec.ID,
				PublicID:         dl.PublicID,
				Source:           trRec.Source,
				SourceID:         trRec.Locator,
				ActorKey:         dl.ActorKey,
				AutoOrganize:     autoSpec,
				SidecarOutputs:   sidecars,
			},
		})
	}()

	if pubErr != nil {
		if !s.checkPauseOrCancel(tc, dl, spec) {
			s.finishError(tc, dl, spec, pubErr)
		}
		close(pubDone)
		return
	}
	close(pubDone)

	// Published and committed atomically as complete.
	_ = os.Remove(partPath)
	s.deleteSpec(dlID)
	s.log("info", dlID, fmt.Sprintf("下载完成：《%s》 · %s · %d bytes", trRec.Title, strings.TrimPrefix(ext, "."), bytesWritten))
	if createdJobID != "" && s.opts.AutoOrganizeRunner != nil {
		finalDL, _, _ := s.store.GetDownload(s.baseCtx, dlID)
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			// Never downgrade completed download if post-publish organize fails.
			if orgErr := s.opts.AutoOrganizeRunner(s.baseCtx, createdJobID, finalDL); orgErr != nil {
				_ = s.store.WithTx(s.baseCtx, func(tx *sql.Tx) error {
					_, err := tx.ExecContext(s.baseCtx, `
						UPDATE library_jobs SET state = 'failed', detail = ?, updated_at = ? WHERE id = ?
					`, orgErr.Error(), time.Now().UTC().UnixMilli(), createdJobID)
					return err
				})
			}
		}()
	}
}

func (s *Service) checkPauseOrCancel(tc *taskControl, dl model.Download, _ taskSpec) bool {
	pauseReq, cancelReq := tc.flags()
	cur, ok, err := s.store.GetDownload(s.baseCtx, dl.ID)
	if err != nil || !ok {
		return true
	}
	if cur.Status == model.DownloadCancelRequested || cur.Status == model.DownloadCancelled || cancelReq {
		detail := "下载已取消"
		reqTrue := true
		_, _ = s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
			ID:               dl.ID,
			ExpectedRevision: cur.Revision,
			AllowedStates: []model.DownloadStatus{
				model.DownloadResolving, model.DownloadDownloading, model.DownloadVerifying,
				model.DownloadPublishing, model.DownloadPauseRequested, model.DownloadCancelRequested,
			},
			NewState:        model.DownloadCancelled,
			CancelRequested: &reqTrue,
			Detail:          &detail,
		})
		s.cleanupPartial(dl.ID)
		return true
	}
	if cur.Status == model.DownloadPauseRequested || cur.Status == model.DownloadPaused || pauseReq {
		detail := "下载已暂停"
		_, _ = s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
			ID:               dl.ID,
			ExpectedRevision: cur.Revision,
			AllowedStates: []model.DownloadStatus{
				model.DownloadResolving, model.DownloadDownloading, model.DownloadVerifying,
				model.DownloadPublishing, model.DownloadPauseRequested,
			},
			NewState: model.DownloadPaused,
			Detail:   &detail,
		})
		return true
	}
	return false
}

func (s *Service) finishError(tc *taskControl, dl model.Download, spec taskSpec, err error) {
	if s.checkPauseOrCancel(tc, dl, spec) {
		return
	}
	cur, ok, gErr := s.store.GetDownload(s.baseCtx, dl.ID)
	if gErr != nil || !ok {
		return
	}
	retryable := false
	var retryAfterSec int
	detail := err.Error()
	if de, ok := model.AsDomainError(err); ok {
		retryable = de.Retryable
		retryAfterSec = de.RetryAfter
		if de.Detail != "" {
			detail = de.Detail
		}
	}
	errPayload, _ := json.Marshal(map[string]any{"detail": detail})
	errStr := string(errPayload)

	if retryable && cur.Attempt < s.opts.MaxAttempts {
		delay := s.computeBackoff(cur.Attempt, retryAfterSec)
		nextAt := time.Now().UTC().Add(delay).UnixMilli()
		waitDetail := fmt.Sprintf("等待重试（第 %d/%d 次）：%s", cur.Attempt, s.opts.MaxAttempts, detail)
		updated, casErr := s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
			ID:               dl.ID,
			ExpectedRevision: cur.Revision,
			AllowedStates:    []model.DownloadStatus{model.DownloadResolving, model.DownloadDownloading, model.DownloadVerifying},
			NewState:         model.DownloadRetryWait,
			Detail:           &waitDetail,
			NextAttemptAt:    &nextAt,
			ErrorJSON:        &errStr,
		})
		if casErr == nil {
			s.log("warning", dl.ID, waitDetail)
			s.scheduleRetry(dl.ID, updated.Revision, delay, spec)
		}
		return
	}

	s.log("error", dl.ID, fmt.Sprintf("下载失败：%s", detail))
	_, _ = s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
		ID:               dl.ID,
		ExpectedRevision: cur.Revision,
		AllowedStates: []model.DownloadStatus{
			model.DownloadResolving, model.DownloadDownloading, model.DownloadVerifying, model.DownloadPublishing,
		},
		NewState:  model.DownloadFailed,
		Detail:    &detail,
		ErrorJSON: &errStr,
	})
	s.deleteSpec(dl.ID)
}

func (s *Service) computeBackoff(attempt, retryAfterSec int) time.Duration {
	if retryAfterSec > 0 {
		d := time.Duration(retryAfterSec) * time.Second
		if d > s.opts.BackoffMax {
			return s.opts.BackoffMax
		}
		return d
	}
	shift := attempt - 1
	if shift < 0 {
		shift = 0
	}
	if shift > 6 {
		shift = 6
	}
	d := s.opts.BackoffBase * (1 << shift)
	if d > s.opts.BackoffMax {
		d = s.opts.BackoffMax
	}
	jitter := time.Duration(rand.Int63n(int64(d/4) + 1))
	return d + jitter
}

func (s *Service) scheduleRetry(dlID string, expectedRev int64, delay time.Duration, spec taskSpec) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	if prev, ok := s.timers[dlID]; ok {
		prev.Stop()
	}
	s.timers[dlID] = time.AfterFunc(delay, func() {
		s.mu.Lock()
		delete(s.timers, dlID)
		closed := s.closed
		s.mu.Unlock()
		if closed {
			return
		}
		detail := "退避结束，重新排队"
		var zero int64
		_, err := s.store.CompareAndSetDownload(s.baseCtx, store.CASDownloadParams{
			ID:               dlID,
			ExpectedRevision: expectedRev,
			AllowedStates:    []model.DownloadStatus{model.DownloadRetryWait},
			NewState:         model.DownloadQueued,
			NextAttemptAt:    &zero,
			Detail:           &detail,
		})
		if err == nil {
			_ = s.dispatch(dlID, spec)
		}
	})
}

func (s *Service) stopTimer(dlID string) {
	s.mu.Lock()
	if t, ok := s.timers[dlID]; ok {
		t.Stop()
		delete(s.timers, dlID)
	}
	s.mu.Unlock()
}

func (s *Service) signalPause(dlID string) {
	s.mu.Lock()
	tc := s.controls[dlID]
	s.mu.Unlock()
	if tc != nil {
		tc.requestPause()
	}
}

func (s *Service) signalCancel(dlID string) {
	s.mu.Lock()
	tc := s.controls[dlID]
	s.mu.Unlock()
	if tc != nil {
		tc.requestCancel()
	}
}

func (s *Service) waitPublishing(dlID string) {
	s.mu.Lock()
	tc := s.controls[dlID]
	s.mu.Unlock()
	if tc != nil {
		if ch := tc.publishingWaitCh(); ch != nil {
			<-ch
		}
	}
}

func (s *Service) waitWorkerOrPublishing(dlID string) {
	s.mu.Lock()
	tc := s.controls[dlID]
	s.mu.Unlock()
	if tc == nil {
		return
	}
	if pub := tc.publishingWaitCh(); pub != nil {
		<-pub
		return
	}
	<-tc.doneCh
}

func (s *Service) partPath(downloadID string) string {
	return filepath.Join(s.stagingRoot, downloadID+".part")
}

func (s *Service) cleanupPartial(downloadID string) {
	_ = os.Remove(s.partPath(downloadID))
	_ = os.RemoveAll(filepath.Join(s.stagingRoot, downloadID))
}

func (s *Service) loadTrackForDownload(ctx context.Context, dl model.Download) (store.ProviderTrackRecord, []model.MediaVariant, model.PublicRef, error) {
	ref, _, ok, err := s.store.GetPublicRef(ctx, dl.PublicID)
	if err != nil {
		return store.ProviderTrackRecord{}, nil, model.PublicRef{}, err
	}
	if !ok {
		return store.ProviderTrackRecord{}, nil, model.PublicRef{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到曲目公开引用")
	}
	tr, variants, ok, err := s.store.GetProviderTrack(ctx, ref.TargetID)
	if err != nil {
		return store.ProviderTrackRecord{}, nil, model.PublicRef{}, err
	}
	if !ok {
		return store.ProviderTrackRecord{}, nil, model.PublicRef{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "平台曲目记录不存在")
	}
	return tr, variants, ref, nil
}

func (s *Service) specForRetry(ctx context.Context, dlID string, snap config.Snapshot) taskSpec {
	s.mu.Lock()
	prev, ok := s.specs[dlID]
	s.mu.Unlock()
	if ok {
		if snap.Revision != "" {
			prev.snap = snap
		}
		return prev
	}
	var settingsJSON string
	_ = s.store.Reader().QueryRowContext(ctx, `SELECT settings_json FROM downloads WHERE id = ?`, dlID).Scan(&settingsJSON)
	var parsed struct {
		AllowPreview       bool                `json:"allow_preview"`
		AllowExternalWrite bool                `json:"allow_external_write"`
		RootID             string              `json:"root_id"`
		AutoOrganize       *AutoOrganizeConfig `json:"auto_organize"`
	}
	_ = json.Unmarshal([]byte(settingsJSON), &parsed)
	return taskSpec{
		downloadID:         dlID,
		snap:               snap,
		allowPreview:       parsed.AllowPreview,
		allowExternalWrite: parsed.AllowExternalWrite,
		rootID:             parsed.RootID,
		autoOrganize:       parsed.AutoOrganize,
	}
}

func domainTrackFromRecord(publicID string, tr store.ProviderTrackRecord, variants []model.MediaVariant) model.ProviderTrack {
	var meta map[string]any
	if tr.MetadataJSON != "" {
		_ = json.Unmarshal([]byte(tr.MetadataJSON), &meta)
	}
	var cover *string
	if c, ok := meta["cover"].(string); ok && c != "" {
		cover = &c
	}
	return model.ProviderTrack{
		PublicID:   publicID,
		Source:     tr.Source,
		SourceID:   tr.Locator,
		RootSource: tr.RootSource,
		Title:      tr.Title,
		Artist:     tr.Artist,
		Album:      tr.Album,
		Duration:   float64(tr.DurationMS) / 1000.0,
		Cover:      cover,
		Variants:   variants,
	}
}

func pickResolvedMedia(list []model.ResolvedMedia, qualityID string, allowPreview bool) (model.ResolvedMedia, bool) {
	for _, rm := range list {
		if rm.URL == "" {
			continue
		}
		if rm.PreviewOnly && !allowPreview {
			continue
		}
		if qualityID == "" || rm.VariantID == qualityID || rm.Variant.UpstreamID == qualityID || rm.Variant.VariantID == qualityID {
			return rm, true
		}
	}
	for _, rm := range list {
		if rm.URL == "" || (rm.PreviewOnly && !allowPreview) {
			continue
		}
		return rm, true
	}
	return model.ResolvedMedia{}, false
}

func computeDedupeKey(source, locator, qualityID, configRev string) string {
	sum := sha256.Sum256([]byte(source + "\x00" + locator + "\x00" + qualityID + "\x00" + configRev))
	return hex.EncodeToString(sum[:16])
}
