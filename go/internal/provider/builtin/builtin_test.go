package builtin_test

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/builtin"
	"github.com/agonie0v0/melokeep/internal/transport"
)

func makeMP3Payload() []byte {
	b := []byte{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 10}
	b = append(b, make([]byte, 10)...)
	b = append(b, 0xFF, 0xFB, 0x90, 0x64)
	return append(b, make([]byte, 64)...)
}

func TestBuiltin7ProvidersOffline(t *testing.T) {
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		switch {
		case strings.HasPrefix(p, "/audio/mp3") || strings.HasSuffix(p, ".mp3"):
			w.Header().Set("Content-Type", "audio/mpeg")
			w.Header().Set("Content-Length", "8000000")
			_, _ = w.Write(makeMP3Payload())
		case strings.HasPrefix(p, "/audio/preview"):
			w.Header().Set("Content-Type", "audio/mpeg")
			w.Header().Set("Content-Length", "480000")
			_, _ = w.Write(makeMP3Payload())

		// Jamendo
		case p == "/jamendo/tracks":
			cid := r.URL.Query().Get("client_id")
			if cid == "" || cid == "bad" {
				_, _ = w.Write([]byte(`{"headers":{"status":"failed","error_message":"invalid client_id"}}`))
				return
			}
			_, _ = fmt.Fprintf(w, `{"headers":{"status":"success"},"results":[{"id":"jm1","name":"Jamendo Track","artist_name":"JM Artist","album_name":"JM Album","duration":150,"audiodownload":"%s/audio/mp3"}]}`, srv.URL)
		case p == "/jamendo/playlist":
			cid := r.URL.Query().Get("client_id")
			if cid == "" || cid == "bad" {
				_, _ = w.Write([]byte(`{"headers":{"status":"failed","error_message":"invalid client_id"}}`))
				return
			}
			_, _ = w.Write([]byte(`{"headers":{"status":"success"},"results":[{"name":"JM PL","tracks":[{"id":"jm1","name":"Jamendo Track","artist_name":"JM Artist"}]}]}`))

		// FMA
		case p == "/fma/search":
			_, _ = fmt.Fprintf(w, `<div data-track-info='{"id":501,"title":"FMA Track","artistName":"FMA Artist","albumTitle":"FMA Album","fileUrl":"%s/audio/mp3"}'></div>`, srv.URL)

		// OpenGameArt
		case p == "/oga/search":
			_, _ = w.Write([]byte(`<span class="art-preview-title"><a href="/content/battle-theme">Battle Theme</a></span>`))
		case p == "/content/battle-theme":
			_, _ = fmt.Fprintf(w, `<h1>Battle Theme</h1><a href="%s/audio/battle.mp3">Download</a>`, srv.URL)

		// Wikimedia Commons
		case p == "/wiki/api":
			_, _ = fmt.Fprintf(w, `{"query":{"pages":{"601":{"pageid":601,"title":"File:TestAudio.mp3","imageinfo":[{"url":"%s/audio/mp3","size":8000000,"mime":"audio/mpeg"}]}}}}`, srv.URL)

		// Audius
		case p == "/audius/search":
			_, _ = w.Write([]byte(`{"data":[{"id":"au1","title":"Audius Track","duration":140,"user":{"name":"AU Artist"}}]}`))
		case p == "/audius/tracks/au1/stream":
			w.Header().Set("Content-Type", "audio/mpeg")
			w.Header().Set("Content-Length", "7000000")
			_, _ = w.Write(makeMP3Payload())

		// CCMixter
		case p == "/ccmixter/query":
			_, _ = fmt.Fprintf(w, `<?xml version="1.0"?><playlist><trackList><track><location>%s/audio/mp3</location><identifier>cc1</identifier><title>CC Track</title><creator>CC Artist</creator><album>CC Album</album><duration>120000</duration></track></trackList></playlist>`, srv.URL)

		// iTunes
		case p == "/itunes/search":
			switch r.URL.Query().Get("term") {
			case "unsafe":
				_, _ = fmt.Fprintf(w, `{"results":[{"collectionId":701,"collectionName":"Bad Pod","artistName":"Host","feedUrl":"%s/itunes/unsafe.rss"}]}`, srv.URL)
			case "preview":
				_, _ = fmt.Fprintf(w, `{"results":[{"collectionId":999,"trackId":702,"collectionName":"Preview Album","trackName":"Preview Track","artistName":"Artist","previewUrl":"%s/audio/preview"}]}`, srv.URL)
			default:
				_, _ = fmt.Fprintf(w, `{"results":[{"collectionId":703,"collectionName":"Good Pod","artistName":"Host","feedUrl":"%s/itunes/good.rss"}]}`, srv.URL)
			}
		case p == "/itunes/lookup":
			switch r.URL.Query().Get("id") {
			case "701":
				_, _ = fmt.Fprintf(w, `{"results":[{"collectionId":701,"feedUrl":"%s/itunes/unsafe.rss"}]}`, srv.URL)
			case "702":
				_, _ = fmt.Fprintf(w, `{"results":[{"collectionId":999,"trackId":702,"trackName":"Preview Track","previewUrl":"%s/audio/preview"}]}`, srv.URL)
			default:
				_, _ = fmt.Fprintf(w, `{"results":[{"collectionId":703,"feedUrl":"%s/itunes/good.rss"}]}`, srv.URL)
			}
		case p == "/itunes/unsafe.rss":
			_, _ = w.Write([]byte(`<!DOCTYPE rss SYSTEM "http://evil"><rss><channel></channel></rss>`))
		case p == "/itunes/good.rss":
			_, _ = fmt.Fprintf(w, `<rss><channel><title>Good Pod</title><item><title>Ep 1</title><enclosure url="%s/audio/mp3" type="audio/mpeg"/></item></channel></rss>`, srv.URL)

		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()

	opts := builtin.Options{
		Pool: pool,
		Endpoints: map[string]string{
			"jamendo_tracks":     srv.URL + "/jamendo/tracks",
			"jamendo_playlist":   srv.URL + "/jamendo/playlist",
			"fma_search":         srv.URL + "/fma/search",
			"opengameart_search": srv.URL + "/oga/search",
			"opengameart_base":   srv.URL,
			"wikimedia_api":      srv.URL + "/wiki/api",
			"audius_search":      srv.URL + "/audius/search",
			"audius_track_base":  srv.URL + "/audius/tracks",
			"ccmixter_query":     srv.URL + "/ccmixter/query",
			"itunes_search":      srv.URL + "/itunes/search",
			"itunes_lookup":      srv.URL + "/itunes/lookup",
		},
	}

	entries := builtin.Entries(opts)
	if len(entries) != 7 {
		t.Fatalf("expected 7 built-in providers, got %d", len(entries))
	}
	for _, e := range entries {
		if e.Meta.Origin != "builtin" || e.Meta.Category == "" || e.Meta.Label == "" {
			t.Fatalf("invalid built-in entry metadata: %+v", e.Meta)
		}
	}

	reg, err := provider.NewRegistry(entries)
	if err != nil {
		t.Fatalf("NewRegistry failed: %v", err)
	}

	baseCtx := provider.WithRequestContext(context.Background(), provider.RequestContext{
		Config: config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets()),
	})
	jamendoCtx := provider.WithRequestContext(context.Background(), provider.RequestContext{
		Config:  config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets()),
		Cookies: map[string]string{"client_id": "test-client-id"},
	})

	for _, p := range reg.List() {
		ctx := baseCtx
		if p.EngineName() == "JamendoMusicClient" {
			ctx = jamendoCtx
		}
		var found []model.ProviderTrack
		sum, err := p.Search(ctx, model.SearchOptions{Keyword: "test", Limit: 3}, func(tr model.ProviderTrack) error {
			found = append(found, tr)
			return nil
		})
		if err != nil {
			t.Fatalf("%s Search failed: %v", p.EngineName(), err)
		}
		if sum.Count == 0 || len(found) == 0 {
			t.Fatalf("%s Search returned 0 items", p.EngineName())
		}
		res, err := p.Resolve(ctx, found[0], model.QualityPolicy{AllowPreview: true})
		if err != nil {
			t.Fatalf("%s Resolve failed: %v", p.EngineName(), err)
		}
		if len(res) == 0 || res[0].URL == "" {
			t.Fatalf("%s Resolve returned empty result", p.EngineName())
		}

		pp, ok := p.(model.PlaylistProvider)
		if !ok {
			t.Fatalf("%s must implement PlaylistProvider interface", p.EngineName())
		}
		if p.Capabilities().Playlist {
			var items []model.ProviderTrack
			plSum, err := pp.Playlist(ctx, srv.URL+"/jamendo.com/playlist/1", func(tr model.ProviderTrack) error {
				items = append(items, tr)
				return nil
			})
			if err != nil || plSum.Count == 0 || len(items) == 0 {
				t.Fatalf("%s Playlist failed: sum=%+v err=%v", p.EngineName(), plSum, err)
			}
		} else {
			_, err := pp.Playlist(ctx, "https://example.invalid/pl", func(tr model.ProviderTrack) error { return nil })
			var de *model.DomainError
			if !errors.As(err, &de) || de.Code != model.ErrUnsupported {
				t.Fatalf("%s undeclared Playlist expected ErrUnsupported, got %v", p.EngineName(), err)
			}
		}
	}

	t.Run("JamendoRequiresConfiguredClientID", func(t *testing.T) {
		p, _ := reg.Get("JamendoMusicClient")

		if _, err := p.Search(baseCtx, model.SearchOptions{Keyword: "ambient", Limit: 2}, func(tr model.ProviderTrack) error { return nil }); err == nil {
			t.Fatal("expected Jamendo Search without client_id to fail")
		} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrAuthRequired || de.HTTPStatus() != http.StatusUnauthorized {
			t.Fatalf("expected 401 ErrAuthRequired from Search, got %v", err)
		}

		if _, err := p.Resolve(baseCtx, model.ProviderTrack{SourceID: "jm1"}, model.QualityPolicy{}); err == nil {
			t.Fatal("expected Jamendo Resolve without client_id to fail")
		} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrAuthRequired || de.HTTPStatus() != http.StatusUnauthorized {
			t.Fatalf("expected 401 ErrAuthRequired from Resolve, got %v", err)
		}

		pp := p.(model.PlaylistProvider)
		if _, err := pp.Playlist(baseCtx, "https://jamendo.com/playlist/1", func(tr model.ProviderTrack) error { return nil }); err == nil {
			t.Fatal("expected Jamendo Playlist without client_id to fail")
		} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrAuthRequired || de.HTTPStatus() != http.StatusUnauthorized {
			t.Fatalf("expected 401 ErrAuthRequired from Playlist, got %v", err)
		}

		// Raw client_id inside SourceSettings.Cookie must also be accepted.
		rt := config.DefaultRuntimeConfig()
		rt.SourceSettings["JamendoMusicClient"] = model.SourceSettingDTO{Cookie: "raw-client-id-123"}
		rawCfgCtx := provider.WithRequestContext(context.Background(), provider.RequestContext{
			Config: config.NewSnapshot(rt, config.DefaultResourceBudgets()),
		})
		sum, err := p.Search(rawCfgCtx, model.SearchOptions{Keyword: "ambient", Limit: 1}, func(tr model.ProviderTrack) error { return nil })
		if err != nil || sum.Count != 1 {
			t.Fatalf("expected raw client_id in SourceSettings to work, sum=%+v err=%v", sum, err)
		}
	})

	t.Run("ITunesRejectsDoctypeAndFlagsPreview", func(t *testing.T) {
		p, _ := reg.Get("ITunesMusicClient")
		if !p.Capabilities().Episodes {
			t.Fatal("ITunesMusicClient must declare Episodes=true")
		}
		_, err := p.Resolve(baseCtx, model.ProviderTrack{SourceID: "701"}, model.QualityPolicy{})
		var de *model.DomainError
		if !errors.As(err, &de) || de.Code != model.ErrInvalidResponse {
			t.Fatalf("expected ErrInvalidResponse for DOCTYPE RSS, got %v", err)
		}

		var previewFound []model.ProviderTrack
		_, err = p.Search(baseCtx, model.SearchOptions{Keyword: "preview", Limit: 2}, func(tr model.ProviderTrack) error {
			previewFound = append(previewFound, tr)
			return nil
		})
		if err != nil || len(previewFound) != 1 || previewFound[0].SourceID != "702" || previewFound[0].Title != "Preview Track" || previewFound[0].Episodes != 0 {
			t.Fatalf("unexpected iTunes preview search result: found=%+v err=%v", previewFound, err)
		}

		var feedFound []model.ProviderTrack
		_, err = p.Search(baseCtx, model.SearchOptions{Keyword: "good", Limit: 2}, func(tr model.ProviderTrack) error {
			feedFound = append(feedFound, tr)
			return nil
		})
		if err != nil || len(feedFound) != 1 || feedFound[0].SourceID != "703" || feedFound[0].Episodes != -1 {
			t.Fatalf("expected iTunes podcast feed search track Episodes=-1, found=%+v err=%v", feedFound, err)
		}

		_, err = p.Resolve(baseCtx, previewFound[0], model.QualityPolicy{AllowPreview: false})
		if !errors.As(err, &de) || de.Code != model.ErrAuthRequired {
			t.Fatalf("expected ErrAuthRequired when preview disallowed, got %v", err)
		}
		res, err := p.Resolve(baseCtx, previewFound[0], model.QualityPolicy{AllowPreview: true})
		if err != nil || len(res) != 1 || !res[0].PreviewOnly || !res[0].Variant.PreviewOnly {
			t.Fatalf("expected PreviewOnly=true result, got res=%+v err=%v", res, err)
		}

		epProv, ok := p.(provider.EpisodeProvider)
		if !ok {
			t.Fatal("ITunesMusicClient should implement provider.EpisodeProvider")
		}
		eps, err := epProv.Episodes(baseCtx, model.ProviderTrack{SourceID: "703"})
		if err != nil || len(eps) != 1 || eps[0].Title != "Ep 1" {
			t.Fatalf("expected 1 podcast episode, got eps=%+v err=%v", eps, err)
		}
		epRes, err := p.Resolve(baseCtx, eps[0], model.QualityPolicy{})
		if err != nil || len(epRes) == 0 || epRes[0].PreviewOnly {
			t.Fatalf("expected targeted podcast episode resolve to succeed, got res=%+v err=%v", epRes, err)
		}
	})

	t.Run("CancelPropagates", func(t *testing.T) {
		p, _ := reg.Get("AudiusMusicClient")
		cctx, cancel := context.WithCancel(baseCtx)
		cancel()
		_, err := p.Search(cctx, model.SearchOptions{Keyword: "test", Limit: 1}, func(tr model.ProviderTrack) error { return nil })
		var de *model.DomainError
		if !errors.As(err, &de) || de.Code != model.ErrCancelled {
			t.Fatalf("expected ErrCancelled, got %v", err)
		}
	})
}
