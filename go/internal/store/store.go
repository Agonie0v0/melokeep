package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/migrations"
	_ "modernc.org/sqlite"
)

type Options struct {
	Path        string
	ReadMaxOpen int
}

// DefaultDBPath returns MELOKEEP_DATA_DIR/go/melokeep.db.
func DefaultDBPath(dataDir string) string {
	if strings.TrimSpace(dataDir) == "" {
		dataDir = "data"
	}
	return filepath.Join(dataDir, "go", "melokeep.db")
}

// Store owns the SQLite connection pools (single writer + bounded read-only pool)
// and short atomic domain transactions.
type Store struct {
	path   string
	writer *sql.DB
	reader *sql.DB
}

func Open(ctx context.Context, opts Options) (*Store, error) {
	if strings.TrimSpace(opts.Path) == "" {
		return nil, model.NewError(model.ErrInvalidInput, 400, "数据库路径不能为空")
	}
	absPath, err := filepath.Abs(opts.Path)
	if err != nil {
		return nil, fmt.Errorf("resolve db path: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(absPath), 0o750); err != nil {
		return nil, fmt.Errorf("create db directory: %w", err)
	}

	readConns := opts.ReadMaxOpen
	if readConns < 2 {
		readConns = 2
	}
	if readConns > 4 {
		readConns = 4
	}

	baseDSN := fmt.Sprintf(
		"file:%s?_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)&_pragma=busy_timeout(5000)&_pragma=synchronous(FULL)",
		sqliteFileURIPath(absPath),
	)
	writer, err := sql.Open("sqlite", baseDSN)
	if err != nil {
		return nil, fmt.Errorf("open writer db: %w", err)
	}
	writer.SetMaxOpenConns(1)
	writer.SetMaxIdleConns(1)

	if err := writer.PingContext(ctx); err != nil {
		_ = writer.Close()
		return nil, fmt.Errorf("ping writer db: %w", err)
	}
	if err := verifyPragmas(ctx, writer); err != nil {
		_ = writer.Close()
		return nil, err
	}
	if err := migrations.Apply(ctx, writer); err != nil {
		_ = writer.Close()
		return nil, err
	}

	readerDSN := baseDSN + "&_pragma=query_only(ON)"
	reader, err := sql.Open("sqlite", readerDSN)
	if err != nil {
		_ = writer.Close()
		return nil, fmt.Errorf("open reader db: %w", err)
	}
	reader.SetMaxOpenConns(readConns)
	reader.SetMaxIdleConns(readConns)
	if err := reader.PingContext(ctx); err != nil {
		_ = reader.Close()
		_ = writer.Close()
		return nil, fmt.Errorf("ping reader db: %w", err)
	}

	return &Store{
		path:   absPath,
		writer: writer,
		reader: reader,
	}, nil
}

func sqliteFileURIPath(absPath string) string {
	r := strings.NewReplacer("%", "%25", "?", "%3F", "#", "%23")
	return r.Replace(filepath.ToSlash(absPath))
}

func verifyPragmas(ctx context.Context, db *sql.DB) error {
	var journal string
	var fk, syncMode int
	if err := db.QueryRowContext(ctx, `PRAGMA journal_mode`).Scan(&journal); err != nil {
		return fmt.Errorf("read journal_mode: %w", err)
	}
	if err := db.QueryRowContext(ctx, `PRAGMA foreign_keys`).Scan(&fk); err != nil {
		return fmt.Errorf("read foreign_keys: %w", err)
	}
	if err := db.QueryRowContext(ctx, `PRAGMA synchronous`).Scan(&syncMode); err != nil {
		return fmt.Errorf("read synchronous: %w", err)
	}
	if !strings.EqualFold(journal, "wal") {
		return fmt.Errorf("expected WAL journal_mode, got %q", journal)
	}
	if fk != 1 {
		return fmt.Errorf("expected foreign_keys=1, got %d", fk)
	}
	if syncMode < 2 {
		return fmt.Errorf("expected synchronous=FULL (>=2), got %d", syncMode)
	}
	return nil
}

func (s *Store) Path() string {
	return s.path
}

func (s *Store) Writer() *sql.DB {
	return s.writer
}

func (s *Store) Reader() *sql.DB {
	return s.reader
}

func (s *Store) Close() error {
	var errR, errW error
	if s.reader != nil {
		errR = s.reader.Close()
	}
	if s.writer != nil {
		errW = s.writer.Close()
	}
	return errors.Join(errR, errW)
}

func (s *Store) WithTx(ctx context.Context, fn func(tx *sql.Tx) error) error {
	tx, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	if err := fn(tx); err != nil {
		_ = tx.Rollback()
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit tx: %w", err)
	}
	return nil
}

func (s *Store) WithReadTx(ctx context.Context, fn func(tx *sql.Tx) error) error {
	tx, err := s.reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return fmt.Errorf("begin read tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	return fn(tx)
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func isUniqueConstraintErr(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "UNIQUE constraint failed") || strings.Contains(msg, "PRIMARY KEY constraint failed")
}
