package builtin

import (
	"context"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
)

type WikimediaCommonsMusicClient struct {
	adapter *provider.TransportAdapter
	opts    Options
}

func NewWikimediaCommonsMusicClient(opts Options) *WikimediaCommonsMusicClient {
	return &WikimediaCommonsMusicClient{
		adapter: provider.NewTransportAdapter(opts.Pool),
		opts:    opts,
	}
}

func (c *WikimediaCommonsMusicClient) ID() string         { return "WikimediaCommonsMusicClient" }
func (c *WikimediaCommonsMusicClient) EngineName() string { return "WikimediaCommonsMusicClient" }
func (c *WikimediaCommonsMusicClient) Capabilities() model.Capability {
	return model.Capability{
		Tier:     "native",
		Note:     "Wikimedia Commons 公共领域与开放许可音频",
		Search:   true,
		Playlist: false,
	}
}

type wikimediaPage struct {
	PageID    any    `json:"pageid"`
	Title     string `json:"title"`
	ImageInfo []struct {
		URL  string `json:"url"`
		Size int64  `json:"size"`
		Mime string `json:"mime"`
	} `json:"imageinfo"`
}

func (c *WikimediaCommonsMusicClient) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if strings.TrimSpace(opts.Keyword) == "" {
		return model.SearchSummary{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索关键词不能为空")
	}
	apiURL := endpointOr(c.opts, "wikimedia_api", "https://commons.wikimedia.org/w/api.php")
	params := url.Values{
		"action":       {"query"},
		"format":       {"json"},
		"generator":    {"search"},
		"gsrnamespace": {"6"},
		"gsrsearch":    {opts.Keyword + " filetype:audio"},
		"gsrlimit":     {strconv.Itoa(max(5, targetLimit(ctx, opts)))},
		"prop":         {"imageinfo"},
		"iiprop":       {"url|size|mime"},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, apiURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return model.SearchSummary{}, err
	}
	var parsed struct {
		Query struct {
			Pages map[string]wikimediaPage `json:"pages"`
		} `json:"query"`
	}
	if err := provider.ParseSafeJSON(body, &parsed); err != nil {
		return model.SearchSummary{}, err
	}
	var rows []model.ProviderTrack
	for _, p := range parsed.Query.Pages {
		if len(p.ImageInfo) == 0 || p.ImageInfo[0].URL == "" {
			continue
		}
		sid := fmtAnyID(p.PageID)
		title := strings.TrimPrefix(p.Title, "File:")
		tr := newCandidateTrack(c.ID(), c.EngineName(), sid, title, "Wikimedia Commons", "", 0, "", nil)
		if p.ImageInfo[0].Size > 0 {
			sz := p.ImageInfo[0].Size
			tr.SizeBytes = &sz
		}
		rows = append(rows, tr)
	}
	return emitSearchResults(ctx, opts, rows, emit)
}

func (c *WikimediaCommonsMusicClient) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	sid := strings.TrimSpace(track.SourceID)
	if sid == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少 Wikimedia Commons 页面标识")
	}
	apiURL := endpointOr(c.opts, "wikimedia_api", "https://commons.wikimedia.org/w/api.php")
	params := url.Values{
		"action":  {"query"},
		"format":  {"json"},
		"pageids": {sid},
		"prop":    {"imageinfo"},
		"iiprop":  {"url|size|mime"},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, apiURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return nil, err
	}
	var parsed struct {
		Query struct {
			Pages map[string]wikimediaPage `json:"pages"`
		} `json:"query"`
	}
	if err := provider.ParseSafeJSON(body, &parsed); err != nil {
		return nil, err
	}
	var cands []mediaCandidate
	for _, p := range parsed.Query.Pages {
		if len(p.ImageInfo) > 0 && p.ImageInfo[0].URL != "" {
			u := p.ImageInfo[0].URL
			ext := strings.ToLower(strings.TrimPrefix(path.Ext(strings.SplitN(u, "?", 2)[0]), "."))
			cands = append(cands, mediaCandidate{URL: u, Codec: ext, UpstreamID: sid})
		}
	}
	return probeCandidates(ctx, c.adapter, c.EngineName(), track, policy, cands, 0)
}

func (c *WikimediaCommonsMusicClient) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	return model.PlaylistSummary{}, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "Wikimedia Commons 不支持歌单导入")
}
