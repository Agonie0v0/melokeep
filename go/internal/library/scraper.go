package library

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/transport"
)

var (
	versionPatterns = map[string]*regexp.Regexp{
		"live":         regexp.MustCompile(`(?i)\blive\b|现场|演唱会`),
		"remix":        regexp.MustCompile(`(?i)\bremix\b|混音`),
		"instrumental": regexp.MustCompile(`(?i)\binstrumental\b|伴奏|纯音乐`),
		"cover":        regexp.MustCompile(`(?i)\bcover\b|翻唱`),
		"acoustic":     regexp.MustCompile(`(?i)\bacoustic\b|不插电`),
	}
	mbUUIDRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
)

type ScrapeCandidate struct {
	ID               string            `json:"id"`
	Source           string            `json:"source"`
	SourceID         string            `json:"source_id"`
	ReleaseID        string            `json:"release_id,omitempty"`
	Title            string            `json:"title"`
	Artist           string            `json:"artist"`
	Album            string            `json:"album"`
	AlbumArtist      string            `json:"album_artist,omitempty"`
	Date             string            `json:"date,omitempty"`
	TrackNumber      string            `json:"track_number,omitempty"`
	DiscNumber       string            `json:"disc_number,omitempty"`
	Duration         float64           `json:"duration"`
	Version          string            `json:"version,omitempty"`
	CoverURL         string            `json:"cover_url,omitempty"`
	Lyrics           string            `json:"lyrics,omitempty"`
	FingerprintScore float64              `json:"fingerprint_score,omitempty"`
	Score            float64              `json:"score"`
	Reasons          []string             `json:"reasons"`
	Conflicts        []string             `json:"conflicts"`
	Confident        bool                 `json:"confident"`
	FieldSources     map[string]string    `json:"field_sources,omitempty"`
	providerVariants []model.MediaVariant `json:"-"`
}

func (c ScrapeCandidate) ToDTO() model.MetadataCandidateDTO {
	return model.MetadataCandidateDTO{
		ID:           c.ID,
		Source:       c.Source,
		Title:        c.Title,
		Artist:       c.Artist,
		Album:        c.Album,
		Score:        c.Score,
		Reasons:      nonNilStrings(c.Reasons),
		Conflicts:    nonNilStrings(c.Conflicts),
		FieldSources: c.FieldSources,
	}
}

func (c ScrapeCandidate) FieldsMap() map[string]string {
	m := map[string]string{}
	if c.Title != "" {
		m["title"] = c.Title
	}
	if c.Artist != "" {
		m["artist"] = c.Artist
	}
	if c.Album != "" {
		m["album"] = c.Album
	}
	if c.AlbumArtist != "" {
		m["album_artist"] = c.AlbumArtist
	}
	if c.Date != "" {
		m["date"] = c.Date
	}
	if c.TrackNumber != "" {
		m["track_number"] = c.TrackNumber
	}
	if c.DiscNumber != "" {
		m["disc_number"] = c.DiscNumber
	}
	return m
}

type ScrapeReference struct {
	Source   string
	SourceID string
	Title    string
	Artist   string
	Album    string
	Duration float64
	Version  string
	Path     string
	Query    string
}

func builtinSourceCatalog() []model.MetadataSourceDTO {
	return []model.MetadataSourceDTO{
		{
			ID: "musicbrainz", Label: "MusicBrainz", Description: "国际音乐与不同发行版本的结构化资料",
			Fields: []string{"title", "artist", "album", "album_artist", "date", "track_number", "disc_number", "cover"},
			Note:   "无需账号；封面来自 Cover Art Archive，不提供歌词。社区资料可能缺失，多个发行版本需人工确认。", URL: "https://musicbrainz.org/",
		},
		{
			ID: "itunes", Label: "iTunes", Description: "国际与中文发行专辑结构化资料与高清封面",
			Fields: []string{"title", "artist", "album", "date", "track_number", "disc_number", "cover"},
			Note:   "无需账号；提供官方发行曲序与高分辨率封面，不提供歌词。", URL: "https://itunes.apple.com/",
		},
		{
			ID: "acoustid", Label: "AcoustID 指纹", Description: "通过音频内容识别，适合文件名和标签缺失的歌曲",
			Fields: []string{"title", "artist", "album", "album_artist", "date", "track_number", "disc_number", "cover"},
			Note:   "需要 fpcalc 和 AcoustID 应用 Key；指纹查询返回 MusicBrainz 录音，发行版本仍需审核。不上传音频。", URL: "https://acoustid.org/",
		},
		{
			ID: "lrclib", Label: "LRCLIB", Description: "开源免费同步与纯文本歌词库",
			Fields: []string{"lyrics"},
			Note:   "无需账号与 Key；优先按歌名、歌手、专辑与时长匹配 LRC 同步歌词。", URL: "https://lrclib.net/",
		},
	}
}

// SourceCatalog returns the built-in compliant metadata source catalog.
func SourceCatalog() []model.MetadataSourceDTO {
	return builtinSourceCatalog()
}

func dynamicPluginFields(caps model.Capability) []string {
	var fields []string
	if caps.Metadata {
		fields = append(fields, "title", "artist", "album", "album_artist", "date", "track_number", "disc_number", "cover")
	} else if caps.Search {
		fields = append(fields, "title", "artist", "album", "cover")
	}
	if caps.Lyrics {
		fields = append(fields, "lyrics")
	}
	if len(fields) == 0 {
		return []string{}
	}
	return fields
}

// SourceCatalog returns the built-in metadata sources together with enabled plugin sources.
func (s *Scraper) SourceCatalog() []model.MetadataSourceDTO {
	out := builtinSourceCatalog()
	if s == nil || s.providers == nil {
		return out
	}
	seen := make(map[string]bool, len(out))
	for _, item := range out {
		seen[item.ID] = true
	}
	for _, entry := range s.providers.PluginEntries() {
		if entry.Provider == nil {
			continue
		}
		caps := entry.Provider.Capabilities()
		if !caps.Search && !caps.Metadata && !caps.Lyrics {
			continue
		}
		id := strings.TrimSpace(entry.Provider.ID())
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		label := strings.TrimSpace(entry.Meta.Label)
		if label == "" {
			label = id
		}
		out = append(out, model.MetadataSourceDTO{
			ID:          id,
			Label:       label,
			Description: strings.TrimSpace(entry.Meta.Description),
			Fields:      dynamicPluginFields(caps),
			Note:        strings.TrimSpace(caps.Note),
		})
	}
	return out
}

// ValidSourceID reports whether id is a built-in metadata source or an enabled metadata-capable plugin.
func (s *Scraper) ValidSourceID(id string) bool {
	id = strings.TrimSpace(id)
	if id == "" {
		return false
	}
	for _, src := range s.SourceCatalog() {
		if src.ID == id {
			return true
		}
	}
	return false
}

// SourceLabel returns the display label for id, or an empty string if unknown.
func (s *Scraper) SourceLabel(id string) string {
	id = strings.TrimSpace(id)
	if id == "" {
		return ""
	}
	for _, src := range s.SourceCatalog() {
		if src.ID == id {
			return src.Label
		}
	}
	return ""
}

// SourceFields returns the set of metadata fields supported by id.
func (s *Scraper) SourceFields(id string) map[string]bool {
	id = strings.TrimSpace(id)
	out := make(map[string]bool)
	if id == "" {
		return out
	}
	for _, src := range s.SourceCatalog() {
		if src.ID == id {
			for _, f := range src.Fields {
				out[f] = true
			}
			return out
		}
	}
	return out
}

func CleanMetaValue(v string) string {
	s := strings.TrimSpace(v)
	switch strings.ToLower(s) {
	case "none", "null", "未知", "未知歌手", "未知专辑":
		return ""
	default:
		return s
	}
}

func NormalizedMeta(v string) string {
	s := CleanMetaValue(v)
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func VersionFlags(v string) map[string]bool {
	s := CleanMetaValue(v)
	flags := make(map[string]bool)
	for k, re := range versionPatterns {
		if re.MatchString(s) {
			flags[k] = true
		}
	}
	return flags
}

func equalBoolMap(a, b map[string]bool) bool {
	if len(a) != len(b) {
		return false
	}
	for k := range a {
		if !b[k] {
			return false
		}
	}
	return true
}

// similarityRatio computes SequenceMatcher-like similarity ratio (2*M/T) using LCS on runes.
func similarityRatio(a, b string) float64 {
	ra := []rune(a)
	rb := []rune(b)
	if len(ra) == 0 || len(rb) == 0 {
		return 0
	}
	if a == b {
		return 1.0
	}
	// DP LCS length
	dp := make([]int, len(rb)+1)
	for i := 1; i <= len(ra); i++ {
		prev := 0
		for j := 1; j <= len(rb); j++ {
			cur := dp[j]
			if ra[i-1] == rb[j-1] {
				dp[j] = prev + 1
			} else if dp[j-1] > dp[j] {
				dp[j] = dp[j-1]
			}
			prev = cur
		}
	}
	matches := dp[len(rb)]
	return float64(2*matches) / float64(len(ra)+len(rb))
}

func RankCandidate(ref ScrapeReference, cand ScrapeCandidate) ScrapeCandidate {
	var reasons, conflicts []string
	title := NormalizedMeta(ref.Title)
	other := NormalizedMeta(cand.Title)
	sim := 0.0
	if title != "" && other != "" {
		sim = similarityRatio(title, other)
	}
	score := sim * 45.0
	if title != "" && title == other {
		reasons = append(reasons, "标题一致")
	} else if sim < 0.85 {
		conflicts = append(conflicts, "标题不同")
	}

	artists := NormalizedMeta(ref.Artist)
	otherArtists := NormalizedMeta(cand.Artist)
	artistMatch := artists != "" && otherArtists != "" &&
		(artists == otherArtists || similarityRatio(artists, otherArtists) >= 0.9)
	if artistMatch {
		score += 30
		reasons = append(reasons, "歌手一致")
	} else if artists != "" {
		conflicts = append(conflicts, "歌手不同")
	}

	album := NormalizedMeta(ref.Album)
	otherAlbum := NormalizedMeta(cand.Album)
	if album != "" && album == otherAlbum {
		score += 15
		reasons = append(reasons, "专辑一致")
	} else if album != "" && otherAlbum != "" {
		conflicts = append(conflicts, "专辑不同")
	}

	if ref.Duration > 0 && cand.Duration > 0 {
		diff := math.Abs(ref.Duration - cand.Duration)
		if diff <= 3 {
			score += 10
			reasons = append(reasons, "时长接近")
		} else if diff > math.Max(8, ref.Duration*0.04) {
			conflicts = append(conflicts, "时长差异较大")
		}
	}

	if !equalBoolMap(VersionFlags(ref.Title+" "+ref.Version), VersionFlags(cand.Title+" "+cand.Version)) {
		conflicts = append(conflicts, "歌曲版本不同")
	}

	trusted := ref.Source == cand.Source && ref.SourceID != "" && ref.SourceID == cand.SourceID
	if trusted && len(conflicts) == 0 {
		score = 100
		reasons = append([]string{"下载来源 ID 一致"}, reasons...)
	}
	confident := len(conflicts) == 0 && (trusted || (sim >= 0.96 && artistMatch && score >= 85))

	if cand.Source == "acoustid" && cand.FingerprintScore >= 0.9 {
		var filtered []string
		for _, c := range conflicts {
			if c == "专辑不同" || c == "时长差异较大" {
				filtered = append(filtered, c)
			}
		}
		conflicts = filtered
		score = 98
		reasons = []string{"音频指纹高度匹配；发行版本需核对"}
		confident = len(conflicts) == 0
	}

	sum := sha256.Sum256([]byte(fmt.Sprintf("%s|%s|%s|%s", cand.Source, cand.SourceID, cand.ReleaseID, cand.Album)))
	cand.ID = hex.EncodeToString(sum[:])[:20]
	cand.Score = math.Round(score)
	cand.Reasons = nonNilStrings(reasons)
	cand.Conflicts = nonNilStrings(conflicts)
	cand.Confident = confident
	return cand
}

func PickCandidates(ref ScrapeReference, candidates []ScrapeCandidate) ([]ScrapeCandidate, *ScrapeCandidate) {
	ranked := make([]ScrapeCandidate, 0, len(candidates))
	for _, c := range candidates {
		ranked = append(ranked, RankCandidate(ref, c))
	}
	sort.SliceStable(ranked, func(i, j int) bool {
		return ranked[i].Score > ranked[j].Score
	})
	seen := make(map[string]bool, len(ranked))
	unique := make([]ScrapeCandidate, 0, len(ranked))
	for _, c := range ranked {
		if seen[c.ID] {
			continue
		}
		seen[c.ID] = true
		unique = append(unique, c)
	}
	if len(unique) > 12 {
		unique = unique[:12]
	}
	var chosen *ScrapeCandidate
	if len(unique) > 0 && unique[0].Confident {
		c0 := unique[0]
		chosen = &c0
		if len(unique) > 1 && unique[1].Confident && c0.Score-unique[1].Score < 5 {
			c1 := unique[1]
			if NormalizedMeta(c0.Album) != NormalizedMeta(c1.Album) ||
				(c0.ReleaseID != "" && c1.ReleaseID != "" && c0.ReleaseID != c1.ReleaseID) {
				chosen = nil
			}
		}
	}
	return unique, chosen
}

// --- Scraper Client ---

type ScraperOptions struct {
	MusicBrainzBaseURL string
	ITunesBaseURL      string
	CoverArtBaseURL    string
	AcoustIDURL        string
	LRCLIBBaseURL      string
	AcoustIDKey        string
	AcoustIDKeyFunc    func(ctx context.Context) string
	MBLimitInterval    time.Duration
}

type Scraper struct {
	pool      *transport.Pool
	providers *provider.Registry
	runner    *media.Runner
	opts      ScraperOptions

	mbMu   sync.Mutex
	mbNext time.Time
}

func NewScraper(pool *transport.Pool, providers *provider.Registry, runner *media.Runner, opts ScraperOptions) *Scraper {
	if pool == nil {
		pool = transport.NewPool()
	}
	if opts.MusicBrainzBaseURL == "" {
		opts.MusicBrainzBaseURL = "https://musicbrainz.org/ws/2"
	}
	if opts.ITunesBaseURL == "" {
		opts.ITunesBaseURL = "https://itunes.apple.com"
	}
	if opts.CoverArtBaseURL == "" {
		opts.CoverArtBaseURL = "https://coverartarchive.org"
	}
	if opts.AcoustIDURL == "" {
		opts.AcoustIDURL = "https://api.acoustid.org/v2/lookup"
	}
	if opts.LRCLIBBaseURL == "" {
		opts.LRCLIBBaseURL = "https://lrclib.net/api"
	}
	if opts.MBLimitInterval == 0 {
		opts.MBLimitInterval = 1100 * time.Millisecond
	}
	return &Scraper{
		pool:      pool,
		providers: providers,
		runner:    runner,
		opts:      opts,
	}
}

func scraperProxyURL(snap config.Snapshot, sourceID string) string {
	if sourceID != "" {
		if setting, ok := snap.Runtime.SourceSettings[sourceID]; ok {
			if p := strings.TrimSpace(setting.Proxy); p != "" && p != config.HiddenPlaceholder {
				return p
			}
		}
	}
	raw := strings.TrimSpace(snap.Runtime.ProxyURL)
	if raw == "" || raw == config.HiddenPlaceholder {
		return ""
	}
	scope := strings.ToLower(strings.TrimSpace(snap.Runtime.ProxyScope))
	if scope == "" || scope == "all" {
		return raw
	}
	for _, s := range snap.Runtime.ProxySources {
		if strings.EqualFold(strings.TrimSpace(s), sourceID) {
			return raw
		}
	}
	return ""
}

func (s *Scraper) Search(ctx context.Context, snap config.Snapshot, ref ScrapeReference, sources []string) ([]ScrapeCandidate, []string) {
	var candidates []ScrapeCandidate
	var warnings []string

	query := ref.Query
	if query == "" {
		parts := make([]string, 0, 2)
		if t := CleanMetaValue(ref.Title); t != "" {
			parts = append(parts, t)
		}
		if a := CleanMetaValue(ref.Artist); a != "" {
			parts = append(parts, a)
		}
		query = strings.Join(parts, " ")
	}
	hasAcoustID := false
	for _, src := range sources {
		if src == "acoustid" {
			hasAcoustID = true
			break
		}
	}
	if query == "" && !hasAcoustID {
		return nil, []string{"缺少标题，请先编辑标签或从文件名提取"}
	}

	ordered := append([]string(nil), sources...)
	sort.SliceStable(ordered, func(i, j int) bool {
		return ordered[i] == "acoustid" && ordered[j] != "acoustid"
	})

	searchRef := ref
	missingTitleWarned := false
	for _, src := range ordered {
		if src != "acoustid" && query == "" {
			if !missingTitleWarned {
				warnings = append(warnings, "缺少标题，请先编辑标签或从文件名提取")
				missingTitleWarned = true
			}
			continue
		}
		var found []ScrapeCandidate
		var err error
		switch src {
		case "musicbrainz":
			found, err = s.searchMusicBrainz(ctx, snap, searchRef)
		case "itunes":
			found, err = s.searchITunes(ctx, snap, query)
		case "lrclib":
			found, err = s.searchLRCLIB(ctx, snap, searchRef)
		case "acoustid":
			found, err = s.searchAcoustID(ctx, snap, ref)
			if err == nil && ref.Query == "" {
				var reliable []ScrapeCandidate
				recIDs := make(map[string]bool)
				for _, c := range found {
					if c.FingerprintScore >= 0.9 {
						reliable = append(reliable, c)
						recIDs[c.SourceID] = true
					}
				}
				if len(reliable) > 0 && len(recIDs) == 1 {
					q := strings.TrimSpace(reliable[0].Title + " " + reliable[0].Artist)
					if q != "" {
						query = q
					}
					if CleanMetaValue(searchRef.Title) == "" && CleanMetaValue(reliable[0].Title) != "" {
						searchRef.Title = reliable[0].Title
						searchRef.Artist = reliable[0].Artist
						if searchRef.Album == "" {
							searchRef.Album = reliable[0].Album
						}
						if searchRef.Duration <= 0 {
							searchRef.Duration = reliable[0].Duration
						}
					}
				}
			}
		default:
			found, err = s.searchProviderSource(ctx, snap, src, searchRef, query)
		}
		if err != nil {
			label := s.SourceLabel(src)
			if label == "" {
				label = src
			}
			warnings = append(warnings, fmt.Sprintf("%s查询失败：%s", label, err.Error()))
			continue
		}
		candidates = append(candidates, found...)
		if len(found) == 0 {
			label := s.SourceLabel(src)
			if label == "" {
				label = src
			}
			warnings = append(warnings, label+"未找到候选")
		}
	}
	return candidates, warnings
}

func (s *Scraper) searchProviderSource(ctx context.Context, snap config.Snapshot, sourceID string, ref ScrapeReference, query string) ([]ScrapeCandidate, error) {
	if s.providers == nil {
		return nil, fmt.Errorf("未注册音源")
	}
	p, ok := s.providers.Get(sourceID)
	if !ok {
		return nil, fmt.Errorf("不支持的刮削来源 %s", sourceID)
	}
	pCtx := provider.WithRequestContext(ctx, provider.RequestContext{
		Config: snap,
	})
	caps := p.Capabilities()
	if mp, isMeta := p.(provider.MetadataProvider); isMeta && (caps.Metadata || caps.Search || !caps.Lyrics) {
		items, err := mp.SearchMetadata(pCtx, query, CleanMetaValue(ref.Title), CleanMetaValue(ref.Artist), CleanMetaValue(ref.Album), 8)
		if err == nil {
			if caps.Metadata || len(items) > 0 || (!caps.Search && !caps.Lyrics) {
				out := make([]ScrapeCandidate, 0, len(items))
				for _, it := range items {
					out = append(out, ScrapeCandidate{
						Source:      sourceID,
						SourceID:    strings.TrimSpace(it.SourceID),
						Title:       CleanMetaValue(it.Title),
						Artist:      CleanMetaValue(it.Artist),
						Album:       CleanMetaValue(it.Album),
						AlbumArtist: CleanMetaValue(it.AlbumArtist),
						Date:        CleanMetaValue(it.Date),
						TrackNumber: CleanMetaValue(it.TrackNumber),
						DiscNumber:  CleanMetaValue(it.DiscNumber),
						Duration:    it.Duration,
						CoverURL:    strings.TrimSpace(it.CoverURL),
						Lyrics:      strings.TrimSpace(it.Lyrics),
					})
					if len(out) >= 8 {
						break
					}
				}
				return out, nil
			}
		} else if caps.Metadata || (!caps.Search && !caps.Lyrics) {
			return nil, err
		}
	}
	if caps.Search || (!caps.Metadata && !caps.Lyrics) {
		var out []ScrapeCandidate
		_, err := p.Search(pCtx, model.SearchOptions{Keyword: query, Limit: 8}, func(tr model.ProviderTrack) error {
			c := ScrapeCandidate{
				Source:           sourceID,
				SourceID:         tr.SourceID,
				Title:            CleanMetaValue(tr.Title),
				Artist:           CleanMetaValue(tr.Artist),
				Album:            CleanMetaValue(tr.Album),
				Duration:         tr.Duration,
				Lyrics:           strings.TrimSpace(tr.Lyrics),
				providerVariants: tr.Variants,
			}
			if tr.Cover != nil {
				c.CoverURL = strings.TrimSpace(*tr.Cover)
			}
			out = append(out, c)
			if len(out) >= 8 {
				return fmt.Errorf("limit_reached")
			}
			return nil
		})
		if err != nil && len(out) == 0 {
			return nil, err
		}
		return out, nil
	}
	if lp, isLyrics := p.(provider.LyricsProvider); isLyrics && caps.Lyrics {
		title := CleanMetaValue(ref.Title)
		if title == "" {
			title = strings.TrimSpace(query)
		}
		artist := CleanMetaValue(ref.Artist)
		album := CleanMetaValue(ref.Album)
		lrc, err := lp.Lyrics(pCtx, model.ProviderTrack{
			Source:   sourceID,
			Title:    title,
			Artist:   artist,
			Album:    album,
			Duration: ref.Duration,
		})
		if err != nil {
			return nil, err
		}
		trimmed := strings.TrimSpace(lrc)
		if trimmed == "" {
			return nil, nil
		}
		return []ScrapeCandidate{{
			Source:   sourceID,
			SourceID: sourceID + ":" + NormalizedMeta(title+"|"+artist),
			Title:    title,
			Artist:   artist,
			Album:    album,
			Duration: ref.Duration,
			Lyrics:   trimmed,
		}}, nil
	}
	return nil, fmt.Errorf("音源 %s 不支持元数据刮削", sourceID)
}

type lrclibRecord struct {
	ID           json.RawMessage `json:"id"`
	TrackName    string          `json:"trackName"`
	Name         string          `json:"name"`
	ArtistName   string          `json:"artistName"`
	AlbumName    string          `json:"albumName"`
	Duration     float64         `json:"duration"`
	Instrumental bool            `json:"instrumental"`
	PlainLyrics  string          `json:"plainLyrics"`
	SyncedLyrics string          `json:"syncedLyrics"`
}

func parseLRCLIBID(raw json.RawMessage) string {
	s := strings.TrimSpace(string(raw))
	if s == "" || s == "null" {
		return ""
	}
	var str string
	if json.Unmarshal(raw, &str) == nil {
		return strings.TrimSpace(str)
	}
	return s
}

func lrclibRecordToCandidate(rec lrclibRecord) (ScrapeCandidate, bool) {
	lrc := strings.TrimSpace(rec.SyncedLyrics)
	if lrc == "" {
		lrc = strings.TrimSpace(rec.PlainLyrics)
	}
	if lrc == "" {
		return ScrapeCandidate{}, false
	}
	title := CleanMetaValue(rec.TrackName)
	if title == "" {
		title = CleanMetaValue(rec.Name)
	}
	return ScrapeCandidate{
		Source:   "lrclib",
		SourceID: parseLRCLIBID(rec.ID),
		Title:    title,
		Artist:   CleanMetaValue(rec.ArtistName),
		Album:    CleanMetaValue(rec.AlbumName),
		Duration: rec.Duration,
		Lyrics:   lrc,
	}, true
}

func (s *Scraper) lrclibGet(ctx context.Context, snap config.Snapshot, endpoint string) (int, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("User-Agent", "MeloKeep/3.0")
	resp, err := s.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         scraperProxyURL(snap, "lrclib"),
		Timeout:          12 * time.Second,
		MaxResponseBytes: 4 << 20,
	})
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound || resp.StatusCode >= 400 {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return resp.StatusCode, nil, nil
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, raw, nil
}

func (s *Scraper) searchLRCLIB(ctx context.Context, snap config.Snapshot, ref ScrapeReference) ([]ScrapeCandidate, error) {
	baseURL := strings.TrimRight(s.opts.LRCLIBBaseURL, "/")
	if baseURL == "" {
		baseURL = "https://lrclib.net/api"
	}
	title := CleanMetaValue(ref.Title)
	artist := CleanMetaValue(ref.Artist)
	album := CleanMetaValue(ref.Album)
	manualQuery := strings.TrimSpace(ref.Query)

	if manualQuery == "" && title != "" && artist != "" {
		params := url.Values{
			"track_name":  {title},
			"artist_name": {artist},
		}
		if album != "" {
			params.Set("album_name", album)
		}
		if ref.Duration > 0 {
			params.Set("duration", strconv.Itoa(int(math.Round(ref.Duration))))
		}
		status, raw, err := s.lrclibGet(ctx, snap, baseURL+"/get?"+params.Encode())
		if err != nil {
			return nil, err
		}
		if status == http.StatusOK {
			var rec lrclibRecord
			if err := json.Unmarshal(raw, &rec); err == nil {
				if cand, ok := lrclibRecordToCandidate(rec); ok {
					return []ScrapeCandidate{cand}, nil
				}
			}
		} else if status != http.StatusNotFound && status >= 400 {
			return nil, fmt.Errorf("HTTP %d", status)
		}
	}

	q := manualQuery
	if q == "" {
		parts := make([]string, 0, 2)
		if title != "" {
			parts = append(parts, title)
		}
		if artist != "" {
			parts = append(parts, artist)
		}
		q = strings.Join(parts, " ")
	}
	if strings.TrimSpace(q) == "" {
		return nil, nil
	}
	status, raw, err := s.lrclibGet(ctx, snap, baseURL+"/search?"+url.Values{"q": {q}}.Encode())
	if err != nil {
		return nil, err
	}
	if status == http.StatusNotFound {
		return nil, nil
	}
	if status >= 400 {
		return nil, fmt.Errorf("HTTP %d", status)
	}
	var recs []lrclibRecord
	if err := json.Unmarshal(raw, &recs); err != nil {
		return nil, err
	}
	out := make([]ScrapeCandidate, 0, 3)
	for _, rec := range recs {
		if cand, ok := lrclibRecordToCandidate(rec); ok {
			out = append(out, cand)
			if len(out) >= 3 {
				break
			}
		}
	}
	return out, nil
}

func (s *Scraper) musicBrainzGet(ctx context.Context, snap config.Snapshot, path string, params url.Values, out any) error {
	s.mbMu.Lock()
	wait := time.Until(s.mbNext)
	if wait > 0 {
		s.mbMu.Unlock()
		select {
		case <-time.After(wait):
		case <-ctx.Done():
			return ctx.Err()
		}
		s.mbMu.Lock()
	}
	s.mbNext = time.Now().Add(s.opts.MBLimitInterval)
	s.mbMu.Unlock()

	if params == nil {
		params = url.Values{}
	}
	params.Set("fmt", "json")
	endpoint := strings.TrimRight(s.opts.MusicBrainzBaseURL, "/") + "/" + strings.TrimLeft(path, "/") + "?" + params.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "MeloKeep/3.0 (https://github.com/Agonie0v0/melokeep)")
	resp, err := s.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         scraperProxyURL(snap, "musicbrainz"),
		Timeout:          12 * time.Second,
		MaxResponseBytes: 4 << 20,
	})
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, out)
}

type mbArtistCredit struct {
	Name       string `json:"name"`
	JoinPhrase string `json:"joinphrase"`
	Artist     struct {
		Name string `json:"name"`
	} `json:"artist"`
}

func joinMBArtistCredit(credits []mbArtistCredit) string {
	var b strings.Builder
	for _, c := range credits {
		name := c.Name
		if name == "" {
			name = c.Artist.Name
		}
		b.WriteString(name)
		b.WriteString(c.JoinPhrase)
	}
	return strings.TrimSpace(b.String())
}

func quoteMBTerm(v string) string {
	v = CleanMetaValue(v)
	v = strings.ReplaceAll(v, `\`, `\\`)
	v = strings.ReplaceAll(v, `"`, `\"`)
	return v
}

func (s *Scraper) searchMusicBrainz(ctx context.Context, snap config.Snapshot, ref ScrapeReference) ([]ScrapeCandidate, error) {
	q := fmt.Sprintf(`recording:"%s"`, quoteMBTerm(ref.Title))
	if a := CleanMetaValue(ref.Artist); a != "" {
		q += fmt.Sprintf(` AND artist:"%s"`, quoteMBTerm(a))
	}
	var payload struct {
		Recordings []struct {
			ID             string           `json:"id"`
			Title          string           `json:"title"`
			Length         float64          `json:"length"`
			Disambiguation string           `json:"disambiguation"`
			ArtistCredit   []mbArtistCredit `json:"artist-credit"`
			Releases       []struct {
				ID           string           `json:"id"`
				Title        string           `json:"title"`
				Date         string           `json:"date"`
				ArtistCredit []mbArtistCredit `json:"artist-credit"`
			} `json:"releases"`
		} `json:"recordings"`
	}
	if err := s.musicBrainzGet(ctx, snap, "recording", url.Values{"query": {q}, "limit": {"8"}}, &payload); err != nil {
		return nil, err
	}
	var out []ScrapeCandidate
	for _, rec := range payload.Recordings {
		rels := rec.Releases
		if len(rels) > 4 {
			rels = rels[:4]
		}
		for _, rel := range rels {
			out = append(out, ScrapeCandidate{
				Source:      "musicbrainz",
				SourceID:    rec.ID,
				ReleaseID:   rel.ID,
				Title:       rec.Title,
				Artist:      joinMBArtistCredit(rec.ArtistCredit),
				Album:       rel.Title,
				AlbumArtist: joinMBArtistCredit(rel.ArtistCredit),
				Date:        rel.Date,
				Duration:    rec.Length / 1000.0,
				Version:     rec.Disambiguation,
			})
		}
	}
	return out, nil
}

func (s *Scraper) enrichMusicBrainz(ctx context.Context, snap config.Snapshot, cand ScrapeCandidate) (ScrapeCandidate, []string) {
	var warnings []string
	if !mbUUIDRe.MatchString(cand.ReleaseID) {
		return cand, []string{"MusicBrainz 发行版本 ID 无效"}
	}
	var rel struct {
		Title           string           `json:"title"`
		Date            string           `json:"date"`
		ArtistCredit    []mbArtistCredit `json:"artist-credit"`
		CoverArtArchive struct {
			Front bool `json:"front"`
		} `json:"cover-art-archive"`
		Media []struct {
			Position int `json:"position"`
			Tracks   []struct {
				Position  int `json:"position"`
				Recording struct {
					ID string `json:"id"`
				} `json:"recording"`
			} `json:"tracks"`
		} `json:"media"`
	}
	if err := s.musicBrainzGet(ctx, snap, "release/"+cand.ReleaseID, url.Values{"inc": {"recordings+artist-credits"}}, &rel); err != nil {
		return cand, []string{fmt.Sprintf("MusicBrainz 发行资料获取失败（%v）", err)}
	}
	if rel.Title != "" {
		cand.Album = rel.Title
	}
	if aa := joinMBArtistCredit(rel.ArtistCredit); aa != "" {
		cand.AlbumArtist = aa
	}
	if rel.Date != "" {
		cand.Date = rel.Date
	}
	type trackPos struct{ med, trk int }
	var matches []trackPos
	for _, m := range rel.Media {
		for _, t := range m.Tracks {
			if t.Recording.ID == cand.SourceID {
				matches = append(matches, trackPos{med: m.Position, trk: t.Position})
			}
		}
	}
	if len(matches) == 1 {
		cand.TrackNumber = strconv.Itoa(matches[0].trk)
		cand.DiscNumber = strconv.Itoa(matches[0].med)
	}
	if rel.CoverArtArchive.Front {
		cand.CoverURL = strings.TrimRight(s.opts.CoverArtBaseURL, "/") + "/release/" + cand.ReleaseID + "/front-500"
	}
	return cand, warnings
}

func (s *Scraper) searchITunes(ctx context.Context, snap config.Snapshot, query string) ([]ScrapeCandidate, error) {
	endpoint := strings.TrimRight(s.opts.ITunesBaseURL, "/") + "/search?" + url.Values{
		"term": {query}, "media": {"music"}, "entity": {"song"}, "limit": {"8"},
	}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	resp, err := s.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         scraperProxyURL(snap, "itunes"),
		Timeout:          12 * time.Second,
		MaxResponseBytes: 4 << 20,
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	var payload struct {
		Results []struct {
			TrackID        int64  `json:"trackId"`
			TrackName      string `json:"trackName"`
			ArtistName     string `json:"artistName"`
			CollectionName string `json:"collectionName"`
			ReleaseDate    string `json:"releaseDate"`
			TrackNumber    int    `json:"trackNumber"`
			DiscNumber     int    `json:"discNumber"`
			TrackTimeMillis int64 `json:"trackTimeMillis"`
			ArtworkURL100  string `json:"artworkUrl100"`
		} `json:"results"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return nil, err
	}
	out := make([]ScrapeCandidate, 0, len(payload.Results))
	for _, r := range payload.Results {
		dt := ""
		if len(r.ReleaseDate) >= 10 {
			dt = r.ReleaseDate[:10]
		}
		cover := strings.ReplaceAll(r.ArtworkURL100, "100x100bb", "600x600bb")
		trk := ""
		if r.TrackNumber > 0 {
			trk = strconv.Itoa(r.TrackNumber)
		}
		disc := ""
		if r.DiscNumber > 0 {
			disc = strconv.Itoa(r.DiscNumber)
		}
		out = append(out, ScrapeCandidate{
			Source:      "itunes",
			SourceID:    strconv.FormatInt(r.TrackID, 10),
			Title:       CleanMetaValue(r.TrackName),
			Artist:      CleanMetaValue(r.ArtistName),
			Album:       CleanMetaValue(r.CollectionName),
			Date:        dt,
			TrackNumber: trk,
			DiscNumber:  disc,
			Duration:    float64(r.TrackTimeMillis) / 1000.0,
			CoverURL:    cover,
		})
	}
	return out, nil
}

func (s *Scraper) searchAcoustID(ctx context.Context, snap config.Snapshot, ref ScrapeReference) ([]ScrapeCandidate, error) {
	key := strings.TrimSpace(s.opts.AcoustIDKey)
	if key == "" && s.opts.AcoustIDKeyFunc != nil {
		key = strings.TrimSpace(s.opts.AcoustIDKeyFunc(ctx))
	}
	if key == "" {
		key = strings.TrimSpace(os.Getenv("MELOKEEP_ACOUSTID_KEY"))
	}
	if key == "" {
		return nil, fmt.Errorf("请先配置 AcoustID 应用 Key")
	}
	if ref.Path == "" {
		return nil, fmt.Errorf("指纹识别需要本地音频")
	}
	if s.runner == nil {
		return nil, fmt.Errorf("未配置 fpcalc 运行器")
	}
	fp, err := s.runner.Fingerprint(ctx, ref.Path)
	if err != nil {
		return nil, err
	}
	form := url.Values{
		"client":      {key},
		"duration":    {strconv.Itoa(int(fp.Duration))},
		"fingerprint": {fp.Fingerprint},
		"meta":        {"recordings releases"},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.opts.AcoustIDURL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := s.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         scraperProxyURL(snap, "acoustid"),
		Timeout:          20 * time.Second,
		MaxResponseBytes: 4 << 20,
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	var data struct {
		Status  string `json:"status"`
		Results []struct {
			Score      float64 `json:"score"`
			Recordings []struct {
				ID       string  `json:"id"`
				Title    string  `json:"title"`
				Duration float64 `json:"duration"`
				Artists  []struct {
					Name string `json:"name"`
				} `json:"artists"`
				Releases []struct {
					ID    string `json:"id"`
					Title string `json:"title"`
				} `json:"releases"`
			} `json:"recordings"`
		} `json:"results"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		return nil, err
	}
	if data.Status != "ok" {
		return nil, fmt.Errorf("AcoustID 查询失败，请检查应用 Key")
	}
	var out []ScrapeCandidate
	for i, match := range data.Results {
		if i >= 3 {
			break
		}
		if match.Score < 0.7 {
			continue
		}
		for j, rec := range match.Recordings {
			if j >= 4 {
				break
			}
			var artistNames []string
			for _, a := range rec.Artists {
				if a.Name != "" {
					artistNames = append(artistNames, a.Name)
				}
			}
			dur := rec.Duration
			if dur <= 0 {
				dur = fp.Duration
			}
			if len(rec.Releases) == 0 {
				out = append(out, ScrapeCandidate{
					Source:           "acoustid",
					SourceID:         rec.ID,
					Title:            rec.Title,
					Artist:           strings.Join(artistNames, ", "),
					Duration:         dur,
					FingerprintScore: match.Score,
				})
				continue
			}
			for k, rel := range rec.Releases {
				if k >= 5 {
					break
				}
				out = append(out, ScrapeCandidate{
					Source:           "acoustid",
					SourceID:         rec.ID,
					ReleaseID:        rel.ID,
					Title:            rec.Title,
					Artist:           strings.Join(artistNames, ", "),
					Album:            rel.Title,
					Duration:         dur,
					FingerprintScore: match.Score,
				})
			}
		}
	}
	return out, nil
}

func sameScrapeCandidate(a, b ScrapeCandidate) bool {
	if a.ID != "" && b.ID != "" {
		return a.ID == b.ID
	}
	return a.Source == b.Source && a.SourceID == b.SourceID && a.ReleaseID == b.ReleaseID && a.Album == b.Album
}

func (s *Scraper) matchesLyricsFallback(ref ScrapeReference, cand, alt ScrapeCandidate) bool {
	if sameScrapeCandidate(alt, cand) || s.SourceLabel(alt.Source) == "" {
		return false
	}
	if !s.SourceFields(alt.Source)["lyrics"] {
		return false
	}
	if cand.ReleaseID != "" && alt.ReleaseID != "" && cand.ReleaseID != alt.ReleaseID {
		return false
	}
	title := NormalizedMeta(ref.Title)
	other := NormalizedMeta(alt.Title)
	if title == "" || other == "" || similarityRatio(title, other) < 0.96 {
		return false
	}
	artists := NormalizedMeta(ref.Artist)
	otherArtists := NormalizedMeta(alt.Artist)
	if artists != "" && otherArtists != "" &&
		artists != otherArtists && similarityRatio(artists, otherArtists) < 0.9 {
		return false
	}
	if !equalBoolMap(VersionFlags(ref.Title+" "+ref.Version), VersionFlags(alt.Title+" "+alt.Version)) {
		return false
	}
	if ref.Duration > 0 && alt.Duration > 0 {
		if math.Abs(ref.Duration-alt.Duration) > math.Max(8, ref.Duration*0.04) {
			return false
		}
	}
	return true
}

func (s *Scraper) Enrich(ctx context.Context, snap config.Snapshot, cand ScrapeCandidate, wantLyrics bool, alternatives []ScrapeCandidate) (ScrapeCandidate, []string) {
	res, warnings := s.enrichOne(ctx, snap, cand, wantLyrics)
	var alts []ScrapeCandidate
	for _, alt := range alternatives {
		if sameScrapeCandidate(alt, cand) || s.SourceLabel(alt.Source) == "" {
			continue
		}
		if CleanMetaValue(cand.Album) == "" {
			continue
		}
		if cand.ReleaseID != "" && alt.ReleaseID != "" && cand.ReleaseID != alt.ReleaseID {
			continue
		}
		if NormalizedMeta(alt.Album) == NormalizedMeta(cand.Album) {
			alts = append(alts, alt)
		}
	}
	ref := ScrapeReference{
		Source:   cand.Source,
		SourceID: cand.SourceID,
		Title:    cand.Title,
		Artist:   cand.Artist,
		Album:    cand.Album,
		Duration: cand.Duration,
		Version:  cand.Version,
	}
	prov := make(map[string]string, len(res.FieldSources))
	for k, v := range res.FieldSources {
		prov[k] = v
	}

	_, fallback := PickCandidates(ref, alts)
	if fallback != nil {
		fields := []string{"album_artist", "date", "track_number", "disc_number", "cover_url"}
		if wantLyrics {
			fields = append(fields, "lyrics")
		}
		supported := s.SourceFields(fallback.Source)
		var filtered []string
		for _, f := range fields {
			sf := f
			if sf == "cover_url" {
				sf = "cover"
			}
			if supported[sf] {
				filtered = append(filtered, f)
			}
		}
		needsExtra := false
		for _, f := range filtered {
			if candidateFieldValue(res, f) == "" {
				needsExtra = true
				break
			}
		}
		if needsExtra {
			extra, extraWarns := s.enrichOne(ctx, snap, *fallback, wantLyrics && CleanMetaValue(res.Lyrics) == "")
			warnings = append(warnings, extraWarns...)
			for _, f := range filtered {
				if CleanMetaValue(candidateFieldValue(res, f)) == "" {
					if v := CleanMetaValue(candidateFieldValue(extra, f)); v != "" {
						setCandidateFieldValue(&res, f, v)
						prov[f] = fallback.Source
					}
				}
			}
		}
	}

	if wantLyrics && CleanMetaValue(res.Lyrics) == "" {
		var lyricAlts []ScrapeCandidate
		for _, alt := range alternatives {
			if s.matchesLyricsFallback(ref, cand, alt) {
				lyricAlts = append(lyricAlts, RankCandidate(ref, alt))
			}
		}
		candAlbumNorm := NormalizedMeta(cand.Album)
		sort.SliceStable(lyricAlts, func(i, j int) bool {
			iSame := candAlbumNorm != "" && NormalizedMeta(lyricAlts[i].Album) == candAlbumNorm
			jSame := candAlbumNorm != "" && NormalizedMeta(lyricAlts[j].Album) == candAlbumNorm
			if iSame != jSame {
				return iSame
			}
			return lyricAlts[i].Score > lyricAlts[j].Score
		})
		for _, alt := range lyricAlts {
			extra, extraWarns := s.enrichOne(ctx, snap, alt, true)
			if lrc := CleanMetaValue(extra.Lyrics); lrc != "" {
				res.Lyrics = lrc
				prov["lyrics"] = alt.Source
				warnings = append(warnings, extraWarns...)
				break
			}
		}
	}

	if len(prov) > 0 {
		res.FieldSources = prov
	}
	if CleanMetaValue(res.Lyrics) != "" {
		var kept []string
		for _, w := range warnings {
			if !strings.HasPrefix(w, "MusicBrainz 不提供歌词") && !strings.HasPrefix(w, "iTunes 不提供歌词") {
				kept = append(kept, w)
			}
		}
		warnings = kept
	}
	return res, warnings
}

func (s *Scraper) enrichOne(ctx context.Context, snap config.Snapshot, cand ScrapeCandidate, wantLyrics bool) (ScrapeCandidate, []string) {
	if cand.Source == "musicbrainz" || cand.Source == "acoustid" {
		res, warns := s.enrichMusicBrainz(ctx, snap, cand)
		if wantLyrics {
			warns = append(warns, "MusicBrainz 不提供歌词；可选择其他平台候选补全歌词")
		}
		return res, warns
	}
	if cand.Source == "itunes" && wantLyrics {
		return cand, []string{"iTunes 不提供歌词；可选择其他平台候选补全歌词"}
	}
	if wantLyrics && CleanMetaValue(cand.Lyrics) == "" && s.providers != nil {
		if p, ok := s.providers.Get(cand.Source); ok {
			if lp, ok := p.(provider.LyricsProvider); ok {
				pCtx := provider.WithRequestContext(ctx, provider.RequestContext{Config: snap})
				pt := model.ProviderTrack{
					Source:   cand.Source,
					SourceID: cand.SourceID,
					Title:    cand.Title,
					Artist:   cand.Artist,
					Album:    cand.Album,
					Duration: cand.Duration,
					Variants: cand.providerVariants,
				}
				if lrc, lErr := lp.Lyrics(pCtx, pt); lErr == nil {
					if trimmed := strings.TrimSpace(lrc); trimmed != "" {
						cand.Lyrics = trimmed
					}
				}
			}
		}
	}
	return cand, nil
}

func candidateFieldValue(c ScrapeCandidate, field string) string {
	switch field {
	case "title":
		return c.Title
	case "artist":
		return c.Artist
	case "album":
		return c.Album
	case "album_artist":
		return c.AlbumArtist
	case "date":
		return c.Date
	case "track_number":
		return c.TrackNumber
	case "disc_number":
		return c.DiscNumber
	case "cover_url":
		return c.CoverURL
	case "lyrics":
		return c.Lyrics
	default:
		return ""
	}
}

func setCandidateFieldValue(c *ScrapeCandidate, field, val string) {
	switch field {
	case "title":
		c.Title = val
	case "artist":
		c.Artist = val
	case "album":
		c.Album = val
	case "album_artist":
		c.AlbumArtist = val
	case "date":
		c.Date = val
	case "track_number":
		c.TrackNumber = val
	case "disc_number":
		c.DiscNumber = val
	case "cover_url":
		c.CoverURL = val
	case "lyrics":
		c.Lyrics = val
	}
}

func (s *Scraper) FetchCover(ctx context.Context, snap config.Snapshot, coverURL string) ([]byte, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, coverURL, nil)
	if err != nil {
		return nil, "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "封面地址无效", err)
	}
	req.Header.Set("User-Agent", "MeloKeep/3.0")
	resp, err := s.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         scraperProxyURL(snap, ""),
		Timeout:          15 * time.Second,
		MaxResponseBytes: 8 << 20,
	})
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return nil, "", model.NewError(model.ErrTransport, http.StatusBadGateway, fmt.Sprintf("下载封面失败: HTTP %d", resp.StatusCode))
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, (8<<20)+1))
	if err != nil {
		return nil, "", err
	}
	if len(data) > 8<<20 {
		return nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "封面超过 8 MB")
	}
	mime := DetectImageMIME(data)
	if mime == "" {
		return nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "封面不是有效的 PNG/JPEG 图片")
	}
	return data, mime, nil
}

func DetectImageMIME(data []byte) string {
	if len(data) >= 8 && bytes.Equal(data[:8], []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}) {
		return "image/png"
	}
	if len(data) >= 3 && bytes.Equal(data[:3], []byte{0xff, 0xd8, 0xff}) {
		return "image/jpeg"
	}
	return ""
}

func nonNilStrings(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}
