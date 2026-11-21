package model_test

import (
	"encoding/json"
	"net/http"
	"reflect"
	"strings"
	"testing"

	"github.com/agonie0v0/melokeep/internal/model"
)

func TestNewUUIDAndErrors(t *testing.T) {
	a := model.NewUUID()
	b := model.NewUUID()
	if a == b {
		t.Fatalf("expected unique UUIDs, got %s", a)
	}
	if !model.IsValidUUID(a) || !model.IsValidUUID(b) {
		t.Fatalf("invalid UUID format: %s / %s", a, b)
	}
	err := model.NewError(model.ErrConflict, 0, "冲突")
	if err.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409, got %d", err.HTTPStatus())
	}
	de, ok := model.AsDomainError(err)
	if !ok || de.Detail != "冲突" {
		t.Fatalf("AsDomainError failed: %+v", de)
	}
}

func TestMediaVariantHasNoURLOrCredentialFields(t *testing.T) {
	typ := reflect.TypeOf(model.MediaVariant{})
	forbidden := []string{"url", "expiry", "header", "cookie", "credential"}
	for i := 0; i < typ.NumField(); i++ {
		name := strings.ToLower(typ.Field(i).Name)
		tag := strings.ToLower(typ.Field(i).Tag.Get("json"))
		for _, bad := range forbidden {
			if strings.Contains(name, bad) || strings.Contains(tag, bad) {
				t.Fatalf("MediaVariant must not contain %q (field %s)", bad, typ.Field(i).Name)
			}
		}
	}

	// Verify ResolvedMedia exists separately and holds runtime fields.
	rm := model.ResolvedMedia{
		VariantID: "v1",
		URL:       "https://example.test/audio.flac",
	}
	if rm.URL == "" || rm.VariantID != "v1" {
		t.Fatalf("unexpected ResolvedMedia: %+v", rm)
	}
}

func TestPaginationCursor400And409(t *testing.T) {
	fh := model.FiltersHash("open", "ambient")
	cur, err := (model.PaginationCursor{
		SortField:     "name",
		LastID:        "asset-10",
		FiltersHash:   fh,
		IndexRevision: 5,
	}).Encode()
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := model.DecodePaginationCursor(cur, "name", fh, 5)
	if err != nil || decoded.LastID != "asset-10" {
		t.Fatalf("expected valid decode, got %+v err=%v", decoded, err)
	}

	// Malformed -> 400
	if _, err := model.DecodePaginationCursor("!!!not-base64", "name", fh, 5); err == nil {
		t.Fatal("expected error for malformed cursor")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != 400 {
		t.Fatalf("expected 400 for malformed cursor, got %d", de.HTTPStatus())
	}

	// Filter/sort mismatch -> 400
	if _, err := model.DecodePaginationCursor(cur, "name", model.FiltersHash("other", "ambient"), 5); err == nil {
		t.Fatal("expected error for filter mismatch")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != 400 {
		t.Fatalf("expected 400 for filter mismatch, got %d", de.HTTPStatus())
	}

	// Stale index_revision -> 409
	if _, err := model.DecodePaginationCursor(cur, "name", fh, 6); err == nil {
		t.Fatal("expected error for stale index_revision")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != 409 {
		t.Fatalf("expected 409 for stale index_revision, got %d", de.HTTPStatus())
	}
}

func TestCapabilityAndPluginDTOSerialization(t *testing.T) {
	capModel := model.Capability{
		Tier:     "native",
		Note:     "Open licensed audio",
		Search:   true,
		Playlist: false,
		Lyrics:   true,
		Metadata: true,
		Episodes: true,
	}
	rawCap, err := json.Marshal(capModel)
	if err != nil {
		t.Fatalf("marshal Capability: %v", err)
	}
	var capFields map[string]any
	if err := json.Unmarshal(rawCap, &capFields); err != nil {
		t.Fatalf("unmarshal Capability map: %v", err)
	}
	allowedCapKeys := map[string]bool{
		"tier": true, "note": true, "search": true, "playlist": true,
		"lyrics": true, "metadata": true, "episodes": true,
	}
	for k := range capFields {
		if !allowedCapKeys[k] {
			t.Fatalf("unexpected key %q in serialized Capability: %s", k, string(rawCap))
		}
	}

	src := model.AvailableSourceDTO{
		ID:          "sample-plugin",
		Label:       "Sample Plugin",
		Category:    "插件源",
		EngineName:  "sample-plugin",
		Origin:      "plugin",
		PluginID:    "sample-plugin",
		Version:     "1.0.0",
		Description: "Plugin description",
		Capability: &model.SourceCapabilityDTO{
			Tier:     "plugin",
			Note:     "Custom JS source",
			Search:   true,
			Playlist: false,
			Lyrics:   true,
			Metadata: true,
			Episodes: false,
		},
	}
	pluginDTO := model.ProviderPluginDTO{
		ID:      "sample-plugin",
		Enabled: true,
		SHA256:  "deadbeef",
		Manifest: model.PluginManifestDTO{
			APIVersion:  1,
			ID:          "sample-plugin",
			Name:        "Sample Plugin",
			Version:     "1.0.0",
			Description: "Plugin description",
			Category:    "插件源",
			Capabilities: *src.Capability,
			Settings: []model.PluginSettingFieldDTO{
				{
					Key:          "api_key",
					Label:        "API Key",
					Type:         "secret",
					Placeholder:  "Enter key",
					Required:     true,
					DefaultValue: "",
				},
			},
		},
		Settings:       map[string]string{"api_key": "masked"},
		AllowedOrigins: []string{"https://api.example.invalid"},
		CreatedAt:      100,
		UpdatedAt:      200,
	}
	inspectDTO := model.PluginInspectResultDTO{
		SHA256:      "deadbeef",
		Manifest:    pluginDTO.Manifest,
		Permissions: []string{"https://api.example.invalid"},
		Warnings:    []string{},
	}
	testDTO := model.PluginTestResultDTO{
		OK:         true,
		Action:     "search",
		DurationMS: 42,
		Result:     []string{"ok"},
		Logs: []model.PluginLogEntryDTO{
			{Level: "info", Message: "done", Time: "2026-01-01T00:00:00Z"},
		},
	}
	archiveInspectDTO := model.PluginArchiveInspectResultDTO{
		Total:        2,
		ValidCount:   1,
		InvalidCount: 1,
		Entries: []model.PluginArchiveEntryInspectDTO{
			{
				EntryPath:   "b1/sample.js",
				OK:          true,
				SHA256:      "deadbeef",
				Manifest:    &pluginDTO.Manifest,
				Permissions: []string{"声明能力：搜索"},
				Warnings:    []string{},
			},
			{
				EntryPath: "b1/broken.js",
				OK:        false,
				Error:     "插件脚本解析失败",
			},
		},
	}
	archiveInstallDTO := model.PluginArchiveInstallResultDTO{
		Total:          2,
		InstalledCount: 1,
		FailedCount:    1,
		Installed:      []model.ProviderPluginDTO{pluginDTO},
		Results: []model.PluginArchiveInstallItemDTO{
			{
				EntryPath: "b1/sample.js",
				ID:        pluginDTO.ID,
				OK:        true,
				Plugin:    &pluginDTO,
			},
			{
				EntryPath: "b1/broken.js",
				OK:        false,
				Error:     "插件脚本解析失败",
			},
		},
	}

	for name, val := range map[string]any{
		"AvailableSourceDTO":            src,
		"ProviderPluginDTO":             pluginDTO,
		"PluginInspectResultDTO":        inspectDTO,
		"PluginTestResultDTO":           testDTO,
		"PluginArchiveInspectResultDTO": archiveInspectDTO,
		"PluginArchiveInstallResultDTO": archiveInstallDTO,
	} {
		data, err := json.Marshal(val)
		if err != nil {
			t.Fatalf("marshal %s: %v", name, err)
		}
		if len(data) == 0 {
			t.Fatalf("empty JSON for %s", name)
		}
	}

	br := 320
	sz := int64(4096000)
	tr := model.ProviderTrack{
		PublicID:   "pub-1",
		Source:     "AudiusMusicClient",
		SourceID:   "au-1",
		RootSource: "AudiusMusicClient",
		Title:      "Open Horizon",
		Artist:     "CC Artist",
		Album:      "Commons",
		Duration:   180,
		Variants: []model.MediaVariant{
			{
				VariantID:    "var_1",
				Label:        "MP3 320k",
				Codec:        "mp3",
				Bitrate:      &br,
				Availability: "verified",
				Evidence:     "probed_audio",
				Protocol:     "HTTP",
				SizeBytes:    &sz,
			},
		},
	}
	pb := model.PlaybackDTOFromResolvedTrack(tr, "")
	dto := model.TrackDTOFromDomain(tr, pb)
	if dto.ID != "pub-1" || dto.Source != "AudiusMusicClient" || len(dto.Qualities) != 1 || dto.Playback.State != "ready" {
		t.Fatalf("unexpected TrackDTO: %+v", dto)
	}
}

func TestQualityAndTrackDTOSizePassThroughAndFormatSizeLabel(t *testing.T) {
	if got := model.FormatSizeLabel(nil); got != nil {
		t.Fatalf("expected nil for nil input, got %q", *got)
	}
	for _, n := range []int64{0, -100} {
		v := n
		if got := model.FormatSizeLabel(&v); got != nil {
			t.Fatalf("expected nil for %d, got %q", n, *got)
		}
	}
	formatCases := []struct {
		bytes int64
		want  string
	}{
		{512, "512 B"},
		{1024, "1 KB"},
		{512 * 1024, "512 KB"},
		{1024 * 1024, "1.0 MB"},
		{33973862, "32.4 MB"},
		{1024 * 1024 * 1024, "1.00 GB"},
		{1320702443, "1.23 GB"},
	}
	for _, tc := range formatCases {
		b := tc.bytes
		got := model.FormatSizeLabel(&b)
		if got == nil || *got != tc.want {
			t.Fatalf("FormatSizeLabel(%d) = %v, want %q", tc.bytes, got, tc.want)
		}
	}

	szFLAC := int64(33973862) // 32.4 MB
	szMP3 := int64(8388608)   // 8.0 MB
	br999 := 999
	br320 := 320
	qFLAC := model.QualityDTOFromVariant(model.MediaVariant{
		VariantID: "v_flac", Label: "无损音质", Codec: "flac", Bitrate: &br999,
		Lossless: true, Availability: "full", Evidence: "declared", SizeBytes: &szFLAC,
	})
	if qFLAC.SizeBytes == nil || *qFLAC.SizeBytes != szFLAC || qFLAC.SizeLabel == nil || *qFLAC.SizeLabel != "32.4 MB" {
		t.Fatalf("unexpected QualityDTO size fields: %+v", qFLAC)
	}
	qNoSize := model.QualityDTOFromVariant(model.MediaVariant{
		VariantID: "v_unknown", Label: "未知", Codec: "mp3", Availability: "candidate",
	})
	if qNoSize.SizeBytes != nil || qNoSize.SizeLabel != nil {
		t.Fatalf("expected nil size fields when unset, got %+v", qNoSize)
	}
	rawNoSize, err := json.Marshal(qNoSize)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(rawNoSize), "size_bytes") || strings.Contains(string(rawNoSize), "size_label") {
		t.Fatalf("expected omitempty for nil size fields, got %s", string(rawNoSize))
	}

	// TrackDTOFromDomain auto-fills SizeLabel from SizeBytes when SizeLabel is nil, and preserves variant sizes.
	tr := model.ProviderTrack{
		PublicID:  "pub-size-1",
		Source:    "AudiusMusicClient",
		SourceID:  "s-1",
		Title:     "Size Track",
		Artist:    "Artist",
		SizeBytes: &szFLAC,
		Variants: []model.MediaVariant{
			{VariantID: "v_flac", Label: "FLAC", Codec: "flac", Bitrate: &br999, Lossless: true, SizeBytes: &szFLAC},
			{VariantID: "v_mp3", Label: "MP3", Codec: "mp3", Bitrate: &br320, SizeBytes: &szMP3},
		},
	}
	dto := model.TrackDTOFromDomain(tr, model.PlaybackDTO{State: "ready"})
	if dto.SizeBytes == nil || *dto.SizeBytes != szFLAC || dto.SizeLabel == nil || *dto.SizeLabel != "32.4 MB" {
		t.Fatalf("expected TrackDTO auto SizeLabel 32.4 MB, got bytes=%v label=%v", dto.SizeBytes, dto.SizeLabel)
	}
	if len(dto.Qualities) != 2 || dto.Qualities[1].SizeBytes == nil || *dto.Qualities[1].SizeBytes != szMP3 || dto.Qualities[1].SizeLabel == nil || *dto.Qualities[1].SizeLabel != "8.0 MB" {
		t.Fatalf("expected quality 1 size 8.0 MB, got %+v", dto.Qualities)
	}
	rawTrack, err := json.Marshal(dto)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(rawTrack, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded["size_label"] != "32.4 MB" {
		t.Fatalf("expected JSON size_label=32.4 MB, got %v", decoded["size_label"])
	}
	quals, _ := decoded["qualities"].([]any)
	q0, _ := quals[0].(map[string]any)
	if q0["size_label"] != "32.4 MB" || int64(q0["size_bytes"].(float64)) != szFLAC {
		t.Fatalf("unexpected JSON quality 0 size fields: %+v", q0)
	}

	// Explicit Track.SizeLabel must not be overwritten.
	explicit := "约 33 MB"
	tr.SizeLabel = &explicit
	dtoExplicit := model.TrackDTOFromDomain(tr, model.PlaybackDTO{State: "ready"})
	if dtoExplicit.SizeLabel == nil || *dtoExplicit.SizeLabel != explicit {
		t.Fatalf("expected explicit SizeLabel %q preserved, got %v", explicit, dtoExplicit.SizeLabel)
	}
}
