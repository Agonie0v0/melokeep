package config_test

import (
	"reflect"
	"testing"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

func TestCanonicalEnvAndDefaultSnapshot(t *testing.T) {
	gotEnv := config.CanonicalEnv(map[string]string{
		"MELODOCK_PORT":     "9000",
		"MELODOCK_DATA_DIR": "/legacy/data",
		"MELOKEEP_PORT":     "8765",
		"MELOKEEP_USER":     "admin",
	})
	wantEnv := map[string]string{
		"MELOKEEP_PORT":     "8765",
		"MELOKEEP_DATA_DIR": "/legacy/data",
		"MELOKEEP_USER":     "admin",
	}
	if !reflect.DeepEqual(gotEnv, wantEnv) {
		t.Fatalf("CanonicalEnv mismatch: got=%v want=%v", gotEnv, wantEnv)
	}

	rt := config.DefaultRuntimeConfig()
	wantSources := []string{"AudiusMusicClient", "FMAMusicClient", "ITunesMusicClient"}
	if !reflect.DeepEqual(rt.Sources, wantSources) {
		t.Fatalf("DefaultRuntimeConfig.Sources mismatch: got=%v want=%v", rt.Sources, wantSources)
	}

	snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())
	pub := snap.PublicDTO(nil)
	if pub.Engine != rt.Engine ||
		pub.ResultsPerSource != rt.ResultsPerSource ||
		pub.DownloadConcurrency != rt.DownloadConcurrency ||
		!reflect.DeepEqual(pub.Sources, wantSources) {
		t.Fatalf("PublicDTO mismatch: %+v", pub)
	}
}

func TestRedactAndMergeHiddenConfig(t *testing.T) {
	rt := config.DefaultRuntimeConfig()
	rt.SourceSettings["JamendoMusicClient"] = model.SourceSettingDTO{
		Cookie: "client_id=test_secret_id",
		Proxy:  "http://user:pass@127.0.0.1:7890?token=abc",
	}
	snap := config.NewSnapshot(rt, config.DefaultResourceBudgets())
	dto := snap.PublicDTO(nil)
	if dto.SourceSettings["JamendoMusicClient"].Cookie != config.HiddenPlaceholder {
		t.Fatalf("expected redacted cookie, got %q", dto.SourceSettings["JamendoMusicClient"].Cookie)
	}
	if dto.SourceSettings["JamendoMusicClient"].Proxy != "http://user:%2A%2A%2A@127.0.0.1:7890?token=%2A%2A%2A" {
		t.Fatalf("unexpected redacted proxy: %q", dto.SourceSettings["JamendoMusicClient"].Proxy)
	}

	merged := config.MergeHiddenConfig(
		map[string]any{"cookie": "real-cookie", "nested": map[string]any{"token": "real-tok"}},
		map[string]any{"cookie": config.HiddenPlaceholder, "nested": map[string]any{"token": "new-tok"}},
	)
	if merged["cookie"] != "real-cookie" {
		t.Fatalf("expected preserved cookie, got %v", merged["cookie"])
	}
	if merged["nested"].(map[string]any)["token"] != "new-tok" {
		t.Fatalf("expected updated token, got %v", merged["nested"])
	}
}

func TestSourceAliasesAndSnapshotRevision(t *testing.T) {
	if len(config.CoreSourceAliases()) != 0 {
		t.Fatalf("expected empty CoreSourceAliases, got %+v", config.CoreSourceAliases())
	}
	if config.CanonicalEngineName(" AudiusMusicClient ") != "AudiusMusicClient" {
		t.Fatalf("unexpected CanonicalEngineName: %q", config.CanonicalEngineName(" AudiusMusicClient "))
	}
	if config.PublicSourceID("FMAMusicClient") != "FMAMusicClient" {
		t.Fatalf("unexpected PublicSourceID: %q", config.PublicSourceID("FMAMusicClient"))
	}
	s1 := config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())
	rt2 := config.DefaultRuntimeConfig()
	rt2.ResultsPerSource = 10
	s2 := config.NewSnapshot(rt2, config.DefaultResourceBudgets())
	if s1.Revision == "" || s1.Revision == s2.Revision {
		t.Fatalf("expected distinct non-empty revisions: %q vs %q", s1.Revision, s2.Revision)
	}
}
