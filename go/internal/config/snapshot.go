package config

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

// ResourceBudgets defines conservative concurrency and memory bounds from architecture.md §9.
type ResourceBudgets struct {
	ActiveSearchJobs          int `json:"active_search_jobs"`
	ProviderConcurrency       int `json:"provider_concurrency"`
	DownloadConcurrency       int `json:"download_concurrency"`
	MaxDownloadConcurrency    int `json:"max_download_concurrency"`
	MaxQueuedDownloads        int `json:"max_queued_downloads"`
	AudioToolsConcurrency     int `json:"audio_tools_concurrency"`
	LocalFileWriters          int `json:"local_file_writers"`
	ExtractorHelpers          int `json:"extractor_helpers"`
	TaggerHelpers             int `json:"tagger_helpers"`
	TotalHelpers              int `json:"total_helpers"`
	AISessionConcurrency      int `json:"ai_session_concurrency"`
	AIGlobalConcurrency       int `json:"ai_global_concurrency"`
	NotificationConcurrency   int `json:"notification_concurrency"`
	SearchCacheMaxBytes       int `json:"search_cache_max_bytes"`
	SearchCacheTTLSeconds     int `json:"search_cache_ttl_seconds"`
	SearchCacheMaxEntries     int `json:"search_cache_max_entries"`
	SearchLogBudgetBytes      int `json:"search_log_budget_bytes"`
	SearchJobMaxEvents        int `json:"search_job_max_events"`
	SearchJobMaxBytes         int `json:"search_job_max_bytes"`
	SearchCompletedRetention  int `json:"search_completed_retention"`
	SearchCompletedTTLSeconds int `json:"search_completed_ttl_seconds"`
	HelperMaxLineBytes        int `json:"helper_max_line_bytes"`
	HelperMaxOutputBytes      int `json:"helper_max_output_bytes"`
	HelperMaxStderrBytes      int `json:"helper_max_stderr_bytes"`
	DefaultJSONMaxBytes       int `json:"default_json_max_bytes"`
	ImageJSONMaxBytes         int `json:"image_json_max_bytes"`
	MaxBatchSize              int `json:"max_batch_size"`
}

func DefaultResourceBudgets() ResourceBudgets {
	return ResourceBudgets{
		ActiveSearchJobs:          6,
		ProviderConcurrency:       4,
		DownloadConcurrency:       2,
		MaxDownloadConcurrency:    4,
		MaxQueuedDownloads:        5000,
		AudioToolsConcurrency:     1,
		LocalFileWriters:          1,
		ExtractorHelpers:          2,
		TaggerHelpers:             1,
		TotalHelpers:              3,
		AISessionConcurrency:      1,
		AIGlobalConcurrency:       2,
		NotificationConcurrency:   2,
		SearchCacheMaxBytes:       8 << 20,
		SearchCacheTTLSeconds:     30,
		SearchCacheMaxEntries:     32,
		SearchLogBudgetBytes:      16 << 20,
		SearchJobMaxEvents:        4096,
		SearchJobMaxBytes:         4 << 20,
		SearchCompletedRetention:  32,
		SearchCompletedTTLSeconds: 300,
		HelperMaxLineBytes:        1 << 20,
		HelperMaxOutputBytes:      16 << 20,
		HelperMaxStderrBytes:      256 << 10,
		DefaultJSONMaxBytes:       1 << 20,
		ImageJSONMaxBytes:         5 << 20,
		MaxBatchSize:              1000,
	}
}

// RuntimeConfig holds active application settings (with secrets stored separately from public DTOs).
type RuntimeConfig struct {
	Engine                 string                            `json:"engine"`
	Sources                []string                          `json:"sources"`
	ResultsPerSource       int                               `json:"results_per_source"`
	SearchTimeoutSeconds   int                               `json:"search_timeout_seconds"`
	PlaybackTimeoutSeconds int                               `json:"playback_timeout_seconds"`
	DownloadConcurrency    int                               `json:"download_concurrency"`
	DownloadLayout         string                            `json:"download_layout"`
	LyricsMode             string                            `json:"lyrics_mode"`
	EmbedMetadata          bool                              `json:"embed_metadata"`
	SourceSettings         map[string]model.SourceSettingDTO `json:"source_settings"`
	ProxyURL               string                            `json:"proxy_url,omitempty"`
	ProxyScope             string                            `json:"proxy_scope,omitempty"`
	ProxySources           []string                          `json:"proxy_sources,omitempty"`
	WorkDir                string                            `json:"work_dir,omitempty"`
	DataDir                string                            `json:"data_dir,omitempty"`
}

func DefaultRuntimeConfig() RuntimeConfig {
	return RuntimeConfig{
		Engine:                 "MeloKeep engine 1.0.0",
		Sources:                []string{"AudiusMusicClient", "FMAMusicClient", "ITunesMusicClient"},
		ResultsPerSource:       5,
		SearchTimeoutSeconds:   30,
		PlaybackTimeoutSeconds: 30,
		DownloadConcurrency:    2,
		DownloadLayout:         "source",
		LyricsMode:             "both",
		EmbedMetadata:          true,
		SourceSettings:         map[string]model.SourceSettingDTO{},
		ProxyScope:             "all",
		ProxySources:           []string{},
		WorkDir:                "melokeep_outputs",
		DataDir:                "data",
	}
}

// Snapshot is an immutable configuration snapshot identified by its content SHA-256 Revision.
type Snapshot struct {
	Revision  string
	CreatedAt time.Time
	Runtime   RuntimeConfig
	Budgets   ResourceBudgets
}

func NewSnapshot(rt RuntimeConfig, budgets ResourceBudgets) Snapshot {
	cloned := cloneRuntimeConfig(rt)
	snap := Snapshot{
		CreatedAt: time.Now().UTC(),
		Runtime:   cloned,
		Budgets:   budgets,
	}
	snap.Revision = computeRevision(cloned, budgets)
	return snap
}

// PublicDTO projects a Snapshot into the redacted legacy ConfigDTO shape.
func (s Snapshot) PublicDTO(available []model.AvailableSourceDTO) model.ConfigDTO {
	sources := make([]string, len(s.Runtime.Sources))
	copy(sources, s.Runtime.Sources)

	settings := make(map[string]model.SourceSettingDTO, len(s.Runtime.SourceSettings))
	for k, v := range s.Runtime.SourceSettings {
		settings[k] = model.SourceSettingDTO{
			Cookie: RedactValue(v.Cookie),
			Proxy:  RedactURL(v.Proxy),
		}
	}

	return model.ConfigDTO{
		AvailableSources:       available,
		Engine:                 s.Runtime.Engine,
		Sources:                sources,
		ResultsPerSource:       s.Runtime.ResultsPerSource,
		SearchTimeoutSeconds:   s.Runtime.SearchTimeoutSeconds,
		PlaybackTimeoutSeconds: s.Runtime.PlaybackTimeoutSeconds,
		DownloadConcurrency:    s.Runtime.DownloadConcurrency,
		DownloadLayout:         s.Runtime.DownloadLayout,
		LyricsMode:             s.Runtime.LyricsMode,
		EmbedMetadata:          s.Runtime.EmbedMetadata,
		SourceSettings:         settings,
	}
}

func cloneRuntimeConfig(rt RuntimeConfig) RuntimeConfig {
	out := rt
	out.Sources = append([]string(nil), rt.Sources...)
	out.ProxySources = append([]string(nil), rt.ProxySources...)
	out.SourceSettings = make(map[string]model.SourceSettingDTO, len(rt.SourceSettings))
	for k, v := range rt.SourceSettings {
		out.SourceSettings[k] = v
	}
	return out
}

func computeRevision(rt RuntimeConfig, budgets ResourceBudgets) string {
	type canonSetting struct {
		Key    string                 `json:"k"`
		Val    model.SourceSettingDTO `json:"v"`
	}
	keys := make([]string, 0, len(rt.SourceSettings))
	for k := range rt.SourceSettings {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	settings := make([]canonSetting, 0, len(keys))
	for _, k := range keys {
		settings = append(settings, canonSetting{Key: k, Val: rt.SourceSettings[k]})
	}
	payload := struct {
		Runtime  RuntimeConfig   `json:"runtime"`
		Settings []canonSetting  `json:"settings"`
		Budgets  ResourceBudgets `json:"budgets"`
	}{
		Runtime:  rt,
		Settings: settings,
		Budgets:  budgets,
	}
	payload.Runtime.SourceSettings = nil
	raw, _ := json.Marshal(payload)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:16])
}
