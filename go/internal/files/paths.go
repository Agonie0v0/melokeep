package files

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

var InternalDirs = map[string]bool{
	".melokeep-history": true,
	".melokeep-stage":   true,
	".melokeep-tmp":     true,
	".melokeep-part":    true,
}

var AudioExtensions = map[string]bool{
	".mp3": true, ".flac": true, ".m4a": true, ".mp4": true,
	".ogg": true, ".opus": true, ".wav": true, ".aac": true,
	".aiff": true, ".aif": true, ".wma": true, ".ape": true, ".wv": true,
}

var SidecarExtensions = []string{".lrc", ".jpg", ".jpeg", ".png", ".webp"}

type RootCapabilities struct {
	CaseSensitive     bool `json:"case_sensitive"`
	HardlinkNoReplace bool `json:"hardlink_no_replace"`
	DirSync           bool `json:"dir_sync"`
}

type Root struct {
	ID            string
	CanonicalPath string
	CaseMode      string // sensitive | insensitive
	Capabilities  RootCapabilities
}

type ValidateOptions struct {
	AllowInternal bool
	MustExist     bool
	AudioOnly     bool
}

type ValidatedPath struct {
	Root    Root
	AbsPath string
	RelPath string
	PathKey string
	Folder  string
	Name    string
}

// CanonicalizeRoot validates that rawRoot exists, is a directory, and contains no symlinks or reparse points.
func CanonicalizeRoot(rawRoot string) (string, error) {
	if strings.TrimSpace(rawRoot) == "" {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "音乐根目录不能为空")
	}
	abs, err := filepath.Abs(rawRoot)
	if err != nil {
		return "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的音乐根目录路径", err)
	}
	if err := verifyNoSymlinkChain(abs); err != nil {
		return "", err
	}
	resolved, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return "", model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "音乐根目录不可达", err)
	}
	st, err := os.Stat(resolved)
	if err != nil || !st.IsDir() {
		return "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "音乐根目录不存在或不是目录")
	}
	return filepath.Clean(resolved), nil
}

// ProbeCapabilities checks case sensitivity and hardlink no-replace support inside canonicalRoot.
func ProbeCapabilities(canonicalRoot string) (RootCapabilities, string, error) {
	caps := RootCapabilities{
		CaseSensitive: runtime.GOOS != "windows" && runtime.GOOS != "darwin",
		DirSync:       runtime.GOOS != "windows",
	}
	probeDir, err := os.MkdirTemp(canonicalRoot, ".melokeep-probe-")
	if err != nil {
		return caps, caseModeFromBool(caps.CaseSensitive), nil
	}
	defer os.RemoveAll(probeDir)

	aPath := filepath.Join(probeDir, "ProbeCase")
	bPath := filepath.Join(probeDir, "probecase")
	if err := os.WriteFile(aPath, []byte("probe"), 0o600); err == nil {
		if _, statErr := os.Stat(bPath); statErr == nil {
			caps.CaseSensitive = false
		} else if errors.Is(statErr, os.ErrNotExist) {
			caps.CaseSensitive = true
		}
		linkTarget := filepath.Join(probeDir, "ProbeLink")
		if err := os.Link(aPath, linkTarget); err == nil {
			if err2 := os.Link(aPath, linkTarget); errors.Is(err2, os.ErrExist) {
				caps.HardlinkNoReplace = true
			}
			_ = os.Remove(linkTarget)
		}
	}
	return caps, caseModeFromBool(caps.CaseSensitive), nil
}

func caseModeFromBool(sensitive bool) string {
	if sensitive {
		return "sensitive"
	}
	return "insensitive"
}

func RegisterRoot(rawPath string, existingID string) (Root, store.RootRecord, error) {
	canon, err := CanonicalizeRoot(rawPath)
	if err != nil {
		return Root{}, store.RootRecord{}, err
	}
	caps, caseMode, err := ProbeCapabilities(canon)
	if err != nil {
		return Root{}, store.RootRecord{}, err
	}
	id := existingID
	if id == "" {
		id = model.NewUUID()
	}
	capsJSON, _ := json.Marshal(caps)
	rec := store.RootRecord{
		ID:               id,
		CanonicalPath:    canon,
		PathCaseMode:     caseMode,
		CapabilitiesJSON: string(capsJSON),
		Enabled:          true,
		Healthy:          true,
	}
	return Root{
		ID:            id,
		CanonicalPath: canon,
		CaseMode:      caseMode,
		Capabilities:  caps,
	}, rec, nil
}

func RootFromRecord(rec store.RootRecord) Root {
	var caps RootCapabilities
	_ = json.Unmarshal([]byte(rec.CapabilitiesJSON), &caps)
	return Root{
		ID:            rec.ID,
		CanonicalPath: filepath.Clean(rec.CanonicalPath),
		CaseMode:      rec.PathCaseMode,
		Capabilities:  caps,
	}
}

func ComputePathKey(caseMode, relPath string) string {
	slashed := filepath.ToSlash(filepath.Clean(relPath))
	if caseMode == "insensitive" {
		return strings.ToLower(slashed)
	}
	return slashed
}

// ValidatePath verifies candidate is strictly contained inside one of roots (deepest match wins),
// contains no symlink/reparse-point components, and respects internal-dir and audio-ext rules.
func ValidatePath(roots []Root, candidate string, opts ValidateOptions) (ValidatedPath, error) {
	if strings.TrimSpace(candidate) == "" {
		return ValidatedPath{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件路径不能为空")
	}
	absTarget, err := filepath.Abs(candidate)
	if err != nil {
		return ValidatedPath{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "文件路径格式无效", err)
	}
	absTarget = filepath.Clean(absTarget)

	sortedRoots := append([]Root(nil), roots...)
	sort.Slice(sortedRoots, func(i, j int) bool {
		return len(sortedRoots[i].CanonicalPath) > len(sortedRoots[j].CanonicalPath)
	})

	var matched *Root
	var rel string
	for i := range sortedRoots {
		r := sortedRoots[i]
		canonRoot := filepath.Clean(r.CanonicalPath)
		rRel, relErr := filepath.Rel(canonRoot, absTarget)
		if relErr != nil || rRel == "." || rRel == ".." || strings.HasPrefix(rRel, ".."+string(filepath.Separator)) || filepath.IsAbs(rRel) {
			continue
		}
		matched = &r
		rel = rRel
		break
	}
	if matched == nil {
		return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "文件不在允许的音乐目录内")
	}

	if err := verifyRelativeChainNoSymlinks(matched.CanonicalPath, rel); err != nil {
		return ValidatedPath{}, err
	}
	if err := verifyDeepestAncestorContained(matched.CanonicalPath, absTarget); err != nil {
		return ValidatedPath{}, err
	}

	slashedRel := filepath.ToSlash(rel)
	parts := strings.Split(slashedRel, "/")
	for _, part := range parts {
		if part == "" || part == "." || part == ".." {
			return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "文件路径包含非法片段")
		}
		if !opts.AllowInternal && (InternalDirs[part] || strings.HasPrefix(part, ".melokeep-")) {
			return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "不能操作下载或处理中的内部文件")
		}
	}

	if opts.MustExist {
		st, err := os.Lstat(absTarget)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return ValidatedPath{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "文件不存在")
			}
			return ValidatedPath{}, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取文件状态失败", err)
		}
		if st.IsDir() {
			return ValidatedPath{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "目标路径是目录而非文件")
		}
		isLink, err := IsReparseOrSymlink(absTarget, st)
		if err != nil || isLink {
			return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止访问符号链接或目录联接")
		}
	}

	if opts.AudioOnly {
		ext := strings.ToLower(filepath.Ext(absTarget))
		if !AudioExtensions[ext] {
			return ValidatedPath{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "只支持音频文件")
		}
	}

	folder := filepath.ToSlash(filepath.Dir(slashedRel))
	if folder == "." {
		folder = ""
	}
	return ValidatedPath{
		Root:    *matched,
		AbsPath: absTarget,
		RelPath: slashedRel,
		PathKey: ComputePathKey(matched.CaseMode, slashedRel),
		Folder:  folder,
		Name:    filepath.Base(absTarget),
	}, nil
}

func verifyNoSymlinkChain(path string) error {
	clean := filepath.Clean(path)
	vol := filepath.VolumeName(clean)
	rest := strings.TrimPrefix(clean, vol)
	cur := vol
	if strings.HasPrefix(rest, string(filepath.Separator)) {
		cur += string(filepath.Separator)
		rest = strings.TrimPrefix(rest, string(filepath.Separator))
	}
	if rest == "" {
		return nil
	}
	for _, seg := range strings.Split(rest, string(filepath.Separator)) {
		if seg == "" {
			continue
		}
		cur = filepath.Join(cur, seg)
		st, err := os.Lstat(cur)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return nil
			}
			return model.WrapError(model.ErrForbidden, http.StatusForbidden, "路径校验失败", err)
		}
		isLink, lerr := IsReparseOrSymlink(cur, st)
		if lerr != nil || isLink {
			return model.NewError(model.ErrForbidden, http.StatusForbidden, "音乐路径不能包含符号链接或目录联接")
		}
	}
	return nil
}

func verifyRelativeChainNoSymlinks(rootCanon, rel string) error {
	if err := verifyNoSymlinkChain(rootCanon); err != nil {
		return err
	}
	cur := rootCanon
	for _, seg := range strings.Split(rel, string(filepath.Separator)) {
		if seg == "" || seg == "." {
			continue
		}
		cur = filepath.Join(cur, seg)
		st, err := os.Lstat(cur)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				break
			}
			return model.WrapError(model.ErrForbidden, http.StatusForbidden, "文件状态检验失败", err)
		}
		isLink, lerr := IsReparseOrSymlink(cur, st)
		if lerr != nil || isLink {
			return model.NewError(model.ErrForbidden, http.StatusForbidden, "拒绝访问符号链接或目录联接")
		}
	}
	return nil
}

func verifyDeepestAncestorContained(rootCanon, absTarget string) error {
	cur := absTarget
	for {
		if _, err := os.Lstat(cur); err == nil {
			resolved, rerr := filepath.EvalSymlinks(cur)
			if rerr != nil {
				return model.WrapError(model.ErrForbidden, http.StatusForbidden, "路径解析失败", rerr)
			}
			rel, relErr := filepath.Rel(rootCanon, resolved)
			if relErr != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
				return model.NewError(model.ErrForbidden, http.StatusForbidden, "文件路径超出允许根目录")
			}
			return nil
		}
		parent := filepath.Dir(cur)
		if parent == cur {
			return model.NewError(model.ErrForbidden, http.StatusForbidden, "文件路径不在允许的根目录内")
		}
		cur = parent
	}
}

// DiscoverSidecars finds matching .lrc and cover image sidecars sharing the audio stem.
func DiscoverSidecars(audioPath string) []string {
	ext := filepath.Ext(audioPath)
	stem := strings.TrimSuffix(audioPath, ext)
	var found []string
	for _, sExt := range SidecarExtensions {
		cand := stem + sExt
		st, err := os.Lstat(cand)
		if err != nil || st.IsDir() {
			continue
		}
		if isLink, err := IsReparseOrSymlink(cand, st); err == nil && !isLink {
			found = append(found, cand)
		}
	}
	return found
}

// LinkNoReplace creates a new path exclusively without overwriting an existing target.
// It prefers OS hardlink and falls back to exclusive file creation copy when hardlinks are unsupported.
func LinkNoReplace(stage, target string) error {
	err := os.Link(stage, target)
	if err == nil {
		_ = SyncDir(filepath.Dir(target))
		return nil
	}
	if errors.Is(err, os.ErrExist) {
		return model.NewError(model.ErrConflict, http.StatusConflict, "目标文件已存在，请重新预览整理路径")
	}
	if cErr := copyFileExclusiveNoReplace(stage, target); cErr != nil {
		if errors.Is(cErr, os.ErrExist) {
			return model.NewError(model.ErrConflict, http.StatusConflict, "目标文件已存在，请重新预览整理路径")
		}
		return model.WrapError(model.ErrUnsupported, http.StatusBadRequest,
			fmt.Sprintf("发布目标文件失败(%s): %v", target, cErr), cErr)
	}
	_ = SyncDir(filepath.Dir(target))
	return nil
}

func copyFileExclusiveNoReplace(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		_ = os.Remove(dst)
		return err
	}
	if err := out.Sync(); err != nil {
		_ = out.Close()
		_ = os.Remove(dst)
		return err
	}
	return out.Close()
}

// SafeRemoveEmptyParents removes empty directories up to (but never including) stopRoots or internal directories.
func SafeRemoveEmptyParents(dir string, stopRoots []Root) {
	cur := filepath.Clean(dir)
	boundaries := make(map[string]bool, len(stopRoots))
	lowerBoundaries := make(map[string]bool, len(stopRoots))
	for _, r := range stopRoots {
		cleaned := filepath.Clean(r.CanonicalPath)
		boundaries[cleaned] = true
		if runtime.GOOS == "windows" || r.CaseMode == "insensitive" {
			lowerBoundaries[strings.ToLower(cleaned)] = true
		}
	}
	for {
		if boundaries[cur] || lowerBoundaries[strings.ToLower(cur)] || InternalDirs[filepath.Base(cur)] {
			return
		}
		st, err := os.Lstat(cur)
		if err != nil || !st.IsDir() {
			return
		}
		if isLink, err := IsReparseOrSymlink(cur, st); err != nil || isLink {
			return
		}
		if err := os.Remove(cur); err != nil {
			return
		}
		parent := filepath.Dir(cur)
		if parent == cur {
			return
		}
		cur = parent
	}
}

var windowsReservedNames = map[string]bool{
	"CON": true, "PRN": true, "AUX": true, "NUL": true,
	"COM1": true, "COM2": true, "COM3": true, "COM4": true, "COM5": true,
	"COM6": true, "COM7": true, "COM8": true, "COM9": true,
	"LPT1": true, "LPT2": true, "LPT3": true, "LPT4": true, "LPT5": true,
	"LPT6": true, "LPT7": true, "LPT8": true, "LPT9": true,
}

// ValidateFolderSegment validates a single new folder name (no separators, dots, reserved names, or internal prefixes).
func ValidateFolderSegment(raw string) (string, error) {
	name := strings.TrimSpace(raw)
	if name == "" || name == "." || name == ".." {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件夹名称不能为空")
	}
	for _, r := range name {
		if r < 0x20 || r == 0x7f {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件夹名称包含非法控制字符")
		}
	}
	if strings.ContainsAny(name, `/\:*?"<>|`) {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件夹名称包含非法字符")
	}
	if strings.HasSuffix(name, ".") {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件夹名称不能以英文句点结尾")
	}
	baseUpper := strings.ToUpper(name)
	if dot := strings.IndexByte(baseUpper, '.'); dot >= 0 {
		baseUpper = baseUpper[:dot]
	}
	if windowsReservedNames[baseUpper] {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件夹名称不能使用系统保留设备名")
	}
	if InternalDirs[name] || strings.HasPrefix(name, ".melokeep-") {
		return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "不能使用保留的内部目录名称")
	}
	return name, nil
}

// ResolveRoot finds a registered root matching rootHint (by canonical path or root ID).
// If rootHint is empty and exactly one root is registered, that root is returned.
func ResolveRoot(roots []Root, rootHint string) (Root, bool) {
	hint := strings.TrimSpace(rootHint)
	if hint == "" {
		if len(roots) == 1 {
			return roots[0], true
		}
		return Root{}, false
	}
	cleanHint := filepath.Clean(hint)
	for _, r := range roots {
		if r.ID == hint || filepath.Clean(r.CanonicalPath) == cleanHint {
			return r, true
		}
	}
	return Root{}, false
}

// ResolveManagedDir resolves a user-configured directory path (absolute or relative to defaultRoot)
// against managed roots (deepest root match wins) and rejects escapes, internal directories,
// symlinks/reparse points, and non-directory targets.
func ResolveManagedDir(roots []Root, raw string, defaultRoot string) (Root, string, error) {
	if len(roots) == 0 {
		return Root{}, "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "未配置受管音乐根目录")
	}
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return Root{}, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "目录路径不能为空")
	}
	base := strings.TrimSpace(defaultRoot)
	if base == "" {
		base = roots[0].CanonicalPath
	}

	var candidate string
	if filepath.IsAbs(trimmed) {
		candidate = filepath.Clean(trimmed)
	} else {
		candidate = filepath.Join(base, filepath.FromSlash(trimmed))
	}
	absTarget, err := filepath.Abs(candidate)
	if err != nil {
		return Root{}, "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "目录路径格式无效", err)
	}
	absTarget = filepath.Clean(absTarget)

	sortedRoots := append([]Root(nil), roots...)
	sort.Slice(sortedRoots, func(i, j int) bool {
		return len(filepath.Clean(sortedRoots[i].CanonicalPath)) > len(filepath.Clean(sortedRoots[j].CanonicalPath))
	})

	var matched *Root
	var rel string
	for i := range sortedRoots {
		r := sortedRoots[i]
		canonRoot := filepath.Clean(r.CanonicalPath)
		rRel, relErr := filepath.Rel(canonRoot, absTarget)
		if relErr != nil || rRel == ".." || strings.HasPrefix(rRel, ".."+string(filepath.Separator)) || filepath.IsAbs(rRel) {
			continue
		}
		if rRel == "." {
			rel = ""
			absTarget = canonRoot
		} else {
			rel = rRel
			absTarget = filepath.Join(canonRoot, rel)
		}
		rCopy := r
		rCopy.CanonicalPath = canonRoot
		matched = &rCopy
		break
	}
	if matched == nil {
		return Root{}, "", model.NewError(
			model.ErrInvalidInput,
			http.StatusBadRequest,
			"下载目录必须位于已挂载的音乐根目录内（例如 /music、/music/待刮削 或相对路径 待刮削）；如需使用其他位置，请先在容器挂载并配置 MELOKEEP_FILE_ROOT",
		)
	}

	if rel != "" {
		slashedRel := filepath.ToSlash(rel)
		for _, part := range strings.Split(slashedRel, "/") {
			if part == "" || part == "." || part == ".." {
				return Root{}, "", model.NewError(model.ErrForbidden, http.StatusForbidden, "目录路径包含非法片段")
			}
			if InternalDirs[part] || strings.HasPrefix(part, ".melokeep-") {
				return Root{}, "", model.NewError(model.ErrForbidden, http.StatusForbidden, "不能使用内部工作目录")
			}
		}
		if err := verifyRelativeChainNoSymlinks(matched.CanonicalPath, rel); err != nil {
			return Root{}, "", err
		}
		if err := verifyDeepestAncestorContained(matched.CanonicalPath, absTarget); err != nil {
			return Root{}, "", err
		}
	}

	if st, err := os.Lstat(absTarget); err == nil {
		if !st.IsDir() {
			return Root{}, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "目标路径已存在但不是目录")
		}
		isLink, lErr := IsReparseOrSymlink(absTarget, st)
		if lErr != nil || isLink {
			return Root{}, "", model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止将符号链接或目录联接设为下载目录")
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return Root{}, "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "目录路径不可访问或父级不是目录", err)
	}

	return *matched, absTarget, nil
}

// ValidateFolderPath validates that (rootHint, folder) resolves to a non-root subdirectory inside roots,
// free of symlinks/reparse points and internal directories.
func ValidateFolderPath(roots []Root, rootHint, folder string, mustExist bool) (ValidatedPath, error) {
	if len(roots) == 0 {
		return ValidatedPath{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "未配置受管音乐目录")
	}
	cleanFolder := strings.Trim(strings.TrimSpace(filepath.ToSlash(folder)), "/")
	if cleanFolder == "" || cleanFolder == "." {
		return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "不能直接操作音乐根目录")
	}
	if filepath.IsAbs(cleanFolder) {
		return ValidatedPath{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件夹路径必须是相对路径")
	}
	parts := strings.Split(cleanFolder, "/")
	for _, part := range parts {
		if part == "" || part == "." || part == ".." {
			return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "文件夹路径包含非法片段")
		}
		if InternalDirs[part] || strings.HasPrefix(part, ".melokeep-") {
			return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "不能操作内部工作目录")
		}
	}

	var matched *Root
	if r, ok := ResolveRoot(roots, rootHint); ok {
		matched = &r
	} else if strings.TrimSpace(rootHint) == "" && mustExist {
		for i := range roots {
			cand := filepath.Join(roots[i].CanonicalPath, filepath.FromSlash(cleanFolder))
			if st, err := os.Lstat(cand); err == nil && st.IsDir() {
				r := roots[i]
				matched = &r
				break
			}
		}
	}
	if matched == nil {
		return ValidatedPath{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "无法定位所属音乐根目录")
	}

	absTarget := filepath.Clean(filepath.Join(matched.CanonicalPath, filepath.FromSlash(cleanFolder)))
	rel, err := filepath.Rel(filepath.Clean(matched.CanonicalPath), absTarget)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "文件夹路径超出允许根目录")
	}
	if err := verifyRelativeChainNoSymlinks(matched.CanonicalPath, rel); err != nil {
		return ValidatedPath{}, err
	}
	if err := verifyDeepestAncestorContained(matched.CanonicalPath, absTarget); err != nil {
		return ValidatedPath{}, err
	}

	if mustExist {
		st, err := os.Lstat(absTarget)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return ValidatedPath{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "文件夹不存在")
			}
			return ValidatedPath{}, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取文件夹状态失败", err)
		}
		if !st.IsDir() {
			return ValidatedPath{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "目标路径不是文件夹")
		}
		isLink, lErr := IsReparseOrSymlink(absTarget, st)
		if lErr != nil || isLink {
			return ValidatedPath{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止访问符号链接或目录联接")
		}
	}

	slashedRel := filepath.ToSlash(rel)
	parentFolder := filepath.ToSlash(filepath.Dir(slashedRel))
	if parentFolder == "." {
		parentFolder = ""
	}
	return ValidatedPath{
		Root:    *matched,
		AbsPath: absTarget,
		RelPath: slashedRel,
		PathKey: ComputePathKey(matched.CaseMode, slashedRel),
		Folder:  parentFolder,
		Name:    filepath.Base(absTarget),
	}, nil
}

// VerifySubtreeNoSymlinks ensures no symlinks, reparse points, or internal directories exist beneath dir.
func VerifySubtreeNoSymlinks(dir string) error {
	return filepath.WalkDir(dir, func(p string, d fs.DirEntry, wErr error) error {
		if wErr != nil {
			return model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "扫描目录失败", wErr)
		}
		name := d.Name()
		if p != dir && (InternalDirs[name] || strings.HasPrefix(name, ".melokeep-")) {
			return model.NewError(model.ErrForbidden, http.StatusForbidden, "目录包含内部工作目录，拒绝操作")
		}
		st, err := os.Lstat(p)
		if err != nil {
			return model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "检查目录条目失败", err)
		}
		isLink, lErr := IsReparseOrSymlink(p, st)
		if lErr != nil || isLink {
			return model.NewError(model.ErrForbidden, http.StatusForbidden, "目录包含符号链接或目录联接，拒绝操作")
		}
		return nil
	})
}

// ListSubdirectories enumerates non-internal directories across roots so empty folders also appear in the UI tree.
func ListSubdirectories(roots []Root, maxPerRoot int) []model.LibraryFolderDTO {
	if maxPerRoot <= 0 {
		maxPerRoot = 5000
	}
	var out []model.LibraryFolderDTO
	for _, r := range roots {
		canon := filepath.Clean(r.CanonicalPath)
		count := 0
		_ = filepath.WalkDir(canon, func(p string, d fs.DirEntry, wErr error) error {
			if wErr != nil || !d.IsDir() {
				return nil
			}
			name := d.Name()
			if p != canon && (InternalDirs[name] || strings.HasPrefix(name, ".melokeep-")) {
				return filepath.SkipDir
			}
			st, err := os.Lstat(p)
			if err != nil {
				return filepath.SkipDir
			}
			if isLink, lErr := IsReparseOrSymlink(p, st); lErr != nil || isLink {
				return filepath.SkipDir
			}
			if p == canon {
				return nil
			}
			rel, rErr := filepath.Rel(canon, p)
			if rErr != nil || rel == "." {
				return nil
			}
			count++
			if count > maxPerRoot {
				return fs.SkipAll
			}
			out = append(out, model.LibraryFolderDTO{
				Root:   canon,
				Folder: filepath.ToSlash(rel),
			})
			return nil
		})
	}
	return out
}
