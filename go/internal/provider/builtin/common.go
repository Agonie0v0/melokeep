package builtin

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const defaultResolvedTTL = 15 * time.Minute

// Options configures built-in compliant HTTP providers.
type Options struct {
	Pool *transport.Pool
	// Optional endpoint overrides for deterministic offline fixture tests.
	Endpoints map[string]string
}

func endpointOr(opts Options, key, def string) string {
	if opts.Endpoints != nil {
		if v, ok := opts.Endpoints[key]; ok && strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return def
}

type mediaCandidate struct {
	URL         string
	Codec       string
	Bitrate     int
	PreviewOnly bool
	Headers     map[string]string
	Cookies     map[string]string
	IncludeAuth bool
	Label       string
	UpstreamID  string
}

func qualityLabel(codec string, bitrate int, previewOnly bool) string {
	c := strings.ToUpper(strings.TrimPrefix(strings.TrimSpace(codec), "."))
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

func probeCandidates(
	ctx context.Context,
	adapter *provider.TransportAdapter,
	engineName string,
	track model.ProviderTrack,
	policy model.QualityPolicy,
	candidates []mediaCandidate,
	minTrialBPS float64,
) ([]model.ResolvedMedia, error) {
	var out []model.ResolvedMedia
	seenURLs := make(map[string]bool)
	seenQuality := make(map[string]bool)
	var lastErr error
	sawTrialOnly := false

	for _, cand := range candidates {
		if err := ctx.Err(); err != nil {
			return nil, provider.ClassifyError(engineName, 0, "", err)
		}
		u := strings.TrimSpace(cand.URL)
		if u == "" || seenURLs[u] {
			continue
		}
		seenURLs[u] = true

		probe, err := adapter.ProbeAudio(ctx, engineName, u, cand.Headers, cand.Cookies, cand.IncludeAuth)
		if err != nil {
			if de, ok := model.AsDomainError(err); ok && de.Code == model.ErrCancelled {
				return nil, de
			}
			lastErr = err
			continue
		}

		ext := probe.Ext
		if ext == "" {
			ext = strings.ToLower(strings.TrimPrefix(cand.Codec, "."))
		}
		br := provider.NormalizeResolvedBitrate(ext, probe.Bitrate, cand.Bitrate, probe.SizeBytes, track.Duration)

		isTrial := cand.PreviewOnly || provider.IsTrialAudio(probe.FinalURL, probe.SizeBytes, track.Duration, minTrialBPS)
		if isTrial && !policy.AllowPreview {
			sawTrialOnly = true
			continue
		}

		var sizePtr *int64
		if probe.SizeBytes > 0 {
			s := probe.SizeBytes
			sizePtr = &s
		}
		var brPtr *int
		if br > 0 {
			b := br
			brPtr = &b
		}

		vid := provider.MakeVariantID(track.Source, track.SourceID, cand.UpstreamID, ext, br, isTrial, probe.SizeBytes)
		label := cand.Label
		if label == "" {
			label = qualityLabel(ext, br, isTrial)
		}
		variant := provider.NewMediaVariant(vid, cand.UpstreamID, label, ext, br, true, isTrial, probe.Protocol, sizePtr, track.Duration)
		if !provider.MatchQualityPolicy(variant, policy) {
			continue
		}

		qKey := fmt.Sprintf("%s|%d|%d|%v", ext, br, probe.SizeBytes, isTrial)
		if seenQuality[qKey] {
			continue
		}
		seenQuality[qKey] = true

		var headersCopy map[string]string
		if cand.IncludeAuth && len(cand.Headers) > 0 {
			headersCopy = cloneMap(cand.Headers)
		}
		var cookiesCopy map[string]string
		if cand.IncludeAuth && len(cand.Cookies) > 0 {
			cookiesCopy = cloneMap(cand.Cookies)
		}

		out = append(out, model.ResolvedMedia{
			VariantID:   vid,
			Variant:     variant,
			URL:         probe.FinalURL,
			Expiry:      time.Now().UTC().Add(defaultResolvedTTL),
			Headers:     headersCopy,
			Cookies:     cookiesCopy,
			PreviewOnly: isTrial,
			Codec:       ext,
			Bitrate:     brPtr,
			SizeBytes:   sizePtr,
		})
	}

	if len(out) == 0 {
		if sawTrialOnly {
			return nil, model.NewError(model.ErrAuthRequired, http.StatusUnauthorized, "仅检测到试听片段，需要允许试听或更高权限凭据")
		}
		if lastErr != nil {
			return nil, lastErr
		}
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "未能解析到可用音频地址")
	}

	sort.SliceStable(out, func(i, j int) bool {
		if out[i].PreviewOnly != out[j].PreviewOnly {
			return !out[i].PreviewOnly
		}
		return provider.AudioRankBetter(out[i], out[j])
	})
	return out, nil
}

func emitSearchResults(ctx context.Context, opts model.SearchOptions, rows []model.ProviderTrack, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
	target := targetLimit(ctx, opts)
	seen := make(map[string]bool)
	count := 0
	for _, tr := range rows {
		if err := ctx.Err(); err != nil {
			return model.SearchSummary{Count: count}, provider.ClassifyError(tr.RootSource, 0, "", err)
		}
		key := tr.SourceID + "\x00" + tr.Title + "\x00" + tr.Artist
		if seen[key] {
			continue
		}
		seen[key] = true
		if err := emit(tr); err != nil {
			return model.SearchSummary{Count: count}, err
		}
		count++
		if count >= target {
			break
		}
	}
	return model.SearchSummary{Count: count}, nil
}

func targetLimit(ctx context.Context, opts model.SearchOptions) int {
	if opts.Limit > 0 {
		return opts.Limit
	}
	rc := provider.RequestContextFrom(ctx)
	if rc.Config.Runtime.ResultsPerSource > 0 {
		return rc.Config.Runtime.ResultsPerSource
	}
	return 5
}

func newCandidateTrack(publicSource, engineName, sourceID, title, artist, album string, durationS float64, coverURL string, playlistIdx *int) model.ProviderTrack {
	return model.ProviderTrack{
		PublicID:      model.NewUUID(),
		Source:        publicSource,
		SourceID:      strings.TrimSpace(sourceID),
		RootSource:    engineName,
		Title:         provider.CleanText(title),
		Artist:        provider.CleanText(artist),
		Album:         provider.CleanText(album),
		Duration:      durationS,
		Cover:         provider.PtrString(coverURL),
		PlaylistIndex: playlistIdx,
		Variants:      []model.MediaVariant{},
	}
}

func cloneMap(m map[string]string) map[string]string {
	if len(m) == 0 {
		return nil
	}
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if s := strings.TrimSpace(v); s != "" {
			return s
		}
	}
	return ""
}

func fmtAnyID(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return strings.TrimSpace(x)
	case float64:
		return strconv.FormatInt(int64(x), 10)
	case int64:
		return strconv.FormatInt(x, 10)
	case int:
		return strconv.Itoa(x)
	case json.Number:
		return x.String()
	default:
		return strings.TrimSpace(fmt.Sprint(x))
	}
}

func sourceCookieOrSetting(ctx context.Context, engineName string, keys ...string) string {
	rc := provider.RequestContextFrom(ctx)
	_, cookies, _ := provider.SourceRequestOptions(rc, engineName)
	for _, k := range keys {
		if v := strings.TrimSpace(cookies[k]); v != "" {
			return v
		}
		if v := strings.TrimSpace(rc.Cookies[k]); v != "" {
			return v
		}
		if v := strings.TrimSpace(rc.Headers[k]); v != "" {
			return v
		}
	}
	return ""
}

func hasXMLUnsafeDecl(raw []byte) bool {
	upper := bytes.ToUpper(raw)
	return bytes.Contains(upper, []byte("<!DOCTYPE")) || bytes.Contains(upper, []byte("<!ENTITY"))
}
