package library

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const (
	defaultNeteaseArtistSearchBase = "https://music.163.com"
	defaultDeezerArtistSearchBase  = "https://api.deezer.com"
	defaultArtistMinImageBytes     = 1500
	defaultArtistMaxImageBytes     = 10 * 1024 * 1024
	defaultArtistNegativeTTL       = 10 * time.Minute
	defaultArtistMemoryCacheTTL    = 15 * time.Minute
	defaultArtistDirIndexTTL       = 30 * time.Second
	maxArtistMemoryCacheEntries    = 256
	artistFetchTimeout             = 6 * time.Second
	artistLiveFetchTimeout         = 3500 * time.Millisecond
	artistPrefetchQueueSize        = 128
	artistPrefetchWorkers          = 2
	artistLiveConcurrency          = 2
)

var (
	artistCollabSplitRe = regexp.MustCompile(`(?i)\s*(?:[、,，;；/＆&]|(?:\s+(?:feat\.?|ft\.?|featuring|with)\s+))\s*`)
	artistTrailingFeat  = regexp.MustCompile(`(?i)\s*[\(\[（【]\s*(?:feat\.?|ft\.?|featuring)\b[^\)\]）】]*[\)\]）】]\s*$`)
	multiSpaceRe        = regexp.MustCompile(`\s+`)

	ignoredArtistNames = map[string]bool{
		"":                true,
		"未知":              true,
		"未知歌手":            true,
		"未知艺术家":           true,
		"unknown":         true,
		"unknown artist":  true,
		"<unknown>":       true,
		"[unknown]":       true,
		"various":         true,
		"various artists": true,
		"various artist":  true,
		"va":              true,
		"v.a.":            true,
		"群星":              true,
		"合辑":              true,
		"none":            true,
		"null":            true,
	}

	neteasePlaceholderIDs = []string{
		"5639395138885805",
		"109951165647004069",
	}

	deezerPlaceholderMarkers = []string{
		"/images/artist//",
		"/images/user//",
		"d41d8cd98f00b204e9800998ecf8427e",
	}

	artistArtworkFileNames = []string{
		"artist.jpg",
		"artist.jpeg",
		"artist.png",
		"artist.webp",
	}
)

// ArtistArtworkOptions configures base URLs, thresholds, and cache TTLs for artist avatar scraping.
type ArtistArtworkOptions struct {
	NeteaseBaseURL  string
	DeezerBaseURL   string
	NegativeTTL     time.Duration
	MemoryCacheTTL  time.Duration
	DirIndexTTL     time.Duration
	MinImageBytes   int
	MaxImageBytes   int
	DisablePrefetch bool
}

// ArtistArtworkLookup describes the resolved candidate and bytes for an artist.
type ArtistArtworkLookup struct {
	Artist   string
	Source   string
	ImageURL string
	MIME     string
	Data     []byte
	Found    bool
	Err      error
}

type cachedArtistAvatar struct {
	data      []byte
	mime      string
	source    string
	imageURL  string
	expiresAt time.Time
}

type inflightArtistLookup struct {
	done          chan struct{}
	res           ArtistArtworkLookup
	leaderAborted bool
}

type artistPrefetchTask struct {
	snap       config.Snapshot
	artistName string
	key        string
}

// ArtistArtworkFetcher scrapes, validates, caches, and atomically persists artist portraits.
type ArtistArtworkFetcher struct {
	pool   *transport.Pool
	store  *store.Store
	coords *files.Coordinator

	mu       sync.Mutex
	opts     ArtistArtworkOptions
	negCache map[string]time.Time
	memCache map[string]cachedArtistAvatar
	inflight map[string]*inflightArtistLookup
	dirLocks map[string]*sync.Mutex

	dirIndexBuildMu   sync.Mutex
	dirIndexMu        sync.Mutex
	dirIndexExpiresAt time.Time
	dirIndexBaseDirs  map[string]bool
	dirIndexExact     map[string][]string
	dirIndexNorm      map[string][]string
	dirIndexGen       uint64

	prefetchMu      sync.Mutex
	disablePrefetch bool
	closed          bool
	prefetchCh      chan artistPrefetchTask
	prefetchQueued  map[string]bool
	liveSem         chan struct{}
	prefetchOnce    sync.Once
	prefetchCtx     context.Context
	prefetchCancel  context.CancelFunc
	prefetchWG      sync.WaitGroup
}

// NewArtistArtworkFetcher constructs an ArtistArtworkFetcher using the given transport pool and stores.
func NewArtistArtworkFetcher(pool *transport.Pool, st *store.Store, coords *files.Coordinator, opts ArtistArtworkOptions) *ArtistArtworkFetcher {
	normOpts := normalizeArtistArtworkOptions(opts)
	prefetchCtx, prefetchCancel := context.WithCancel(context.Background())
	return &ArtistArtworkFetcher{
		pool:            pool,
		store:           st,
		coords:          coords,
		opts:            normOpts,
		disablePrefetch: normOpts.DisablePrefetch,
		negCache:        make(map[string]time.Time),
		memCache:        make(map[string]cachedArtistAvatar),
		inflight:        make(map[string]*inflightArtistLookup),
		dirLocks:        make(map[string]*sync.Mutex),
		prefetchCh:      make(chan artistPrefetchTask, artistPrefetchQueueSize),
		prefetchQueued:  make(map[string]bool),
		liveSem:         make(chan struct{}, artistLiveConcurrency),
		prefetchCtx:     prefetchCtx,
		prefetchCancel:  prefetchCancel,
	}
}

func normalizeArtistArtworkOptions(opts ArtistArtworkOptions) ArtistArtworkOptions {
	if strings.TrimSpace(opts.NeteaseBaseURL) == "" {
		opts.NeteaseBaseURL = defaultNeteaseArtistSearchBase
	}
	opts.NeteaseBaseURL = strings.TrimRight(strings.TrimSpace(opts.NeteaseBaseURL), "/")
	if strings.TrimSpace(opts.DeezerBaseURL) == "" {
		opts.DeezerBaseURL = defaultDeezerArtistSearchBase
	}
	opts.DeezerBaseURL = strings.TrimRight(strings.TrimSpace(opts.DeezerBaseURL), "/")
	if opts.NegativeTTL <= 0 {
		opts.NegativeTTL = defaultArtistNegativeTTL
	}
	if opts.MemoryCacheTTL <= 0 {
		opts.MemoryCacheTTL = defaultArtistMemoryCacheTTL
	}
	if opts.DirIndexTTL <= 0 {
		opts.DirIndexTTL = defaultArtistDirIndexTTL
	}
	if opts.MinImageBytes <= 0 {
		opts.MinImageBytes = defaultArtistMinImageBytes
	}
	if opts.MaxImageBytes <= 0 {
		opts.MaxImageBytes = defaultArtistMaxImageBytes
	}
	return opts
}

// SetOptions updates fetcher endpoints and cache tuning (primarily for tests).
func (f *ArtistArtworkFetcher) SetOptions(opts ArtistArtworkOptions) {
	if f == nil {
		return
	}
	normOpts := normalizeArtistArtworkOptions(opts)
	f.mu.Lock()
	f.opts = normOpts
	clear(f.negCache)
	clear(f.memCache)
	f.mu.Unlock()

	f.dirIndexMu.Lock()
	f.dirIndexExpiresAt = time.Time{}
	f.dirIndexBaseDirs = nil
	f.dirIndexExact = nil
	f.dirIndexNorm = nil
	f.dirIndexGen++
	f.dirIndexMu.Unlock()

	f.prefetchMu.Lock()
	f.disablePrefetch = normOpts.DisablePrefetch
	f.prefetchMu.Unlock()
}

// Close cancels background prefetch workers and waits for clean exit without closing prefetchCh.
func (f *ArtistArtworkFetcher) Close() error {
	if f == nil {
		return nil
	}
	f.prefetchMu.Lock()
	if f.closed {
		f.prefetchMu.Unlock()
		return nil
	}
	f.closed = true
	cancel := f.prefetchCancel
	f.prefetchMu.Unlock()

	if cancel != nil {
		cancel()
	}
	f.prefetchWG.Wait()
	return nil
}

func (f *ArtistArtworkFetcher) options() ArtistArtworkOptions {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.opts
}

func (f *ArtistArtworkFetcher) hasPool() bool {
	return f != nil && f.pool != nil
}

func (f *ArtistArtworkFetcher) lockForDir(dir string) func() {
	key := filepath.Clean(dir)
	if runtime.GOOS == "windows" {
		key = strings.ToLower(key)
	}
	f.mu.Lock()
	dl, ok := f.dirLocks[key]
	if !ok {
		dl = &sync.Mutex{}
		f.dirLocks[key] = dl
	}
	f.mu.Unlock()
	dl.Lock()
	return dl.Unlock
}

// IsIgnoredArtist reports whether raw represents an empty or placeholder artist name.
func IsIgnoredArtist(raw string) bool {
	cleaned := collapseWhitespace(raw)
	return ignoredArtistNames[strings.ToLower(cleaned)]
}

// PrimaryArtistName extracts the canonical primary artist name, returning "" for ignored placeholders.
func PrimaryArtistName(raw string) string {
	cands := normalizeArtistCandidates(raw)
	if len(cands) == 0 {
		return ""
	}
	return cands[0]
}

// normalizeArtistCandidates splits collaboration strings (e.g. "A / B", "A & B", "A, B", "A、B")
// prioritizing the primary artist while preserving exact full-string backups.
func normalizeArtistCandidates(raw string) []string {
	cleaned := collapseWhitespace(raw)
	if cleaned == "" || ignoredArtistNames[strings.ToLower(cleaned)] {
		return nil
	}
	stripped := strings.TrimSpace(artistTrailingFeat.ReplaceAllString(cleaned, ""))
	parts := artistCollabSplitRe.Split(stripped, -1)

	seen := make(map[string]bool, len(parts)+2)
	out := make([]string, 0, 3)
	addCand := func(s string) {
		c := collapseWhitespace(s)
		if c == "" || ignoredArtistNames[strings.ToLower(c)] {
			return
		}
		k := strings.ToLower(c)
		if seen[k] {
			return
		}
		seen[k] = true
		out = append(out, c)
	}

	for _, part := range parts {
		if c := collapseWhitespace(part); c != "" && !ignoredArtistNames[strings.ToLower(c)] {
			addCand(c)
			break
		}
	}
	if stripped != "" && !ignoredArtistNames[strings.ToLower(stripped)] {
		addCand(stripped)
	}
	addCand(cleaned)
	return out
}

func collapseWhitespace(s string) string {
	return strings.TrimSpace(multiSpaceRe.ReplaceAllString(s, " "))
}

func artistCacheKey(artist string) string {
	primary := PrimaryArtistName(artist)
	if primary == "" {
		return ""
	}
	return strings.ToLower(primary)
}

func artistDiskCachePath(dataDir, artist string) string {
	key := artistCacheKey(artist)
	return artistDiskCachePathForKey(dataDir, key)
}

func artistDiskCachePathForKey(dataDir, key string) string {
	dir := strings.TrimSpace(dataDir)
	// Avoid creating stray ./data directories during unit tests using DefaultRuntimeConfig().
	if dir == "" || (!filepath.IsAbs(dir) && dir == "data") {
		return ""
	}
	key = strings.ToLower(collapseWhitespace(key))
	if key == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(key))
	return filepath.Join(dir, "artist-avatars", hex.EncodeToString(sum[:])+".jpg")
}

// ResolveArtistDir locates `<libRoot>/<Artist>` when audioPath sits inside `<libRoot>/<Artist>/<Track>`
// or `<libRoot>/<Artist>/<Album>/<Track>`. It strictly prevents escaping libRoot or returning libRoot itself.
func ResolveArtistDir(libRoot, audioPath, artistName string) string {
	libRoot = strings.TrimSpace(libRoot)
	audioPath = strings.TrimSpace(audioPath)
	if libRoot == "" || audioPath == "" {
		return ""
	}
	cands := normalizeArtistCandidates(artistName)
	if len(cands) == 0 {
		return ""
	}

	absLib, err := filepath.Abs(libRoot)
	if err != nil {
		return ""
	}
	absLib = filepath.Clean(absLib)
	absAudio, err := filepath.Abs(audioPath)
	if err != nil {
		return ""
	}
	absAudio = filepath.Clean(absAudio)

	if st, lErr := os.Lstat(absLib); lErr == nil {
		if !st.IsDir() {
			return ""
		}
		if isLink, rErr := files.IsReparseOrSymlink(absLib, st); rErr != nil || isLink {
			return ""
		}
	}

	rel, err := filepath.Rel(absLib, absAudio)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return ""
	}
	parts := strings.Split(filepath.ToSlash(rel), "/")
	if len(parts) != 2 && len(parts) != 3 {
		return ""
	}
	for _, part := range parts {
		if part == "" || part == "." || part == ".." || files.InternalDirs[part] || strings.HasPrefix(part, ".melokeep-") {
			return ""
		}
	}

	firstSeg := parts[0]
	if !artistSegmentMatches(firstSeg, cands) {
		return ""
	}

	artistDir := filepath.Join(absLib, firstSeg)
	dirRel, err := filepath.Rel(absLib, artistDir)
	if err != nil || dirRel == "." || dirRel == ".." || strings.HasPrefix(dirRel, ".."+string(filepath.Separator)) || filepath.IsAbs(dirRel) {
		return ""
	}

	if st, sErr := os.Lstat(artistDir); sErr == nil {
		if !st.IsDir() {
			return ""
		}
		if isLink, rErr := files.IsReparseOrSymlink(artistDir, st); rErr != nil || isLink {
			return ""
		}
		if evalLib, e1 := filepath.EvalSymlinks(absLib); e1 == nil {
			if evalArtist, e2 := filepath.EvalSymlinks(artistDir); e2 == nil {
				if eRel, e3 := filepath.Rel(evalLib, evalArtist); e3 != nil || eRel == "." || eRel == ".." || strings.HasPrefix(eRel, ".."+string(filepath.Separator)) {
					return ""
				}
			}
		}
	}

	// Ensure intermediate directory (if <Artist>/<Album>/<Track>) is not a symlink either.
	cur := artistDir
	for _, seg := range parts[1 : len(parts)-1] {
		cur = filepath.Join(cur, seg)
		if st, sErr := os.Lstat(cur); sErr == nil {
			if isLink, rErr := files.IsReparseOrSymlink(cur, st); rErr != nil || isLink {
				return ""
			}
		}
	}

	return artistDir
}

func artistSegmentMatches(folderSeg string, artistCandidates []string) bool {
	folderSeg = strings.TrimSpace(folderSeg)
	if folderSeg == "" || ignoredArtistNames[strings.ToLower(folderSeg)] {
		return false
	}
	normFolder := NormalizedMeta(folderSeg)
	for _, cand := range artistCandidates {
		if strings.EqualFold(folderSeg, cand) {
			return true
		}
		safeCand := SafeSegment(cand, "")
		if safeCand != "" && strings.EqualFold(folderSeg, safeCand) {
			return true
		}
		normCand := NormalizedMeta(cand)
		if normFolder != "" && normCand != "" && normFolder == normCand {
			return true
		}
	}
	return false
}

func findExistingArtistArtwork(artistDir string) (string, string, bool) {
	if strings.TrimSpace(artistDir) == "" {
		return "", "", false
	}
	entries, err := os.ReadDir(artistDir)
	if err == nil {
		byLower := make(map[string]string, len(entries))
		for _, entry := range entries {
			if entry.IsDir() {
				continue
			}
			byLower[strings.ToLower(entry.Name())] = entry.Name()
		}
		for _, targetName := range artistArtworkFileNames {
			actualName, ok := byLower[targetName]
			if !ok {
				continue
			}
			p := filepath.Join(artistDir, actualName)
			st, stErr := os.Lstat(p)
			if stErr != nil || !st.Mode().IsRegular() || st.Size() <= 0 || st.Size() > defaultArtistMaxImageBytes {
				continue
			}
			if isLink, lErr := files.IsReparseOrSymlink(p, st); lErr != nil || isLink {
				continue
			}
			return p, imageMIMEFromFilename(actualName), true
		}
		return "", "", false
	}
	for _, targetName := range artistArtworkFileNames {
		p := filepath.Join(artistDir, targetName)
		st, stErr := os.Lstat(p)
		if stErr != nil || !st.Mode().IsRegular() || st.Size() <= 0 || st.Size() > defaultArtistMaxImageBytes {
			continue
		}
		if isLink, lErr := files.IsReparseOrSymlink(p, st); lErr != nil || isLink {
			continue
		}
		return p, imageMIMEFromFilename(targetName), true
	}
	return "", "", false
}

func imageMIMEFromFilename(name string) string {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	default:
		return "image/jpeg"
	}
}

func detectArtworkMIME(data []byte) string {
	if len(data) >= 8 && bytes.Equal(data[:8], []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}) {
		return "image/png"
	}
	if len(data) >= 3 && bytes.Equal(data[:3], []byte{0xff, 0xd8, 0xff}) {
		return "image/jpeg"
	}
	if len(data) >= 12 && bytes.Equal(data[:4], []byte("RIFF")) && bytes.Equal(data[8:12], []byte("WEBP")) {
		return "image/webp"
	}
	return ""
}

func writeFileAtomic(targetPath string, data []byte) error {
	dir := filepath.Dir(targetPath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmpFile, err := os.CreateTemp(dir, ".melokeep-art-*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmpFile.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tmpPath)
		}
	}()

	if _, err := tmpFile.Write(data); err != nil {
		_ = tmpFile.Close()
		return err
	}
	if err := tmpFile.Sync(); err != nil {
		_ = tmpFile.Close()
		return err
	}
	if err := tmpFile.Close(); err != nil {
		return err
	}
	if err := os.Chmod(tmpPath, 0o644); err != nil {
		// Ignore chmod failure on platforms with restricted ACLs.
	}
	if err := os.Rename(tmpPath, targetPath); err != nil {
		return err
	}
	cleanup = false
	_ = files.SyncDir(dir)
	return nil
}

func (f *ArtistArtworkFetcher) readDiskCache(snap config.Snapshot, artistName string) ([]byte, string, bool) {
	return f.readDiskCacheByPath(artistDiskCachePath(snap.Runtime.DataDir, artistName))
}

func (f *ArtistArtworkFetcher) readDiskCacheExact(snap config.Snapshot, rawName string) ([]byte, string, bool) {
	return f.readDiskCacheByPath(artistDiskCachePathForKey(snap.Runtime.DataDir, rawName))
}

func (f *ArtistArtworkFetcher) readDiskCacheByPath(cachePath string) ([]byte, string, bool) {
	if cachePath == "" {
		return nil, "", false
	}
	st, err := os.Lstat(cachePath)
	if err != nil || !st.Mode().IsRegular() || st.Size() < int64(f.options().MinImageBytes) || st.Size() > int64(f.options().MaxImageBytes) {
		return nil, "", false
	}
	raw, err := os.ReadFile(cachePath)
	if err != nil {
		return nil, "", false
	}
	if len(raw) < f.options().MinImageBytes || len(raw) > f.options().MaxImageBytes {
		return nil, "", false
	}
	mime := detectArtworkMIME(raw)
	if mime == "" {
		return nil, "", false
	}
	return raw, mime, true
}

func (f *ArtistArtworkFetcher) writeDiskCache(snap config.Snapshot, artistName string, data []byte) {
	cachePath := artistDiskCachePath(snap.Runtime.DataDir, artistName)
	if cachePath == "" || len(data) < f.options().MinImageBytes || len(data) > f.options().MaxImageBytes {
		return
	}
	if detectArtworkMIME(data) == "" {
		return
	}
	_ = writeFileAtomic(cachePath, data)
}

func (f *ArtistArtworkFetcher) getMemoryCache(key string) (ArtistArtworkLookup, bool) {
	if key == "" {
		return ArtistArtworkLookup{}, false
	}
	now := time.Now()
	f.mu.Lock()
	defer f.mu.Unlock()
	if exp, neg := f.negCache[key]; neg {
		if now.Before(exp) {
			return ArtistArtworkLookup{Found: false, Err: errors.New("歌手近期未命中有效写真")}, true
		}
		delete(f.negCache, key)
	}
	if entry, ok := f.memCache[key]; ok {
		if now.Before(entry.expiresAt) {
			copied := append([]byte(nil), entry.data...)
			return ArtistArtworkLookup{
				Artist:   key,
				Source:   entry.source,
				ImageURL: entry.imageURL,
				MIME:     entry.mime,
				Data:     copied,
				Found:    true,
			}, true
		}
		delete(f.memCache, key)
	}
	return ArtistArtworkLookup{}, false
}

func (f *ArtistArtworkFetcher) putPositiveCache(key string, res ArtistArtworkLookup) {
	if key == "" || !res.Found || len(res.Data) == 0 {
		return
	}
	opts := f.options()
	now := time.Now()
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.negCache, key)
	if len(f.memCache) >= maxArtistMemoryCacheEntries {
		var oldestKey string
		var oldestExp time.Time
		for k, v := range f.memCache {
			if now.After(v.expiresAt) {
				delete(f.memCache, k)
				continue
			}
			if oldestKey == "" || v.expiresAt.Before(oldestExp) {
				oldestKey = k
				oldestExp = v.expiresAt
			}
		}
		if len(f.memCache) >= maxArtistMemoryCacheEntries && oldestKey != "" {
			delete(f.memCache, oldestKey)
		}
	}
	f.memCache[key] = cachedArtistAvatar{
		data:      append([]byte(nil), res.Data...),
		mime:      res.MIME,
		source:    res.Source,
		imageURL:  res.ImageURL,
		expiresAt: now.Add(opts.MemoryCacheTTL),
	}
}

func (f *ArtistArtworkFetcher) putNegativeCache(key string) {
	if key == "" {
		return
	}
	opts := f.options()
	now := time.Now()
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.negCache) >= maxArtistMemoryCacheEntries {
		for k, exp := range f.negCache {
			if !now.Before(exp) {
				delete(f.negCache, k)
			}
		}
	}
	f.negCache[key] = now.Add(opts.NegativeTTL)
}

// LookupArtistArtwork resolves artist portrait bytes and candidate metadata using disk cache,
// in-memory dedupe/negative cache, and multi-source scraping (Netease -> Deezer).
func (f *ArtistArtworkFetcher) LookupArtistArtwork(ctx context.Context, snap config.Snapshot, artistName string) ArtistArtworkLookup {
	if f == nil {
		return ArtistArtworkLookup{Err: errors.New("歌手头像抓取器未初始化")}
	}
	cands := normalizeArtistCandidates(artistName)
	if len(cands) == 0 {
		return ArtistArtworkLookup{Err: errors.New("歌手名为空或为占位符")}
	}
	primary := cands[0]
	key := strings.ToLower(primary)

	if raw, mime, ok := f.readDiskCache(snap, primary); ok {
		res := ArtistArtworkLookup{
			Artist: primary,
			Source: "cache",
			MIME:   mime,
			Data:   raw,
			Found:  true,
		}
		f.putPositiveCache(key, res)
		return res
	}
	if cached, hit := f.getMemoryCache(key); hit {
		if cached.Found {
			cached.Artist = primary
			f.writeDiskCache(snap, primary, cached.Data)
		}
		return cached
	}

	for attempt := 0; attempt < 2; attempt++ {
		f.mu.Lock()
		if call, ok := f.inflight[key]; ok {
			f.mu.Unlock()
			select {
			case <-call.done:
				if call.leaderAborted && ctx.Err() == nil && attempt == 0 {
					continue
				}
				res := call.res
				if res.Found {
					res.Data = append([]byte(nil), res.Data...)
					f.writeDiskCache(snap, primary, res.Data)
				}
				return res
			case <-ctx.Done():
				return ArtistArtworkLookup{Artist: primary, Err: ctx.Err()}
			}
		}
		call := &inflightArtistLookup{done: make(chan struct{})}
		f.inflight[key] = call
		f.mu.Unlock()

		var res ArtistArtworkLookup
		func() {
			defer func() {
				call.res = res
				call.leaderAborted = ctx.Err() != nil
				close(call.done)
				f.mu.Lock()
				if f.inflight[key] == call {
					delete(f.inflight, key)
				}
				f.mu.Unlock()
			}()

			if !f.hasPool() {
				res = ArtistArtworkLookup{Artist: primary, Err: errors.New("网络传输池未初始化")}
				return
			}

			// Keep ctx respected so Shutdown/Cancel or short live timeouts immediately abort in-flight requests.
			fetchCtx, cancel := context.WithTimeout(ctx, artistFetchTimeout*2)
			defer cancel()

			var lastErr error
			allCleanMiss := true
			for _, query := range cands {
				src, imgURL, data, mime, cleanMiss, err := f.scrapeAcrossSources(fetchCtx, snap, query)
				if err == nil && len(data) > 0 {
					res = ArtistArtworkLookup{
						Artist:   primary,
						Source:   src,
						ImageURL: imgURL,
						MIME:     mime,
						Data:     data,
						Found:    true,
					}
					f.writeDiskCache(snap, primary, data)
					f.putPositiveCache(key, res)
					return
				}
				if !cleanMiss {
					allCleanMiss = false
				}
				if err != nil {
					lastErr = err
				}
			}

			if allCleanMiss && ctx.Err() == nil && fetchCtx.Err() == nil {
				f.putNegativeCache(key)
			}
			if lastErr == nil {
				lastErr = errors.New("未匹配到有效歌手写真")
			}
			res = ArtistArtworkLookup{Artist: primary, Found: false, Err: lastErr}
		}()
		return res
	}
	return ArtistArtworkLookup{Artist: primary, Found: false, Err: errors.New("未匹配到有效歌手写真")}
}

func (f *ArtistArtworkFetcher) scrapeAcrossSources(ctx context.Context, snap config.Snapshot, artistQuery string) (source, imageURL string, data []byte, mime string, cleanMiss bool, err error) {
	cleanMiss = true

	// 1. Netease Cloud Music artist search
	neteaseURLs, nErr := f.searchNeteaseArtistImages(ctx, snap, artistQuery)
	if nErr != nil {
		cleanMiss = false
		err = nErr
	}
	for _, candidateURL := range neteaseURLs {
		imgData, imgMIME, dErr := f.FetchAndValidateImage(ctx, snap, "netease", candidateURL, map[string]string{
			"Referer": "https://music.163.com/",
		})
		if dErr == nil {
			return "netease", candidateURL, imgData, imgMIME, true, nil
		}
		err = dErr
	}

	// 2. Deezer Public API
	deezerURLs, dErr := f.searchDeezerArtistImages(ctx, snap, artistQuery)
	if dErr != nil {
		cleanMiss = false
		if err == nil {
			err = dErr
		}
	}
	for _, candidateURL := range deezerURLs {
		imgData, imgMIME, fetchErr := f.FetchAndValidateImage(ctx, snap, "deezer", candidateURL, nil)
		if fetchErr == nil {
			return "deezer", candidateURL, imgData, imgMIME, true, nil
		}
		err = fetchErr
	}

	return "", "", nil, "", cleanMiss, err
}

func (f *ArtistArtworkFetcher) searchNeteaseArtistImages(ctx context.Context, snap config.Snapshot, query string) ([]string, error) {
	opts := f.options()
	endpoint := opts.NeteaseBaseURL + "/api/search/get/web?" + url.Values{
		"type":  {"100"},
		"s":     {query},
		"limit": {"5"},
	}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", provider.DefaultUserAgent)
	req.Header.Set("Referer", "https://music.163.com/")
	req.Header.Set("Accept", "application/json, text/plain, */*")

	resp, err := f.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         scraperProxyURL(snap, "netease"),
		Timeout:          artistFetchTimeout,
		MaxResponseBytes: 2 << 20,
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("netease HTTP %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, err
	}
	var payload struct {
		Code   int `json:"code"`
		Result struct {
			Artists []struct {
				ID         int64    `json:"id"`
				Name       string   `json:"name"`
				Trans      string   `json:"trans"`
				TransNames []string `json:"transNames"`
				Alias      []string `json:"alias"`
				PicID      int64    `json:"picId"`
				PicURL     string   `json:"picUrl"`
				Img1v1URL  string   `json:"img1v1Url"`
			} `json:"artists"`
		} `json:"result"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return nil, err
	}
	if payload.Code != 0 && payload.Code != 200 {
		return nil, fmt.Errorf("netease code %d", payload.Code)
	}

	var ranked []rankedArtworkURLs
	for _, a := range payload.Result.Artists {
		names := make([]string, 0, 2+len(a.Alias)+len(a.TransNames))
		names = append(names, a.Name, a.Trans)
		names = append(names, a.TransNames...)
		names = append(names, a.Alias...)
		score := bestArtistMatchScore(query, names...)
		if score < 0.85 {
			continue
		}
		var candURLs []string
		for _, u := range []string{a.PicURL, a.Img1v1URL} {
			u = strings.TrimSpace(u)
			if u == "" || isNeteasePlaceholderURL(u) {
				continue
			}
			candURLs = append(candURLs, formatNeteaseImageURL(u))
		}
		if len(candURLs) > 0 {
			ranked = append(ranked, rankedArtworkURLs{score: score, urls: candURLs})
		}
	}
	return flattenBestRankedURLs(ranked), nil
}

func (f *ArtistArtworkFetcher) searchDeezerArtistImages(ctx context.Context, snap config.Snapshot, query string) ([]string, error) {
	opts := f.options()
	endpoint := opts.DeezerBaseURL + "/search/artist?" + url.Values{
		"q":     {query},
		"limit": {"5"},
	}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", provider.DefaultUserAgent)
	req.Header.Set("Accept", "application/json")

	resp, err := f.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         scraperProxyURL(snap, "deezer"),
		Timeout:          artistFetchTimeout,
		MaxResponseBytes: 2 << 20,
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("deezer HTTP %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, err
	}
	var payload struct {
		Data []struct {
			ID            int64  `json:"id"`
			Name          string `json:"name"`
			Picture       string `json:"picture"`
			PictureMedium string `json:"picture_medium"`
			PictureBig    string `json:"picture_big"`
			PictureXL     string `json:"picture_xl"`
		} `json:"data"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return nil, err
	}

	var ranked []rankedArtworkURLs
	for _, a := range payload.Data {
		score := bestArtistMatchScore(query, a.Name)
		if score < 0.85 {
			continue
		}
		var candURLs []string
		for _, u := range []string{a.PictureXL, a.PictureBig, a.PictureMedium, a.Picture} {
			u = strings.TrimSpace(u)
			if u == "" || isDeezerPlaceholderURL(u) {
				continue
			}
			candURLs = append(candURLs, u)
		}
		if len(candURLs) > 0 {
			ranked = append(ranked, rankedArtworkURLs{score: score, urls: candURLs})
		}
	}
	return flattenBestRankedURLs(ranked), nil
}

type rankedArtworkURLs struct {
	score float64
	urls  []string
}

func flattenBestRankedURLs(ranked []rankedArtworkURLs) []string {
	if len(ranked) == 0 {
		return nil
	}
	// Stable selection keeping highest scores first.
	for i := 0; i < len(ranked); i++ {
		for j := i + 1; j < len(ranked); j++ {
			if ranked[j].score > ranked[i].score {
				ranked[i], ranked[j] = ranked[j], ranked[i]
			}
		}
	}
	seen := make(map[string]bool)
	var out []string
	for _, item := range ranked {
		for _, u := range item.urls {
			if !seen[u] {
				seen[u] = true
				out = append(out, u)
			}
		}
	}
	return out
}

func bestArtistMatchScore(query string, candidates ...string) float64 {
	qTrim := collapseWhitespace(query)
	qNorm := NormalizedMeta(qTrim)
	if qTrim == "" || qNorm == "" {
		return 0
	}
	best := 0.0
	for i, cand := range candidates {
		cTrim := collapseWhitespace(cand)
		if cTrim == "" {
			continue
		}
		cNorm := NormalizedMeta(cTrim)
		if cNorm == "" {
			continue
		}
		var score float64
		switch {
		case strings.EqualFold(qTrim, cTrim) || qNorm == cNorm:
			score = 1.0
			if i > 0 {
				score = 0.98
			}
		default:
			score = similarityRatio(qNorm, cNorm)
			if score < 0.85 && utf8.RuneCountInString(qNorm) >= 2 && (strings.Contains(cNorm, qNorm) || strings.Contains(qNorm, cNorm)) {
				minLen := utf8.RuneCountInString(qNorm)
				maxLen := utf8.RuneCountInString(cNorm)
				if minLen > maxLen {
					minLen, maxLen = maxLen, minLen
				}
				if maxLen > 0 && float64(minLen)/float64(maxLen) >= 0.72 {
					score = 0.86
				}
			}
			if i > 0 && score > 0.02 {
				score -= 0.02
			}
		}
		if score > best {
			best = score
		}
	}
	return best
}

func isNeteasePlaceholderURL(raw string) bool {
	lower := strings.ToLower(raw)
	for _, id := range neteasePlaceholderIDs {
		if strings.Contains(lower, id) {
			return true
		}
	}
	return false
}

func formatNeteaseImageURL(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return raw
	}
	if strings.EqualFold(u.Scheme, "http") && strings.HasSuffix(strings.ToLower(u.Hostname()), ".music.126.net") {
		u.Scheme = "https"
	}
	q := u.Query()
	if q.Get("param") == "" {
		q.Set("param", "800y800")
		u.RawQuery = q.Encode()
	}
	return u.String()
}

func isDeezerPlaceholderURL(raw string) bool {
	lower := strings.ToLower(raw)
	for _, marker := range deezerPlaceholderMarkers {
		if strings.Contains(lower, marker) {
			return true
		}
	}
	return strings.HasSuffix(strings.TrimRight(lower, "/"), "/images/artist")
}

// FetchAndValidateImage downloads imageURL through transport.Pool, enforcing HTTP 200,
// byte-size bounds [1500, 10MB], and JPEG/PNG/WebP magic-byte + Content-Type checks.
func (f *ArtistArtworkFetcher) FetchAndValidateImage(ctx context.Context, snap config.Snapshot, sourceID, imageURL string, headers map[string]string) ([]byte, string, error) {
	if !f.hasPool() {
		return nil, "", errors.New("网络传输池未初始化")
	}
	opts := f.options()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, imageURL, nil)
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("User-Agent", provider.DefaultUserAgent)
	for k, v := range headers {
		if strings.TrimSpace(v) != "" {
			req.Header.Set(k, v)
		}
	}

	resp, err := f.pool.Do(ctx, req, transport.RequestOptions{
		Domain:           transport.DomainPublicMedia,
		ConfigRevision:   snap.Revision,
		ProxyURL:         scraperProxyURL(snap, sourceID),
		Timeout:          artistFetchTimeout,
		MaxResponseBytes: int64(opts.MaxImageBytes),
	})
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, "", fmt.Errorf("图片响应状态异常: HTTP %d", resp.StatusCode)
	}
	ct := strings.ToLower(strings.TrimSpace(strings.Split(resp.Header.Get("Content-Type"), ";")[0]))
	if ct != "" && ct != "application/octet-stream" && ct != "binary/octet-stream" {
		switch ct {
		case "image/jpeg", "image/jpg", "image/png", "image/webp":
		default:
			return nil, "", fmt.Errorf("不支持的歌手头像 Content-Type: %s", ct)
		}
	}

	data, err := io.ReadAll(io.LimitReader(resp.Body, int64(opts.MaxImageBytes)+1))
	if err != nil {
		return nil, "", err
	}
	if len(data) < opts.MinImageBytes {
		return nil, "", fmt.Errorf("图片体积过小 (%d bytes)", len(data))
	}
	if len(data) > opts.MaxImageBytes {
		return nil, "", fmt.Errorf("图片体积超过 10 MB 上限")
	}
	mime := detectArtworkMIME(data)
	if mime == "" {
		return nil, "", errors.New("图片内容不是有效的 JPEG/PNG/WebP")
	}
	return data, mime, nil
}

// EnsureArtistArtwork checks `<artistDir>/artist.{jpg,jpeg,png,webp}` and, if missing,
// resolves and atomically writes `<artistDir>/artist.jpg`.
func (f *ArtistArtworkFetcher) writeFromCacheIfAvailable(snap config.Snapshot, artistDir, artistName string) bool {
	if f == nil || strings.TrimSpace(artistDir) == "" {
		return false
	}
	primary := PrimaryArtistName(artistName)
	if primary == "" {
		return false
	}
	var data []byte
	if raw, _, ok := f.readDiskCache(snap, primary); ok {
		data = raw
	} else if cached, hit := f.getMemoryCache(strings.ToLower(primary)); hit && cached.Found && len(cached.Data) > 0 {
		data = cached.Data
		f.writeDiskCache(snap, primary, data)
	}
	if len(data) == 0 {
		return false
	}
	unlock := f.lockForDir(artistDir)
	defer unlock()
	if _, _, exists := findExistingArtistArtwork(artistDir); exists {
		f.recordArtistDirInIndex(artistDir)
		f.clearNegativeCache(artistName, artistDir)
		return true
	}
	target := filepath.Join(artistDir, "artist.jpg")
	if writeFileAtomic(target, data) != nil {
		return false
	}
	f.recordArtistDirInIndex(artistDir)
	f.clearNegativeCache(artistName, artistDir)
	return true
}

func (f *ArtistArtworkFetcher) EnsureArtistArtwork(ctx context.Context, snap config.Snapshot, libRoot, audioPath, artistName string) (targetPath, status string, err error) {
	if f == nil {
		return "", "skipped", errors.New("歌手头像抓取器未初始化")
	}
	artistDir := ResolveArtistDir(libRoot, audioPath, artistName)
	if artistDir == "" {
		return "", "skipped", errors.New("无法定位合法的歌手一级目录")
	}

	if existing, _, ok := findExistingArtistArtwork(artistDir); ok {
		f.recordArtistDirInIndex(artistDir)
		f.clearNegativeCache(artistName, artistDir)
		return existing, "exists", nil
	}

	res := f.LookupArtistArtwork(ctx, snap, artistName)
	if !res.Found || len(res.Data) == 0 {
		if res.Err != nil {
			return "", "", res.Err
		}
		return "", "", errors.New("未找到有效歌手写真")
	}

	unlock := f.lockForDir(artistDir)
	defer unlock()

	if existing, _, ok := findExistingArtistArtwork(artistDir); ok {
		f.recordArtistDirInIndex(artistDir)
		f.clearNegativeCache(artistName, artistDir)
		return existing, "exists", nil
	}

	target := filepath.Join(artistDir, "artist.jpg")
	if err := writeFileAtomic(target, res.Data); err != nil {
		return "", "", err
	}
	f.recordArtistDirInIndex(artistDir)
	f.clearNegativeCache(artistName, artistDir)
	return target, "written", nil
}

// WriteArtistArtworkFromCandidate writes `<artistDir>/artist.jpg` atomically using disk/memory cache,
// the preselected coverURL, or multi-source lookup fallback, also updating disk cache and directory index.
func (f *ArtistArtworkFetcher) WriteArtistArtworkFromCandidate(ctx context.Context, snap config.Snapshot, artistDir, artistName, coverURL string) (targetPath, status string, err error) {
	if f == nil {
		return "", "skipped", errors.New("歌手头像抓取器未初始化")
	}
	if strings.TrimSpace(artistDir) == "" {
		return "", "skipped", errors.New("歌手目录不能为空")
	}
	primary := PrimaryArtistName(artistName)
	if primary == "" {
		return "", "skipped", errors.New("歌手名称无效")
	}

	if existing, _, ok := findExistingArtistArtwork(artistDir); ok {
		f.recordArtistDirInIndex(artistDir)
		f.clearNegativeCache(artistName, artistDir)
		return existing, "exists", nil
	}

	var data []byte
	if raw, _, ok := f.readDiskCache(snap, primary); ok {
		data = raw
	} else if cached, hit := f.getMemoryCache(strings.ToLower(primary)); hit && cached.Found && len(cached.Data) > 0 {
		data = cached.Data
		f.writeDiskCache(snap, primary, data)
	} else if strings.TrimSpace(coverURL) != "" && f.hasPool() {
		srcID := ""
		headers := map[string]string(nil)
		if strings.Contains(strings.ToLower(coverURL), "126.net") || strings.Contains(strings.ToLower(coverURL), "163.com") {
			srcID = "netease"
			headers = map[string]string{"Referer": "https://music.163.com/"}
		} else if strings.Contains(strings.ToLower(coverURL), "dzcdn.net") || strings.Contains(strings.ToLower(coverURL), "deezer.com") {
			srcID = "deezer"
		}
		if fetched, mime, fErr := f.FetchAndValidateImage(ctx, snap, srcID, coverURL, headers); fErr == nil {
			data = fetched
			f.writeDiskCache(snap, primary, data)
			f.putPositiveCache(strings.ToLower(primary), ArtistArtworkLookup{
				Artist:   primary,
				Source:   srcID,
				ImageURL: coverURL,
				MIME:     mime,
				Data:     data,
				Found:    true,
			})
		}
	}
	if len(data) == 0 {
		res := f.LookupArtistArtwork(ctx, snap, artistName)
		if !res.Found || len(res.Data) == 0 {
			if res.Err != nil {
				return "", "", res.Err
			}
			return "", "", errors.New("未找到有效歌手写真")
		}
		data = res.Data
	}

	unlock := f.lockForDir(artistDir)
	defer unlock()

	if existing, _, ok := findExistingArtistArtwork(artistDir); ok {
		f.recordArtistDirInIndex(artistDir)
		f.clearNegativeCache(artistName, artistDir)
		return existing, "exists", nil
	}
	target := filepath.Join(artistDir, "artist.jpg")
	if err := writeFileAtomic(target, data); err != nil {
		return "", "", err
	}
	f.recordArtistDirInIndex(artistDir)
	f.clearNegativeCache(artistName, artistDir)
	if mime := detectArtworkMIME(data); mime != "" {
		f.putPositiveCache(strings.ToLower(primary), ArtistArtworkLookup{
			Artist: primary,
			Source: "local",
			MIME:   mime,
			Data:   data,
			Found:  true,
		})
	}
	return target, "written", nil
}

func (f *ArtistArtworkFetcher) clearNegativeCache(artistName, artistDir string) {
	if f == nil {
		return
	}
	keys := make([]string, 0, 4)
	for _, cand := range normalizeArtistCandidates(artistName) {
		if k := strings.ToLower(cand); k != "" {
			keys = append(keys, k)
		}
	}
	if base := strings.TrimSpace(filepath.Base(artistDir)); base != "" && base != "." && base != string(filepath.Separator) {
		keys = append(keys, strings.ToLower(collapseWhitespace(base)))
	}
	if len(keys) == 0 {
		return
	}
	f.mu.Lock()
	for _, k := range keys {
		delete(f.negCache, k)
	}
	f.mu.Unlock()
}

func (f *ArtistArtworkFetcher) resolveCachedOrLocalArtistAvatar(ctx context.Context, snap config.Snapshot, cands []string) (data []byte, mime string, found bool, negHit bool) {
	if len(cands) == 0 {
		return nil, "", false, false
	}
	primary := cands[0]
	key := strings.ToLower(primary)

	if cached, hit := f.getMemoryCache(key); hit {
		if cached.Found && len(cached.Data) > 0 {
			return cached.Data, cached.MIME, true, false
		}
		return nil, "", false, true
	}

	if raw, m, ok := f.scanLocalRootsForArtistAvatar(ctx, cands); ok {
		f.putPositiveCache(key, ArtistArtworkLookup{
			Artist: primary,
			Source: "local",
			MIME:   m,
			Data:   raw,
			Found:  true,
		})
		return raw, m, true, false
	}

	for _, cand := range cands {
		if raw, m, ok := f.readDiskCacheExact(snap, cand); ok {
			f.putPositiveCache(key, ArtistArtworkLookup{
				Artist: primary,
				Source: "cache",
				MIME:   m,
				Data:   raw,
				Found:  true,
			})
			return raw, m, true, false
		}
	}
	return nil, "", false, false
}

// ResolveArtistAvatarFast resolves artist avatar bytes strictly from in-memory cache, 30s local directory
// index, and disk cache without blocking on remote upstream requests. On cache miss, it enqueues a
// deduplicated background prefetch task and immediately returns NotFound.
func (f *ArtistArtworkFetcher) ResolveArtistAvatarFast(ctx context.Context, snap config.Snapshot, artistName string) ([]byte, string, error) {
	if f == nil {
		return nil, "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "歌手头像服务未初始化")
	}
	cands := normalizeArtistCandidates(artistName)
	if len(cands) == 0 {
		return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "歌手头像不存在")
	}
	if data, mime, ok, negHit := f.resolveCachedOrLocalArtistAvatar(ctx, snap, cands); ok {
		return data, mime, nil
	} else if negHit {
		return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到歌手头像")
	}

	f.enqueuePrefetch(snap, artistName)
	return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到歌手头像")
}

// ResolveArtistAvatarLive resolves artist avatar bytes from cache/local index first, and on miss
// acquires a bounded live scrape slot (capacity 2) with a short 3.5s timeout. If the live slot is
// busy or the scrape times out, it enqueues background prefetch and returns NotFound.
func (f *ArtistArtworkFetcher) ResolveArtistAvatarLive(ctx context.Context, snap config.Snapshot, artistName string) ([]byte, string, error) {
	if f == nil {
		return nil, "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "歌手头像服务未初始化")
	}
	cands := normalizeArtistCandidates(artistName)
	if len(cands) == 0 {
		return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "歌手头像不存在")
	}
	if data, mime, ok, negHit := f.resolveCachedOrLocalArtistAvatar(ctx, snap, cands); ok {
		return data, mime, nil
	} else if negHit {
		return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到歌手头像")
	}

	if f.liveSem != nil {
		select {
		case f.liveSem <- struct{}{}:
			defer func() { <-f.liveSem }()
		default:
			f.enqueuePrefetch(snap, artistName)
			return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到歌手头像")
		}
	}

	liveCtx, cancel := context.WithTimeout(ctx, artistLiveFetchTimeout)
	defer cancel()

	res := f.LookupArtistArtwork(liveCtx, snap, artistName)
	if res.Found && len(res.Data) > 0 {
		return res.Data, res.MIME, nil
	}
	if ctx.Err() == nil && errors.Is(liveCtx.Err(), context.DeadlineExceeded) {
		f.enqueuePrefetch(snap, artistName)
	}
	return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到歌手头像")
}

// ResolveArtistAvatar resolves artist avatar bytes with priority:
// 1) `<localLibraryRoot>/<Artist>/artist.{jpg,jpeg,png,webp}` (30s cached first-level dir index),
// 2) `<dataDir>/artist-avatars/<sha256(lower(name))>.jpg` disk cache,
// 3) live Netease/Deezer scrape + disk cache writeback.
func (f *ArtistArtworkFetcher) ResolveArtistAvatar(ctx context.Context, snap config.Snapshot, artistName string) ([]byte, string, error) {
	if f == nil {
		return nil, "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "歌手头像服务未初始化")
	}
	cands := normalizeArtistCandidates(artistName)
	if len(cands) == 0 {
		return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "歌手头像不存在")
	}
	if data, mime, ok, negHit := f.resolveCachedOrLocalArtistAvatar(ctx, snap, cands); ok {
		return data, mime, nil
	} else if negHit {
		return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到歌手头像")
	}

	res := f.LookupArtistArtwork(ctx, snap, artistName)
	if res.Found && len(res.Data) > 0 {
		return res.Data, res.MIME, nil
	}
	return nil, "", model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到歌手头像")
}

func (f *ArtistArtworkFetcher) enqueuePrefetch(snap config.Snapshot, artistName string) {
	if f == nil || f.prefetchCh == nil {
		return
	}
	key := artistCacheKey(artistName)
	if key == "" {
		return
	}
	if _, hit := f.getMemoryCache(key); hit {
		return
	}

	f.prefetchMu.Lock()
	defer f.prefetchMu.Unlock()
	if f.closed || f.disablePrefetch || !f.hasPool() {
		return
	}
	if f.prefetchQueued[key] {
		return
	}
	f.prefetchOnce.Do(func() {
		for i := 0; i < artistPrefetchWorkers; i++ {
			f.prefetchWG.Add(1)
			go f.prefetchWorker()
		}
	})
	select {
	case f.prefetchCh <- artistPrefetchTask{snap: snap, artistName: artistName, key: key}:
		f.prefetchQueued[key] = true
	default:
	}
}

func (f *ArtistArtworkFetcher) prefetchWorker() {
	defer f.prefetchWG.Done()
	for {
		select {
		case <-f.prefetchCtx.Done():
			return
		case task := <-f.prefetchCh:
			if f.prefetchCtx.Err() != nil {
				return
			}
			f.runPrefetchTask(f.prefetchCtx, task)
		}
	}
}

func (f *ArtistArtworkFetcher) runPrefetchTask(ctx context.Context, task artistPrefetchTask) {
	defer func() {
		f.prefetchMu.Lock()
		delete(f.prefetchQueued, task.key)
		f.prefetchMu.Unlock()
	}()
	if ctx.Err() != nil {
		return
	}
	tCtx, cancel := context.WithTimeout(ctx, artistFetchTimeout*2)
	defer cancel()
	_ = f.LookupArtistArtwork(tCtx, task.snap, task.artistName)
}

func dirIndexPathKey(p string) string {
	clean := filepath.Clean(p)
	if runtime.GOOS == "windows" {
		return strings.ToLower(clean)
	}
	return clean
}

func appendUniqueDir(dst []string, dir string) []string {
	key := dirIndexPathKey(dir)
	for _, existing := range dst {
		if dirIndexPathKey(existing) == key {
			return dst
		}
	}
	return append(dst, dir)
}

func (f *ArtistArtworkFetcher) recordArtistDirInIndex(artistDir string) {
	if f == nil {
		return
	}
	cleanDir := filepath.Clean(strings.TrimSpace(artistDir))
	if cleanDir == "" || cleanDir == "." {
		return
	}
	folderName := filepath.Base(cleanDir)
	if folderName == "" || folderName == "." || files.InternalDirs[folderName] || strings.HasPrefix(folderName, ".melokeep-") || strings.HasPrefix(folderName, ".") || ignoredArtistNames[strings.ToLower(folderName)] {
		return
	}
	st, err := os.Lstat(cleanDir)
	if err != nil || !st.IsDir() {
		return
	}
	if isLink, lErr := files.IsReparseOrSymlink(cleanDir, st); lErr != nil || isLink {
		return
	}

	parentKey := dirIndexPathKey(filepath.Dir(cleanDir))
	now := time.Now()
	f.dirIndexMu.Lock()
	defer f.dirIndexMu.Unlock()
	f.dirIndexGen++
	if f.dirIndexExpiresAt.IsZero() || !now.Before(f.dirIndexExpiresAt) {
		return
	}
	if !f.dirIndexBaseDirs[parentKey] {
		f.dirIndexExpiresAt = time.Time{}
		f.dirIndexBaseDirs = nil
		f.dirIndexExact = nil
		f.dirIndexNorm = nil
		return
	}
	if f.dirIndexExact == nil {
		f.dirIndexExact = make(map[string][]string)
	}
	if f.dirIndexNorm == nil {
		f.dirIndexNorm = make(map[string][]string)
	}
	exactKey := strings.ToLower(folderName)
	f.dirIndexExact[exactKey] = appendUniqueDir(f.dirIndexExact[exactKey], cleanDir)
	if norm := NormalizedMeta(folderName); norm != "" {
		f.dirIndexNorm[norm] = appendUniqueDir(f.dirIndexNorm[norm], cleanDir)
	}
}

func (f *ArtistArtworkFetcher) lookupLocalDirsFromIndex(ctx context.Context, artistCandidates []string) []string {
	if f == nil || len(artistCandidates) == 0 || ctx.Err() != nil {
		return nil
	}
	if err := f.ensureLocalDirIndex(ctx); err != nil {
		return nil
	}

	now := time.Now()
	f.dirIndexMu.Lock()
	defer f.dirIndexMu.Unlock()
	if f.dirIndexExpiresAt.IsZero() || !now.Before(f.dirIndexExpiresAt) {
		return nil
	}

	seen := make(map[string]bool)
	var matched []string
	addList := func(dirs []string) {
		for _, d := range dirs {
			k := dirIndexPathKey(d)
			if !seen[k] {
				seen[k] = true
				matched = append(matched, d)
			}
		}
	}

	// Pass 1: exact case-insensitive match on folder name.
	for _, cand := range artistCandidates {
		addList(f.dirIndexExact[strings.ToLower(cand)])
	}
	// Pass 2: SafeSegment / normalized match.
	for _, cand := range artistCandidates {
		if safeCand := SafeSegment(cand, ""); safeCand != "" {
			addList(f.dirIndexExact[strings.ToLower(safeCand)])
		}
		if normCand := NormalizedMeta(cand); normCand != "" {
			addList(f.dirIndexNorm[normCand])
		}
	}
	return matched
}

func (f *ArtistArtworkFetcher) ensureLocalDirIndex(ctx context.Context) error {
	now := time.Now()
	f.dirIndexMu.Lock()
	valid := !f.dirIndexExpiresAt.IsZero() && now.Before(f.dirIndexExpiresAt)
	f.dirIndexMu.Unlock()
	if valid {
		return nil
	}

	f.dirIndexBuildMu.Lock()
	defer f.dirIndexBuildMu.Unlock()

	now = time.Now()
	f.dirIndexMu.Lock()
	valid = !f.dirIndexExpiresAt.IsZero() && now.Before(f.dirIndexExpiresAt)
	startGen := f.dirIndexGen
	f.dirIndexMu.Unlock()
	if valid {
		return nil
	}

	baseDirs := f.collectLocalSearchBaseDirs(ctx)
	baseSet := make(map[string]bool, len(baseDirs))
	exact := make(map[string][]string)
	normMap := make(map[string][]string)

	for _, baseDir := range baseDirs {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		baseSet[dirIndexPathKey(baseDir)] = true
		entries, err := os.ReadDir(baseDir)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			name := entry.Name()
			if name == "" || files.InternalDirs[name] || strings.HasPrefix(name, ".melokeep-") || strings.HasPrefix(name, ".") || ignoredArtistNames[strings.ToLower(name)] {
				continue
			}
			if !entry.IsDir() {
				continue
			}
			subDir := filepath.Join(baseDir, name)
			st, sErr := os.Lstat(subDir)
			if sErr != nil || !st.IsDir() {
				continue
			}
			if isLink, lErr := files.IsReparseOrSymlink(subDir, st); lErr != nil || isLink {
				continue
			}
			exactKey := strings.ToLower(name)
			exact[exactKey] = appendUniqueDir(exact[exactKey], subDir)
			if norm := NormalizedMeta(name); norm != "" {
				normMap[norm] = appendUniqueDir(normMap[norm], subDir)
			}
		}
	}

	if ctx.Err() != nil {
		return ctx.Err()
	}

	ttl := f.options().DirIndexTTL
	f.dirIndexMu.Lock()
	if f.dirIndexGen == startGen {
		f.dirIndexBaseDirs = baseSet
		f.dirIndexExact = exact
		f.dirIndexNorm = normMap
		f.dirIndexExpiresAt = time.Now().Add(ttl)
	}
	f.dirIndexMu.Unlock()
	return nil
}

func (f *ArtistArtworkFetcher) scanLocalRootsForArtistAvatar(ctx context.Context, artistCandidates []string) ([]byte, string, bool) {
	matchedDirs := f.lookupLocalDirsFromIndex(ctx, artistCandidates)
	if len(matchedDirs) == 0 {
		return nil, "", false
	}
	maxBytes := f.options().MaxImageBytes
	for _, subDir := range matchedDirs {
		if ctx.Err() != nil {
			return nil, "", false
		}
		artPath, _, ok := findExistingArtistArtwork(subDir)
		if !ok {
			continue
		}
		raw, rErr := os.ReadFile(artPath)
		if rErr != nil || len(raw) == 0 || len(raw) > maxBytes {
			continue
		}
		mime := detectArtworkMIME(raw)
		if mime == "" {
			continue
		}
		return raw, mime, true
	}
	return nil, "", false
}

func (f *ArtistArtworkFetcher) collectLocalSearchBaseDirs(ctx context.Context) []string {
	var roots []files.Root
	if f.coords != nil {
		roots = append(roots, f.coords.Roots()...)
	}
	if len(roots) == 0 && f.store != nil {
		if recs, err := f.store.ListRoots(ctx); err == nil {
			for _, rec := range recs {
				if rec.Enabled {
					roots = append(roots, files.RootFromRecord(rec))
				}
			}
		}
	}
	if len(roots) == 0 {
		return nil
	}

	seen := make(map[string]bool)
	var dirs []string
	addDir := func(p string) {
		p = strings.TrimSpace(p)
		if p == "" {
			return
		}
		clean := filepath.Clean(p)
		key := clean
		if runtime.GOOS == "windows" {
			key = strings.ToLower(clean)
		}
		if seen[key] {
			return
		}
		st, err := os.Lstat(clean)
		if err != nil || !st.IsDir() {
			return
		}
		if isLink, lErr := files.IsReparseOrSymlink(clean, st); lErr != nil || isLink {
			return
		}
		seen[key] = true
		dirs = append(dirs, clean)
	}

	// Prefer configured library_dir first (e.g., <root>/library), then registered roots themselves.
	libDirSetting := DefaultSettings().LibraryDir
	if f.store != nil {
		rec, ok, err := f.store.GetSetting(ctx, settingsNamespace)
		if err == nil && !ok {
			rec, ok, err = f.store.GetSetting(ctx, store.NamespaceLibraryConfig)
		}
		if err == nil && ok && strings.TrimSpace(rec.BodyJSON) != "" {
			settings := DefaultSettings()
			if json.Unmarshal([]byte(rec.BodyJSON), &settings) == nil && strings.TrimSpace(settings.LibraryDir) != "" {
				libDirSetting = settings.LibraryDir
			}
		}
	}
	if strings.TrimSpace(libDirSetting) != "" {
		cand := resolveDirAgainstRoots(libDirSetting, roots)
		if vp, vErr := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: false, AudioOnly: false}); vErr == nil {
			addDir(vp.AbsPath)
		}
	}
	for _, r := range roots {
		addDir(r.CanonicalPath)
	}
	return dirs
}

// EnsureArtistArtwork delegates to the underlying ArtistArtworkFetcher on Service.
func (s *Service) EnsureArtistArtwork(ctx context.Context, snap config.Snapshot, libRoot, audioPath, artistName string) (targetPath, status string, err error) {
	if s == nil {
		return "", "skipped", errors.New("曲库服务未初始化")
	}
	return s.artworkFetcher().EnsureArtistArtwork(ctx, snap, libRoot, audioPath, artistName)
}

// ResolveArtistAvatar resolves artist avatar bytes from local roots, disk cache, or remote sources.
func (s *Service) ResolveArtistAvatar(ctx context.Context, snap config.Snapshot, artistName string) ([]byte, string, error) {
	if s == nil {
		return nil, "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "曲库服务未初始化")
	}
	return s.artworkFetcher().ResolveArtistAvatar(ctx, snap, artistName)
}

// ResolveArtistAvatarFast resolves artist avatar bytes without blocking on remote upstream requests.
func (s *Service) ResolveArtistAvatarFast(ctx context.Context, snap config.Snapshot, artistName string) ([]byte, string, error) {
	if s == nil {
		return nil, "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "曲库服务未初始化")
	}
	return s.artworkFetcher().ResolveArtistAvatarFast(ctx, snap, artistName)
}

// ResolveArtistAvatarLive resolves artist avatar bytes with bounded live scraping fallback.
func (s *Service) ResolveArtistAvatarLive(ctx context.Context, snap config.Snapshot, artistName string) ([]byte, string, error) {
	if s == nil {
		return nil, "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "曲库服务未初始化")
	}
	return s.artworkFetcher().ResolveArtistAvatarLive(ctx, snap, artistName)
}
