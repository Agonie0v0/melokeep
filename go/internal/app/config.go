package app

import (
	"context"
	"crypto/sha256"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

const DefaultVersion = "1.0.0-go"

// Config defines all assembly options for running a single MeloKeep instance.
type Config struct {
	Host           string
	Port           int
	DataDir        string
	MusicRoots     []string
	StaticDir      string
	FrontendFS     fs.FS
	ReadOnly       bool
	EngineVersion  string
	BuildInfo      model.BuildInfoDTO
	AuthUser       string
	AuthPass       string
	CookieSecure   bool
	TrustedProxies []string
	ConfigError    string
	ConfigWarning  string

	PythonPath        string
	HelperDir         string
	FFmpegPath        string
	FFprobePath       string
	FpcalcPath        string
	AllowTestLoopback bool
	AllowBlockedWrite bool
	ProviderEndpoints map[string]string
	WatchTickInterval time.Duration
	Budgets           config.ResourceBudgets
}

// ConfigOverrides supplies explicit CLI flag overrides on top of environment variables.
type ConfigOverrides struct {
	Host       string
	Port       int
	DataDir    string
	MusicRoot  string
	StaticDir  string
	ReadOnly   *bool
	AuthUser   string
	AuthPass   string
}

// LoadConfig resolves canonical MELOKEEP_* / MELODOCK_* environment variables
// and applies explicit CLI overrides.
func LoadConfig(environ []string, ov ConfigOverrides) Config {
	raw := make(map[string]string, len(environ))
	for _, kv := range environ {
		idx := strings.IndexByte(kv, '=')
		if idx <= 0 {
			continue
		}
		raw[kv[:idx]] = kv[idx+1:]
	}
	env := config.CanonicalEnv(raw)

	var cfgErrors []string
	authUser, userErr := readAuthSecret(env, "MELOKEEP_USER", "MELOKEEP_AUTH_USER", "MELOKEEP_USERNAME")
	if userErr != nil {
		cfgErrors = append(cfgErrors, userErr.Error())
	}
	authPass, passErr := readAuthSecret(env, "MELOKEEP_PASS", "MELOKEEP_AUTH_PASS", "MELOKEEP_PASSWORD")
	if passErr != nil {
		cfgErrors = append(cfgErrors, passErr.Error())
	}

	buildInfo := CurrentBuildInfo()
	cfg := Config{
		Host:          firstNonEmpty(ov.Host, env["MELOKEEP_HOST"], env["MELOKEEP_BIND"], "0.0.0.0"),
		Port:          8765,
		DataDir:       firstNonEmpty(ov.DataDir, env["MELOKEEP_DATA_DIR"], "data"),
		StaticDir:     firstNonEmpty(ov.StaticDir, env["MELOKEEP_STATIC_DIR"]),
		EngineVersion: buildInfo.Version,
		BuildInfo:     buildInfo,
		AuthUser:      firstNonEmpty(ov.AuthUser, authUser),
		AuthPass:      firstNonEmpty(ov.AuthPass, authPass),
		CookieSecure:  parseBool(env["MELOKEEP_COOKIE_SECURE"]),
		PythonPath:    firstNonEmpty(env["MELOKEEP_PYTHON"], env["PYTHON_BIN"]),
		HelperDir:     firstNonEmpty(env["MELOKEEP_HELPER_DIR"]),
		FFmpegPath:    firstNonEmpty(env["MELOKEEP_FFMPEG"], env["FFMPEG_BIN"]),
		FFprobePath:   firstNonEmpty(env["MELOKEEP_FFPROBE"], env["FFPROBE_BIN"]),
		FpcalcPath:    firstNonEmpty(env["MELOKEEP_FPCALC"], env["FPCALC_BIN"]),
		ReadOnly:      parseBool(env["MELOKEEP_READ_ONLY"]),
		Budgets:       config.DefaultResourceBudgets(),
	}
	if len(cfgErrors) == 0 && ((strings.TrimSpace(cfg.AuthUser) == "") != (strings.TrimSpace(cfg.AuthPass) == "")) {
		cfgErrors = append(cfgErrors, "MELOKEEP_USER 与 MELOKEEP_PASS 必须同时设置；拒绝以半配置认证启动")
	}
	if len(cfgErrors) == 0 {
		switch strings.TrimSpace(cfg.AuthPass) {
		case "请改成你自己的密码", "请改成你自己的强密码", "change_me_please":
			cfg.ConfigWarning = "当前 MELOKEEP_PASS 仍为示例占位符密码，安全性较低，建议尽快修改为你自己的强密码"
		}
	}
	if len(cfgErrors) > 0 {
		cfg.ConfigError = strings.Join(cfgErrors, "; ")
	}
	if ov.ReadOnly != nil {
		cfg.ReadOnly = *ov.ReadOnly
	}
	if pStr := strings.TrimSpace(env["MELOKEEP_PORT"]); pStr != "" {
		if p, err := strconv.Atoi(pStr); err == nil && p > 0 && p <= 65535 {
			cfg.Port = p
		}
	}
	if ov.Port > 0 && ov.Port <= 65535 {
		cfg.Port = ov.Port
	}
	rootVal := firstNonEmpty(ov.MusicRoot, env["MELOKEEP_FILE_ROOT"], env["MELOKEEP_DOWNLOAD_DIR"], "melokeep_outputs")
	for _, part := range strings.Split(rootVal, string(os.PathListSeparator)) {
		if p := strings.TrimSpace(part); p != "" {
			cfg.MusicRoots = append(cfg.MusicRoots, p)
		}
	}
	if len(cfg.MusicRoots) == 0 {
		cfg.MusicRoots = []string{"melokeep_outputs"}
	}
	if tp := strings.TrimSpace(env["MELOKEEP_TRUSTED_PROXIES"]); tp != "" {
		for _, part := range strings.Split(tp, ",") {
			if p := strings.TrimSpace(part); p != "" {
				cfg.TrustedProxies = append(cfg.TrustedProxies, p)
			}
		}
	}
	return cfg
}

// DiscoverStaticFS locates frontend/dist automatically unless an explicit FS or static directory is supplied.
func DiscoverStaticFS(explicit string) (fs.FS, string) {
	var candidates []string
	if strings.TrimSpace(explicit) != "" {
		candidates = append(candidates, explicit)
	}
	if exe, err := os.Executable(); err == nil {
		exeDir := filepath.Dir(exe)
		candidates = append(candidates,
			filepath.Join(exeDir, "frontend", "dist"),
			filepath.Join(exeDir, "..", "frontend", "dist"),
			filepath.Join(exeDir, "..", "..", "frontend", "dist"),
		)
	}
	if wd, err := os.Getwd(); err == nil {
		candidates = append(candidates,
			filepath.Join(wd, "frontend", "dist"),
			filepath.Join(wd, "..", "frontend", "dist"),
			filepath.Join(wd, "..", "..", "frontend", "dist"),
		)
	}
	for _, c := range candidates {
		abs, err := filepath.Abs(c)
		if err != nil {
			continue
		}
		if st, err := os.Stat(filepath.Join(abs, "index.html")); err == nil && !st.IsDir() {
			return os.DirFS(abs), abs
		}
	}
	return nil, ""
}

// DiscoverHelperDir locates helpers/ containing mutagen/tagger.py.
func DiscoverHelperDir(explicit string) string {
	var candidates []string
	if strings.TrimSpace(explicit) != "" {
		candidates = append(candidates, explicit)
	}
	if exe, err := os.Executable(); err == nil {
		exeDir := filepath.Dir(exe)
		candidates = append(candidates,
			filepath.Join(exeDir, "helpers"),
			filepath.Join(exeDir, "..", "helpers"),
			filepath.Join(exeDir, "..", "..", "helpers"),
		)
	}
	if wd, err := os.Getwd(); err == nil {
		candidates = append(candidates,
			filepath.Join(wd, "helpers"),
			filepath.Join(wd, "..", "helpers"),
			filepath.Join(wd, "..", "..", "helpers"),
		)
	}
	for _, c := range candidates {
		abs, err := filepath.Abs(c)
		if err != nil {
			continue
		}
		if _, err := os.Stat(filepath.Join(abs, "mutagen", "tagger.py")); err == nil {
			return abs
		}
	}
	return ""
}

// DiscoverPython locates a runnable Python interpreter (preferring one with mutagen/requests).
func DiscoverPython(explicit string) string {
	if trimmed := strings.TrimSpace(explicit); trimmed != "" {
		return trimmed
	}

	var candidates []string
	seen := make(map[string]struct{})
	addCandidate := func(p string) {
		p = strings.TrimSpace(p)
		if p == "" {
			return
		}
		key := p
		if runtime.GOOS == "windows" {
			key = strings.ToLower(p)
		}
		if _, ok := seen[key]; ok {
			return
		}
		seen[key] = struct{}{}
		candidates = append(candidates, p)
	}

	var baseDirs []string
	if wd, err := os.Getwd(); err == nil && wd != "" {
		baseDirs = append(baseDirs, wd)
	}
	if exe, err := os.Executable(); err == nil && exe != "" {
		baseDirs = append(baseDirs, filepath.Dir(exe))
	}
	venvRelPaths := []string{
		filepath.Join(".venv", "bin", "python"),
		filepath.Join(".venv", "bin", "python3"),
	}
	if runtime.GOOS == "windows" {
		venvRelPaths = []string{
			filepath.Join(".venv", "Scripts", "python.exe"),
		}
	}
	for _, base := range baseDirs {
		for _, up := range []string{".", "..", filepath.Join("..", ".."), filepath.Join("..", "..", "..")} {
			for _, rel := range venvRelPaths {
				cand := filepath.Join(base, up, rel)
				if _, err := os.Stat(cand); err == nil {
					if abs, err := filepath.Abs(cand); err == nil {
						addCandidate(abs)
					} else {
						addCandidate(cand)
					}
				}
			}
		}
	}

	names := []string{"python3", "python"}
	if runtime.GOOS == "windows" {
		names = []string{"python", "python3"}
	}
	for _, name := range names {
		if p, err := exec.LookPath(name); err == nil {
			addCandidate(p)
		}
	}

	if runtime.GOOS == "windows" {
		if pyLauncher, err := exec.LookPath("py"); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			out, oErr := exec.CommandContext(ctx, pyLauncher, "-0p").Output()
			cancel()
			if oErr == nil {
				for _, rawLine := range strings.Split(string(out), "\n") {
					line := strings.TrimSpace(rawLine)
					idx := strings.Index(line, `:\`)
					if idx < 1 {
						continue
					}
					drive := line[idx-1]
					if !((drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z')) {
						continue
					}
					exePath := strings.TrimSpace(line[idx-1:])
					if _, sErr := os.Stat(exePath); sErr == nil {
						addCandidate(exePath)
					}
				}
			}
		}
	}

	probePython := func(exe, snippet string) bool {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		return exec.CommandContext(ctx, exe, "-c", snippet).Run() == nil
	}

	for _, cand := range candidates {
		if probePython(cand, "import mutagen, requests") {
			return cand
		}
	}
	for _, cand := range candidates {
		if probePython(cand, "import sys") {
			return cand
		}
	}
	if runtime.GOOS == "windows" {
		return "python"
	}
	return "python3"
}

func digestCred(v string) [32]byte {
	if v == "" {
		return [32]byte{}
	}
	return sha256.Sum256([]byte(v))
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if s := strings.TrimSpace(v); s != "" {
			return s
		}
	}
	return ""
}

func readAuthSecret(env map[string]string, primary string, aliases ...string) (string, error) {
	names := append([]string{primary}, aliases...)
	for _, name := range names {
		val := strings.TrimSpace(env[name])
		filePath := strings.TrimSpace(env[name+"_FILE"])
		if val != "" && filePath != "" {
			return "", fmt.Errorf("%s 与 %s_FILE 不能同时设置", name, name)
		}
		if filePath != "" {
			raw, err := os.ReadFile(filePath)
			if err != nil {
				return "", fmt.Errorf("无法读取 %s_FILE: %w", name, err)
			}
			if trimmed := strings.TrimSpace(string(raw)); trimmed != "" {
				return trimmed, nil
			}
			continue
		}
		if val != "" {
			return val, nil
		}
	}
	return "", nil
}

func parseBool(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}
