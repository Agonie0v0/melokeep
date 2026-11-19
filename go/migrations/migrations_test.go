package migrations_test

import (
	"context"
	"database/sql"
	"fmt"
	"path/filepath"
	"strings"
	"testing"

	"github.com/agonie0v0/melokeep/migrations"
	_ "modernc.org/sqlite"
)

func TestLoadAndApplyChecksumVerification(t *testing.T) {
	list, err := migrations.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if len(list) == 0 {
		t.Fatal("expected at least one embedded migration")
	}

	dbPath := filepath.Join(t.TempDir(), "test.db")
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)&_pragma=busy_timeout(5000)&_pragma=synchronous(FULL)", filepath.ToSlash(dbPath))
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		t.Fatalf("sql.Open: %v", err)
	}
	defer db.Close()

	ctx := context.Background()
	if err := migrations.Apply(ctx, db); err != nil {
		t.Fatalf("first Apply: %v", err)
	}
	// Re-applying is idempotent.
	if err := migrations.Apply(ctx, db); err != nil {
		t.Fatalf("second Apply: %v", err)
	}

	// Tampering checksum in schema_migrations must fail Apply.
	if _, err := db.Exec(`UPDATE schema_migrations SET checksum = 'tampered' WHERE version = 1`); err != nil {
		t.Fatalf("tamper checksum: %v", err)
	}
	err = migrations.Apply(ctx, db)
	if err == nil || !strings.Contains(err.Error(), "checksum mismatch") {
		t.Fatalf("expected checksum mismatch error, got: %v", err)
	}
}
