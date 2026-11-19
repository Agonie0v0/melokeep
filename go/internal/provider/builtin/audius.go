package builtin

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
)

type AudiusMusicClient struct {
	adapter *provider.TransportAdapter
	opts    Options
}

func NewAudiusMusicClient(opts Options) *AudiusMusicClient {
	return &AudiusMusicClient{
		adapter: provider.NewTransportAdapter(opts.Pool),
		opts:    opts,
	}
}

func (c *AudiusMusicClient) ID() string         { return "AudiusMusicClient" }
func (c *AudiusMusicClient) EngineName() string { return "AudiusMusicClient" }
func (c *AudiusMusicClient) Capabilities() model.Capability {
	return model.Capability{
		Tier:     "native",
		Note:     "Audius 开放许可与独立音乐流媒体",
		Search:   true,
		Playlist: false,
	}
}

func (c *AudiusMusicClient) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if strings.TrimSpace(opts.Keyword) == "" {
		return model.SearchSummary{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索关键词不能为空")
	}
	searchURL := endpointOr(c.opts, "audius_search", "https://discoveryprovider.audius.co/v1/tracks/search")
	params := url.Values{
		"query":    {opts.Keyword},
		"app_name": {"MeloKeep"},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, searchURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return model.SearchSummary{}, err
	}
	var parsed struct {
		Data []struct {
			ID       any    `json:"id"`
			Title    string `json:"title"`
			Duration any    `json:"duration"`
			User     struct {
				Name string `json:"name"`
			} `json:"user"`
			Artwork map[string]string `json:"artwork"`
		} `json:"data"`
	}
	if err := provider.ParseSafeJSON(body, &parsed); err != nil {
		return model.SearchSummary{}, err
	}
	var rows []model.ProviderTrack
	for _, r := range parsed.Data {
		sid := fmtAnyID(r.ID)
		if sid == "" {
			continue
		}
		rows = append(rows, newCandidateTrack(c.ID(), c.EngineName(), sid, r.Title, r.User.Name, "", provider.ParseDurationSeconds(r.Duration), r.Artwork["480x480"], nil))
	}
	return emitSearchResults(ctx, opts, rows, emit)
}

func (c *AudiusMusicClient) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	sid := strings.TrimSpace(track.SourceID)
	if sid == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少 Audius 曲目标识")
	}
	trackBase := strings.TrimRight(endpointOr(c.opts, "audius_track_base", "https://discoveryprovider.audius.co/v1/tracks"), "/")
	streamURL := fmt.Sprintf("%s/%s/stream?app_name=MeloKeep", trackBase, url.PathEscape(sid))
	return probeCandidates(ctx, c.adapter, c.EngineName(), track, policy, []mediaCandidate{
		{URL: streamURL, Codec: "mp3", Bitrate: 320, UpstreamID: sid},
	}, 0)
}

func (c *AudiusMusicClient) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	return model.PlaylistSummary{}, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "Audius 暂不支持歌单导入")
}
