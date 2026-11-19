package navidrome

import (
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

// Settings holds server-side Navidrome OpenSubsonic configuration.
// Password and APIKey are secret fields and must never appear in PublicDTO or logs.
type Settings struct {
	Enabled         bool   `json:"enabled"`
	URL             string `json:"url"`
	Username        string `json:"username"`
	Password        string `json:"password,omitempty"`
	APIKey          string `json:"api_key,omitempty"`
	AutoScan        bool   `json:"auto_scan"`
	DeleteEnabled   bool   `json:"delete_enabled"`
	MusicRoot       string `json:"music_root"`
	ServerMusicRoot string `json:"server_music_root"`
}

// SettingsUpdate represents an inbound config update where HiddenPlaceholder preserves existing secrets.
type SettingsUpdate struct {
	Enabled         bool   `json:"enabled"`
	URL             string `json:"url"`
	Username        string `json:"username"`
	Password        string `json:"password,omitempty"`
	APIKey          string `json:"api_key,omitempty"`
	ClearPassword   bool   `json:"clear_password,omitempty"`
	ClearAPIKey     bool   `json:"clear_api_key,omitempty"`
	AutoScan        bool   `json:"auto_scan"`
	DeleteEnabled   bool   `json:"delete_enabled"`
	MusicRoot       string `json:"music_root"`
	ServerMusicRoot string `json:"server_music_root"`
}

// ValidateSettings checks URL shape and field constraints matching navidrome.py.
func ValidateSettings(s Settings) (Settings, error) {
	s.URL = strings.TrimRight(strings.TrimSpace(s.URL), "/")
	s.Username = strings.TrimSpace(s.Username)
	s.MusicRoot = strings.TrimSpace(s.MusicRoot)
	s.ServerMusicRoot = strings.TrimSpace(s.ServerMusicRoot)
	if len(s.URL) > 2048 || len(s.Username) > 200 || len(s.Password) > 1000 || len(s.APIKey) > 1000 ||
		len(s.MusicRoot) > 2048 || len(s.ServerMusicRoot) > 2048 {
		return Settings{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Navidrome 配置字段超出最大长度限制")
	}
	if s.URL != "" {
		u, err := url.Parse(s.URL)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" ||
			u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return Settings{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Navidrome 地址应为不含账号、参数的 HTTP / HTTPS 地址")
		}
	}
	return s, nil
}

// NormalizeSettingsUpdate treats omitted or null secret fields in a raw JSON
// body as preserving existing secrets unless explicitly cleared.
func NormalizeSettingsUpdate(body map[string]any, upd *SettingsUpdate) {
	if upd == nil {
		return
	}
	if !upd.ClearPassword {
		if val, exists := body["password"]; !exists || val == nil {
			upd.Password = config.HiddenPlaceholder
		}
	}
	if !upd.ClearAPIKey {
		if val, exists := body["api_key"]; !exists || val == nil {
			upd.APIKey = config.HiddenPlaceholder
		}
	}
}

// MergeSettings applies incoming changes while preserving secrets for HiddenPlaceholder.
func MergeSettings(existing Settings, incoming SettingsUpdate) (Settings, error) {
	out := Settings{
		Enabled:         incoming.Enabled,
		URL:             incoming.URL,
		Username:        incoming.Username,
		Password:        config.MergeHiddenString(existing.Password, incoming.Password),
		APIKey:          config.MergeHiddenString(existing.APIKey, incoming.APIKey),
		AutoScan:        incoming.AutoScan,
		DeleteEnabled:   incoming.DeleteEnabled,
		MusicRoot:       incoming.MusicRoot,
		ServerMusicRoot: incoming.ServerMusicRoot,
	}
	if incoming.ClearPassword && (incoming.Password == "" || incoming.Password == config.HiddenPlaceholder) {
		out.Password = ""
	}
	if incoming.ClearAPIKey && (incoming.APIKey == "" || incoming.APIKey == config.HiddenPlaceholder) {
		out.APIKey = ""
	}
	return ValidateSettings(out)
}

// PublicDTO projects Settings into NavidromeConfigDTO without exposing credentials.
func (s Settings) PublicDTO() model.NavidromeConfigDTO {
	return model.NavidromeConfigDTO{
		Enabled:         s.Enabled,
		URL:             s.URL,
		Username:        s.Username,
		AutoScan:        s.AutoScan,
		DeleteEnabled:   s.DeleteEnabled,
		MusicRoot:       s.MusicRoot,
		ServerMusicRoot: s.ServerMusicRoot,
		HasPassword:     strings.TrimSpace(s.Password) != "",
		HasAPIKey:       strings.TrimSpace(s.APIKey) != "",
	}
}

type PingResult struct {
	OK      bool   `json:"ok"`
	Version string `json:"version,omitempty"`
}

type ScanStatus struct {
	Scanning   bool  `json:"scanning"`
	Count      int64 `json:"count"`
	FolderCount int64 `json:"folderCount,omitempty"`
}

type AlbumDTO struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	Artist    string  `json:"artist"`
	Year      *int    `json:"year,omitempty"`
	SongCount int     `json:"song_count"`
	Cover     *string `json:"cover"`
}

type AlbumDetailDTO struct {
	AlbumDTO
	Tracks []model.TrackDTO `json:"tracks"`
}

type ArtistDTO struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	AlbumCount int    `json:"album_count"`
}

type ArtistDetailDTO struct {
	Name   string     `json:"name"`
	Albums []AlbumDTO `json:"albums"`
}

type SongsPageDTO struct {
	Tracks  []model.TrackDTO `json:"tracks"`
	HasMore bool             `json:"has_more"`
}

type AlbumsPageDTO struct {
	Albums  []AlbumDTO `json:"albums"`
	HasMore bool       `json:"has_more"`
}

type SearchResultDTO struct {
	Tracks  []model.TrackDTO `json:"tracks"`
	Albums  []AlbumDTO       `json:"albums"`
	HasMore bool             `json:"has_more"`
}

type MediaStream struct {
	StatusCode int
	Headers    http.Header
	Body       io.ReadCloser
}
