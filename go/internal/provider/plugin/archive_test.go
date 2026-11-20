package plugin_test

import (
	"archive/zip"
	"bytes"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider/plugin"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type zipEntrySpec struct {
	name    string
	content []byte
	isDir   bool
}

func buildTestZip(t *testing.T, entries []zipEntrySpec) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, e := range entries {
		hdr := &zip.FileHeader{
			Name:   e.name,
			Method: zip.Deflate,
		}
		if e.isDir {
			hdr.SetMode(os.ModeDir | 0o755)
		} else {
			hdr.SetMode(0o644)
		}
		w, err := zw.CreateHeader(hdr)
		if err != nil {
			t.Fatalf("zip create %q: %v", e.name, err)
		}
		if !e.isDir && len(e.content) > 0 {
			if _, err := w.Write(e.content); err != nil {
				t.Fatalf("zip write %q: %v", e.name, err)
			}
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("zip close: %v", err)
	}
	return buf.Bytes()
}

func makeArchivePluginCode(id, name, version string) []byte {
	return []byte(fmt.Sprintf(`
module.exports = {
	manifest: {
		apiVersion: 1,
		id: %q,
		name: %q,
		version: %q,
		description: "Archive test plugin",
		category: "测试分类",
		capabilities: { search: true, playlist: false, episodes: false, lyrics: true, metadata: false },
		settings: [
			{ key: "api_key", label: "API Key", type: "secret" }
		]
	},
	search(ctx, opts) {
		return [{ sourceId: %q + "-1", title: "Track from " + %q, artist: "Tester", duration: 180 }];
	},
	resolve(ctx, track) {
		return [{ url: "https://cdn.example.org/" + track.sourceId + ".mp3", codec: "mp3", bitrate: 320 }];
	},
	lyrics(ctx, track) {
		return "[00:00.00]" + track.title;
	}
};
`, id, name, version, id, id))
}

func TestArchiveInspectFilteringSortingAndNormalization(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	pool := transport.NewPool()
	defer pool.CloseAll()
	reg := newTestRegistry(t, pool)
	mgr := plugin.NewManager(st, pool, reg, filepath.Join(t.TempDir(), "plugins"), nil)

	archiveBytes := buildTestZip(t, []zipEntrySpec{
		{name: "b2/", isDir: true},
		{name: "b2/zeta.js", content: makeArchivePluginCode("zip-zeta", "Zeta", "1.0.0")},
		{name: "b1\\alpha.js", content: makeArchivePluginCode("zip-alpha", "Alpha", "1.0.0")},
		{name: "./root-plugin.JS", content: makeArchivePluginCode("zip-root", "Root", "1.0.0")},
		{name: "__MACOSX/b1/._alpha.js", content: []byte("mac resource fork")},
		{name: "__MACOSX/ignore.js", content: makeArchivePluginCode("zip-mac", "Mac", "1.0.0")},
		{name: "b1/._hidden.js", content: makeArchivePluginCode("zip-dot", "Dot", "1.0.0")},
		{name: ".hidden/nested.js", content: makeArchivePluginCode("zip-hidden-dir", "HiddenDir", "1.0.0")},
		{name: ".DS_Store", content: []byte("junk")},
		{name: "README.md", content: []byte("# docs")},
		{name: "scripts/build.mjs", content: []byte("export default 1;")},
	})

	res, err := mgr.InspectArchive(ctx, archiveBytes)
	if err != nil {
		t.Fatalf("InspectArchive failed: %v", err)
	}
	if res.Total != 3 || res.ValidCount != 3 || res.InvalidCount != 0 || len(res.Entries) != 3 {
		t.Fatalf("unexpected counts: %+v", res)
	}

	wantPaths := []string{"b1/alpha.js", "b2/zeta.js", "root-plugin.JS"}
	wantIDs := []string{"zip-alpha", "zip-zeta", "zip-root"}
	for i, wantPath := range wantPaths {
		got := res.Entries[i]
		if !got.OK || got.EntryPath != wantPath || got.Manifest == nil || got.Manifest.ID != wantIDs[i] || len(got.SHA256) != 64 {
			t.Fatalf("entry[%d] mismatch: got=%+v wantPath=%s wantID=%s", i, got, wantPath, wantIDs[i])
		}
		if len(got.Permissions) == 0 || len(got.Warnings) == 0 {
			t.Fatalf("entry[%d] missing permissions/warnings: %+v", i, got)
		}
	}
}

func TestArchiveInspectErrorsInvalidEmptyCorruptTraversalAndLimits(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	pool := transport.NewPool()
	defer pool.CloseAll()
	reg := newTestRegistry(t, pool)
	mgr := plugin.NewManager(st, pool, reg, filepath.Join(t.TempDir(), "plugins"), nil)

	assertBadInput := func(name string, data []byte, wantSubstr string) {
		t.Helper()
		_, err := mgr.InspectArchive(ctx, data)
		if err == nil {
			t.Fatalf("%s: expected error, got nil", name)
		}
		de, ok := model.AsDomainError(err)
		if !ok || de.Code != model.ErrInvalidInput || de.HTTPStatus() != 400 {
			t.Fatalf("%s: expected 400 ErrInvalidInput, got %v", name, err)
		}
		if wantSubstr != "" && !strings.Contains(de.Detail, wantSubstr) {
			t.Fatalf("%s: expected detail containing %q, got %q", name, wantSubstr, de.Detail)
		}
	}

	// Empty bytes & corrupt bytes.
	assertBadInput("empty bytes", nil, "不能为空")
	assertBadInput("corrupt zip", []byte("not a valid zip payload"), "无效的 ZIP")

	// Empty ZIP & ZIP without .js files.
	assertBadInput("empty zip", buildTestZip(t, nil), "未找到任何 .js")
	assertBadInput("no js zip", buildTestZip(t, []zipEntrySpec{
		{name: "README.md", content: []byte("hello")},
		{name: "__MACOSX/._a.js", content: []byte("mac")},
		{name: ".hidden.js", content: makeArchivePluginCode("hid", "Hid", "1.0.0")},
		{name: "helper.mjs", content: []byte("export {};")},
	}), "未找到任何 .js")

	// Path traversal and absolute paths.
	for _, badPath := range []string{
		"../evil.js",
		"b1/../../evil.js",
		"a/../b.js",
		"/etc/evil.js",
		"\\windows\\evil.js",
		"C:\\evil.js",
		"../README.md",
	} {
		z := buildTestZip(t, []zipEntrySpec{
			{name: badPath, content: makeArchivePluginCode("evil-src", "Evil", "1.0.0")},
		})
		assertBadInput("traversal "+badPath, z, "非法")
	}

	// Archive exceeding MaxArchiveBytes (16 MiB).
	assertBadInput("archive > 16MiB", make([]byte, plugin.MaxArchiveBytes+1), "16 MiB")

	// Exceeding MaxArchivePlugins (101 .js entries).
	manyEntries := make([]zipEntrySpec, 0, plugin.MaxArchivePlugins+1)
	for i := 0; i <= plugin.MaxArchivePlugins; i++ {
		id := fmt.Sprintf("plug-%03d", i)
		manyEntries = append(manyEntries, zipEntrySpec{
			name:    id + ".js",
			content: makeArchivePluginCode(id, id, "1.0.0"),
		})
	}
	assertBadInput("too many plugins", buildTestZip(t, manyEntries), "超过 100")

	// Multi-file Zip Bomb: 66 * 500 KiB = ~33 MiB (> 32 MiB MaxArchiveUncompressedBytes), compressed tiny.
	pad500K := bytes.Repeat([]byte("a"), 500<<10)
	bombEntries := make([]zipEntrySpec, 0, 66)
	for i := 0; i < 66; i++ {
		bombEntries = append(bombEntries, zipEntrySpec{
			name:    fmt.Sprintf("bomb-%02d.js", i),
			content: pad500K,
		})
	}
	bombZip := buildTestZip(t, bombEntries)
	if len(bombZip) >= plugin.MaxArchiveBytes {
		t.Fatalf("expected compressed zip bomb to stay below 16 MiB, got %d bytes", len(bombZip))
	}
	assertBadInput("multi-file zip bomb", bombZip, "32 MiB")

	// Single-file Zip Bomb: 33 MiB of zeros in one .js entry.
	singleBomb := buildTestZip(t, []zipEntrySpec{
		{name: "huge-bomb.js", content: make([]byte, plugin.MaxArchiveUncompressedBytes+1024)},
	})
	assertBadInput("single-file zip bomb", singleBomb, "32 MiB")
}

func TestArchiveInspectPartialErrorsConflictDuplicateAndOversizedEntry(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	pool := transport.NewPool()
	defer pool.CloseAll()
	reg := newTestRegistry(t, pool)
	mgr := plugin.NewManager(st, pool, reg, filepath.Join(t.TempDir(), "plugins"), nil)

	oversized := append(makeArchivePluginCode("too-big", "TooBig", "1.0.0"), bytes.Repeat([]byte("/"), plugin.MaxSourceBytes)...)

	archiveBytes := buildTestZip(t, []zipEntrySpec{
		{name: "01-valid.js", content: makeArchivePluginCode("good-one", "Good One", "1.0.0")},
		{name: "02-syntax-err.js", content: []byte("module.exports = {{{ broken syntax")},
		{name: "03-builtin-conflict.js", content: makeArchivePluginCode("audius", "Audius Clash", "1.0.0")},
		{name: "04-dup-second.js", content: makeArchivePluginCode("good-one", "Good One Dup", "1.0.1")},
		{name: "05-oversized.js", content: oversized},
		{name: "06-valid-two.js", content: makeArchivePluginCode("good-two", "Good Two", "1.0.0")},
	})

	res, err := mgr.InspectArchive(ctx, archiveBytes)
	if err != nil {
		t.Fatalf("InspectArchive should isolate per-entry errors, got err=%v", err)
	}
	if res.Total != 6 || res.ValidCount != 2 || res.InvalidCount != 4 || len(res.Entries) != 6 {
		t.Fatalf("unexpected summary counts: %+v", res)
	}

	byPath := make(map[string]model.PluginArchiveEntryInspectDTO, len(res.Entries))
	for _, e := range res.Entries {
		byPath[e.EntryPath] = e
	}

	if !byPath["01-valid.js"].OK || !byPath["06-valid-two.js"].OK {
		t.Fatalf("expected valid entries OK: %+v", byPath)
	}
	if byPath["02-syntax-err.js"].OK || byPath["02-syntax-err.js"].Error == "" {
		t.Fatalf("expected syntax error entry failed: %+v", byPath["02-syntax-err.js"])
	}
	if byPath["03-builtin-conflict.js"].OK || !strings.Contains(byPath["03-builtin-conflict.js"].Error, "内置音源冲突") {
		t.Fatalf("expected builtin conflict error: %+v", byPath["03-builtin-conflict.js"])
	}
	if byPath["04-dup-second.js"].OK || !strings.Contains(byPath["04-dup-second.js"].Error, "重复插件 ID") {
		t.Fatalf("expected duplicate ID error: %+v", byPath["04-dup-second.js"])
	}
	if byPath["05-oversized.js"].OK || !strings.Contains(byPath["05-oversized.js"].Error, "512 KiB") {
		t.Fatalf("expected 512 KiB error: %+v", byPath["05-oversized.js"])
	}
}

func TestArchiveInstallAllSelectedDisabledOverwriteAndHotRegister(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	pool := transport.NewPool()
	defer pool.CloseAll()
	reg := newTestRegistry(t, pool)
	pluginDir := filepath.Join(t.TempDir(), "plugins")
	mgr := plugin.NewManager(st, pool, reg, pluginDir, nil)

	archiveBytes := buildTestZip(t, []zipEntrySpec{
		{name: "b1/alpha.js", content: makeArchivePluginCode("zip-alpha", "Alpha", "1.0.0")},
		{name: "b1/beta.js", content: makeArchivePluginCode("zip-beta", "Beta", "1.0.0")},
		{name: "b1/broken.js", content: []byte("not valid js (((")},
		{name: "b1/clash.js", content: makeArchivePluginCode("itunes", "Clash", "1.0.0")},
		{name: "b2/alpha-dup.js", content: makeArchivePluginCode("zip-alpha", "Alpha Dup", "1.0.1")},
	})

	// 1. SelectedIDs installs ONLY selected valid entries (unselected & invalid skipped, not counted in Total).
	falseVal := false
	selRes, err := mgr.InstallArchive(ctx, plugin.InstallArchiveRequest{
		ArchiveBytes: archiveBytes,
		Enabled:      &falseVal,
		SelectedIDs:  []string{"zip-alpha"},
	})
	if err != nil {
		t.Fatalf("InstallArchive selected failed: %v", err)
	}
	if selRes.Total != 1 || selRes.InstalledCount != 1 || selRes.FailedCount != 0 || len(selRes.Installed) != 1 || len(selRes.Results) != 1 {
		t.Fatalf("unexpected selected install result: %+v", selRes)
	}
	if selRes.Installed[0].ID != "zip-alpha" || selRes.Installed[0].Enabled {
		t.Fatalf("expected zip-alpha installed disabled, got %+v", selRes.Installed[0])
	}
	if _, ok := reg.Get("zip-alpha"); ok {
		t.Fatal("disabled plugin should not be hot-registered in Registry")
	}

	// Store a secret setting on zip-alpha to verify subsequent overwrite preserves secrets.
	if _, err := mgr.Update(ctx, "zip-alpha", plugin.UpdateRequest{
		Settings: map[string]string{"api_key": "keep-my-secret"},
	}); err != nil {
		t.Fatalf("Update secret failed: %v", err)
	}

	// 2. InstallArchive with empty SelectedIDs and enabled=true installs all valid entries,
	// records invalid entries as failed, preserves existing secrets, and hot-registers both plugins.
	trueVal := true
	allRes, err := mgr.InstallArchive(ctx, plugin.InstallArchiveRequest{
		ArchiveBytes: archiveBytes,
		Enabled:      &trueVal,
	})
	if err != nil {
		t.Fatalf("InstallArchive all failed: %v", err)
	}
	if allRes.Total != 5 || allRes.InstalledCount != 2 || allRes.FailedCount != 3 || len(allRes.Installed) != 2 || len(allRes.Results) != 5 {
		t.Fatalf("unexpected install-all result: %+v", allRes)
	}

	for _, id := range []string{"zip-alpha", "zip-beta"} {
		prov, ok := reg.Get(id)
		if !ok {
			t.Fatalf("expected %s hot-registered in Registry", id)
		}
		var tracks []model.ProviderTrack
		if _, err := prov.Search(ctx, model.SearchOptions{Keyword: "test"}, func(tr model.ProviderTrack) error {
			tracks = append(tracks, tr)
			return nil
		}); err != nil || len(tracks) != 1 {
			t.Fatalf("hot-registered plugin %s search failed: tracks=%+v err=%v", id, tracks, err)
		}
		if _, statErr := os.Stat(filepath.Join(pluginDir, id+".js")); statErr != nil {
			t.Fatalf("expected %s.js written to disk: %v", id, statErr)
		}
	}

	// Verify zip-alpha secret setting was preserved across ZIP re-install.
	listed, err := mgr.List(ctx)
	if err != nil || len(listed) != 2 {
		t.Fatalf("List failed: len=%d err=%v", len(listed), err)
	}
	for _, item := range listed {
		if item.ID == "zip-alpha" && item.Settings["api_key"] != config.HiddenPlaceholder {
			t.Fatalf("expected zip-alpha secret preserved and redacted, got %+v", item.Settings)
		}
	}

	// 3. Invalid allowed_origins fails fast with 400 before modifying plugins.
	if _, err := mgr.InstallArchive(ctx, plugin.InstallArchiveRequest{
		ArchiveBytes:   archiveBytes,
		AllowedOrigins: []string{"ftp://bad.example.org"},
	}); err == nil {
		t.Fatal("expected invalid allowed_origins to fail")
	}
}
