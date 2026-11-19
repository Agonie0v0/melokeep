package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	_ "modernc.org/sqlite"
)

func TestCLIMigrationAndHelp(t *testing.T) {
	var out, errBuf bytes.Buffer
	if code := run([]string{"--help"}, nil, &out, &errBuf); code != 0 {
		t.Fatalf("--help exit=%d stderr=%s", code, errBuf.String())
	}
	if !strings.Contains(errBuf.String(), "melokeep migrate") {
		t.Fatalf("expected usage to mention migrate, got: %s", errBuf.String())
	}

	out.Reset()
	errBuf.Reset()
	if code := run([]string{"migrate", "--help"}, nil, &out, &errBuf); code != 0 {
		t.Fatalf("migrate --help exit=%d stderr=%s", code, errBuf.String())
	}
	if !strings.Contains(errBuf.String(), "-dry-run") {
		t.Fatalf("expected migrate usage to mention -dry-run, got: %s", errBuf.String())
	}

	tmp := t.TempDir()
	srcData := filepath.Join(tmp, "legacy_data")
	dstData := filepath.Join(tmp, "target_data")
	musicDir := filepath.Join(tmp, "music")
	if err := os.MkdirAll(srcData, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(musicDir, 0o755); err != nil {
		t.Fatal(err)
	}
	legacyDB := filepath.Join(srcData, "melokeep.db")
	db, err := sql.Open("sqlite", legacyDB)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`
		CREATE TABLE records(kind TEXT, id TEXT, body TEXT, updated INTEGER);
		INSERT INTO records(kind, id, body, updated) VALUES('track', 'trk1', '{"title":"Morning Breeze","artist":"Open Horizon","source":"AudiusMusicClient","source_id":"au-101"}', 1000);
		INSERT INTO records(kind, id, body, updated) VALUES('collection', 'col1', '{"name":"最爱","tracks":["trk1"]}', 1001);
	`)
	_ = db.Close()
	if err != nil {
		t.Fatal(err)
	}

	cfgPath := filepath.Join(tmp, "config.json")
	if err := os.WriteFile(cfgPath, []byte(`{"sources":["AudiusMusicClient"],"proxy_url":"http://user:secretpass@127.0.0.1:7890"}`), 0o600); err != nil {
		t.Fatal(err)
	}

	reportPath := filepath.Join(tmp, "reports", "migrate.json")
	out.Reset()
	errBuf.Reset()
	code := run([]string{
		"migrate",
		"--source-data", srcData,
		"--source-config", cfgPath,
		"--target-data", dstData,
		"--music-root", musicDir,
		"--dry-run",
		"--report-out", reportPath,
	}, nil, &out, &errBuf)
	if code != 0 {
		t.Fatalf("dry-run migrate exit=%d stderr=%s stdout=%s", code, errBuf.String(), out.String())
	}
	if strings.Contains(out.String(), "secretpass") {
		t.Fatalf("migrate output leaked secret password: %s", out.String())
	}
	var rep migrateOutput
	if err := json.Unmarshal(out.Bytes(), &rep); err != nil {
		t.Fatalf("decode dry-run report: %v\nraw=%s", err, out.String())
	}
	if !rep.Import.DryRun || rep.Import.Counts.Tracks != 1 || rep.Import.Counts.Collections != 1 {
		t.Fatalf("unexpected dry-run report: %+v", rep.Import)
	}

	// Real import should succeed and pass integrity check.
	out.Reset()
	errBuf.Reset()
	code = run([]string{
		"migrate",
		"--source-data", srcData,
		"--source-config", cfgPath,
		"--target-data", dstData,
		"--music-root", musicDir,
		"--report-out", reportPath,
	}, nil, &out, &errBuf)
	if code != 0 {
		t.Fatalf("real migrate exit=%d stderr=%s stdout=%s", code, errBuf.String(), out.String())
	}
	if err := json.Unmarshal(out.Bytes(), &rep); err != nil {
		t.Fatalf("decode real report: %v", err)
	}
	if rep.Import.DryRun || rep.Integrity == nil || !rep.Integrity.OK {
		t.Fatalf("expected clean integrity report, got: %+v", rep)
	}
}
