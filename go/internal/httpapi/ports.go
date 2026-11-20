package httpapi

import (
	"context"
	"io"
	"io/fs"
	"net/http"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/agent"
	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/library"
	"github.com/agonie0v0/melokeep/internal/library/index"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/plugin"
	"github.com/agonie0v0/melokeep/internal/search"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

// RouteDef describes a single registered HTTP method+path contract entry.
type RouteDef struct {
	Method   string
	Path     string
	Endpoint string
}

// DeviceTokenDTO represents an issued persistent client token exposed in device management APIs.
type DeviceTokenDTO struct {
	ID         string `json:"id"`
	UserID     string `json:"user_id"`
	DeviceName string `json:"device_name"`
	CreatedAt  int64  `json:"created_at"`
	LastUsedAt int64  `json:"last_used_at"`
	ExpiresAt  int64  `json:"expires_at"`
	Current    bool   `json:"current"`
}

// BotGateway decouples HTTP routes from T12's bot/notification implementation.
type BotGateway interface {
	ReadConfig(ctx context.Context) (any, error)
	WriteConfig(ctx context.Context, actor auth.Actor, body map[string]any) (any, error)
	TestConnection(ctx context.Context, body map[string]any) (any, error)
	ServeSignedDownload(w http.ResponseWriter, r *http.Request, ident, expires, signature string) error
	VerifyWeCom(w http.ResponseWriter, r *http.Request) error
	ReceiveWeCom(w http.ResponseWriter, r *http.Request) error
	ReceiveFeishu(w http.ResponseWriter, r *http.Request) error
	ReceiveDingTalk(w http.ResponseWriter, r *http.Request) error
	ReceiveDiscord(w http.ResponseWriter, r *http.Request) error
	RegisterDiscordCommands(ctx context.Context) (any, error)
}

// NavidromeGateway decouples HTTP routes from T12's Navidrome implementation.
type NavidromeGateway interface {
	GetPublicConfig(ctx context.Context) (model.NavidromeConfigDTO, error)
	SaveConfig(ctx context.Context, actor auth.Actor, body map[string]any) (model.NavidromeConfigDTO, error)
	Capabilities(ctx context.Context) (map[string]any, error)
	DeletePreview(ctx context.Context, ident string) (map[string]any, error)
	DeleteSong(ctx context.Context, ident, revision string) (map[string]any, error)
	ListPlaylists(ctx context.Context) (any, error)
	GetPlaylist(ctx context.Context, ident string) (any, error)
	CreatePlaylist(ctx context.Context, actor auth.Actor, body map[string]any) (any, error)
	RenamePlaylist(ctx context.Context, actor auth.Actor, ident string, body map[string]any) (any, error)
	DeletePlaylist(ctx context.Context, actor auth.Actor, ident string) (any, error)
	AddPlaylistTracks(ctx context.Context, actor auth.Actor, ident string, body map[string]any) (any, error)
	RemovePlaylistTracks(ctx context.Context, actor auth.Actor, ident string, body map[string]any) (any, error)
	ListSongs(ctx context.Context, offset, size int) (any, error)
	GetSong(ctx context.Context, ident string) (model.TrackDTO, error)
	ListAlbums(ctx context.Context, offset, size int, sort string) (any, error)
	GetAlbum(ctx context.Context, ident string) (any, error)
	ListArtists(ctx context.Context) (any, error)
	GetArtist(ctx context.Context, ident string) (any, error)
	Search(ctx context.Context, query string, offset int) (any, error)
	ProxyCover(w http.ResponseWriter, r *http.Request, ident string) error
	ProxyStream(w http.ResponseWriter, r *http.Request, ident string) error
	OpenDownload(ctx context.Context, ident, rangeHeader string) (int, http.Header, io.ReadCloser, error)
	TestConnection(ctx context.Context) (any, error)
	ScanStatus(ctx context.Context) (any, error)
	StartScan(ctx context.Context, actor auth.Actor) (any, error)
	GetLyrics(ctx context.Context, ident, artist, title string) string
}

// PluginGateway decouples HTTP routes from the JS provider plugin manager.
// *plugin.Manager satisfies this interface directly.
type PluginGateway interface {
	List(ctx context.Context) ([]model.ProviderPluginDTO, error)
	Inspect(ctx context.Context, code, sourceURL string) (model.PluginInspectResultDTO, error)
	InspectArchive(ctx context.Context, archiveBytes []byte) (model.PluginArchiveInspectResultDTO, error)
	Install(ctx context.Context, req plugin.InstallRequest) (model.ProviderPluginDTO, error)
	InstallArchive(ctx context.Context, req plugin.InstallArchiveRequest) (model.PluginArchiveInstallResultDTO, error)
	Update(ctx context.Context, id string, req plugin.UpdateRequest) (model.ProviderPluginDTO, error)
	Delete(ctx context.Context, id string) (bool, error)
	Test(ctx context.Context, id string, req plugin.TestRequest) (model.PluginTestResultDTO, error)
}

type LogEntry struct {
	ID       int64  `json:"id"`
	Time     string `json:"time"`
	Level    string `json:"level"`
	Category string `json:"category"`
	TaskID   string `json:"task_id,omitempty"`
	Message  string `json:"message"`
}

type LogBuffer struct {
	mu      sync.Mutex
	maxSize int
	nextID  int64
	entries []LogEntry
}

func NewLogBuffer(maxSize int) *LogBuffer {
	if maxSize <= 0 {
		maxSize = 800
	}
	return &LogBuffer{maxSize: maxSize}
}

func (b *LogBuffer) Push(level, message, category, taskID string) {
	if b == nil {
		return
	}
	if level == "" {
		level = "info"
	} else if level == "warn" {
		level = "warning"
	}
	if category == "" {
		category = "system"
	}
	cleanMsg := redactTextURLs(message)
	b.mu.Lock()
	defer b.mu.Unlock()
	b.nextID++
	entry := LogEntry{
		ID:       b.nextID,
		Time:     time.Now().Format("2006-01-02 15:04:05"),
		Level:    level,
		Category: category,
		TaskID:   taskID,
		Message:  cleanMsg,
	}
	b.entries = append(b.entries, entry)
	if len(b.entries) > b.maxSize {
		drop := len(b.entries) - b.maxSize
		b.entries = append([]LogEntry(nil), b.entries[drop:]...)
	}
}

func (b *LogBuffer) List() []LogEntry {
	if b == nil {
		return []LogEntry{}
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]LogEntry, len(b.entries))
	copy(out, b.entries)
	return out
}

func (b *LogBuffer) Clear() {
	if b == nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	b.entries = nil
}

// Dependencies aggregates all services required to construct the HTTP API server.
type Dependencies struct {
	Store          *store.Store
	Sessions       *auth.SessionManager
	RateLimiter    *auth.LoginRateLimiter
	AuthEnabled    bool
	AuthUserDigest [32]byte
	AuthPassDigest [32]byte
	CookieSecure   bool
	TrustedProxies []string

	InitialSnapshot config.Snapshot
	Budgets         config.ResourceBudgets
	Providers       *provider.Registry
	Transport       *transport.Pool
	Search          *search.Service
	Download        *download.Service
	Files           *files.Coordinator
	Media           *media.Runner
	Tags            *media.TagService
	Indexer         *index.Indexer
	Library         *library.Service
	Agent           *agent.Service
	Operations      *operations.Registry

	Bot       BotGateway
	Navidrome NavidromeGateway
	Plugins   PluginGateway
	Logs      *LogBuffer

	FrontendFS      fs.FS
	EngineVersion   string
	BuildInfo       model.BuildInfoDTO
	UpdateRepo      string
	GitHubAPIBase   string
	ReadOnly        bool
	IsWindows       bool
	Platform        string
	WorkDir         string
	RestartCallback func()
	OpenDirCallback func() error
}
