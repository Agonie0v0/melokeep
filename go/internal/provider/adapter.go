package provider

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const maxID3ProbeBytes = 1024 * 1024 // 1 MiB cap matching native_engine.probe_audio

// TransportAdapter binds a provider to transport.Pool with immutable per-request
// snapshot, proxy, and account isolation.
type TransportAdapter struct {
	pool *transport.Pool
}

func NewTransportAdapter(pool *transport.Pool) *TransportAdapter {
	if pool == nil {
		pool = transport.NewPool()
	}
	return &TransportAdapter{pool: pool}
}

func (a *TransportAdapter) Pool() *transport.Pool {
	return a.pool
}

type CallOptions struct {
	Headers          map[string]string
	Cookies          map[string]string
	Timeout          time.Duration
	MaxResponseBytes int64
	Idempotent       bool
	HasSideEffects   bool
	MaxRetries       int
	IncludeAuth      bool
}

// DoBytes performs a bounded HTTP call through transport.Pool and returns status, final URL, headers, and body bytes.
func (a *TransportAdapter) DoBytes(ctx context.Context, engineName string, method, rawURL string, body []byte, call CallOptions) (int, string, http.Header, []byte, error) {
	rc := RequestContextFrom(ctx)
	proxyURL, cfgCookies, acct := SourceRequestOptions(rc, engineName)

	domain := transport.DomainPublicMedia
	if call.IncludeAuth && (len(cfgCookies) > 0 || len(call.Cookies) > 0 || acct.AccountID != "") {
		domain = transport.DomainAccountProvider
	}
	timeout := call.Timeout
	if timeout <= 0 {
		if rc.Config.Runtime.SearchTimeoutSeconds > 0 {
			timeout = time.Duration(rc.Config.Runtime.SearchTimeoutSeconds) * time.Second
		} else {
			timeout = 15 * time.Second
		}
	}
	opts := transport.RequestOptions{
		Domain:           domain,
		ConfigRevision:   rc.Config.Revision,
		ProxyURL:         proxyURL,
		Account:          acct,
		Timeout:          timeout,
		MaxResponseBytes: call.MaxResponseBytes,
		Idempotent:       call.Idempotent,
		HasSideEffects:   call.HasSideEffects,
		MaxRetries:       call.MaxRetries,
	}

	mergedHeaders := make(map[string]string)
	for k, v := range rc.Headers {
		mergedHeaders[k] = v
	}
	for k, v := range call.Headers {
		mergedHeaders[k] = v
	}

	var mergedCookies map[string]string
	if call.IncludeAuth {
		mergedCookies = make(map[string]string, len(cfgCookies)+len(call.Cookies))
		for k, v := range cfgCookies {
			mergedCookies[k] = v
		}
		for k, v := range call.Cookies {
			mergedCookies[k] = v
		}
	}

	buildReq := func() (*http.Request, error) {
		var reader io.Reader
		if len(body) > 0 {
			reader = bytes.NewReader(body)
		}
		req, err := http.NewRequestWithContext(ctx, method, rawURL, reader)
		if err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "构造请求失败", err)
		}
		for k, v := range mergedHeaders {
			if v != "" {
				req.Header.Set(k, v)
			}
		}
		if req.Header.Get("User-Agent") == "" {
			req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
		}
		if len(mergedCookies) > 0 && req.Header.Get("Cookie") == "" {
			req.Header.Set("Cookie", FormatCookieHeader(mergedCookies))
		}
		return req, nil
	}

	var resp *http.Response
	var err error
	if call.Idempotent && !call.HasSideEffects && call.MaxRetries > 0 && (method == http.MethodGet || method == http.MethodHead) {
		resp, err = a.pool.DoWithRetry(ctx, buildReq, opts)
	} else {
		var req *http.Request
		req, err = buildReq()
		if err == nil {
			resp, err = a.pool.Do(ctx, req, opts)
		}
	}
	if err != nil {
		return 0, "", nil, nil, ClassifyError(engineName, 0, "", err)
	}
	defer resp.Body.Close()

	raw, readErr := io.ReadAll(resp.Body)
	if readErr != nil {
		finalURL := rawURL
		if resp.Request != nil && resp.Request.URL != nil {
			finalURL = resp.Request.URL.String()
		}
		return resp.StatusCode, finalURL, resp.Header, nil, ClassifyError(engineName, resp.StatusCode, resp.Header.Get("Retry-After"), readErr)
	}
	finalURL := rawURL
	if resp.Request != nil && resp.Request.URL != nil {
		finalURL = resp.Request.URL.String()
	}
	if resp.StatusCode >= 400 {
		return resp.StatusCode, finalURL, resp.Header, raw, ClassifyError(engineName, resp.StatusCode, resp.Header.Get("Retry-After"), nil)
	}
	return resp.StatusCode, finalURL, resp.Header, raw, nil
}

// ProbeResult holds verified stream header inspection output without buffering the whole audio file.
type ProbeResult struct {
	FinalURL  string
	Ext       string
	SizeBytes int64
	Bitrate   int
	Protocol  string
}

var (
	validAudioExts = map[string]bool{
		"mp3": true, "flac": true, "m4a": true, "aac": true, "ogg": true,
		"opus": true, "wav": true, "wma": true, "alac": true, "ape": true,
		"aiff": true, "aif": true, "wv": true, "dsf": true, "dff": true,
		"mp4": true, "webm": true, "m3u8": true,
	}
	mimeToExt = map[string]string{
		"audio/mpeg":                    "mp3",
		"audio/mp3":                     "mp3",
		"audio/flac":                    "flac",
		"audio/x-flac":                  "flac",
		"audio/mp4":                     "m4a",
		"audio/x-m4a":                   "m4a",
		"audio/aac":                     "aac",
		"audio/ogg":                     "ogg",
		"audio/opus":                    "opus",
		"audio/wav":                     "wav",
		"audio/x-wav":                   "wav",
		"audio/dsf":                     "dsf",
		"audio/dff":                     "dff",
		"application/x-mpegurl":         "m3u8",
		"application/vnd.apple.mpegurl": "m3u8",
	}
)

// ProbeAudio validates a playable media URL by reading only the leading audio header bytes.
func (a *TransportAdapter) ProbeAudio(ctx context.Context, engineName, rawURL string, headers, cookies map[string]string, includePlatformAuth bool) (ProbeResult, error) {
	if !strings.HasPrefix(rawURL, "http://") && !strings.HasPrefix(rawURL, "https://") {
		return ProbeResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "无效音频地址")
	}
	rc := RequestContextFrom(ctx)
	proxyURL, cfgCookies, acct := SourceRequestOptions(rc, engineName)
	domain := transport.DomainPublicMedia
	if includePlatformAuth && (len(cfgCookies) > 0 || len(cookies) > 0 || acct.AccountID != "") {
		domain = transport.DomainAccountProvider
	}
	timeout := 15 * time.Second
	if rc.Config.Runtime.PlaybackTimeoutSeconds > 0 {
		timeout = time.Duration(rc.Config.Runtime.PlaybackTimeoutSeconds) * time.Second
		if !includePlatformAuth && timeout > 15*time.Second {
			timeout = 15 * time.Second
		}
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return ProbeResult{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效音频地址", err)
	}
	for k, v := range headers {
		if v != "" {
			req.Header.Set(k, v)
		}
	}
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
	}
	if includePlatformAuth {
		merged := make(map[string]string, len(cfgCookies)+len(cookies))
		for k, v := range cfgCookies {
			merged[k] = v
		}
		for k, v := range cookies {
			merged[k] = v
		}
		if len(merged) > 0 {
			req.Header.Set("Cookie", FormatCookieHeader(merged))
		}
	}

	opts := transport.RequestOptions{
		Domain:              domain,
		ConfigRevision:      rc.Config.Revision,
		ProxyURL:            proxyURL,
		Account:             acct,
		Timeout:             timeout,
		MaxResponseBytes:    -1, // Bounded manually during header read below.
		Idempotent:          true,
		AllowHTTPSDowngrade: true,
	}
	resp, err := a.pool.Do(ctx, req, opts)
	if err != nil {
		return ProbeResult{}, ClassifyError(engineName, 0, "", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return ProbeResult{}, ClassifyError(engineName, resp.StatusCode, resp.Header.Get("Retry-After"), nil)
	}

	buf := make([]byte, 0, 8192)
	tmp := make([]byte, 4096)
	n, readErr := resp.Body.Read(tmp)
	if n > 0 {
		buf = append(buf, tmp[:n]...)
	}
	if len(buf) == 0 && readErr != nil {
		return ProbeResult{}, ClassifyError(engineName, resp.StatusCode, "", readErr)
	}
	trimmed := bytes.TrimLeft(buf, " \t\r\n")
	if len(trimmed) == 0 || trimmed[0] == '<' || trimmed[0] == '{' || trimmed[0] == '[' {
		return ProbeResult{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "解析地址未返回音频")
	}

	finalURL := rawURL
	if resp.Request != nil && resp.Request.URL != nil {
		finalURL = resp.Request.URL.String()
	}
	u, _ := url.Parse(finalURL)
	ext := ""
	if u != nil {
		ext = strings.ToLower(strings.TrimPrefix(path.Ext(u.Path), "."))
	}
	mime := strings.ToLower(strings.TrimSpace(strings.Split(resp.Header.Get("Content-Type"), ";")[0]))
	switch mime {
	case "text/html", "application/json", "application/xml", "text/xml":
		return ProbeResult{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "解析地址返回了非音频内容")
	}

	audioPrefix := buf
	hasID3 := bytes.HasPrefix(buf, []byte("ID3"))
	if hasID3 {
		if ext != "flac" && mime != "audio/flac" && mime != "audio/x-flac" {
			ext = "mp3"
		}
		for len(buf) < 10 && readErr == nil {
			n, readErr = resp.Body.Read(tmp)
			if n > 0 {
				buf = append(buf, tmp[:n]...)
			}
		}
		if len(buf) >= 10 && (buf[6]&0x80) == 0 && (buf[7]&0x80) == 0 && (buf[8]&0x80) == 0 && (buf[9]&0x80) == 0 {
			tagSize := 10 + (int(buf[6])<<21 | int(buf[7])<<14 | int(buf[8])<<7 | int(buf[9]))
			if buf[3] == 4 && (buf[5]&0x10) != 0 {
				tagSize += 10
			}
			if tagSize <= maxID3ProbeBytes {
				for len(buf) < tagSize+16 && readErr == nil {
					n, readErr = resp.Body.Read(tmp)
					if n > 0 {
						buf = append(buf, tmp[:n]...)
					}
				}
				if len(buf) > tagSize {
					audioPrefix = buf[tagSize:]
				}
			}
		}
	}

	recognized := hasID3
	sigs := []struct {
		prefix []byte
		ext    string
	}{
		{[]byte("fLaC"), "flac"},
		{[]byte("RIFF"), "wav"},
		{[]byte("OggS"), "ogg"},
		{[]byte("DSD "), "dsf"},
		{[]byte("FRM8"), "dff"},
		{[]byte("#EXTM3U"), "m3u8"},
	}
	for _, sig := range sigs {
		if bytes.HasPrefix(audioPrefix, sig.prefix) {
			ext = sig.ext
			recognized = true
			break
		}
	}
	if !recognized && len(audioPrefix) >= 2 && audioPrefix[0] == 0xFF && (audioPrefix[1]&0xE0) == 0xE0 {
		if (audioPrefix[1] & 0xF6) == 0xF0 {
			ext = "aac"
		} else {
			ext = "mp3"
		}
		recognized = true
	}
	if !recognized && len(audioPrefix) >= 8 && string(audioPrefix[4:8]) == "ftyp" {
		ext = "m4a"
		recognized = true
	}
	if mime == "text/plain" && !recognized {
		return ProbeResult{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "解析地址返回了纯文本错误")
	}
	if !validAudioExts[ext] {
		ext = mimeToExt[mime]
	}
	if ext == "" {
		return ProbeResult{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "无法确认音频格式")
	}
	var sizeBytes int64
	if cl := strings.TrimSpace(resp.Header.Get("Content-Length")); cl != "" {
		if parsed, err := strconv.ParseInt(cl, 10, 64); err == nil && parsed > 0 {
			sizeBytes = parsed
		}
	}
	protocol := "HTTP"
	if ext == "m3u8" || (u != nil && strings.Contains(strings.ToLower(u.Path), ".m3u8")) {
		protocol = "HLS"
		ext = "m4a"
		sizeBytes = 0
	}
	return ProbeResult{
		FinalURL:  finalURL,
		Ext:       ext,
		SizeBytes: sizeBytes,
		Protocol:  protocol,
	}, nil
}
