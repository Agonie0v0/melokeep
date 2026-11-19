package legacyimport

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
	_ "modernc.org/sqlite"
)

const (
	ImporterVersion     = "legacyimport.v1"
	DefaultMaxSongBytes = 16 << 20 // 16 MiB safety cap for .song files
)

type ImportOptions struct {
	LegacyDataDir  string
	LegacyDBPath   string
	ConfigPath     string
	EnvPath        string
	FileRoot       string
	PythonBin      string
	DryRun         bool
	MaxSongBytes   int64
	SongSubprocess time.Duration
}

type CorruptSong struct {
	File    string `json:"file"`
	TrackID string `json:"track_id,omitempty"`
	Reason  string `json:"reason"`
}

type ImportCounts struct {
	LegacyRecords     int `json:"legacy_records"`
	Settings          int `json:"settings"`
	Tracks            int `json:"tracks"`
	Variants          int `json:"variants"`
	SongFiles         int `json:"song_files"`
	Assets            int `json:"assets"`
	AssetAliases      int `json:"asset_aliases"`
	Collections       int `json:"collections"`
	CollectionItems   int `json:"collection_items"`
	Downloads         int `json:"downloads"`
	LibraryJobs       int `json:"library_jobs"`
	FileOperations    int `json:"file_operations"`
	DurableRecords    int `json:"durable_records"`
	ReResolveRequired int `json:"re_resolve_required"`
}

type ImportReport struct {
	ImportID          string        `json:"import_id"`
	ImporterVersion   string        `json:"importer_version"`
	SourceFingerprint string        `json:"source_fingerprint"`
	DryRun            bool          `json:"dry_run"`
	AlreadyImported   bool          `json:"already_imported"`
	BlocksWriteSwitch bool          `json:"blocks_write_switch"`
	Counts            ImportCounts  `json:"counts"`
	CorruptSongs      []CorruptSong `json:"corrupt_songs,omitempty"`
	Warnings          []string      `json:"warnings,omitempty"`
	StartedAt         int64         `json:"started_at"`
	CompletedAt       int64         `json:"completed_at"`
}

// restrictedSongDecoderScript replicates song_storage.RestrictedUnpickler offline in an isolated Python process.
const restrictedSongDecoderScript = `
import base64, copyreg, io, json, os, pathlib, pickle, sys
from collections import OrderedDict
from types import SimpleNamespace

MAX_BYTES = int(sys.argv[1])
PATHS = {
    'PosixPath': pathlib.PurePosixPath, 'PurePosixPath': pathlib.PurePosixPath,
    'WindowsPath': pathlib.PureWindowsPath, 'PureWindowsPath': pathlib.PureWindowsPath,
    'Path': pathlib.PurePath,
}
BUILTINS = {'object': object, 'set': set, 'frozenset': frozenset,
            'slice': slice, 'dict': dict, 'list': list, 'tuple': tuple, 'bytes': bytes}

class _UnpickledSongInfo:
    def __setstate__(self, state):
        if isinstance(state, dict):
            self.__dict__.update(state)
        elif isinstance(state, tuple) and len(state) == 2:
            d, s = state
            if isinstance(d, dict): self.__dict__.update(d)
            if isinstance(s, dict): self.__dict__.update(s)
        elif state is not None:
            raise pickle.UnpicklingError('unsupported song state')

class RestrictedUnpickler(pickle.Unpickler):
    def find_class(self, module, name):
        if module == 'native_engine' and name == 'SongInfo':
            return _UnpickledSongInfo
        if module == 'types' and name == 'SimpleNamespace':
            return SimpleNamespace
        if module in {'copyreg', 'copy_reg'} and name == '_reconstructor':
            return copyreg._reconstructor
        if module == 'builtins' and name in BUILTINS:
            return BUILTINS[name]
        if module == 'collections' and name == 'OrderedDict':
            return OrderedDict
        if module in {'pathlib', 'pathlib._local'} and name in PATHS:
            return PATHS[name]
        raise pickle.UnpicklingError(f'disallowed class: {module}.{name}')

SECRET_KEYS = {'download_url', 'default_download_headers', 'default_download_cookies', 'downloaded_contents', 'downloaded_contents_b64'}

def safe(v):
    if v is None or isinstance(v, (bool, int, float, str)):
        return v
    if isinstance(v, pathlib.PurePath):
        return str(v)
    if isinstance(v, (bytes, bytearray)):
        return {'__bytes_b64__': base64.b64encode(bytes(v)).decode('ascii')}
    if isinstance(v, dict):
        return {str(k): safe(val) for k, val in v.items() if str(k) not in SECRET_KEYS}
    if isinstance(v, (list, tuple, set, frozenset)):
        return [safe(x) for x in v]
    if isinstance(v, (_UnpickledSongInfo, SimpleNamespace)):
        return safe(v.__dict__)
    return str(v)

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

out = []
for path in json.loads(sys.stdin.read()):
    try:
        size = os.path.getsize(path)
        if size > MAX_BYTES:
            out.append({'file': path, 'error': f'file exceeds limit ({size} > {MAX_BYTES})'})
            continue
        with open(path, 'rb') as f:
            raw = f.read()
        if raw.lstrip().startswith(b'{'):
            data = json.loads(raw.decode('utf-8'))
        else:
            obj = RestrictedUnpickler(io.BytesIO(raw)).load()
            data = safe(obj)
        for secret_k in ('download_url', 'default_download_headers', 'default_download_cookies', 'downloaded_contents', 'downloaded_contents_b64'):
            if isinstance(data, dict):
                data.pop(secret_k, None)
        out.append({'file': path, 'data': data})
    except Exception as exc:
        out.append({'file': path, 'error': str(exc)})
print(json.dumps(out, ensure_ascii=False))
`

type decodedSongEntry struct {
	File  string         `json:"file"`
	Data  map[string]any `json:"data,omitempty"`
	Error string         `json:"error,omitempty"`
}

type legacyRecord struct {
	Kind    string
	ID      string
	Body    string
	Updated int64
}

type legacyAsset struct {
	ID     string
	Path   string
	Locks  string
	Source string
}

type legacyPathAlias struct {
	Token   string
	AssetID string
}

func ImportLegacySnapshot(ctx context.Context, st *store.Store, opts ImportOptions) (ImportReport, error) {
	if st == nil {
		return ImportReport{}, model.NewError(model.ErrInvalidInput, 400, "store 不能为空")
	}
	if opts.MaxSongBytes <= 0 {
		opts.MaxSongBytes = DefaultMaxSongBytes
	}
	if opts.SongSubprocess <= 0 {
		opts.SongSubprocess = 30 * time.Second
	}

	dbPath := resolveLegacyDBPath(opts)
	cfgPath := opts.ConfigPath
	if cfgPath == "" && opts.LegacyDataDir != "" {
		cand := filepath.Join(filepath.Dir(opts.LegacyDataDir), "config.json")
		if _, err := os.Stat(cand); err == nil {
			cfgPath = cand
		}
	}
	envPath := opts.EnvPath
	if envPath == "" && opts.LegacyDataDir != "" {
		cand := filepath.Join(filepath.Dir(opts.LegacyDataDir), ".env")
		if _, err := os.Stat(cand); err == nil {
			envPath = cand
		}
	}

	var songFiles []string
	if opts.LegacyDataDir != "" {
		engineDir := filepath.Join(opts.LegacyDataDir, "engine")
		if entries, err := os.ReadDir(engineDir); err == nil {
			for _, e := range entries {
				if !e.IsDir() && strings.HasSuffix(strings.ToLower(e.Name()), ".song") {
					songFiles = append(songFiles, filepath.Join(engineDir, e.Name()))
				}
			}
		}
	}
	sort.Strings(songFiles)

	fp, err := computeSourceFingerprint(dbPath, cfgPath, envPath, songFiles)
	if err != nil {
		return ImportReport{}, fmt.Errorf("compute source fingerprint: %w", err)
	}

	started := time.Now().UTC().UnixMilli()
	report := ImportReport{
		ImportID:          model.NewUUID(),
		ImporterVersion:   ImporterVersion,
		SourceFingerprint: fp,
		DryRun:            opts.DryRun,
		StartedAt:         started,
	}

	var existingState, existingReportJSON string
	err = st.Reader().QueryRowContext(ctx, `
		SELECT state, report_json FROM import_runs WHERE source_fingerprint = ?
	`, fp).Scan(&existingState, &existingReportJSON)
	if err == nil && existingState == "completed" {
		var prev ImportReport
		if json.Unmarshal([]byte(existingReportJSON), &prev) == nil {
			prev.AlreadyImported = true
			prev.DryRun = opts.DryRun
			return prev, nil
		}
	} else if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return ImportReport{}, err
	}

	var records []legacyRecord
	var legAssets []legacyAsset
	var legAliases []legacyPathAlias
	if dbPath != "" {
		records, legAssets, legAliases, err = readLegacySQLite(ctx, dbPath)
		if err != nil {
			return ImportReport{}, fmt.Errorf("read legacy sqlite %s: %w", dbPath, err)
		}
	}

	decodedSongs, corruptSongs, err := decodeSongFiles(ctx, songFiles, opts.PythonBin, opts.MaxSongBytes, opts.SongSubprocess)
	if err != nil {
		return ImportReport{}, err
	}
	report.CorruptSongs = corruptSongs
	report.Counts.SongFiles = len(songFiles)
	report.Counts.ReResolveRequired = len(corruptSongs)
	report.Counts.LegacyRecords = len(records)

	runtimeCfg, hasRuntimeCfg, cfgWarnings := loadMergedLegacyConfig(cfgPath, envPath, opts.FileRoot)
	report.Warnings = append(report.Warnings, cfgWarnings...)

	counts, blocksSwitch, scanWarnings := analyzeLegacyPlan(records, decodedSongs, legAssets, legAliases, hasRuntimeCfg)
	report.Counts.Settings += counts.Settings
	report.Counts.Tracks += counts.Tracks
	report.Counts.Variants += counts.Variants
	report.Counts.Assets += counts.Assets
	report.Counts.AssetAliases += counts.AssetAliases
	report.Counts.Collections += counts.Collections
	report.Counts.CollectionItems += counts.CollectionItems
	report.Counts.Downloads += counts.Downloads
	report.Counts.LibraryJobs += counts.LibraryJobs
	report.Counts.FileOperations += counts.FileOperations
	report.Counts.DurableRecords += counts.DurableRecords
	report.Counts.ReResolveRequired += counts.ReResolveRequired
	report.BlocksWriteSwitch = blocksSwitch
	report.Warnings = append(report.Warnings, scanWarnings...)
	report.CompletedAt = time.Now().UTC().UnixMilli()

	if opts.DryRun {
		return report, nil
	}

	if err := persistLegacyImport(ctx, st, report, opts, runtimeCfg, hasRuntimeCfg, records, decodedSongs, legAssets, legAliases); err != nil {
		return ImportReport{}, err
	}
	return report, nil
}

func resolveLegacyDBPath(opts ImportOptions) string {
	if opts.LegacyDBPath != "" {
		return opts.LegacyDBPath
	}
	if opts.LegacyDataDir == "" {
		return ""
	}
	for _, name := range []string{"melokeep.db", "engine_api.db", "melodock.db"} {
		p := filepath.Join(opts.LegacyDataDir, name)
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func computeSourceFingerprint(dbPath, cfgPath, envPath string, songFiles []string) (string, error) {
	h := sha256.New()
	_, _ = io.WriteString(h, ImporterVersion+"\n")
	for _, p := range []string{dbPath, cfgPath, envPath} {
		if p == "" {
			_, _ = io.WriteString(h, "none\n")
			continue
		}
		if sum, err := fileSHA256(p); err == nil {
			_, _ = io.WriteString(h, filepath.Base(p)+":"+sum+"\n")
		} else {
			_, _ = io.WriteString(h, filepath.Base(p)+":missing\n")
		}
	}
	for _, sf := range songFiles {
		sum, err := fileSHA256(sf)
		if err != nil {
			sum = "err"
		}
		_, _ = io.WriteString(h, filepath.Base(sf)+":"+sum+"\n")
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func readLegacySQLite(ctx context.Context, dbPath string) ([]legacyRecord, []legacyAsset, []legacyPathAlias, error) {
	abs, err := filepath.Abs(dbPath)
	if err != nil {
		return nil, nil, nil, err
	}
	dsn := fmt.Sprintf("file:%s?mode=ro&_pragma=query_only(ON)&_pragma=busy_timeout(5000)", filepath.ToSlash(abs))
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, nil, nil, err
	}
	defer db.Close()

	hasTable := func(name string) bool {
		var cnt int
		_ = db.QueryRowContext(ctx, `SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?`, name).Scan(&cnt)
		return cnt > 0
	}

	var records []legacyRecord
	if hasTable("records") {
		rows, err := db.QueryContext(ctx, `SELECT kind, id, body, updated FROM records ORDER BY kind, id`)
		if err != nil {
			return nil, nil, nil, err
		}
		for rows.Next() {
			var r legacyRecord
			if err := rows.Scan(&r.Kind, &r.ID, &r.Body, &r.Updated); err != nil {
				_ = rows.Close()
				return nil, nil, nil, err
			}
			records = append(records, r)
		}
		_ = rows.Close()
	}

	var assets []legacyAsset
	if hasTable("library_assets") {
		rows, err := db.QueryContext(ctx, `SELECT id, path, locks, source FROM library_assets ORDER BY id`)
		if err != nil {
			return nil, nil, nil, err
		}
		for rows.Next() {
			var a legacyAsset
			if err := rows.Scan(&a.ID, &a.Path, &a.Locks, &a.Source); err != nil {
				_ = rows.Close()
				return nil, nil, nil, err
			}
			assets = append(assets, a)
		}
		_ = rows.Close()
	}

	var aliases []legacyPathAlias
	if hasTable("library_paths") {
		rows, err := db.QueryContext(ctx, `SELECT token, asset_id FROM library_paths ORDER BY token`)
		if err != nil {
			return nil, nil, nil, err
		}
		for rows.Next() {
			var al legacyPathAlias
			if err := rows.Scan(&al.Token, &al.AssetID); err != nil {
				_ = rows.Close()
				return nil, nil, nil, err
			}
			aliases = append(aliases, al)
		}
		_ = rows.Close()
	}

	return records, assets, aliases, nil
}

func decodeSongFiles(ctx context.Context, files []string, pythonBin string, maxBytes int64, timeout time.Duration) ([]decodedSongEntry, []CorruptSong, error) {
	if len(files) == 0 {
		return nil, nil, nil
	}
	var decoded []decodedSongEntry
	var corrupt []CorruptSong
	var picklePaths []string

	for _, p := range files {
		fi, err := os.Stat(p)
		if err != nil {
			corrupt = append(corrupt, CorruptSong{File: filepath.Base(p), TrackID: strings.TrimSuffix(filepath.Base(p), ".song"), Reason: err.Error()})
			continue
		}
		if fi.Size() > maxBytes {
			corrupt = append(corrupt, CorruptSong{File: filepath.Base(p), TrackID: strings.TrimSuffix(filepath.Base(p), ".song"), Reason: fmt.Sprintf("file exceeds %d bytes", maxBytes)})
			continue
		}
		raw, err := os.ReadFile(p)
		if err != nil {
			corrupt = append(corrupt, CorruptSong{File: filepath.Base(p), TrackID: strings.TrimSuffix(filepath.Base(p), ".song"), Reason: err.Error()})
			continue
		}
		trimmed := bytes.TrimSpace(raw)
		if bytes.HasPrefix(trimmed, []byte("{")) {
			m, err := DecodeJSONSong(trimmed)
			if err != nil {
				corrupt = append(corrupt, CorruptSong{File: filepath.Base(p), TrackID: strings.TrimSuffix(filepath.Base(p), ".song"), Reason: "invalid json: " + err.Error()})
				continue
			}
			decoded = append(decoded, decodedSongEntry{File: p, Data: m})
			continue
		}
		picklePaths = append(picklePaths, p)
	}

	if len(picklePaths) > 0 {
		if pythonBin == "" {
			pythonBin = detectPythonBin()
		}
		if pythonBin == "" {
			for _, p := range picklePaths {
				corrupt = append(corrupt, CorruptSong{
					File:    filepath.Base(p),
					TrackID: strings.TrimSuffix(filepath.Base(p), ".song"),
					Reason:  "legacy pickle .song requires python restricted unpickler",
				})
			}
			return decoded, corrupt, nil
		}
		subCtx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		cmd := exec.CommandContext(subCtx, pythonBin, "-c", restrictedSongDecoderScript, fmt.Sprintf("%d", maxBytes))
		cmd.Env = append(os.Environ(), "PYTHONUTF8=1", "PYTHONIOENCODING=utf-8")
		inBytes, _ := json.Marshal(picklePaths)
		cmd.Stdin = bytes.NewReader(inBytes)
		var stdout, stderr bytes.Buffer
		cmd.Stdout = &stdout
		cmd.Stderr = &stderr
		if err := cmd.Run(); err != nil {
			return nil, nil, fmt.Errorf("restricted unpickler subprocess failed: %w (%s)", err, strings.TrimSpace(stderr.String()))
		}
		var entries []decodedSongEntry
		if err := json.Unmarshal(stdout.Bytes(), &entries); err != nil {
			return nil, nil, fmt.Errorf("parse restricted unpickler output: %w", err)
		}
		for _, e := range entries {
			if e.Error != "" {
				corrupt = append(corrupt, CorruptSong{
					File:    filepath.Base(e.File),
					TrackID: strings.TrimSuffix(filepath.Base(e.File), ".song"),
					Reason:  e.Error,
				})
				continue
			}
			decoded = append(decoded, e)
		}
	}
	return decoded, corrupt, nil
}

func detectPythonBin() string {
	var candidates []string
	if runtime.GOOS == "windows" {
		candidates = []string{
			filepath.Join(".venv", "Scripts", "python.exe"),
			"python",
			"python3",
		}
	} else {
		candidates = []string{
			filepath.Join(".venv", "bin", "python"),
			"python3",
			"python",
		}
	}
	for _, c := range candidates {
		if filepath.IsAbs(c) || strings.ContainsAny(c, `/\`) {
			if _, err := os.Stat(c); err == nil {
				return c
			}
		} else if p, err := exec.LookPath(c); err == nil {
			return p
		}
	}
	return ""
}

// DecodeJSONSong parses a JSON-encoded .song cache payload and recursively strips
// transient URLs, headers, cookies, and embedded bytes.
func DecodeJSONSong(raw []byte) (map[string]any, error) {
	var m map[string]any
	if err := json.Unmarshal(bytes.TrimSpace(raw), &m); err != nil {
		return nil, err
	}
	stripSongSecrets(m)
	return m, nil
}

func stripSongSecrets(v any) {
	switch x := v.(type) {
	case map[string]any:
		for _, sk := range []string{"download_url", "default_download_headers", "default_download_cookies", "downloaded_contents", "downloaded_contents_b64"} {
			delete(x, sk)
		}
		for _, val := range x {
			stripSongSecrets(val)
		}
	case []any:
		for _, item := range x {
			stripSongSecrets(item)
		}
	}
}

func loadMergedLegacyConfig(cfgPath, envPath, fileRoot string) (config.RuntimeConfig, bool, []string) {
	rt := config.DefaultRuntimeConfig()
	var warnings []string
	hasAny := false

	if envPath != "" {
		if raw, err := os.ReadFile(envPath); err == nil {
			hasAny = true
			envMap := parseDotEnv(string(raw))
			canon := config.CanonicalEnv(envMap)
			if v := canon["MELOKEEP_FILE_ROOT"]; v != "" {
				rt.WorkDir = v
			}
			if v := canon["MELOKEEP_DATA_DIR"]; v != "" {
				rt.DataDir = v
			}
		} else {
			warnings = append(warnings, "读取 .env 失败: "+err.Error())
		}
	}
	if cfgPath != "" {
		if raw, err := os.ReadFile(cfgPath); err == nil {
			hasAny = true
			var rawMap map[string]any
			if err := json.Unmarshal(raw, &rawMap); err == nil {
				if srcs, ok := rawMap["music_sources"].([]any); ok {
					var out []string
					for _, s := range srcs {
						if name, ok := s.(string); ok {
							out = append(out, config.PublicSourceID(name))
						}
					}
					if len(out) > 0 {
						rt.Sources = out
					}
				}
				if gcfg, ok := rawMap["global_client_cfg"].(map[string]any); ok {
					if n, ok := gcfg["search_size_per_source"].(float64); ok && n > 0 {
						rt.ResultsPerSource = int(n)
					}
				}
				if v, ok := rawMap["download_layout"].(string); ok && v != "" {
					rt.DownloadLayout = v
				}
				if v, ok := rawMap["lyrics_mode"].(string); ok && v != "" {
					rt.LyricsMode = v
				}
				if cookies, ok := rawMap["cookies"].(map[string]any); ok {
					for k, v := range cookies {
						sid := config.PublicSourceID(k)
						cur := rt.SourceSettings[sid]
						if s, ok := v.(string); ok {
							cur.Cookie = s
						}
						rt.SourceSettings[sid] = cur
					}
				}
			} else {
				warnings = append(warnings, "解析 config.json 失败: "+err.Error())
			}
		}
	}
	if fileRoot != "" {
		rt.WorkDir = fileRoot
	}
	return rt, hasAny, warnings
}

func parseDotEnv(raw string) map[string]string {
	out := make(map[string]string)
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k = strings.TrimSpace(k)
		v = strings.Trim(strings.TrimSpace(v), `"'`)
		out[k] = v
	}
	return out
}

func analyzeLegacyPlan(records []legacyRecord, songs []decodedSongEntry, assets []legacyAsset, aliases []legacyPathAlias, hasCfg bool) (ImportCounts, bool, []string) {
	var c ImportCounts
	var warnings []string
	blocks := false
	if hasCfg {
		c.Settings++
	}
	c.Assets = len(assets)
	c.AssetAliases = len(aliases)
	c.Tracks += len(songs)
	c.Variants += len(songs)
	favCount := 0
	for _, r := range records {
		switch r.Kind {
		case "config", "library_setting":
			c.Settings++
		case "track":
			c.Tracks++
		case "favorite":
			favCount++
		case "collection":
			c.Collections++
			var body map[string]any
			if json.Unmarshal([]byte(r.Body), &body) == nil {
				if tracks, ok := body["tracks"].([]any); ok {
					c.CollectionItems += len(tracks)
				}
			}
		case "download":
			c.Downloads++
		case "library_job":
			c.LibraryJobs++
		case "library_operation":
			c.FileOperations++
			var body map[string]any
			if json.Unmarshal([]byte(r.Body), &body) == nil {
				if st, _ := body["state"].(string); mapLegacyOpState(st) == "needs_attention" {
					blocks = true
					warnings = append(warnings, fmt.Sprintf("旧文件操作 %s 仍处于未完成状态 (%s)，需先在旧版恢复或人工处理后再切换写模式", r.ID, st))
				}
			}
		default:
			if strings.HasPrefix(r.Kind, "bot_") || strings.HasPrefix(r.Kind, "ai_") {
				c.DurableRecords++
			}
		}
	}
	if favCount > 0 {
		c.Collections++
		c.CollectionItems += favCount
	}
	return c, blocks, warnings
}

func persistLegacyImport(
	ctx context.Context,
	st *store.Store,
	report ImportReport,
	opts ImportOptions,
	rtCfg config.RuntimeConfig,
	hasRuntimeCfg bool,
	records []legacyRecord,
	songs []decodedSongEntry,
	legAssets []legacyAsset,
	legAliases []legacyPathAlias,
) error {
	now := time.Now().UTC().UnixMilli()
	repBytes, _ := json.Marshal(report)
	return st.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO import_runs(id, source_fingerprint, state, report_json, created_at, completed_at)
			VALUES(?, ?, 'running', ?, ?, NULL)
		`, report.ImportID, report.SourceFingerprint, string(repBytes), now); err != nil {
			return err
		}

		for _, r := range records {
			body := r.Body
			if !json.Valid([]byte(body)) {
				encoded, _ := json.Marshal(map[string]string{"raw": body})
				body = string(encoded)
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO legacy_records(kind, legacy_id, body_json, source_updated, import_id)
				VALUES(?, ?, ?, ?, ?)
				ON CONFLICT(kind, legacy_id) DO NOTHING
			`, r.Kind, r.ID, body, r.Updated, report.ImportID); err != nil {
				return err
			}
		}

		if hasRuntimeCfg {
			cfgBytes, _ := json.Marshal(rtCfg)
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO settings(namespace, body_json, revision, updated_at)
				VALUES(?, ?, 1, ?)
				ON CONFLICT(namespace) DO UPDATE SET body_json = excluded.body_json, revision = settings.revision + 1, updated_at = excluded.updated_at
			`, store.NamespaceRuntimeConfig, string(cfgBytes), now); err != nil {
				return err
			}
		}

		defaultRootID := "legacy-root"
		rootPath := opts.FileRoot
		if rootPath == "" {
			rootPath = rtCfg.WorkDir
		}
		if rootPath == "" {
			rootPath = "melokeep_outputs"
		}
		if abs, err := filepath.Abs(rootPath); err == nil {
			rootPath = abs
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO roots(id, canonical_path, path_case_mode, capabilities_json, enabled, scan_generation, healthy, last_error, updated_at)
			VALUES(?, ?, 'insensitive', '{"legacy":true}', 1, 1, 1, '', ?)
			ON CONFLICT(canonical_path) DO NOTHING
		`, defaultRootID, rootPath, now); err != nil {
			return err
		}
		var actualRootID string
		if err := tx.QueryRowContext(ctx, `SELECT id FROM roots WHERE canonical_path = ?`, rootPath).Scan(&actualRootID); err != nil {
			return err
		}

		assetIDs := make(map[string]bool, len(legAssets))
		validPublicRefs := make(map[string]bool, len(legAssets)+len(songs))
		for _, a := range legAssets {
			rel := a.Path
			if r, err := filepath.Rel(rootPath, a.Path); err == nil && !strings.HasPrefix(r, "..") {
				rel = r
			}
			rel = filepath.ToSlash(rel)
			folder := filepath.ToSlash(filepath.Dir(rel))
			if folder == "." {
				folder = ""
			}
			pathKey := strings.ToLower(rel)
			name := filepath.Base(rel)
			locks := a.Locks
			if !json.Valid([]byte(locks)) {
				locks = "[]"
			}
			origin := a.Source
			if !json.Valid([]byte(origin)) {
				origin = "{}"
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO assets(
					id, root_id, rel_path, path_key, folder, name, status, editable,
					size, mtime_ns, revision, title, artist, album, duration_ms,
					metadata_json, locks_json, origin_json, indexed_generation, updated_at
				) VALUES(?, ?, ?, ?, ?, ?, 'active', 1, 0, 0, 1, ?, '', '', 0, '{}', ?, ?, 1, ?)
				ON CONFLICT(id) DO NOTHING
			`, a.ID, actualRootID, rel, pathKey, folder, name, strings.TrimSuffix(name, filepath.Ext(name)), locks, origin, now); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO public_refs(public_id, kind, target_id, source, source_id, quality_hint, revision, created_at)
				VALUES(?, 'asset', ?, 'local', ?, '', 1, ?)
				ON CONFLICT(public_id) DO NOTHING
			`, "asset:"+a.ID, a.ID, a.ID, now); err != nil {
				return err
			}
			assetIDs[a.ID] = true
			validPublicRefs["asset:"+a.ID] = true
		}
		for _, al := range legAliases {
			if !assetIDs[al.AssetID] {
				continue
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO asset_aliases(alias_token, asset_id) VALUES(?, ?)
				ON CONFLICT(alias_token) DO NOTHING
			`, al.Token, al.AssetID); err != nil {
				return err
			}
		}

		trackPublicMap := make(map[string]string)
		insertLegacySong := func(pubID string, songMap map[string]any, metaJSON string) (string, error) {
			source := strVal(songMap, "source")
			locator := strVal(songMap, "identifier")
			if locator == "" {
				locator = pubID
			}
			if source == "" {
				source = "legacy"
			}
			title := strVal(songMap, "song_name")
			artist := strVal(songMap, "singers")
			album := strVal(songMap, "album")
			durMS := int64(floatVal(songMap, "duration_s") * 1000)
			if metaJSON == "" {
				metaJSON = "{}"
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO provider_tracks(id, source, locator, root_source, title, artist, album, duration_ms, metadata_json, provider_data_json, revision, updated_at)
				VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, '{"legacy_song":true}', 1, ?)
				ON CONFLICT(source, locator) DO UPDATE SET
					metadata_json = CASE WHEN excluded.metadata_json != '{}' THEN excluded.metadata_json ELSE provider_tracks.metadata_json END,
					updated_at = excluded.updated_at
			`, pubID, source, locator, strVal(songMap, "root_source"), title, artist, album, durMS, metaJSON, now); err != nil {
				return "", err
			}
			var actualTrackID string
			if err := tx.QueryRowContext(ctx, `SELECT id FROM provider_tracks WHERE source = ? AND locator = ?`, source, locator).Scan(&actualTrackID); err != nil {
				return "", err
			}
			ext := strVal(songMap, "ext")
			br := int64(floatVal(songMap, "bitrate"))
			var brArg any
			if br > 0 {
				brArg = br
			}
			var szArg any
			if sz := int64(floatVal(songMap, "file_size_bytes")); sz > 0 {
				szArg = sz
			}
			varKey := fmt.Sprintf("legacy:%s:%v", ext, br)
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO media_variants(id, track_id, variant_key, upstream_id, label, codec, container, bitrate, lossless, availability, evidence, protocol, size_bytes, duration_ms, preview_only, metadata_json, updated_at)
				VALUES(?, ?, ?, ?, ?, ?, ?, ?, 0, 'legacy_needs_resolve', 'legacy_song', ?, ?, ?, 0, '{}', ?)
				ON CONFLICT(track_id, variant_key) DO NOTHING
			`, model.NewUUID(), actualTrackID, varKey, locator, ext, ext, ext, brArg, strVal(songMap, "protocol"), szArg, durMS, now); err != nil {
				return "", err
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO public_refs(public_id, kind, target_id, source, source_id, quality_hint, revision, created_at)
				VALUES(?, 'track', ?, ?, ?, ?, 1, ?)
				ON CONFLICT(public_id) DO NOTHING
			`, pubID, actualTrackID, source, locator, ext, now); err != nil {
				return "", err
			}
			trackPublicMap[pubID] = actualTrackID
			validPublicRefs[pubID] = true
			return actualTrackID, nil
		}
		for _, s := range songs {
			pubID := strings.TrimSuffix(filepath.Base(s.File), ".song")
			metaJSON := "{}"
			if epRaw, ok := s.Data["episodes"].([]any); ok && len(epRaw) > 0 {
				epIDs := make([]string, 0, len(epRaw))
				for idx, rawEp := range epRaw {
					epMap, ok := rawEp.(map[string]any)
					if !ok {
						continue
					}
					epPubID := fmt.Sprintf("%s_ep_%d", pubID, idx+1)
					if _, err := insertLegacySong(epPubID, epMap, "{}"); err != nil {
						return err
					}
					epIDs = append(epIDs, epPubID)
				}
				if len(epIDs) > 0 {
					if rawMeta, mErr := json.Marshal(map[string]any{
						"episodes":    len(epIDs),
						"episode_ids": epIDs,
					}); mErr == nil {
						metaJSON = string(rawMeta)
					}
				}
			}
			if _, err := insertLegacySong(pubID, s.Data, metaJSON); err != nil {
				return err
			}
		}

		for _, r := range records {
			if r.Kind != "track" {
				continue
			}
			var m map[string]any
			if json.Unmarshal([]byte(r.Body), &m) != nil {
				continue
			}
			trackMap := m
			if inner, ok := m["track"].(map[string]any); ok {
				trackMap = inner
			}
			source := strVal(trackMap, "source")
			locator := strVal(trackMap, "source_id")
			if source == "" {
				source = "legacy"
			}
			if locator == "" {
				locator = r.ID
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO provider_tracks(id, source, locator, root_source, title, artist, album, duration_ms, metadata_json, provider_data_json, revision, updated_at)
				VALUES(?, ?, ?, ?, ?, ?, ?, ?, '{}', '{"legacy_record":true}', 1, ?)
				ON CONFLICT(source, locator) DO NOTHING
			`, r.ID, source, locator, strVal(trackMap, "root_source"), strVal(trackMap, "title"), strVal(trackMap, "artist"), strVal(trackMap, "album"), int64(floatVal(trackMap, "duration")*1000), now); err != nil {
				return err
			}
			var actualTrackID string
			if err := tx.QueryRowContext(ctx, `SELECT id FROM provider_tracks WHERE source = ? AND locator = ?`, source, locator).Scan(&actualTrackID); err == nil {
				_, _ = tx.ExecContext(ctx, `
					INSERT INTO public_refs(public_id, kind, target_id, source, source_id, quality_hint, revision, created_at)
					VALUES(?, 'track', ?, ?, ?, '', 1, ?)
					ON CONFLICT(public_id) DO NOTHING
				`, r.ID, actualTrackID, source, locator, now)
				trackPublicMap[r.ID] = actualTrackID
				validPublicRefs[r.ID] = true
			}
		}

		var favRefs []string
		for _, r := range records {
			switch r.Kind {
			case "library_setting":
				ns := "library_setting:" + r.ID
				if r.ID == "navidrome" {
					ns = store.NamespaceNavidrome
				} else if r.ID == "config" {
					ns = store.NamespaceLibraryConfig
				}
				_, _ = tx.ExecContext(ctx, `
					INSERT INTO settings(namespace, body_json, revision, updated_at)
					VALUES(?, ?, 1, ?)
					ON CONFLICT(namespace) DO NOTHING
				`, ns, r.Body, now)
			case "favorite":
				if validPublicRefs[r.ID] {
					favRefs = append(favRefs, r.ID)
				}
			case "collection":
				var col map[string]any
				if json.Unmarshal([]byte(r.Body), &col) != nil {
					continue
				}
				name := strVal(col, "name")
				if name == "" {
					name = r.ID
				}
				_, _ = tx.ExecContext(ctx, `
					INSERT INTO collections(id, name, kind, owner_key, source, source_url, detail, task_id, revision, created_at, updated_at)
					VALUES(?, ?, 'playlist', 'web_admin:legacy', ?, ?, ?, ?, 1, ?, ?)
					ON CONFLICT(id) DO NOTHING
				`, r.ID, name, strVal(col, "source"), strVal(col, "source_url"), strVal(col, "detail"), strVal(col, "task_id"), now, now)
				if items, ok := col["tracks"].([]any); ok {
					pos := 0
					for _, item := range items {
						tm, ok := item.(map[string]any)
						if !ok {
							continue
						}
						tid := strVal(tm, "id")
						if !validPublicRefs[tid] {
							continue
						}
						_, _ = tx.ExecContext(ctx, `
							INSERT INTO collection_items(collection_id, position, public_ref, added_at)
							VALUES(?, ?, ?, ?) ON CONFLICT DO NOTHING
						`, r.ID, pos, tid, now)
						pos++
					}
				}
			case "download":
				var dl map[string]any
				if json.Unmarshal([]byte(r.Body), &dl) != nil {
					continue
				}
				stVal := strVal(dl, "status")
				switch stVal {
				case "queued", "resolving", "downloading", "enriching":
					stVal = "paused"
				case "complete", "paused", "cancelled", "failed":
				default:
					stVal = "paused"
				}
				pubID := r.ID
				if tr, ok := dl["track"].(map[string]any); ok {
					if tid := strVal(tr, "id"); tid != "" {
						pubID = tid
					}
				}
				_, _ = tx.ExecContext(ctx, `
					INSERT INTO downloads(
						id, public_id, quality_id, quality_label, download_source, owner_key, dedupe_key, intent_scope,
						state, attempt, bytes_done, detail, revision, error_json, settings_json, created_at, updated_at
					) VALUES(?, ?, ?, ?, ?, 'web_admin:legacy', ?, ?, ?, 0, ?, ?, 1, '{}', '{}', ?, ?)
					ON CONFLICT(id) DO NOTHING
				`, r.ID, pubID, strVal(dl, "quality_id"), strVal(dl, "quality_label"), strVal(dl, "download_source"),
					"legacy:"+r.ID, "legacy:"+r.ID, stVal, int64(floatVal(dl, "bytes")), strVal(dl, "detail"), now, now)
			case "library_job":
				var job map[string]any
				if json.Unmarshal([]byte(r.Body), &job) != nil {
					continue
				}
				jst := mapLegacyJobStatus(strVal(job, "status"))
				_, _ = tx.ExecContext(ctx, `
					INSERT INTO library_jobs(
						id, action, state, settings_json, options_json, warnings_json, detail, total, processed, revision, owner_key, created_at, updated_at
					) VALUES(?, ?, ?, '{}', '{}', '[]', ?, ?, ?, 1, 'web_admin:legacy', ?, ?)
					ON CONFLICT(id) DO NOTHING
				`, r.ID, strVal(job, "action"), jst, strVal(job, "detail"), int(floatVal(job, "total")), int(floatVal(job, "processed")), now, now)
			case "library_operation":
				var op map[string]any
				if json.Unmarshal([]byte(r.Body), &op) != nil {
					continue
				}
				ost := mapLegacyOpState(strVal(op, "state"))
				kind := strVal(op, "kind")
				if kind == "" {
					kind = "edit"
				}
				var assetArg any
				if aid := strVal(op, "asset_id"); assetIDs[aid] {
					assetArg = aid
				}
				_, _ = tx.ExecContext(ctx, `
					INSERT INTO file_operations(id, asset_id, kind, state, before_asset_json, after_asset_json, error_json, created_at, updated_at)
					VALUES(?, ?, ?, ?, ?, '{}', '{}', ?, ?)
					ON CONFLICT(id) DO NOTHING
				`, r.ID, assetArg, kind, ost, r.Body, now, now)
			default:
				if strings.HasPrefix(r.Kind, "bot_") || strings.HasPrefix(r.Kind, "ai_") {
					_, _ = tx.ExecContext(ctx, `
						INSERT INTO durable_records(namespace, id, body_json, revision, updated_at)
						VALUES(?, ?, ?, 1, ?)
						ON CONFLICT(namespace, id) DO NOTHING
					`, r.Kind, r.ID, r.Body, now)
				}
			}
		}

		if len(favRefs) > 0 {
			favColID := "legacy-favorites"
			_, _ = tx.ExecContext(ctx, `
				INSERT INTO collections(id, name, kind, owner_key, source, source_url, detail, task_id, revision, created_at, updated_at)
				VALUES(?, '收藏', 'favorites', 'web_admin:legacy', 'local', '', '', '', 1, ?, ?)
				ON CONFLICT DO NOTHING
			`, favColID, now, now)
			for idx, ref := range favRefs {
				_, _ = tx.ExecContext(ctx, `
					INSERT INTO collection_items(collection_id, position, public_ref, added_at)
					VALUES(?, ?, ?, ?) ON CONFLICT DO NOTHING
				`, favColID, idx, ref, now)
			}
		}

		_, err := tx.ExecContext(ctx, `
			UPDATE import_runs SET state = 'completed', completed_at = ? WHERE id = ?
		`, time.Now().UTC().UnixMilli(), report.ImportID)
		return err
	})
}

func mapLegacyJobStatus(st string) string {
	switch st {
	case "queued", "scanning", "preview_ready", "needs_review", "applying", "complete", "partial", "paused", "cancelled", "failed", "needs_attention":
		return st
	case "organizing", "preparing":
		return "paused"
	case "ready":
		return "preview_ready"
	default:
		return "paused"
	}
}

func mapLegacyOpState(st string) string {
	switch st {
	case "complete", "cleaned":
		return "cleaned"
	case "preparing", "publishing":
		return "needs_attention"
	case "rolled_back":
		return "rolled_back"
	default:
		return "needs_attention"
	}
}

func strVal(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	if s, ok := m[key].(string); ok {
		return s
	}
	return ""
}

func floatVal(m map[string]any, key string) float64 {
	if m == nil {
		return 0
	}
	if f, ok := m[key].(float64); ok {
		return f
	}
	return 0
}
