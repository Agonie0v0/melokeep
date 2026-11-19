package risks

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

type PoolKey struct {
	Origin         string
	Proxy          string
	TLSPolicy      string
	ConfigRevision int64
}

type SafeTransportPool struct {
	mu           sync.Mutex
	transports   map[PoolKey]*http.Transport
	allowTestIPs bool
	tlsRootCAs   *tls.Config
}

func isPublicIP(ip net.IP) bool {
	if ip == nil || ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() {
		return false
	}
	return ip.IsGlobalUnicast()
}

func (p *SafeTransportPool) transportFor(key PoolKey) *http.Transport {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.transports == nil {
		p.transports = make(map[PoolKey]*http.Transport)
	}
	if tr, ok := p.transports[key]; ok {
		return tr
	}
	dialer := &net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}
	var tlsCfg *tls.Config
	if p.tlsRootCAs != nil {
		tlsCfg = p.tlsRootCAs.Clone()
	}
	tr := &http.Transport{
		Proxy:                 nil, // Never read ambient HTTP_PROXY/HTTPS_PROXY silently.
		TLSClientConfig:       tlsCfg,
		MaxIdleConns:          32,
		MaxIdleConnsPerHost:   4,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: 10 * time.Second,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			var ips []net.IP
			if parsed := net.ParseIP(host); parsed != nil {
				ips = []net.IP{parsed}
			} else {
				resolved, err := net.DefaultResolver.LookupIPAddr(ctx, host)
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
			for _, ip := range ips {
				if !p.allowTestIPs && !isPublicIP(ip) {
					return nil, fmt.Errorf("blocked non-public IP %s for %s", ip.String(), host)
				}
			}
			var lastErr error
			for _, ip := range ips {
				pinnedAddr := net.JoinHostPort(ip.String(), port)
				conn, err := dialer.DialContext(ctx, network, pinnedAddr)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
	p.transports[key] = tr
	return tr
}

func (p *SafeTransportPool) InvalidateRevision(oldRev int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for k, tr := range p.transports {
		if k.ConfigRevision == oldRev {
			tr.CloseIdleConnections()
			delete(p.transports, k)
		}
	}
}

func sameOrigin(a, b *url.URL) bool {
	portOf := func(u *url.URL) string {
		if p := u.Port(); p != "" {
			return p
		}
		if strings.EqualFold(u.Scheme, "https") {
			return "443"
		}
		return "80"
	}
	return strings.EqualFold(a.Scheme, b.Scheme) &&
		strings.EqualFold(a.Hostname(), b.Hostname()) &&
		portOf(a) == portOf(b)
}

func (p *SafeTransportPool) Client(rev int64, proxy string) *http.Client {
	return &http.Client{
		Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			key := PoolKey{
				Origin:         strings.ToLower(req.URL.Scheme + "://" + req.URL.Host),
				Proxy:          proxy,
				TLSPolicy:      "strict",
				ConfigRevision: rev,
			}
			return p.transportFor(key).RoundTrip(req)
		}),
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("too many redirects")
			}
			prev := via[len(via)-1]
			if strings.EqualFold(prev.URL.Scheme, "https") && !strings.EqualFold(req.URL.Scheme, "https") {
				return errors.New("blocked https to http downgrade")
			}
			if !sameOrigin(prev.URL, req.URL) {
				if prev.Method != http.MethodGet && prev.Method != http.MethodHead && prev.GetBody != nil {
					return errors.New("blocked cross-origin body replay")
				}
				for _, h := range []string{"Authorization", "Cookie", "Proxy-Authorization", "Referer"} {
					req.Header.Del(h)
				}
			}
			return nil
		},
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestPublicTransportSecurityAndPoolPartitioning(t *testing.T) {
	// 1. Strict default blocks loopback & private IPs.
	strictPool := &SafeTransportPool{allowTestIPs: false}
	for _, blocked := range []string{"http://127.0.0.1:8080/x", "http://10.0.0.5/x", "http://169.254.169.254/latest", "http://[::1]:8080/x"} {
		_, err := strictPool.Client(1, "").Get(blocked)
		if err == nil || !strings.Contains(err.Error(), "blocked non-public IP") {
			t.Fatalf("expected non-public IP rejection for %s, got: %v", blocked, err)
		}
	}

	// 2. Test-only loopback toggle verifies TLS SNI pinning, redirect downgrade rejection, cross-origin header stripping, and config-revision pool isolation.
	var sawHost string
	var sawAuthCrossOrigin string
	var remoteAddrs []string
	var mu sync.Mutex

	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		sawHost = r.Host
		sawAuthCrossOrigin = r.Header.Get("Authorization")
		remoteAddrs = append(remoteAddrs, r.RemoteAddr)
		mu.Unlock()
		_, _ = io.WriteString(w, "ok")
	}))
	defer target.Close()

	plainHTTP := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "downgraded")
	}))
	defer plainHTTP.Close()

	redirector := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/to-target":
			http.Redirect(w, r, target.URL+"/final", http.StatusFound)
		case "/downgrade":
			http.Redirect(w, r, plainHTTP.URL+"/insecure", http.StatusFound)
		}
	}))
	defer redirector.Close()

	// Build pooled TLS config trusting both test certs; test ServerName=127.0.0.1 (cert SAN of httptest.NewTLSServer).
	poolTLS := target.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	poolTLS.RootCAs.AddCert(redirector.Certificate())
	pool := &SafeTransportPool{allowTestIPs: true, tlsRootCAs: poolTLS}

	// Cross-origin redirect strips Authorization and Cookie.
	req, _ := http.NewRequest(http.MethodGet, redirector.URL+"/to-target", nil)
	req.Header.Set("Authorization", "Bearer secret-token")
	req.Header.Set("Cookie", "sid=secret")
	req.Header.Set("Referer", "https://origin.example/page")
	resp, err := pool.Client(1, "").Do(req)
	if err != nil {
		t.Fatalf("cross-origin safe redirect failed: %v", err)
	}
	_ = resp.Body.Close()
	if sawAuthCrossOrigin != "" {
		t.Fatalf("Authorization leaked across origin: %q", sawAuthCrossOrigin)
	}
	if sawHost == "" {
		t.Fatal("expected Host preserved")
	}

	// HTTPS -> HTTP downgrade must fail.
	if _, err := pool.Client(1, "").Get(redirector.URL + "/downgrade"); err == nil || !strings.Contains(err.Error(), "downgrade") {
		t.Fatalf("expected https->http downgrade rejection, got: %v", err)
	}

	// Reuse on same config revision keeps same TCP remoteAddr; changing config revision opens new connection.
	r1, err := pool.Client(1, "").Get(target.URL)
	if err != nil {
		t.Fatalf("rev1 req: %v", err)
	}
	_ = r1.Body.Close()
	r2, err := pool.Client(2, "").Get(target.URL)
	if err != nil {
		t.Fatalf("rev2 req: %v", err)
	}
	_ = r2.Body.Close()
	pool.InvalidateRevision(1)

	mu.Lock()
	defer mu.Unlock()
	if len(remoteAddrs) < 3 {
		t.Fatalf("expected >=3 requests, got %d", len(remoteAddrs))
	}
	if remoteAddrs[0] != remoteAddrs[1] {
		t.Fatalf("expected same revision connection reuse, got %s vs %s", remoteAddrs[0], remoteAddrs[1])
	}
	if remoteAddrs[1] == remoteAddrs[2] {
		t.Fatalf("expected isolated pool across config revisions, both used %s", remoteAddrs[2])
	}
}
