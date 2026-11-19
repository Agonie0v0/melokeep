package builtin

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
)

type FMAMusicClient struct {
	adapter *provider.TransportAdapter
	opts    Options
	mu      sync.RWMutex
	urlMemo map[string]string
}

func NewFMAMusicClient(opts Options) *FMAMusicClient {
	return &FMAMusicClient{
		adapter: provider.NewTransportAdapter(opts.Pool),
		opts:    opts,
		urlMemo: make(map[string]string),
	}
}

func (c *FMAMusicClient) ID() string         { return "FMAMusicClient" }
func (c *FMAMusicClient) EngineName() string { return "FMAMusicClient" }
func (c *FMAMusicClient) Capabilities() model.Capability {
	return model.Capability{
		Tier:     "partial",
		Note:     "支持 Free Music Archive 站内搜索与开放许可音频解析",
		Search:   true,
		Playlist: false,
	}
}

func (c *FMAMusicClient) rememberURL(id, mediaURL string) {
	if id == "" || mediaURL == "" {
		return
	}
	c.mu.Lock()
	if len(c.urlMemo) >= 512 {
		c.urlMemo = make(map[string]string)
	}
	c.urlMemo[id] = mediaURL
	c.mu.Unlock()
}

func (c *FMAMusicClient) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if strings.TrimSpace(opts.Keyword) == "" {
		return model.SearchSummary{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索关键词不能为空")
	}
	searchURL := endpointOr(c.opts, "fma_search", "https://freemusicarchive.org/search/")
	params := url.Values{
		"quicksearch": {opts.Keyword},
		"pageSize":    {strconv.Itoa(max(5, targetLimit(ctx, opts)))},
		"page":        {"1"},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, searchURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return model.SearchSummary{}, err
	}
	doc := parseHTML(string(body))
	var rows []model.ProviderTrack
	for _, node := range doc.FindAll(func(n *domNode) bool { return n.Attr("data-track-info") != "" }) {
		var info struct {
			ID          any    `json:"id"`
			TrackID     any    `json:"track_id"`
			Title       string `json:"title"`
			ArtistName  string `json:"artistName"`
			AlbumTitle  string `json:"albumTitle"`
			FileURL     string `json:"fileUrl"`
			PlaybackURL string `json:"playbackUrl"`
			DownloadURL string `json:"downloadUrl"`
		}
		if json.Unmarshal([]byte(node.Attr("data-track-info")), &info) != nil {
			continue
		}
		sid := firstNonEmpty(fmtAnyID(info.TrackID), fmtAnyID(info.ID))
		if sid == "" {
			continue
		}
		mediaURL := firstNonEmpty(info.FileURL, info.PlaybackURL, info.DownloadURL)
		c.rememberURL(sid, mediaURL)
		rows = append(rows, newCandidateTrack(c.ID(), c.EngineName(), sid, info.Title, info.ArtistName, info.AlbumTitle, 0, "", nil))
	}
	return emitSearchResults(ctx, opts, rows, emit)
}

func (c *FMAMusicClient) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	sid := strings.TrimSpace(track.SourceID)
	if sid == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少 FMA 曲目标识")
	}
	c.mu.RLock()
	mediaURL := c.urlMemo[sid]
	c.mu.RUnlock()
	if mediaURL == "" && track.Title != "" {
		searchURL := endpointOr(c.opts, "fma_search", "https://freemusicarchive.org/search/")
		params := url.Values{"quicksearch": {track.Title}, "pageSize": {"10"}, "page": {"1"}}
		if _, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, searchURL+"?"+params.Encode(), nil, provider.CallOptions{Idempotent: true}); err == nil {
			doc := parseHTML(string(body))
			for _, node := range doc.FindAll(func(n *domNode) bool { return n.Attr("data-track-info") != "" }) {
				var info struct {
					ID          any    `json:"id"`
					TrackID     any    `json:"track_id"`
					FileURL     string `json:"fileUrl"`
					PlaybackURL string `json:"playbackUrl"`
					DownloadURL string `json:"downloadUrl"`
				}
				if json.Unmarshal([]byte(node.Attr("data-track-info")), &info) == nil {
					if fmtAnyID(info.TrackID) == sid || fmtAnyID(info.ID) == sid {
						mediaURL = firstNonEmpty(info.FileURL, info.PlaybackURL, info.DownloadURL)
						break
					}
				}
			}
		}
	}
	if mediaURL == "" {
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "FMA 未找到公开音频地址")
	}
	return probeCandidates(ctx, c.adapter, c.EngineName(), track, policy, []mediaCandidate{
		{URL: mediaURL, Codec: "mp3", UpstreamID: sid},
	}, 0)
}

func (c *FMAMusicClient) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	return model.PlaylistSummary{}, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "FMA 暂不支持歌单导入")
}
