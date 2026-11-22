package model

import (
	"context"
	"time"
)

// MediaVariant is the persistent quality identity of a track candidate.
// It MUST NOT carry transient media URLs, expiry timestamps, headers, or cookies.
type MediaVariant struct {
	VariantID    string `json:"variant_id"`
	UpstreamID   string `json:"upstream_id,omitempty"`
	Label        string `json:"label"`
	Codec        string `json:"codec"`
	Bitrate      *int   `json:"bitrate"`
	Lossless     bool   `json:"lossless"`
	Availability string `json:"availability"`
	Evidence     string `json:"evidence"`
	PreviewOnly  bool   `json:"preview_only,omitempty"`
	Protocol     string `json:"protocol,omitempty"`
	SizeBytes    *int64 `json:"size_bytes,omitempty"`
	Duration     int64  `json:"duration,omitempty"`
}

// ResolvedMedia is runtime-only and MUST NEVER be persisted into variant storage.
type ResolvedMedia struct {
	VariantID   string
	Variant     MediaVariant
	URL         string
	Expiry      time.Time
	Headers     map[string]string
	Cookies     map[string]string
	PreviewOnly bool
	Codec       string
	Bitrate     *int
	SizeBytes   *int64
	Lyrics      string
}

// ProviderTrack represents an upstream search/playlist candidate before DTO projection.
type ProviderTrack struct {
	PublicID      string         `json:"public_id"`
	Source        string         `json:"source"`
	SourceID      string         `json:"source_id"`
	RootSource    string         `json:"root_source,omitempty"`
	Title         string         `json:"title"`
	Artist        string         `json:"artist"`
	Album         string         `json:"album"`
	Duration      float64        `json:"duration"`
	Cover         *string        `json:"cover"`
	SizeBytes     *int64         `json:"size_bytes,omitempty"`
	SizeLabel     *string        `json:"size_label,omitempty"`
	Episodes      int            `json:"episodes,omitempty"`
	PlaylistIndex *int           `json:"playlist_index,omitempty"`
	Lyrics        string         `json:"lyrics,omitempty"`
	PayRequired   bool           `json:"pay_required,omitempty"`
	Variants      []MediaVariant `json:"variants"`
}

type Capability struct {
	Tier     string `json:"tier"`
	Note     string `json:"note"`
	Search   bool   `json:"search"`
	Playlist bool   `json:"playlist"`
	Lyrics   bool   `json:"lyrics,omitempty"`
	Metadata bool   `json:"metadata,omitempty"`
	Episodes bool   `json:"episodes,omitempty"`
	Partial  *bool  `json:"partial,omitempty"`
}

type SearchOptions struct {
	Keyword string
	Limit   int
	Refresh bool
}

type QualityPolicy struct {
	PreferredVariantID string
	PreferredQuality   string
	AllowPreview       bool
}

type SearchSummary struct {
	Count  int
	Detail string
}

type PlaylistSummary struct {
	ID     string
	Name   string
	Count  int
	Issues []PlaylistIssueDTO
	Detail string
}

// Provider defines the core music platform protocol contract.
type Provider interface {
	ID() string
	EngineName() string
	Capabilities() Capability
	Search(ctx context.Context, opts SearchOptions, emit func(ProviderTrack) error) (SearchSummary, error)
	Resolve(ctx context.Context, track ProviderTrack, policy QualityPolicy) ([]ResolvedMedia, error)
}

// PlaylistProvider is implemented by providers supporting playlist/album extraction.
type PlaylistProvider interface {
	Provider
	Playlist(ctx context.Context, locator string, emit func(ProviderTrack) error) (PlaylistSummary, error)
}

type Asset struct {
	ID             string            `json:"id"`
	Root           string            `json:"root"`
	RelPath        string            `json:"rel_path"`
	Folder         string            `json:"folder"`
	Name           string            `json:"name"`
	Size           int64             `json:"size"`
	MTimeNS        int64             `json:"mtime_ns"`
	ScanGeneration int64             `json:"scan_generation"`
	Missing        bool              `json:"missing"`
	Editable       bool              `json:"editable"`
	Metadata       map[string]string `json:"metadata,omitempty"`
	LockedFields   []string          `json:"locked_fields,omitempty"`
	UpdatedAt      int64             `json:"updated_at"`
}

type PublicRef struct {
	PublicID  string `json:"public_id"`
	Kind      string `json:"kind"` // candidate, asset, navidrome
	TargetID  string `json:"target_id"`
	Source    string `json:"source,omitempty"`
	SourceID  string `json:"source_id,omitempty"`
	Revision  int64  `json:"revision"`
	CreatedAt int64  `json:"created_at"`
}

type DownloadStatus string

const (
	DownloadQueued          DownloadStatus = "queued"
	DownloadResolving       DownloadStatus = "resolving"
	DownloadDownloading     DownloadStatus = "downloading"
	DownloadVerifying       DownloadStatus = "verifying"
	DownloadPublishing      DownloadStatus = "publishing"
	DownloadPauseRequested  DownloadStatus = "pause_requested"
	DownloadPaused          DownloadStatus = "paused"
	DownloadCancelRequested DownloadStatus = "cancel_requested"
	DownloadCancelled       DownloadStatus = "cancelled"
	DownloadRetryWait       DownloadStatus = "retry_wait"
	DownloadComplete        DownloadStatus = "complete"
	DownloadFailed          DownloadStatus = "failed"
	DownloadNeedsAttention  DownloadStatus = "needs_attention"
)

type Download struct {
	ID             string         `json:"id"`
	ActorKey       string         `json:"actor_key"`
	PublicID       string         `json:"public_id"`
	QualityID      string         `json:"quality_id"`
	QualityLabel   string         `json:"quality_label"`
	DownloadSource string         `json:"download_source,omitempty"`
	Status         DownloadStatus `json:"status"`
	Revision       int64          `json:"revision"`
	Attempt        int            `json:"attempt"`
	Bytes          int64          `json:"bytes"`
	TotalBytes     *int64         `json:"total_bytes"`
	Detail         string         `json:"detail"`
	File           *string        `json:"file"`
	IntentNonce    string         `json:"intent_nonce,omitempty"`
	NextAttemptAt  int64          `json:"next_attempt_at,omitempty"`
	CreatedAt      int64          `json:"created_at"`
	UpdatedAt      int64          `json:"updated_at"`
}

type DownloadOutput struct {
	DownloadID string `json:"download_id"`
	AssetID    string `json:"asset_id"`
	RelPath    string `json:"rel_path"`
	LyricsPath string `json:"lyrics_path,omitempty"`
	CoverPath  string `json:"cover_path,omitempty"`
	CreatedAt  int64  `json:"created_at"`
}

type TransferCheckpoint struct {
	DownloadID   string `json:"download_id"`
	Attempt      int    `json:"attempt"`
	TempPath     string `json:"temp_path"`
	Bytes        int64  `json:"bytes"`
	TotalBytes   *int64 `json:"total_bytes"`
	ETag         string `json:"etag,omitempty"`
	LastModified string `json:"last_modified,omitempty"`
	UpdatedAt    int64  `json:"updated_at"`
}

type LibraryJobStatus string

const (
	LibraryJobQueued         LibraryJobStatus = "queued"
	LibraryJobScanning       LibraryJobStatus = "scanning"
	LibraryJobPreviewReady   LibraryJobStatus = "preview_ready"
	LibraryJobNeedsReview    LibraryJobStatus = "needs_review"
	LibraryJobApplying       LibraryJobStatus = "applying"
	LibraryJobComplete       LibraryJobStatus = "complete"
	LibraryJobPartial        LibraryJobStatus = "partial"
	LibraryJobPaused         LibraryJobStatus = "paused"
	LibraryJobCancelled      LibraryJobStatus = "cancelled"
	LibraryJobFailed         LibraryJobStatus = "failed"
	LibraryJobNeedsAttention LibraryJobStatus = "needs_attention"
)

type LibraryItemStatus string

const (
	LibraryItemQueued       LibraryItemStatus = "queued"
	LibraryItemPreviewReady LibraryItemStatus = "preview_ready"
	LibraryItemNeedsReview  LibraryItemStatus = "needs_review"
	LibraryItemApplying     LibraryItemStatus = "applying"
	LibraryItemComplete     LibraryItemStatus = "complete"
	LibraryItemSkipped      LibraryItemStatus = "skipped"
	LibraryItemFailed       LibraryItemStatus = "failed"
)

type LibraryJob struct {
	ID        string           `json:"id"`
	Action    string           `json:"action"`
	Status    LibraryJobStatus `json:"status"`
	CreatedAt int64            `json:"created_at"`
	UpdatedAt int64            `json:"updated_at"`
	Total     int              `json:"total"`
	Processed int              `json:"processed"`
	Detail    string           `json:"detail"`
	Warnings  []string         `json:"warnings"`
}

type LibraryItem struct {
	ID           string            `json:"id"`
	JobID        string            `json:"job_id"`
	AssetID      string            `json:"asset_id,omitempty"`
	Path         string            `json:"path"`
	Name         string            `json:"name"`
	OriginalPath string            `json:"original_path"`
	Target       string            `json:"target"`
	Status       LibraryItemStatus `json:"status"`
	Chosen       *string           `json:"chosen"`
	Locks        []string          `json:"locks"`
	Warnings     []string          `json:"warnings"`
	Error        string            `json:"error,omitempty"`
	OperationID  string            `json:"operation_id,omitempty"`
}

type FileOperation struct {
	ID        string `json:"id"`
	Kind      string `json:"kind"`
	State     string `json:"state"`
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
}

type OperationFile struct {
	OperationID string `json:"operation_id"`
	AssetID     string `json:"asset_id,omitempty"`
	SourcePath  string `json:"source_path"`
	TargetPath  string `json:"target_path"`
	BackupPath  string `json:"backup_path,omitempty"`
	StagePath   string `json:"stage_path,omitempty"`
	State       string `json:"state"`
}

type Collection struct {
	ID        string `json:"id"`
	Owner     string `json:"owner"`
	Name      string `json:"name"`
	Source    string `json:"source"`
	SourceURL string `json:"source_url"`
	Revision  string `json:"revision"`
	Detail    string `json:"detail"`
	TaskID    string `json:"task_id"`
	CreatedAt int64  `json:"created_at"`
}

type CollectionItem struct {
	CollectionID string `json:"collection_id"`
	Position     int    `json:"position"`
	PublicID     string `json:"public_id"`
}

type OutboxStatus string

const (
	OutboxPending         OutboxStatus = "pending"
	OutboxSending         OutboxStatus = "sending"
	OutboxSent            OutboxStatus = "sent"
	OutboxRetryWait       OutboxStatus = "retry_wait"
	OutboxFailed          OutboxStatus = "failed"
	OutboxDeliveryUnknown OutboxStatus = "delivery_unknown"
)

type OutboxMessage struct {
	ID          string       `json:"id"`
	Channel     string       `json:"channel"`
	Recipient   string       `json:"recipient"`
	PayloadJSON string       `json:"payload_json"`
	Status      OutboxStatus `json:"status"`
	Attempt     int          `json:"attempt"`
	NextAttempt int64        `json:"next_attempt"`
	LastError   string       `json:"last_error,omitempty"`
	CreatedAt   int64        `json:"created_at"`
	UpdatedAt   int64        `json:"updated_at"`
}

type InboxEvent struct {
	Channel           string `json:"channel"`
	PlatformMessageID string `json:"platform_message_id"`
	SenderID          string `json:"sender_id"`
	ReceivedAt        int64  `json:"received_at"`
}

type OperationRequest struct {
	ActorKey       string `json:"actor_key"`
	OperationID    string `json:"operation_id"`
	IdempotencyKey string `json:"idempotency_key"`
	PayloadHash    string `json:"payload_hash"`
	Status         string `json:"status"`
	ResultJSON     string `json:"result_json"`
	CreatedAt      int64  `json:"created_at"`
}

// DeviceToken represents a persistent bearer token issued to a mobile/desktop client.
type DeviceToken struct {
	ID         string `json:"id"`
	TokenHash  string `json:"-"`
	UserID     string `json:"user_id"`
	DeviceName string `json:"device_name"`
	CreatedAt  int64  `json:"created_at"`
	LastUsedAt int64  `json:"last_used_at"`
	ExpiresAt  int64  `json:"expires_at"`
}
