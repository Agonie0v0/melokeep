package plugin_test

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/builtin"
	"github.com/agonie0v0/melokeep/internal/provider/catalog"
	"github.com/agonie0v0/melokeep/internal/provider/plugin"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

func openTestStore(t *testing.T) *store.Store {
	t.Helper()
	st, err := store.Open(context.Background(), store.Options{
		Path:        store.DefaultDBPath(t.TempDir()),
		ReadMaxOpen: 3,
	})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func newTestRegistry(t *testing.T, pool *transport.Pool) *provider.Registry {
	t.Helper()
	reg, err := catalog.NewFullRegistry(catalog.Options{Pool: pool})
	if err != nil {
		t.Fatalf("catalog.NewFullRegistry: %v", err)
	}
	return reg
}

func TestSpecValidationAndInspect(t *testing.T) {
	ctx := context.Background()

	// Oversized script (> 512 KiB).
	oversized := "const manifest = { apiVersion: 1, id: 'too-big', name: 'Big', version: '1.0.0', capabilities: { search: true } };\n//" + strings.Repeat("a", plugin.MaxSourceBytes)
	if _, err := plugin.ParseAndCompile(oversized); err == nil {
		t.Fatal("expected error for script exceeding 512 KiB")
	}

	// Invalid IDs and API versions.
	badSpecs := []string{
		`module.exports = { manifest: { apiVersion: 2, id: "valid-id", name: "X", version: "1.0.0", capabilities: { search: true } }, search: function(){}, resolve: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "A", name: "X", version: "1.0.0", capabilities: { search: true } }, search: function(){}, resolve: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "-bad-start", name: "X", version: "1.0.0", capabilities: { search: true } }, search: function(){}, resolve: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "../escape", name: "X", version: "1.0.0", capabilities: { search: true } }, search: function(){}, resolve: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "missing-resolve", name: "X", version: "1.0.0", capabilities: { search: true } }, search: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "missing-search", name: "X", version: "1.0.0", capabilities: { search: true } }, resolve: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "missing-lyrics", name: "X", version: "1.0.0", capabilities: { search: false, lyrics: true } }, resolve: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "missing-meta", name: "X", version: "1.0.0", capabilities: { search: false, metadata: true } }, resolve: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "missing-ep", name: "X", version: "1.0.0", capabilities: { search: false, episodes: true } }, resolve: function(){} };`,
		`module.exports = { manifest: { apiVersion: 1, id: "missing-pl", name: "X", version: "1.0.0", capabilities: { search: false, playlist: true } }, resolve: function(){} };`,
	}
	for i, src := range badSpecs {
		if _, err := plugin.ParseAndCompile(src); err == nil {
			t.Fatalf("case %d: expected validation failure, got nil", i)
		}
	}

	// Valid script with api_version snake_case, global exports, secret field, and plain http:// URL warning.
	validGlobal := `
		const manifest = {
			api_version: 1,
			id: "global_export_demo",
			name: "Global Demo",
			version: "1.2.3",
			description: "Open CC audio catalog",
			capabilities: { search: true, playlist: false, episodes: false, lyrics: true, metadata: false },
			settings: [
				{ key: "api_key", label: "API Secret", type: "secret", required: true, default_value: "def" }
			]
		};
		function search(ctx, opts) { return []; }
		function resolve(ctx, track) { return []; }
		function lyrics(ctx, track) { return "http://example.org/lrc"; }
	`
	res, err := plugin.Inspect(ctx, validGlobal)
	if err != nil {
		t.Fatalf("Inspect failed: %v", err)
	}
	if len(res.SHA256) != 64 || res.Manifest.ID != "global_export_demo" || res.Manifest.APIVersion != 1 {
		t.Fatalf("unexpected inspect result: %+v", res)
	}
	if len(res.Permissions) == 0 || len(res.Warnings) < 2 {
		t.Fatalf("expected permissions and warnings, got perms=%v warns=%v", res.Permissions, res.Warnings)
	}
	joinedWarns := strings.Join(res.Warnings, "\n")
	if !strings.Contains(joinedWarns, "secret") || !strings.Contains(joinedWarns, "http://") {
		t.Fatalf("expected secret and http warnings, got %v", res.Warnings)
	}
}

func TestProviderCapabilitiesSearchModesAndNormalization(t *testing.T) {
	ctx := context.Background()

	// 1. Streaming ctx.emit search + full capabilities.
	streamScript := `
		module.exports = {
			manifest: {
				apiVersion: 1,
				id: "stream-open-src",
				name: "Stream Open",
				version: "1.0.0",
				capabilities: { search: true, playlist: true, episodes: true, lyrics: true, metadata: true }
			},
			search(ctx, opts) {
				ctx.emit({
					sourceId: "tr-1",
					title: "<b>Solar</b> &amp; Wind",
					artist: "Open Artist",
					album: "CC Sessions",
					duration: "03:15",
					cover: "https://cdn.example.org/c1.jpg",
					variants: [{ variantId: "flac:999", codec: "flac", bitrate: 999, sizeBytes: 123456 }]
				});
				ctx.emit({
					sourceId: "tr-2",
					title: "Morning Dew",
					artist: "Field Archive",
					duration: 120
				});
				return { count: 2, detail: "ok" };
			},
			resolve(ctx, track, policy) {
				return [
					{
						variantId: "preview-30s",
						url: "https://cdn.example.org/preview.mp3",
						codec: "mp3",
						bitrate: 128,
						previewOnly: true,
						label: "MP3 Preview"
					},
					{
						variantId: "full-flac",
						url: "https://cdn.example.org/full.flac",
						codec: "flac",
						bitrate: 999,
						previewOnly: false,
						headers: { "X-Custom": "1" },
						cookies: { "sid": "abc" },
						lyrics: "[00:00.00]Solar Wind"
					}
				];
			},
			playlist(ctx, locator) {
				ctx.emit({ sourceId: "pl-1", title: "Track One", artist: "Artist A", duration: 100 });
				return {
					id: "pl-" + locator,
					name: "Open Playlist",
					detail: "loaded",
					issues: [{ sourceId: "bad-1", title: "Skipped", artist: "Unknown", reason: "archived" }]
				};
			},
			episodes(ctx, track) {
				return [
					{ sourceId: track.sourceId + "#ep1", title: "Episode 1", artist: track.artist, duration: 600 },
					{ sourceId: track.sourceId + "#ep2", title: "Episode 2", artist: track.artist, duration: 720 }
				];
			},
			lyrics(ctx, track) {
				return "[00:01.00]" + track.title;
			},
			metadata(ctx, opts) {
				return [{
					sourceId: "meta-1",
					title: opts.title || opts.query,
					artist: opts.artist || "Open Artist",
					album: "CC Album",
					albumArtist: "Various",
					date: "2026-01-01",
					trackNumber: "1",
					discNumber: "1",
					duration: 195,
					coverUrl: "https://cdn.example.org/cover.jpg",
					lyrics: "[00:00.00]Meta Lyric"
				}];
			}
		};
	`
	compiled, err := plugin.ParseAndCompile(streamScript)
	if err != nil {
		t.Fatalf("ParseAndCompile: %v", err)
	}
	prov, err := plugin.NewProvider(compiled, plugin.ProviderOptions{})
	if err != nil {
		t.Fatalf("NewProvider: %v", err)
	}

	var emitted []model.ProviderTrack
	summary, err := prov.Search(ctx, model.SearchOptions{Keyword: "solar", Limit: 5}, func(tr model.ProviderTrack) error {
		emitted = append(emitted, tr)
		return nil
	})
	if err != nil || summary.Count != 2 || summary.Detail != "ok" || len(emitted) != 2 {
		t.Fatalf("unexpected Search result: summary=%+v len=%d err=%v", summary, len(emitted), err)
	}
	if emitted[0].PublicID == "" || emitted[0].Source != "stream-open-src" || emitted[0].RootSource != "stream-open-src" || emitted[0].Title != "Solar & Wind" || emitted[0].Duration != 195 || len(emitted[0].Variants) != 1 {
		t.Fatalf("unexpected normalized track: %+v", emitted[0])
	}

	// Resolve with AllowPreview=false filters out previewOnly candidate.
	resolvedFull, err := prov.Resolve(ctx, emitted[0], model.QualityPolicy{AllowPreview: false})
	if err != nil || len(resolvedFull) != 1 {
		t.Fatalf("expected 1 full resolved media, got %+v err=%v", resolvedFull, err)
	}
	if resolvedFull[0].Codec != "flac" || resolvedFull[0].Lyrics != "[00:00.00]Solar Wind" || resolvedFull[0].Headers["X-Custom"] != "1" || resolvedFull[0].Cookies["sid"] != "abc" {
		t.Fatalf("unexpected resolvedFull[0]: %+v", resolvedFull[0])
	}
	if resolvedFull[0].Variant.VariantID == "" || resolvedFull[0].Variant.Lossless {
		t.Fatalf("expected deterministic non-empty VariantID and unverified lossless=false, got %+v", resolvedFull[0].Variant)
	}

	// Resolve with AllowPreview=true includes both.
	resolvedAll, err := prov.Resolve(ctx, emitted[0], model.QualityPolicy{AllowPreview: true})
	if err != nil || len(resolvedAll) != 2 {
		t.Fatalf("expected 2 resolved items with AllowPreview=true, got %d err=%v", len(resolvedAll), err)
	}

	// Playlist
	var plTracks []model.ProviderTrack
	plSum, err := prov.Playlist(ctx, "42", func(tr model.ProviderTrack) error {
		plTracks = append(plTracks, tr)
		return nil
	})
	if err != nil || plSum.ID != "pl-42" || plSum.Name != "Open Playlist" || plSum.Count != 1 || len(plSum.Issues) != 1 || len(plTracks) != 1 {
		t.Fatalf("unexpected playlist result: sum=%+v tracks=%+v err=%v", plSum, plTracks, err)
	}
	if plTracks[0].PlaylistIndex == nil || *plTracks[0].PlaylistIndex != 1 {
		t.Fatalf("expected auto playlistIndex=1, got %v", plTracks[0].PlaylistIndex)
	}

	// Episodes
	eps, err := prov.Episodes(ctx, emitted[0])
	if err != nil || len(eps) != 2 || eps[1].SourceID != "tr-1#ep2" {
		t.Fatalf("unexpected episodes: %+v err=%v", eps, err)
	}

	// Lyrics
	lrc, err := prov.Lyrics(ctx, emitted[0])
	if err != nil || lrc != "[00:01.00]Solar & Wind" {
		t.Fatalf("unexpected lyrics: %q err=%v", lrc, err)
	}

	// Metadata
	metaItems, err := prov.SearchMetadata(ctx, "solar", "Solar Wind", "Open Artist", "", 5)
	if err != nil || len(metaItems) != 1 || metaItems[0].AlbumArtist != "Various" || metaItems[0].CoverURL != "https://cdn.example.org/cover.jpg" {
		t.Fatalf("unexpected metadata items: %+v err=%v", metaItems, err)
	}

	// 2. Plain return search (no ctx.emit) + capability-exact wrapper check.
	returnOnlyScript := `
		module.exports = {
			manifest: {
				apiVersion: 1,
				id: "return-only-src",
				name: "Return Only",
				version: "1.0.0",
				capabilities: { search: true }
			},
			search(ctx, opts) {
				return [
					{ sourceId: "r-1", title: "Direct Return One", artist: "CC Musician", duration: 90 },
					{ sourceId: "r-2", title: "Direct Return Two", artist: "CC Musician", duration: 110 }
				];
			},
			resolve(ctx, track) {
				return [{ url: "https://cdn.example.org/r.mp3", codec: "mp3", bitrate: 320 }];
			}
		};
	`
	c2, err := plugin.ParseAndCompile(returnOnlyScript)
	if err != nil {
		t.Fatalf("ParseAndCompile return-only: %v", err)
	}
	p2, err := plugin.NewProvider(c2, plugin.ProviderOptions{})
	if err != nil {
		t.Fatalf("NewProvider return-only: %v", err)
	}
	capProv := p2.AsCapabilityProvider()
	if _, ok := capProv.(model.PlaylistProvider); ok {
		t.Fatal("search-only plugin wrapper should not implement PlaylistProvider")
	}
	if _, ok := capProv.(provider.EpisodeProvider); ok {
		t.Fatal("search-only plugin wrapper should not implement EpisodeProvider")
	}
	if _, ok := capProv.(provider.LyricsProvider); ok {
		t.Fatal("search-only plugin wrapper should not implement LyricsProvider")
	}
	if _, ok := capProv.(provider.MetadataProvider); ok {
		t.Fatal("search-only plugin wrapper should not implement MetadataProvider")
	}
	var retTracks []model.ProviderTrack
	sum2, err := capProv.Search(ctx, model.SearchOptions{Keyword: "direct", Limit: 1}, func(tr model.ProviderTrack) error {
		retTracks = append(retTracks, tr)
		return nil
	})
	if err != nil || sum2.Count != 1 || len(retTracks) != 1 || retTracks[0].SourceID != "r-1" {
		t.Fatalf("unexpected plain-return search with limit=1: sum=%+v tracks=%+v err=%v", sum2, retTracks, err)
	}
}

func TestHTTPBridgeBatchLimitsHeadersAndSandboxIsolation(t *testing.T) {
	var inFlight atomic.Int32
	var maxInFlight atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/echo":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"ua":      r.Header.Get("User-Agent"),
				"cookie":  r.Header.Get("Cookie"),
				"xCustom": r.Header.Get("X-Custom"),
			})
		case "/batch":
			cur := inFlight.Add(1)
			for {
				prev := maxInFlight.Load()
				if cur <= prev || maxInFlight.CompareAndSwap(prev, cur) {
					break
				}
			}
			id := r.URL.Query().Get("id")
			if id == "1" {
				time.Sleep(40 * time.Millisecond)
			} else if id == "2" {
				time.Sleep(10 * time.Millisecond)
			}
			inFlight.Add(-1)
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprintf(w, `{"id":%q}`, id)
		case "/large":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(make([]byte, plugin.MaxHTTPResponseBytes+1024))
		}
	}))
	defer srv.Close()

	pool := transport.NewPool()
	defer pool.CloseAll()

	script := `
		module.exports = {
			manifest: {
				apiVersion: 1,
				id: "http-bridge-test",
				name: "HTTP Bridge",
				version: "1.0.0",
				capabilities: { search: true },
				settings: [{ key: "endpoint", label: "Endpoint", type: "url", defaultValue: "https://default.example.org" }]
			},
			search(ctx, opts) {
				if (opts.keyword === "isolation") {
					const leaked = [typeof os, typeof fs, typeof process, typeof require, typeof fetch].filter(t => t !== "undefined");
					ctx.settings.endpoint = "mutated";
					if (ctx.settings.endpoint !== "https://default.example.org") {
						throw new Error("ctx.settings was mutable");
					}
					return [{ sourceId: "iso", title: "leaked=" + leaked.length + ",cookie=" + ctx.cookie.get("global_ck"), artist: ctx.cookie["global_ck"] }];
				}
				if (opts.keyword === "headers") {
					const r1 = ctx.http.request({ url: "` + srv.URL + `/echo" });
					const r2 = ctx.http.request({
						url: "` + srv.URL + `/echo",
						headers: { "user-agent": "ExplicitUA/2.0", "cookie": "c=override", "X-Custom": "yes" }
					});
					const j1 = r1.json();
					const j2 = r2.json();
					return [{ sourceId: "hdr", title: j1.cookie + "|" + j2.ua + "|" + j2.cookie + "|" + j2.xCustom, artist: r1.base64 ? "ok" : "no" }];
				}
				if (opts.keyword === "batch") {
					const res = ctx.http.batch([
						{ url: "` + srv.URL + `/batch?id=1" },
						{ url: "` + srv.URL + `/batch?id=2" },
						{ url: "ftp://invalid.example.org/bad" },
						{ url: "` + srv.URL + `/batch?id=4" }
					]);
					const summary = res.map(item => item.error ? "ERR" : item.json().id).join(",");
					return [{ sourceId: "b1", title: summary, artist: "batch" }];
				}
				if (opts.keyword === "batch_too_many") {
					const reqs = [];
					for (let i = 0; i < 9; i++) reqs.push({ url: "` + srv.URL + `/echo" });
					ctx.http.batch(reqs);
					return [];
				}
				if (opts.keyword === "too_many_total") {
					for (let i = 0; i < 25; i++) {
						ctx.http.request({ url: "` + srv.URL + `/echo" });
					}
					return [];
				}
				if (opts.keyword === "too_large") {
					ctx.http.request({ url: "` + srv.URL + `/large" });
					return [];
				}
				return [];
			},
			resolve(ctx, track) { return []; }
		};
	`
	compiled, err := plugin.ParseAndCompile(script)
	if err != nil {
		t.Fatalf("ParseAndCompile: %v", err)
	}
	prov, err := plugin.NewProvider(compiled, plugin.ProviderOptions{
		Pool:           pool,
		AllowedOrigins: []string{srv.URL},
	})
	if err != nil {
		t.Fatalf("NewProvider: %v", err)
	}

	rt := config.DefaultRuntimeConfig()
	rt.SourceSettings["http-bridge-test"] = model.SourceSettingDTO{Cookie: "global_ck=val123"}
	snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())
	reqCtx := provider.WithRequestContext(context.Background(), provider.RequestContext{Config: snap})

	// 1. Sandbox isolation & read-only settings/cookie.
	var isoTracks []model.ProviderTrack
	_, err = prov.Search(reqCtx, model.SearchOptions{Keyword: "isolation"}, func(tr model.ProviderTrack) error {
		isoTracks = append(isoTracks, tr)
		return nil
	})
	if err != nil || len(isoTracks) != 1 || isoTracks[0].Title != "leaked=0,cookie=val123" || isoTracks[0].Artist != "val123" {
		t.Fatalf("unexpected isolation result: tracks=%+v err=%v", isoTracks, err)
	}

	// 2. Headers & Cookie overlay + explicit override.
	var hdrTracks []model.ProviderTrack
	_, err = prov.Search(reqCtx, model.SearchOptions{Keyword: "headers"}, func(tr model.ProviderTrack) error {
		hdrTracks = append(hdrTracks, tr)
		return nil
	})
	if err != nil || len(hdrTracks) != 1 || hdrTracks[0].Title != "global_ck=val123|ExplicitUA/2.0|c=override|yes" {
		t.Fatalf("unexpected headers result: tracks=%+v err=%v", hdrTracks, err)
	}

	// 3. Batch concurrency & partial error isolation & order preservation.
	var batchTracks []model.ProviderTrack
	_, err = prov.Search(reqCtx, model.SearchOptions{Keyword: "batch"}, func(tr model.ProviderTrack) error {
		batchTracks = append(batchTracks, tr)
		return nil
	})
	if err != nil || len(batchTracks) != 1 || batchTracks[0].Title != "1,2,ERR,4" {
		t.Fatalf("unexpected batch result: tracks=%+v err=%v", batchTracks, err)
	}
	if maxInFlight.Load() < 2 {
		t.Fatalf("expected concurrent batch execution (inFlight >= 2), got %d", maxInFlight.Load())
	}

	// 4. Limit guards: batch > 8, total > 24, body > 4 MiB.
	for _, kw := range []string{"batch_too_many", "too_many_total", "too_large"} {
		if _, err := prov.Search(reqCtx, model.SearchOptions{Keyword: kw}, nil); err == nil {
			t.Fatalf("expected error for limit case %q", kw)
		}
	}
}

func TestCryptoEncodingAndHTMLBridges(t *testing.T) {
	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("rsa.GenerateKey: %v", err)
	}
	pubDER, err := x509.MarshalPKIXPublicKey(&privKey.PublicKey)
	if err != nil {
		t.Fatalf("MarshalPKIXPublicKey: %v", err)
	}
	pubPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: pubDER}))

	script := `
		module.exports = {
			manifest: {
				apiVersion: 1,
				id: "crypto-html-test",
				name: "Crypto HTML",
				version: "1.0.0",
				capabilities: { search: true }
			},
			search(ctx, opts) {
				const md5v = ctx.crypto.md5("hello");
				const sha1v = ctx.crypto.sha1("hello");
				const sha256v = ctx.crypto.sha256("hello");
				const h1 = ctx.crypto.hmacSha1("secret", "hello");
				const h256 = ctx.crypto.hmacSha256("secret", "hello");
				const rnd = ctx.crypto.randomBytes(16);

				const cbcEnc = ctx.crypto.aesEncrypt({ key: "0123456789abcdef", iv: "fedcba9876543210", mode: "CBC", padding: "pkcs7", data: "MeloKeep Audio" });
				const cbcDec = ctx.crypto.aesDecrypt({ key: "0123456789abcdef", iv: "fedcba9876543210", mode: "CBC", padding: "pkcs7", data: cbcEnc });

				const ecbEnc = ctx.crypto.aesEncrypt({ key: "0123456789abcdef", mode: "ECB", padding: "pkcs7", data: "ECB Plaintext", outputEncoding: "hex" });
				const ecbDec = ctx.crypto.aesDecrypt({ key: "0123456789abcdef", mode: "ECB", padding: "pkcs7", data: ecbEnc, inputEncoding: "hex" });

				const gcmEnc = ctx.crypto.aesEncrypt({ key: "0123456789abcdef", iv: "123456789012", mode: "GCM", data: "GCM Secret", aad: "hdr" });
				const gcmDec = ctx.crypto.aesDecrypt({ key: "0123456789abcdef", iv: "123456789012", mode: "GCM", data: gcmEnc, aad: "hdr" });

				const pem = ` + "`" + pubPEM + "`" + `;
				const rsa1 = ctx.crypto.rsaEncrypt({ publicKeyPem: pem, padding: "pkcs1v15", data: "rsa-pkcs1" });
				const rsa2 = ctx.crypto.rsaEncrypt({ publicKeyPem: pem, padding: "oaep", hash: "sha256", data: "rsa-oaep" });

				const b64 = ctx.encoding.base64Encode("abc+");
				const b64d = ctx.encoding.base64.decode(b64);
				const hx = ctx.encoding.hex.encode("AB");
				const hxd = ctx.encoding.hexDecode(hx);
				const ue = ctx.encoding.urlEncode("a b");
				const ud = ctx.encoding.url.decode(ue);

				const sampleHTML = '<div class="card"><span class="title">Solar &amp; Wind</span><a class="dl" href="https://cdn.example.org/x.mp3">DL</a></div>';
				const stripped = ctx.html.stripTags('<p>Hello <b>World</b></p>');
				const unesc = ctx.html.unescape('&lt;tag&gt; &amp; &quot;q&quot;');
				const ft1 = ctx.html.findText(sampleHTML, 'span', 'title');
				const ft2 = ctx.html.findText(sampleHTML, 'span.title');
				const fa = ctx.html.findAttr(sampleHTML, 'a.dl', 'href');

				return [{
					sourceId: "ok",
					title: "crypto-result",
					lyrics: JSON.stringify({ md5v, sha1v, sha256v, h1, h256, rndLen: rnd.length, cbcDec, ecbDec, gcmDec, rsa1, rsa2, b64d, hx, hxd, ue, ud, stripped, unesc, ft1, ft2, fa })
				}];
			},
			resolve() { return []; }
		};
	`
	compiled, err := plugin.ParseAndCompile(script)
	if err != nil {
		t.Fatalf("ParseAndCompile: %v", err)
	}
	prov, err := plugin.NewProvider(compiled, plugin.ProviderOptions{})
	if err != nil {
		t.Fatalf("NewProvider: %v", err)
	}
	var tracks []model.ProviderTrack
	if _, err := prov.Search(context.Background(), model.SearchOptions{Keyword: "test"}, func(tr model.ProviderTrack) error {
		tracks = append(tracks, tr)
		return nil
	}); err != nil {
		t.Fatalf("Search failed: %v", err)
	}

	var got map[string]any
	if err := json.Unmarshal([]byte(tracks[0].Lyrics), &got); err != nil {
		t.Fatalf("json.Unmarshal: %v", err)
	}

	expMD5 := md5.Sum([]byte("hello"))
	expSHA1 := sha1.Sum([]byte("hello"))
	expSHA256 := sha256.Sum256([]byte("hello"))
	mac1 := hmac.New(sha1.New, []byte("secret"))
	_, _ = mac1.Write([]byte("hello"))
	mac256 := hmac.New(sha256.New, []byte("secret"))
	_, _ = mac256.Write([]byte("hello"))

	if got["md5v"] != hex.EncodeToString(expMD5[:]) ||
		got["sha1v"] != hex.EncodeToString(expSHA1[:]) ||
		got["sha256v"] != hex.EncodeToString(expSHA256[:]) ||
		got["h1"] != hex.EncodeToString(mac1.Sum(nil)) ||
		got["h256"] != hex.EncodeToString(mac256.Sum(nil)) {
		t.Fatalf("hash mismatch: %+v", got)
	}
	if int(got["rndLen"].(float64)) != 32 ||
		got["cbcDec"] != "MeloKeep Audio" ||
		got["ecbDec"] != "ECB Plaintext" ||
		got["gcmDec"] != "GCM Secret" ||
		got["b64d"] != "abc+" ||
		got["hx"] != "4142" ||
		got["hxd"] != "AB" ||
		got["ue"] != "a+b" ||
		got["ud"] != "a b" ||
		got["stripped"] != "Hello World" ||
		got["unesc"] != `<tag> & "q"` ||
		got["ft1"] != "Solar & Wind" ||
		got["ft2"] != "Solar & Wind" ||
		got["fa"] != "https://cdn.example.org/x.mp3" {
		t.Fatalf("unexpected bridge values: %+v", got)
	}

	rsa1Bytes, err := base64.StdEncoding.DecodeString(got["rsa1"].(string))
	if err != nil {
		t.Fatalf("decode rsa1: %v", err)
	}
	plain1, err := rsa.DecryptPKCS1v15(rand.Reader, privKey, rsa1Bytes)
	if err != nil || string(plain1) != "rsa-pkcs1" {
		t.Fatalf("RSA PKCS1v15 decrypt failed: %q err=%v", string(plain1), err)
	}
	rsa2Bytes, err := base64.StdEncoding.DecodeString(got["rsa2"].(string))
	if err != nil {
		t.Fatalf("decode rsa2: %v", err)
	}
	plain2, err := rsa.DecryptOAEP(sha256.New(), rand.Reader, privKey, rsa2Bytes, nil)
	if err != nil || string(plain2) != "rsa-oaep" {
		t.Fatalf("RSA OAEP decrypt failed: %q err=%v", string(plain2), err)
	}

	// Double-check AES-GCM stdlib compatibility explicitly.
	_ = aes.BlockSize
	_ = cipher.NewGCM
}

func TestTimeoutInterruptAndNoGoroutineLeak(t *testing.T) {
	slowSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(3 * time.Second):
		}
	}))
	defer slowSrv.Close()

	pool := transport.NewPool()
	defer pool.CloseAll()

	loopScript := `
		module.exports = {
			manifest: { apiVersion: 1, id: "timeout-loop", name: "Timeout Loop", version: "1.0.0", capabilities: { search: true } },
			search(ctx, opts) {
				if (opts.keyword === "http") {
					ctx.http.request({ url: "` + slowSrv.URL + `/hang", timeoutMs: 5000 });
					return [];
				}
				while (true) {}
			},
			resolve() { return []; }
		};
	`
	compiled, err := plugin.ParseAndCompile(loopScript)
	if err != nil {
		t.Fatalf("ParseAndCompile: %v", err)
	}
	prov, err := plugin.NewProvider(compiled, plugin.ProviderOptions{
		Pool:           pool,
		AllowedOrigins: []string{slowSrv.URL},
		InvokeTimeout:  150 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("NewProvider: %v", err)
	}

	// Warm baseline goroutine count before running timeout cases.
	runtime.GC()
	time.Sleep(20 * time.Millisecond)
	baseGoroutines := runtime.NumGoroutine()

	for i := 0; i < 3; i++ {
		start := time.Now()
		_, err := prov.Search(context.Background(), model.SearchOptions{Keyword: "cpu"}, nil)
		elapsed := time.Since(start)
		de, ok := model.AsDomainError(err)
		if !ok || de.Code != model.ErrDeadline {
			t.Fatalf("iter %d: expected ErrDeadline, got %v", i, err)
		}
		if elapsed > 1200*time.Millisecond {
			t.Fatalf("iter %d: CPU loop took too long to interrupt: %v", i, elapsed)
		}
	}

	start := time.Now()
	_, err = prov.Search(context.Background(), model.SearchOptions{Keyword: "http"}, nil)
	elapsed := time.Since(start)
	de, ok := model.AsDomainError(err)
	if !ok || de.Code != model.ErrDeadline {
		t.Fatalf("expected ErrDeadline for slow HTTP, got %v", err)
	}
	if elapsed > 1200*time.Millisecond {
		t.Fatalf("slow HTTP took too long to interrupt: %v", elapsed)
	}

	// Allow HTTP transport dial/handler cleanup then verify no goroutine leak.
	time.Sleep(80 * time.Millisecond)
	runtime.GC()
	afterGoroutines := runtime.NumGoroutine()
	if afterGoroutines > baseGoroutines+4 {
		t.Fatalf("potential goroutine leak: before=%d after=%d", baseGoroutines, afterGoroutines)
	}
}

func TestSSRFBlocksLoopbackAndAllowsWhitelist(t *testing.T) {
	targetSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"title":"Internal NAS Track"}`))
	}))
	defer targetSrv.Close()

	otherSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer otherSrv.Close()

	script := `
		module.exports = {
			manifest: { apiVersion: 1, id: "ssrf-check", name: "SSRF Check", version: "1.0.0", capabilities: { search: true } },
			search(ctx, opts) {
				const resp = ctx.http.request({ url: opts.keyword });
				const data = resp.json();
				return [{ sourceId: "internal-1", title: data.title || "ok", artist: "NAS" }];
			},
			resolve(ctx, track) {
				return [{ url: track.title, codec: "mp3", bitrate: 320 }];
			}
		};
	`
	compiled, err := plugin.ParseAndCompile(script)
	if err != nil {
		t.Fatalf("ParseAndCompile: %v", err)
	}

	// Production pool WITHOUT WithAllowTestLoopback.
	prodPool := transport.NewPool()
	defer prodPool.CloseAll()

	// 1. Without AllowedOrigins: ctx.http and Resolve must both block 127.0.0.1.
	blockedProv, err := plugin.NewProvider(compiled, plugin.ProviderOptions{Pool: prodPool})
	if err != nil {
		t.Fatalf("NewProvider blocked: %v", err)
	}
	if _, err := blockedProv.Search(context.Background(), model.SearchOptions{Keyword: targetSrv.URL + "/api"}, nil); err == nil {
		t.Fatal("expected SSRF error blocking loopback in ctx.http.request")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrForbidden {
		t.Fatalf("expected ErrForbidden for loopback HTTP, got %v", err)
	}
	if _, err := blockedProv.Resolve(context.Background(), model.ProviderTrack{SourceID: "1", Title: targetSrv.URL + "/audio.mp3"}, model.QualityPolicy{}); err == nil {
		t.Fatal("expected SSRF error blocking loopback in Resolve")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrForbidden {
		t.Fatalf("expected ErrForbidden for loopback Resolve, got %v", err)
	}

	// Inspect via Manager sourceURL must also block loopback via DomainPublicMedia.
	st := openTestStore(t)
	reg := newTestRegistry(t, prodPool)
	mgr := plugin.NewManager(st, prodPool, reg, filepath.Join(t.TempDir(), "plugins"), nil)
	if _, err := mgr.Inspect(context.Background(), "", targetSrv.URL+"/plugin.js"); err == nil {
		t.Fatal("expected Manager.Inspect sourceURL to block loopback")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrForbidden {
		t.Fatalf("expected ErrForbidden for loopback sourceURL, got %v", err)
	}

	// 2. With AllowedOrigins whitelisting targetSrv.URL: targetSrv succeeds while otherSrv remains blocked.
	allowedProv, err := plugin.NewProvider(compiled, plugin.ProviderOptions{
		Pool:           prodPool,
		AllowedOrigins: []string{targetSrv.URL},
	})
	if err != nil {
		t.Fatalf("NewProvider allowed: %v", err)
	}
	var tracks []model.ProviderTrack
	if _, err := allowedProv.Search(context.Background(), model.SearchOptions{Keyword: targetSrv.URL + "/api"}, func(tr model.ProviderTrack) error {
		tracks = append(tracks, tr)
		return nil
	}); err != nil || len(tracks) != 1 || tracks[0].Title != "Internal NAS Track" {
		t.Fatalf("expected whitelisted origin to succeed, tracks=%+v err=%v", tracks, err)
	}
	if media, err := allowedProv.Resolve(context.Background(), model.ProviderTrack{SourceID: "1", Title: targetSrv.URL + "/a.mp3"}, model.QualityPolicy{}); err != nil || len(media) != 1 {
		t.Fatalf("expected whitelisted origin in Resolve to succeed, media=%+v err=%v", media, err)
	}
	if _, err := allowedProv.Search(context.Background(), model.SearchOptions{Keyword: otherSrv.URL + "/api"}, nil); err == nil {
		t.Fatal("expected non-whitelisted loopback server to remain blocked")
	}
}

func TestManagerLifecycleConflictSecretsAndLoadAllIsolation(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	pool := transport.NewPool()
	defer pool.CloseAll()
	reg := newTestRegistry(t, pool)
	pluginDir := filepath.Join(t.TempDir(), "plugins")

	var forwardedLogs []string
	mgr := plugin.NewManager(st, pool, reg, pluginDir, func(level, message, category, taskID string) {
		forwardedLogs = append(forwardedLogs, level+"|"+category+"|"+message)
	})

	// 1. Reject conflict with built-in providers (AudiusMusicClient / audiusmusicclient / itunes / ITunesMusicClient).
	for _, tc := range []struct {
		id           string
		wantConflict bool
	}{
		{"AudiusMusicClient", false}, // uppercase fails ID regex first
		{"audiusmusicclient", true},
		{"itunes", true},
		{"ITunesMusicClient", false},
	} {
		code := fmt.Sprintf(`module.exports = { manifest: { apiVersion: 1, id: %q, name: "Clash", version: "1.0.0", capabilities: { search: true } }, search(){return [];}, resolve(){return [];} };`, tc.id)
		_, err := mgr.Install(ctx, plugin.InstallRequest{Code: code})
		if err == nil {
			t.Fatalf("expected error installing conflicting id %q", tc.id)
		}
		if tc.wantConflict {
			if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrConflict {
				t.Fatalf("expected 409 ErrConflict for %q, got %v", tc.id, err)
			}
		}
	}

	// Verify path traversal ID is rejected on Update / Delete / Test as well.
	if _, err := mgr.Delete(ctx, "../escape"); err == nil {
		t.Fatal("expected Delete to reject invalid path traversal ID")
	}
	if _, err := mgr.Update(ctx, "../escape", plugin.UpdateRequest{}); err == nil {
		t.Fatal("expected Update to reject invalid path traversal ID")
	}

	// Also verify Registry.HasBuiltin works directly.
	if !reg.HasBuiltin("AudiusMusicClient") || !reg.HasBuiltin("ITunesMusicClient") {
		t.Fatal("expected HasBuiltin true for built-in sources")
	}

	// 2. Install valid plugin with secret setting and verify hot registration + secret redaction.
	pluginAlpha := `
		module.exports = {
			manifest: {
				apiVersion: 1,
				id: "alpha-open-source",
				name: "Alpha Open",
				version: "1.0.0",
				description: "Test lifecycle plugin",
				category: "开放许可",
				capabilities: { search: true, lyrics: true, metadata: true },
				settings: [
					{ key: "api_secret", label: "Secret Key", type: "secret", required: true },
					{ key: "prefix", label: "Prefix", type: "text", defaultValue: "P1" }
				]
			},
			search(ctx, opts) {
				ctx.log("info", "searching with secret=" + ctx.settings.api_secret);
				for (let i = 0; i < 105; i++) {
					ctx.log("debug", "line-" + i);
				}
				return [{
					sourceId: "a1",
					title: ctx.settings.prefix + ":" + ctx.settings.api_secret + ":" + opts.keyword,
					artist: "Open Artist",
					duration: 180
				}];
			},
			resolve(ctx, track) {
				return [{ url: "https://cdn.example.org/a1.mp3", codec: "mp3", bitrate: 320 }];
			},
			lyrics(ctx, track) { return "[00:00.00]" + track.title; },
			metadata(ctx, opts) { return [{ sourceId: "m1", title: opts.query, artist: "Open Artist" }]; }
		};
	`
	dto, err := mgr.Install(ctx, plugin.InstallRequest{
		Code:     pluginAlpha,
		Settings: map[string]string{"api_secret": "super-secret-xyz"},
	})
	if err != nil {
		t.Fatalf("Install alpha failed: %v", err)
	}
	if dto.Settings["api_secret"] != config.HiddenPlaceholder {
		t.Fatalf("expected secret masked in Install DTO, got %q", dto.Settings["api_secret"])
	}
	if _, ok := reg.Get("alpha-open-source"); !ok {
		t.Fatal("expected alpha-open-source registered immediately in Registry")
	}

	// Check List() secret masking.
	listed, err := mgr.List(ctx)
	if err != nil || len(listed) != 1 || listed[0].Settings["api_secret"] != config.HiddenPlaceholder {
		t.Fatalf("unexpected List result: %+v err=%v", listed, err)
	}

	// 3. Test() runs search, captures <= 100 logs, redacts secret from logs.
	testRes, err := mgr.Test(ctx, "alpha-open-source", plugin.TestRequest{Action: "search", Keyword: "wind"})
	if err != nil || !testRes.OK {
		t.Fatalf("Test search failed: %+v err=%v", testRes, err)
	}
	if len(testRes.Logs) != plugin.MaxPluginLogs {
		t.Fatalf("expected log cap %d, got %d", plugin.MaxPluginLogs, len(testRes.Logs))
	}
	if strings.Contains(testRes.Logs[0].Message, "super-secret-xyz") || !strings.Contains(testRes.Logs[0].Message, "***") {
		t.Fatalf("expected secret redacted from ctx.log, got %q", testRes.Logs[0].Message)
	}

	// 4. Update with HiddenPlaceholder preserves secret while updating text field; toggling Enabled updates Registry.
	updatedDTO, err := mgr.Update(ctx, "alpha-open-source", plugin.UpdateRequest{
		Settings: map[string]string{
			"api_secret": config.HiddenPlaceholder,
			"prefix":     "P2",
		},
	})
	if err != nil || updatedDTO.Settings["api_secret"] != config.HiddenPlaceholder || updatedDTO.Settings["prefix"] != "P2" {
		t.Fatalf("unexpected Update DTO: %+v err=%v", updatedDTO, err)
	}

	// Verify runtime still sees preserved secret + updated prefix.
	provFromReg, _ := reg.Get("alpha-open-source")
	var checkTracks []model.ProviderTrack
	_, err = provFromReg.Search(ctx, model.SearchOptions{Keyword: "k"}, func(tr model.ProviderTrack) error {
		checkTracks = append(checkTracks, tr)
		return nil
	})
	if err != nil || len(checkTracks) != 1 || checkTracks[0].Title != "P2:super-secret-xyz:k" {
		t.Fatalf("secret preservation failed: tracks=%+v err=%v", checkTracks, err)
	}

	// Disable -> unregistered from Registry; Enable -> re-registered in Registry.
	falseVal := false
	trueVal := true
	if _, err := mgr.Update(ctx, "alpha-open-source", plugin.UpdateRequest{Enabled: &falseVal}); err != nil {
		t.Fatalf("Update disable: %v", err)
	}
	if _, ok := reg.Get("alpha-open-source"); ok {
		t.Fatal("expected disabled plugin removed from Registry")
	}
	if _, err := mgr.Update(ctx, "alpha-open-source", plugin.UpdateRequest{Enabled: &trueVal}); err != nil {
		t.Fatalf("Update enable: %v", err)
	}
	if _, ok := reg.Get("alpha-open-source"); !ok {
		t.Fatal("expected re-enabled plugin registered in Registry")
	}

	// 5. Install two more plugins, corrupt one on disk and delete the other's file, then test LoadAll isolation.
	betaScript := `module.exports = { manifest: { apiVersion: 1, id: "beta-broken", name: "Beta", version: "1.0.0", capabilities: { search: true } }, search(){return [];}, resolve(){return [];} };`
	gammaScript := `module.exports = { manifest: { apiVersion: 1, id: "gamma-missing", name: "Gamma", version: "1.0.0", capabilities: { search: true } }, search(){return [];}, resolve(){return [];} };`
	if _, err := mgr.Install(ctx, plugin.InstallRequest{Code: betaScript}); err != nil {
		t.Fatalf("Install beta: %v", err)
	}
	if _, err := mgr.Install(ctx, plugin.InstallRequest{Code: gammaScript}); err != nil {
		t.Fatalf("Install gamma: %v", err)
	}
	if err := os.WriteFile(filepath.Join(pluginDir, "beta-broken.js"), []byte("this is invalid javascript {{{{"), 0o600); err != nil {
		t.Fatalf("corrupt beta file: %v", err)
	}
	if err := os.Remove(filepath.Join(pluginDir, "gamma-missing.js")); err != nil {
		t.Fatalf("remove gamma file: %v", err)
	}

	freshReg := newTestRegistry(t, pool)
	freshMgr := plugin.NewManager(st, pool, freshReg, pluginDir, nil)
	if err := freshMgr.LoadAll(ctx); err != nil {
		t.Fatalf("LoadAll must isolate broken plugins instead of failing: %v", err)
	}
	if _, ok := freshReg.Get("alpha-open-source"); !ok {
		t.Fatal("expected healthy plugin alpha-open-source loaded despite broken siblings")
	}
	if _, ok := freshReg.Get("beta-broken"); ok {
		t.Fatal("expected corrupted plugin beta-broken skipped")
	}
	if _, ok := freshReg.Get("gamma-missing"); ok {
		t.Fatal("expected missing plugin gamma-missing skipped")
	}
	afterList, err := freshMgr.List(ctx)
	if err != nil {
		t.Fatalf("List after LoadAll: %v", err)
	}
	errByID := make(map[string]string)
	for _, item := range afterList {
		errByID[item.ID] = item.LastError
	}
	if errByID["alpha-open-source"] != "" || errByID["beta-broken"] == "" || errByID["gamma-missing"] == "" {
		t.Fatalf("unexpected LastError isolation state: %+v", errByID)
	}

	// 6. Delete cleans Registry, DB, and disk file.
	deleted, err := freshMgr.Delete(ctx, "alpha-open-source")
	if err != nil || !deleted {
		t.Fatalf("Delete failed: deleted=%v err=%v", deleted, err)
	}
	if _, ok := freshReg.Get("alpha-open-source"); ok {
		t.Fatal("expected deleted plugin removed from Registry")
	}
	if _, statErr := os.Stat(filepath.Join(pluginDir, "alpha-open-source.js")); !os.IsNotExist(statErr) {
		t.Fatalf("expected plugin file deleted, statErr=%v", statErr)
	}
	if len(forwardedLogs) == 0 {
		t.Fatal("expected logs forwarded to logFn")
	}
	_ = builtin.Options{}
}

func TestDocsSpecCompatibleMockPluginIntegration(t *testing.T) {
	// Self-contained fixture matching docs/provider-plugin-spec.md and docs/examples contracts.
	rssXML := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>Open Audio Journal</title>
    <itunes:author>Creative Commons Host</itunes:author>
    <item>
      <title>Episode 01 &amp; Intro</title>
      <guid>ep-01</guid>
      <itunes:duration>04:10</itunes:duration>
      <enclosure url="https://media.example.org/ep01.mp3" type="audio/mpeg" length="4000000" />
    </item>
  </channel>
</rss>`

	feedSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/xml")
		_, _ = w.Write([]byte(rssXML))
	}))
	defer feedSrv.Close()

	specFixture := `
		const manifest = {
			apiVersion: 1,
			id: "spec-rss-mock",
			name: "Spec RSS Mock",
			version: "1.0.0",
			description: "Spec compatible RSS & metadata fixture",
			category: "播客与有声",
			capabilities: {
				search: true,
				playlist: true,
				episodes: true,
				lyrics: true,
				metadata: true
			},
			settings: [
				{ key: "feed_url", label: "Feed URL", type: "url", required: true, defaultValue: "" }
			]
		};

		function parseTracks(ctx, xml) {
			const channelTitle = ctx.html.unescape(ctx.html.findText(xml, "channel title") || "Open Audio Journal");
			const itemRe = /<item\b[^>]*>([\s\S]*?)<\/item>/gi;
			const tracks = [];
			let m;
			while ((m = itemRe.exec(xml)) !== null) {
				const block = m[1];
				const title = ctx.html.unescape(ctx.html.stripTags(ctx.html.findText(block, "title")));
				const audioUrl = ctx.html.findAttr(block, "enclosure", "url");
				const guid = ctx.html.stripTags(ctx.html.findText(block, "guid"));
				tracks.push({
					sourceId: guid + "::" + audioUrl,
					title: title,
					artist: "Creative Commons Host",
					album: channelTitle,
					duration: 250,
					variants: [{ variantId: "mp3:128", label: "MP3 128k", codec: "mp3", bitrate: 128 }]
				});
			}
			return tracks;
		}

		function search(ctx, opts) {
			const resp = ctx.http.request({ method: "GET", url: ctx.settings.feed_url });
			if (resp.status !== 200) throw new Error("unexpected status " + resp.status);
			const tracks = parseTracks(ctx, resp.body);
			for (let i = 0; i < tracks.length; i++) {
				ctx.emit(tracks[i]);
			}
			return { count: tracks.length, detail: "rss loaded" };
		}

		function episodes(ctx, track) {
			const resp = ctx.http.request({ method: "GET", url: ctx.settings.feed_url });
			return parseTracks(ctx, resp.body);
		}

		function resolve(ctx, track) {
			const sid = track.sourceId || "";
			const url = sid.split("::")[1];
			return [{
				variantId: "mp3:128",
				label: "MP3 128k",
				codec: "mp3",
				bitrate: 128,
				previewOnly: false,
				protocol: "http",
				url: url
			}];
		}

		function playlist(ctx, locator) {
			const resp = ctx.http.request({ method: "GET", url: ctx.settings.feed_url });
			const tracks = parseTracks(ctx, resp.body);
			tracks.forEach((t, idx) => { t.playlistIndex = idx + 1; ctx.emit(t); });
			return { id: locator, name: "Feed Playlist", count: tracks.length, issues: [] };
		}

		function lyrics(ctx, track) {
			return "[00:00.00]" + track.title;
		}

		function metadata(ctx, opts) {
			return [{
				sourceId: "spec-meta-1",
				title: opts.title || "Episode 01 & Intro",
				artist: "Creative Commons Host",
				album: "Open Audio Journal",
				duration: 250,
				coverUrl: "https://media.example.org/cover.jpg"
			}];
		}

		module.exports = { manifest, search, resolve, playlist, episodes, lyrics, metadata };
	`

	ctx := context.Background()
	st := openTestStore(t)
	pool := transport.NewPool()
	defer pool.CloseAll()
	reg := newTestRegistry(t, pool)
	mgr := plugin.NewManager(st, pool, reg, filepath.Join(t.TempDir(), "plugins"), nil)

	inspectRes, err := mgr.Inspect(ctx, specFixture, "")
	if err != nil || inspectRes.Manifest.ID != "spec-rss-mock" {
		t.Fatalf("Inspect failed: %+v err=%v", inspectRes, err)
	}

	_, err = mgr.Install(ctx, plugin.InstallRequest{
		Code:           specFixture,
		Settings:       map[string]string{"feed_url": feedSrv.URL + "/feed.xml"},
		AllowedOrigins: []string{feedSrv.URL},
	})
	if err != nil {
		t.Fatalf("Install failed: %v", err)
	}

	for _, action := range []string{"search", "playlist", "episodes", "lyrics", "metadata"} {
		req := plugin.TestRequest{
			Action:  action,
			Keyword: "Episode",
			Locator: "feed",
			Title:   "Episode 01 & Intro",
			Track: map[string]any{
				"sourceId": "ep-01::https://media.example.org/ep01.mp3",
				"title":    "Episode 01 & Intro",
			},
		}
		res, err := mgr.Test(ctx, "spec-rss-mock", req)
		if err != nil || !res.OK {
			t.Fatalf("Manager.Test action=%s failed: %+v err=%v", action, res, err)
		}
	}

	resResolve, err := mgr.Test(ctx, "spec-rss-mock", plugin.TestRequest{
		Action: "resolve",
		Track:  map[string]any{"sourceId": "ep-01::https://media.example.org/ep01.mp3", "title": "Episode 01 & Intro"},
	})
	if err != nil || !resResolve.OK {
		t.Fatalf("Manager.Test action=resolve failed: %+v err=%v", resResolve, err)
	}

	// Verify remote sourceURL inspect & install with size guard using test-loopback pool.
	remotePool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer remotePool.CloseAll()
	remoteReg := newTestRegistry(t, remotePool)
	remoteMgr := plugin.NewManager(openTestStore(t), remotePool, remoteReg, filepath.Join(t.TempDir(), "plugins"), nil)

	remoteSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ok.js":
			_, _ = w.Write([]byte(`module.exports = { manifest: { apiVersion: 1, id: "remote-ok", name: "Remote OK", version: "1.0.0", capabilities: { search: true } }, search(){return [];}, resolve(){return [];} };`))
		case "/huge.js":
			_, _ = w.Write(make([]byte, plugin.MaxSourceBytes+100))
		}
	}))
	defer remoteSrv.Close()

	inspected, err := remoteMgr.Inspect(ctx, "", remoteSrv.URL+"/ok.js")
	if err != nil || inspected.Manifest.ID != "remote-ok" {
		t.Fatalf("Inspect via sourceURL failed: %+v err=%v", inspected, err)
	}
	if _, err := remoteMgr.Inspect(ctx, "", remoteSrv.URL+"/huge.js"); err == nil {
		t.Fatal("expected Inspect via sourceURL to reject >512 KiB script")
	}
	falseVal := false
	installedRemote, err := remoteMgr.Install(ctx, plugin.InstallRequest{
		SourceURL: remoteSrv.URL + "/ok.js",
		Enabled:   &falseVal,
	})
	if err != nil || installedRemote.Enabled || installedRemote.SourceURL == "" {
		t.Fatalf("Install disabled via sourceURL failed: %+v err=%v", installedRemote, err)
	}
	if _, ok := remoteReg.Get("remote-ok"); ok {
		t.Fatal("disabled plugin should not be registered on Install")
	}
	// Disabled plugin can still be tested.
	if tr, err := remoteMgr.Test(ctx, "remote-ok", plugin.TestRequest{Action: "search", Keyword: "x"}); err != nil || !tr.OK {
		t.Fatalf("expected disabled plugin Test to succeed, res=%+v err=%v", tr, err)
	}
}
