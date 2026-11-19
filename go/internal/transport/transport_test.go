package transport

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

type staticResolver struct {
	ips []net.IPAddr
	err error
}

func (r staticResolver) LookupIPAddr(_ context.Context, _ string) ([]net.IPAddr, error) {
	return r.ips, r.err
}

func TestPublicMediaSSRFBlocking(t *testing.T) {
	pool := NewPool()
	defer pool.CloseAll()

	blockedURLs := []string{
		"http://127.0.0.1:8080/x",
		"http://10.0.0.5/x",
		"http://172.16.0.1/x",
		"http://192.168.1.10/x",
		"http://169.254.169.254/latest/meta-data/",
		"http://100.64.0.1/x",
		"http://[::1]:8080/x",
		"http://[::ffff:127.0.0.1]:8080/x",
		"http://user:pass@example.com/x",
		"file:///etc/passwd",
		"ftp://example.com/file",
	}

	for _, raw := range blockedURLs {
		req, err := http.NewRequest(http.MethodGet, raw, nil)
		if err != nil {
			continue
		}
		_, err = pool.Do(context.Background(), req, RequestOptions{Domain: DomainPublicMedia})
		if err == nil {
			t.Fatalf("expected SSRF/URL rejection for %s, got nil", raw)
		}
		de, ok := model.AsDomainError(err)
		if !ok || (de.Code != model.ErrForbidden && de.Code != model.ErrInvalidInput) {
			t.Fatalf("expected forbidden/invalid_input for %s, got %v", raw, err)
		}
	}

	// DNS rebinding/private answer rejection via mock resolver.
	dnsPool := NewPool(WithResolver(staticResolver{ips: []net.IPAddr{{IP: net.ParseIP("10.1.2.3")}}}))
	req, _ := http.NewRequest(http.MethodGet, "https://public.example.invalid/track", nil)
	_, err := dnsPool.Do(context.Background(), req, RequestOptions{Domain: DomainPublicMedia})
	if err == nil {
		t.Fatal("expected private DNS response to be blocked")
	}
	if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrForbidden {
		t.Fatalf("expected ErrForbidden, got %v", err)
	}
}

func TestPublicMediaRedirectsSNIAndRevisionPools(t *testing.T) {
	var mu sync.Mutex
	var sawHost string
	var sawAuth string
	var sawCookie string
	var sawReferer string
	var remoteAddrs []string

	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		sawHost = r.Host
		sawAuth = r.Header.Get("Authorization")
		sawCookie = r.Header.Get("Cookie")
		sawReferer = r.Header.Get("Referer")
		remoteAddrs = append(remoteAddrs, r.RemoteAddr)
		mu.Unlock()
		_, _ = io.WriteString(w, "ok")
	}))
	defer target.Close()

	plain := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "plain")
	}))
	defer plain.Close()

	redirector := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/to-target":
			http.Redirect(w, r, target.URL+"/final", http.StatusFound)
		case "/downgrade":
			http.Redirect(w, r, plain.URL+"/insecure", http.StatusFound)
		case "/post-redirect":
			http.Redirect(w, r, target.URL+"/final", http.StatusTemporaryRedirect)
		}
	}))
	defer redirector.Close()

	tlsCfg := target.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	tlsCfg.RootCAs.AddCert(redirector.Certificate())
	pool := NewPool(WithAllowTestLoopback(true), WithTLSConfig(tlsCfg))
	defer pool.CloseAll()

	// Cross-origin redirect strips Authorization, Cookie, Referer.
	req, _ := http.NewRequest(http.MethodGet, redirector.URL+"/to-target", nil)
	req.Header.Set("Authorization", "Bearer top-secret")
	req.Header.Set("Cookie", "sid=secret")
	req.Header.Set("Referer", "https://origin.example/page")
	resp, err := pool.Do(context.Background(), req, RequestOptions{
		Domain:         DomainPublicMedia,
		ConfigRevision: "rev-1",
	})
	if err != nil {
		t.Fatalf("redirect failed: %v", err)
	}
	_ = resp.Body.Close()

	mu.Lock()
	if sawAuth != "" || sawCookie != "" || sawReferer != "" {
		mu.Unlock()
		t.Fatalf("sensitive headers leaked cross-origin: auth=%q cookie=%q referer=%q", sawAuth, sawCookie, sawReferer)
	}
	if sawHost == "" {
		mu.Unlock()
		t.Fatal("Host header was not preserved")
	}
	mu.Unlock()

	// HTTPS -> HTTP downgrade must be rejected by default, allowed when AllowHTTPSDowngrade is true.
	downReq, _ := http.NewRequest(http.MethodGet, redirector.URL+"/downgrade", nil)
	if _, err := pool.Do(context.Background(), downReq, RequestOptions{Domain: DomainPublicMedia, ConfigRevision: "rev-1"}); err == nil {
		t.Fatal("expected https->http downgrade to fail")
	}
	allowedDownReq, _ := http.NewRequest(http.MethodGet, redirector.URL+"/downgrade", nil)
	if res, err := pool.Do(context.Background(), allowedDownReq, RequestOptions{Domain: DomainPublicMedia, ConfigRevision: "rev-1", AllowHTTPSDowngrade: true}); err != nil {
		t.Fatalf("expected https->http downgrade to succeed with AllowHTTPSDowngrade=true, got %v", err)
	} else {
		_ = res.Body.Close()
	}

	// Cross-origin POST body replay must be rejected.
	postReq, _ := http.NewRequest(http.MethodPost, redirector.URL+"/post-redirect", strings.NewReader("payload"))
	if _, err := pool.Do(context.Background(), postReq, RequestOptions{Domain: DomainPublicMedia, ConfigRevision: "rev-1"}); err == nil {
		t.Fatal("expected cross-origin POST body replay to fail")
	}

	// Same revision reuses connection; different revision uses isolated pool.
	for _, rev := range []string{"rev-1", "rev-1", "rev-2"} {
		r, _ := http.NewRequest(http.MethodGet, target.URL, nil)
		res, err := pool.Do(context.Background(), r, RequestOptions{Domain: DomainPublicMedia, ConfigRevision: rev})
		if err != nil {
			t.Fatalf("request rev=%s failed: %v", rev, err)
		}
		_ = res.Body.Close()
	}
	pool.InvalidateRevision("rev-1")

	mu.Lock()
	defer mu.Unlock()
	if len(remoteAddrs) < 4 {
		t.Fatalf("expected >=4 requests, got %d", len(remoteAddrs))
	}
	if remoteAddrs[1] != remoteAddrs[2] {
		t.Fatalf("expected same revision reuse: %s vs %s", remoteAddrs[1], remoteAddrs[2])
	}
	if remoteAddrs[2] == remoteAddrs[3] {
		t.Fatalf("expected different connection across config revisions: %s", remoteAddrs[3])
	}
}

func TestAccountProviderCookieIsolation(t *testing.T) {
	var mu sync.Mutex
	var seenCookies []string

	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/login" {
			acct := r.URL.Query().Get("acct")
			http.SetCookie(w, &http.Cookie{Name: "acct_cookie", Value: acct, Path: "/"})
			_, _ = io.WriteString(w, "logged")
			return
		}
		if r.URL.Path == "/redirect-other" {
			http.Redirect(w, r, r.URL.Query().Get("next"), http.StatusFound)
			return
		}
		mu.Lock()
		seenCookies = append(seenCookies, r.Header.Get("Cookie"))
		mu.Unlock()
		_, _ = io.WriteString(w, "ok")
	}))
	defer srv.Close()

	other := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seenCookies = append(seenCookies, "other:"+r.Header.Get("Cookie"))
		mu.Unlock()
		_, _ = io.WriteString(w, "other")
	}))
	defer other.Close()

	tlsCfg := srv.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	tlsCfg.RootCAs.AddCert(other.Certificate())
	pool := NewPool(WithAllowTestLoopback(true), WithTLSConfig(tlsCfg))
	defer pool.CloseAll()

	login := func(source, acct string) {
		r, _ := http.NewRequest(http.MethodGet, srv.URL+"/login?acct="+acct, nil)
		res, err := pool.Do(context.Background(), r, RequestOptions{
			Domain:         DomainAccountProvider,
			ConfigRevision: "r1",
			Account:        AccountKey{Source: source, AccountID: acct},
		})
		if err != nil {
			t.Fatalf("login %s/%s: %v", source, acct, err)
		}
		_ = res.Body.Close()
	}

	login("audius", "alice")
	login("audius", "bob")
	login("fma", "alice")

	check := func(source, acct string) {
		r, _ := http.NewRequest(http.MethodGet, srv.URL+"/me", nil)
		res, err := pool.Do(context.Background(), r, RequestOptions{
			Domain:         DomainAccountProvider,
			ConfigRevision: "r1",
			Account:        AccountKey{Source: source, AccountID: acct},
		})
		if err != nil {
			t.Fatalf("check %s/%s: %v", source, acct, err)
		}
		_ = res.Body.Close()
	}

	check("audius", "alice")
	check("audius", "bob")

	// Cross-origin redirect from audius alice must NOT carry alice's cookie to other origin.
	r, _ := http.NewRequest(http.MethodGet, srv.URL+"/redirect-other?next="+other.URL+"/leak", nil)
	res, err := pool.Do(context.Background(), r, RequestOptions{
		Domain:         DomainAccountProvider,
		ConfigRevision: "r1",
		Account:        AccountKey{Source: "audius", AccountID: "alice"},
	})
	if err != nil {
		t.Fatalf("redirect-other: %v", err)
	}
	_ = res.Body.Close()

	mu.Lock()
	defer mu.Unlock()
	if len(seenCookies) != 3 {
		t.Fatalf("expected 3 cookie checks, got %d", len(seenCookies))
	}
	if seenCookies[0] != "acct_cookie=alice" || seenCookies[1] != "acct_cookie=bob" {
		t.Fatalf("account cookies mixed: %v", seenCookies)
	}
	if seenCookies[2] != "other:" {
		t.Fatalf("account cookie leaked cross-origin: %q", seenCookies[2])
	}
}

func TestConfiguredInternalOriginWhitelist(t *testing.T) {
	pool := NewPool() // allowTestLoopback=false! DomainConfiguredInternal allows loopback.
	defer pool.CloseAll()

	external := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "external")
	}))
	defer external.Close()

	internal := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/escape" {
			http.Redirect(w, r, external.URL+"/steal", http.StatusFound)
			return
		}
		_, _ = io.WriteString(w, "navidrome-ok")
	}))
	defer internal.Close()

	// Same-origin internal request succeeds even on loopback.
	r1, _ := http.NewRequest(http.MethodGet, internal.URL+"/rest/ping", nil)
	res, err := pool.Do(context.Background(), r1, RequestOptions{
		Domain:                 DomainConfiguredInternal,
		AllowedInternalOrigins: []string{internal.URL},
	})
	if err != nil {
		t.Fatalf("internal req failed: %v", err)
	}
	_ = res.Body.Close()

	// Redirect escaping AllowedInternalOrigins must fail.
	r2, _ := http.NewRequest(http.MethodGet, internal.URL+"/escape", nil)
	if _, err := pool.Do(context.Background(), r2, RequestOptions{
		Domain:                 DomainConfiguredInternal,
		AllowedInternalOrigins: []string{internal.URL},
	}); err == nil {
		t.Fatal("expected internal redirect outside whitelist to fail")
	}
}

func TestSOCKS5RemoteDNSNoLocalLeak(t *testing.T) {
	var sawATYP byte
	var sawHost string
	var mu sync.Mutex

	// Mock SOCKS5 server that records ATYP=0x03 domain and serves an HTTP response directly over the tunnel.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				var hdr [2]byte
				if _, err := io.ReadFull(c, hdr[:]); err != nil {
					return
				}
				methods := make([]byte, hdr[1])
				_, _ = io.ReadFull(c, methods)
				_, _ = c.Write([]byte{0x05, 0x00})

				var reqHdr [4]byte
				if _, err := io.ReadFull(c, reqHdr[:]); err != nil {
					return
				}
				mu.Lock()
				sawATYP = reqHdr[3]
				mu.Unlock()
				if reqHdr[3] == 0x03 {
					var lnBuf [1]byte
					_, _ = io.ReadFull(c, lnBuf[:])
					hostBuf := make([]byte, lnBuf[0])
					_, _ = io.ReadFull(c, hostBuf)
					var portBuf [2]byte
					_, _ = io.ReadFull(c, portBuf[:])
					_ = binary.BigEndian.Uint16(portBuf[:])
					mu.Lock()
					sawHost = string(hostBuf)
					mu.Unlock()
				}
				_, _ = c.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80})
				buf := make([]byte, 1024)
				_, _ = c.Read(buf)
				_, _ = io.WriteString(c, "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nproxy-body")
			}(conn)
		}
	}()

	// Local resolver intentionally fails for non-existent domain; SOCKS5 must NOT call local resolver.
	pool := NewPool(WithResolver(staticResolver{err: errors.New("local DNS should not be called")}))
	defer pool.CloseAll()

	req, _ := http.NewRequest(http.MethodGet, "http://remote-only.example.invalid/song", nil)
	resp, err := pool.Do(context.Background(), req, RequestOptions{
		Domain:         DomainPublicMedia,
		ConfigRevision: "r1",
		ProxyURL:       "socks5h://secretuser:secretpass@" + ln.Addr().String(),
	})
	if err != nil {
		t.Fatalf("socks5 request failed: %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if string(body) != "proxy-body" {
		t.Fatalf("unexpected body: %q", string(body))
	}
	mu.Lock()
	defer mu.Unlock()
	if sawATYP != 0x03 || sawHost != "remote-only.example.invalid" {
		t.Fatalf("expected SOCKS5 remote domain ATYP=0x03 host=remote-only.example.invalid, got atyp=%d host=%q", sawATYP, sawHost)
	}
	if pool.ActivePoolCount() != 1 {
		t.Fatalf("expected 1 pooled transport, got %d", pool.ActivePoolCount())
	}
}

func TestResponseCapAndSafeRetryPolicy(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := hits.Add(1)
		if r.URL.Path == "/big" {
			_, _ = io.WriteString(w, strings.Repeat("A", 2048))
			return
		}
		if n == 1 && r.Method == http.MethodGet {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		_, _ = io.WriteString(w, "ok")
	}))
	defer srv.Close()

	pool := NewPool(WithAllowTestLoopback(true), WithTLSConfig(&tls.Config{MinVersion: tls.VersionTLS12}))
	defer pool.CloseAll()

	// MaxResponseBytes cap triggers error.
	bigReq, _ := http.NewRequest(http.MethodGet, srv.URL+"/big", nil)
	bigResp, err := pool.Do(context.Background(), bigReq, RequestOptions{
		Domain:           DomainPublicMedia,
		MaxResponseBytes: 512,
	})
	if err != nil {
		t.Fatalf("big req open: %v", err)
	}
	_, readErr := io.ReadAll(bigResp.Body)
	_ = bigResp.Body.Close()
	if readErr == nil {
		t.Fatal("expected response byte limit error")
	}

	// Idempotent GET retries 503 -> 200.
	hits.Store(0)
	resp, err := pool.DoWithRetry(context.Background(), func() (*http.Request, error) {
		return http.NewRequest(http.MethodGet, srv.URL+"/ok", nil)
	}, RequestOptions{
		Domain:     DomainPublicMedia,
		Idempotent: true,
		MaxRetries: 2,
	})
	if err != nil {
		t.Fatalf("safe retry failed: %v", err)
	}
	_ = resp.Body.Close()
	if hits.Load() != 2 {
		t.Fatalf("expected 2 attempts for idempotent GET, got %d", hits.Load())
	}

	// POST is never retried even if MaxRetries > 0.
	hits.Store(0)
	postResp, err := pool.DoWithRetry(context.Background(), func() (*http.Request, error) {
		return http.NewRequest(http.MethodPost, srv.URL+"/mutate", nil)
	}, RequestOptions{
		Domain:     DomainPublicMedia,
		Idempotent: true,
		MaxRetries: 3,
	})
	if err != nil {
		t.Fatalf("post call failed: %v", err)
	}
	_ = postResp.Body.Close()
	if hits.Load() != 1 {
		t.Fatalf("expected 1 attempt for POST (no retry), got %d", hits.Load())
	}
}

func TestPoolResponseHeaderTimeoutOverride(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(120 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	}))
	defer srv.Close()

	pool := NewPool(WithAllowTestLoopback(true))
	defer pool.CloseAll()

	reqShort, _ := http.NewRequest(http.MethodGet, srv.URL+"/slow", nil)
	if _, err := pool.Do(context.Background(), reqShort, RequestOptions{
		Domain:                DomainConfiguredInternal,
		Timeout:               2 * time.Second,
		ResponseHeaderTimeout: 30 * time.Millisecond,
	}); err == nil {
		t.Fatal("expected short ResponseHeaderTimeout to fail")
	}

	reqLong, _ := http.NewRequest(http.MethodGet, srv.URL+"/slow", nil)
	resp, err := pool.Do(context.Background(), reqLong, RequestOptions{
		Domain:                DomainConfiguredInternal,
		Timeout:               2 * time.Second,
		ResponseHeaderTimeout: 500 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("expected long ResponseHeaderTimeout to succeed, got %v", err)
	}
	_ = resp.Body.Close()
}
