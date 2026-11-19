package model

import (
	"net/url"
	"strings"
)

// QualityDTO matches frontend/src/types.ts Quality.
type QualityDTO struct {
	ID           string `json:"id"`
	Label        string `json:"label"`
	Codec        string `json:"codec"`
	Bitrate      *int   `json:"bitrate"`
	Lossless     bool   `json:"lossless"`
	Availability string `json:"availability"`
	Evidence     string `json:"evidence"`
}

// PlaybackDTO matches frontend/src/types.ts Track.playback.
type PlaybackDTO struct {
	State       string  `json:"state"`
	URL         *string `json:"url"`
	Codec       *string `json:"codec"`
	Bitrate     *int    `json:"bitrate"`
	Detail      *string `json:"detail"`
	PreviewOnly bool    `json:"preview_only"`
}

// TrackDTO matches frontend/src/types.ts Track.
type TrackDTO struct {
	PlaylistIndex *int         `json:"playlist_index,omitempty"`
	ID            string       `json:"id"`
	Source        string       `json:"source"`
	SourceID      string       `json:"source_id"`
	Title         string       `json:"title"`
	Artist        string       `json:"artist"`
	Album         string       `json:"album"`
	Duration      float64      `json:"duration"`
	Cover         *string      `json:"cover"`
	SizeBytes     *int64       `json:"size_bytes,omitempty"`
	SizeLabel     *string      `json:"size_label,omitempty"`
	Episodes      int          `json:"episodes,omitempty"`
	RootSource    string       `json:"root_source,omitempty"`
	Qualities     []QualityDTO `json:"qualities"`
	Playback      PlaybackDTO  `json:"playback"`
}

type DownloadProcessingDTO struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Detail string `json:"detail"`
}

// DownloadJobDTO matches frontend/src/types.ts DownloadJob.
type DownloadJobDTO struct {
	ID             string                 `json:"id"`
	Track          TrackDTO               `json:"track"`
	QualityID      string                 `json:"quality_id"`
	QualityLabel   string                 `json:"quality_label"`
	DownloadSource string                 `json:"download_source,omitempty"`
	Status         string                 `json:"status"`
	Bytes          int64                  `json:"bytes"`
	TotalBytes     *int64                 `json:"total_bytes"`
	Detail         string                 `json:"detail"`
	File           *string                `json:"file"`
	Processing     *DownloadProcessingDTO `json:"processing,omitempty"`
}

// LibraryFileDTO matches frontend/src/types.ts LibraryFile.
type LibraryFileDTO struct {
	Path         string            `json:"path"`
	Name         string            `json:"name"`
	Size         int64             `json:"size"`
	Root         string            `json:"root,omitempty"`
	Folder       string            `json:"folder,omitempty"`
	Track        *TrackDTO         `json:"track,omitempty"`
	Editable     *bool             `json:"editable,omitempty"`
	Metadata     map[string]string `json:"metadata,omitempty"`
	LockedFields []string          `json:"locked_fields,omitempty"`
}

// LibraryFolderDTO identifies a distinct indexed library directory under a root.
type LibraryFolderDTO struct {
	Root   string `json:"root"`
	Folder string `json:"folder"`
}

// LibraryFilesPageDTO implements the v1 paginated /api/library/files response.
type LibraryFilesPageDTO struct {
	Items         []LibraryFileDTO   `json:"items"`
	NextCursor    *string            `json:"next_cursor"`
	IndexRevision int64              `json:"index_revision"`
	Indexing      bool               `json:"indexing"`
	Folders       []LibraryFolderDTO `json:"folders,omitempty"`
}

type MetadataSourceDTO struct {
	ID          string   `json:"id"`
	Label       string   `json:"label"`
	Description string   `json:"description"`
	Fields      []string `json:"fields"`
	Note        string   `json:"note"`
	URL         string   `json:"url"`
}

type MetadataCandidateDTO struct {
	ID           string            `json:"id"`
	Source       string            `json:"source"`
	Title        string            `json:"title"`
	Artist       string            `json:"artist"`
	Album        string            `json:"album"`
	Score        float64           `json:"score"`
	Reasons      []string          `json:"reasons"`
	Conflicts    []string          `json:"conflicts"`
	FieldSources map[string]string `json:"field_sources,omitempty"`
}

type LibraryJobOutputDTO struct {
	Path  string   `json:"path"`
	Start *float64 `json:"start,omitempty"`
	End   *float64 `json:"end,omitempty"`
}

type LibraryJobArtworkDTO struct {
	Artist string `json:"artist"`
	Source string `json:"source"`
	URL    string `json:"url"`
}

type LibraryJobItemDTO struct {
	ID           string                 `json:"id"`
	Path         string                 `json:"path"`
	Name         string                 `json:"name"`
	OriginalPath string                 `json:"original_path"`
	Target       string                 `json:"target"`
	Status       string                 `json:"status"`
	Before       map[string]string      `json:"before"`
	After        map[string]string      `json:"after"`
	Candidates   []MetadataCandidateDTO `json:"candidates"`
	Chosen       *string                `json:"chosen"`
	Locks        []string               `json:"locks"`
	Warnings     []string               `json:"warnings"`
	Error        string                 `json:"error,omitempty"`
	OperationID  string                 `json:"operation_id,omitempty"`
	Quality      map[string]*float64    `json:"quality,omitempty"`
	Outputs      []LibraryJobOutputDTO  `json:"outputs,omitempty"`
	Artwork      *LibraryJobArtworkDTO  `json:"artwork,omitempty"`
}

type LibraryJobSettingsDTO struct {
	Sources   []string `json:"sources"`
	Overwrite bool     `json:"overwrite"`
	Fields    []string `json:"fields"`
}

type LibraryDuplicateDTO struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Path string `json:"path"`
}

type LibraryJobDTO struct {
	ID         string                  `json:"id"`
	Action     string                  `json:"action"`
	Status     string                  `json:"status"`
	CreatedAt  int64                   `json:"created_at"`
	UpdatedAt  int64                   `json:"updated_at"`
	Total      int                     `json:"total"`
	Processed  int                     `json:"processed"`
	Detail     string                  `json:"detail"`
	Items      []LibraryJobItemDTO     `json:"items,omitempty"`
	Warnings   []string                `json:"warnings"`
	Settings   *LibraryJobSettingsDTO  `json:"settings,omitempty"`
	Duplicates [][]LibraryDuplicateDTO `json:"duplicates,omitempty"`
}

type SourceStatusDTO struct {
	State  string `json:"state"`
	Count  *int   `json:"count,omitempty"`
	Detail string `json:"detail,omitempty"`
}

type PlaylistIssueDTO struct {
	SourceID string `json:"source_id"`
	Title    string `json:"title"`
	Artist   string `json:"artist"`
	Reason   string `json:"reason"`
}

type SavedPlaylistDTO struct {
	ReadOnly  *bool              `json:"read_only,omitempty"`
	CanManage *bool              `json:"can_manage,omitempty"`
	Revision  string             `json:"revision,omitempty"`
	Owner     string             `json:"owner,omitempty"`
	ID        string             `json:"id"`
	Name      string             `json:"name"`
	Source    string             `json:"source"`
	SourceURL string             `json:"source_url"`
	CreatedAt int64              `json:"created_at"`
	Tracks    []TrackDTO         `json:"tracks"`
	Issues    []PlaylistIssueDTO `json:"issues"`
	Detail    string             `json:"detail"`
	TaskID    string             `json:"task_id"`
}

type PlaylistSummaryDTO struct {
	ReadOnly   *bool   `json:"read_only,omitempty"`
	CanManage  *bool   `json:"can_manage,omitempty"`
	Revision   string  `json:"revision,omitempty"`
	Owner      string  `json:"owner,omitempty"`
	ID         string  `json:"id"`
	Name       string  `json:"name"`
	Source     string  `json:"source"`
	SourceURL  string  `json:"source_url"`
	CreatedAt  int64   `json:"created_at"`
	Detail     string  `json:"detail"`
	TaskID     string  `json:"task_id"`
	TrackCount int     `json:"track_count"`
	IssueCount int     `json:"issue_count"`
	Cover      *string `json:"cover"`
}

type SourceSettingDTO struct {
	Cookie string `json:"cookie"`
	Proxy  string `json:"proxy"`
}

type SourceCapabilityDTO struct {
	Tier     string `json:"tier"`
	Note     string `json:"note"`
	Search   bool   `json:"search"`
	Playlist bool   `json:"playlist"`
	Lyrics   bool   `json:"lyrics,omitempty"`
	Metadata bool   `json:"metadata,omitempty"`
	Episodes bool   `json:"episodes,omitempty"`
	Partial  *bool  `json:"partial,omitempty"`
}

type AvailableSourceDTO struct {
	ID               string               `json:"id"`
	Label            string               `json:"label"`
	Category         string               `json:"category,omitempty"`
	EngineName       string               `json:"engine_name,omitempty"`
	Origin           string               `json:"origin"`
	PluginID         string               `json:"plugin_id,omitempty"`
	Version          string               `json:"version,omitempty"`
	Description      string               `json:"description,omitempty"`
	ProxyRecommended bool                 `json:"proxy_recommended"`
	InitError        *string              `json:"init_error"`
	Capability       *SourceCapabilityDTO `json:"capability,omitempty"`
}

// PluginSettingFieldDTO defines a declarative configuration field exposed by a provider plugin.
type PluginSettingFieldDTO struct {
	Key          string `json:"key"`
	Label        string `json:"label"`
	Type         string `json:"type"`
	Placeholder  string `json:"placeholder,omitempty"`
	Required     bool   `json:"required,omitempty"`
	DefaultValue string `json:"default_value,omitempty"`
}

// PluginManifestDTO describes the static manifest declared by a provider plugin script.
type PluginManifestDTO struct {
	APIVersion   int                     `json:"api_version"`
	ID           string                  `json:"id"`
	Name         string                  `json:"name"`
	Version      string                  `json:"version"`
	Description  string                  `json:"description,omitempty"`
	Category     string                  `json:"category,omitempty"`
	Capabilities SourceCapabilityDTO     `json:"capabilities"`
	Settings     []PluginSettingFieldDTO `json:"settings,omitempty"`
}

// ProviderPluginDTO represents an installed provider plugin for API responses.
type ProviderPluginDTO struct {
	ID             string            `json:"id"`
	Enabled        bool              `json:"enabled"`
	SourceURL      string            `json:"source_url,omitempty"`
	SHA256         string            `json:"sha256,omitempty"`
	Manifest       PluginManifestDTO `json:"manifest"`
	Settings       map[string]string `json:"settings"`
	AllowedOrigins []string          `json:"allowed_origins"`
	LastError      string            `json:"last_error,omitempty"`
	CreatedAt      int64             `json:"created_at"`
	UpdatedAt      int64             `json:"updated_at"`
}

// PluginInspectResultDTO summarizes static inspection output for a plugin script prior to installation.
type PluginInspectResultDTO struct {
	SHA256      string            `json:"sha256"`
	Manifest    PluginManifestDTO `json:"manifest"`
	Permissions []string          `json:"permissions"`
	Warnings    []string          `json:"warnings"`
}

// PluginLogEntryDTO captures a single log record emitted during plugin verification/execution.
type PluginLogEntryDTO struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    string `json:"time"`
}

// PluginTestResultDTO summarizes a single sandbox test action invocation against a plugin.
type PluginTestResultDTO struct {
	OK         bool                `json:"ok"`
	Action     string              `json:"action"`
	DurationMS int64               `json:"duration_ms"`
	Result     any                 `json:"result,omitempty"`
	Logs       []PluginLogEntryDTO `json:"logs,omitempty"`
	Error      string              `json:"error,omitempty"`
}

// ConfigDTO matches frontend/src/types.ts Config and contracts/v1/config/samples.json.
type ConfigDTO struct {
	AvailableSources       []AvailableSourceDTO        `json:"available_sources,omitempty"`
	Engine                 string                      `json:"engine,omitempty"`
	Sources                []string                    `json:"sources"`
	ResultsPerSource       int                         `json:"results_per_source"`
	SearchTimeoutSeconds   int                         `json:"search_timeout_seconds"`
	PlaybackTimeoutSeconds int                         `json:"playback_timeout_seconds"`
	DownloadConcurrency    int                         `json:"download_concurrency"`
	DownloadLayout         string                      `json:"download_layout"`
	LyricsMode             string                      `json:"lyrics_mode"`
	EmbedMetadata          bool                        `json:"embed_metadata"`
	SourceSettings         map[string]SourceSettingDTO `json:"source_settings"`
}

type NavidromeConfigDTO struct {
	Enabled         bool   `json:"enabled"`
	URL             string `json:"url"`
	Username        string `json:"username"`
	AutoScan        bool   `json:"auto_scan"`
	DeleteEnabled   bool   `json:"delete_enabled,omitempty"`
	MusicRoot       string `json:"music_root,omitempty"`
	ServerMusicRoot string `json:"server_music_root,omitempty"`
	HasPassword     bool   `json:"has_password"`
	HasAPIKey       bool   `json:"has_api_key"`
}

type LibraryConfigDTO struct {
	AutoScrape         bool                `json:"auto_scrape"`
	AutoOrganize       bool                `json:"auto_organize"`
	LibraryDir         string              `json:"library_dir"`
	FileRoot           string              `json:"file_root"`
	FolderTemplate     string              `json:"folder_template"`
	FilenameTemplate   string              `json:"filename_template"`
	Sources            []string            `json:"sources"`
	Overwrite          bool                `json:"overwrite"`
	LyricsMode         string              `json:"lyrics_mode"`
	CoverMode          string              `json:"cover_mode"`
	Fields             []string            `json:"fields"`
	BackupDays         int                 `json:"backup_days"`
	Navidrome          NavidromeConfigDTO  `json:"navidrome"`
	MetadataSources    []MetadataSourceDTO `json:"metadata_sources,omitempty"`
	Chinese            string              `json:"chinese,omitempty"`
	RepairEncoding     bool                `json:"repair_encoding,omitempty"`
	WatchEnabled       bool                `json:"watch_enabled,omitempty"`
	WatchDirs          []string            `json:"watch_dirs,omitempty"`
	WatchInterval      int                 `json:"watch_interval,omitempty"`
	WatchStableSeconds int                 `json:"watch_stable_seconds,omitempty"`
	WatchAction        string              `json:"watch_action,omitempty"`
	WatchApply         bool                `json:"watch_apply,omitempty"`
	WatchFailureDir    string              `json:"watch_failure_dir,omitempty"`
	HasAcoustIDKey     bool                `json:"has_acoustid_key,omitempty"`
	Tools              map[string]bool     `json:"tools,omitempty"`
}

// ErrorDTO preserves the legacy FastAPI {"detail": "..."} top-level envelope.
type ErrorDTO struct {
	Detail any `json:"detail"`
}

func QualityDTOFromVariant(v MediaVariant) QualityDTO {
	return QualityDTO{
		ID:           v.VariantID,
		Label:        v.Label,
		Codec:        v.Codec,
		Bitrate:      v.Bitrate,
		Lossless:     v.Lossless,
		Availability: v.Availability,
		Evidence:     v.Evidence,
	}
}

func TrackDTOFromDomain(t ProviderTrack, playback PlaybackDTO) TrackDTO {
	qualities := make([]QualityDTO, 0, len(t.Variants))
	for _, v := range t.Variants {
		qualities = append(qualities, QualityDTOFromVariant(v))
	}
	return TrackDTO{
		PlaylistIndex: t.PlaylistIndex,
		ID:            t.PublicID,
		Source:        t.Source,
		SourceID:      t.SourceID,
		Title:         t.Title,
		Artist:        t.Artist,
		Album:         t.Album,
		Duration:      t.Duration,
		Cover:         t.Cover,
		SizeBytes:     t.SizeBytes,
		SizeLabel:     t.SizeLabel,
		Episodes:      t.Episodes,
		RootSource:    t.RootSource,
		Qualities:     qualities,
		Playback:      playback,
	}
}

// ApplyResolvedMedia projects resolved runtime variants and primary size onto tr.
func ApplyResolvedMedia(tr *ProviderTrack, resolved []ResolvedMedia) {
	if tr == nil || len(resolved) == 0 {
		return
	}
	variants := make([]MediaVariant, 0, len(resolved))
	for _, rm := range resolved {
		v := rm.Variant
		if v.VariantID == "" {
			v.VariantID = rm.VariantID
		}
		if v.Codec == "" {
			v.Codec = rm.Codec
		}
		if v.Bitrate == nil {
			v.Bitrate = rm.Bitrate
		}
		if v.SizeBytes == nil {
			v.SizeBytes = rm.SizeBytes
		}
		if v.Protocol == "" {
			if strings.HasPrefix(rm.URL, "http://") || strings.HasPrefix(rm.URL, "https://") {
				v.Protocol = "http"
			} else {
				v.Protocol = "download_only"
			}
		}
		variants = append(variants, v)
	}
	tr.Variants = variants
	if resolved[0].SizeBytes != nil {
		tr.SizeBytes = resolved[0].SizeBytes
	}
	if strings.TrimSpace(tr.Lyrics) == "" {
		for _, item := range resolved {
			if txt := strings.TrimSpace(item.Lyrics); txt != "" {
				tr.Lyrics = txt
				break
			}
		}
	}
}

// PlaybackDTOFromResolvedTrack derives PlaybackDTO from a track's resolved variants.
func PlaybackDTOFromResolvedTrack(tr ProviderTrack, unavailableDetail string) PlaybackDTO {
	if len(tr.Variants) == 0 {
		detail := strings.TrimSpace(unavailableDetail)
		if detail == "" {
			detail = "暂无可用播放地址"
		}
		return PlaybackDTO{
			State:  "unavailable",
			Detail: &detail,
		}
	}
	primary := tr.Variants[0]
	var codecPtr *string
	if primary.Codec != "" {
		c := primary.Codec
		codecPtr = &c
	}
	if primary.Protocol == "download_only" {
		detail := "该解析结果需要原音源下载器处理，可直接下载"
		return PlaybackDTO{
			State:       "download_only",
			URL:         nil,
			Codec:       codecPtr,
			Bitrate:     primary.Bitrate,
			Detail:      &detail,
			PreviewOnly: primary.PreviewOnly,
		}
	}
	streamURL := "/api/stream/" + url.PathEscape(tr.PublicID)
	detail := "可直接试听"
	if primary.PreviewOnly {
		detail = "片段试听"
	}
	return PlaybackDTO{
		State:       "ready",
		URL:         &streamURL,
		Codec:       codecPtr,
		Bitrate:     primary.Bitrate,
		Detail:      &detail,
		PreviewOnly: primary.PreviewOnly,
	}
}

// LegacyDownloadStatus maps internal granular download states to legacy UI statuses.
func LegacyDownloadStatus(s DownloadStatus) string {
	switch s {
	case DownloadQueued, DownloadResolving, DownloadRetryWait:
		return "queued"
	case DownloadDownloading, DownloadVerifying, DownloadPublishing:
		return "downloading"
	case DownloadPauseRequested, DownloadPaused:
		return "paused"
	case DownloadCancelRequested, DownloadCancelled:
		return "cancelled"
	case DownloadComplete:
		return "complete"
	default:
		return "failed"
	}
}

// BuildInfoDTO describes the running MeloKeep build metadata.
type BuildInfoDTO struct {
	Version     string `json:"version"`
	Commit      string `json:"commit,omitempty"`
	ShortCommit string `json:"short_commit,omitempty"`
	Ref         string `json:"ref,omitempty"`
	BuildTime   string `json:"build_time,omitempty"`
	Dirty       bool   `json:"dirty,omitempty"`
	InContainer bool   `json:"in_container"`
}

// UpdateTargetDTO describes the latest available release or branch commit on GitHub.
type UpdateTargetDTO struct {
	Version     string `json:"version,omitempty"`
	Tag         string `json:"tag,omitempty"`
	Commit      string `json:"commit,omitempty"`
	ShortCommit string `json:"short_commit,omitempty"`
	Title       string `json:"title,omitempty"`
	PublishedAt string `json:"published_at,omitempty"`
	URL         string `json:"url,omitempty"`
}

// UpdateCommitDTO describes a single commit entry in the update changelog.
type UpdateCommitDTO struct {
	SHA     string `json:"sha"`
	Short   string `json:"short"`
	Title   string `json:"title"`
	Author  string `json:"author,omitempty"`
	Date    string `json:"date,omitempty"`
	URL     string `json:"url,omitempty"`
}

// SystemUpdateDTO summarizes version comparison and changelog data for the UI.
type SystemUpdateDTO struct {
	Repo         string            `json:"repo"`
	Mode         string            `json:"mode"` // "release", "compare", "commits", or "error"
	Current      BuildInfoDTO      `json:"current"`
	Latest       *UpdateTargetDTO  `json:"latest,omitempty"`
	HasUpdate    bool              `json:"has_update"`
	Comparable   bool              `json:"comparable"`
	AheadBy      int               `json:"ahead_by,omitempty"`
	BehindBy     int               `json:"behind_by,omitempty"`
	ReleaseNotes string            `json:"release_notes,omitempty"`
	Commits      []UpdateCommitDTO `json:"commits"`
	CheckedAt    string            `json:"checked_at"`
	Cached       bool              `json:"cached"`
	Error        string            `json:"error,omitempty"`
}

// AppearancePreferencesDTO describes persisted UI theme and density preferences.
type AppearancePreferencesDTO struct {
	Mode         string  `json:"mode,omitempty"`
	LightPreset  string  `json:"light_preset,omitempty"`
	DarkPreset   string  `json:"dark_preset,omitempty"`
	CustomAccent *string `json:"custom_accent,omitempty"`
	AmbientGlow  *bool   `json:"ambient_glow,omitempty"`
	Density      string  `json:"density,omitempty"`
}

// PlayerPreferencesDTO describes persisted player audio and lyric preferences.
type PlayerPreferencesDTO struct {
	Volume       *float64 `json:"volume,omitempty"`
	Shuffle      *bool    `json:"shuffle,omitempty"`
	Repeat       *bool    `json:"repeat,omitempty"`
	FollowLyrics *bool    `json:"follow_lyrics,omitempty"`
	LargeLyrics  *bool    `json:"large_lyrics,omitempty"`
}

// NavidromeViewPreferencesDTO describes persisted Navidrome browser view preferences.
type NavidromeViewPreferencesDTO struct {
	Mode   string `json:"mode,omitempty"`
	Layout string `json:"layout,omitempty"`
}

// UIPreferencesDTO stores cross-browser UI preferences in the settings store.
type UIPreferencesDTO struct {
	Appearance       *AppearancePreferencesDTO    `json:"appearance,omitempty"`
	NavigationOrder  []string                     `json:"navigation_order,omitempty"`
	Player           *PlayerPreferencesDTO        `json:"player,omitempty"`
	LibraryTreeWidth *int                         `json:"library_tree_width,omitempty"`
	NavidromeView    *NavidromeViewPreferencesDTO `json:"navidrome_view,omitempty"`
	DismissedUpdate  *string                      `json:"dismissed_update,omitempty"`
}
