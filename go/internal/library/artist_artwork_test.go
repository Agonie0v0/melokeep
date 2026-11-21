package library_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"testing/fstest"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/httpapi"
	"github.com/agonie0v0/melokeep/internal/library"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type testMemTagger struct {
	mu   sync.Mutex
	tags map[string]helper.AudioTagData
}

func newTestMemTagger() *testMemTagger {
	return &testMemTagger{tags: make(map[string]helper.AudioTagData)}
}

func (m *testMemTagger) set(path string, tags map[string]string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	copied := make(map[string]string, len(tags))
	for k, v := range tags {
		copied[k] = v
	}
	m.tags[path] = helper.AudioTagData{
		Path:     path,
		Tags:     copied,
		Editable: true,
		Duration: 215,
	}
}

func (m *testMemTagger) ReadTags(_ context.Context, path string) (helper.AudioTagData, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if d, ok := m.tags[path]; ok {
		return d, nil
	}
	return helper.AudioTagData{Path: path, Tags: map[string]string{}, Editable: true, Duration: 215}, nil
}

func (m *testMemTagger) BatchReadTags(ctx context.Context, paths []string) ([]helper.BatchTagItem, error) {
	out := make([]helper.BatchTagItem, len(paths))
	for i, p := range paths {
		d, _ := m.ReadTags(ctx, p)
		out[i] = helper.BatchTagItem{Path: p, OK: true, Data: &d}
	}
	return out, nil
}

func (m *testMemTagger) WriteTags(_ context.Context, req helper.WriteTagsRequest) (helper.AudioTagData, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	copied := make(map[string]string, len(req.Tags))
	for k, v := range req.Tags {
		copied[k] = v
	}
	d := helper.AudioTagData{
		Path:     req.StagePath,
		Tags:     copied,
		Editable: true,
		Duration: 215,
	}
	if req.Lyrics != nil {
		d.Lyrics = *req.Lyrics
	}
	m.tags[req.StagePath] = d
	return d, nil
}

func makeFakeJPEG(size int, tag string) []byte {
	if size < 16 {
		size = 16
	}
	buf := bytes.Repeat([]byte("J"), size)
	buf[0], buf[1], buf[2] = 0xff, 0xd8, 0xff
	buf[3] = 0xe0
	copy(buf[6:], []byte(tag))
	return buf
}

func makeFakePNG(size int, tag string) []byte {
	if size < 16 {
		size = 16
	}
	buf := bytes.Repeat([]byte("P"), size)
	copy(buf[:8], []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
	copy(buf[8:], []byte(tag))
	return buf
}

func makeFakeWebP(size int, tag string) []byte {
	if size < 16 {
		size = 16
	}
	buf := bytes.Repeat([]byte("W"), size)
	copy(buf[:4], []byte("RIFF"))
	copy(buf[8:12], []byte("WEBP"))
	copy(buf[12:], []byte(tag))
	return buf
}

func setupArtistTestLibrary(t *testing.T) (*store.Store, *files.Coordinator, *library.Service, *testMemTagger, files.Root, string, *transport.Pool) {
	t.Helper()
	ctx := context.Background()
	tmp := t.TempDir()
	rootDir := filepath.Join(tmp, "music")
	dataDir := filepath.Join(tmp, "data")
	if err := os.MkdirAll(rootDir, 0o755); err != nil {
		t.Fatalf("mkdir root: %v", err)
	}
	st, err := store.Open(ctx, store.Options{Path: filepath.Join(tmp, "melokeep.db")})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	root, rec, err := files.RegisterRoot(rootDir, "root-1")
	if err != nil {
		t.Fatalf("register root: %v", err)
	}
	if err := st.UpsertRoot(ctx, rec); err != nil {
		t.Fatalf("upsert root: %v", err)
	}
	locks := files.NewAssetLockManager(2 * time.Second)
	coord := files.NewCoordinator(st, locks, []files.Root{root})
	tagger := newTestMemTagger()
	tagSvc := media.NewTagService(tagger, nil)
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	t.Cleanup(func() { pool.CloseAll() })
	scraper := library.NewScraper(pool, nil, nil, library.ScraperOptions{})
	rt := config.DefaultRuntimeConfig()
	rt.DataDir = dataDir
	snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())
	svc := library.NewService(st, coord, tagSvc, nil, scraper, library.ServiceOptions{
		SnapshotProvider: func() config.Snapshot { return snap },
	})
	t.Cleanup(func() { _ = svc.Close() })
	return st, coord, svc, tagger, root, dataDir, pool
}

func TestArtistArtworkMultiSourceAndPlaceholderFiltering(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	dataDir := filepath.Join(tmp, "app-data")

	neteaseJPEG := makeFakeJPEG(2200, "netease-artist")
	deezerPNG := makeFakePNG(2400, "deezer-artist")
	tinyBytes := makeFakeJPEG(400, "too-small")

	var neteaseSearchCalls atomic.Int64
	var deezerSearchCalls atomic.Int64
	var neteaseImageCalls atomic.Int64
	var deezerImageCalls atomic.Int64
	var sawReferer atomic.Bool
	var sawParam800 atomic.Bool
	var sawUA atomic.Bool

	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/search/get/web":
			neteaseSearchCalls.Add(1)
			if r.Header.Get("Referer") == "https://music.163.com/" {
				sawReferer.Store(true)
			}
			if strings.Contains(r.Header.Get("User-Agent"), "Mozilla/") {
				sawUA.Store(true)
			}
			q := r.URL.Query().Get("s")
			switch q {
			case "周杰伦":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"code": 200,
					"result": map[string]any{
						"artists": []map[string]any{
							{
								"id":        6452,
								"name":      "周杰伦",
								"picUrl":    srv.URL + "/img/netease/5639395138885805.jpg",
								"img1v1Url": srv.URL + "/img/netease/jay.jpg",
							},
						},
					},
				})
			case "Daft Punk":
				// Returns both Netease placeholder IDs so fetcher must fall back to Deezer.
				_ = json.NewEncoder(w).Encode(map[string]any{
					"code": 200,
					"result": map[string]any{
						"artists": []map[string]any{
							{
								"id":        1,
								"name":      "Daft Punk",
								"picUrl":    srv.URL + "/img/netease/109951165647004069.jpg",
								"img1v1Url": srv.URL + "/img/netease/5639395138885805.jpg",
							},
						},
					},
				})
			case "Tiny Image":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"code": 200,
					"result": map[string]any{
						"artists": []map[string]any{
							{"id": 2, "name": "Tiny Image", "picUrl": srv.URL + "/img/tiny.jpg"},
						},
					},
				})
			case "Fake Image":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"code": 200,
					"result": map[string]any{
						"artists": []map[string]any{
							{"id": 3, "name": "Fake Image", "picUrl": srv.URL + "/img/fake.jpg"},
						},
					},
				})
			default:
				_ = json.NewEncoder(w).Encode(map[string]any{"code": 200, "result": map[string]any{"artists": []any{}}})
			}

		case "/search/artist":
			deezerSearchCalls.Add(1)
			q := r.URL.Query().Get("q")
			switch q {
			case "Daft Punk":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"data": []map[string]any{
						{
							"id":             27,
							"name":           "Daft Punk",
							"picture_xl":     "https://cdn.example.com/images/artist//1000x1000.jpg", // empty hash placeholder
							"picture_big":    srv.URL + "/img/deezer/daft-big.png",
							"picture_medium": srv.URL + "/img/deezer/daft-medium.png",
						},
					},
				})
			case "Fake Image":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"data": []map[string]any{
						{
							"id":         99,
							"name":       "Fake Image",
							"picture_xl": srv.URL + "/img/html-error",
						},
					},
				})
			default:
				_ = json.NewEncoder(w).Encode(map[string]any{"data": []any{}})
			}

		case "/img/netease/5639395138885805.jpg", "/img/netease/109951165647004069.jpg":
			t.Fatalf("placeholder URL should have been filtered out before downloading: %s", r.URL.String())

		case "/img/netease/jay.jpg":
			neteaseImageCalls.Add(1)
			if r.URL.Query().Get("param") == "800y800" {
				sawParam800.Store(true)
			}
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(neteaseJPEG)

		case "/img/deezer/daft-big.png":
			deezerImageCalls.Add(1)
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(deezerPNG)

		case "/img/tiny.jpg":
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(tinyBytes)

		case "/img/fake.jpg":
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(bytes.Repeat([]byte("NOT_AN_IMAGE"), 200))

		case "/img/html-error":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write([]byte("<html>not an image</html>"))

		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()

	rt := config.DefaultRuntimeConfig()
	rt.DataDir = dataDir
	snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())

	fetcher := library.NewArtistArtworkFetcher(pool, nil, nil, library.ArtistArtworkOptions{
		NeteaseBaseURL: srv.URL,
		DeezerBaseURL:  srv.URL,
		NegativeTTL:    time.Minute,
	})

	// 1. Collaboration string normalization + Netease priority + ?param=800y800 + concurrent singleflight + disk cache.
	if got := library.PrimaryArtistName("周杰伦 / 袁咏琳"); got != "周杰伦" {
		t.Fatalf("PrimaryArtistName slash mismatch: %q", got)
	}
	if got := library.PrimaryArtistName("陈奕迅 & 王菲"); got != "陈奕迅" {
		t.Fatalf("PrimaryArtistName ampersand mismatch: %q", got)
	}
	if got := library.PrimaryArtistName("林俊杰、阿杜"); got != "林俊杰" {
		t.Fatalf("PrimaryArtistName dunhao mismatch: %q", got)
	}
	if !library.IsIgnoredArtist("Various Artists") || !library.IsIgnoredArtist("群星") || !library.IsIgnoredArtist("未知歌手") {
		t.Fatalf("expected ignored placeholder artists to return true")
	}

	var wg sync.WaitGroup
	for i := 0; i < 6; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res := fetcher.LookupArtistArtwork(ctx, snap, "周杰伦 / 袁咏琳")
			if !res.Found || !bytes.Equal(res.Data, neteaseJPEG) {
				t.Errorf("concurrent lookup failed: found=%v err=%v len=%d", res.Found, res.Err, len(res.Data))
			}
		}()
	}
	wg.Wait()

	if neteaseSearchCalls.Load() != 1 || neteaseImageCalls.Load() != 1 {
		t.Fatalf("expected singleflight dedup (search=1, img=1), got search=%d img=%d", neteaseSearchCalls.Load(), neteaseImageCalls.Load())
	}
	if !sawReferer.Load() || !sawParam800.Load() || !sawUA.Load() {
		t.Fatalf("expected Netease headers & param=800y800: referer=%v param=%v ua=%v", sawReferer.Load(), sawParam800.Load(), sawUA.Load())
	}
	if deezerSearchCalls.Load() != 0 {
		t.Fatalf("Deezer should not be queried when Netease succeeded, got %d", deezerSearchCalls.Load())
	}

	sum := sha256.Sum256([]byte("周杰伦"))
	expectedDiskCache := filepath.Join(dataDir, "artist-avatars", hex.EncodeToString(sum[:])+".jpg")
	if raw, err := os.ReadFile(expectedDiskCache); err != nil || !bytes.Equal(raw, neteaseJPEG) {
		t.Fatalf("expected disk cache at %s matching neteaseJPEG, err=%v len=%d", expectedDiskCache, err, len(raw))
	}

	// 2. Netease returns placeholders -> Deezer fallback filters empty hash /images/artist// and picks picture_big.
	deezerRes := fetcher.LookupArtistArtwork(ctx, snap, "Daft Punk")
	if !deezerRes.Found || deezerRes.Source != "deezer" || deezerRes.MIME != "image/png" || !bytes.Equal(deezerRes.Data, deezerPNG) {
		t.Fatalf("expected Deezer fallback PNG, got found=%v src=%s mime=%s err=%v", deezerRes.Found, deezerRes.Source, deezerRes.MIME, deezerRes.Err)
	}
	if deezerImageCalls.Load() != 1 {
		t.Fatalf("expected 1 Deezer image download, got %d", deezerImageCalls.Load())
	}

	// 3. Reject tiny image (<1500 bytes) & fake non-image magic bytes / non-image Content-Type.
	if res := fetcher.LookupArtistArtwork(ctx, snap, "Tiny Image"); res.Found {
		t.Fatalf("expected <1500 byte image rejected")
	}
	if res := fetcher.LookupArtistArtwork(ctx, snap, "Fake Image"); res.Found {
		t.Fatalf("expected bogus magic/Content-Type image rejected")
	}

	// 4. Short-lived negative cache avoids repeating HTTP searches for unknown artist.
	beforeCalls := neteaseSearchCalls.Load()
	miss1 := fetcher.LookupArtistArtwork(ctx, snap, "查无此人测试歌手")
	miss2 := fetcher.LookupArtistArtwork(ctx, snap, "查无此人测试歌手")
	if miss1.Found || miss2.Found {
		t.Fatalf("expected misses for unknown artist")
	}
	if afterCalls := neteaseSearchCalls.Load(); afterCalls-beforeCalls != 1 {
		t.Fatalf("expected negative cache to limit search to 1 call, got %d", afterCalls-beforeCalls)
	}
}

func TestResolveArtistDirSecurityBoundariesAndEnsureArtistArtwork(t *testing.T) {
	ctx := context.Background()
	tmp := t.TempDir()
	libRoot := filepath.Join(tmp, "library")
	dataDir := filepath.Join(tmp, "data")
	outside := filepath.Join(tmp, "outside")
	if err := os.MkdirAll(filepath.Join(libRoot, "周杰伦", "叶惠美"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(libRoot, "Eason", "a", "b"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(libRoot, "AudiusMusicClient"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(outside, 0o755); err != nil {
		t.Fatal(err)
	}

	// 3-level <libRoot>/<Artist>/<Album>/<Track>
	if got := library.ResolveArtistDir(libRoot, filepath.Join(libRoot, "周杰伦", "叶惠美", "晴天.flac"), "周杰伦 / 袁咏琳"); got != filepath.Join(libRoot, "周杰伦") {
		t.Fatalf("3-level ResolveArtistDir got %q", got)
	}
	// 2-level <libRoot>/<Artist>/<Track>
	if got := library.ResolveArtistDir(libRoot, filepath.Join(libRoot, "周杰伦", "晴天.flac"), "周杰伦"); got != filepath.Join(libRoot, "周杰伦") {
		t.Fatalf("2-level ResolveArtistDir got %q", got)
	}
	// Strictly never write artist.jpg to libRoot itself
	if got := library.ResolveArtistDir(libRoot, filepath.Join(libRoot, "晴天.flac"), "周杰伦"); got != "" {
		t.Fatalf("expected empty when audio sits directly in libRoot, got %q", got)
	}
	// Reject 4-level path
	if got := library.ResolveArtistDir(libRoot, filepath.Join(libRoot, "Eason", "a", "b", "song.flac"), "Eason"); got != "" {
		t.Fatalf("expected empty on 4-level path, got %q", got)
	}
	// Reject path outside libRoot or traversal
	if got := library.ResolveArtistDir(libRoot, filepath.Join(outside, "周杰伦", "song.flac"), "周杰伦"); got != "" {
		t.Fatalf("expected empty outside libRoot, got %q", got)
	}
	// Reject mismatched folder ("source" download layout like <root>/AudiusMusicClient/Song.mp3)
	if got := library.ResolveArtistDir(libRoot, filepath.Join(libRoot, "AudiusMusicClient", "Song.mp3"), "Open Artist"); got != "" {
		t.Fatalf("expected empty when folder does not match artist, got %q", got)
	}
	// Reject ignored artist
	if got := library.ResolveArtistDir(libRoot, filepath.Join(libRoot, "Various Artists", "Hit.mp3"), "Various Artists"); got != "" {
		t.Fatalf("expected empty for Various Artists, got %q", got)
	}

	// Existing artist.webp / artist.png must skip cleanly without overwriting or fetching.
	existingArtistDir := filepath.Join(libRoot, "Taylor Swift")
	existingAudio := filepath.Join(existingArtistDir, "1989", "Style.flac")
	if err := os.MkdirAll(filepath.Dir(existingAudio), 0o755); err != nil {
		t.Fatal(err)
	}
	existingWebp := makeFakeWebP(1800, "existing-webp")
	existingArtPath := filepath.Join(existingArtistDir, "artist.webp")
	if err := os.WriteFile(existingArtPath, existingWebp, 0o644); err != nil {
		t.Fatal(err)
	}

	var srvHits atomic.Int64
	avatarBytes := makeFakeJPEG(2100, "fresh-jay")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		srvHits.Add(1)
		switch r.URL.Path {
		case "/api/search/get/web":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"code": 200,
				"result": map[string]any{
					"artists": []map[string]any{
						{"id": 6452, "name": "周杰伦", "picUrl": "http://" + r.Host + "/jay.jpg"},
					},
				},
			})
		case "/jay.jpg":
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(avatarBytes)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()
	rt := config.DefaultRuntimeConfig()
	rt.DataDir = dataDir
	snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())
	fetcher := library.NewArtistArtworkFetcher(pool, nil, nil, library.ArtistArtworkOptions{
		NeteaseBaseURL: srv.URL,
		DeezerBaseURL:  srv.URL,
	})

	gotExisting, status, err := fetcher.EnsureArtistArtwork(ctx, snap, libRoot, existingAudio, "Taylor Swift")
	if err != nil || status != "exists" || gotExisting != existingArtPath {
		t.Fatalf("expected existing artist.webp skipped with status=exists, got path=%q status=%q err=%v", gotExisting, status, err)
	}
	if srvHits.Load() != 0 {
		t.Fatalf("expected zero HTTP calls when artist.webp already exists, got %d", srvHits.Load())
	}

	// Missing artist.jpg -> atomic write, then second call returns "exists".
	jayAudio := filepath.Join(libRoot, "周杰伦", "叶惠美", "晴天.flac")
	writtenPath, status, err := fetcher.EnsureArtistArtwork(ctx, snap, libRoot, jayAudio, "周杰伦")
	if err != nil || status != "written" || writtenPath != filepath.Join(libRoot, "周杰伦", "artist.jpg") {
		t.Fatalf("EnsureArtistArtwork failed: path=%q status=%q err=%v", writtenPath, status, err)
	}
	if raw, err := os.ReadFile(writtenPath); err != nil || !bytes.Equal(raw, avatarBytes) {
		t.Fatalf("written artist.jpg mismatch: err=%v len=%d", err, len(raw))
	}
	_, status2, err2 := fetcher.EnsureArtistArtwork(ctx, snap, libRoot, jayAudio, "周杰伦")
	if err2 != nil || status2 != "exists" {
		t.Fatalf("second EnsureArtistArtwork should report exists, got status=%q err=%v", status2, err2)
	}
	entries, _ := os.ReadDir(filepath.Join(libRoot, "周杰伦"))
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".tmp") {
			t.Fatalf("unexpected leftover temp file: %s", e.Name())
		}
	}
}

func TestArtistArtworkLibraryJobPreviewCommitAndOrganizeBackfill(t *testing.T) {
	ctx := context.Background()
	admin := auth.WebAdminActor("admin")
	_, _, svc, tagger, root, dataDir, _ := setupArtistTestLibrary(t)

	jayJPEG := makeFakeJPEG(2300, "jay-preview")
	jjPNG := makeFakePNG(2300, "jj-backfill")
	var jayImgDownloads atomic.Int64

	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/search/get/web":
			q := r.URL.Query().Get("s")
			switch q {
			case "周杰伦":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"code": 200,
					"result": map[string]any{
						"artists": []map[string]any{
							{"id": 6452, "name": "周杰伦", "picUrl": srv.URL + "/jay.jpg"},
						},
					},
				})
			case "林俊杰":
				_ = json.NewEncoder(w).Encode(map[string]any{
					"code": 200,
					"result": map[string]any{
						"artists": []map[string]any{
							{"id": 3684, "name": "林俊杰", "picUrl": srv.URL + "/jj.png"},
						},
					},
				})
			default:
				_ = json.NewEncoder(w).Encode(map[string]any{"code": 200, "result": map[string]any{"artists": []any{}}})
			}
		case "/search/artist":
			_ = json.NewEncoder(w).Encode(map[string]any{"data": []any{}})
		case "/jay.jpg":
			jayImgDownloads.Add(1)
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(jayJPEG)
		case "/jj.png":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(jjPNG)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	svc.SetArtistArtworkOptions(library.ArtistArtworkOptions{
		NeteaseBaseURL: srv.URL,
		DeezerBaseURL:  srv.URL,
	})

	libRoot := filepath.Join(root.CanonicalPath, "library")
	jayTrack1 := filepath.Join(libRoot, "周杰伦", "叶惠美", "01 - 晴天.flac")
	jayTrack2 := filepath.Join(libRoot, "周杰伦", "叶惠美", "02 - 以父之名.flac")
	easonTrack := filepath.Join(libRoot, "陈奕迅", "U87", "01 - 浮夸.flac")
	easonExistingArt := filepath.Join(libRoot, "陈奕迅", "artist.png")
	looseTrack := filepath.Join(root.CanonicalPath, "incoming", "无歌手目录.flac")

	for _, p := range []string{jayTrack1, jayTrack2, easonTrack, looseTrack} {
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte("fLaC-audio"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	easonExistingPNG := makeFakePNG(1900, "eason-existing")
	if err := os.WriteFile(easonExistingArt, easonExistingPNG, 0o644); err != nil {
		t.Fatal(err)
	}

	tagger.set(jayTrack1, map[string]string{"title": "晴天", "artist": "周杰伦", "album_artist": "周杰伦", "album": "叶惠美"})
	tagger.set(jayTrack2, map[string]string{"title": "以父之名", "artist": "周杰伦 / 洪敬尧", "album_artist": "周杰伦", "album": "叶惠美"})
	tagger.set(easonTrack, map[string]string{"title": "浮夸", "artist": "陈奕迅", "album": "U87"})
	tagger.set(looseTrack, map[string]string{"title": "未知", "artist": "周杰伦"})

	// 1. Preview + commit for artist_artwork across two tracks sharing same artist.jpg + one existing artist.png.
	job, err := svc.Submit(ctx, admin, library.SubmitRequest{
		Action: "artist_artwork",
		Paths: []string{
			"library/周杰伦/叶惠美/01 - 晴天.flac",
			"library/周杰伦/叶惠美/02 - 以父之名.flac",
			"library/陈奕迅/U87/01 - 浮夸.flac",
		},
	})
	if err != nil {
		t.Fatalf("Submit artist_artwork failed: %v", err)
	}
	if job.Status != string(model.LibraryJobPreviewReady) || len(job.Items) != 3 {
		t.Fatalf("unexpected preview job status/items: status=%s items=%d detail=%s", job.Status, len(job.Items), job.Detail)
	}
	expectedJayTarget := filepath.Join(libRoot, "周杰伦", "artist.jpg")
	if job.Items[0].Status != string(model.LibraryItemPreviewReady) || job.Items[0].Target != expectedJayTarget || job.Items[0].Artwork == nil {
		t.Fatalf("unexpected item[0] preview: %+v", job.Items[0])
	}
	if job.Items[1].Status != string(model.LibraryItemPreviewReady) || job.Items[1].Target != expectedJayTarget {
		t.Fatalf("unexpected item[1] preview: %+v", job.Items[1])
	}
	if job.Items[2].Status != string(model.LibraryItemSkipped) || len(job.Items[2].Warnings) == 0 {
		t.Fatalf("expected item[2] skipped due to existing artist.png, got %+v", job.Items[2])
	}
	if jayImgDownloads.Load() != 1 {
		t.Fatalf("expected only 1 image download across duplicate artist tracks, got %d", jayImgDownloads.Load())
	}

	applied, err := svc.Apply(ctx, admin, job.ID)
	if err != nil {
		t.Fatalf("Apply artist_artwork failed: %v", err)
	}
	if applied.Status != string(model.LibraryJobComplete) {
		t.Fatalf("expected complete job, got status=%s detail=%s", applied.Status, applied.Detail)
	}
	if raw, err := os.ReadFile(expectedJayTarget); err != nil || !bytes.Equal(raw, jayJPEG) {
		t.Fatalf("expected written %s matching jayJPEG, err=%v len=%d", expectedJayTarget, err, len(raw))
	}
	if rawEason, err := os.ReadFile(easonExistingArt); err != nil || !bytes.Equal(rawEason, easonExistingPNG) {
		t.Fatalf("existing artist.png should remain untouched: err=%v", err)
	}
	if jayImgDownloads.Load() != 1 {
		t.Fatalf("commit should reuse cache and not re-download jay.jpg, downloads=%d", jayImgDownloads.Load())
	}
	jaySum := sha256.Sum256([]byte("周杰伦"))
	diskCacheFile := filepath.Join(dataDir, "artist-avatars", hex.EncodeToString(jaySum[:])+".jpg")
	if rawCache, err := os.ReadFile(diskCacheFile); err != nil || !bytes.Equal(rawCache, jayJPEG) {
		t.Fatalf("expected disk cache populated at %s, err=%v", diskCacheFile, err)
	}

	// 2. Track outside artist folder structure enters needs_review with clear warning.
	reviewJob, err := svc.Submit(ctx, admin, library.SubmitRequest{
		Action: "artist_artwork",
		Paths:  []string{"incoming/无歌手目录.flac"},
	})
	if err != nil {
		t.Fatalf("Submit looseTrack artist_artwork failed: %v", err)
	}
	if reviewJob.Status != string(model.LibraryJobNeedsReview) || len(reviewJob.Items[0].Warnings) == 0 {
		t.Fatalf("expected needs_review with warning for non-artist folder, got %+v", reviewJob)
	}

	// 3. Organize commit triggers best-effort post-commit artist.jpg backfill cleanly without blocking or failing organize.
	orgSource := filepath.Join(root.CanonicalPath, "incoming", "jj.flac")
	if err := os.WriteFile(orgSource, []byte("fLaC-jj"), 0o644); err != nil {
		t.Fatal(err)
	}
	tagger.set(orgSource, map[string]string{
		"title":        "江南",
		"artist":       "林俊杰",
		"album_artist": "林俊杰",
		"album":        "曹操",
		"track_number": "01",
	})
	orgJob, err := svc.Submit(ctx, admin, library.SubmitRequest{
		Action: "organize",
		Paths:  []string{"incoming/jj.flac"},
	})
	if err != nil {
		t.Fatalf("Submit organize failed: %v", err)
	}
	orgDone, err := svc.Apply(ctx, admin, orgJob.ID)
	if err != nil || orgDone.Status != string(model.LibraryJobComplete) {
		t.Fatalf("Apply organize failed: %+v err=%v", orgDone, err)
	}

	expectedJJArt := filepath.Join(libRoot, "林俊杰", "artist.jpg")
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		if raw, rErr := os.ReadFile(expectedJJArt); rErr == nil && bytes.Equal(raw, jjPNG) {
			break
		}
		time.Sleep(30 * time.Millisecond)
	}
	if raw, rErr := os.ReadFile(expectedJJArt); rErr != nil || !bytes.Equal(raw, jjPNG) {
		t.Fatalf("expected organize backfill to write %s matching jjPNG, err=%v", expectedJJArt, rErr)
	}
}

func TestHTTPArtistAvatarEndpointAndCachePriority(t *testing.T) {
	storeInstance, coord, svc, _, root, dataDir, pool := setupArtistTestLibrary(t)

	localPNG := makeFakePNG(2000, "taylor-local")
	diskJPEG := makeFakeJPEG(2000, "daft-disk-cache")
	remoteJPEG := makeFakeJPEG(2200, "cheer-remote")

	var remoteCalls atomic.Int64
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		remoteCalls.Add(1)
		switch r.URL.Path {
		case "/api/search/get/web":
			if r.URL.Query().Get("s") == "陈绮贞" {
				_ = json.NewEncoder(w).Encode(map[string]any{
					"code": 200,
					"result": map[string]any{
						"artists": []map[string]any{
							{"id": 7217, "name": "陈绮贞", "picUrl": srv.URL + "/cheer.jpg"},
						},
					},
				})
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"code": 200, "result": map[string]any{"artists": []any{}}})
		case "/search/artist":
			_ = json.NewEncoder(w).Encode(map[string]any{"data": []any{}})
		case "/cheer.jpg":
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(remoteJPEG)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	svc.SetArtistArtworkOptions(library.ArtistArtworkOptions{
		NeteaseBaseURL: srv.URL,
		DeezerBaseURL:  srv.URL,
	})

	// Seed Priority 1: local library folder `<root>/library/taylor swift/artist.png` (lowercase folder vs Taylor Swift query).
	taylorDir := filepath.Join(root.CanonicalPath, "library", "taylor swift")
	if err := os.MkdirAll(taylorDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(taylorDir, "artist.png"), localPNG, 0o644); err != nil {
		t.Fatal(err)
	}

	// Seed Priority 2: disk cache `<dataDir>/artist-avatars/<sha256("daft punk")>.jpg`.
	daftSum := sha256.Sum256([]byte("daft punk"))
	daftCachePath := filepath.Join(dataDir, "artist-avatars", hex.EncodeToString(daftSum[:])+".jpg")
	if err := os.MkdirAll(filepath.Dir(daftCachePath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(daftCachePath, diskJPEG, 0o644); err != nil {
		t.Fatal(err)
	}

	rt := config.DefaultRuntimeConfig()
	rt.DataDir = dataDir
	snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())

	httpSrv, err := httpapi.NewServer(httpapi.Dependencies{
		Store:           storeInstance,
		Transport:       pool,
		Files:           coord,
		Library:         svc,
		Sessions:        auth.NewSessionManager(),
		RateLimiter:     auth.NewLoginRateLimiter(),
		Budgets:         config.DefaultResourceBudgets(),
		InitialSnapshot: snap,
		AuthEnabled:     true,
		AuthUserDigest:  sha256.Sum256([]byte("admin")),
		AuthPassDigest:  sha256.Sum256([]byte("secret-pass")),
		FrontendFS: fstest.MapFS{
			"index.html": &fstest.MapFile{Data: []byte("<html>ok</html>")},
		},
		IsWindows: true,
		Platform:  "win32",
		WorkDir:   root.CanonicalPath,
	})
	if err != nil {
		t.Fatalf("httpapi.NewServer: %v", err)
	}

	// 1. Unauthenticated request must return 401.
	unauthRec := httptest.NewRecorder()
	httpSrv.ServeHTTP(unauthRec, httptest.NewRequest(http.MethodGet, "/api/artists/avatar?name=Taylor+Swift", nil))
	if unauthRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 when unauthenticated, got %d", unauthRec.Code)
	}

	// Obtain app tokens (Bearer + media_token) + web cookie.
	appLoginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass","client_type":"app"}`))
	appLoginReq.Header.Set("Content-Type", "application/json")
	appLoginReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	appLoginRec := httptest.NewRecorder()
	httpSrv.ServeHTTP(appLoginRec, appLoginReq)
	if appLoginRec.Code != http.StatusOK {
		t.Fatalf("app login failed: %d %s", appLoginRec.Code, appLoginRec.Body.String())
	}
	var loginPayload struct {
		Token      string `json:"token"`
		MediaToken string `json:"media_token"`
	}
	if err := json.Unmarshal(appLoginRec.Body.Bytes(), &loginPayload); err != nil {
		t.Fatalf("decode login response: %v", err)
	}

	// 2. Case-insensitive local library hit (using ?media_token= for <img> / mobile stream parity) -> 0 remote calls.
	rec1 := httptest.NewRecorder()
	req1 := httptest.NewRequest(http.MethodGet, "/api/artists/avatar?name=Taylor+Swift&media_token="+url.QueryEscape(loginPayload.MediaToken), nil)
	httpSrv.ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusOK || !bytes.Equal(rec1.Body.Bytes(), localPNG) {
		t.Fatalf("local avatar lookup failed: code=%d len=%d", rec1.Code, rec1.Body.Len())
	}
	if rec1.Header().Get("Content-Type") != "image/png" || rec1.Header().Get("Cache-Control") != "public, max-age=86400" {
		t.Fatalf("unexpected headers: %v", rec1.Header())
	}
	if remoteCalls.Load() != 0 {
		t.Fatalf("expected 0 remote calls on local library hit, got %d", remoteCalls.Load())
	}

	// 3. Disk cache hit via Authorization: Bearer -> 0 remote calls.
	rec2 := httptest.NewRecorder()
	req2 := httptest.NewRequest(http.MethodGet, "/api/artists/avatar?name=Daft+Punk", nil)
	req2.Header.Set("Authorization", "Bearer "+loginPayload.Token)
	httpSrv.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusOK || !bytes.Equal(rec2.Body.Bytes(), diskJPEG) {
		t.Fatalf("disk cache avatar lookup failed: code=%d len=%d", rec2.Code, rec2.Body.Len())
	}
	if rec2.Header().Get("Content-Type") != "image/jpeg" || rec2.Header().Get("Cache-Control") != "public, max-age=86400" {
		t.Fatalf("unexpected headers: %v", rec2.Header())
	}
	if remoteCalls.Load() != 0 {
		t.Fatalf("expected 0 remote calls on disk cache hit, got %d", remoteCalls.Load())
	}

	// 4. Web session cookie triggers live remote fallback + caches to disk.
	webLoginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass"}`))
	webLoginReq.Header.Set("Content-Type", "application/json")
	webLoginReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	webLoginRec := httptest.NewRecorder()
	httpSrv.ServeHTTP(webLoginRec, webLoginReq)
	if webLoginRec.Code != http.StatusOK {
		t.Fatalf("web login failed: %d", webLoginRec.Code)
	}
	var sessionCookie *http.Cookie
	for _, c := range webLoginRec.Result().Cookies() {
		if c.Name == auth.SessionCookieName && c.Path == "/api" && c.Value != "" {
			sessionCookie = c
			break
		}
	}
	if sessionCookie == nil {
		t.Fatalf("expected non-empty /api session cookie, got %+v", webLoginRec.Result().Cookies())
	}

	rec3 := httptest.NewRecorder()
	req3 := httptest.NewRequest(http.MethodGet, "/api/artists/avatar?name="+url.QueryEscape("陈绮贞"), nil)
	req3.AddCookie(sessionCookie)
	httpSrv.ServeHTTP(rec3, req3)
	if rec3.Code != http.StatusOK || !bytes.Equal(rec3.Body.Bytes(), remoteJPEG) {
		t.Fatalf("remote fallback avatar failed: code=%d body=%s", rec3.Code, rec3.Body.String())
	}
	if callsAfterFirst := remoteCalls.Load(); callsAfterFirst != 2 {
		t.Fatalf("expected 2 remote calls (search + image), got %d", callsAfterFirst)
	}

	// Second request hits disk cache written above -> 0 extra remote calls.
	rec4 := httptest.NewRecorder()
	req4 := httptest.NewRequest(http.MethodGet, "/api/artists/avatar?name="+url.QueryEscape("陈绮贞"), nil)
	req4.AddCookie(sessionCookie)
	httpSrv.ServeHTTP(rec4, req4)
	if rec4.Code != http.StatusOK || !bytes.Equal(rec4.Body.Bytes(), remoteJPEG) {
		t.Fatalf("cached avatar failed: code=%d", rec4.Code)
	}
	if remoteCalls.Load() != 2 {
		t.Fatalf("expected 0 extra remote calls after caching, total=%d", remoteCalls.Load())
	}

	// 5. Missing / ignored / unfound artist returns 404, and HEAD succeeds with empty body.
	rec404 := httptest.NewRecorder()
	req404 := httptest.NewRequest(http.MethodGet, "/api/artists/avatar?name=Unknown+Artist", nil)
	req404.AddCookie(sessionCookie)
	httpSrv.ServeHTTP(rec404, req404)
	if rec404.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for ignored artist, got %d", rec404.Code)
	}

	recMissing := httptest.NewRecorder()
	reqMissing := httptest.NewRequest(http.MethodGet, "/api/artists/avatar", nil)
	reqMissing.AddCookie(sessionCookie)
	httpSrv.ServeHTTP(recMissing, reqMissing)
	if recMissing.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for missing query name, got %d", recMissing.Code)
	}

	recUnfound := httptest.NewRecorder()
	reqUnfound := httptest.NewRequest(http.MethodGet, "/api/artists/avatar?name="+url.QueryEscape("不存在的歌手"), nil)
	reqUnfound.AddCookie(sessionCookie)
	httpSrv.ServeHTTP(recUnfound, reqUnfound)
	if recUnfound.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for unknown artist, got %d", recUnfound.Code)
	}

	recHead := httptest.NewRecorder()
	reqHead := httptest.NewRequest(http.MethodHead, "/api/artists/avatar?name=Taylor+Swift&media_token="+url.QueryEscape(loginPayload.MediaToken), nil)
	httpSrv.ServeHTTP(recHead, reqHead)
	if recHead.Code != http.StatusOK || recHead.Body.Len() != 0 || recHead.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("expected 200 empty body on HEAD, got code=%d bodyLen=%d ct=%q", recHead.Code, recHead.Body.Len(), recHead.Header().Get("Content-Type"))
	}
}
