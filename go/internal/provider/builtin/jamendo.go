package builtin

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
)

type JamendoMusicClient struct {
	adapter *provider.TransportAdapter
	opts    Options
}

func NewJamendoMusicClient(opts Options) *JamendoMusicClient {
	return &JamendoMusicClient{
		adapter: provider.NewTransportAdapter(opts.Pool),
		opts:    opts,
	}
}

func (c *JamendoMusicClient) ID() string         { return "JamendoMusicClient" }
func (c *JamendoMusicClient) EngineName() string { return "JamendoMusicClient" }
func (c *JamendoMusicClient) Capabilities() model.Capability {
	return model.Capability{
		Tier:     "partial",
		Note:     "开放许可独立音乐；需在音源设置中填入免费申请的 Jamendo client_id",
		Search:   true,
		Playlist: true,
	}
}

func (c *JamendoMusicClient) requireClientID(ctx context.Context) (string, error) {
	if cid := sourceCookieOrSetting(ctx, c.EngineName(), "client_id"); cid != "" {
		return cid, nil
	}
	rc := provider.RequestContextFrom(ctx)
	for _, key := range []string{c.EngineName(), c.ID(), strings.ToLower(c.EngineName()), strings.ToLower(c.ID()), "jamendo"} {
		if setting, ok := rc.Config.Runtime.SourceSettings[key]; ok {
			raw := strings.TrimSpace(setting.Cookie)
			if raw != "" && raw != config.HiddenPlaceholder && !strings.ContainsAny(raw, "=;") {
				return raw, nil
			}
		}
	}
	return "", model.NewError(
		model.ErrAuthRequired,
		http.StatusUnauthorized,
		"Jamendo 未配置 client_id：请前往音源设置填入免费申请的 Jamendo client_id（如 client_id=你的应用ID 或直接填写 ID）",
	)
}

type jamendoTrack struct {
	ID            any    `json:"id"`
	Name          string `json:"name"`
	ArtistName    string `json:"artist_name"`
	AlbumName     string `json:"album_name"`
	Duration      any    `json:"duration"`
	AlbumImage    string `json:"album_image"`
	Audio         string `json:"audio"`
	AudioDownload string `json:"audiodownload"`
}

func (c *JamendoMusicClient) rowToTrack(r jamendoTrack, idx *int) model.ProviderTrack {
	return newCandidateTrack(c.ID(), c.EngineName(), fmtAnyID(r.ID), r.Name, r.ArtistName, r.AlbumName, provider.ParseDurationSeconds(r.Duration), r.AlbumImage, idx)
}

func (c *JamendoMusicClient) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if strings.TrimSpace(opts.Keyword) == "" {
		return model.SearchSummary{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索关键词不能为空")
	}
	clientID, err := c.requireClientID(ctx)
	if err != nil {
		return model.SearchSummary{}, err
	}
	apiURL := endpointOr(c.opts, "jamendo_tracks", "https://api.jamendo.com/v3.0/tracks/")
	params := url.Values{
		"client_id":   {clientID},
		"format":      {"json"},
		"limit":       {strconv.Itoa(max(5, targetLimit(ctx, opts)))},
		"namesearch":  {opts.Keyword},
		"audioformat": {"mp32"},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, apiURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return model.SearchSummary{}, err
	}
	var parsed struct {
		Headers struct {
			Status       string `json:"status"`
			ErrorMessage string `json:"error_message"`
		} `json:"headers"`
		Results []jamendoTrack `json:"results"`
	}
	if err := provider.ParseSafeJSON(body, &parsed); err != nil {
		return model.SearchSummary{}, err
	}
	if parsed.Headers.Status == "failed" {
		return model.SearchSummary{}, model.NewError(model.ErrAuthRequired, http.StatusUnauthorized, "Jamendo API client_id 无效或不可用："+parsed.Headers.ErrorMessage)
	}
	rows := make([]model.ProviderTrack, 0, len(parsed.Results))
	for _, r := range parsed.Results {
		if fmtAnyID(r.ID) != "" {
			rows = append(rows, c.rowToTrack(r, nil))
		}
	}
	return emitSearchResults(ctx, opts, rows, emit)
}

func (c *JamendoMusicClient) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	sid := strings.TrimSpace(track.SourceID)
	if sid == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少 Jamendo 曲目标识")
	}
	clientID, err := c.requireClientID(ctx)
	if err != nil {
		return nil, err
	}
	apiURL := endpointOr(c.opts, "jamendo_tracks", "https://api.jamendo.com/v3.0/tracks/")
	params := url.Values{
		"client_id":   {clientID},
		"format":      {"json"},
		"id":          {sid},
		"audioformat": {"mp32"},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, apiURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return nil, err
	}
	var parsed struct {
		Headers struct {
			Status       string `json:"status"`
			ErrorMessage string `json:"error_message"`
		} `json:"headers"`
		Results []jamendoTrack `json:"results"`
	}
	if err := provider.ParseSafeJSON(body, &parsed); err != nil {
		return nil, err
	}
	if parsed.Headers.Status == "failed" {
		return nil, model.NewError(model.ErrAuthRequired, http.StatusUnauthorized, "Jamendo API client_id 无效或不可用："+parsed.Headers.ErrorMessage)
	}
	var cands []mediaCandidate
	for _, r := range parsed.Results {
		if u := firstNonEmpty(r.AudioDownload, r.Audio); u != "" {
			cands = append(cands, mediaCandidate{URL: u, Codec: "mp3", Bitrate: 320, UpstreamID: sid})
		}
	}
	return probeCandidates(ctx, c.adapter, c.EngineName(), track, policy, cands, 0)
}

var jamendoPlaylistRe = regexp.MustCompile(`playlist/(\d+)`)

func (c *JamendoMusicClient) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	m := jamendoPlaylistRe.FindStringSubmatch(locator)
	if len(m) < 2 {
		return model.PlaylistSummary{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Jamendo 歌单链接格式无效")
	}
	clientID, err := c.requireClientID(ctx)
	if err != nil {
		return model.PlaylistSummary{}, err
	}
	pid := m[1]
	plURL := endpointOr(c.opts, "jamendo_playlist", "https://api.jamendo.com/v3.0/playlists/tracks/")
	params := url.Values{
		"client_id": {clientID},
		"format":    {"json"},
		"id":        {pid},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, plURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return model.PlaylistSummary{}, err
	}
	var parsed struct {
		Headers struct {
			Status       string `json:"status"`
			ErrorMessage string `json:"error_message"`
		} `json:"headers"`
		Results []struct {
			Name   string         `json:"name"`
			Tracks []jamendoTrack `json:"tracks"`
		} `json:"results"`
	}
	if err := provider.ParseSafeJSON(body, &parsed); err != nil {
		return model.PlaylistSummary{}, err
	}
	if parsed.Headers.Status == "failed" {
		return model.PlaylistSummary{}, model.NewError(model.ErrAuthRequired, http.StatusUnauthorized, "Jamendo API client_id 无效或不可用："+parsed.Headers.ErrorMessage)
	}
	if len(parsed.Results) == 0 || len(parsed.Results[0].Tracks) == 0 {
		return model.PlaylistSummary{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "Jamendo 歌单为空或无法访问")
	}
	count := 0
	for i, t := range parsed.Results[0].Tracks {
		idx := i + 1
		if err := emit(c.rowToTrack(t, &idx)); err != nil {
			return model.PlaylistSummary{ID: pid, Name: parsed.Results[0].Name, Count: count}, err
		}
		count++
	}
	return model.PlaylistSummary{ID: pid, Name: provider.CleanText(parsed.Results[0].Name), Count: count}, nil
}
