package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/agonie0v0/melokeep/internal/app"
	"github.com/agonie0v0/melokeep/internal/legacyimport"
	"github.com/agonie0v0/melokeep/internal/store"
)

func main() {
	code := run(os.Args[1:], os.Environ(), os.Stdout, os.Stderr)
	if code != 0 {
		os.Exit(code)
	}
}

func run(args []string, environ []string, stdout, stderr io.Writer) int {
	if len(args) > 0 && args[0] == "migrate" {
		return runMigrate(args[1:], environ, stdout, stderr)
	}
	return runServe(args, environ, stdout, stderr)
}

func runServe(args []string, environ []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("melokeep", flag.ContinueOnError)
	fs.SetOutput(stderr)
	hostFlag := fs.String("host", "", "HTTP listen host (overrides MELOKEEP_HOST)")
	portFlag := fs.Int("port", 0, "HTTP listen port (overrides MELOKEEP_PORT)")
	dataDirFlag := fs.String("data-dir", "", "Data directory (overrides MELOKEEP_DATA_DIR)")
	musicRootFlag := fs.String("music-root", "", "Music root directory (overrides MELOKEEP_FILE_ROOT)")
	staticDirFlag := fs.String("static-dir", "", "Frontend dist directory (overrides MELOKEEP_STATIC_DIR)")
	readOnlyFlag := fs.Bool("read-only", false, "Start in read-only verification mode (no writes, no watchers, no outbound notifications)")
	allowBlockedFlag := fs.Bool("allow-blocked-write", false, "Allow write mode even if a prior legacy migration flagged blocks_write_switch")
	authUserFlag := fs.String("auth-user", "", "Auth username (overrides MELOKEEP_AUTH_USER)")
	authPassFlag := fs.String("auth-pass", "", "Auth password (overrides MELOKEEP_AUTH_PASS)")
	versionFlag := fs.Bool("version", false, "Print version and exit")
	fs.Usage = func() {
		fmt.Fprintf(stderr, "Usage:\n  melokeep [serve flags]\n  melokeep migrate [migrate flags]\n\nServe flags:\n")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		return 2
	}
	if *versionFlag {
		bi := app.CurrentBuildInfo()
		if bi.ShortCommit != "" {
			fmt.Fprintf(stdout, "%s (%s)\n", bi.Version, bi.ShortCommit)
		} else {
			fmt.Fprintln(stdout, bi.Version)
		}
		return 0
	}

	var readOnlyPtr *bool
	fs.Visit(func(f *flag.Flag) {
		if f.Name == "read-only" {
			v := *readOnlyFlag
			readOnlyPtr = &v
		}
	})
	cfg := app.LoadConfig(environ, app.ConfigOverrides{
		Host:      *hostFlag,
		Port:      *portFlag,
		DataDir:   *dataDirFlag,
		MusicRoot: *musicRootFlag,
		StaticDir: *staticDirFlag,
		ReadOnly:  readOnlyPtr,
		AuthUser:  *authUserFlag,
		AuthPass:  *authPassFlag,
	})
	cfg.AllowBlockedWrite = *allowBlockedFlag

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	for {
		application, err := app.New(ctx, cfg)
		if err != nil {
			fmt.Fprintf(stderr, "melokeep startup failed: %v\n", err)
			return 1
		}
		fmt.Fprintf(stdout, "MeloKeep listening on http://%s:%d (read_only=%v, data_dir=%s)\n", cfg.Host, cfg.Port, cfg.ReadOnly, application.AbsDataDir)
		runErr := application.Run(ctx)
		if runErr != nil {
			fmt.Fprintf(stderr, "melokeep stopped with error: %v\n", runErr)
			return 1
		}
		if application.RestartRequested() && ctx.Err() == nil {
			fmt.Fprintln(stdout, "MeloKeep restarting...")
			continue
		}
		return 0
	}
}

type migrateOutput struct {
	Import    legacyimport.ImportReport     `json:"import"`
	Integrity *legacyimport.IntegrityReport `json:"integrity,omitempty"`
}

func runMigrate(args []string, environ []string, stdout, stderr io.Writer) int {
	baseCfg := app.LoadConfig(environ, app.ConfigOverrides{})
	fs := flag.NewFlagSet("melokeep migrate", flag.ContinueOnError)
	fs.SetOutput(stderr)
	sourceData := fs.String("source-data", baseCfg.DataDir, "Legacy data directory containing data.db / .song pickles")
	sourceDB := fs.String("source-db", "", "Explicit legacy SQLite db path (optional)")
	sourceConfig := fs.String("source-config", "", "Legacy config.json path (optional)")
	sourceEnv := fs.String("source-env", "", "Legacy .env file path (optional)")
	targetData := fs.String("target-data", baseCfg.DataDir, "Target Go data directory (stores go/melokeep.db)")
	musicRoot := fs.String("music-root", "", "Target music root path (defaults to MELOKEEP_FILE_ROOT)")
	pythonBin := fs.String("python", "", "Python executable for reading legacy .song pickle snapshots")
	dryRun := fs.Bool("dry-run", false, "Analyze legacy snapshot without writing target database")
	reportOut := fs.String("report-out", "", "Optional path to write redacted JSON migration report")
	fs.Usage = func() {
		fmt.Fprintf(stderr, "Usage:\n  melokeep migrate [flags]\n\nFlags:\n")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		return 2
	}

	fileRoot := strings.TrimSpace(*musicRoot)
	if fileRoot == "" && len(baseCfg.MusicRoots) > 0 {
		fileRoot = baseCfg.MusicRoots[0]
	}
	py := strings.TrimSpace(*pythonBin)
	if py == "" {
		py = app.DiscoverPython(baseCfg.PythonPath)
	}
	absTarget, err := filepath.Abs(*targetData)
	if err != nil {
		fmt.Fprintf(stderr, "invalid target-data %q: %v\n", *targetData, err)
		return 1
	}
	dbPath := store.DefaultDBPath(absTarget)
	if *dryRun {
		// Use a temporary in-dir DB only when target DB does not exist yet so dry-run never alters real data.
		tmpDir, tErr := os.MkdirTemp("", "melokeep-migrate-dryrun-*")
		if tErr != nil {
			fmt.Fprintf(stderr, "create dry-run temp dir: %v\n", tErr)
			return 1
		}
		defer os.RemoveAll(tmpDir)
		dbPath = store.DefaultDBPath(tmpDir)
	}

	ctx := context.Background()
	st, err := store.Open(ctx, store.Options{Path: dbPath})
	if err != nil {
		fmt.Fprintf(stderr, "open store %s: %v\n", dbPath, err)
		return 1
	}
	defer st.Close()

	rep, err := legacyimport.ImportLegacySnapshot(ctx, st, legacyimport.ImportOptions{
		LegacyDataDir: *sourceData,
		LegacyDBPath:  *sourceDB,
		ConfigPath:    *sourceConfig,
		EnvPath:       *sourceEnv,
		FileRoot:      fileRoot,
		PythonBin:     py,
		DryRun:        *dryRun,
	})
	if err != nil {
		fmt.Fprintf(stderr, "migration failed: %v\n", err)
		return 1
	}

	out := migrateOutput{Import: rep}
	var integrityFailed bool
	if !*dryRun {
		irep, iErr := legacyimport.CheckIntegrity(ctx, st)
		if iErr != nil {
			fmt.Fprintf(stderr, "integrity check failed: %v\n", iErr)
			return 1
		}
		out.Integrity = &irep
		integrityFailed = !irep.OK
	}

	encoded, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		fmt.Fprintf(stderr, "encode report: %v\n", err)
		return 1
	}
	encoded = append(encoded, '\n')
	_, _ = stdout.Write(encoded)
	if strings.TrimSpace(*reportOut) != "" {
		if mkErr := os.MkdirAll(filepath.Dir(*reportOut), 0o750); mkErr == nil {
			if wErr := os.WriteFile(*reportOut, encoded, 0o600); wErr != nil {
				fmt.Fprintf(stderr, "write report-out failed: %v\n", wErr)
				return 1
			}
		}
	}

	if rep.BlocksWriteSwitch || integrityFailed {
		return 1
	}
	return 0
}
