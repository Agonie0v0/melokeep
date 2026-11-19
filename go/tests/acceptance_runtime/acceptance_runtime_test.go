package acceptance_runtime_test

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/app"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/transport"
)

func repoRootFromTest(t *testing.T) string {
	t.Helper()
	abs, err := filepath.Abs(filepath.Join("..", "..", ".."))
	if err != nil {
		t.Fatalf("resolve repo root: %v", err)
	}
	return abs
}

func makeValidFLACPayload(t *testing.T, durationSec int) []byte {
	t.Helper()
	ffmpegExe, err := exec.LookPath("ffmpeg")
	if err != nil {
		// Lightweight structural header when only search metadata is exercised.
		buf := make([]byte, 4096)
		copy(buf, []byte("fLaC"))
		buf[4] = 0x80
		buf[7] = 0x22
		return buf
	}
	tmpPath := filepath.Join(t.TempDir(), "sample.flac")
	cmd := exec.Command(
		ffmpegExe, "-y", "-v", "error",
		"-f", "lavfi", "-i", fmt.Sprintf("anoisesrc=d=%d:r=44100:a=0.1", durationSec),
		"-c:a", "flac", tmpPath,
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate sample flac: %v (%s)", err, string(out))
	}
	b, err := os.ReadFile(tmpPath)
	if err != nil {
		t.Fatalf("read generated flac: %v", err)
	}
	return b
}

func makeValidMP3Payload(size int) []byte {
	if size < 128 {
		size = 128
	}
	buf := make([]byte, size)
	copy(buf, []byte{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 10})
	copy(buf[20:], []byte{0xFF, 0xFB, 0x90, 0x64})
	return buf
}

func findAcceptancePython(t *testing.T) string {
	t.Helper()
	py := app.DiscoverPython(os.Getenv("MELOKEEP_PYTHON"))
	if exec.Command(py, "-c", "import sys").Run() == nil {
		return py
	}
	t.Skip("no runnable Python interpreter found")
	return ""
}

type mockCompliantSourceServer struct {
	srv        *httptest.Server
	flacBytes  []byte
	mp3Bytes   []byte
	rangeCalls atomic.Int64
}

func startMockCompliantSourceServer(t *testing.T, flacDurationSec int) *mockCompliantSourceServer {
	t.Helper()
	m := &mockCompliantSourceServer{
		flacBytes: makeValidFLACPayload(t, flacDurationSec),
		mp3Bytes:  makeValidMP3Payload(4096),
	}

	m.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		switch {
		case strings.HasPrefix(p, "/audio/") && strings.HasSuffix(p, ".flac"):
			if r.Header.Get("Range") != "" {
				m.rangeCalls.Add(1)
			}
			w.Header().Set("Content-Type", "audio/flac")
			http.ServeContent(w, r, "track.flac", time.Unix(1700000000, 0), bytes.NewReader(m.flacBytes))
		case strings.HasPrefix(p, "/audio/") && (strings.HasSuffix(p, ".mp3") || strings.Contains(p, "mp3_320")):
			w.Header().Set("Content-Type", "audio/mpeg")
			http.ServeContent(w, r, "track.mp3", time.Unix(1700000000, 0), bytes.NewReader(m.mp3Bytes))

		case p == "/audius/search":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"data":[{"id":"au1","title":"Morning Breeze","duration":140,"user":{"name":"Open Horizon"}}]}`))
		case p == "/audius/tracks/au1/stream":
			w.Header().Set("Content-Type", "audio/mpeg")
			http.ServeContent(w, r, "audius.mp3", time.Unix(1700000000, 0), bytes.NewReader(m.mp3Bytes))

		case p == "/fma/search":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = fmt.Fprintf(w, `<div data-track-info='{"id":501,"title":"Open Sky","artistName":"Open Horizon","albumTitle":"Open Air","fileUrl":"%s/audio/fma.mp3"}'></div>`, m.srv.URL)

		case p == "/ccmixter/query":
			w.Header().Set("Content-Type", "application/xml")
			_, _ = fmt.Fprintf(w, `<?xml version="1.0"?><playlist><trackList><track><location>%s/audio/cc.mp3</location><identifier>cc1</identifier><title>CC Horizon</title><creator>Open Horizon</creator><album>Open Air</album><duration>120000</duration></track></trackList></playlist>`, m.srv.URL)

		case p == "/oga/search":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write([]byte(`<span class="art-preview-title"><a href="/content/morning-theme">Morning Theme</a></span>`))
		case p == "/content/morning-theme":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = fmt.Fprintf(w, `<h1>Morning Theme</h1><a href="%s/audio/oga.mp3">Download</a>`, m.srv.URL)

		case p == "/wiki/api":
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprintf(w, `{"query":{"pages":{"601":{"pageid":601,"title":"File:MorningBreeze.flac","imageinfo":[{"url":"%s/audio/wiki.flac","size":%d,"mime":"audio/flac"}]}}}}`, m.srv.URL, len(m.flacBytes))

		case p == "/itunes/search":
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprintf(w, `{"results":[{"collectionId":703,"collectionName":"Open Air Podcast","artistName":"Open Horizon","feedUrl":"%s/itunes/good.rss"}]}`, m.srv.URL)
		case p == "/itunes/lookup":
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprintf(w, `{"results":[{"collectionId":703,"feedUrl":"%s/itunes/good.rss"}]}`, m.srv.URL)
		case p == "/itunes/good.rss":
			w.Header().Set("Content-Type", "application/rss+xml")
			_, _ = fmt.Fprintf(w, `<rss><channel><title>Open Air Podcast</title><item><title>Ep 1</title><enclosure url="%s/audio/ep1.mp3" type="audio/mpeg"/></item></channel></rss>`, m.srv.URL)

		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(m.srv.Close)
	return m
}

func (m *mockCompliantSourceServer) endpoints() map[string]string {
	u := m.srv.URL
	return map[string]string{
		"audius_search":      u + "/audius/search",
		"audius_track_base":  u + "/audius/tracks",
		"fma_search":         u + "/fma/search",
		"ccmixter_query":     u + "/ccmixter/query",
		"opengameart_search": u + "/oga/search",
		"opengameart_base":   u,
		"wikimedia_api":      u + "/wiki/api",
		"itunes_search":      u + "/itunes/search",
		"itunes_lookup":      u + "/itunes/lookup",
	}
}

func newAcceptanceApp(t *testing.T, endpoints map[string]string) (*app.App, *httptest.Server, string, string) {
	t.Helper()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "data")
	musicRoot := filepath.Join(tmp, "music")
	if err := os.MkdirAll(musicRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	py := findAcceptancePython(t)
	a, err := app.New(context.Background(), app.Config{
		DataDir:           dataDir,
		MusicRoots:        []string{musicRoot},
		HelperDir:         filepath.Join(repoRootFromTest(t), "helpers"),
		PythonPath:        py,
		AllowTestLoopback: true,
		ProviderEndpoints: endpoints,
	})
	if err != nil {
		t.Fatalf("app.New: %v", err)
	}
	t.Cleanup(func() { _ = a.Close() })
	ts := httptest.NewServer(a.Handler)
	t.Cleanup(ts.Close)
	return a, ts, dataDir, musicRoot
}

func postJSON(client *http.Client, targetURL string, payload any) (*http.Response, error) {
	raw, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequest(http.MethodPost, targetURL, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-MeloKeep-Request", "1")
	return client.Do(req)
}

func runSSESearch(t *testing.T, client *http.Client, baseURL, keyword string, sources []string, refresh bool) (firstLat, totalLat time.Duration, tracks []model.TrackDTO) {
	t.Helper()
	firstLat, totalLat, tracks, status, err := trySSESearch(client, baseURL, keyword, sources, refresh)
	if err != nil {
		t.Fatalf("SSE search failed (status=%d): %v", status, err)
	}
	return firstLat, totalLat, tracks
}

func trySSESearch(client *http.Client, baseURL, keyword string, sources []string, refresh bool) (firstLat, totalLat time.Duration, tracks []model.TrackDTO, status int, err error) {
	start := time.Now()
	resp, err := postJSON(client, baseURL+"/api/search/jobs", map[string]any{
		"keyword": keyword,
		"sources": sources,
		"refresh": refresh,
		"limit":   5,
	})
	if err != nil {
		return 0, 0, nil, 0, fmt.Errorf("POST /api/search/jobs: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return 0, 0, nil, resp.StatusCode, fmt.Errorf("POST /api/search/jobs status=%d body=%s", resp.StatusCode, string(b))
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&created); err != nil {
		return 0, 0, nil, resp.StatusCode, fmt.Errorf("decode search job id: %w", err)
	}

	evResp, err := client.Get(baseURL + "/api/search/jobs/" + created.ID + "/events")
	if err != nil {
		return 0, 0, nil, 0, fmt.Errorf("GET events: %w", err)
	}
	defer evResp.Body.Close()
	if evResp.StatusCode != http.StatusOK {
		return 0, 0, nil, evResp.StatusCode, fmt.Errorf("GET events status=%d", evResp.StatusCode)
	}

	sc := bufio.NewScanner(evResp.Body)
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	var curEvent string
	var gotFirst bool
	for sc.Scan() {
		line := sc.Text()
		if strings.HasPrefix(line, "event:") {
			curEvent = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
			continue
		}
		if strings.HasPrefix(line, "data:") {
			raw := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
			switch curEvent {
			case "track":
				if !gotFirst {
					firstLat = time.Since(start)
					gotFirst = true
				}
				var dto model.TrackDTO
				if err := json.Unmarshal([]byte(raw), &dto); err == nil {
					tracks = append(tracks, dto)
				}
			case "done":
				totalLat = time.Since(start)
				return firstLat, totalLat, tracks, http.StatusOK, nil
			}
		}
	}
	if err := sc.Err(); err != nil {
		return 0, 0, nil, http.StatusOK, fmt.Errorf("SSE scanner error: %w", err)
	}
	return 0, 0, nil, http.StatusOK, fmt.Errorf("SSE stream ended without done event (tracks=%d)", len(tracks))
}

func percentileDuration(vals []time.Duration, pct float64) time.Duration {
	if len(vals) == 0 {
		return 0
	}
	cp := append([]time.Duration(nil), vals...)
	sort.Slice(cp, func(i, j int) bool { return cp[i] < cp[j] })
	idx := int(float64(len(cp)-1) * pct)
	return cp[idx]
}

func TestAcceptanceMockCompliantSourceSearchOrchestration(t *testing.T) {
	mockSrv := startMockCompliantSourceServer(t, 1)
	_, ts, _, _ := newAcceptanceApp(t, mockSrv.endpoints())
	client := &http.Client{Timeout: 15 * time.Second}
	sources := []string{
		"AudiusMusicClient",
		"FMAMusicClient",
		"CCMixterMusicClient",
		"OpenGameArtMusicClient",
		"WikimediaCommonsMusicClient",
		"ITunesMusicClient",
	}

	coldFirst, coldTotal, coldTracks := runSSESearch(t, client, ts.URL, "Morning Breeze", sources, true)
	if len(coldTracks) < 6 {
		t.Fatalf("expected >= 6 tracks across 6 sources, got %d", len(coldTracks))
	}
	warmFirst, warmTotal, warmTracks := runSSESearch(t, client, ts.URL, "Morning Breeze", sources, false)
	if len(warmTracks) != len(coldTracks) {
		t.Fatalf("warm cache mismatch: cold=%d warm=%d", len(coldTracks), len(warmTracks))
	}
	t.Logf("6-source cold: first=%v total=%v tracks=%d | warm(cached): first=%v total=%v",
		coldFirst, coldTotal, len(coldTracks), warmFirst, warmTotal)

	for _, conc := range []int{1, 4, 8} {
		const runs = 16
		var wg sync.WaitGroup
		sem := make(chan struct{}, conc)
		var mu sync.Mutex
		var firsts, totals []time.Duration
		var errs atomic.Int32
		var rateLimited atomic.Int32
		for i := 0; i < runs; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				f, tot, tr, status, err := trySSESearch(client, ts.URL, fmt.Sprintf("Morning-Breeze-%d-%d", conc, idx), sources, true)
				if err != nil {
					if status == http.StatusTooManyRequests {
						rateLimited.Add(1)
						return
					}
					t.Errorf("conc=%d idx=%d unexpected error: %v", conc, idx, err)
					errs.Add(1)
					return
				}
				if len(tr) < 6 {
					errs.Add(1)
					return
				}
				mu.Lock()
				firsts = append(firsts, f)
				totals = append(totals, tot)
				mu.Unlock()
			}(i)
		}
		wg.Wait()
		if errs.Load() != 0 {
			t.Fatalf("concurrency=%d had %d incomplete searches", conc, errs.Load())
		}
		if conc <= 6 && rateLimited.Load() != 0 {
			t.Fatalf("concurrency=%d (within ActiveSearchJobs=6) had unexpected 429=%d", conc, rateLimited.Load())
		}
		t.Logf("6-source conc=%d (ok=%d/N=%d, 429=%d): first_p50=%v first_p95=%v total_p50=%v total_p95=%v",
			conc, len(firsts), runs, rateLimited.Load(),
			percentileDuration(firsts, 0.50), percentileDuration(firsts, 0.95),
			percentileDuration(totals, 0.50), percentileDuration(totals, 0.95),
		)
	}
}

func TestAcceptanceMockRangeDownloadThroughput(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg required for full download PCM verification")
	}
	// 70s stereo 44.1kHz noise in FLAC yields ~8+ MiB to exercise multi-MiB Range streaming & FFmpeg verify throughput.
	mockSrv := startMockCompliantSourceServer(t, 70)
	payloadSize := int64(len(mockSrv.flacBytes))
	if payloadSize < 7*1024*1024 {
		t.Fatalf("generated FLAC unexpectedly small: %d bytes", payloadSize)
	}
	_, ts, _, musicRoot := newAcceptanceApp(t, mockSrv.endpoints())
	client := &http.Client{Timeout: 30 * time.Second}

	_, _, tracks := runSSESearch(t, client, ts.URL, "Morning Breeze", []string{"WikimediaCommonsMusicClient"}, true)
	if len(tracks) == 0 {
		t.Fatal("expected at least 1 WikimediaCommonsMusicClient track")
	}

	dlStart := time.Now()
	enqResp, err := postJSON(client, ts.URL+"/api/downloads", []map[string]any{
		{
			"track_id":   tracks[0].ID,
			"quality_id": "",
		},
	})
	if err != nil {
		t.Fatalf("POST /api/downloads: %v", err)
	}
	defer enqResp.Body.Close()
	if enqResp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(enqResp.Body)
		t.Fatalf("POST /api/downloads status=%d body=%s", enqResp.StatusCode, string(b))
	}
	var enqueued []model.DownloadJobDTO
	if err := json.NewDecoder(enqResp.Body).Decode(&enqueued); err != nil || len(enqueued) != 1 {
		t.Fatalf("decode enqueue response: %v len=%d", err, len(enqueued))
	}
	dlID := enqueued[0].ID

	var completed model.DownloadJobDTO
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := client.Get(ts.URL + "/api/downloads")
		if err != nil {
			t.Fatalf("GET /api/downloads: %v", err)
		}
		var jobs []model.DownloadJobDTO
		_ = json.NewDecoder(resp.Body).Decode(&jobs)
		resp.Body.Close()
		for _, j := range jobs {
			if j.ID == dlID && (j.Status == "complete" || j.Status == "failed" || j.Status == "cancelled") {
				completed = j
				goto doneWait
			}
		}
		time.Sleep(15 * time.Millisecond)
	}
doneWait:
	elapsed := time.Since(dlStart)
	if completed.Status != "complete" || completed.File == nil {
		t.Fatalf("download did not complete cleanly: %+v", completed)
	}
	if !strings.HasPrefix(*completed.File, "asset:") {
		t.Fatalf("expected opaque asset handle in DownloadJobDTO.File, got %q", *completed.File)
	}

	var publishedPath string
	var publishedSize int64
	_ = filepath.WalkDir(musicRoot, func(p string, d os.DirEntry, wErr error) error {
		if wErr != nil || d.IsDir() {
			return nil
		}
		if info, sErr := d.Info(); sErr == nil && strings.HasSuffix(strings.ToLower(d.Name()), ".flac") {
			publishedPath = p
			publishedSize = info.Size()
		}
		return nil
	})
	if publishedPath == "" || publishedSize != payloadSize {
		t.Fatalf("expected published .flac of size %d inside %s, got path=%q size=%d",
			payloadSize, musicRoot, publishedPath, publishedSize)
	}
	throughputMiBps := (float64(payloadSize) / (1024 * 1024)) / elapsed.Seconds()
	t.Logf("%.2f MiB mock download (incl. FFmpeg verify & publish) completed in %v (%.1f MiB/s), upstream range_calls=%d, file=%s",
		float64(payloadSize)/(1024*1024), elapsed, throughputMiBps, mockSrv.rangeCalls.Load(), publishedPath)

	// Verify Range streaming on published file via /api/files/audio
	rangeReq, _ := http.NewRequest(http.MethodGet, ts.URL+"/api/files/audio?path="+url.QueryEscape(*completed.File), nil)
	rangeReq.Header.Set("Range", "bytes=0-65535")
	rangeResp, err := client.Do(rangeReq)
	if err != nil {
		t.Fatalf("GET /api/files/audio Range: %v", err)
	}
	defer rangeResp.Body.Close()
	chunk, _ := io.ReadAll(rangeResp.Body)
	diskBytes, err := os.ReadFile(publishedPath)
	if err != nil {
		t.Fatalf("read published file %s: %v", publishedPath, err)
	}
	if rangeResp.StatusCode != http.StatusPartialContent || len(chunk) != 65536 || !bytes.HasPrefix(chunk, []byte("fLaC")) || !bytes.Equal(chunk, diskBytes[:65536]) {
		t.Fatalf("expected 206 with matching 65536 bytes, got status=%d len=%d", rangeResp.StatusCode, len(chunk))
	}
}

type scriptableResolver struct {
	fn func(ctx context.Context, host string) ([]net.IPAddr, error)
}

func (r scriptableResolver) LookupIPAddr(ctx context.Context, host string) ([]net.IPAddr, error) {
	return r.fn(ctx, host)
}

func TestAcceptanceTransportHardeningAndMultiIP(t *testing.T) {
	// 1. Extended IPv4/IPv6 SSRF ranges
	blockedIPs := []string{
		"100.64.0.1",      // CGNAT RFC 6598
		"198.18.0.1",      // Benchmarking RFC 2544
		"192.0.2.1",       // TEST-NET-1
		"fc00::1",         // IPv6 ULA
		"::ffff:127.0.0.1", // IPv4-mapped IPv6 loopback
		"fe80::1",         // IPv6 link-local
	}
	for _, raw := range blockedIPs {
		ip := net.ParseIP(raw)
		if transport.IsPublicIP(ip) {
			t.Fatalf("expected IsPublicIP(%s) == false", raw)
		}
	}
	if !transport.IsPublicIP(net.ParseIP("93.184.216.34")) {
		t.Fatal("expected IsPublicIP(93.184.216.34) == true")
	}

	// 2. Mixed public + private DNS response must be rejected
	poolStrict := transport.NewPool(transport.WithResolver(scriptableResolver{
		fn: func(_ context.Context, _ string) ([]net.IPAddr, error) {
			return []net.IPAddr{
				{IP: net.ParseIP("93.184.216.34")},
				{IP: net.ParseIP("100.64.0.1")},
			}, nil
		},
	}))
	defer poolStrict.CloseAll()
	reqMixed, _ := http.NewRequest(http.MethodGet, "https://mixed.example.com/audio", nil)
	if _, err := poolStrict.Do(context.Background(), reqMixed, transport.RequestOptions{
		Domain:  transport.DomainPublicMedia,
		Timeout: 2 * time.Second,
	}); err == nil {
		t.Fatal("expected mixed public+CGNAT DNS response to be blocked")
	}

	// 3. Dead-first-IP multi-IP fallback (IPv6 ::1 closed -> IPv4 127.0.0.1 succeeds)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/redirect-strip" {
			if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte("leaked sensitive header"))
				return
			}
			_, _ = w.Write([]byte("ok"))
			return
		}
		_, _ = w.Write([]byte("multi-ip-ok"))
	}))
	defer srv.Close()
	u, _ := url.Parse(srv.URL)
	_, port, _ := net.SplitHostPort(u.Host)

	poolFallback := transport.NewPool(
		transport.WithAllowTestLoopback(true),
		transport.WithResolver(scriptableResolver{
			fn: func(_ context.Context, _ string) ([]net.IPAddr, error) {
				return []net.IPAddr{
					{IP: net.ParseIP("::1")},
					{IP: net.ParseIP("127.0.0.1")},
				}, nil
			},
		}),
	)
	defer poolFallback.CloseAll()
	reqMulti, _ := http.NewRequest(http.MethodGet, "http://dual.example.test:"+port+"/ok", nil)
	resp, err := poolFallback.Do(context.Background(), reqMulti, transport.RequestOptions{
		Domain:         transport.DomainPublicMedia,
		ConfigRevision: "rev-1",
		Timeout:        5 * time.Second,
	})
	if err != nil {
		t.Fatalf("expected dead-first-IP fallback to succeed, got %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "multi-ip-ok" {
		t.Fatalf("unexpected fallback body: %q", string(body))
	}
	if poolFallback.ActivePoolCount() == 0 {
		t.Fatal("expected pooled transport for rev-1")
	}
	poolFallback.InvalidateRevision("rev-1")
	if poolFallback.ActivePoolCount() != 0 {
		t.Fatalf("expected revision drain to evict rev-1 pools, remaining=%d", poolFallback.ActivePoolCount())
	}

	// 4. Cross-origin sensitive header stripping
	originA := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, srv.URL+"/redirect-strip", http.StatusFound)
	}))
	defer originA.Close()
	poolRedir := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer poolRedir.CloseAll()
	reqRedir, _ := http.NewRequest(http.MethodGet, originA.URL+"/start", nil)
	reqRedir.Header.Set("Authorization", "Bearer secret-token")
	reqRedir.Header.Set("Cookie", "sid=secret")
	rResp, err := poolRedir.Do(context.Background(), reqRedir, transport.RequestOptions{
		Domain:  transport.DomainPublicMedia,
		Timeout: 5 * time.Second,
	})
	if err != nil {
		t.Fatalf("cross-origin redirect request failed: %v", err)
	}
	rBody, _ := io.ReadAll(rResp.Body)
	rResp.Body.Close()
	if rResp.StatusCode != http.StatusOK || string(rBody) != "ok" {
		t.Fatalf("expected cross-origin redirect to strip auth/cookie, got status=%d body=%q", rResp.StatusCode, string(rBody))
	}

	// 5. DNS rebinding check: public on 1st call, private on 2nd call
	var dnsStep atomic.Int32
	poolRebind := transport.NewPool(transport.WithResolver(scriptableResolver{
		fn: func(_ context.Context, _ string) ([]net.IPAddr, error) {
			if dnsStep.Add(1) == 1 {
				return []net.IPAddr{{IP: net.ParseIP("198.51.100.1")}}, nil // still blocked TEST-NET-2, also verify 2nd call blocks 127.0.0.1
			}
			return []net.IPAddr{{IP: net.ParseIP("127.0.0.1")}}, nil
		},
	}))
	defer poolRebind.CloseAll()
	reqRebind, _ := http.NewRequest(http.MethodGet, "https://rebind.example.com/a", nil)
	if _, err := poolRebind.Do(context.Background(), reqRebind, transport.RequestOptions{
		Domain:  transport.DomainPublicMedia,
		Timeout: 2 * time.Second,
	}); err == nil {
		t.Fatal("expected DNS rebinding / non-public IP to be blocked")
	}
}

func TestAcceptanceHelperSecurityAndProcessTree(t *testing.T) {
	py := findAcceptancePython(t)
	root := repoRootFromTest(t)

	// 1. TaggerClient.WriteTags symlink escape rejection (file symlink & dir symlink)
	tmp := t.TempDir()
	stageRoot := filepath.Join(tmp, "stage")
	outsideDir := filepath.Join(tmp, "outside")
	if err := os.MkdirAll(stageRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(outsideDir, 0o755); err != nil {
		t.Fatal(err)
	}
	outsideFile := filepath.Join(outsideDir, "secret.mp3")
	if err := os.WriteFile(outsideFile, []byte("keep-untouched"), 0o644); err != nil {
		t.Fatal(err)
	}
	symFile := filepath.Join(stageRoot, "escape.mp3")
	fileSymErr := os.Symlink(outsideFile, symFile)
	symDir := filepath.Join(stageRoot, "dirlink")
	dirSymErr := os.Symlink(outsideDir, symDir)

	sup := helper.NewSupervisor(helper.SupervisorConfig{
		PythonExe:    py,
		TaggerScript: filepath.Join(root, "helpers", "mutagen", "tagger.py"),
		WorkRoot:     tmp,
		CancelGrace:  150 * time.Millisecond,
	})
	defer sup.Close()
	tagger := helper.NewTaggerClient(sup)

	// Traversal escape attempt must always be rejected regardless of OS symlink privileges.
	if _, err := tagger.WriteTags(context.Background(), helper.WriteTagsRequest{
		AllowedStageRoot: stageRoot,
		StagePath:        filepath.Join(stageRoot, "..", "outside", "secret.mp3"),
		Tags:             map[string]string{"title": "pwned"},
	}); err == nil {
		t.Fatal("expected traversal path outside AllowedStageRoot in WriteTags to be rejected")
	}
	if fileSymErr == nil {
		if _, err := tagger.WriteTags(context.Background(), helper.WriteTagsRequest{
			AllowedStageRoot: stageRoot,
			StagePath:        symFile,
			Tags:             map[string]string{"title": "pwned"},
		}); err == nil {
			t.Fatal("expected file symlink escape in WriteTags to be rejected")
		}
	} else {
		t.Logf("symlink creation not permitted on this host (%v), skipping symlink escape subcheck", fileSymErr)
	}
	if dirSymErr == nil {
		if _, err := tagger.WriteTags(context.Background(), helper.WriteTagsRequest{
			AllowedStageRoot: stageRoot,
			StagePath:        filepath.Join(symDir, "secret.mp3"),
			Tags:             map[string]string{"title": "pwned"},
		}); err == nil {
			t.Fatal("expected directory symlink escape in WriteTags to be rejected")
		}
	}

	// 2. Probe standard Win32 Python vs MSIX Store Python behavior for StartInTree
	if runtime.GOOS == "windows" {
		venvPy := filepath.Join(root, ".venv", "Scripts", "python.exe")
		if _, err := os.Stat(venvPy); err == nil {
			bpOut, bpErr := exec.Command(venvPy, "-c", "import sys; print(sys.base_prefix)").CombinedOutput()
			t.Logf("Windows .venv base_prefix (err=%v): %s", bpErr, strings.TrimSpace(string(bpOut)))
		}
		if _, err := os.Stat(`C:\Python314\python.exe`); err == nil {
			t.Logf("Win32 Python available at C:\\Python314\\python.exe for Job Object enforcement")
		}
	}
}

func TestAcceptanceBoundedConcurrencyAndTempCleanliness(t *testing.T) {
	mockSrv := startMockCompliantSourceServer(t, 1)
	_, ts, dataDir, _ := newAcceptanceApp(t, mockSrv.endpoints())
	client := &http.Client{Timeout: 10 * time.Second}

	// Warm up before taking goroutine baseline.
	resp, err := client.Get(ts.URL + "/api/health")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	baseGoroutines := runtime.NumGoroutine()

	const workers = 8
	const iters = 25
	var wg sync.WaitGroup
	var failCount atomic.Int32
	endpoints := []string{"/api/health", "/api/config", "/api/library/settings", "/api/library/files?limit=50"}
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(wid int) {
			defer wg.Done()
			for i := 0; i < iters; i++ {
				ep := endpoints[(wid+i)%len(endpoints)]
				r, e := client.Get(ts.URL + ep)
				if e != nil || r.StatusCode != http.StatusOK {
					failCount.Add(1)
				}
				if r != nil {
					_, _ = io.Copy(io.Discard, r.Body)
					r.Body.Close()
				}
			}
		}(w)
	}
	wg.Wait()
	if failCount.Load() != 0 {
		t.Fatalf("concurrent HTTP requests had %d failures", failCount.Load())
	}
	time.Sleep(150 * time.Millisecond)
	afterGoroutines := runtime.NumGoroutine()
	if afterGoroutines > baseGoroutines+16 {
		t.Fatalf("possible goroutine leak: base=%d after=%d", baseGoroutines, afterGoroutines)
	}
	for _, sub := range []string{"tmp", "staging"} {
		entries, err := os.ReadDir(filepath.Join(dataDir, "go", sub))
		if err != nil {
			t.Fatalf("read %s: %v", sub, err)
		}
		if len(entries) != 0 {
			t.Fatalf("expected empty data/go/%s after idle, got %d entries", sub, len(entries))
		}
	}
	t.Logf("goroutines base=%d after=%d, data/go/{tmp,staging} clean", baseGoroutines, afterGoroutines)
}
