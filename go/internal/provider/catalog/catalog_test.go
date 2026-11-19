package catalog_test

import (
	"reflect"
	"sort"
	"testing"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider/catalog"
	"github.com/agonie0v0/melokeep/internal/transport"
)

func TestBuiltinCatalogRegistersOnlySevenCompliantProviders(t *testing.T) {
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	defer pool.CloseAll()

	entries := catalog.AllEntries(catalog.Options{Pool: pool})
	if len(entries) != 7 {
		t.Fatalf("expected 7 catalog entries, got %d", len(entries))
	}

	reg, err := catalog.NewFullRegistry(catalog.Options{Pool: pool})
	if err != nil {
		t.Fatalf("NewFullRegistry failed: %v", err)
	}
	if got := len(reg.List()); got != 7 {
		t.Fatalf("expected 7 providers in Registry.List(), got %d", got)
	}

	wantIDs := []string{
		"AudiusMusicClient",
		"CCMixterMusicClient",
		"FMAMusicClient",
		"ITunesMusicClient",
		"JamendoMusicClient",
		"OpenGameArtMusicClient",
		"WikimediaCommonsMusicClient",
	}
	sort.Strings(wantIDs)
	gotIDs := reg.IDs()
	if !reflect.DeepEqual(gotIDs, wantIDs) {
		t.Fatalf("Registry.IDs mismatch: got=%v want=%v", gotIDs, wantIDs)
	}

	avail := reg.AvailableSources()
	if len(avail) != 7 {
		t.Fatalf("expected 7 AvailableSources, got %d", len(avail))
	}

	seenID := make(map[string]bool, 7)
	seenEngine := make(map[string]bool, 7)
	byEngine := make(map[string]model.AvailableSourceDTO, 7)
	for _, src := range avail {
		if seenID[src.ID] {
			t.Fatalf("duplicate public ID %q", src.ID)
		}
		if seenEngine[src.EngineName] {
			t.Fatalf("duplicate engine_name %q", src.EngineName)
		}
		if src.Origin != "builtin" {
			t.Fatalf("%s expected Origin=builtin, got %q", src.EngineName, src.Origin)
		}
		if src.Label == "" || src.Category == "" || src.Description == "" {
			t.Fatalf("%s missing display metadata: %+v", src.EngineName, src)
		}
		if src.Capability == nil || !src.Capability.Search {
			t.Fatalf("%s unexpected capability: %+v", src.EngineName, src.Capability)
		}
		seenID[src.ID] = true
		seenEngine[src.EngineName] = true
		byEngine[src.EngineName] = src
	}

	if !byEngine["ITunesMusicClient"].Capability.Episodes {
		t.Fatal("ITunesMusicClient must advertise Episodes=true")
	}
	if !byEngine["JamendoMusicClient"].Capability.Playlist {
		t.Fatal("JamendoMusicClient must advertise Playlist=true")
	}

	for _, id := range wantIDs {
		if !reg.HasBuiltin(id) {
			t.Fatalf("expected HasBuiltin(%q)=true", id)
		}
		p, ok := reg.Get(id)
		if !ok || p == nil || p.EngineName() != id {
			t.Fatalf("Registry.Get(%q) failed: p=%v ok=%v", id, p, ok)
		}
	}
}
