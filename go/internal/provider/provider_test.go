package provider_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/builtin"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type stubPluginProvider struct {
	id         string
	engineName string
	caps       model.Capability
}

func (s *stubPluginProvider) ID() string                     { return s.id }
func (s *stubPluginProvider) EngineName() string             { return s.engineName }
func (s *stubPluginProvider) Capabilities() model.Capability { return s.caps }
func (s *stubPluginProvider) Search(_ context.Context, _ model.SearchOptions, _ func(model.ProviderTrack) error) (model.SearchSummary, error) {
	return model.SearchSummary{}, nil
}
func (s *stubPluginProvider) Resolve(_ context.Context, _ model.ProviderTrack, _ model.QualityPolicy) ([]model.ResolvedMedia, error) {
	return nil, nil
}
func (s *stubPluginProvider) Lyrics(_ context.Context, _ model.ProviderTrack) (string, error) {
	return "[00:00.00]sample lyric", nil
}
func (s *stubPluginProvider) SearchMetadata(_ context.Context, _ string, _, _, _ string, _ int) ([]provider.MetadataItem, error) {
	return []provider.MetadataItem{{SourceID: "m1", Title: "Sample", Artist: "Artist"}}, nil
}

func TestRegistryLookupAndDynamicPlugins(t *testing.T) {
	reg, err := provider.NewRegistry(builtin.Entries(builtin.Options{}))
	if err != nil {
		t.Fatalf("NewRegistry failed: %v", err)
	}
	cases := []struct {
		query  string
		wantID string
		wantEn string
	}{
		{"AudiusMusicClient", "AudiusMusicClient", "AudiusMusicClient"},
		{"audiusmusicclient", "AudiusMusicClient", "AudiusMusicClient"},
		{"FMAMusicClient", "FMAMusicClient", "FMAMusicClient"},
		{"ITunesMusicClient", "ITunesMusicClient", "ITunesMusicClient"},
		{"JamendoMusicClient", "JamendoMusicClient", "JamendoMusicClient"},
		{"CCMixterMusicClient", "CCMixterMusicClient", "CCMixterMusicClient"},
		{"OpenGameArtMusicClient", "OpenGameArtMusicClient", "OpenGameArtMusicClient"},
		{"WikimediaCommonsMusicClient", "WikimediaCommonsMusicClient", "WikimediaCommonsMusicClient"},
	}
	for _, tc := range cases {
		p, ok := reg.Get(tc.query)
		if !ok {
			t.Fatalf("expected provider for %q", tc.query)
		}
		if p.ID() != tc.wantID || p.EngineName() != tc.wantEn {
			t.Fatalf("query %q got (%s, %s), want (%s, %s)", tc.query, p.ID(), p.EngineName(), tc.wantID, tc.wantEn)
		}
		if !reg.HasBuiltin(tc.query) {
			t.Fatalf("expected HasBuiltin(%q)=true", tc.query)
		}
	}
	avail := reg.AvailableSources()
	if len(avail) != 7 {
		t.Fatalf("expected 7 available sources, got %d", len(avail))
	}
	for _, s := range avail {
		if s.Origin != "builtin" {
			t.Fatalf("expected Origin=builtin for %s, got %q", s.ID, s.Origin)
		}
		if s.Capability == nil || !s.Capability.Search {
			t.Fatalf("unexpected capability for %s: %+v", s.ID, s.Capability)
		}
	}

	// Plugin conflict with builtin must fail.
	conflictErr := reg.RegisterPlugin(provider.Entry{
		Provider: &stubPluginProvider{id: "AudiusMusicClient", engineName: "AudiusMusicClient"},
		Meta:     provider.Metadata{Label: "Fake Audius"},
	})
	if de, ok := model.AsDomainError(conflictErr); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 conflict overriding builtin, got %v", conflictErr)
	}

	// Register a valid plugin implementing LyricsProvider + MetadataProvider.
	plugV1 := &stubPluginProvider{
		id:         "open-lyrics-plugin",
		engineName: "open-lyrics-plugin",
		caps: model.Capability{
			Tier:     "plugin",
			Note:     "Custom plugin",
			Search:   true,
			Lyrics:   true,
			Metadata: true,
		},
	}
	var _ provider.LyricsProvider = plugV1
	var _ provider.MetadataProvider = plugV1

	if err := reg.RegisterPlugin(provider.Entry{
		Provider: plugV1,
		Meta: provider.Metadata{
			Label:       "Open Lyrics",
			Category:    "插件源",
			PluginID:    "open-lyrics-plugin",
			Version:     "1.0.0",
			Description: "Sample JS provider",
		},
	}); err != nil {
		t.Fatalf("RegisterPlugin v1 failed: %v", err)
	}
	if reg.HasBuiltin("open-lyrics-plugin") {
		t.Fatal("plugin must not be marked builtin")
	}
	if got := len(reg.List()); got != 8 {
		t.Fatalf("expected 8 providers after RegisterPlugin, got %d", got)
	}
	if got := len(reg.PluginEntries()); got != 1 {
		t.Fatalf("expected 1 plugin entry, got %d", got)
	}

	// Re-registering same plugin ID updates it in place.
	plugV2 := &stubPluginProvider{
		id:         "open-lyrics-plugin",
		engineName: "open-lyrics-plugin",
		caps:       plugV1.caps,
	}
	if err := reg.RegisterPlugin(provider.Entry{
		Provider: plugV2,
		Meta: provider.Metadata{
			Label:    "Open Lyrics v2",
			Category: "插件源",
			PluginID: "open-lyrics-plugin",
			Version:  "1.1.0",
		},
	}); err != nil {
		t.Fatalf("RegisterPlugin v2 failed: %v", err)
	}
	if got := len(reg.PluginEntries()); got != 1 {
		t.Fatalf("expected 1 plugin entry after update, got %d", got)
	}
	var foundPlug *model.AvailableSourceDTO
	for _, s := range reg.AvailableSources() {
		if s.ID == "open-lyrics-plugin" {
			cp := s
			foundPlug = &cp
			break
		}
	}
	if foundPlug == nil || foundPlug.Origin != "plugin" || foundPlug.Version != "1.1.0" || foundPlug.Label != "Open Lyrics v2" || !foundPlug.Capability.Lyrics || !foundPlug.Capability.Metadata {
		t.Fatalf("unexpected plugin AvailableSourceDTO: %+v", foundPlug)
	}

	// ReplacePlugins rejects conflicting batch atomically without dropping existing plugins.
	if err := reg.ReplacePlugins([]provider.Entry{
		{Provider: &stubPluginProvider{id: "FMAMusicClient", engineName: "FMAMusicClient"}},
	}); err == nil {
		t.Fatal("expected ReplacePlugins conflict against builtin")
	}
	if got := len(reg.PluginEntries()); got != 1 {
		t.Fatalf("existing plugin should remain intact on failed ReplacePlugins, got %d", got)
	}

	// ReplacePlugins succeeds for valid batch.
	if err := reg.ReplacePlugins([]provider.Entry{
		{
			Provider: &stubPluginProvider{id: "plugin-two", engineName: "plugin-two", caps: model.Capability{Tier: "plugin", Search: true}},
			Meta:     provider.Metadata{Label: "Plugin Two", Version: "0.2.0"},
		},
	}); err != nil {
		t.Fatalf("ReplacePlugins failed: %v", err)
	}
	if _, ok := reg.Get("open-lyrics-plugin"); ok {
		t.Fatal("expected old plugin removed after ReplacePlugins")
	}
	if p, ok := reg.Get("plugin-two"); !ok || p.ID() != "plugin-two" {
		t.Fatalf("expected plugin-two after ReplacePlugins, ok=%v", ok)
	}

	// UnregisterPlugin removes the plugin cleanly.
	reg.UnregisterPlugin("plugin-two")
	if _, ok := reg.Get("plugin-two"); ok {
		t.Fatal("expected plugin-two removed after UnregisterPlugin")
	}
	if got := len(reg.List()); got != 7 {
		t.Fatalf("expected 7 built-in providers remaining, got %d", got)
	}
}

func TestProbeAudioMagicAndRejectsNonAudio(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/flac":
			w.Header().Set("Content-Type", "audio/flac")
			w.Header().Set("Content-Length", "1234567")
			_, _ = w.Write(append([]byte("fLaC"), make([]byte, 64)...))
		case "/id3-mp3":
			w.Header().Set("Content-Length", "512000")
			hdr := []byte{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 10}
			hdr = append(hdr, make([]byte, 10)...)
			hdr = append(hdr, 0xFF, 0xFB, 0x90, 0x64)
			_, _ = w.Write(hdr)
		case "/hls.m3u8":
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			_, _ = w.Write([]byte("#EXTM3U\n#EXTINF:10.0,\nseg.ts\n"))
		case "/html":
			w.Header().Set("Content-Type", "text/html")
			_, _ = w.Write([]byte("<html>not audio</html>"))
		case "/json":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"error":"forbidden"}`))
		case "/rate":
			w.Header().Set("Retry-After", "12")
			w.WriteHeader(http.StatusTooManyRequests)
		}
	}))
	defer srv.Close()

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()
	adapter := provider.NewTransportAdapter(pool)
	ctx := context.Background()

	res, err := adapter.ProbeAudio(ctx, "AudiusMusicClient", srv.URL+"/flac", nil, nil, false)
	if err != nil || res.Ext != "flac" || res.SizeBytes != 1234567 || res.Protocol != "HTTP" {
		t.Fatalf("unexpected flac probe: %+v err=%v", res, err)
	}
	res, err = adapter.ProbeAudio(ctx, "AudiusMusicClient", srv.URL+"/id3-mp3", nil, nil, false)
	if err != nil || res.Ext != "mp3" || res.SizeBytes != 512000 {
		t.Fatalf("unexpected id3-mp3 probe: %+v err=%v", res, err)
	}
	res, err = adapter.ProbeAudio(ctx, "AudiusMusicClient", srv.URL+"/hls.m3u8", nil, nil, false)
	if err != nil || res.Protocol != "HLS" || res.Ext != "m4a" || res.SizeBytes != 0 {
		t.Fatalf("unexpected hls probe: %+v err=%v", res, err)
	}
	if _, err := adapter.ProbeAudio(ctx, "AudiusMusicClient", srv.URL+"/html", nil, nil, false); err == nil {
		t.Fatalf("expected error on html response")
	}
	if _, err := adapter.ProbeAudio(ctx, "AudiusMusicClient", srv.URL+"/json", nil, nil, false); err == nil {
		t.Fatalf("expected error on json response")
	}
	if _, err := adapter.ProbeAudio(ctx, "AudiusMusicClient", srv.URL+"/rate", nil, nil, false); err == nil {
		t.Fatalf("expected rate limit error")
	} else if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrRateLimited || de.RetryAfter != 12 {
		t.Fatalf("expected ErrRateLimited with RetryAfter=12, got %+v", de)
	}
}

func TestMediaVariantLosslessGuardAndImmutableRequestContext(t *testing.T) {
	unverified := provider.NewMediaVariant("v1", "u1", "FLAC?", "flac", 999, false, false, "HTTP", nil, 200)
	if unverified.Lossless {
		t.Fatalf("unverified flac must not claim lossless=true")
	}
	verified := provider.NewMediaVariant("v2", "u2", "FLAC", "flac", 999, true, false, "HTTP", provider.PtrInt64Positive(1024), 200)
	if !verified.Lossless || verified.Availability != "verified" {
		t.Fatalf("verified flac must claim lossless=true, got %+v", verified)
	}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(r.Header.Get("Cookie")))
	}))
	defer srv.Close()

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()
	adapter := provider.NewTransportAdapter(pool)

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			rt := config.DefaultRuntimeConfig()
			cookieVal := "acct=A"
			acctID := "A"
			if idx%2 == 1 {
				cookieVal = "acct=B"
				acctID = "B"
			}
			rt.SourceSettings["JamendoMusicClient"] = model.SourceSettingDTO{Cookie: cookieVal}
			snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())
			ctx := provider.WithRequestContext(context.Background(), provider.RequestContext{
				Config:  snap,
				Account: transport.AccountKey{Source: "JamendoMusicClient", AccountID: acctID},
				MaxWait: 2 * time.Second,
			})
			_, _, _, body, err := adapter.DoBytes(ctx, "JamendoMusicClient", http.MethodGet, srv.URL, nil, provider.CallOptions{IncludeAuth: true})
			if err != nil {
				t.Errorf("DoBytes failed: %v", err)
				return
			}
			if string(body) != cookieVal {
				t.Errorf("cookie leak: got %q want %q", string(body), cookieVal)
			}
		}(i)
	}
	wg.Wait()
}

func TestNormalizeResolvedBitrateAndIsTrialAudio(t *testing.T) {
	// Probed bitrate always takes precedence.
	if got := provider.NormalizeResolvedBitrate("flac", 1411, 999, 30000000, 240); got != 1411 {
		t.Fatalf("expected probed bitrate 1411, got %d", got)
	}
	// Lossless placeholder 999 must be replaced by size/duration calculation when available.
	if got := provider.NormalizeResolvedBitrate("flac", 0, 999, 30000000, 240); got != 1000 {
		t.Fatalf("expected computed lossless bitrate 1000, got %d", got)
	}
	// Lossless placeholder >= 900 without size/duration must be suppressed (0 -> nil).
	if got := provider.NormalizeResolvedBitrate("flac", 0, 999, 0, 0); got != 0 {
		t.Fatalf("expected suppressed bitrate 0 when size/duration unknown, got %d", got)
	}
	// Lossy sentinel >= 500 with size/duration gets capped to MP3 320.
	if got := provider.NormalizeResolvedBitrate("mp3", 0, 999, 20000000, 200); got != 320 {
		t.Fatalf("expected capped mp3 bitrate 320, got %d", got)
	}
	// IsTrialAudio detects truncated clips by size/duration ratio, without vendor-specific filename heuristics.
	if !provider.IsTrialAudio("https://cdn.example.invalid/audio.mp3", 480000, 200, 250000) {
		t.Fatal("expected truncated audio clip to be flagged as trial")
	}
	if provider.IsTrialAudio("https://cdn.example.invalid/full.mp3", 8000000, 200, 250000) {
		t.Fatal("expected full audio clip not to be flagged as trial")
	}
}
