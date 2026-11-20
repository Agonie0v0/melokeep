package plugin

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"path"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/transport"
)

var (
	_ model.Provider            = (*Provider)(nil)
	_ model.PlaylistProvider    = (*Provider)(nil)
	_ provider.EpisodeProvider  = (*Provider)(nil)
	_ provider.LyricsProvider   = (*Provider)(nil)
	_ provider.MetadataProvider = (*Provider)(nil)
)

const (
	defaultResolvedMediaTTL = 15 * time.Minute
	maxResolveCandidates    = 16
	resolveProbeConcurrency = 4
	resolveProbeTimeout     = 8 * time.Second
	trialMinBPSLossless     = 250000.0
	trialMinBPSLossy        = 64000.0
)

// ProviderOptions configures a runtime plugin provider adapter.
type ProviderOptions struct {
	Pool           *transport.Pool
	Settings       map[string]string
	AllowedOrigins []string
	InvokeTimeout  time.Duration
	LogFunc        provider.LogFunc
	SkipAudioProbe bool
}

// Provider adapts a compiled JS plugin to MeloKeep provider interfaces.
type Provider struct {
	compiled       *CompiledPlugin
	pool           *transport.Pool
	adapter        *provider.TransportAdapter
	settings       map[string]string
	allowedOrigins []string
	allowedSet     map[string]bool
	invokeTimeout  time.Duration
	logFn          provider.LogFunc
	skipAudioProbe bool
}

// NewProvider constructs a Provider adapter from a compiled plugin.
func NewProvider(compiled *CompiledPlugin, opts ProviderOptions) (*Provider, error) {
	if compiled == nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件编译结果不能为空")
	}
	normOrigins, err := ValidateAndNormalizeOrigins(opts.AllowedOrigins)
	if err != nil {
		return nil, err
	}
	settingsCopy := make(map[string]string, len(opts.Settings))
	for k, v := range opts.Settings {
		settingsCopy[k] = v
	}
	pool := opts.Pool
	if pool == nil {
		pool = transport.NewPool()
	}
	return &Provider{
		compiled:       compiled,
		pool:           pool,
		adapter:        provider.NewTransportAdapter(pool),
		settings:       settingsCopy,
		allowedOrigins: normOrigins,
		allowedSet:     buildOriginSet(normOrigins),
		invokeTimeout:  opts.InvokeTimeout,
		logFn:          opts.LogFunc,
		skipAudioProbe: opts.SkipAudioProbe,
	}, nil
}

func (p *Provider) ID() string         { return p.compiled.Manifest.ID }
func (p *Provider) EngineName() string { return p.compiled.Manifest.ID }

func (p *Provider) Capabilities() model.Capability {
	c := p.compiled.Manifest.Capabilities
	return model.Capability{
		Tier:     "plugin",
		Note:     c.Note,
		Search:   c.Search,
		Playlist: c.Playlist,
		Lyrics:   c.Lyrics,
		Metadata: c.Metadata,
		Episodes: c.Episodes,
		Partial:  c.Partial,
	}
}

// RegistryEntry builds a provider.Entry using a capability-exact provider wrapper.
func (p *Provider) RegistryEntry() provider.Entry {
	m := p.compiled.Manifest
	cat := strings.TrimSpace(m.Category)
	if cat == "" {
		cat = "自定义插件"
	}
	return provider.Entry{
		Provider: p.AsCapabilityProvider(),
		Meta: provider.Metadata{
			Label:       m.Name,
			Category:    cat,
			Origin:      "plugin",
			PluginID:    m.ID,
			Version:     m.Version,
			Description: m.Description,
		},
	}
}

func (p *Provider) runtimeCfg(logFn provider.LogFunc) runtimeConfig {
	fn := p.logFn
	if logFn != nil {
		fn = logFn
	}
	return runtimeConfig{
		pool:           p.pool,
		settings:       p.settings,
		allowedOrigins: p.allowedOrigins,
		timeout:        p.invokeTimeout,
		logFn:          fn,
	}
}

func (p *Provider) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	if !p.compiled.Manifest.Capabilities.Search {
		return model.SearchSummary{}, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "插件未启用 search 能力")
	}
	limit := opts.Limit
	if limit <= 0 {
		rc := provider.RequestContextFrom(ctx)
		if rc.Config.Runtime.ResultsPerSource > 0 {
			limit = rc.Config.Runtime.ResultsPerSource
		} else {
			limit = 10
		}
	}

	seen := make(map[string]bool)
	emitted := 0
	emitTrack := func(raw map[string]any) error {
		if limit > 0 && emitted >= limit {
			return errEmitLimitReached
		}
		tr, ok := p.normalizeTrackMap(raw)
		if !ok {
			return nil
		}
		key := tr.SourceID + "\x00" + tr.Title + "\x00" + tr.Artist
		if seen[key] {
			return nil
		}
		seen[key] = true
		if emit != nil {
			if err := emit(tr); err != nil {
				return err
			}
		}
		emitted++
		return nil
	}

	res, err := invokeCompiledPlugin(ctx, p.compiled, p.runtimeCfg(nil), invocationParams{
		fnName: "search",
		args: []any{
			map[string]any{
				"keyword": opts.Keyword,
				"limit":   limit,
				"refresh": opts.Refresh,
			},
		},
		onEmit: emitTrack,
	})
	if err != nil && !errors.Is(err, errEmitLimitReached) {
		return model.SearchSummary{Count: emitted}, err
	}

	detail := ""
	if !errors.Is(err, errEmitLimitReached) && res != nil {
		var returnedRows []map[string]any
		switch typed := res.(type) {
		case []any:
			returnedRows = sliceAnyToMaps(typed)
		case map[string]any:
			detail = strings.TrimSpace(anyToString(typed["detail"]))
			if rows := extractTrackSlice(typed, "tracks", "items", "results"); len(rows) > 0 {
				returnedRows = rows
			}
		}
		for _, row := range returnedRows {
			if eErr := emitTrack(row); eErr != nil {
				if errors.Is(eErr, errEmitLimitReached) {
					break
				}
				return model.SearchSummary{Count: emitted}, eErr
			}
		}
	}

	return model.SearchSummary{Count: emitted, Detail: detail}, nil
}

func (p *Provider) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	jsTrack := trackToJSMap(track)
	jsPolicy := map[string]any{
		"preferredVariantId": policy.PreferredVariantID,
		"preferredQuality":   policy.PreferredQuality,
		"allowPreview":       policy.AllowPreview,
	}

	res, err := invokeCompiledPlugin(ctx, p.compiled, p.runtimeCfg(nil), invocationParams{
		fnName: "resolve",
		args:   []any{jsTrack, jsPolicy},
	})
	if err != nil {
		return nil, err
	}

	var rawItems []map[string]any
	switch typed := res.(type) {
	case []any:
		rawItems = sliceAnyToMaps(typed)
	case map[string]any:
		if rows := extractTrackSlice(typed, "media", "items", "results", "candidates"); len(rows) > 0 {
			rawItems = rows
		} else if strings.TrimSpace(anyToString(typed["url"])) != "" {
			rawItems = []map[string]any{typed}
		}
	}

	type resolveCandidate struct {
		rawURL      string
		codec       string
		bitrate     int
		sizeBytes   int64
		duration    float64
		upstreamID  string
		protocol    string
		label       string
		previewOnly bool
		headers     map[string]string
		cookies     map[string]string
		lyrics      string
	}

	candidates := make([]resolveCandidate, 0, min(len(rawItems), maxResolveCandidates))
	seenURLs := make(map[string]bool, len(rawItems))
	var sawForbidden, sawInvalid, sawPreviewSkipped bool
	for _, item := range rawItems {
		rawURL := strings.TrimSpace(anyToString(item["url"]))
		if err := validateResolvedMediaURL(rawURL, p.allowedSet); err != nil {
			if de, ok := model.AsDomainError(err); ok && de.Code == model.ErrForbidden {
				sawForbidden = true
			} else {
				sawInvalid = true
			}
			continue
		}
		if seenURLs[rawURL] {
			continue
		}
		seenURLs[rawURL] = true

		previewOnly := anyToBool(firstNonNil(item["previewOnly"], item["preview_only"]))
		if previewOnly && !policy.AllowPreview {
			sawPreviewSkipped = true
			continue
		}

		codec := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(anyToString(item["codec"])), "."))
		bitrate := anyToInt(item["bitrate"])
		sizeBytes := anyToInt64(firstNonNil(item["sizeBytes"], item["size_bytes"]))
		duration := provider.ParseDurationSeconds(item["duration"])
		if duration <= 0 {
			duration = track.Duration
		}
		upstreamID := strings.TrimSpace(anyToString(firstNonNil(item["upstreamId"], item["upstream_id"], item["variantId"], item["variant_id"])))
		protocol := strings.ToLower(strings.TrimSpace(anyToString(item["protocol"])))
		label := strings.TrimSpace(anyToString(item["label"]))

		candidates = append(candidates, resolveCandidate{
			rawURL:      rawURL,
			codec:       codec,
			bitrate:     bitrate,
			sizeBytes:   sizeBytes,
			duration:    duration,
			upstreamID:  upstreamID,
			protocol:    protocol,
			label:       label,
			previewOnly: previewOnly,
			headers:     anyToStringMap(item["headers"]),
			cookies:     anyToStringMap(item["cookies"]),
			lyrics:      strings.TrimSpace(anyToString(item["lyrics"])),
		})
		if len(candidates) >= maxResolveCandidates {
			break
		}
	}

	var out []model.ResolvedMedia
	var lastProbeErr error
	if p.skipAudioProbe {
		for _, cand := range candidates {
			codec := cand.codec
			if codec == "" {
				codec = inferExtFromURL(cand.rawURL)
			}
			protocol := cand.protocol
			if protocol == "" {
				protocol = "http"
			}
			label := cand.label
			if label == "" {
				label = formatVariantLabel(codec, cand.bitrate, cand.previewOnly)
			}
			vid := provider.MakeVariantID(p.ID(), track.SourceID, cand.upstreamID, codec, cand.bitrate, cand.previewOnly, cand.sizeBytes)
			sizePtr := provider.PtrInt64Positive(cand.sizeBytes)
			variant := provider.NewMediaVariant(vid, cand.upstreamID, label, codec, cand.bitrate, false, cand.previewOnly, protocol, sizePtr, cand.duration)

			out = append(out, model.ResolvedMedia{
				VariantID:   vid,
				Variant:     variant,
				URL:         cand.rawURL,
				Expiry:      time.Now().UTC().Add(defaultResolvedMediaTTL),
				Headers:     cand.headers,
				Cookies:     cand.cookies,
				PreviewOnly: cand.previewOnly,
				Codec:       codec,
				Bitrate:     variant.Bitrate,
				SizeBytes:   sizePtr,
				Lyrics:      cand.lyrics,
			})
		}
	} else if len(candidates) > 0 {
		type probeOutcome struct {
			probe provider.ProbeResult
			err   error
		}
		outcomes := make([]probeOutcome, len(candidates))
		sem := make(chan struct{}, resolveProbeConcurrency)
		var wg sync.WaitGroup
		for i, cand := range candidates {
			wg.Add(1)
			go func(idx int, c resolveCandidate) {
				defer wg.Done()
				select {
				case sem <- struct{}{}:
				case <-ctx.Done():
					outcomes[idx].err = ctx.Err()
					return
				}
				defer func() { <-sem }()

				probeCtx, cancel := context.WithTimeout(ctx, resolveProbeTimeout)
				defer cancel()
				res, probeErr := p.adapter.ProbeAudioWithAllowedOrigins(
					probeCtx,
					p.EngineName(),
					c.rawURL,
					c.headers,
					c.cookies,
					true,
					p.allowedOrigins,
				)
				outcomes[idx] = probeOutcome{probe: res, err: probeErr}
			}(i, cand)
		}
		wg.Wait()

		seenQuality := make(map[string]bool, len(candidates))
		for i, cand := range candidates {
			oc := outcomes[i]
			if oc.err != nil {
				lastProbeErr = oc.err
				if de, ok := model.AsDomainError(oc.err); ok && de.Code == model.ErrForbidden {
					sawForbidden = true
				}
				continue
			}

			probe := oc.probe
			finalURL := strings.TrimSpace(probe.FinalURL)
			if finalURL == "" {
				finalURL = cand.rawURL
			}
			ext := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(probe.Ext), "."))
			if ext == "" {
				ext = cand.codec
			}
			if ext == "" {
				ext = inferExtFromURL(finalURL)
			}

			finalSize := probe.SizeBytes
			if finalSize <= 0 {
				finalSize = cand.sizeBytes
			}
			br := provider.NormalizeResolvedBitrate(ext, probe.Bitrate, cand.bitrate, finalSize, cand.duration)
			trialMinBPS := trialMinBPSLossy
			if provider.IsLosslessExt(ext) {
				trialMinBPS = trialMinBPSLossless
			}
			isTrial := cand.previewOnly || provider.IsTrialAudio(finalURL, finalSize, cand.duration, trialMinBPS)
			if isTrial && !policy.AllowPreview {
				sawPreviewSkipped = true
				continue
			}

			protocol := strings.ToLower(strings.TrimSpace(probe.Protocol))
			if protocol == "" {
				protocol = cand.protocol
			}
			if protocol == "" {
				protocol = "http"
			}

			label := cand.label
			if label == "" ||
				isTrial != cand.previewOnly ||
				provider.IsLosslessExt(ext) ||
				(cand.codec != "" && cand.codec != ext) ||
				(br > 0 && br != cand.bitrate) {
				label = formatVariantLabel(ext, br, isTrial)
			}

			vid := provider.MakeVariantID(p.ID(), track.SourceID, cand.upstreamID, ext, br, isTrial, finalSize)
			sizePtr := provider.PtrInt64Positive(finalSize)
			variant := provider.NewMediaVariant(vid, cand.upstreamID, label, ext, br, true, isTrial, protocol, sizePtr, cand.duration)

			matchPolicy := policy
			if pref := strings.TrimSpace(policy.PreferredVariantID); pref != "" && variant.UpstreamID == pref {
				matchPolicy.PreferredVariantID = ""
			}
			if !provider.MatchQualityPolicy(variant, matchPolicy) {
				continue
			}

			qKey := fmt.Sprintf("%s|%d|%d|%v", ext, br, finalSize, isTrial)
			if seenQuality[qKey] {
				continue
			}
			seenQuality[qKey] = true

			out = append(out, model.ResolvedMedia{
				VariantID:   vid,
				Variant:     variant,
				URL:         finalURL,
				Expiry:      time.Now().UTC().Add(defaultResolvedMediaTTL),
				Headers:     cand.headers,
				Cookies:     cand.cookies,
				PreviewOnly: isTrial,
				Codec:       ext,
				Bitrate:     variant.Bitrate,
				SizeBytes:   sizePtr,
				Lyrics:      cand.lyrics,
			})
		}
	}

	if len(out) == 0 {
		if ctx.Err() != nil {
			return nil, provider.ClassifyError(p.EngineName(), 0, "", ctx.Err())
		}
		if sawForbidden {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "解析结果包含禁止访问的内网地址")
		}
		if sawPreviewSkipped {
			return nil, model.NewError(model.ErrAuthRequired, http.StatusUnauthorized, "仅检测到试听片段，需要允许试听或更高权限凭据")
		}
		if sawInvalid {
			return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "插件返回的媒体地址格式无效")
		}
		if lastProbeErr != nil {
			return nil, lastProbeErr
		}
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "插件未能解析到可用音频地址")
	}

	pref := strings.TrimSpace(policy.PreferredVariantID)
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].PreviewOnly != out[j].PreviewOnly {
			return !out[i].PreviewOnly
		}
		if pref != "" {
			iMatch := out[i].VariantID == pref || out[i].Variant.UpstreamID == pref
			jMatch := out[j].VariantID == pref || out[j].Variant.UpstreamID == pref
			if iMatch != jMatch {
				return iMatch
			}
		}
		return provider.AudioRankBetter(out[i], out[j])
	})
	return out, nil
}

func (p *Provider) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	if !p.compiled.Manifest.Capabilities.Playlist {
		return model.PlaylistSummary{}, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "插件未启用 playlist 能力")
	}

	seen := make(map[string]bool)
	emitted := 0
	emitTrack := func(raw map[string]any) error {
		tr, ok := p.normalizeTrackMap(raw)
		if !ok {
			return nil
		}
		key := tr.SourceID + "\x00" + tr.Title + "\x00" + tr.Artist
		if seen[key] {
			return nil
		}
		seen[key] = true
		emitted++
		if tr.PlaylistIndex == nil {
			idx := emitted
			tr.PlaylistIndex = &idx
		}
		if emit != nil {
			if err := emit(tr); err != nil {
				return err
			}
		}
		return nil
	}

	res, err := invokeCompiledPlugin(ctx, p.compiled, p.runtimeCfg(nil), invocationParams{
		fnName: "playlist",
		args:   []any{locator},
		onEmit: emitTrack,
	})
	if err != nil {
		return model.PlaylistSummary{Count: emitted}, err
	}

	summary := model.PlaylistSummary{
		ID:     strings.TrimSpace(locator),
		Count:  emitted,
		Issues: []model.PlaylistIssueDTO{},
	}

	switch typed := res.(type) {
	case []any:
		for _, row := range sliceAnyToMaps(typed) {
			if eErr := emitTrack(row); eErr != nil {
				return summary, eErr
			}
		}
		summary.Count = emitted
	case map[string]any:
		if idVal := strings.TrimSpace(anyToString(typed["id"])); idVal != "" {
			summary.ID = idVal
		}
		summary.Name = strings.TrimSpace(anyToString(typed["name"]))
		summary.Detail = strings.TrimSpace(anyToString(typed["detail"]))
		if rows := extractTrackSlice(typed, "tracks", "items"); len(rows) > 0 {
			for _, row := range rows {
				if eErr := emitTrack(row); eErr != nil {
					return summary, eErr
				}
			}
		}
		summary.Count = emitted
		if summary.Count == 0 {
			if c := anyToInt(typed["count"]); c > 0 {
				summary.Count = c
			}
		}
		summary.Issues = parsePlaylistIssues(typed["issues"])
	}

	return summary, nil
}

func (p *Provider) Episodes(ctx context.Context, track model.ProviderTrack) ([]model.ProviderTrack, error) {
	if !p.compiled.Manifest.Capabilities.Episodes {
		return nil, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "插件未启用 episodes 能力")
	}

	var out []model.ProviderTrack
	seen := make(map[string]bool)
	appendTrack := func(raw map[string]any) {
		tr, ok := p.normalizeTrackMap(raw)
		if !ok {
			return
		}
		if seen[tr.SourceID] {
			return
		}
		seen[tr.SourceID] = true
		if tr.PlaylistIndex == nil {
			idx := len(out) + 1
			tr.PlaylistIndex = &idx
		}
		out = append(out, tr)
	}

	res, err := invokeCompiledPlugin(ctx, p.compiled, p.runtimeCfg(nil), invocationParams{
		fnName: "episodes",
		args:   []any{trackToJSMap(track)},
		onEmit: func(raw map[string]any) error {
			appendTrack(raw)
			return nil
		},
	})
	if err != nil {
		return nil, err
	}

	switch typed := res.(type) {
	case []any:
		for _, row := range sliceAnyToMaps(typed) {
			appendTrack(row)
		}
	case map[string]any:
		for _, row := range extractTrackSlice(typed, "episodes", "tracks", "items") {
			appendTrack(row)
		}
	}
	if out == nil {
		out = []model.ProviderTrack{}
	}
	return out, nil
}

func (p *Provider) Lyrics(ctx context.Context, track model.ProviderTrack) (string, error) {
	if !p.compiled.Manifest.Capabilities.Lyrics {
		return "", model.NewError(model.ErrUnsupported, http.StatusBadRequest, "插件未启用 lyrics 能力")
	}

	res, err := invokeCompiledPlugin(ctx, p.compiled, p.runtimeCfg(nil), invocationParams{
		fnName: "lyrics",
		args:   []any{trackToJSMap(track)},
	})
	if err != nil {
		return "", err
	}

	switch typed := res.(type) {
	case nil:
		return "", nil
	case string:
		return strings.TrimSpace(typed), nil
	case map[string]any:
		return strings.TrimSpace(anyToString(firstNonNil(typed["lyrics"], typed["text"], typed["lrc"]))), nil
	default:
		return strings.TrimSpace(anyToString(typed)), nil
	}
}

func (p *Provider) SearchMetadata(ctx context.Context, query string, title, artist, album string, limit int) ([]provider.MetadataItem, error) {
	if !p.compiled.Manifest.Capabilities.Metadata {
		return nil, model.NewError(model.ErrUnsupported, http.StatusBadRequest, "插件未启用 metadata 能力")
	}
	if limit <= 0 {
		limit = 8
	}

	res, err := invokeCompiledPlugin(ctx, p.compiled, p.runtimeCfg(nil), invocationParams{
		fnName: "metadata",
		args: []any{
			map[string]any{
				"query":  query,
				"title":  title,
				"artist": artist,
				"album":  album,
				"limit":  limit,
			},
		},
	})
	if err != nil {
		return nil, err
	}

	var rows []map[string]any
	switch typed := res.(type) {
	case []any:
		rows = sliceAnyToMaps(typed)
	case map[string]any:
		rows = extractTrackSlice(typed, "items", "results", "metadata")
	}

	items := make([]provider.MetadataItem, 0, len(rows))
	for _, row := range rows {
		item := provider.MetadataItem{
			SourceID:    strings.TrimSpace(anyToString(firstNonNil(row["sourceId"], row["source_id"], row["id"]))),
			Title:       provider.CleanText(anyToString(row["title"])),
			Artist:      provider.CleanText(anyToString(row["artist"])),
			Album:       provider.CleanText(anyToString(row["album"])),
			AlbumArtist: provider.CleanText(anyToString(firstNonNil(row["albumArtist"], row["album_artist"]))),
			Date:        strings.TrimSpace(anyToString(firstNonNil(row["date"], row["releaseDate"], row["release_date"]))),
			TrackNumber: strings.TrimSpace(anyToString(firstNonNil(row["trackNumber"], row["track_number"]))),
			DiscNumber:  strings.TrimSpace(anyToString(firstNonNil(row["discNumber"], row["disc_number"]))),
			Duration:    provider.ParseDurationSeconds(row["duration"]),
			CoverURL:    strings.TrimSpace(anyToString(firstNonNil(row["coverUrl"], row["cover_url"], row["cover"]))),
			Lyrics:      strings.TrimSpace(anyToString(row["lyrics"])),
		}
		if item.Title == "" && item.SourceID == "" {
			continue
		}
		items = append(items, item)
		if len(items) >= limit {
			break
		}
	}
	return items, nil
}

func (p *Provider) normalizeTrackMap(raw map[string]any) (model.ProviderTrack, bool) {
	if raw == nil {
		return model.ProviderTrack{}, false
	}
	sourceID := strings.TrimSpace(anyToString(firstNonNil(raw["sourceId"], raw["source_id"], raw["id"])))
	if sourceID == "" {
		return model.ProviderTrack{}, false
	}

	title := provider.CleanText(anyToString(raw["title"]))
	artist := provider.CleanText(anyToString(raw["artist"]))
	album := provider.CleanText(anyToString(raw["album"]))
	duration := provider.ParseDurationSeconds(raw["duration"])
	if duration < 0 {
		duration = 0
	}
	coverURL := strings.TrimSpace(anyToString(firstNonNil(raw["cover"], raw["coverUrl"], raw["cover_url"])))
	sizeBytes := provider.PtrInt64Positive(anyToInt64(firstNonNil(raw["sizeBytes"], raw["size_bytes"])))
	sizeLabel := provider.PtrString(anyToString(firstNonNil(raw["sizeLabel"], raw["size_label"])))
	episodes := anyToInt(raw["episodes"])
	lyrics := strings.TrimSpace(anyToString(raw["lyrics"]))

	var playlistIdx *int
	if rawIdx := firstNonNil(raw["playlistIndex"], raw["playlist_index"]); rawIdx != nil {
		if idx := anyToInt(rawIdx); idx > 0 {
			playlistIdx = &idx
		}
	}

	variants := normalizeVariants(p.ID(), sourceID, duration, raw["variants"])

	return model.ProviderTrack{
		PublicID:      model.NewUUID(),
		Source:        p.ID(),
		SourceID:      sourceID,
		RootSource:    p.EngineName(),
		Title:         title,
		Artist:        artist,
		Album:         album,
		Duration:      duration,
		Cover:         provider.PtrString(coverURL),
		SizeBytes:     sizeBytes,
		SizeLabel:     sizeLabel,
		Episodes:      episodes,
		PlaylistIndex: playlistIdx,
		Lyrics:        lyrics,
		Variants:      variants,
	}, true
}

func normalizeVariants(pluginID, sourceID string, trackDuration float64, raw any) []model.MediaVariant {
	arr, ok := raw.([]any)
	if !ok || len(arr) == 0 {
		return []model.MediaVariant{}
	}
	out := make([]model.MediaVariant, 0, len(arr))
	for _, entry := range arr {
		m, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		codec := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(anyToString(m["codec"])), "."))
		bitrate := anyToInt(m["bitrate"])
		previewOnly := anyToBool(firstNonNil(m["previewOnly"], m["preview_only"]))
		sizeVal := anyToInt64(firstNonNil(m["sizeBytes"], m["size_bytes"]))
		upstreamID := strings.TrimSpace(anyToString(firstNonNil(m["upstreamId"], m["upstream_id"], m["variantId"], m["variant_id"])))
		protocol := strings.TrimSpace(anyToString(m["protocol"]))
		if protocol == "" {
			protocol = "http"
		}
		label := strings.TrimSpace(anyToString(m["label"]))
		if label == "" {
			label = formatVariantLabel(codec, bitrate, previewOnly)
		}
		dur := provider.ParseDurationSeconds(m["duration"])
		if dur <= 0 {
			dur = trackDuration
		}
		vid := provider.MakeVariantID(pluginID, sourceID, upstreamID, codec, bitrate, previewOnly, sizeVal)
		out = append(out, provider.NewMediaVariant(vid, upstreamID, label, codec, bitrate, false, previewOnly, protocol, provider.PtrInt64Positive(sizeVal), dur))
	}
	return out
}

func trackToJSMap(tr model.ProviderTrack) map[string]any {
	cover := ""
	if tr.Cover != nil {
		cover = *tr.Cover
	}
	variants := make([]map[string]any, 0, len(tr.Variants))
	for _, v := range tr.Variants {
		item := map[string]any{
			"variantId":    v.VariantID,
			"upstreamId":   v.UpstreamID,
			"label":        v.Label,
			"codec":        v.Codec,
			"lossless":     v.Lossless,
			"availability": v.Availability,
			"evidence":     v.Evidence,
			"previewOnly":  v.PreviewOnly,
			"protocol":     v.Protocol,
		}
		if v.Bitrate != nil {
			item["bitrate"] = *v.Bitrate
		}
		if v.SizeBytes != nil {
			item["sizeBytes"] = *v.SizeBytes
		}
		variants = append(variants, item)
	}
	return map[string]any{
		"publicId":   tr.PublicID,
		"source":     tr.Source,
		"sourceId":   tr.SourceID,
		"source_id":  tr.SourceID,
		"rootSource": tr.RootSource,
		"title":      tr.Title,
		"artist":     tr.Artist,
		"album":      tr.Album,
		"duration":   tr.Duration,
		"cover":      cover,
		"episodes":   tr.Episodes,
		"variants":   variants,
	}
}

func validateResolvedMediaURL(rawURL string, allowedOrigins map[string]bool) error {
	trimmed := strings.TrimSpace(rawURL)
	if trimmed == "" {
		return model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "媒体地址不能为空")
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "媒体地址解析失败", err)
	}
	scheme := strings.ToLower(u.Scheme)
	host := u.Hostname()
	if (scheme != "http" && scheme != "https") || host == "" || u.User != nil {
		return model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "仅允许不含凭据的 http/https 媒体地址")
	}
	if allowedOrigins[originKey(u)] {
		return nil
	}
	if ip := net.ParseIP(host); ip != nil {
		if !transport.IsPublicIP(ip) {
			return model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止返回非公网媒体地址")
		}
		return nil
	}
	if isInternalHostname(host) {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止返回内网或本机媒体地址")
	}
	return nil
}

func isInternalHostname(rawHost string) bool {
	h := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(rawHost), "."))
	if h == "" {
		return true
	}
	if h == "localhost" || strings.HasSuffix(h, ".localhost") ||
		strings.HasSuffix(h, ".local") || strings.HasSuffix(h, ".internal") ||
		h == "host.docker.internal" || h == "metadata.google.internal" ||
		h == "metadata" || h == "169.254.169.254" {
		return true
	}
	if strings.HasSuffix(h, ".nip.io") || strings.HasSuffix(h, ".sslip.io") || strings.HasSuffix(h, ".xip.io") {
		labels := strings.Split(h, ".")
		if len(labels) >= 6 {
			if ip := net.ParseIP(strings.Join(labels[:4], ".")); ip != nil && !transport.IsPublicIP(ip) {
				return true
			}
		}
		if len(labels) >= 3 {
			if ip := net.ParseIP(strings.ReplaceAll(labels[0], "-", ".")); ip != nil && !transport.IsPublicIP(ip) {
				return true
			}
		}
	}
	return false
}

func inferExtFromURL(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil {
		return "mp3"
	}
	ext := strings.ToLower(strings.TrimPrefix(path.Ext(u.Path), "."))
	if ext != "" && len(ext) <= 8 {
		return ext
	}
	return "mp3"
}

func formatVariantLabel(codec string, bitrate int, previewOnly bool) string {
	c := strings.ToUpper(strings.TrimSpace(codec))
	if c == "" {
		c = "MP3"
	}
	base := c
	if provider.IsLosslessExt(c) {
		base = c + " 无损"
	} else if bitrate > 0 {
		base = fmt.Sprintf("%s %dk", c, bitrate)
	}
	if previewOnly {
		return base + " (试听)"
	}
	return base
}

func parsePlaylistIssues(raw any) []model.PlaylistIssueDTO {
	arr, ok := raw.([]any)
	if !ok || len(arr) == 0 {
		return []model.PlaylistIssueDTO{}
	}
	out := make([]model.PlaylistIssueDTO, 0, len(arr))
	for _, entry := range arr {
		m, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		out = append(out, model.PlaylistIssueDTO{
			SourceID: strings.TrimSpace(anyToString(firstNonNil(m["sourceId"], m["source_id"]))),
			Title:    strings.TrimSpace(anyToString(m["title"])),
			Artist:   strings.TrimSpace(anyToString(m["artist"])),
			Reason:   strings.TrimSpace(anyToString(m["reason"])),
		})
	}
	return out
}

func extractTrackSlice(m map[string]any, keys ...string) []map[string]any {
	for _, k := range keys {
		if arr, ok := m[k].([]any); ok {
			return sliceAnyToMaps(arr)
		}
	}
	return nil
}

func sliceAnyToMaps(arr []any) []map[string]any {
	out := make([]map[string]any, 0, len(arr))
	for _, item := range arr {
		if m, ok := item.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func firstNonNil(vals ...any) any {
	for _, v := range vals {
		if v != nil {
			return v
		}
	}
	return nil
}

func anyToInt(v any) int {
	switch x := v.(type) {
	case nil:
		return 0
	case int:
		return x
	case int64:
		return int(x)
	case float64:
		return int(x)
	case string:
		n, _ := strconv.Atoi(strings.TrimSpace(x))
		return n
	default:
		return 0
	}
}

func anyToInt64(v any) int64 {
	switch x := v.(type) {
	case nil:
		return 0
	case int64:
		return x
	case int:
		return int64(x)
	case float64:
		return int64(x)
	case string:
		n, _ := strconv.ParseInt(strings.TrimSpace(x), 10, 64)
		return n
	default:
		return 0
	}
}

func anyToStringMap(v any) map[string]string {
	raw, ok := v.(map[string]any)
	if !ok || len(raw) == 0 {
		return nil
	}
	out := make(map[string]string, len(raw))
	for k, val := range raw {
		if strings.TrimSpace(k) != "" && val != nil {
			out[k] = anyToString(val)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// Capability-exact projection helpers ensure Go interface assertions match manifest.Capabilities.

type coreProvider struct {
	p *Provider
}

func (c *coreProvider) ID() string                         { return c.p.ID() }
func (c *coreProvider) EngineName() string                 { return c.p.EngineName() }
func (c *coreProvider) Capabilities() model.Capability     { return c.p.Capabilities() }
func (c *coreProvider) Search(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	return c.p.Search(ctx, opts, emit)
}
func (c *coreProvider) Resolve(ctx context.Context, track model.ProviderTrack, policy model.QualityPolicy) ([]model.ResolvedMedia, error) {
	return c.p.Resolve(ctx, track, policy)
}

type playlistCap struct{ p *Provider }

func (c playlistCap) Playlist(ctx context.Context, locator string, emit func(model.ProviderTrack) error) (model.PlaylistSummary, error) {
	return c.p.Playlist(ctx, locator, emit)
}

type episodesCap struct{ p *Provider }

func (c episodesCap) Episodes(ctx context.Context, track model.ProviderTrack) ([]model.ProviderTrack, error) {
	return c.p.Episodes(ctx, track)
}

type lyricsCap struct{ p *Provider }

func (c lyricsCap) Lyrics(ctx context.Context, track model.ProviderTrack) (string, error) {
	return c.p.Lyrics(ctx, track)
}

type metadataCap struct{ p *Provider }

func (c metadataCap) SearchMetadata(ctx context.Context, query, title, artist, album string, limit int) ([]provider.MetadataItem, error) {
	return c.p.SearchMetadata(ctx, query, title, artist, album, limit)
}

// AsCapabilityProvider returns a model.Provider exposing only the optional interfaces enabled in manifest.Capabilities.
func (p *Provider) AsCapabilityProvider() model.Provider {
	base := &coreProvider{p: p}
	caps := p.compiled.Manifest.Capabilities
	key := (boolBit(caps.Playlist) << 3) | (boolBit(caps.Episodes) << 2) | (boolBit(caps.Lyrics) << 1) | boolBit(caps.Metadata)
	switch key {
	case 0:
		return base
	case 1:
		return struct{ *coreProvider; metadataCap }{base, metadataCap{p}}
	case 2:
		return struct{ *coreProvider; lyricsCap }{base, lyricsCap{p}}
	case 3:
		return struct{ *coreProvider; lyricsCap; metadataCap }{base, lyricsCap{p}, metadataCap{p}}
	case 4:
		return struct{ *coreProvider; episodesCap }{base, episodesCap{p}}
	case 5:
		return struct{ *coreProvider; episodesCap; metadataCap }{base, episodesCap{p}, metadataCap{p}}
	case 6:
		return struct{ *coreProvider; episodesCap; lyricsCap }{base, episodesCap{p}, lyricsCap{p}}
	case 7:
		return struct{ *coreProvider; episodesCap; lyricsCap; metadataCap }{base, episodesCap{p}, lyricsCap{p}, metadataCap{p}}
	case 8:
		return struct{ *coreProvider; playlistCap }{base, playlistCap{p}}
	case 9:
		return struct{ *coreProvider; playlistCap; metadataCap }{base, playlistCap{p}, metadataCap{p}}
	case 10:
		return struct{ *coreProvider; playlistCap; lyricsCap }{base, playlistCap{p}, lyricsCap{p}}
	case 11:
		return struct{ *coreProvider; playlistCap; lyricsCap; metadataCap }{base, playlistCap{p}, lyricsCap{p}, metadataCap{p}}
	case 12:
		return struct{ *coreProvider; playlistCap; episodesCap }{base, playlistCap{p}, episodesCap{p}}
	case 13:
		return struct{ *coreProvider; playlistCap; episodesCap; metadataCap }{base, playlistCap{p}, episodesCap{p}, metadataCap{p}}
	case 14:
		return struct{ *coreProvider; playlistCap; episodesCap; lyricsCap }{base, playlistCap{p}, episodesCap{p}, lyricsCap{p}}
	default:
		return p
	}
}

func boolBit(b bool) int {
	if b {
		return 1
	}
	return 0
}
