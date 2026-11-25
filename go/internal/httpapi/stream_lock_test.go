package httpapi

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
)

func seedLocalStreamTestAsset(t *testing.T, st *store.Store, root, relPath string, data []byte) (string, string) {
	t.Helper()
	absPath := filepath.Join(root, filepath.FromSlash(relPath))
	if err := os.MkdirAll(filepath.Dir(absPath), 0o755); err != nil {
		t.Fatalf("mkdir %s: %v", filepath.Dir(absPath), err)
	}
	if err := os.WriteFile(absPath, data, 0o644); err != nil {
		t.Fatalf("write %s: %v", absPath, err)
	}
	info, err := os.Stat(absPath)
	if err != nil {
		t.Fatalf("stat %s: %v", absPath, err)
	}
	rec, err := st.UpsertAsset(context.Background(), store.AssetRecord{
		RootID:   "root-1",
		RelPath:  relPath,
		Size:     info.Size(),
		MTimeNS:  info.ModTime().UnixNano(),
		Title:    "晨风序曲",
		Artist:   "Open Lab",
		Editable: true,
	}, 0, true)
	if err != nil {
		t.Fatalf("UpsertAsset %s: %v", relPath, err)
	}
	return rec.ID, absPath
}

func makeCheckBytes(size int, salt byte) []byte {
	buf := make([]byte, size)
	for i := range buf {
		buf[i] = byte((i*31 + (i >> 8) + int(salt)) & 0xff)
	}
	if size >= 4 {
		copy(buf[:4], []byte("fLaC"))
	}
	return buf
}

func TestLocalAudioInlineRangeChunkCapAndHeaders(t *testing.T) {
	srv, st, root := newTestServer(t, nil)
	const totalSize = (2 << 20) + (512 << 10) // 2.5 MiB
	payload := makeCheckBytes(totalSize, 17)
	assetID, _ := seedLocalStreamTestAsset(t, st, root, "open-lab/sample.flac", payload)

	audioPath := "/api/files/audio?path=" + url.QueryEscape("asset:"+assetID)
	contentPath := "/api/files/content?path=" + url.QueryEscape("asset:"+assetID)

	// 1. Open-ended Range "bytes=0-" -> capped to 1 MiB chunk (206), request Header unmodified.
	reqOpen := httptest.NewRequest(http.MethodGet, audioPath, nil)
	reqOpen.Header.Set("Range", "bytes=0-")
	recOpen := httptest.NewRecorder()
	srv.ServeHTTP(recOpen, reqOpen)
	if reqOpen.Header.Get("Range") != "bytes=0-" {
		t.Fatalf("caller request Range header mutated: %q", reqOpen.Header.Get("Range"))
	}
	if recOpen.Code != http.StatusPartialContent {
		t.Fatalf("open range status = %d, want 206; body=%s", recOpen.Code, recOpen.Body.String())
	}
	wantCR := fmt.Sprintf("bytes 0-%d/%d", inlineStreamMaxRangeBytes-1, totalSize)
	if got := recOpen.Header().Get("Content-Range"); got != wantCR {
		t.Fatalf("Content-Range = %q, want %q", got, wantCR)
	}
	if got := recOpen.Header().Get("Content-Length"); got != strconv.FormatInt(inlineStreamMaxRangeBytes, 10) {
		t.Fatalf("Content-Length = %q, want %d", got, inlineStreamMaxRangeBytes)
	}
	if got := recOpen.Header().Get("Accept-Ranges"); got != "bytes" {
		t.Fatalf("Accept-Ranges = %q, want bytes", got)
	}
	if got := recOpen.Header().Get("ETag"); got == "" {
		t.Fatalf("expected non-empty ETag header")
	}
	if got := recOpen.Header().Get("Cache-Control"); got != "private, no-cache" {
		t.Fatalf("Cache-Control = %q, want private, no-cache", got)
	}
	if recOpen.Body.Len() != int(inlineStreamMaxRangeBytes) || !bytes.Equal(recOpen.Body.Bytes(), payload[:inlineStreamMaxRangeBytes]) {
		t.Fatalf("open range body mismatch: len=%d", recOpen.Body.Len())
	}

	// 2. Oversized closed Range "bytes=0-<size-1>" -> also capped to 1 MiB chunk.
	reqClosed := httptest.NewRequest(http.MethodGet, audioPath, nil)
	reqClosed.Header.Set("Range", fmt.Sprintf("bytes=0-%d", totalSize-1))
	recClosed := httptest.NewRecorder()
	srv.ServeHTTP(recClosed, reqClosed)
	if recClosed.Code != http.StatusPartialContent || recClosed.Header().Get("Content-Range") != wantCR {
		t.Fatalf("oversized closed range unexpected: code=%d cr=%q", recClosed.Code, recClosed.Header().Get("Content-Range"))
	}
	if !bytes.Equal(recClosed.Body.Bytes(), payload[:inlineStreamMaxRangeBytes]) {
		t.Fatalf("oversized closed range body mismatch")
	}

	// 3. Tail open-ended Range "bytes=2097152-" -> exact remaining 512 KiB up to size-1.
	const tailStart = 2 << 20 // 2097152
	reqTail := httptest.NewRequest(http.MethodGet, audioPath, nil)
	reqTail.Header.Set("Range", fmt.Sprintf("bytes=%d-", tailStart))
	recTail := httptest.NewRecorder()
	srv.ServeHTTP(recTail, reqTail)
	wantTailCR := fmt.Sprintf("bytes %d-%d/%d", tailStart, totalSize-1, totalSize)
	if recTail.Code != http.StatusPartialContent || recTail.Header().Get("Content-Range") != wantTailCR {
		t.Fatalf("tail range unexpected: code=%d cr=%q want=%q", recTail.Code, recTail.Header().Get("Content-Range"), wantTailCR)
	}
	if !bytes.Equal(recTail.Body.Bytes(), payload[tailStart:]) {
		t.Fatalf("tail range body mismatch: len=%d want=%d", recTail.Body.Len(), totalSize-tailStart)
	}

	// 4. Small probe Range "bytes=0-1" -> preserved as 2 bytes.
	reqProbe := httptest.NewRequest(http.MethodGet, audioPath, nil)
	reqProbe.Header.Set("Range", "bytes=0-1")
	recProbe := httptest.NewRecorder()
	srv.ServeHTTP(recProbe, reqProbe)
	if recProbe.Code != http.StatusPartialContent || recProbe.Body.Len() != 2 || !bytes.Equal(recProbe.Body.Bytes(), payload[:2]) {
		t.Fatalf("probe range unexpected: code=%d len=%d", recProbe.Code, recProbe.Body.Len())
	}

	// 5. Suffix Range "bytes=-64" -> preserved as last 64 bytes.
	reqSuffix := httptest.NewRequest(http.MethodGet, audioPath, nil)
	reqSuffix.Header.Set("Range", "bytes=-64")
	recSuffix := httptest.NewRecorder()
	srv.ServeHTTP(recSuffix, reqSuffix)
	if recSuffix.Code != http.StatusPartialContent || recSuffix.Body.Len() != 64 || !bytes.Equal(recSuffix.Body.Bytes(), payload[totalSize-64:]) {
		t.Fatalf("suffix range unexpected: code=%d len=%d cr=%q", recSuffix.Code, recSuffix.Body.Len(), recSuffix.Header().Get("Content-Range"))
	}

	// 6. Out-of-bounds Range "bytes=99999999-" -> 416.
	reqOOS := httptest.NewRequest(http.MethodGet, audioPath, nil)
	reqOOS.Header.Set("Range", "bytes=99999999-")
	recOOS := httptest.NewRecorder()
	srv.ServeHTTP(recOOS, reqOOS)
	if recOOS.Code != http.StatusRequestedRangeNotSatisfiable {
		t.Fatalf("out-of-bounds range status = %d, want 416", recOOS.Code)
	}

	// 7. No Range -> 200 OK full payload.
	reqFull := httptest.NewRequest(http.MethodGet, audioPath, nil)
	recFull := httptest.NewRecorder()
	srv.ServeHTTP(recFull, reqFull)
	if recFull.Code != http.StatusOK || recFull.Body.Len() != totalSize || !bytes.Equal(recFull.Body.Bytes(), payload) {
		t.Fatalf("full GET unexpected: code=%d len=%d", recFull.Code, recFull.Body.Len())
	}

	// 8. Attachment endpoint /api/files/content with Range: bytes=0- -> NOT capped, full 2.5 MiB.
	reqAttach := httptest.NewRequest(http.MethodGet, contentPath, nil)
	reqAttach.Header.Set("Range", "bytes=0-")
	recAttach := httptest.NewRecorder()
	srv.ServeHTTP(recAttach, reqAttach)
	if recAttach.Code != http.StatusPartialContent {
		t.Fatalf("attachment range status = %d, want 206", recAttach.Code)
	}
	if !strings.Contains(recAttach.Header().Get("Content-Disposition"), "attachment") {
		t.Fatalf("expected attachment disposition, got %q", recAttach.Header().Get("Content-Disposition"))
	}
	wantFullCR := fmt.Sprintf("bytes 0-%d/%d", totalSize-1, totalSize)
	if recAttach.Header().Get("Content-Range") != wantFullCR {
		t.Fatalf("attachment Content-Range = %q, want %q", recAttach.Header().Get("Content-Range"), wantFullCR)
	}
	if recAttach.Body.Len() != totalSize || !bytes.Equal(recAttach.Body.Bytes(), payload) {
		t.Fatalf("attachment body mismatch: len=%d want=%d", recAttach.Body.Len(), totalSize)
	}
}

func TestLocalAudioPlaybackThenImmediateWriteLockSucceeds(t *testing.T) {
	srv, st, root := newTestServer(t, nil)
	locks := srv.deps.Files.Locks()

	oldUnit := []byte("OPENLAB-OLD-FRAME-")
	oldPayload := bytes.Repeat(oldUnit, (3<<20)/len(oldUnit)+1)[:3<<20]
	assetID, absPath := seedLocalStreamTestAsset(t, st, root, "open-lab/morning-breeze.flac", oldPayload)

	ts := httptest.NewServer(srv)
	defer ts.Close()

	tr := &http.Transport{
		DisableKeepAlives:   false,
		MaxIdleConnsPerHost: 4,
	}
	defer tr.CloseIdleConnections()
	client := &http.Client{
		Transport: tr,
		Timeout:   5 * time.Second,
	}

	audioURL := ts.URL + "/api/files/audio?path=" + url.QueryEscape("asset:"+assetID)

	// 1. Stream first chunk with Range: bytes=0- over a Keep-Alive connection.
	req1, err := http.NewRequest(http.MethodGet, audioURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	req1.Header.Set("Range", "bytes=0-")
	resp1, err := client.Do(req1)
	if err != nil {
		t.Fatalf("GET range chunk: %v", err)
	}
	body1, err := io.ReadAll(resp1.Body)
	_ = resp1.Body.Close()
	if err != nil {
		t.Fatalf("read chunk body: %v", err)
	}
	if resp1.StatusCode != http.StatusPartialContent {
		t.Fatalf("expected 206, got %d", resp1.StatusCode)
	}
	if int64(len(body1)) != inlineStreamMaxRangeBytes || !bytes.Equal(body1, oldPayload[:inlineStreamMaxRangeBytes]) {
		t.Fatalf("unexpected chunk body len=%d", len(body1))
	}
	oldETag := resp1.Header.Get("ETag")
	if oldETag == "" {
		t.Fatalf("expected non-empty ETag")
	}

	// 2. Keep-Alive idle connection stays open, yet AcquireWrite succeeds immediately.
	lockCtx, lockCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer lockCancel()
	start := time.Now()
	releaseWrite, err := locks.AcquireWrite(lockCtx, assetID)
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("AcquireWrite failed immediately after range stream: %v", err)
	}
	if elapsed > 500*time.Millisecond {
		releaseWrite()
		t.Fatalf("AcquireWrite took too long (%v); read lock may not have been released promptly", elapsed)
	}

	// Modify file content and bump MTime while holding the write lock.
	newUnit := []byte("OPENLAB-NEW-FRAME-")
	newPayload := bytes.Repeat(newUnit, (2<<20)/len(newUnit)+1)[:2<<20]
	if err := os.WriteFile(absPath, newPayload, 0o644); err != nil {
		releaseWrite()
		t.Fatalf("rewrite asset: %v", err)
	}
	newMTime := time.Now().Add(2 * time.Second).Truncate(time.Second)
	if err := os.Chtimes(absPath, newMTime, newMTime); err != nil {
		releaseWrite()
		t.Fatalf("chtimes asset: %v", err)
	}
	releaseWrite()

	// 3. Reuse Keep-Alive client with stale If-Range + Range: bytes=0- -> 200 OK with latest bytes only.
	req2, err := http.NewRequest(http.MethodGet, audioURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	req2.Header.Set("Range", "bytes=0-")
	req2.Header.Set("If-Range", oldETag)
	resp2, err := client.Do(req2)
	if err != nil {
		t.Fatalf("GET after modify: %v", err)
	}
	body2, err := io.ReadAll(resp2.Body)
	_ = resp2.Body.Close()
	if err != nil {
		t.Fatalf("read modified body: %v", err)
	}
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK when If-Range mismatches updated ETag, got %d", resp2.StatusCode)
	}
	if newETag := resp2.Header.Get("ETag"); newETag == "" || newETag == oldETag {
		t.Fatalf("expected updated ETag different from %q, got %q", oldETag, newETag)
	}
	if !bytes.Equal(body2, newPayload) {
		t.Fatalf("modified response body mismatch: len=%d want=%d", len(body2), len(newPayload))
	}
	if bytes.Contains(body2, oldUnit) {
		t.Fatalf("stale data leaked into updated response")
	}
}

func TestServeLocalPathStalledClientReleasesReadLock(t *testing.T) {
	origTimeout := inlineStreamWriteStallTimeout
	inlineStreamWriteStallTimeout = 150 * time.Millisecond
	t.Cleanup(func() {
		inlineStreamWriteStallTimeout = origTimeout
	})

	srv, st, root := newTestServer(t, nil)
	locks := srv.deps.Files.Locks()
	locks.WriteWaitTimeout = 3 * time.Second

	const largeSize = 24 << 20 // 24 MiB to exceed loopback TCP buffers
	payload := makeCheckBytes(largeSize, 42)
	assetID, _ := seedLocalStreamTestAsset(t, st, root, "open-lab/stalled-sample.flac", payload)

	ts := httptest.NewServer(srv)
	defer ts.Close()

	u, err := url.Parse(ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.DialTimeout("tcp", u.Host, 2*time.Second)
	if err != nil {
		t.Fatalf("dial %s: %v", u.Host, err)
	}
	defer conn.Close()
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetReadBuffer(4096)
	}

	reqRaw := fmt.Sprintf("GET /api/files/audio?path=%s HTTP/1.1\r\nHost: %s\r\nConnection: keep-alive\r\n\r\n",
		url.QueryEscape("asset:"+assetID), u.Host)
	if _, err := io.WriteString(conn, reqRaw); err != nil {
		t.Fatalf("send raw request: %v", err)
	}

	// Read only status line + headers, then stop reading body bytes while keeping TCP socket open.
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	br := bufio.NewReaderSize(conn, 1024)
	statusLine, err := br.ReadString('\n')
	if err != nil {
		t.Fatalf("read status line: %v", err)
	}
	if !strings.Contains(statusLine, "200 OK") {
		t.Fatalf("expected 200 OK, got %q", statusLine)
	}
	for {
		line, hErr := br.ReadString('\n')
		if hErr != nil {
			t.Fatalf("read response header: %v", hErr)
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}
	_ = conn.SetReadDeadline(time.Time{})

	// Client is stalled (socket open, no body read). Server write stall timeout (~150ms)
	// should abort the stalled write and release the read lock well before WriteWaitTimeout (3s).
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	releaseWrite, err := locks.AcquireWrite(ctx, assetID)
	if err != nil {
		t.Fatalf("AcquireWrite failed for stalled client: %v", err)
	}
	releaseWrite()

	// Verify no read lock remains leaked afterwards.
	verifyCtx, verifyCancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer verifyCancel()
	releaseVerify, err := locks.AcquireWrite(verifyCtx, assetID)
	if err != nil {
		t.Fatalf("expected clean read lock release after stall cleanup: %v", err)
	}
	releaseVerify()
}

func TestCapInlineRangeHeader(t *testing.T) {
	const total = 3 << 20
	const chunk = 1 << 20
	cases := []struct {
		name      string
		raw       string
		totalSize int64
		maxChunk  int64
		want      string
		wantOK    bool
	}{
		{name: "empty", raw: "", totalSize: total, maxChunk: chunk},
		{name: "zero total", raw: "bytes=0-", totalSize: 0, maxChunk: chunk},
		{name: "zero chunk", raw: "bytes=0-", totalSize: total, maxChunk: 0},
		{name: "non-bytes unit", raw: "items=0-10", totalSize: total, maxChunk: chunk},
		{name: "multi-range", raw: "bytes=0-10, 20-30", totalSize: total, maxChunk: chunk},
		{name: "suffix", raw: "bytes=-64", totalSize: total, maxChunk: chunk},
		{name: "plus sign invalid", raw: "bytes=+0-", totalSize: total, maxChunk: chunk},
		{name: "reversed end", raw: "bytes=100-50", totalSize: total, maxChunk: chunk},
		{name: "start out of bounds", raw: fmt.Sprintf("bytes=%d-", total), totalSize: total, maxChunk: chunk},
		{name: "exact chunk size untouched", raw: fmt.Sprintf("bytes=0-%d", chunk-1), totalSize: total, maxChunk: chunk},
		{name: "one byte over chunk capped", raw: fmt.Sprintf("bytes=0-%d", chunk), totalSize: total, maxChunk: chunk, want: fmt.Sprintf("bytes=0-%d", chunk-1), wantOK: true},
		{name: "case insensitive open", raw: "BYTES=1024-", totalSize: total, maxChunk: chunk, want: fmt.Sprintf("bytes=1024-%d", 1024+chunk-1), wantOK: true},
		{name: "clamped end still capped", raw: fmt.Sprintf("bytes=0-%d", total*2), totalSize: total, maxChunk: chunk, want: fmt.Sprintf("bytes=0-%d", chunk-1), wantOK: true},
	}
	for _, tc := range cases {
		got, ok := capInlineRangeHeader(tc.raw, tc.totalSize, tc.maxChunk)
		if got != tc.want || ok != tc.wantOK {
			t.Errorf("%s: capInlineRangeHeader(%q, %d, %d) = (%q, %v), want (%q, %v)",
				tc.name, tc.raw, tc.totalSize, tc.maxChunk, got, ok, tc.want, tc.wantOK)
		}
	}
}

func TestCapOpenEndedRangeHeader(t *testing.T) {
	const chunk = streamRangeChunkBytes
	cases := []struct {
		name   string
		raw    string
		chunk  int64
		want   string
		wantOK bool
	}{
		{name: "empty", raw: "", chunk: chunk},
		{name: "zero chunk", raw: "bytes=0-", chunk: 0},
		{name: "open zero", raw: "bytes=0-", chunk: chunk, want: fmt.Sprintf("bytes=0-%d", chunk-1), wantOK: true},
		{name: "open offset case insensitive", raw: "BYTES= 1024 - ", chunk: chunk, want: fmt.Sprintf("bytes=1024-%d", 1024+chunk-1), wantOK: true},
		{name: "closed small untouched", raw: "bytes=0-1023", chunk: chunk},
		{name: "closed large untouched", raw: fmt.Sprintf("bytes=0-%d", chunk*2), chunk: chunk},
		{name: "suffix untouched", raw: "bytes=-64", chunk: chunk},
		{name: "multi-range untouched", raw: "bytes=0-10, 20-", chunk: chunk},
		{name: "non-bytes unit untouched", raw: "items=0-", chunk: chunk},
		{name: "plus sign untouched", raw: "bytes=+0-", chunk: chunk},
		{name: "missing hyphen untouched", raw: "bytes=0", chunk: chunk},
		{name: "empty start untouched", raw: "bytes=-", chunk: chunk},
		{name: "alpha start untouched", raw: "bytes=abc-", chunk: chunk},
	}
	for _, tc := range cases {
		got, ok := capOpenEndedRangeHeader(tc.raw, tc.chunk)
		if got != tc.want || ok != tc.wantOK {
			t.Errorf("%s: capOpenEndedRangeHeader(%q, %d) = (%q, %v), want (%q, %v)",
				tc.name, tc.raw, tc.chunk, got, ok, tc.want, tc.wantOK)
		}
	}
}

func TestHandleStreamRangeCapFallbackAnd401Refresh(t *testing.T) {
	var seenRanges []string
	var seenIfRanges []string
	var mode atomic.Int32 // 0=206 capped, 1=416 then 206 on original, 2=400 then 206 on original, 3=401 refresh
	var resolveCalls atomic.Int32
	payload := []byte("STREAM-REMOTE-PAYLOAD-0123456789")

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rng := r.Header.Get("Range")
		seenRanges = append(seenRanges, r.URL.Path+":"+rng)
		seenIfRanges = append(seenIfRanges, r.Header.Get("If-Range"))
		switch r.URL.Path {
		case "/cap-ok":
			if rng == fmt.Sprintf("bytes=0-%d", streamRangeChunkBytes-1) {
				w.Header().Set("Content-Type", "audio/flac")
				w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-%d/%d", len(payload)-1, len(payload)))
				w.Header().Set("Accept-Ranges", "bytes")
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write(payload)
				return
			}
			w.WriteHeader(http.StatusBadRequest)
		case "/reject-416":
			if rng == fmt.Sprintf("bytes=0-%d", streamRangeChunkBytes-1) {
				w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
				return
			}
			if rng == "bytes=0-" {
				w.Header().Set("Content-Type", "audio/flac")
				w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-%d/%d", len(payload)-1, len(payload)))
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write(payload)
				return
			}
			w.WriteHeader(http.StatusBadRequest)
		case "/reject-400":
			if rng == fmt.Sprintf("bytes=0-%d", streamRangeChunkBytes-1) {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			if rng == "bytes=0-" {
				w.Header().Set("Content-Type", "audio/flac")
				w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-%d/%d", len(payload)-1, len(payload)))
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write(payload)
				return
			}
			w.WriteHeader(http.StatusInternalServerError)
		case "/token-expired":
			w.WriteHeader(http.StatusUnauthorized)
		case "/token-fresh":
			w.Header().Set("Content-Type", "audio/flac")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(payload)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer upstream.Close()

	srv, st, _ := newTestServer(t, nil)
	reg, _ := provider.NewRegistry([]provider.Entry{
		{
			Provider: &stubProvider{
				id:         "AudiusMusicClient",
				engineName: "AudiusMusicClient",
				resolveFn: func(_ context.Context, _ model.ProviderTrack, _ model.QualityPolicy) ([]model.ResolvedMedia, error) {
					n := resolveCalls.Add(1)
					path := "/cap-ok"
					switch mode.Load() {
					case 1:
						path = "/reject-416"
					case 2:
						path = "/reject-400"
					case 3:
						if n%2 == 1 {
							path = "/token-expired"
						} else {
							path = "/token-fresh"
						}
					}
					return []model.ResolvedMedia{{VariantID: "v1", URL: upstream.URL + path}}, nil
				},
			},
			Meta: provider.Metadata{Label: "Audius", Category: "开放许可"},
		},
	})
	srv.deps.Providers = reg

	ctx := context.Background()
	for idx, pubID := range []string{"stream-cap-0", "stream-cap-1", "stream-cap-2", "stream-cap-3"} {
		trID, err := st.UpsertProviderTrack(ctx, store.ProviderTrackRecord{
			Source:     "AudiusMusicClient",
			Locator:    fmt.Sprintf("loc-%d", idx),
			RootSource: "AudiusMusicClient",
			Title:      fmt.Sprintf("Track %d", idx),
			Artist:     "Artist A",
		}, []model.MediaVariant{{VariantID: "v1", Label: "FLAC", Codec: "flac", Availability: "resolved"}})
		if err != nil {
			t.Fatal(err)
		}
		if err := st.PutPublicRef(ctx, model.PublicRef{
			PublicID: pubID, Kind: "candidate", TargetID: trID, Source: "AudiusMusicClient", SourceID: fmt.Sprintf("loc-%d", idx),
		}, ""); err != nil {
			t.Fatal(err)
		}
	}

	// 1. Open-ended Range is capped to 4 MiB chunk when contacting upstream.
	seenRanges, seenIfRanges = nil, nil
	req1 := httptest.NewRequest(http.MethodGet, "/api/stream/stream-cap-0", nil)
	req1.Header.Set("Range", "bytes=0-")
	req1.Header.Set("If-Range", `"etag-v1"`)
	rec1 := httptest.NewRecorder()
	srv.ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusPartialContent || !bytes.Equal(rec1.Body.Bytes(), payload) {
		t.Fatalf("capped stream failed: code=%d body=%q", rec1.Code, rec1.Body.String())
	}
	wantCapped := fmt.Sprintf("/cap-ok:bytes=0-%d", streamRangeChunkBytes-1)
	if len(seenRanges) != 1 || seenRanges[0] != wantCapped || seenIfRanges[0] != `"etag-v1"` {
		t.Fatalf("unexpected upstream headers: ranges=%v ifRanges=%v", seenRanges, seenIfRanges)
	}

	// 2. Upstream returns 416 on capped Range -> immediate fallback on same target.URL with original Range & If-Range (no refresh).
	mode.Store(1)
	resolveCalls.Store(0)
	seenRanges, seenIfRanges = nil, nil
	req2 := httptest.NewRequest(http.MethodGet, "/api/stream/stream-cap-1", nil)
	req2.Header.Set("Range", "bytes=0-")
	req2.Header.Set("If-Range", `"etag-v2"`)
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusPartialContent || !bytes.Equal(rec2.Body.Bytes(), payload) {
		t.Fatalf("416 fallback failed: code=%d body=%q", rec2.Code, rec2.Body.String())
	}
	if resolveCalls.Load() != 1 || len(seenRanges) != 2 || seenRanges[1] != "/reject-416:bytes=0-" || seenIfRanges[1] != `"etag-v2"` {
		t.Fatalf("expected single resolve + fallback to original Range on same URL, resolve=%d ranges=%v ifRanges=%v", resolveCalls.Load(), seenRanges, seenIfRanges)
	}

	// 3. Upstream returns 400 on capped Range -> immediate fallback on same target.URL with original Range.
	mode.Store(2)
	resolveCalls.Store(0)
	seenRanges, seenIfRanges = nil, nil
	req3 := httptest.NewRequest(http.MethodGet, "/api/stream/stream-cap-2", nil)
	req3.Header.Set("Range", "bytes=0-")
	req3.Header.Set("If-Range", `"etag-v3"`)
	rec3 := httptest.NewRecorder()
	srv.ServeHTTP(rec3, req3)
	if rec3.Code != http.StatusPartialContent || !bytes.Equal(rec3.Body.Bytes(), payload) {
		t.Fatalf("400 fallback failed: code=%d body=%q", rec3.Code, rec3.Body.String())
	}
	if resolveCalls.Load() != 1 || len(seenRanges) != 2 || seenRanges[1] != "/reject-400:bytes=0-" || seenIfRanges[1] != `"etag-v3"` {
		t.Fatalf("expected single resolve + 400 fallback, resolve=%d ranges=%v ifRanges=%v", resolveCalls.Load(), seenRanges, seenIfRanges)
	}

	// 4. Upstream returns 401 on attempt 0 -> refreshes resolved URL and succeeds on attempt 1.
	mode.Store(3)
	resolveCalls.Store(0)
	seenRanges = nil
	req4 := httptest.NewRequest(http.MethodGet, "/api/stream/stream-cap-3", nil)
	rec4 := httptest.NewRecorder()
	srv.ServeHTTP(rec4, req4)
	if rec4.Code != http.StatusOK || !bytes.Equal(rec4.Body.Bytes(), payload) {
		t.Fatalf("401 refresh retry failed: code=%d body=%q", rec4.Code, rec4.Body.String())
	}
	if resolveCalls.Load() != 2 || len(seenRanges) != 2 || seenRanges[0] != "/token-expired:" || seenRanges[1] != "/token-fresh:" {
		t.Fatalf("expected 401 refresh sequence, resolve=%d ranges=%v", resolveCalls.Load(), seenRanges)
	}
}
