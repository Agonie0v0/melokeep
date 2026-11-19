package plugin

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/transport"
	"github.com/dop251/goja"
)

const (
	// MaxHTTPRequestsPerCall caps total HTTP requests (including batch expansion) per plugin call.
	MaxHTTPRequestsPerCall = 24
	// MaxHTTPBatchSize caps a single ctx.http.batch call.
	MaxHTTPBatchSize = 8
	// MaxHTTPResponseBytes caps each HTTP response body at 4 MiB.
	MaxHTTPResponseBytes = 4 << 20
	// MaxSingleHTTPTimeout caps per-request timeoutMs at 15 seconds.
	MaxSingleHTTPTimeout = 15 * time.Second

	defaultPluginUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
)

// ValidateAndNormalizeOrigins validates an allowed_origins list and returns canonicalized unique origins.
func ValidateAndNormalizeOrigins(origins []string) ([]string, error) {
	if len(origins) == 0 {
		return []string{}, nil
	}
	out := make([]string, 0, len(origins))
	seen := make(map[string]bool, len(origins))
	for _, raw := range origins {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			continue
		}
		u, err := url.Parse(trimmed)
		if err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("allowed_origins 格式无效: %q", trimmed), err)
		}
		scheme := strings.ToLower(u.Scheme)
		host := strings.ToLower(u.Hostname())
		if (scheme != "http" && scheme != "https") || host == "" || u.User != nil {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("allowed_origins 仅支持不含凭据的 http/https Origin: %q", trimmed))
		}
		if (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("allowed_origins 不能包含路径或查询参数: %q", trimmed))
		}
		port := u.Port()
		var canonical string
		if port != "" {
			canonical = fmt.Sprintf("%s://%s", scheme, net.JoinHostPort(host, port))
		} else if strings.Contains(host, ":") {
			canonical = fmt.Sprintf("%s://[%s]", scheme, host)
		} else {
			canonical = fmt.Sprintf("%s://%s", scheme, host)
		}
		key := originKey(u)
		if !seen[key] {
			seen[key] = true
			out = append(out, canonical)
		}
	}
	return out, nil
}

func originKey(u *url.URL) string {
	scheme := strings.ToLower(u.Scheme)
	host := strings.ToLower(u.Hostname())
	port := u.Port()
	if port == "" {
		if scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}
	return fmt.Sprintf("%s://%s:%s", scheme, host, port)
}

func buildOriginSet(origins []string) map[string]bool {
	out := make(map[string]bool, len(origins))
	for _, raw := range origins {
		if u, err := url.Parse(strings.TrimSpace(raw)); err == nil && u.Hostname() != "" {
			out[originKey(u)] = true
		}
	}
	return out
}

type httpBridge struct {
	ctx               context.Context
	pool              *transport.Pool
	rc                provider.RequestContext
	pluginID          string
	allowedOrigins    map[string]bool
	allowedOriginList []string
	reqCount          atomic.Int32
}

type httpReqSpec struct {
	URL       string
	Method    string
	Headers   map[string]string
	Body      []byte
	TimeoutMs int64
}

type httpResponseData struct {
	Status   int
	FinalURL string
	Headers  map[string]string
	Body     []byte
}

func newHTTPBridge(ctx context.Context, pool *transport.Pool, rc provider.RequestContext, pluginID string, allowedOrigins []string) *httpBridge {
	if pool == nil {
		pool = transport.NewPool()
	}
	normOrigins, err := ValidateAndNormalizeOrigins(allowedOrigins)
	if err != nil {
		normOrigins = nil
	}
	return &httpBridge{
		ctx:               ctx,
		pool:              pool,
		rc:                rc,
		pluginID:          pluginID,
		allowedOrigins:    buildOriginSet(normOrigins),
		allowedOriginList: normOrigins,
	}
}

func (b *httpBridge) install(vm *goja.Runtime, ctxObj *goja.Object) {
	httpObj := vm.NewObject()
	_ = httpObj.Set("request", func(call goja.FunctionCall) goja.Value {
		if len(call.Arguments) == 0 {
			panic(vm.NewGoError(model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "ctx.http.request 缺少请求参数")))
		}
		spec, err := parseHTTPReqSpec(vm, call.Arguments[0])
		if err != nil {
			panic(vm.NewGoError(err))
		}
		res, err := b.doOne(spec)
		if err != nil {
			panic(vm.NewGoError(err))
		}
		return b.toJSResponse(vm, res)
	})

	_ = httpObj.Set("batch", func(call goja.FunctionCall) goja.Value {
		if len(call.Arguments) == 0 || isNilOrUndefined(call.Arguments[0]) {
			return vm.NewArray()
		}
		arrObj, ok := call.Arguments[0].(*goja.Object)
		if !ok || arrObj.ClassName() != "Array" {
			panic(vm.NewGoError(model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "ctx.http.batch 参数必须为数组")))
		}
		length := int(arrObj.Get("length").ToInteger())
		if length > MaxHTTPBatchSize {
			panic(vm.NewGoError(model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("ctx.http.batch 单批最多支持 %d 个请求", MaxHTTPBatchSize))))
		}
		if length == 0 {
			return vm.NewArray()
		}

		specs := make([]httpReqSpec, length)
		parseErrs := make([]error, length)
		for i := 0; i < length; i++ {
			specs[i], parseErrs[i] = parseHTTPReqSpec(vm, arrObj.GetIdx(int64(i)))
		}

		results := make([]httpResponseData, length)
		errs := make([]error, length)
		var wg sync.WaitGroup
		for i := 0; i < length; i++ {
			if parseErrs[i] != nil {
				errs[i] = parseErrs[i]
				continue
			}
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				results[idx], errs[idx] = b.doOne(specs[idx])
			}(i)
		}
		wg.Wait()

		if b.ctx.Err() != nil {
			panic(vm.NewGoError(mapContextErr(b.ctx.Err())))
		}

		jsItems := make([]any, length)
		for i := 0; i < length; i++ {
			if errs[i] != nil {
				errObj := vm.NewObject()
				_ = errObj.Set("error", errs[i].Error())
				jsItems[i] = errObj
				continue
			}
			jsItems[i] = b.toJSResponse(vm, results[i])
		}
		return vm.NewArray(jsItems...)
	})

	_ = ctxObj.Set("http", httpObj)
}

func parseHTTPReqSpec(vm *goja.Runtime, v goja.Value) (httpReqSpec, error) {
	if isNilOrUndefined(v) {
		return httpReqSpec{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "HTTP 请求参数不能为空")
	}
	if s, ok := v.Export().(string); ok {
		return httpReqSpec{URL: strings.TrimSpace(s), Method: http.MethodGet}, nil
	}
	obj, ok := v.(*goja.Object)
	if !ok {
		return httpReqSpec{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "HTTP 请求参数必须为对象")
	}

	rawURL := strings.TrimSpace(readJSString(obj, "url"))
	if rawURL == "" {
		return httpReqSpec{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "HTTP 请求缺少 url")
	}
	method := strings.ToUpper(strings.TrimSpace(readJSString(obj, "method")))
	if method == "" {
		method = http.MethodGet
	}
	switch method {
	case http.MethodGet, http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodHead, http.MethodPatch:
	default:
		return httpReqSpec{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的 HTTP 方法: %s", method))
	}

	headers := make(map[string]string)
	if hVal := obj.Get("headers"); !isNilOrUndefined(hVal) {
		var rawMap map[string]any
		if err := vm.ExportTo(hVal, &rawMap); err == nil {
			for k, val := range rawMap {
				if strings.TrimSpace(k) != "" && val != nil {
					headers[k] = anyToString(val)
				}
			}
		}
	}

	var bodyBytes []byte
	if bVal := obj.Get("body"); !isNilOrUndefined(bVal) {
		switch bv := bVal.Export().(type) {
		case string:
			bodyBytes = []byte(bv)
		case []byte:
			bodyBytes = bv
		case goja.ArrayBuffer:
			bodyBytes = bv.Bytes()
		default:
			if encoded, err := json.Marshal(bv); err == nil {
				bodyBytes = encoded
			} else {
				bodyBytes = []byte(bVal.String())
			}
		}
	}

	var timeoutMs int64
	if tVal := obj.Get("timeoutMs"); !isNilOrUndefined(tVal) {
		timeoutMs = tVal.ToInteger()
	}

	return httpReqSpec{
		URL:       rawURL,
		Method:    method,
		Headers:   headers,
		Body:      bodyBytes,
		TimeoutMs: timeoutMs,
	}, nil
}

func (b *httpBridge) doOne(spec httpReqSpec) (httpResponseData, error) {
	if err := b.ctx.Err(); err != nil {
		return httpResponseData{}, mapContextErr(err)
	}
	if b.reqCount.Add(1) > MaxHTTPRequestsPerCall {
		return httpResponseData{}, model.NewError(model.ErrRateLimited, http.StatusTooManyRequests, fmt.Sprintf("单次插件调用 HTTP 请求总数超过上限 (%d)", MaxHTTPRequestsPerCall))
	}

	parsed, err := url.Parse(strings.TrimSpace(spec.URL))
	if err != nil {
		return httpResponseData{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的请求 URL", err)
	}
	scheme := strings.ToLower(parsed.Scheme)
	if (scheme != "http" && scheme != "https") || parsed.Hostname() == "" || parsed.User != nil {
		return httpResponseData{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "仅允许不含凭据的 http/https 请求")
	}

	domain := transport.DomainPublicMedia
	var allowedInternal []string
	if b.allowedOrigins[originKey(parsed)] {
		domain = transport.DomainConfiguredInternal
		allowedInternal = b.allowedOriginList
	}

	timeout := MaxSingleHTTPTimeout
	if spec.TimeoutMs > 0 {
		reqTimeout := time.Duration(spec.TimeoutMs) * time.Millisecond
		if reqTimeout < timeout {
			timeout = reqTimeout
		}
	}

	proxyURL, cfgCookies, acct := provider.SourceRequestOptions(b.rc, b.pluginID)

	var reader io.Reader
	if len(spec.Body) > 0 {
		reader = bytes.NewReader(spec.Body)
	}
	req, err := http.NewRequestWithContext(b.ctx, spec.Method, parsed.String(), reader)
	if err != nil {
		return httpResponseData{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "构造 HTTP 请求失败", err)
	}

	// Apply default User-Agent, global headers, and plugin cookies first so explicit req.headers take precedence.
	req.Header.Set("User-Agent", defaultPluginUserAgent)
	for k, v := range b.rc.Headers {
		if strings.TrimSpace(k) != "" && v != "" {
			req.Header.Set(k, v)
		}
	}
	if len(cfgCookies) > 0 {
		req.Header.Set("Cookie", provider.FormatCookieHeader(cfgCookies))
	}
	for k, v := range spec.Headers {
		if strings.TrimSpace(k) != "" {
			req.Header.Set(k, v)
		}
	}

	opts := transport.RequestOptions{
		Domain:                 domain,
		ConfigRevision:         b.rc.Config.Revision,
		ProxyURL:               proxyURL,
		Account:                acct,
		Timeout:                timeout,
		MaxResponseBytes:       MaxHTTPResponseBytes,
		AllowedInternalOrigins: allowedInternal,
		Idempotent:             spec.Method == http.MethodGet || spec.Method == http.MethodHead,
		HasSideEffects:         spec.Method != http.MethodGet && spec.Method != http.MethodHead,
	}

	resp, err := b.pool.Do(b.ctx, req, opts)
	if err != nil {
		return httpResponseData{}, provider.ClassifyError(b.pluginID, 0, "", err)
	}
	defer resp.Body.Close()

	limited := io.LimitReader(resp.Body, MaxHTTPResponseBytes+1)
	rawBody, err := io.ReadAll(limited)
	if err != nil {
		return httpResponseData{}, provider.ClassifyError(b.pluginID, resp.StatusCode, resp.Header.Get("Retry-After"), err)
	}
	if len(rawBody) > MaxHTTPResponseBytes {
		return httpResponseData{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("响应体超过上限 %d 字节", MaxHTTPResponseBytes))
	}

	finalURL := parsed.String()
	if resp.Request != nil && resp.Request.URL != nil {
		finalURL = resp.Request.URL.String()
	}

	hdrs := make(map[string]string, len(resp.Header))
	for k, vals := range resp.Header {
		hdrs[strings.ToLower(k)] = strings.Join(vals, ", ")
	}

	return httpResponseData{
		Status:   resp.StatusCode,
		FinalURL: finalURL,
		Headers:  hdrs,
		Body:     rawBody,
	}, nil
}

func (b *httpBridge) toJSResponse(vm *goja.Runtime, res httpResponseData) goja.Value {
	obj := vm.NewObject()
	text := string(res.Body)
	_ = obj.Set("status", res.Status)
	_ = obj.Set("finalUrl", res.FinalURL)
	_ = obj.Set("headers", res.Headers)
	_ = obj.Set("text", text)
	_ = obj.Set("body", text)
	_ = obj.Set("base64", base64.StdEncoding.EncodeToString(res.Body))
	_ = obj.Set("json", func(call goja.FunctionCall) goja.Value {
		var parsed any
		if err := json.Unmarshal(res.Body, &parsed); err != nil {
			panic(vm.NewGoError(model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "响应 JSON 解析失败", err)))
		}
		return vm.ToValue(parsed)
	})
	return obj
}
