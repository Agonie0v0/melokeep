package acceptance_integrity_test

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
	_ "modernc.org/sqlite"
)

func findOSPython(t *testing.T) string {
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
	t.Skip("no usable python interpreter for current OS")
	return ""
}

func TestAcceptanceLegacyMigrationAssetsCollectionsSecretsAndIntegrity(t *testing.T) {
	pyBin := findOSPython(t)
	ctx := context.Background()

	tmpRoot := t.TempDir()
	legacyDataDir := filepath.Join(tmpRoot, "data")
	engineDir := filepath.Join(legacyDataDir, "engine")
	musicRoot := filepath.Join(tmpRoot, "melokeep_outputs")
	if err := os.MkdirAll(engineDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(musicRoot, 0o755); err != nil {
		t.Fatal(err)
	}

	const secretCookie = "AUDIUS_SECRET_COOKIE_DO_NOT_LEAK"
	const secretJamendo = "JAMENDO_SECRET_DO_NOT_LEAK"
	const secretSignedURL = "https://signed.example.com/audio.flac?sig=DO_NOT_LEAK_URL"
	if err := os.WriteFile(filepath.Join(tmpRoot, "config.json"), []byte(`{
		"music_sources": ["AudiusMusicClient", "FMAMusicClient"],
		"cookies": {"AudiusMusicClient": "`+secretCookie+`"}
	}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(tmpRoot, ".env"), []byte("MELOKEEP_JAMENDO_ID="+secretJamendo+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	// JSON .song with nested episodes containing secret URLs/cookies.
	jsonSong := filepath.Join(engineDir, "song-json-1.song")
	if err := os.WriteFile(jsonSong, []byte(`{
		"_format": "melokeep.song.v1",
		"source": "AudiusMusicClient",
		"identifier": "au-1",
		"song_name": "Root Song",
		"singers": "Artist R",
		"album": "Album R",
		"ext": "flac",
		"duration_s": 200.0,
		"bitrate": 960,
		"download_url": "`+secretSignedURL+`",
		"default_download_cookies": {"uin": "SECRET_UIN"},
		"episodes": [{"title": "ep1", "download_url": "`+secretSignedURL+`"}]
	}`), 0o600); err != nil {
		t.Fatal(err)
	}

	// Pickle .song (valid + disallowed + corrupt).
	pickleSong := filepath.Join(engineDir, "song-pickle-1.song")
	unsafeSong := filepath.Join(engineDir, "song-unsafe.song")
	corruptSong := filepath.Join(engineDir, "song-corrupt.song")
	_ = os.WriteFile(corruptSong, []byte("corrupt-bytes"), 0o600)
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
s.identifier = 'fma-100'
s.song_name = '老曲目'
s.singers = '歌手'
s.album = '专辑'
s.ext = 'mp3'
s.duration_s = 123.0
s.bitrate = 320
s.download_url = 'https://signed.example.com/fma.mp3?sig=DO_NOT_LEAK_URL'
with open(sys.argv[1], 'wb') as f:
    pickle.dump(s, f)

class Evil:
    def __reduce__(self):
        import os
        return (os.system, ('echo pwned',))
with open(sys.argv[2], 'wb') as f:
    pickle.dump(Evil(), f)
`
	cmd := exec.CommandContext(ctx, pyBin, "-c", pyGen, pickleSong, unsafeSong)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("generate pickle fixtures: %v (%s)", err, string(out))
	}

	legacyDBPath := filepath.Join(legacyDataDir, "melokeep.db")
	ldb, err := sql.Open("sqlite", fmt.Sprintf("file:%s", filepath.ToSlash(legacyDBPath)))
	if err != nil {
		t.Fatal(err)
	}
	defer ldb.Close()
	_, err = ldb.Exec(`
		CREATE TABLE records(kind TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, updated INTEGER NOT NULL DEFAULT 1000, PRIMARY KEY(kind,id));
		CREATE TABLE library_assets(id TEXT PRIMARY KEY, path TEXT NOT NULL UNIQUE, locks TEXT NOT NULL DEFAULT '[]', source TEXT NOT NULL DEFAULT '{}');
		CREATE TABLE library_paths(token TEXT PRIMARY KEY, asset_id TEXT NOT NULL);
	`)
	if err != nil {
		t.Fatal(err)
	}

	// Root-level asset (folder should normalize to "", not ".") and nested mixed-case asset (path_key lowercase under insensitive root).
	rootAudio := filepath.Join(musicRoot, "RootSong.flac")
	nestedAudio := filepath.Join(musicRoot, "Artist R", "Nested Song.flac")
	_, _ = ldb.Exec(`INSERT INTO library_assets(id,path,locks,source) VALUES('asset-root', ?, '[]', '{}')`, rootAudio)
	_, _ = ldb.Exec(`INSERT INTO library_assets(id,path,locks,source) VALUES('asset-nested', ?, '["title"]', '{"source":"AudiusMusicClient"}')`, nestedAudio)
	_, _ = ldb.Exec(`INSERT INTO library_paths(token,asset_id) VALUES('tok-root', 'asset-root')`)
	_, _ = ldb.Exec(`INSERT INTO library_paths(token,asset_id) VALUES('tok-nested', 'asset-nested')`)

	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('favorite','song-json-1','{"id":"song-json-1"}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('collection','col-mix','{"id":"col-mix","name":"混合歌单","source":"local","tracks":[{"id":"song-json-1"},{"id":"asset:asset-root"},{"id":"song-pickle-1"}]}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('download','dl-1','{"id":"dl-1","status":"downloading","bytes":512,"track":{"id":"song-json-1"}}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('download','dl-done','{"id":"dl-done","status":"complete","bytes":2048,"track":{"id":"song-pickle-1"}}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('library_job','lj-1','{"id":"lj-1","action":"both","status":"organizing","total":2,"processed":1}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('library_operation','op-na','{"id":"op-na","asset_id":"asset-nested","state":"needs_attention"}', 100)`)
	_, _ = ldb.Exec(`INSERT INTO records(kind,id,body,updated) VALUES('bot_link','link-1','{"id":"link-1","path":"`+filepath.ToSlash(nestedAudio)+`"}', 100)`)

	st := openIntegrityStore(t)
	opts := legacyimport.ImportOptions{
		LegacyDataDir: legacyDataDir,
		FileRoot:      musicRoot,
		PythonBin:     pyBin,
		DryRun:        true,
	}
	dryRep, err := legacyimport.ImportLegacySnapshot(ctx, st, opts)
	if err != nil {
		t.Fatalf("DryRun: %v", err)
	}
	if !dryRep.DryRun || !dryRep.BlocksWriteSwitch {
		t.Fatalf("expected DryRun=true and BlocksWriteSwitch=true when op-na is needs_attention, got %+v", dryRep)
	}
	if len(dryRep.CorruptSongs) != 2 {
		t.Fatalf("expected 2 corrupt/disallowed songs, got %+v", dryRep.CorruptSongs)
	}
	dryRaw, _ := json.Marshal(dryRep)
	for _, secret := range []string{secretCookie, secretJamendo, secretSignedURL, "SECRET_UIN"} {
		if strings.Contains(string(dryRaw), secret) {
			t.Fatalf("secret %q leaked into dry-run report", secret)
		}
	}

	opts.DryRun = false
	rep, err := legacyimport.ImportLegacySnapshot(ctx, st, opts)
	if err != nil {
		t.Fatalf("ImportLegacySnapshot: %v", err)
	}
	if rep.AlreadyImported || !rep.BlocksWriteSwitch {
		t.Fatalf("unexpected import report: %+v", rep)
	}
	repRaw, _ := json.Marshal(rep)
	for _, secret := range []string{secretCookie, secretJamendo, secretSignedURL, "SECRET_UIN"} {
		if strings.Contains(string(repRaw), secret) {
			t.Fatalf("secret %q leaked into import report", secret)
		}
	}

	// Verify normalized folder and path_key for imported assets.
	rootAsset, ok, err := st.GetAsset(ctx, "asset-root")
	if err != nil || !ok {
		t.Fatalf("GetAsset asset-root: ok=%v err=%v", ok, err)
	}
	if rootAsset.Folder != "" {
		t.Fatalf("root-level asset folder must be empty string \"\", got %q", rootAsset.Folder)
	}
	nestedAsset, ok, err := st.GetAsset(ctx, "asset-nested")
	if err != nil || !ok {
		t.Fatalf("GetAsset asset-nested: ok=%v err=%v", ok, err)
	}
	if nestedAsset.PathKey != "artist r/nested song.flac" {
		t.Fatalf("expected lowercased path_key under insensitive legacy root, got %q", nestedAsset.PathKey)
	}

	// Verify collection preserves all 3 items in order including asset:asset-root!
	_, items, ok, err := st.GetCollection(ctx, "col-mix")
	if err != nil || !ok || len(items) != 3 {
		t.Fatalf("expected 3 collection items including asset ref, got ok=%v len=%d items=%+v err=%v", ok, len(items), items, err)
	}
	if items[0].PublicID != "song-json-1" || items[1].PublicID != "asset:asset-root" || items[2].PublicID != "song-pickle-1" {
		t.Fatalf("collection order/content mismatch: %+v", items)
	}

	// Verify active download -> paused, completed download -> complete, active library_job -> paused.
	dl1, _, _ := st.GetDownload(ctx, "dl-1")
	dlDone, _, _ := st.GetDownload(ctx, "dl-done")
	lj1, _, _, _ := st.GetLibraryJob(ctx, "lj-1")
	if dl1.Status != "paused" || dlDone.Status != "complete" || lj1.Status != "paused" {
		t.Fatalf("unexpected migrated states: dl1=%s dlDone=%s lj1=%s", dl1.Status, dlDone.Status, lj1.Status)
	}

	// Repeat import is idempotent.
	repRepeat, err := legacyimport.ImportLegacySnapshot(ctx, st, opts)
	if err != nil || !repRepeat.AlreadyImported {
		t.Fatalf("expected AlreadyImported=true, got %+v err=%v", repRepeat, err)
	}

	// CheckIntegrity detects needs_attention_operation and orphan public_refs.
	integ1, err := legacyimport.CheckIntegrity(ctx, st)
	if err != nil || integ1.OK {
		t.Fatalf("expected CheckIntegrity to flag op-na, got %+v err=%v", integ1, err)
	}
	// Inject an orphan public_ref on a fresh store and verify CheckIntegrity catches it.
	st2 := openIntegrityStore(t)
	_, err = st2.Writer().Exec(`INSERT INTO public_refs(public_id, kind, target_id, source, source_id, quality_hint, revision, created_at) VALUES('orphan-1', 'asset', 'missing-asset', 'local', 'missing-asset', '', 1, 1)`)
	if err != nil {
		t.Fatal(err)
	}
	integ2, err := legacyimport.CheckIntegrity(ctx, st2)
	if err != nil || integ2.OK || len(integ2.Issues) != 1 || integ2.Issues[0].Category != "orphan_public_ref" {
		t.Fatalf("expected CheckIntegrity to report orphan_public_ref, got %+v err=%v", integ2, err)
	}
}
