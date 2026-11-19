package legacyimport_test

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/agonie0v0/melokeep/internal/legacyimport"
	"github.com/agonie0v0/melokeep/internal/store"
	_ "modernc.org/sqlite"
)

func findVenvPython(t *testing.T) string {
	t.Helper()
	var candidates []string
	if runtime.GOOS == "windows" {
		candidates = []string{
			filepath.Join("..", "..", "..", ".venv", "Scripts", "python.exe"),
			"python",
			"python3",
		}
	} else {
		candidates = []string{
			filepath.Join("..", "..", "..", ".venv", "bin", "python"),
			"python3",
			"python",
		}
	}
	for _, c := range candidates {
		if filepath.IsAbs(c) || strings.ContainsAny(c, `/\`) {
			if abs, err := filepath.Abs(c); err == nil {
				if _, err := os.Stat(abs); err == nil {
					return abs
				}
			}
		} else if p, err := exec.LookPath(c); err == nil {
			return p
		}
	}
	t.Skip("venv python not found")
	return ""
}

func TestImportLegacySnapshotDryRunIdempotencyPickleAndRedaction(t *testing.T) {
	pyBin := findVenvPython(t)
	ctx := context.Background()

	rootDir := t.TempDir()
	legacyDataDir := filepath.Join(rootDir, "data")
	engineDir := filepath.Join(legacyDataDir, "engine")
	musicRoot := filepath.Join(rootDir, "melokeep_outputs")
	if err := os.MkdirAll(engineDir, 0o755); err != nil {
		t.Fatalf("mkdir engine: %v", err)
	}
	if err := os.MkdirAll(musicRoot, 0o755); err != nil {
		t.Fatalf("mkdir musicRoot: %v", err)
	}

	// Write config.json and .env with synthetic secrets that MUST NEVER leak into reports.
	const secretCookie = "COOKIE_SECRET_DO_NOT_LEAK_12345"
	const secretJamendo = "JAMENDO_SECRET_DO_NOT_LEAK_67890"
	if err := os.WriteFile(filepath.Join(rootDir, "config.json"), []byte(`{
		"music_sources": ["AudiusMusicClient", "FMAMusicClient"],
		"global_client_cfg": {"search_size_per_source": 10},
		"cookies": {"AudiusMusicClient": "`+secretCookie+`"}
	}`), 0o600); err != nil {
		t.Fatalf("write config.json: %v", err)
	}
	if err := os.WriteFile(filepath.Join(rootDir, ".env"), []byte("MELOKEEP_JAMENDO_ID="+secretJamendo+"\n"), 0o600); err != nil {
		t.Fatalf("write .env: %v", err)
	}

	// Write 1 JSON .song, 1 valid legacy pickle .song, 1 disallowed pickle .song, and 1 corrupt .song.
	jsonSong := filepath.Join(engineDir, "song-json-1.song")
	if err := os.WriteFile(jsonSong, []byte(`{
		"_format": "melokeep.song.v1",
		"source": "AudiusMusicClient",
		"identifier": "au-1",
		"song_name": "JSON Song",
		"singers": "Artist J",
		"album": "Album J",
		"ext": "flac",
		"duration_s": 215.0,
		"bitrate": 960,
		"download_url": "https://secret.example.com/signed.flac?sig=TOPSECRET",
		"default_download_cookies": {"uin": "SECRET_UIN"}
	}`), 0o600); err != nil {
		t.Fatalf("write json song: %v", err)
	}

	pickleSong := filepath.Join(engineDir, "song-pickle-1.song")
	unsafePickleSong := filepath.Join(engineDir, "song-unsafe.song")
	corruptSong := filepath.Join(engineDir, "song-corrupt.song")
	if err := os.WriteFile(corruptSong, []byte("not-json-or-pickle"), 0o600); err != nil {
		t.Fatalf("write corrupt song: %v", err)
	}
	pyGen := `
import pickle, sys, types
mod = types.ModuleType('native_engine')
class SongInfo:
    pass
SongInfo.__module__ = 'native_engine'
mod.SongInfo = SongInfo
sys.modules['native_engine'] = mod
s = SongInfo()
s.source = 'FMAMusicClient'
s.identifier = 'fma-99'
s.song_name = '老缓存歌曲'
s.singers = '测试歌手'
s.album = '老专辑'
s.ext = 'mp3'
s.duration_s = 180.5
s.bitrate = 320
s.download_url = 'https://secret.example.com/fma.mp3?token=SECRET'
with open(sys.argv[1], 'wb') as f:
    pickle.dump(s, f)

class Evil:
    def __reduce__(self):
        import os
        return (os.system, ('echo pwned',))
with open(sys.argv[2], 'wb') as f:
    pickle.dump(Evil(), f)
`
	cmd := exec.CommandContext(ctx, pyBin, "-c", pyGen, pickleSong, unsafePickleSong)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("generate pickle fixtures: %v (%s)", err, string(out))
	}

	// Build legacy SQLite DB.
	legacyDBPath := filepath.Join(legacyDataDir, "melokeep.db")
	dsn := fmt.Sprintf("file:%s", filepath.ToSlash(legacyDBPath))
	ldb, err := sql.Open("sqlite", dsn)
	if err != nil {
		t.Fatalf("open legacy db: %v", err)
	}
	defer ldb.Close()
	_, err = ldb.Exec(`
		CREATE TABLE records(kind TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, updated INTEGER NOT NULL DEFAULT 1000, PRIMARY KEY(kind,id));
		CREATE TABLE library_assets(id TEXT PRIMARY KEY, path TEXT NOT NULL UNIQUE, locks TEXT NOT NULL DEFAULT '[]', source TEXT NOT NULL DEFAULT '{}');
		CREATE TABLE library_paths(token TEXT PRIMARY KEY, asset_id TEXT NOT NULL);
	`)
	if err != nil {
		t.Fatalf("create legacy tables: %v", err)
	}
	assetFile := filepath.Join(musicRoot, "Artist J", "JSON Song.flac")
	_, _ = ldb.Exec(`INSERT INTO library_assets(id,path,locks,source) VALUES('old-asset-1', ?, '["title"]', '{"source":"AudiusMusicClient"}')`, assetFile)
	_, _ = ldb.Exec(`INSERT INTO library_paths(token,asset_id) VALUES('legacy-tok-1', 'old-asset-1')`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('favorite','song-json-1','{"id":"song-json-1"}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('collection','col-1','{"id":"col-1","name":"我的歌单","source":"local","tracks":[{"id":"song-json-1"},{"id":"song-pickle-1"}]}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('download','dl-active-1','{"id":"dl-active-1","status":"downloading","bytes":1024,"track":{"id":"song-json-1"}}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('library_job','lj-1','{"id":"lj-1","action":"both","status":"organizing","total":1,"processed":0}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('library_operation','op-unfinished','{"id":"op-unfinished","asset_id":"old-asset-1","state":"publishing"}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('bot_link','link-1','{"id":"link-1","path":"`+filepath.ToSlash(assetFile)+`"}', 100)`)

	// Open fresh Go Store.
	goStore, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(t.TempDir())})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	defer goStore.Close()

	opts := legacyimport.ImportOptions{
		LegacyDataDir: legacyDataDir,
		FileRoot:      musicRoot,
		PythonBin:     pyBin,
		DryRun:        true,
	}
	dryReport, err := legacyimport.ImportLegacySnapshot(ctx, goStore, opts)
	if err != nil {
		t.Fatalf("DryRun import: %v", err)
	}
	if !dryReport.DryRun || !dryReport.BlocksWriteSwitch {
		t.Fatalf("expected DryRun=true and BlocksWriteSwitch=true, got: %+v", dryReport)
	}
	if len(dryReport.CorruptSongs) != 2 {
		t.Fatalf("expected 2 corrupt/disallowed songs, got: %+v", dryReport.CorruptSongs)
	}
	dryBytes, _ := json.Marshal(dryReport)
	if strings.Contains(string(dryBytes), secretCookie) || strings.Contains(string(dryBytes), secretJamendo) || strings.Contains(string(dryBytes), "TOPSECRET") {
		t.Fatal("secret leaked into dry-run import report!")
	}
	// Verify dry-run wrote 0 rows to Go DB.
	var recCount int
	_ = goStore.Reader().QueryRow(`SELECT COUNT(*) FROM legacy_records`).Scan(&recCount)
	if recCount != 0 {
		t.Fatalf("expected 0 legacy_records after dry-run, got %d", recCount)
	}

	// Actual import.
	opts.DryRun = false
	rep, err := legacyimport.ImportLegacySnapshot(ctx, goStore, opts)
	if err != nil {
		t.Fatalf("actual import: %v", err)
	}
	if rep.AlreadyImported || rep.Counts.Assets != 1 || rep.Counts.LegacyRecords != 6 {
		t.Fatalf("unexpected import counts: %+v", rep)
	}
	repBytes, _ := json.Marshal(rep)
	if strings.Contains(string(repBytes), secretCookie) || strings.Contains(string(repBytes), secretJamendo) {
		t.Fatal("secret leaked into import report!")
	}

	// Verify active download migrated as paused.
	dl, ok, err := goStore.GetDownload(ctx, "dl-active-1")
	if err != nil || !ok || dl.Status != "paused" {
		t.Fatalf("expected migrated active download to become paused, got %+v ok=%v err=%v", dl, ok, err)
	}

	// Verify legacy asset ID and alias preserved.
	alAsset, ok, err := goStore.ResolveAssetAlias(ctx, "legacy-tok-1")
	if err != nil || !ok || alAsset.ID != "old-asset-1" {
		t.Fatalf("expected legacy alias resolution to old-asset-1, got %+v ok=%v err=%v", alAsset, ok, err)
	}

	// Verify media_variants contain NO URLs/cookies/headers.
	var variantCount int
	_ = goStore.Reader().QueryRow(`SELECT COUNT(*) FROM media_variants`).Scan(&variantCount)
	if variantCount != 2 {
		t.Fatalf("expected 2 migrated variants from valid .song files, got %d", variantCount)
	}

	// Re-running import with same snapshot is idempotent.
	rep2, err := legacyimport.ImportLegacySnapshot(ctx, goStore, opts)
	if err != nil || !rep2.AlreadyImported {
		t.Fatalf("expected AlreadyImported=true on repeat run, got %+v err=%v", rep2, err)
	}

	// Integrity check detects the unfinished needs_attention legacy operation.
	integ, err := legacyimport.CheckIntegrity(ctx, goStore)
	if err != nil {
		t.Fatalf("CheckIntegrity: %v", err)
	}
	if integ.OK || len(integ.Issues) == 0 {
		t.Fatalf("expected CheckIntegrity to report needs_attention issue, got: %+v", integ)
	}
}
