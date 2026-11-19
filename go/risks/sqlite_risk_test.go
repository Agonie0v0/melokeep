package risks

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func openRiskDB(t *testing.T, dbPath string) (writer *sql.DB, reader *sql.DB) {
	t.Helper()
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)&_pragma=busy_timeout(5000)&_pragma=synchronous(FULL)", filepath.ToSlash(dbPath))
	w, err := sql.Open("sqlite", dsn)
	if err != nil {
		t.Fatalf("open writer: %v", err)
	}
	w.SetMaxOpenConns(1)
	w.SetMaxIdleConns(1)

	r, err := sql.Open("sqlite", dsn+"&_pragma=query_only(ON)")
	if err != nil {
		_ = w.Close()
		t.Fatalf("open reader: %v", err)
	}
	r.SetMaxOpenConns(4)
	r.SetMaxIdleConns(2)

	t.Cleanup(func() {
		_ = r.Close()
		_ = w.Close()
	})
	return w, r
}

func TestSQLitePragmasCASContentionAnd100kPagination(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "melokeep-risk.db")
	writer, reader := openRiskDB(t, dbPath)

	var journalMode string
	var fkEnabled, syncMode int
	if err := writer.QueryRow("PRAGMA journal_mode").Scan(&journalMode); err != nil {
		t.Fatalf("pragma journal_mode: %v", err)
	}
	if err := writer.QueryRow("PRAGMA foreign_keys").Scan(&fkEnabled); err != nil {
		t.Fatalf("pragma foreign_keys: %v", err)
	}
	if err := writer.QueryRow("PRAGMA synchronous").Scan(&syncMode); err != nil {
		t.Fatalf("pragma synchronous: %v", err)
	}
	if journalMode != "wal" || fkEnabled != 1 || syncMode != 2 {
		t.Fatalf("unexpected pragmas: journal=%s fk=%d sync=%d", journalMode, fkEnabled, syncMode)
	}

	schema := `
	CREATE TABLE roots (
		id TEXT PRIMARY KEY,
		canonical_path TEXT NOT NULL UNIQUE
	);
	CREATE TABLE assets (
		id TEXT PRIMARY KEY,
		root_id TEXT NOT NULL REFERENCES roots(id) ON DELETE RESTRICT,
		rel_path TEXT NOT NULL,
		path_key TEXT NOT NULL,
		status TEXT NOT NULL CHECK(status IN ('active','missing','deleted')),
		size INTEGER NOT NULL,
		mtime_ns INTEGER NOT NULL,
		revision INTEGER NOT NULL DEFAULT 1,
		title TEXT NOT NULL,
		artist TEXT NOT NULL,
		album TEXT NOT NULL,
		duration_ms INTEGER NOT NULL,
		UNIQUE(root_id, path_key)
	);
	CREATE INDEX assets_folder ON assets(root_id, status, path_key, id);
	CREATE INDEX assets_artist ON assets(root_id, status, artist, path_key, id);
	`
	if _, err := writer.Exec(schema); err != nil {
		t.Fatalf("create schema: %v", err)
	}
	if _, err := writer.Exec(`INSERT INTO roots(id, canonical_path) VALUES('r1', '/music')`); err != nil {
		t.Fatalf("insert root: %v", err)
	}

	// Verify FK enforcement.
	if _, err := writer.Exec(`INSERT INTO assets(id,root_id,rel_path,path_key,status,size,mtime_ns,revision,title,artist,album,duration_ms) VALUES('bad','missing-root','a.flac','a.flac','active',1,1,1,'t','a','al',1000)`); err == nil {
		t.Fatal("expected foreign key error for missing root")
	}

	// Seed 100,000 rows in batched transactions (2,500 rows/tx).
	const totalRows = 100000
	const batchSize = 2500
	insertStart := time.Now()
	for base := 0; base < totalRows; base += batchSize {
		tx, err := writer.Begin()
		if err != nil {
			t.Fatalf("begin tx: %v", err)
		}
		stmt, err := tx.Prepare(`INSERT INTO assets(id,root_id,rel_path,path_key,status,size,mtime_ns,revision,title,artist,album,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`)
		if err != nil {
			_ = tx.Rollback()
			t.Fatalf("prepare: %v", err)
		}
		for i := base; i < base+batchSize; i++ {
			id := fmt.Sprintf("a-%06d", i)
			artist := fmt.Sprintf("Artist-%04d", i%500)
			album := fmt.Sprintf("Album-%04d", i%2000)
			rel := fmt.Sprintf("%s/%s/%06d.flac", artist, album, i)
			if _, err := stmt.Exec(id, "r1", rel, rel, "active", 1024*1024+int64(i), 1700000000000000000+int64(i), 1, fmt.Sprintf("Song %d", i), artist, album, 210000); err != nil {
				_ = stmt.Close()
				_ = tx.Rollback()
				t.Fatalf("exec insert: %v", err)
			}
		}
		_ = stmt.Close()
		if err := tx.Commit(); err != nil {
			t.Fatalf("commit batch: %v", err)
		}
	}
	insertElapsed := time.Since(insertStart)

	// Optimistic CAS test.
	res, err := writer.Exec(`UPDATE assets SET title=?, revision=revision+1 WHERE id=? AND revision=?`, "Updated", "a-000001", 1)
	if err != nil {
		t.Fatalf("cas update 1: %v", err)
	}
	if n, _ := res.RowsAffected(); n != 1 {
		t.Fatalf("expected 1 affected row, got %d", n)
	}
	res, err = writer.Exec(`UPDATE assets SET title=?, revision=revision+1 WHERE id=? AND revision=?`, "Stale", "a-000001", 1)
	if err != nil {
		t.Fatalf("cas stale update: %v", err)
	}
	if n, _ := res.RowsAffected(); n != 0 {
		t.Fatalf("expected 0 affected rows for stale revision, got %d", n)
	}

	// Contention test: 1 writer doing 100 updates while 4 concurrent readers paginate.
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	var wg sync.WaitGroup
	errCh := make(chan error, 16)

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 100; i++ {
			if _, err := writer.ExecContext(ctx, `UPDATE assets SET size=size+1, revision=revision+1 WHERE id=?`, fmt.Sprintf("a-%06d", i)); err != nil {
				errCh <- fmt.Errorf("writer iter %d: %w", i, err)
				return
			}
		}
	}()
	for rIdx := 0; rIdx < 4; rIdx++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for j := 0; j < 25; j++ {
				rows, err := reader.QueryContext(ctx, `SELECT id, path_key, title FROM assets WHERE root_id='r1' AND status='active' AND (path_key, id) > (?, ?) ORDER BY path_key, id LIMIT 100`,
					fmt.Sprintf("Artist-%04d/", (j*13+id)%450), "")
				if err != nil {
					errCh <- fmt.Errorf("reader %d iter %d: %w", id, j, err)
					return
				}
				count := 0
				for rows.Next() {
					var a, b, c string
					if err := rows.Scan(&a, &b, &c); err != nil {
						_ = rows.Close()
						errCh <- err
						return
					}
					count++
				}
				_ = rows.Close()
				if count != 100 {
					errCh <- fmt.Errorf("reader %d expected 100 rows, got %d", id, count)
					return
				}
			}
		}(rIdx)
	}
	wg.Wait()
	close(errCh)
	for e := range errCh {
		t.Fatalf("contention error: %v", e)
	}

	// Benchmark 40 deep keyset pagination queries and 40 filtered queries.
	var deepLatencies []time.Duration
	for i := 0; i < 40; i++ {
		cursorPath := fmt.Sprintf("Artist-%04d/", (i*11)%480)
		start := time.Now()
		rows, err := reader.Query(`SELECT id, path_key, title, artist, album, size, duration_ms FROM assets WHERE root_id='r1' AND status='active' AND (path_key, id) > (?, ?) ORDER BY path_key, id LIMIT 100`, cursorPath, "")
		if err != nil {
			t.Fatalf("deep query: %v", err)
		}
		n := 0
		for rows.Next() {
			var id, pk, title, artist, album string
			var sz, dur int64
			if err := rows.Scan(&id, &pk, &title, &artist, &album, &sz, &dur); err != nil {
				_ = rows.Close()
				t.Fatalf("scan: %v", err)
			}
			n++
		}
		_ = rows.Close()
		deepLatencies = append(deepLatencies, time.Since(start))
		if n != 100 {
			t.Fatalf("expected 100 rows, got %d", n)
		}
	}

	var filterLatencies []time.Duration
	for i := 0; i < 40; i++ {
		artistFilter := fmt.Sprintf("Artist-%04d", i%500)
		start := time.Now()
		rows, err := reader.Query(`SELECT id, path_key, title FROM assets WHERE root_id='r1' AND status='active' AND artist=? ORDER BY path_key, id LIMIT 100`, artistFilter)
		if err != nil {
			t.Fatalf("filter query: %v", err)
		}
		n := 0
		for rows.Next() {
			var id, pk, title string
			if err := rows.Scan(&id, &pk, &title); err != nil {
				_ = rows.Close()
				t.Fatalf("scan filter: %v", err)
			}
			n++
		}
		_ = rows.Close()
		filterLatencies = append(filterLatencies, time.Since(start))
		if n != 100 {
			t.Fatalf("expected 100 filtered rows, got %d", n)
		}
	}

	slices.Sort(deepLatencies)
	slices.Sort(filterLatencies)
	fi, _ := os.Stat(dbPath)
	t.Logf("100k insert: %v, db_bytes=%d (%.1f MiB)", insertElapsed, fi.Size(), float64(fi.Size())/(1024*1024))
	t.Logf("deep keyset page(100) p50=%v p95=%v max=%v", deepLatencies[len(deepLatencies)/2], deepLatencies[int(float64(len(deepLatencies))*0.95)], deepLatencies[len(deepLatencies)-1])
	t.Logf("artist filter page(100) p50=%v p95=%v max=%v", filterLatencies[len(filterLatencies)/2], filterLatencies[int(float64(len(filterLatencies))*0.95)], filterLatencies[len(filterLatencies)-1])
}
