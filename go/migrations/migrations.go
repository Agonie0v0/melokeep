package migrations

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"fmt"
	"io/fs"
	"sort"
	"strconv"
	"strings"
	"time"
)

//go:embed *.sql
var sqlFiles embed.FS

type Migration struct {
	Version  int
	Name     string
	SQL      string
	Checksum string
}

func Load() ([]Migration, error) {
	entries, err := fs.ReadDir(sqlFiles, ".")
	if err != nil {
		return nil, fmt.Errorf("read embedded migrations: %w", err)
	}
	var list []Migration
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		raw, err := fs.ReadFile(sqlFiles, entry.Name())
		if err != nil {
			return nil, fmt.Errorf("read migration %s: %w", entry.Name(), err)
		}
		parts := strings.SplitN(entry.Name(), "_", 2)
		if len(parts) < 2 {
			return nil, fmt.Errorf("invalid migration filename %q", entry.Name())
		}
		ver, err := strconv.Atoi(parts[0])
		if err != nil || ver <= 0 {
			return nil, fmt.Errorf("invalid migration version in %q", entry.Name())
		}
		normalized := strings.ReplaceAll(string(raw), "\r\n", "\n")
		sum := sha256.Sum256([]byte(normalized))
		list = append(list, Migration{
			Version:  ver,
			Name:     entry.Name(),
			SQL:      normalized,
			Checksum: hex.EncodeToString(sum[:]),
		})
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].Version < list[j].Version
	})
	for i := 1; i < len(list); i++ {
		if list[i].Version == list[i-1].Version {
			return nil, fmt.Errorf("duplicate migration version %d", list[i].Version)
		}
	}
	return list, nil
}

func Apply(ctx context.Context, db *sql.DB) error {
	if db == nil {
		return fmt.Errorf("migrations.Apply: nil db")
	}
	list, err := Load()
	if err != nil {
		return err
	}
	if _, err := db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version INTEGER PRIMARY KEY,
			checksum TEXT NOT NULL,
			applied_at INTEGER NOT NULL
		)
	`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	applied := make(map[int]string)
	rows, err := db.QueryContext(ctx, `SELECT version, checksum FROM schema_migrations ORDER BY version`)
	if err != nil {
		return fmt.Errorf("query schema_migrations: %w", err)
	}
	for rows.Next() {
		var ver int
		var sum string
		if err := rows.Scan(&ver, &sum); err != nil {
			_ = rows.Close()
			return fmt.Errorf("scan schema_migrations: %w", err)
		}
		applied[ver] = sum
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return fmt.Errorf("iterate schema_migrations: %w", err)
	}
	_ = rows.Close()

	for _, m := range list {
		if existingSum, ok := applied[m.Version]; ok {
			if existingSum != m.Checksum {
				return fmt.Errorf("migration version %d (%s) checksum mismatch: db=%s embedded=%s",
					m.Version, m.Name, existingSum, m.Checksum)
			}
			continue
		}
		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return fmt.Errorf("begin migration %d: %w", m.Version, err)
		}
		if _, err := tx.ExecContext(ctx, m.SQL); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("exec migration %d (%s): %w", m.Version, m.Name, err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO schema_migrations(version, checksum, applied_at) VALUES(?, ?, ?)`,
			m.Version, m.Checksum, time.Now().UTC().UnixMilli(),
		); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("record migration %d: %w", m.Version, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %d: %w", m.Version, err)
		}
	}
	return nil
}
