package provider

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"math"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type reqCtxKey struct{}

// LogFunc records runtime diagnostic events without coupling providers to HTTP transport packages.
// Signature matches httpapi.LogBuffer.Push: (level, message, category, taskID).
type LogFunc func(level, message, category, taskID string)

// EpisodeProvider is optionally implemented by podcast or audiobook providers that expand
// a parent album/book candidate into concrete playable episode tracks.
type EpisodeProvider interface {
	Episodes(ctx context.Context, track model.ProviderTrack) ([]model.ProviderTrack, error)
}

// MetadataItem represents a structured metadata enrichment candidate returned by a MetadataProvider.
type MetadataItem struct {
	SourceID    string  `json:"source_id"`
	Title       string  `json:"title"`
	Artist      string  `json:"artist"`
	Album       string  `json:"album"`
	AlbumArtist string  `json:"album_artist,omitempty"`
	Date        string  `json:"date,omitempty"`
	TrackNumber string  `json:"track_number,omitempty"`
	DiscNumber  string  `json:"disc_number,omitempty"`
	Duration    float64 `json:"duration"`
	CoverURL    string  `json:"cover_url,omitempty"`
	Lyrics      string  `json:"lyrics,omitempty"`
}

// LyricsProvider is optionally implemented by providers that fetch lyrics for a track.
type LyricsProvider interface {
	Lyrics(ctx context.Context, track model.ProviderTrack) (string, error)
}

// MetadataProvider is optionally implemented by providers that search structured track metadata.
type MetadataProvider interface {
	SearchMetadata(ctx context.Context, query string, title, artist, album string, limit int) ([]MetadataItem, error)
}

// RequestContext carries immutable per-request configuration, proxy, and account
// credentials without mutating shared Provider instances.
type RequestContext struct {
	Config   config.Snapshot
	Account  transport.AccountKey
	ProxyURL string
	Cookies  map[string]string
	Headers  map[string]string
	PageSize int
	MaxWait  time.Duration
	NoCache  bool
	TaskID   string
	LogFunc  LogFunc
	// AllowExternalWrite is granted only by an already-authorized download or
	// explicit external-write operation. Provider Resolve must remain read-only
	// by default.
	AllowExternalWrite bool
}

// Log emits a diagnostic entry to the context's LogFunc if configured.
func Log(ctx context.Context, level, category, message string) {
	if ctx == nil || message == "" {
		return
	}
	rc := RequestContextFrom(ctx)
	if rc.LogFunc != nil {
		rc.LogFunc(level, message, category, rc.TaskID)
	}
}

// WithRequestContext attaches an immutable RequestContext to ctx.
func WithRequestContext(ctx context.Context, rc RequestContext) context.Context {
	return context.WithValue(ctx, reqCtxKey{}, cloneRequestContext(rc))
}

// RequestContextFrom extracts RequestContext from ctx or returns a default snapshot context.
func RequestContextFrom(ctx context.Context) RequestContext {
	if ctx != nil {
		if rc, ok := ctx.Value(reqCtxKey{}).(RequestContext); ok {
			return cloneRequestContext(rc)
		}
	}
	return RequestContext{
		Config: config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets()),
	}
}

func cloneRequestContext(rc RequestContext) RequestContext {
	out := rc
	if len(rc.Cookies) > 0 {
		out.Cookies = make(map[string]string, len(rc.Cookies))
		for k, v := range rc.Cookies {
			out.Cookies[k] = v
		}
	}
	if len(rc.Headers) > 0 {
		out.Headers = make(map[string]string, len(rc.Headers))
		for k, v := range rc.Headers {
			out.Headers[k] = v
		}
	}
	return out
}

// SourceRequestOptions resolves proxy, cookies, and account isolation key for a provider
// from an immutable RequestContext.
func SourceRequestOptions(rc RequestContext, engineName string) (proxyURL string, cookies map[string]string, account transport.AccountKey) {
	cookies = make(map[string]string)
	for k, v := range rc.Cookies {
		cookies[k] = v
	}
	pubID := config.PublicSourceID(engineName)
	for _, key := range []string{engineName, pubID, strings.ToLower(engineName), strings.ToLower(pubID)} {
		if setting, ok := rc.Config.Runtime.SourceSettings[key]; ok {
			if setting.Cookie != "" && setting.Cookie != config.HiddenPlaceholder {
				for k, v := range ParseCookieString(setting.Cookie) {
					if _, exists := cookies[k]; !exists {
						cookies[k] = v
					}
				}
			}
			if proxyURL == "" && setting.Proxy != "" && setting.Proxy != config.HiddenPlaceholder {
				proxyURL = setting.Proxy
			}
		}
	}
	if proxyURL == "" {
		proxyURL = rc.ProxyURL
	}
	if proxyURL == "" && rc.Config.Runtime.ProxyURL != "" && rc.Config.Runtime.ProxyURL != config.HiddenPlaceholder {
		scope := strings.ToLower(strings.TrimSpace(rc.Config.Runtime.ProxyScope))
		if scope == "" || scope == "all" {
			proxyURL = rc.Config.Runtime.ProxyURL
		} else {
			for _, s := range rc.Config.Runtime.ProxySources {
				if strings.EqualFold(s, engineName) || strings.EqualFold(s, pubID) {
					proxyURL = rc.Config.Runtime.ProxyURL
					break
				}
			}
		}
	}
	account = rc.Account
	if account.Source == "" {
		account.Source = engineName
	}
	return proxyURL, cookies, account
}

// ParseCookieString parses a standard HTTP Cookie header string into key/value pairs.
func ParseCookieString(raw string) map[string]string {
	out := make(map[string]string)
	for _, part := range strings.Split(raw, ";") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		k, v, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		k = strings.TrimSpace(k)
		v = strings.TrimSpace(v)
		if k != "" {
			out[k] = v
		}
	}
	return out
}

// FormatCookieHeader builds a deterministic Cookie header value.
func FormatCookieHeader(cookies map[string]string) string {
	if len(cookies) == 0 {
		return ""
	}
	keys := make([]string, 0, len(cookies))
	for k := range cookies {
		if strings.TrimSpace(k) != "" {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		parts = append(parts, k+"="+cookies[k])
	}
	return strings.Join(parts, "; ")
}

var (
	htmlTagRe    = regexp.MustCompile(`<[^>]+>`)
	jsonpFuncRe  = regexp.MustCompile(`(?s)^[\w.$]+\((.*)\)\s*;?$`)
	losslessExts = map[string]bool{
		"flac": true, "alac": true, "ape": true, "wav": true,
		"aiff": true, "aif": true, "wv": true, "dsd": true,
		"dsf": true, "dff": true,
	}
)

// IsLosslessExt reports whether ext is a known lossless audio container/codec.
func IsLosslessExt(ext string) bool {
	return losslessExts[strings.ToLower(strings.TrimPrefix(strings.TrimSpace(ext), "."))]
}

// CleanText strips HTML tags and unescapes entities identically to native_providers._clean_text.
func CleanText(s string) string {
	stripped := htmlTagRe.ReplaceAllString(s, "")
	return strings.TrimSpace(html.UnescapeString(stripped))
}

// ParseDurationSeconds converts numeric or "HH:MM:SS" duration strings into seconds.
func ParseDurationSeconds(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case float32:
		return float64(x)
	case int:
		return float64(x)
	case int64:
		return float64(x)
	case json.Number:
		f, _ := x.Float64()
		return f
	case string:
		text := strings.TrimSpace(x)
		if text == "" {
			return 0
		}
		if strings.Contains(text, ":") {
			parts := strings.Split(text, ":")
			var total float64
			mult := 1.0
			for i := len(parts) - 1; i >= 0; i-- {
				p, err := strconv.ParseFloat(strings.TrimSpace(parts[i]), 64)
				if err != nil {
					return 0
				}
				total += p * mult
				mult *= 60
			}
			return total
		}
		f, err := strconv.ParseFloat(text, 64)
		if err != nil {
			return 0
		}
		return f
	default:
		return 0
	}
}

// ParseSafeJSON strips optional JSONP wrappers before decoding JSON.
func ParseSafeJSON(raw []byte, dst any) error {
	text := strings.TrimSpace(string(raw))
	if text == "" {
		return model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "上游返回空响应")
	}
	if strings.HasPrefix(text, "(") && strings.HasSuffix(text, ")") {
		text = text[1 : len(text)-1]
	}
	if m := jsonpFuncRe.FindStringSubmatch(text); len(m) == 2 {
		text = m[1]
	}
	if err := json.Unmarshal([]byte(text), dst); err != nil {
		return model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "上游 JSON 解析失败", err)
	}
	return nil
}

// IsTrialAudio detects short trial or truncated audio clips based on file size and duration.
func IsTrialAudio(_ string, fileSizeBytes int64, durationS float64, minBPS float64) bool {
	if minBPS <= 0 {
		minBPS = 250000
	}
	if durationS > 60 && fileSizeBytes > 0 && float64(fileSizeBytes)*8 < minBPS*durationS {
		return true
	}
	return false
}

// ClassifyError maps upstream HTTP statuses and transport/context errors to DomainError.
func ClassifyError(providerID string, status int, retryAfterHeader string, err error) *model.DomainError {
	if err != nil {
		if de, ok := model.AsDomainError(err); ok {
			cp := *de
			if cp.Provider == "" {
				cp.Provider = providerID
			}
			return &cp
		}
		if errors.Is(err, context.Canceled) {
			return &model.DomainError{
				Code:     model.ErrCancelled,
				Status:   499,
				Detail:   "请求已取消",
				Provider: providerID,
				Cause:    err,
			}
		}
		if errors.Is(err, context.DeadlineExceeded) {
			return &model.DomainError{
				Code:      model.ErrDeadline,
				Status:    http.StatusGatewayTimeout,
				Detail:    "上游音源请求超时",
				Provider:  providerID,
				Retryable: true,
				Cause:     err,
			}
		}
		return &model.DomainError{
			Code:      model.ErrTransport,
			Status:    http.StatusBadGateway,
			Detail:    "上游音源网络异常",
			Provider:  providerID,
			Retryable: true,
			Cause:     err,
		}
	}
	switch {
	case status == http.StatusTooManyRequests:
		ra := parseRetryAfter(retryAfterHeader)
		if ra <= 0 {
			ra = 5
		}
		return &model.DomainError{
			Code:       model.ErrRateLimited,
			Status:     http.StatusTooManyRequests,
			Detail:     "上游音源触发限流",
			Provider:   providerID,
			RetryAfter: ra,
			Retryable:  true,
		}
	case status == http.StatusUnauthorized || status == http.StatusForbidden:
		return &model.DomainError{
			Code:     model.ErrAuthRequired,
			Status:   status,
			Detail:   "上游音源需要有效凭据或无访问权限",
			Provider: providerID,
		}
	case status == http.StatusNotImplemented:
		return &model.DomainError{
			Code:     model.ErrUnsupported,
			Status:   http.StatusBadRequest,
			Detail:   "上游音源不支持该操作",
			Provider: providerID,
		}
	case status >= 500:
		return &model.DomainError{
			Code:      model.ErrUnavailable,
			Status:    http.StatusServiceUnavailable,
			Detail:    "上游音源服务暂不可用",
			Provider:  providerID,
			Retryable: true,
		}
	case status >= 400:
		return &model.DomainError{
			Code:     model.ErrInvalidResponse,
			Status:   http.StatusBadGateway,
			Detail:   "上游音源返回错误响应",
			Provider: providerID,
		}
	default:
		return nil
	}
}

func parseRetryAfter(h string) int {
	h = strings.TrimSpace(h)
	if h == "" {
		return 0
	}
	if sec, err := strconv.Atoi(h); err == nil && sec > 0 {
		return sec
	}
	return 0
}

// MakeVariantID derives a deterministic, collision-resistant variant identifier scoped to track and stream identity.
func MakeVariantID(source, sourceID, upstreamID, codec string, bitrate int, previewOnly bool, sizeBytes int64) string {
	raw := fmt.Sprintf("%s|%s|%s|%s|%d|%v|%d",
		strings.ToLower(strings.TrimSpace(source)),
		strings.TrimSpace(sourceID),
		strings.TrimSpace(upstreamID),
		strings.ToLower(strings.TrimPrefix(strings.TrimSpace(codec), ".")),
		bitrate,
		previewOnly,
		sizeBytes,
	)
	sum := sha256.Sum256([]byte(raw))
	return "var_" + hex.EncodeToString(sum[:8])
}

// NewMediaVariant constructs a persistent MediaVariant without URLs, expiry, or credentials.
// Lossless is only marked true when audio format is verified or explicitly evidenced as lossless.
func NewMediaVariant(variantID, upstreamID, label, codec string, bitrate int, verified bool, previewOnly bool, protocol string, sizeBytes *int64, durationS float64) model.MediaVariant {
	normCodec := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(codec), "."))
	var brPtr *int
	if bitrate > 0 {
		b := bitrate
		brPtr = &b
	}
	lossless := false
	evidence := "declared"
	availability := "candidate"
	if verified {
		availability = "verified"
		evidence = "probed_audio"
		lossless = IsLosslessExt(normCodec)
	} else if IsLosslessExt(normCodec) {
		// Unverified lossless extensions cannot be asserted as verified lossless.
		evidence = "unverified_extension"
	}
	if previewOnly {
		availability = "preview"
	}
	return model.MediaVariant{
		VariantID:    variantID,
		UpstreamID:   upstreamID,
		Label:        label,
		Codec:        normCodec,
		Bitrate:      brPtr,
		Lossless:     lossless,
		Availability: availability,
		Evidence:     evidence,
		PreviewOnly:  previewOnly,
		Protocol:     protocol,
		SizeBytes:    sizeBytes,
		Duration:     int64(durationS),
	}
}

func PtrString(s string) *string {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	return &s
}

func PtrInt64Positive(v int64) *int64 {
	if v <= 0 {
		return nil
	}
	return &v
}

// MatchQualityPolicy checks whether a candidate variant satisfies the requested QualityPolicy.
func MatchQualityPolicy(v model.MediaVariant, policy model.QualityPolicy) bool {
	if v.PreviewOnly && !policy.AllowPreview {
		return false
	}
	if policy.PreferredVariantID != "" && v.VariantID != policy.PreferredVariantID {
		return false
	}
	if policy.PreferredQuality != "" {
		pq := strings.ToLower(strings.TrimSpace(policy.PreferredQuality))
		switch pq {
		case "lossless", "flac":
			return v.Lossless || strings.EqualFold(v.Codec, "flac")
		case "320", "exhigh", "hq":
			return v.Lossless || (v.Bitrate != nil && *v.Bitrate >= 320)
		}
	}
	return true
}

// AudioRankBetter compares two ResolvedMedia items identically to provider_compat.audio_rank:
// (is_lossless, size_bytes, bitrate).
func AudioRankBetter(a, b model.ResolvedMedia) bool {
	aLossless := IsLosslessExt(a.Codec)
	bLossless := IsLosslessExt(b.Codec)
	if aLossless != bLossless {
		return aLossless
	}
	var aSize, bSize int64
	if a.SizeBytes != nil {
		aSize = *a.SizeBytes
	}
	if b.SizeBytes != nil {
		bSize = *b.SizeBytes
	}
	if aSize != bSize {
		return aSize > bSize
	}
	var aBr, bBr int
	if a.Bitrate != nil {
		aBr = *a.Bitrate
	}
	if b.Bitrate != nil {
		bBr = *b.Bitrate
	}
	return aBr > bBr
}

// NormalizeResolvedBitrate computes an accurate bitrate in kbps from probe metadata,
// media size/duration, or candidate hints while stripping lossless placeholder values such as 999.
func NormalizeResolvedBitrate(ext string, probedBitrate, candidateBitrate int, sizeBytes int64, durationS float64) int {
	if probedBitrate > 0 {
		return probedBitrate
	}
	normExt := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(ext), "."))
	if IsLosslessExt(normExt) {
		if sizeBytes > 0 && durationS > 0 {
			est := int(math.Round(float64(sizeBytes) * 8.0 / durationS / 1000.0))
			if est < 1 {
				est = 1
			}
			return est
		}
		if candidateBitrate >= 900 || candidateBitrate <= 0 {
			return 0
		}
		return candidateBitrate
	}
	if candidateBitrate >= 500 {
		if sizeBytes > 0 && durationS > 0 {
			capBr := 512
			if normExt == "mp3" {
				capBr = 320
			}
			est := int(math.Round(float64(sizeBytes) * 8.0 / durationS / 1000.0))
			if est < 32 {
				est = 32
			} else if est > capBr {
				est = capBr
			}
			return est
		}
		return 0
	}
	return candidateBitrate
}
