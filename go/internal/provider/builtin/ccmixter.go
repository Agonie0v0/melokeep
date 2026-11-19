package builtin

import (
	"bytes"
	"context"
	"encoding/xml"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
)

type CCMixterMusicClient struct {
	adapter *provider.TransportAdapter
	opts    Options
	mu      sync.RWMutex
	urlMemo map[string]string
}

func NewCCMixterMusicClient(opts Options) *CCMixterMusicClient {
	return &CCMixterMusicClient{
		adapter: provider.NewTransportAdapter(opts.Pool),
		opts:    opts,
		urlMemo: make(map[string]string),
	}
}

func (c *CCMixterMusicClient) ID() string         { return "CCMixterMusicClient" }
func (c *CCMixterMusicClient) EngineName() string { return "CCMixterMusicClient" }
func (c *CCMixterMusicClient) Capabilities() model.Capability {
	return model.Capability{
		Tier:     "partial",
		Note:     "支持 ccMixter XSPF 开放许可搜索与音频解析",
		Search:   true,
		Playlist: false,
	}
}

type xspfPlaylist struct {
	TrackList struct {
		Tracks []struct {
			Location   string `xml:"location"`
			Identifier string `xml:"identifier"`
			Title      string `xml:"title"`
			Creator    string `xml:"creator"`
			Album      string `xml:"album"`
			Image      string `xml:"image"`
			Duration   string `xml:"duration"`
		} `xml:"track"`
	} `xml:"trackList"`
}

func (c *CCMixterMusicClient) rememberURL(id, u string) {
	if id == "" || u == "" {
		return
	}
	c.mu.Lock()
	if len(c.urlMemo) >= 512 {
		c.urlMemo = make(map[string]string)
	}
	c.urlMemo[id] = u
	c.mu.Unlock()
}

func (c *CCMixterMusicClient) parseXSPF(raw []byte) ([]model.ProviderTrack, error) {
	if hasXMLUnsafeDecl(raw) {
		return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "XSPF 包含不支持的 XML 声明")
	}
	var pl xspfPlaylist
	if err := xml.NewDecoder(bytes.NewReader(raw)).Decode(&pl); err != nil {
		return nil, model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "ccMixter XSPF 解析失败", err)
	}
	var rows []model.ProviderTrack
	for _, t := range pl.TrackList.Tracks {
		loc := strings.TrimSpace(t.Location)
		if !strings.HasPrefix(loc, "http://") && !strings.HasPrefix(loc, "https://") {
			continue
		}
		id := firstNonEmpty(t.Identifier, loc)
		c.rememberURL(id, loc)
		dur := provider.ParseDurationSeconds(t.Duration) / 1000.0
		rows = append(rows, newCandidateTrack(c.ID(), c.EngineName(), id, t.Title, t.Creator, t.Album, dur, t.Image, nil))
	}
	return rows, nil
}

func (c *CCMixterMusicClient) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if strings.TrimSpace(opts.Keyword) == "" {
		return model.SearchSummary{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索关键词不能为空")
	}
	apiURL := endpointOr(c.opts, "ccmixter_query", "https://ccmixter.org/api/query")
	params := url.Values{
		"search":      {opts.Keyword},
		"t":           {"search_uploads"},
		"search_type": {"any"},
		"f":           {"xspf"},
		"limit":       {strconv.Itoa(max(5, targetLimit(ctx, opts)))},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, apiURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return model.SearchSummary{}, err
	}
	rows, err := c.parseXSPF(body)
	if err != nil {
		return model.SearchSummary{}, err
	}
	return emitSearchResults(ctx, opts, rows, emit)
}

func (c *CCMixterMusicClient) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	sid := strings.TrimSpace(track.SourceID)
	if sid == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少 ccMixter 曲目标识")
	}
	mediaURL := ""
	if strings.HasPrefix(sid, "http://") || strings.HasPrefix(sid, "https://") {
		mediaURL = sid
	} else {
		c.mu.RLock()
		mediaURL = c.urlMemo[sid]
		c.mu.RUnlock()
	}
	if mediaURL == "" {
		apiURL := endpointOr(c.opts, "ccmixter_query", "https://ccmixter.org/api/query")
		params := url.Values{"ids": {sid}, "f": {"xspf"}}
		if _, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, apiURL+"?"+params.Encode(), nil, provider.CallOptions{Idempotent: true}); err == nil {
			if rows, perr := c.parseXSPF(body); perr == nil && len(rows) > 0 {
				c.mu.RLock()
				mediaURL = c.urlMemo[rows[0].SourceID]
				c.mu.RUnlock()
			}
		}
	}
	if mediaURL == "" {
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "ccMixter 未找到可用音频地址")
	}
	return probeCandidates(ctx, c.adapter, c.EngineName(), track, policy, []mediaCandidate{
		{URL: mediaURL, Codec: "mp3", UpstreamID: sid},
	}, 0)
}

func (c *CCMixterMusicClient) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	return model.PlaylistSummary{}, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "ccMixter 暂不支持歌单导入")
}
