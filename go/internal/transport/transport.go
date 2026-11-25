package transport

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

var timeZero time.Time

// TrustDomain classifies network egress boundaries.
type TrustDomain string

const (
	// DomainPublicMedia enforces public-IP SSRF checks, DNS pinning, strict TLS,
	// no https->http downgrade, and cross-origin sensitive header stripping.
	DomainPublicMedia TrustDomain = "public_media"
	// DomainAccountProvider enforces DomainPublicMedia protections plus strict
	// per-(source, account_id) cookie isolation.
	DomainAccountProvider TrustDomain = "account_provider"
	// DomainConfiguredInternal allows explicitly configured private/loopback endpoints
	// (e.g. Navidrome, AI, WeCom, local Telegram Bot API) while restricting redirects.
	DomainConfiguredInternal TrustDomain = "configured_internal"
)

const (
	DefaultTimeout          = 30 * time.Second
	DefaultMaxResponseBytes = 16 << 20 // 16 MiB
	DefaultMaxRedirects     = 5
)

// AccountKey isolates cookies and connection pools per source and account identity.
type AccountKey struct {
	Source    string
	AccountID string
}

func (a AccountKey) String() string {
	if a.Source == "" && a.AccountID == "" {
		return ""
	}
	return a.Source + ":" + a.AccountID
}

// PoolKey partitions underlying HTTP connection pools by trust domain, origin,
// hashed proxy identity, TLS policy, account key, config revision, and response-header timeout.
type PoolKey struct {
	Domain                TrustDomain
	Origin                string
	ProxyKey              string
	TLSPolicy             string
	AccountKey            string
	ConfigRevision        string
	ResponseHeaderTimeout time.Duration
}

// RequestOptions controls trust domain, pool revision, proxy, and safety limits per call.
type RequestOptions struct {
	Domain                 TrustDomain
	ConfigRevision         string
	ProxyURL               string
	Account                AccountKey
	Timeout                time.Duration
	ResponseHeaderTimeout  time.Duration
	MaxResponseBytes       int64 // 0 -> DefaultMaxResponseBytes; <0 -> unlimited (caller manages stream)
	AllowedInternalOrigins []string
	Idempotent             bool
	HasSideEffects         bool
	MaxRetries             int
	AllowHTTPSDowngrade    bool
}

type Option func(*Pool)

// WithAllowTestLoopback allows loopback/private IP resolution in tests.
// Production callers MUST NEVER enable this for public trust domains.
func WithAllowTestLoopback(allow bool) Option {
	return func(p *Pool) { p.allowTestLoopback = allow }
}

// WithTLSConfig overrides base TLS configuration (for test CA roots).
func WithTLSConfig(cfg *tls.Config) Option {
	return func(p *Pool) {
		if cfg != nil {
			p.baseTLS = cfg.Clone()
		}
	}
}

// WithResolver overrides DNS resolution (useful for deterministic SSRF/pinning tests).
func WithResolver(r Resolver) Option {
	return func(p *Pool) { p.resolver = r }
}

type Resolver interface {
	LookupIPAddr(ctx context.Context, host string) ([]net.IPAddr, error)
}

type stdResolver struct{}

func (stdResolver) LookupIPAddr(ctx context.Context, host string) ([]net.IPAddr, error) {
	return net.DefaultResolver.LookupIPAddr(ctx, host)
}

// Pool manages partitioned, revision-scoped HTTP transports and per-account cookie jars.
type Pool struct {
	mu                sync.Mutex
	transports        map[PoolKey]*http.Transport
	jars              map[string]http.CookieJar
	allowTestLoopback bool
	baseTLS           *tls.Config
	resolver          Resolver
}

func NewPool(opts ...Option) *Pool {
	p := &Pool{
		transports: make(map[PoolKey]*http.Transport),
		jars:       make(map[string]http.CookieJar),
		resolver:   stdResolver{},
	}
	for _, opt := range opts {
		opt(p)
	}
	return p
}

var (
	cgnatNet      = mustParseCIDR("100.64.0.0/10")
	benchNet      = mustParseCIDR("198.18.0.0/15")
	testNet1      = mustParseCIDR("192.0.2.0/24")
	testNet2      = mustParseCIDR("198.51.100.0/24")
	testNet3      = mustParseCIDR("203.0.113.0/24")
	reservedV4Net = mustParseCIDR("240.0.0.0/4")
	ietfProtoNet  = mustParseCIDR("192.0.0.0/24")
)

func mustParseCIDR(s string) *net.IPNet {
	_, n, err := net.ParseCIDR(s)
	if err != nil {
		panic(err)
	}
	return n
}

// IsPublicIP reports whether ip is a globally routable unicast address safe for public egress.
func IsPublicIP(ip net.IP) bool {
	if ip == nil {
		return false
	}
	if v4 := ip.To4(); v4 != nil {
		ip = v4
	}
	if ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() {
		return false
	}
	if !ip.IsGlobalUnicast() {
		return false
	}
	if ip.To4() != nil {
		for _, blocked := range []*net.IPNet{cgnatNet, benchNet, testNet1, testNet2, testNet3, reservedV4Net, ietfProtoNet} {
			if blocked.Contains(ip) {
				return false
			}
		}
	}
	return true
}

func validateURLShape(raw string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的请求地址", err)
	}
	scheme := strings.ToLower(u.Scheme)
	if (scheme != "http" && scheme != "https") || u.Hostname() == "" || u.User != nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "仅允许不含账号密码的 HTTP/HTTPS 地址")
	}
	return u, nil
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

func isBlockedInternalHostname(rawHost string) bool {
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
	// Check embedded IPv4 tokens such as 127.0.0.1.nip.io or 192-168-1-1.sslip.io
	if strings.HasSuffix(h, ".nip.io") || strings.HasSuffix(h, ".sslip.io") || strings.HasSuffix(h, ".xip.io") {
		labels := strings.Split(h, ".")
		if len(labels) >= 6 {
			cand := strings.Join(labels[:4], ".")
			if ip := net.ParseIP(cand); ip != nil && !IsPublicIP(ip) {
				return true
			}
		}
		if len(labels) >= 3 {
			dashCand := strings.ReplaceAll(labels[0], "-", ".")
			if ip := net.ParseIP(dashCand); ip != nil && !IsPublicIP(ip) {
				return true
			}
		}
	}
	return false
}

func proxyHash(rawProxy string) string {
	trimmed := strings.TrimSpace(rawProxy)
	if trimmed == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(trimmed))
	return hex.EncodeToString(sum[:12])
}

func (p *Pool) accountJar(opts RequestOptions) http.CookieJar {
	if opts.Domain != DomainAccountProvider || opts.Account.String() == "" {
		return nil
	}
	key := opts.ConfigRevision + "\x00" + opts.Account.String()
	p.mu.Lock()
	defer p.mu.Unlock()
	jar, ok := p.jars[key]
	if !ok {
		jar, _ = cookiejar.New(nil)
		p.jars[key] = jar
	}
	return jar
}

// InvalidateRevision closes idle connections and evicts pools/jars tied to oldRev.
func (p *Pool) InvalidateRevision(oldRev string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for k, tr := range p.transports {
		if k.ConfigRevision == oldRev {
			tr.CloseIdleConnections()
			delete(p.transports, k)
		}
	}
	prefix := oldRev + "\x00"
	for k := range p.jars {
		if strings.HasPrefix(k, prefix) {
			delete(p.jars, k)
		}
	}
}

// CloseAll closes idle connections across all pooled transports.
func (p *Pool) CloseAll() {
	p.mu.Lock()
	defer p.mu.Unlock()
	for k, tr := range p.transports {
		tr.CloseIdleConnections()
		delete(p.transports, k)
	}
	clear(p.jars)
}

// ActivePoolCount returns the number of cached transports (for diagnostics/tests).
func (p *Pool) ActivePoolCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.transports)
}

func (p *Pool) transportFor(key PoolKey, proxyURL *url.URL) *http.Transport {
	p.mu.Lock()
	defer p.mu.Unlock()
	if tr, ok := p.transports[key]; ok {
		return tr
	}
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	var tlsCfg *tls.Config
	if p.baseTLS != nil {
		tlsCfg = p.baseTLS.Clone()
	} else {
		tlsCfg = &tls.Config{MinVersion: tls.VersionTLS12}
	}

	hdrTimeout := key.ResponseHeaderTimeout
	if hdrTimeout <= 0 {
		hdrTimeout = 15 * time.Second
	}

	tr := &http.Transport{
		Proxy:                 nil, // Never read ambient HTTP_PROXY/HTTPS_PROXY silently.
		TLSClientConfig:       tlsCfg,
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: hdrTimeout,
	}

	if proxyURL != nil {
		scheme := strings.ToLower(proxyURL.Scheme)
		switch scheme {
		case "http", "https":
			tr.Proxy = http.ProxyURL(proxyURL)
			tr.DialContext = dialer.DialContext
		case "socks5", "socks5h":
			tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
				host, _, err := net.SplitHostPort(addr)
				if err == nil && (key.Domain == DomainPublicMedia || key.Domain == DomainAccountProvider) {
					if ip := net.ParseIP(host); ip != nil && !p.allowTestLoopback && !IsPublicIP(ip) {
						return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止访问非公网地址")
					}
				}
				return dialSOCKS5(ctx, dialer, proxyURL, network, addr)
			}
		}
	} else {
		tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			var ips []net.IP
			if ip := net.ParseIP(host); ip != nil {
				ips = []net.IP{ip}
			} else {
				resolved, err := p.resolver.LookupIPAddr(ctx, host)
				if err != nil {
					return nil, err
				}
				for _, r := range resolved {
					ips = append(ips, r.IP)
				}
			}
			if len(ips) == 0 {
				return nil, errors.New("empty DNS response")
			}
			if key.Domain == DomainPublicMedia || key.Domain == DomainAccountProvider {
				for _, ip := range ips {
					if !p.allowTestLoopback && !IsPublicIP(ip) {
						return nil, model.NewError(model.ErrForbidden, http.StatusForbidden,
							fmt.Sprintf("远程资源地址必须指向公网 (%s)", ip.String()))
					}
				}
			}
			var lastErr error
			for _, ip := range ips {
				conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		}
	}

	p.transports[key] = tr
	return tr
}

type originPinnedJar struct {
	origin string
	inner  http.CookieJar
}

func (j *originPinnedJar) SetCookies(u *url.URL, cookies []*http.Cookie) {
	if u == nil || originKey(u) != j.origin {
		return
	}
	j.inner.SetCookies(u, cookies)
}

func (j *originPinnedJar) Cookies(u *url.URL) []*http.Cookie {
	if u == nil || originKey(u) != j.origin {
		return nil
	}
	return j.inner.Cookies(u)
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// Do executes req within the specified TrustDomain, enforcing URL checks,
// redirect rules, sensitive header stripping, and response body limits.
func (p *Pool) Do(ctx context.Context, req *http.Request, opts RequestOptions) (*http.Response, error) {
	if req == nil || req.URL == nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请求不能为空")
	}
	if opts.Domain == "" {
		opts.Domain = DomainPublicMedia
	}
	if _, err := validateURLShape(req.URL.String()); err != nil {
		return nil, err
	}
	var parsedProxy *url.URL
	if strings.TrimSpace(opts.ProxyURL) != "" {
		var err error
		parsedProxy, err = url.Parse(strings.TrimSpace(opts.ProxyURL))
		if err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "代理地址格式无效", err)
		}
		ps := strings.ToLower(parsedProxy.Scheme)
		if ps != "http" && ps != "https" && ps != "socks5" && ps != "socks5h" {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的代理协议")
		}
	}

	var (
		reqCtx context.Context
		cancel context.CancelFunc
	)
	if opts.Timeout < 0 {
		reqCtx, cancel = context.WithCancel(ctx)
	} else {
		timeout := opts.Timeout
		if timeout == 0 {
			timeout = DefaultTimeout
		}
		reqCtx, cancel = context.WithTimeout(ctx, timeout)
	}
	cloneReq := req.Clone(reqCtx)

	initialOrigin := originKey(cloneReq.URL)
	allowedInternal := make(map[string]bool)
	if opts.Domain == DomainConfiguredInternal {
		if len(opts.AllowedInternalOrigins) == 0 {
			allowedInternal[initialOrigin] = true
		} else {
			for _, raw := range opts.AllowedInternalOrigins {
				if u, err := validateURLShape(raw); err == nil {
					allowedInternal[originKey(u)] = true
				}
			}
		}
		if !allowedInternal[initialOrigin] {
			cancel()
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "目标不在已配置的内网来源白名单内")
		}
	}

	var jar http.CookieJar
	if baseJar := p.accountJar(opts); baseJar != nil {
		jar = &originPinnedJar{origin: initialOrigin, inner: baseJar}
	}

	pKeyHash := proxyHash(opts.ProxyURL)
	acctKey := opts.Account.String()

	client := &http.Client{
		Jar: jar,
		Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			if _, err := validateURLShape(r.URL.String()); err != nil {
				return nil, err
			}
			if opts.Domain == DomainPublicMedia || opts.Domain == DomainAccountProvider {
				if !p.allowTestLoopback {
					host := r.URL.Hostname()
					if ip := net.ParseIP(host); ip != nil {
						if !IsPublicIP(ip) {
							return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止访问非公网地址")
						}
					} else if isBlockedInternalHostname(host) {
						return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止访问内网或本机地址")
					}
				}
			}
			pk := PoolKey{
				Domain:                opts.Domain,
				Origin:                originKey(r.URL),
				ProxyKey:              pKeyHash,
				TLSPolicy:             "strict",
				AccountKey:            acctKey,
				ConfigRevision:        opts.ConfigRevision,
				ResponseHeaderTimeout: opts.ResponseHeaderTimeout,
			}
			return p.transportFor(pk, parsedProxy).RoundTrip(r)
		}),
		CheckRedirect: func(r *http.Request, via []*http.Request) error {
			if len(via) >= DefaultMaxRedirects {
				return model.NewError(model.ErrTransport, http.StatusBadGateway, "远程资源跳转次数过多")
			}
			if _, err := validateURLShape(r.URL.String()); err != nil {
				return err
			}
			prev := via[len(via)-1]
			if !opts.AllowHTTPSDowngrade && strings.EqualFold(prev.URL.Scheme, "https") && !strings.EqualFold(r.URL.Scheme, "https") {
				return model.NewError(model.ErrForbidden, http.StatusBadGateway, "禁止从 HTTPS 跳转到 HTTP")
			}
			nextOrig := originKey(r.URL)
			if opts.Domain == DomainConfiguredInternal && !allowedInternal[nextOrig] {
				return model.NewError(model.ErrForbidden, http.StatusBadGateway, "禁止内部服务重定向到未授权来源")
			}
			if originKey(prev.URL) != nextOrig {
				if prev.Method != http.MethodGet && prev.Method != http.MethodHead && prev.GetBody != nil {
					return model.NewError(model.ErrForbidden, http.StatusBadGateway, "禁止跨站重发提交内容")
				}
				for _, h := range []string{"Authorization", "Cookie", "Proxy-Authorization", "Referer"} {
					r.Header.Del(h)
				}
			}
			return nil
		},
	}

	resp, err := client.Do(cloneReq)
	if err != nil {
		cancel()
		if de, ok := model.AsDomainError(err); ok {
			return nil, de
		}
		var urlErr *url.Error
		if errors.As(err, &urlErr) {
			if de, ok := model.AsDomainError(urlErr.Err); ok {
				return nil, de
			}
		}
		if errors.Is(err, context.DeadlineExceeded) {
			return nil, model.WrapError(model.ErrDeadline, http.StatusGatewayTimeout, "上游请求超时", err)
		}
		if errors.Is(err, context.Canceled) {
			return nil, model.WrapError(model.ErrCancelled, 499, "请求已取消", err)
		}
		return nil, model.WrapError(model.ErrTransport, http.StatusBadGateway, "上游网络请求失败", err)
	}

	maxBytes := opts.MaxResponseBytes
	if maxBytes == 0 {
		maxBytes = DefaultMaxResponseBytes
	}
	resp.Body = &guardedBody{
		rc:       resp.Body,
		maxBytes: maxBytes,
		cancel:   cancel,
	}
	return resp, nil
}

// DoWithRetry executes a request builder with bounded retries ONLY when the
// request is safe/idempotent (GET/HEAD, Idempotent=true, HasSideEffects=false).
func (p *Pool) DoWithRetry(ctx context.Context, buildReq func() (*http.Request, error), opts RequestOptions) (*http.Response, error) {
	attempts := 1
	if opts.Idempotent && !opts.HasSideEffects && opts.MaxRetries > 0 {
		attempts += opts.MaxRetries
		if attempts > 4 {
			attempts = 4
		}
	}
	var lastErr error
	for i := 0; i < attempts; i++ {
		req, err := buildReq()
		if err != nil {
			return nil, err
		}
		if req.Method != http.MethodGet && req.Method != http.MethodHead {
			// Side-effecting methods are never retried even if caller set MaxRetries.
			return p.Do(ctx, req, opts)
		}
		resp, err := p.Do(ctx, req, opts)
		if err == nil {
			if resp.StatusCode >= 500 && resp.StatusCode != http.StatusNotImplemented && i < attempts-1 {
				_ = resp.Body.Close()
				lastErr = model.NewError(model.ErrUnavailable, resp.StatusCode, fmt.Sprintf("上游返回 %d", resp.StatusCode))
				if !sleepCtx(ctx, backoffForAttempt(i)) {
					return nil, ctx.Err()
				}
				continue
			}
			return resp, nil
		}
		lastErr = err
		de, ok := model.AsDomainError(err)
		if ok && (de.Code == model.ErrForbidden || de.Code == model.ErrInvalidInput || de.Code == model.ErrCancelled) {
			return nil, err
		}
		if i < attempts-1 {
			if !sleepCtx(ctx, backoffForAttempt(i)) {
				return nil, ctx.Err()
			}
		}
	}
	return nil, lastErr
}

func backoffForAttempt(attempt int) time.Duration {
	d := 100 * time.Millisecond * (1 << attempt)
	if d > 1*time.Second {
		return 1 * time.Second
	}
	return d
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

type guardedBody struct {
	rc       io.ReadCloser
	maxBytes int64
	read     int64
	cancel   context.CancelFunc
}

func (g *guardedBody) Read(p []byte) (int, error) {
	n, err := g.rc.Read(p)
	if n > 0 && g.maxBytes > 0 {
		g.read += int64(n)
		if g.read > g.maxBytes {
			return n, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway,
				fmt.Sprintf("上游响应体超过上限 %d 字节", g.maxBytes))
		}
	}
	return n, err
}

func (g *guardedBody) Close() error {
	err := g.rc.Close()
	if g.cancel != nil {
		g.cancel()
	}
	return err
}
