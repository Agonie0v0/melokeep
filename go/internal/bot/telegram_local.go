package bot

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/model"
)

const DefaultLocalBotAPIURL = "http://127.0.0.1:8081"

var reAPIHash = regexp.MustCompile(`^[a-fA-F0-9]{32}$`)

type LocalBotAPIStatus struct {
	Available bool   `json:"available"`
	Running   bool   `json:"running"`
	Detail    string `json:"detail"`
	URL       string `json:"url,omitempty"`
}

// ValidateLocalCredentials checks TG local API ID (positive int32) and 32-hex API Hash.
func ValidateLocalCredentials(apiID, apiHash string) (string, string, error) {
	apiID = strings.TrimSpace(apiID)
	apiHash = strings.TrimSpace(apiHash)
	n, err := strconv.ParseInt(apiID, 10, 64)
	if err != nil || n <= 0 || n >= 2147483648 {
		return "", "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "内置 Telegram 服务需要有效的 API ID（正整数）")
	}
	if !reAPIHash.MatchString(apiHash) {
		return "", "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "内置 Telegram 服务需要 32 位 API Hash，请从 my.telegram.org 获取")
	}
	return apiID, apiHash, nil
}

type LocalBotAPISpec struct {
	BinaryPath string
	DataDir    string
	ListenAddr string // defaults to 127.0.0.1:8081
	Args       []string
}

// LocalBotAPI manages the optional official telegram-bot-api child process bound to loopback,
// using helper.StartInTree so the entire process tree is killed on Stop or parent exit.
type LocalBotAPI struct {
	mu      sync.Mutex
	spec    LocalBotAPISpec
	tree    *helper.ProcessTree
	cmd     *exec.Cmd
	doneCh  chan error
	running bool
	apiID   string
	apiHash string
	failure string
}

func NewLocalBotAPI(spec LocalBotAPISpec) *LocalBotAPI {
	if spec.ListenAddr == "" {
		spec.ListenAddr = "127.0.0.1:8081"
	}
	return &LocalBotAPI{spec: spec}
}

func (l *LocalBotAPI) ResolveBinary() string {
	if l.spec.BinaryPath != "" {
		if p, err := exec.LookPath(l.spec.BinaryPath); err == nil {
			return p
		}
		if _, err := os.Stat(l.spec.BinaryPath); err == nil {
			return l.spec.BinaryPath
		}
		return ""
	}
	bin := strings.TrimSpace(os.Getenv("MELOKEEP_TG_LOCAL_BINARY"))
	if bin == "" {
		bin = "telegram-bot-api"
	}
	p, err := exec.LookPath(bin)
	if err != nil {
		return ""
	}
	return p
}

func (l *LocalBotAPI) URL() string {
	return "http://" + l.spec.ListenAddr
}

func (l *LocalBotAPI) Status() LocalBotAPIStatus {
	l.mu.Lock()
	defer l.mu.Unlock()
	return LocalBotAPIStatus{
		Available: l.ResolveBinary() != "",
		Running:   l.running,
		Detail:    l.failure,
		URL:       l.URL(),
	}
}

func (l *LocalBotAPI) Matches(apiID, apiHash string) bool {
	id, h, err := ValidateLocalCredentials(apiID, apiHash)
	if err != nil {
		return false
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.running && l.apiID == id && l.apiHash == h
}

func (l *LocalBotAPI) Start(ctx context.Context, apiID, apiHash string) error {
	id, h, err := ValidateLocalCredentials(apiID, apiHash)
	if err != nil {
		return err
	}
	bin := l.ResolveBinary()
	if bin == "" {
		err = model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "未找到官方 telegram-bot-api 程序，请确认已安装并加入 PATH")
		l.Stop()
		l.mu.Lock()
		l.failure = err.Error()
		l.mu.Unlock()
		return err
	}

	l.mu.Lock()
	if l.running && l.apiID == id && l.apiHash == h {
		l.mu.Unlock()
		return nil
	}
	l.stopLocked()

	if err := l.launchLocked(ctx, bin, id, h); err != nil {
		l.failure = err.Error()
		l.stopLocked()
		l.mu.Unlock()
		return err
	}
	l.apiID = id
	l.apiHash = h
	l.failure = ""
	l.mu.Unlock()
	return nil
}

func (l *LocalBotAPI) launchLocked(ctx context.Context, bin, apiID, apiHash string) error {
	host, port, err := net.SplitHostPort(l.spec.ListenAddr)
	if err != nil || (host != "127.0.0.1" && host != "::1" && host != "localhost") {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "内置 Telegram 服务仅允许监听环回地址")
	}
	probe, err := net.Listen("tcp", l.spec.ListenAddr)
	if err != nil {
		return model.NewError(model.ErrConflict, http.StatusConflict, fmt.Sprintf("内置 Telegram 服务端口 %s 已被占用", l.spec.ListenAddr))
	}
	_ = probe.Close()

	dataDir := l.spec.DataDir
	if dataDir == "" {
		base := strings.TrimSpace(os.Getenv("MELOKEEP_DATA_DIR"))
		if base == "" {
			base = "data"
		}
		dataDir = filepath.Join(base, "telegram-bot-api")
	}
	tempDir := filepath.Join(dataDir, "temp")
	if err := os.MkdirAll(tempDir, 0o700); err != nil {
		return err
	}
	if runtime.GOOS != "windows" {
		_ = os.Chmod(dataDir, 0o700)
	}
	absData, _ := filepath.Abs(dataDir)
	absTemp, _ := filepath.Abs(tempDir)

	args := l.spec.Args
	if len(args) == 0 {
		args = []string{
			"--local",
			"--http-ip-address=" + host,
			"--http-port=" + port,
			"--dir=" + absData,
			"--temp-dir=" + absTemp,
			"--verbosity=0",
		}
	}
	cmd := exec.Command(bin, args...)
	cmd.Env = append(minimalEnv(), "TELEGRAM_API_ID="+apiID, "TELEGRAM_API_HASH="+apiHash)
	tree, err := helper.StartInTree(cmd)
	if err != nil {
		return model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "启动内置 Telegram 服务失败", err)
	}
	doneCh := make(chan error, 1)
	go func() {
		doneCh <- cmd.Wait()
	}()
	l.cmd = cmd
	l.tree = tree
	l.doneCh = doneCh
	l.running = true

	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case err := <-doneCh:
			l.running = false
			return model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "内置 Telegram 服务提前退出", err)
		default:
		}
		conn, err := net.DialTimeout("tcp", l.spec.ListenAddr, 150*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return nil
		}
		time.Sleep(50 * time.Millisecond)
	}
	return model.NewError(model.ErrDeadline, http.StatusGatewayTimeout, "内置 Telegram 服务启动超时")
}

func (l *LocalBotAPI) Stop() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.stopLocked()
}

func (l *LocalBotAPI) stopLocked() {
	if l.tree != nil {
		_ = l.tree.Kill()
		l.tree = nil
	} else if l.cmd != nil && l.cmd.Process != nil {
		_ = l.cmd.Process.Kill()
	}
	if l.doneCh != nil {
		select {
		case <-l.doneCh:
		case <-time.After(3 * time.Second):
		}
		l.doneCh = nil
	}
	l.cmd = nil
	l.running = false
	l.apiID = ""
	l.apiHash = ""
}

func minimalEnv() []string {
	allow := []string{"PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "HOME", "USERPROFILE", "SSL_CERT_FILE", "SSL_CERT_DIR"}
	var out []string
	for _, k := range allow {
		if v, ok := os.LookupEnv(k); ok {
			out = append(out, k+"="+v)
		}
	}
	return out
}
