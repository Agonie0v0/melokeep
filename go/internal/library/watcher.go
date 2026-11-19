package library

import (
	"context"
	"encoding/json"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
)

const (
	durableWatchNamespace = "library_watch"
	durableWatchStatusID  = "status"
)

type WatchStatus struct {
	Queued    int      `json:"queued"`
	CheckedAt int64    `json:"checked_at,omitempty"`
	Detail    string   `json:"detail"`
	Error     string   `json:"error,omitempty"`
	Warnings  []string `json:"warnings,omitempty"`
}

type watchSeenEntry struct {
	signature string
	firstSeen time.Time
}

type watchProcessedRecord struct {
	Revision string `json:"revision"`
	JobID    string `json:"job_id"`
}

// ValidateWatchRoots resolves and validates library_dir, watch_dirs, and watch_failure_dir
// against configured roots, rejecting internal dirs and any directory overlap.
func ValidateWatchRoots(settings Settings, roots []files.Root) (libDir string, watchDirs []string, failureDir string, err error) {
	if len(roots) == 0 {
		return "", nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "未配置音乐根目录")
	}
	libCand := resolveDirAgainstRoots(settings.LibraryDir, roots)
	libVal, err := validateDirCandidate(roots, libCand, false)
	if err != nil {
		return "", nil, "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "整理目录必须位于挂载的音乐根目录内，且不能使用内部任务目录", err)
	}
	libDir = libVal.AbsPath

	for _, raw := range settings.WatchDirs {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			continue
		}
		cand := resolveDirAgainstRoots(trimmed, roots)
		v, vErr := validateDirCandidate(roots, cand, false)
		if vErr != nil {
			return "", nil, "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "监控目录必须位于挂载的音乐根目录内", vErr)
		}
		watchDirs = append(watchDirs, v.AbsPath)
	}

	if settings.WatchEnabled {
		if len(watchDirs) == 0 {
			return "", nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "开启监控需要至少一个目录")
		}
		for _, wd := range watchDirs {
			if pathsOverlap(wd, libDir) {
				return "", nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "监控目录与整理后的音乐库必须分开，避免重复刮削")
			}
		}
	}

	if strings.TrimSpace(settings.WatchFailureDir) != "" {
		failCand := resolveDirAgainstRoots(settings.WatchFailureDir, roots)
		fVal, fErr := validateDirCandidate(roots, failCand, false)
		if fErr != nil {
			return "", nil, "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "待审核目录必须位于挂载的音乐根目录内", fErr)
		}
		failureDir = fVal.AbsPath
		if pathsOverlap(failureDir, libDir) {
			return "", nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "待审核目录须与监控目录及最终音乐库分开")
		}
		for _, wd := range watchDirs {
			if pathsOverlap(failureDir, wd) {
				return "", nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "待审核目录须与监控目录及最终音乐库分开")
			}
		}
	}
	return libDir, watchDirs, failureDir, nil
}

func resolveDirAgainstRoots(raw string, roots []files.Root) string {
	cleaned := strings.TrimSpace(raw)
	if filepath.IsAbs(cleaned) {
		return filepath.Clean(cleaned)
	}
	return filepath.Join(roots[0].CanonicalPath, cleaned)
}

func validateDirCandidate(roots []files.Root, candidate string, mustExist bool) (files.ValidatedPath, error) {
	vp, err := files.ValidatePath(roots, candidate, files.ValidateOptions{MustExist: false, AudioOnly: false})
	if err != nil {
		return files.ValidatedPath{}, err
	}
	if st, stErr := os.Lstat(vp.AbsPath); stErr == nil {
		if !st.IsDir() {
			return files.ValidatedPath{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请选择目录")
		}
		if isLink, lErr := files.IsReparseOrSymlink(vp.AbsPath, st); lErr != nil || isLink {
			return files.ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "目录不能使用符号链接或目录联接")
		}
	} else if mustExist {
		return files.ValidatedPath{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "目录不存在")
	}
	return vp, nil
}

func pathsOverlap(a, b string) bool {
	ca := filepath.Clean(a)
	cb := filepath.Clean(b)
	if runtime.GOOS == "windows" {
		ca = strings.ToLower(ca)
		cb = strings.ToLower(cb)
	}
	return isSameOrChild(ca, cb) || isSameOrChild(cb, ca)
}

func isSameOrChild(parent, child string) bool {
	rel, err := filepath.Rel(parent, child)
	if err != nil {
		return false
	}
	return rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel))
}

type Watcher struct {
	svc      *Service
	nowFn    func() time.Time
	scanMu   sync.Mutex
	stateMu  sync.Mutex
	seen     map[string]watchSeenEntry
	wakeCh   chan struct{}
	stopCh   chan struct{}
	doneCh   chan struct{}
	started  bool
	stopOnce sync.Once
}

func NewWatcher(svc *Service) *Watcher {
	return &Watcher{
		svc:    svc,
		nowFn:  time.Now,
		seen:   make(map[string]watchSeenEntry),
		wakeCh: make(chan struct{}, 1),
		stopCh: make(chan struct{}),
		doneCh: make(chan struct{}),
	}
}

// Start launches the periodic watch loop in the background.
func (w *Watcher) Start(ctx context.Context) {
	w.stateMu.Lock()
	if w.started {
		w.stateMu.Unlock()
		return
	}
	w.started = true
	w.stateMu.Unlock()

	go func() {
		defer close(w.doneCh)
		for {
			interval := 180 * time.Second
			if st, err := w.svc.Settings(ctx); err == nil && st.WatchInterval > 0 {
				interval = time.Duration(st.WatchInterval) * time.Second
			}
			_, _ = w.ScanNow(ctx)
			timer := time.NewTimer(interval)
			select {
			case <-w.stopCh:
				timer.Stop()
				return
			case <-ctx.Done():
				timer.Stop()
				return
			case <-w.wakeCh:
				timer.Stop()
			case <-timer.C:
			}
		}
	}()
}

func (w *Watcher) Wake() {
	select {
	case w.wakeCh <- struct{}{}:
	default:
	}
}

func (w *Watcher) Close() {
	w.stopOnce.Do(func() {
		close(w.stopCh)
	})
	w.stateMu.Lock()
	started := w.started
	w.stateMu.Unlock()
	if started {
		<-w.doneCh
	}
}

func (w *Watcher) Status(ctx context.Context) (WatchStatus, error) {
	rec, ok, err := w.svc.store.GetDurableRecord(ctx, durableWatchNamespace, durableWatchStatusID)
	if err != nil {
		return WatchStatus{}, err
	}
	if !ok {
		return WatchStatus{Detail: "尚未检查目录"}, nil
	}
	var ws WatchStatus
	if err := json.Unmarshal([]byte(rec.BodyJSON), &ws); err != nil {
		return WatchStatus{Detail: "尚未检查目录"}, nil
	}
	return ws, nil
}

func (w *Watcher) saveStatus(ctx context.Context, ws WatchStatus) {
	b, _ := json.Marshal(ws)
	_, _ = w.svc.store.PutDurableRecord(ctx, durableWatchNamespace, durableWatchStatusID, string(b), 0)
}

// ScanNow scans configured watch_dirs using two-check (size, mtime_ns) stability gating across WatchStableSeconds.
// Offline or unreadable roots are safely skipped and marked unhealthy without deleting or marking assets missing.
func (w *Watcher) ScanNow(ctx context.Context) (WatchStatus, error) {
	if !w.scanMu.TryLock() {
		return WatchStatus{Queued: 0, Detail: "已有目录检查正在运行"}, nil
	}
	defer w.scanMu.Unlock()

	settings, err := w.svc.Settings(ctx)
	if err != nil {
		return WatchStatus{}, err
	}
	if !settings.WatchEnabled {
		return WatchStatus{Queued: 0, Detail: "目录监控未开启"}, nil
	}
	allRoots := w.svc.coordinator.Roots()
	var roots []files.Root
	var warnings []string
	for _, r := range allRoots {
		if _, cErr := files.CanonicalizeRoot(r.CanonicalPath); cErr != nil {
			_ = w.svc.store.SetRootHealth(ctx, r.ID, false, cErr.Error())
			warnings = append(warnings, fmt.Sprintf("根目录离线已跳过(%s): %v", r.CanonicalPath, cErr))
			continue
		}
		roots = append(roots, r)
	}
	if len(roots) == 0 {
		ws := WatchStatus{
			Queued:    0,
			CheckedAt: w.nowFn().UTC().Unix(),
			Detail:    "所有音乐根目录当前离线，已跳过监控扫描",
			Warnings:  warnings,
		}
		w.saveStatus(ctx, ws)
		return ws, nil
	}
	_, watchDirs, _, err := ValidateWatchRoots(settings, roots)
	if err != nil {
		ws := WatchStatus{CheckedAt: w.nowFn().UTC().Unix(), Error: err.Error(), Detail: err.Error(), Warnings: warnings}
		w.saveStatus(ctx, ws)
		return ws, err
	}

	now := w.nowFn()
	stableDur := time.Duration(settings.WatchStableSeconds) * time.Second
	type candidate struct {
		absPath   string
		signature string
	}
	var pending []candidate
	currentKeys := make(map[string]struct{})

	for _, wd := range watchDirs {
		if ctx.Err() != nil {
			break
		}
		rootMatch, ok := matchContainingRoot(roots, wd)
		if !ok {
			warnings = append(warnings, fmt.Sprintf("监控目录无对应根目录: %s", wd))
			continue
		}
		if _, cErr := files.CanonicalizeRoot(rootMatch.CanonicalPath); cErr != nil {
			_ = w.svc.store.SetRootHealth(ctx, rootMatch.ID, false, cErr.Error())
			warnings = append(warnings, fmt.Sprintf("根目录离线已跳过(%s): %v", rootMatch.CanonicalPath, cErr))
			continue
		}
		if _, dErr := os.Stat(wd); dErr != nil {
			if os.IsNotExist(dErr) {
				continue
			}
			_ = w.svc.store.SetRootHealth(ctx, rootMatch.ID, false, dErr.Error())
			warnings = append(warnings, fmt.Sprintf("监控目录不可达已跳过(%s): %v", wd, dErr))
			continue
		}
		if _, rdErr := os.ReadDir(wd); rdErr != nil {
			_ = w.svc.store.SetRootHealth(ctx, rootMatch.ID, false, rdErr.Error())
			warnings = append(warnings, fmt.Sprintf("监控目录不可读已跳过(%s): %v", wd, rdErr))
			continue
		}

		_ = filepath.WalkDir(wd, func(p string, d fs.DirEntry, wErr error) error {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if wErr != nil {
				warnings = append(warnings, fmt.Sprintf("遍历监控目录异常(%s): %v", p, wErr))
				return fs.SkipDir
			}
			name := d.Name()
			if d.IsDir() {
				if files.InternalDirs[name] || strings.HasPrefix(name, ".melokeep-") || strings.HasPrefix(name, ".") {
					return filepath.SkipDir
				}
				if strings.HasSuffix(name, " - converted") || strings.HasSuffix(name, " - tracks") {
					return filepath.SkipDir
				}
				if st, stErr := os.Lstat(p); stErr == nil {
					if isLink, _ := files.IsReparseOrSymlink(p, st); isLink {
						return filepath.SkipDir
					}
				}
				return nil
			}
			if strings.HasPrefix(name, ".") {
				return nil
			}
			ext := strings.ToLower(filepath.Ext(name))
			if ext == ".pkl" || ext == ".part" || !files.AudioExtensions[ext] {
				return nil
			}
			val, vErr := files.ValidatePath(roots, p, files.ValidateOptions{MustExist: true, AudioOnly: true})
			if vErr != nil {
				return nil
			}
			st, stErr := os.Lstat(val.AbsPath)
			if stErr != nil {
				return nil
			}
			sig := fmt.Sprintf("%d:%d", st.Size(), st.ModTime().UnixNano())
			key := val.Root.ID + ":" + val.PathKey
			currentKeys[key] = struct{}{}

			// Skip if already processed at this exact signature or previous job is still active.
			if rec, found, _ := w.svc.store.GetDurableRecord(ctx, durableWatchNamespace, "item:"+key); found {
				var prev watchProcessedRecord
				if json.Unmarshal([]byte(rec.BodyJSON), &prev) == nil {
					if prev.Revision == sig {
						return nil
					}
					if prev.JobID != "" {
						if pj, _, ok, _ := w.svc.store.GetLibraryJob(ctx, prev.JobID); ok && isLibraryJobActive(pj.Status) {
							return nil
						}
					}
				}
			}

			w.stateMu.Lock()
			entry, seenOK := w.seen[key]
			if !seenOK || entry.signature != sig {
				w.seen[key] = watchSeenEntry{signature: sig, firstSeen: now}
				w.stateMu.Unlock()
				return nil
			}
			elapsed := now.Sub(entry.firstSeen)
			w.stateMu.Unlock()
			if elapsed < stableDur {
				return nil
			}
			if len(pending) < 1000 {
				pending = append(pending, candidate{absPath: val.AbsPath, signature: sig})
			}
			return nil
		})
	}

	w.stateMu.Lock()
	for k := range w.seen {
		if _, ok := currentKeys[k]; !ok {
			delete(w.seen, k)
		}
	}
	w.stateMu.Unlock()

	queued := 0
	if len(pending) > 0 && ctx.Err() == nil {
		paths := make([]string, len(pending))
		for i, c := range pending {
			paths[i] = c.absPath
		}
		job, subErr := w.svc.submitInternal(ctx, auth.SystemActor(), SubmitRequest{
			Paths:  paths,
			Action: settings.WatchAction,
		}, submitFlags{
			Watch:      true,
			WatchApply: settings.WatchApply,
		})
		if subErr != nil {
			w.svc.log("error", "", "目录监控创建整理任务失败："+subErr.Error())
			ws := WatchStatus{
				Queued:    0,
				CheckedAt: now.UTC().Unix(),
				Error:     subErr.Error(),
				Detail:    subErr.Error(),
				Warnings:  warnings,
			}
			w.saveStatus(ctx, ws)
			return ws, subErr
		}
		queued = len(pending)
		w.svc.log("info", job.ID, fmt.Sprintf("目录监控提交 %d 首稳定新文件进行整理 (%s)", queued, settings.WatchAction))
		for _, c := range pending {
			if val, vErr := files.ValidatePath(roots, c.absPath, files.ValidateOptions{MustExist: false}); vErr == nil {
				key := val.Root.ID + ":" + val.PathKey
				sig := c.signature
				if st, stErr := os.Lstat(c.absPath); stErr == nil {
					sig = fmt.Sprintf("%d:%d", st.Size(), st.ModTime().UnixNano())
				}
				w.stateMu.Lock()
				w.seen[key] = watchSeenEntry{signature: sig, firstSeen: now}
				w.stateMu.Unlock()
				b, _ := json.Marshal(watchProcessedRecord{Revision: sig, JobID: job.ID})
				_, _ = w.svc.store.PutDurableRecord(ctx, durableWatchNamespace, "item:"+key, string(b), 0)
			}
		}
	}

	ws := WatchStatus{
		Queued:    queued,
		CheckedAt: now.UTC().Unix(),
		Detail:    fmt.Sprintf("已提交 %d 首稳定文件；未匹配的歌曲在任务中等待审核", queued),
		Warnings:  warnings,
	}
	w.saveStatus(ctx, ws)
	return ws, nil
}

func matchContainingRoot(roots []files.Root, dir string) (files.Root, bool) {
	var best files.Root
	bestLen := -1
	cleanDir := filepath.Clean(dir)
	for _, r := range roots {
		if isSameOrChild(filepath.Clean(r.CanonicalPath), cleanDir) {
			if len(r.CanonicalPath) > bestLen {
				best = r
				bestLen = len(r.CanonicalPath)
			}
		}
	}
	return best, bestLen >= 0
}
