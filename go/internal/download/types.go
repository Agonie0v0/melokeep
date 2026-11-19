package download

import (
	"context"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
)

const (
	DefaultBackoffBase   = 500 * time.Millisecond
	DefaultBackoffMax    = 30 * time.Second
	DefaultMaxAttempts   = 4
	DefaultTransferTime  = 15 * time.Minute
	DefaultMaxAudioBytes = int64(1 << 30) // 1 GiB
)

// ProviderLookup resolves a provider by public ID or engine name.
type ProviderLookup interface {
	Get(idOrName string) (model.Provider, bool)
}

// AutoOrganizeConfig requests atomic creation of a follow-up LibraryJob on publish.
type AutoOrganizeConfig struct {
	Enabled      bool
	Action       string // scrape | organize | both
	SettingsJSON string
	OptionsJSON  string
	Detail       string
}

// AutoOrganizeHandler runs after download completion to process the created LibraryJob
// without ever downgrading a completed download on organize failure.
type AutoOrganizeHandler func(ctx context.Context, jobID string, dl model.Download) error

// EnqueueRequest carries the actor, immutable config snapshot, and download intent.
type EnqueueRequest struct {
	PublicID       string
	QualityID      string
	QualityLabel   string
	IdempotencyKey string
	RequestHash    string
	ForceNew       bool
	IntentNonce    string
	ReuseCompleted *bool
	AllowPreview   bool
	RootID         string
	AutoOrganize   *AutoOrganizeConfig
	Outbox         *store.OutboxEnqueueParams
}

// Options configures the download Service.
type Options struct {
	StagingRoot        string
	Budgets            config.ResourceBudgets
	BackoffBase        time.Duration
	BackoffMax         time.Duration
	MaxAttempts        int
	TransferTimeout    time.Duration
	MaxAudioBytes      int64
	VerifyPCM          bool
	SnapshotProvider   func() config.Snapshot
	AutoOrganizeRunner AutoOrganizeHandler
	Logger             provider.LogFunc
	// TestHookBeforePublish runs right before files.Coordinator.Execute for fault/race testing.
	TestHookBeforePublish func(ctx context.Context, dl model.Download)
	// TestPublishHook is forwarded to files.OperationRequest.HookAfterPhase for testing.
	TestPublishHook func(phase string) error
}

// TransferResult represents a verified temporary audio artifact ready for publishing.
type TransferResult struct {
	PartPath     string
	BytesWritten int64
	TotalBytes   *int64
	Ext          string
	Codec        string
	Bitrate      *int
	Title        string
	Artist       string
	Album        string
	DurationMS   int64
	Lyrics       string
	CoverData    []byte
	CoverMime    string
}
