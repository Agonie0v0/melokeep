package builtin

import (
	"context"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
)

type OpenGameArtMusicClient struct {
	adapter *provider.TransportAdapter
	opts    Options
}

func NewOpenGameArtMusicClient(opts Options) *OpenGameArtMusicClient {
	return &OpenGameArtMusicClient{
		adapter: provider.NewTransportAdapter(opts.Pool),
		opts:    opts,
	}
}

func (c *OpenGameArtMusicClient) ID() string         { return "OpenGameArtMusicClient" }
func (c *OpenGameArtMusicClient) EngineName() string { return "OpenGameArtMusicClient" }
func (c *OpenGameArtMusicClient) Capabilities() model.Capability {
	return model.Capability{
		Tier:     "partial",
		Note:     "支持 OpenGameArt 开放许可页面音频附件检索与解析",
		Search:   true,
		Playlist: false,
	}
}

var (
	ogaContentLinkRe = regexp.MustCompile(`class="art-preview-title"[^>]*>\s*<a[^>]+href="(/content/[^"]+)"`)
	ogaAudioExts     = map[string]bool{"mp3": true, "ogg": true, "wav": true, "flac": true, "m4a": true, "aac": true, "opus": true}
)

func (c *OpenGameArtMusicClient) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if strings.TrimSpace(opts.Keyword) == "" {
		return model.SearchSummary{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索关键词不能为空")
	}
	searchURL := endpointOr(c.opts, "opengameart_search", "https://opengameart.org/art-search-advanced")
	params := url.Values{
		"keys":               {opts.Keyword},
		"field_art_type_tid": {"12"},
		"page":               {"0"},
		"items_per_page":     {strconv.Itoa(max(24, targetLimit(ctx, opts)))},
	}
	// Keep field_art_type_tid[0]=12 literal as expected by Drupal Views.
	rawQuery := strings.ReplaceAll(params.Encode(), "field_art_type_tid=", "field_art_type_tid%5B0%5D=")
	_, _, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, searchURL+"?"+rawQuery, nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return model.SearchSummary{}, err
	}
	matches := ogaContentLinkRe.FindAllStringSubmatch(string(body), -1)
	seen := make(map[string]bool)
	var rows []model.ProviderTrack
	for _, m := range matches {
		if len(m) < 2 {
			continue
		}
		p := m[1]
		if seen[p] {
			continue
		}
		seen[p] = true
		title := strings.ReplaceAll(path.Base(p), "-", " ")
		rows = append(rows, newCandidateTrack(c.ID(), c.EngineName(), p, title, "OpenGameArt", "", 0, "", nil))
	}
	return emitSearchResults(ctx, opts, rows, emit)
}

func (c *OpenGameArtMusicClient) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	sid := strings.TrimSpace(track.SourceID)
	if sid == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少 OpenGameArt 条目标识")
	}
	siteBase := strings.TrimRight(endpointOr(c.opts, "opengameart_base", "https://opengameart.org"), "/")
	pageURL := sid
	if !strings.HasPrefix(pageURL, "http://") && !strings.HasPrefix(pageURL, "https://") {
		if !strings.HasPrefix(pageURL, "/") {
			pageURL = "/" + pageURL
		}
		pageURL = siteBase + pageURL
	}
	_, finalURL, _, body, err := c.adapter.DoBytes(ctx, c.EngineName(), http.MethodGet, pageURL, nil, provider.CallOptions{
		Idempotent: true,
	})
	if err != nil {
		return nil, err
	}
	if finalURL == "" {
		finalURL = pageURL
	}
	doc := parseHTML(string(body))
	var cands []mediaCandidate
	for _, node := range doc.FindAll(func(n *domNode) bool { return n.Attr("href") != "" || n.Attr("src") != "" }) {
		ref := firstNonEmpty(node.Attr("href"), node.Attr("src"))
		clean := strings.SplitN(strings.ToLower(ref), "?", 2)[0]
		ext := strings.TrimPrefix(path.Ext(clean), ".")
		if ogaAudioExts[ext] {
			cands = append(cands, mediaCandidate{
				URL:        resolveURL(finalURL, ref),
				Codec:      ext,
				UpstreamID: ext,
			})
		}
	}
	return probeCandidates(ctx, c.adapter, c.EngineName(), track, policy, cands, 0)
}

func (c *OpenGameArtMusicClient) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	return model.PlaylistSummary{}, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "OpenGameArt 暂不支持歌单导入")
}
