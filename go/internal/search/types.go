package search

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
)

// ProviderLookup resolves a public source alias or engine class name to a Provider.
type ProviderLookup interface {
	Get(idOrEngine string) (model.Provider, bool)
}

// CreateRequest matches POST /api/search/jobs payload.
type CreateRequest struct {
	Keyword string   `json:"keyword"`
	Sources []string `json:"sources"`
	Refresh bool     `json:"refresh"`
	Limit   int      `json:"limit,omitempty"`
}

// Timings controls search SSE keepalive, idle, and batch persistence intervals.
type Timings struct {
	KeepaliveInterval    time.Duration
	NoSubscriberCancel   time.Duration
	LastSubscriberGrace  time.Duration
	CompletedJobTTL      time.Duration
	CacheTTL             time.Duration
	BatchPersistInterval time.Duration
	BatchPersistSize     int
}

func DefaultTimings() Timings {
	return Timings{
		KeepaliveInterval:    10 * time.Second,
		NoSubscriberCancel:   30 * time.Second,
		LastSubscriberGrace:  10 * time.Second,
		CompletedJobTTL:      300 * time.Second,
		CacheTTL:             30 * time.Second,
		BatchPersistInterval: 50 * time.Millisecond,
		BatchPersistSize:     50,
	}
}

// Event represents one SSE frame in a search job's bounded event log.
type Event struct {
	Seq       int64
	Type      string // "track", "source", "done"
	Data      json.RawMessage
	Keepalive bool
}

// SSEBytes formats the event into standard text/event-stream wire bytes.
func (e Event) SSEBytes() []byte {
	if e.Keepalive {
		return []byte(": keepalive\n\n")
	}
	return []byte(fmt.Sprintf("id: %d\nevent: %s\ndata: %s\n\n", e.Seq, e.Type, e.Data))
}

// SourceEventPayload matches the SSE "source" event payload in contracts/v1/sse/search_events.json.
type SourceEventPayload struct {
	ID     string                `json:"id"`
	Status model.SourceStatusDTO `json:"status"`
}

// DoneEventPayload matches the SSE "done" event payload in contracts/v1/sse/search_events.json.
type DoneEventPayload struct {
	Cancelled bool `json:"cancelled"`
}

// JobState describes the lifecycle state of a search job.
type JobState string

const (
	JobQueued           JobState = "queued"
	JobRunning          JobState = "running"
	JobComplete         JobState = "complete"
	JobPartial          JobState = "partial"
	JobFailed           JobState = "failed"
	JobCancelled        JobState = "cancelled"
	JobCapacityExceeded JobState = "capacity_exceeded"
)

// JobSummary exposes read-only job status for inspection and diagnostics.
type JobSummary struct {
	ID          string
	State       JobState
	Done        bool
	Cancelled   bool
	EventCount  int
	LogBytes    int64
	Subscribers int
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

// PendingTrackPlayback returns the canonical pending playback DTO for search tracks.
func PendingTrackPlayback() model.PlaybackDTO {
	detail := "播放或下载时解析音质与音频地址"
	codec := ""
	return model.PlaybackDTO{
		State:       "pending",
		URL:         nil,
		Codec:       &codec,
		Bitrate:     nil,
		Detail:      &detail,
		PreviewOnly: false,
	}
}

// Option customizes a search Service.
type Option func(*Service)

// WithTimings overrides default timing intervals (useful for deterministic tests).
func WithTimings(t Timings) Option {
	return func(s *Service) {
		if t.KeepaliveInterval > 0 {
			s.timings.KeepaliveInterval = t.KeepaliveInterval
		}
		if t.NoSubscriberCancel > 0 {
			s.timings.NoSubscriberCancel = t.NoSubscriberCancel
		}
		if t.LastSubscriberGrace > 0 {
			s.timings.LastSubscriberGrace = t.LastSubscriberGrace
		}
		if t.CompletedJobTTL > 0 {
			s.timings.CompletedJobTTL = t.CompletedJobTTL
		}
		if t.CacheTTL > 0 {
			s.timings.CacheTTL = t.CacheTTL
		}
		if t.BatchPersistInterval > 0 {
			s.timings.BatchPersistInterval = t.BatchPersistInterval
		}
		if t.BatchPersistSize > 0 {
			s.timings.BatchPersistSize = t.BatchPersistSize
		}
	}
}

// WithMaxJobLogBytes overrides per-job event log byte budget.
func WithMaxJobLogBytes(n int64) Option {
	return func(s *Service) {
		if n > 0 {
			s.maxJobLogBytes = n
		}
	}
}

// WithMaxComponentLogBytes overrides the global search component event log byte budget.
func WithMaxComponentLogBytes(n int64) Option {
	return func(s *Service) {
		if n > 0 {
			s.maxComponentLogBytes = n
		}
	}
}

// WithMaxSingleEventBytes overrides the single SSE event payload byte cap.
func WithMaxSingleEventBytes(n int) Option {
	return func(s *Service) {
		if n > 0 {
			s.maxSingleEventBytes = n
		}
	}
}

// WithSubscriberBuffer overrides the slow-subscriber lag threshold.
func WithSubscriberBuffer(n int) Option {
	return func(s *Service) {
		if n > 0 {
			s.subscriberBuffer = n
		}
	}
}

// WithNow overrides clock function for tests.
func WithNow(nowFn func() time.Time) Option {
	return func(s *Service) {
		if nowFn != nil {
			s.now = nowFn
		}
	}
}

// WithLogger attaches a structured runtime log callback to the search service.
func WithLogger(fn provider.LogFunc) Option {
	return func(s *Service) {
		s.logger = fn
	}
}

// MapProviderLookup is a simple map-backed ProviderLookup for tests or wiring.
type MapProviderLookup map[string]model.Provider

func (m MapProviderLookup) Get(idOrEngine string) (model.Provider, bool) {
	if p, ok := m[idOrEngine]; ok {
		return p, true
	}
	return nil, false
}
