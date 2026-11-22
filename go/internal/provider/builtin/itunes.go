package builtin

import (
	"bytes"
	"context"
	"encoding/xml"
	"fmt"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"sync"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
)

type ITunesMusicClient struct {
	adapter *provider.TransportAdapter
	opts    Options
	mu      sync.RWMutex
	rowMemo map[string]itunesRow
}

func NewITunesMusicClient(opts Options) *ITunesMusicClient {
	return &ITunesMusicClient{
		adapter: provider.NewTransportAdapter(opts.Pool),
		opts:    opts,
		rowMemo: make(map[string]itunesRow),
	}
}

func (c *ITunesMusicClient) ID() string         { return "ITunesMusicClient" }
func (c *ITunesMusicClient) EngineName() string { return "ITunesMusicClient" }
func (c *ITunesMusicClient) Capabilities() model.Capability {
	return model.Capability{
		Tier:     "native",
		Note:     "支持公开播客节目解析与官方 30 秒曲目试听预览（仅试听）",
		Search:   true,
		Playlist: false,
		Episodes: true,
	}
}

type itunesRow struct {
	CollectionID    any    `json:"collectionId"`
	TrackID         any    `json:"trackId"`
	CollectionName  string `json:"collectionName"`
	TrackName       string `json:"trackName"`
	ArtistName      string `json:"artistName"`
	TrackTimeMillis any    `json:"trackTimeMillis"`
	ArtworkURL100   string `json:"artworkUrl100"`
	ArtworkURL600   string `json:"artworkUrl600"`
	FeedURL         string `json:"feedUrl"`
	PreviewURL      string `json:"previewUrl"`
}

func (c *ITunesMusicClient) rememberRow(id string, r itunesRow) {
	if id == "" {
		return
	}
	c.mu.Lock()
	if len(c.rowMemo) >= 512 {
		c.rowMemo = make(map[string]itunesRow)
	}
	c.rowMemo[id] = r
	c.mu.Unlock()
}

func (c *ITunesMusicClient) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if strings.TrimSpace(opts.Keyword) == "" {
		return model.SearchSummary{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索关键词不能为空")
	}
	searchURL := endpointOr(c.opts, "itunes_search", "https://itunes.apple.com/search")
	params := url.Values{
		"term":  {opts.Keyword},
		"media": {"all"},
		"limit": {strconv.Itoa(max(5, targetLimit(ctx, opts)))},
	}
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, searchURL+"?"+params.Encode(), nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return model.SearchSummary{}, err
	}
	var parsed struct {
		Results []itunesRow `json:"results"`
	}
	if err := provider.ParseSafeJSON(body, &parsed); err != nil {
		return model.SearchSummary{}, err
	}
	var rows []model.ProviderTrack
	for _, r := range parsed.Results {
		hasFeed := strings.TrimSpace(r.FeedURL) != ""
		hasPreview := strings.TrimSpace(r.PreviewURL) != ""
		if !hasFeed && !hasPreview {
			continue
		}
		var sid, title string
		if hasFeed {
			sid = firstNonEmpty(fmtAnyID(r.CollectionID), fmtAnyID(r.TrackID))
			title = firstNonEmpty(r.CollectionName, r.TrackName)
		} else {
			sid = firstNonEmpty(fmtAnyID(r.TrackID), fmtAnyID(r.CollectionID))
			title = firstNonEmpty(r.TrackName, r.CollectionName)
		}
		if sid == "" {
			continue
		}
		c.rememberRow(sid, r)
		dur := provider.ParseDurationSeconds(r.TrackTimeMillis) / 1000.0
		cover := firstNonEmpty(r.ArtworkURL600, r.ArtworkURL100)
		tr := newCandidateTrack(c.ID(), c.EngineName(), sid, title, r.ArtistName, r.CollectionName, dur, cover, nil)
		if hasFeed {
			tr.Episodes = -1
		}
		rows = append(rows, tr)
	}
	return emitSearchResults(ctx, opts, rows, emit)
}

type podcastRSS struct {
	Channel *struct {
		Items []struct {
			Title     string `xml:"title"`
			Duration  string `xml:"duration"`
			Enclosure *struct {
				URL  string `xml:"url,attr"`
				Type string `xml:"type,attr"`
			} `xml:"enclosure"`
		} `xml:"item"`
	} `xml:"channel"`
}

const maxPodcastRSSBytes = 16 * 1024 * 1024

func splitEpisodeLocator(sourceID string) (string, int) {
	sid := strings.TrimSpace(sourceID)
	base, rawEp, hasEp := strings.Cut(sid, "#ep:")
	if !hasEp {
		return sid, 0
	}
	epIdx, err := strconv.Atoi(strings.TrimSpace(rawEp))
	if err != nil || epIdx <= 0 {
		return strings.TrimSpace(base), 0
	}
	return strings.TrimSpace(base), epIdx
}

func (c *ITunesMusicClient) lookupRow(ctx context.Context, sid string) itunesRow {
	c.mu.RLock()
	row, ok := c.rowMemo[sid]
	c.mu.RUnlock()
	if ok {
		return row
	}
	lookupURL := endpointOr(c.opts, "itunes_lookup", "https://itunes.apple.com/lookup")
	if _, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, lookupURL+"?id="+url.QueryEscape(sid), nil, provider.CallOptions{Idempotent: true}); err == nil {
		var parsed struct {
			Results []itunesRow `json:"results"`
		}
		if provider.ParseSafeJSON(body, &parsed) == nil && len(parsed.Results) > 0 {
			row = parsed.Results[0]
			c.rememberRow(sid, row)
		}
	}
	return row
}

func (c *ITunesMusicClient) fetchFeedCandidates(ctx context.Context, feedURL string) (string, podcastRSS, error) {
	_, finalURL, _, rssBody, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, feedURL, nil, provider.CallOptions{
		Idempotent:       true,
		MaxResponseBytes: maxPodcastRSSBytes,
	})
	if err != nil {
		return "", podcastRSS{}, err
	}
	if len(rssBody) > maxPodcastRSSBytes || hasXMLUnsafeDecl(rssBody) {
		return "", podcastRSS{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "播客 RSS 超过大小限制或包含不支持的 XML 声明")
	}
	var rss podcastRSS
	if err := xml.NewDecoder(bytes.NewReader(rssBody)).Decode(&rss); err != nil {
		return "", podcastRSS{}, model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "播客 RSS XML 解析失败", err)
	}
	if rss.Channel == nil {
		return "", podcastRSS{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "播客 RSS 缺少 channel")
	}
	return firstNonEmpty(finalURL, feedURL), rss, nil
}

func enclosureExt(eu, encType string) string {
	ext := strings.ToLower(strings.TrimPrefix(path.Ext(strings.SplitN(eu, "?", 2)[0]), "."))
	if ext != "" {
		return ext
	}
	switch strings.ToLower(encType) {
	case "audio/mp4":
		return "m4a"
	case "audio/ogg":
		return "ogg"
	case "audio/wav":
		return "wav"
	default:
		return "mp3"
	}
}

// Episodes expands a podcast feed candidate into individual playable episode tracks.
func (c *ITunesMusicClient) Episodes(ctx context.Context, track model.ProviderTrack) ([]model.ProviderTrack, error) {
	sid, _ := splitEpisodeLocator(track.SourceID)
	if sid == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少 iTunes 曲目标识")
	}
	row := c.lookupRow(ctx, sid)
	if strings.TrimSpace(row.FeedURL) == "" {
		return nil, nil
	}
	_, rss, err := c.fetchFeedCandidates(ctx, row.FeedURL)
	if err != nil {
		return nil, err
	}
	artist := firstNonEmpty(row.ArtistName, track.Artist)
	album := firstNonEmpty(row.CollectionName, track.Album, track.Title)
	cover := firstNonEmpty(row.ArtworkURL600, row.ArtworkURL100)
	if cover == "" && track.Cover != nil {
		cover = *track.Cover
	}
	var episodes []model.ProviderTrack
	idx := 0
	for _, item := range rss.Channel.Items {
		if item.Enclosure == nil || strings.TrimSpace(item.Enclosure.URL) == "" {
			continue
		}
		idx++
		epNum := idx
		title := firstNonEmpty(provider.CleanText(item.Title), fmt.Sprintf("Episode %d", epNum))
		dur := provider.ParseDurationSeconds(item.Duration)
		epID := fmt.Sprintf("%s#ep:%d", sid, epNum)
		episodes = append(episodes, newCandidateTrack(c.ID(), c.EngineName(), epID, title, artist, album, dur, cover, &epNum))
		if len(episodes) >= 200 {
			break
		}
	}
	return episodes, nil
}

func (c *ITunesMusicClient) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	sid, targetEp := splitEpisodeLocator(track.SourceID)
	if sid == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少 iTunes 曲目标识")
	}
	row := c.lookupRow(ctx, sid)

	if strings.TrimSpace(row.FeedURL) != "" {
		base, rss, err := c.fetchFeedCandidates(ctx, row.FeedURL)
		if err != nil {
			return nil, err
		}
		var cands []mediaCandidate
		epNum := 0
		for idx, item := range rss.Channel.Items {
			if item.Enclosure == nil || strings.TrimSpace(item.Enclosure.URL) == "" {
				continue
			}
			epNum++
			if targetEp > 0 && epNum != targetEp {
				continue
			}
			eu := resolveURL(base, item.Enclosure.URL)
			ext := enclosureExt(eu, item.Enclosure.Type)
			cands = append(cands, mediaCandidate{URL: eu, Codec: ext, UpstreamID: strconv.Itoa(idx)})
			if targetEp > 0 || len(cands) >= 3 {
				break
			}
		}
		if len(cands) == 0 {
			return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "播客 RSS 没有公开的音频附件")
		}
		return probeCandidates(ctx, c.adapter, c.EngineName(), track, policy, cands, 0)
	}

	if strings.TrimSpace(row.PreviewURL) == "" {
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "iTunes 未返回可用音频地址")
	}
	return probeCandidates(ctx, c.adapter, c.EngineName(), track, policy, []mediaCandidate{
		{
			URL:         row.PreviewURL,
			Codec:       "m4a",
			Bitrate:     256,
			PreviewOnly: true,
			Label:       "M4A 256k (30秒官方试听)",
			UpstreamID:  "preview",
		},
	}, 0)
}

func (c *ITunesMusicClient) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	return model.PlaylistSummary{}, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "iTunes 暂不支持歌单导入")
}
