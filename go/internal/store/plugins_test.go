package store_test

import (
	"context"
	"net/http"
	"testing"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func TestProviderPluginCRUD(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	list, err := st.ListProviderPlugins(ctx)
	if err != nil {
		t.Fatalf("ListProviderPlugins initial: %v", err)
	}
	if len(list) != 0 {
		t.Fatalf("expected 0 plugins initially, got %d", len(list))
	}

	if err := st.UpsertProviderPlugin(ctx, store.ProviderPluginRecord{ID: ""}); err == nil {
		t.Fatal("expected error for empty plugin ID")
	} else if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 for empty ID, got %v", err)
	}

	if err := st.UpsertProviderPlugin(ctx, store.ProviderPluginRecord{
		ID:           "bad-json",
		ManifestJSON: "{broken",
	}); err == nil {
		t.Fatal("expected error for invalid manifest json")
	}

	rec1 := store.ProviderPluginRecord{
		ID:                 "plugin-alpha",
		Enabled:            true,
		SourceURL:          "https://example.invalid/alpha.js",
		SHA256:             "abc123",
		ManifestJSON:       `{"api_version":1,"id":"plugin-alpha","name":"Alpha","version":"1.0.0"}`,
		SettingsJSON:       `{"client_id":"demo"}`,
		AllowedOriginsJSON: `["https://api.example.invalid"]`,
		CreatedAt:          1000,
		UpdatedAt:          1000,
	}
	if err := st.UpsertProviderPlugin(ctx, rec1); err != nil {
		t.Fatalf("UpsertProviderPlugin insert alpha: %v", err)
	}

	rec2 := store.ProviderPluginRecord{
		ID:           "plugin-beta",
		Enabled:      false,
		ManifestJSON: `{"api_version":1,"id":"plugin-beta","name":"Beta","version":"0.1.0"}`,
		LastError:    "timeout",
		CreatedAt:    2000,
		UpdatedAt:    2000,
	}
	if err := st.UpsertProviderPlugin(ctx, rec2); err != nil {
		t.Fatalf("UpsertProviderPlugin insert beta: %v", err)
	}

	got, ok, err := st.GetProviderPlugin(ctx, "plugin-alpha")
	if err != nil || !ok {
		t.Fatalf("GetProviderPlugin alpha: ok=%v err=%v", ok, err)
	}
	if !got.Enabled || got.SourceURL != rec1.SourceURL || got.SHA256 != "abc123" || got.SettingsJSON != rec1.SettingsJSON || got.AllowedOriginsJSON != rec1.AllowedOriginsJSON {
		t.Fatalf("unexpected alpha record: %+v", got)
	}

	// Update alpha preserving created_at while updating enabled, settings, last_error, updated_at.
	updated := rec1
	updated.Enabled = false
	updated.SettingsJSON = `{"client_id":"updated"}`
	updated.LastError = "rate limited"
	updated.CreatedAt = 9999 // must be preserved as original 1000 on conflict update
	updated.UpdatedAt = 3000
	if err := st.UpsertProviderPlugin(ctx, updated); err != nil {
		t.Fatalf("UpsertProviderPlugin update alpha: %v", err)
	}

	got2, ok, err := st.GetProviderPlugin(ctx, "plugin-alpha")
	if err != nil || !ok {
		t.Fatalf("GetProviderPlugin alpha after update: ok=%v err=%v", ok, err)
	}
	if got2.Enabled || got2.CreatedAt != 1000 || got2.UpdatedAt != 3000 || got2.LastError != "rate limited" || got2.SettingsJSON != `{"client_id":"updated"}` {
		t.Fatalf("unexpected updated alpha record: %+v", got2)
	}

	list, err = st.ListProviderPlugins(ctx)
	if err != nil {
		t.Fatalf("ListProviderPlugins: %v", err)
	}
	if len(list) != 2 || list[0].ID != "plugin-alpha" || list[1].ID != "plugin-beta" {
		t.Fatalf("unexpected plugin list: %+v", list)
	}
	if list[1].SettingsJSON != "{}" || list[1].AllowedOriginsJSON != "[]" || list[1].Enabled {
		t.Fatalf("unexpected beta defaults: %+v", list[1])
	}

	deleted, err := st.DeleteProviderPlugin(ctx, "plugin-alpha")
	if err != nil || !deleted {
		t.Fatalf("DeleteProviderPlugin alpha: deleted=%v err=%v", deleted, err)
	}
	deletedAgain, err := st.DeleteProviderPlugin(ctx, "plugin-alpha")
	if err != nil || deletedAgain {
		t.Fatalf("DeleteProviderPlugin nonexistent: deleted=%v err=%v", deletedAgain, err)
	}
	if _, ok, err := st.GetProviderPlugin(ctx, "plugin-alpha"); err != nil || ok {
		t.Fatalf("expected alpha deleted, ok=%v err=%v", ok, err)
	}
}
