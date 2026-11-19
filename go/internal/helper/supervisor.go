package helper

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

type HelperKind string

const (
	HelperTagger HelperKind = "tagger"
)

const (
	ProtocolVersion     = 1
	DefaultHelloTimeout = 3 * time.Second
	DefaultExitGrace    = 1 * time.Second
	DefaultCancelGrace  = 1 * time.Second
	DefaultTaskTimeout  = 30 * time.Second
	DefaultLongTimeout  = 120 * time.Second
)

type HelperError struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable,omitempty"`
}

type frame struct {
	V              int             `json:"v"`
	Type           string          `json:"type"`
	ID             string          `json:"id,omitempty"`
	Seq            int             `json:"seq,omitempty"`
	Helper         string          `json:"helper,omitempty"`
	Implementation string          `json:"implementation,omitempty"`
	Capabilities   []string        `json:"capabilities,omitempty"`
	Operation      string          `json:"operation,omitempty"`
	TimeoutMS      int64           `json:"timeout_ms,omitempty"`
	Input          any             `json:"input,omitempty"`
	Credentials    any             `json:"credentials,omitempty"`
	Data           json.RawMessage `json:"data,omitempty"`
	Error          *HelperError    `json:"error,omitempty"`
}

type SupervisorConfig struct {
	PythonExe    string
	TaggerScript string
	WorkRoot     string
	HelloTimeout time.Duration
	ExitGrace    time.Duration
	CancelGrace  time.Duration
	Budgets      config.ResourceBudgets
	ExtraSafeEnv map[string]string
}

// Supervisor manages single-task, short-lived NDJSON v1 helper processes with
// bounded concurrency, strict line/stdout/stderr caps, and tree-kill cleanup.
type Supervisor struct {
	cfg SupervisorConfig

	mu          sync.Mutex
	cond        *sync.Cond
	activeTotal int
	activeKind  map[HelperKind]int
	closed      bool
}

func NewSupervisor(cfg SupervisorConfig) *Supervisor {
	if cfg.Budgets.TotalHelpers <= 0 {
		cfg.Budgets = config.DefaultResourceBudgets()
	}
	if cfg.HelloTimeout <= 0 {
		cfg.HelloTimeout = DefaultHelloTimeout
	}
	if cfg.ExitGrace <= 0 {
		cfg.ExitGrace = DefaultExitGrace
	}
	if cfg.CancelGrace <= 0 {
		cfg.CancelGrace = DefaultCancelGrace
	}
	if cfg.Budgets.HelperMaxLineBytes <= 0 {
		cfg.Budgets.HelperMaxLineBytes = 1 << 20
	}
	if cfg.Budgets.HelperMaxOutputBytes <= 0 {
		cfg.Budgets.HelperMaxOutputBytes = 16 << 20
	}
	if cfg.Budgets.HelperMaxStderrBytes <= 0 {
		cfg.Budgets.HelperMaxStderrBytes = 256 << 10
	}
	s := &Supervisor{
		cfg:        cfg,
		activeKind: make(map[HelperKind]int),
	}
	s.cond = sync.NewCond(&s.mu)
	return s
}

func (s *Supervisor) Close() {
	s.mu.Lock()
	s.closed = true
	s.cond.Broadcast()
	s.mu.Unlock()
}

func (s *Supervisor) limitFor(kind HelperKind) int {
	switch kind {
	case HelperTagger:
		if s.cfg.Budgets.TaggerHelpers > 0 {
			return s.cfg.Budgets.TaggerHelpers
		}
		return 1
	default:
		return 1
	}
}

func (s *Supervisor) acquireSlot(ctx context.Context, kind HelperKind) error {
	kindLimit := s.limitFor(kind)
	totalLimit := s.cfg.Budgets.TotalHelpers
	if totalLimit <= 0 {
		totalLimit = 3
	}

	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			s.mu.Lock()
			s.cond.Broadcast()
			s.mu.Unlock()
		case <-done:
		}
	}()

	s.mu.Lock()
	defer s.mu.Unlock()
	for {
		if s.closed {
			return model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "helper 管理器已关闭")
		}
		if err := ctx.Err(); err != nil {
			if errors.Is(err, context.DeadlineExceeded) {
				return model.WrapError(model.ErrDeadline, http.StatusGatewayTimeout, "等待 helper 并发槽位超时", err)
			}
			return model.WrapError(model.ErrCancelled, 499, "helper 任务已取消", err)
		}
		if s.activeTotal < totalLimit && s.activeKind[kind] < kindLimit {
			s.activeTotal++
			s.activeKind[kind]++
			return nil
		}
		s.cond.Wait()
	}
}

func (s *Supervisor) releaseSlot(kind HelperKind) {
	s.mu.Lock()
	if s.activeTotal > 0 {
		s.activeTotal--
	}
	if s.activeKind[kind] > 0 {
		s.activeKind[kind]--
	}
	s.cond.Broadcast()
	s.mu.Unlock()
}

func (s *Supervisor) scriptFor(kind HelperKind) (string, error) {
	var p string
	switch kind {
	case HelperTagger:
		p = s.cfg.TaggerScript
	default:
		return "", model.NewError(model.ErrUnsupported, http.StatusBadRequest, fmt.Sprintf("未知 helper 类型: %s", kind))
	}
	if strings.TrimSpace(p) == "" {
		return "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, fmt.Sprintf("未配置 %s helper 脚本路径", kind))
	}
	return p, nil
}

var safeEnvAllowlist = []string{
	"PATH", "PATHEXT", "SYSTEMROOT", "COMSPEC", "WINDIR",
	"TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE",
	"LOCALAPPDATA", "APPDATA", "LANG", "LC_ALL",
	"SSL_CERT_FILE", "SSL_CERT_DIR", "PYTHONPATH",
}

func buildCleanEnv(extra map[string]string) []string {
	envMap := make(map[string]string)
	for _, key := range safeEnvAllowlist {
		if val, ok := os.LookupEnv(key); ok && val != "" {
			envMap[key] = val
		}
	}
	for k, v := range extra {
		if v != "" {
			envMap[k] = v
		}
	}
	// Critical on Windows: force UTF-8 stdio so pipes do not fail on cp936/cp1252.
	envMap["PYTHONUTF8"] = "1"
	envMap["PYTHONIOENCODING"] = "utf-8"
	out := make([]string, 0, len(envMap))
	for k, v := range envMap {
		out = append(out, k+"="+v)
	}
	return out
}

// Run executes a single NDJSON v1 helper invocation in an isolated task directory and process tree.
func (s *Supervisor) Run(
	ctx context.Context,
	kind HelperKind,
	operation string,
	timeout time.Duration,
	input any,
	credentials any,
	onProgress func(json.RawMessage),
) (json.RawMessage, error) {
	scriptPath, err := s.scriptFor(kind)
	if err != nil {
		return nil, err
	}
	pythonExe := strings.TrimSpace(s.cfg.PythonExe)
	if pythonExe == "" {
		pythonExe = "python"
	}
	if timeout <= 0 {
		timeout = DefaultTaskTimeout
	}

	if err := s.acquireSlot(ctx, kind); err != nil {
		return nil, err
	}
	defer s.releaseSlot(kind)

	taskID := model.NewUUID()
	workRoot := s.cfg.WorkRoot
	if strings.TrimSpace(workRoot) == "" {
		workRoot = os.TempDir()
	}
	taskDir := filepath.Join(workRoot, "melokeep-helper-"+taskID)
	if err := os.MkdirAll(taskDir, 0o700); err != nil {
		return nil, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "创建 helper 临时目录失败", err)
	}
	defer func() {
		_ = os.RemoveAll(taskDir)
	}()

	taskCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	cmd := exec.Command(pythonExe, scriptPath)
	cmd.Dir = taskDir
	cmd.Env = buildCleanEnv(s.cfg.ExtraSafeEnv)

	stdinR, stdin, err := os.Pipe()
	if err != nil {
		return nil, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "创建 helper stdin 管道失败", err)
	}
	stdoutR, stdoutW, err := os.Pipe()
	if err != nil {
		_ = stdinR.Close()
		_ = stdin.Close()
		return nil, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "创建 helper stdout 管道失败", err)
	}
	cmd.Stdin = stdinR
	cmd.Stdout = stdoutW
	stderrBuf := &limitedTailWriter{limit: s.cfg.Budgets.HelperMaxStderrBytes}
	cmd.Stderr = stderrBuf

	tree, err := StartInTree(cmd)
	_ = stdinR.Close()
	_ = stdoutW.Close()
	if err != nil {
		_ = stdin.Close()
		_ = stdoutR.Close()
		return nil, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "启动 helper 进程失败", err)
	}

	var killOnce sync.Once
	killTree := func() {
		killOnce.Do(func() {
			_ = tree.Kill()
		})
	}
	defer killTree()

	waitDone := make(chan error, 1)
	go func() {
		waitDone <- cmd.Wait()
	}()

	type readOutcome struct {
		result json.RawMessage
		err    error
	}
	helloCh := make(chan error, 1)
	termCh := make(chan readOutcome, 1)
	eofCh := make(chan error, 1)

	maxLine := s.cfg.Budgets.HelperMaxLineBytes
	maxOut := s.cfg.Budgets.HelperMaxOutputBytes

	go func() {
		defer stdoutR.Close()
		reader := bufio.NewReaderSize(stdoutR, 64*1024)
		totalBytes := 0

		helloRaw, err := readBoundedLine(reader, maxLine, &totalBytes, maxOut)
		if err != nil {
			helloCh <- fmt.Errorf("hello 读取失败: %w", err)
			return
		}
		var helloMsg frame
		if err := json.Unmarshal(helloRaw, &helloMsg); err != nil {
			helloCh <- fmt.Errorf("hello JSON 无效: %w", err)
			return
		}
		if helloMsg.V != ProtocolVersion || helloMsg.Type != "hello" || helloMsg.Helper != string(kind) {
			helloCh <- fmt.Errorf("hello 协议不匹配: v=%d type=%s helper=%s", helloMsg.V, helloMsg.Type, helloMsg.Helper)
			return
		}
		helloCh <- nil

		lastSeq := 0
		var gotTerminal bool
		for {
			raw, err := readBoundedLine(reader, maxLine, &totalBytes, maxOut)
			if err != nil {
				// cmd.Wait closes the stdout pipe after a normal terminal exit on
				// some platforms. Treat that close exactly like EOF once the
				// terminal frame has already been received; before then it is a
				// protocol violation and must remain visible.
				if errors.Is(err, io.EOF) || (gotTerminal && errors.Is(err, os.ErrClosed)) {
					if !gotTerminal {
						termCh <- readOutcome{err: model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "helper 未返回终态即退出")}
					}
					eofCh <- nil
					return
				}
				if !gotTerminal {
					termCh <- readOutcome{err: model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "读取 helper 输出失败", err)}
				}
				eofCh <- err
				return
			}
			var msg frame
			if err := json.Unmarshal(raw, &msg); err != nil {
				if !gotTerminal {
					termCh <- readOutcome{err: model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "helper 返回无效 JSON", err)}
				}
				eofCh <- err
				return
			}
			if msg.V != ProtocolVersion {
				if !gotTerminal {
					termCh <- readOutcome{err: model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "helper 协议版本不匹配")}
				}
				eofCh <- errors.New("protocol version mismatch")
				return
			}
			if msg.ID != taskID {
				if !gotTerminal {
					termCh <- readOutcome{err: model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "helper 响应任务 ID 不一致")}
				}
				eofCh <- errors.New("task id mismatch")
				return
			}
			if msg.Seq <= lastSeq {
				if !gotTerminal {
					termCh <- readOutcome{err: model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "helper 序号未单调递增")}
				}
				eofCh <- errors.New("non-monotonic seq")
				return
			}
			lastSeq = msg.Seq

			if gotTerminal {
				eofCh <- errors.New("helper emitted extra frame after terminal state")
				return
			}

			switch msg.Type {
			case "progress":
				if onProgress != nil {
					onProgress(msg.Data)
				}
			case "result":
				gotTerminal = true
				termCh <- readOutcome{result: msg.Data}
			case "error":
				gotTerminal = true
				if msg.Error == nil {
					termCh <- readOutcome{err: model.NewError(model.ErrInternal, http.StatusBadGateway, "helper 返回空错误体")}
				} else {
					termCh <- readOutcome{err: mapHelperError(msg.Error)}
				}
			default:
				termCh <- readOutcome{err: model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("未知 helper 帧类型: %s", msg.Type))}
				eofCh <- errors.New("unknown frame type")
				return
			}
		}
	}()

	// 1. Wait for hello within HelloTimeout (3s).
	helloTimer := time.NewTimer(s.cfg.HelloTimeout)
	select {
	case err := <-helloCh:
		helloTimer.Stop()
		if err != nil {
			_ = stdin.Close()
			killTree()
			<-waitDone
			return nil, s.attachStderr(model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, err.Error(), err), stderrBuf)
		}
	case <-helloTimer.C:
		_ = stdin.Close()
		killTree()
		<-waitDone
		return nil, s.attachStderr(model.NewError(model.ErrDeadline, http.StatusGatewayTimeout, "helper 启动握手超时"), stderrBuf)
	case <-taskCtx.Done():
		helloTimer.Stop()
		_ = stdin.Close()
		killTree()
		<-waitDone
		return nil, s.ctxErr(taskCtx.Err())
	}

	// 2. Send run envelope with credentials exclusively via stdin pipe.
	runEnv := frame{
		V:           ProtocolVersion,
		Type:        "run",
		ID:          taskID,
		Operation:   operation,
		TimeoutMS:   timeout.Milliseconds(),
		Input:       input,
		Credentials: credentials,
	}
	payload, err := json.Marshal(runEnv)
	if err != nil {
		_ = stdin.Close()
		killTree()
		<-waitDone
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "序列化 helper 请求失败", err)
	}
	payload = append(payload, '\n')
	if _, err := stdin.Write(payload); err != nil {
		_ = stdin.Close()
		killTree()
		<-waitDone
		return nil, s.attachStderr(model.WrapError(model.ErrTransport, http.StatusBadGateway, "写入 helper stdin 失败", err), stderrBuf)
	}

	// 3. Wait for terminal frame or cancellation/timeout.
	select {
	case out := <-termCh:
		_ = stdin.Close()
		// Helper must exit cleanly with exit code 0 within ExitGrace (1s) after terminal frame.
		exitTimer := time.NewTimer(s.cfg.ExitGrace)
		var waitErr error
		select {
		case waitErr = <-waitDone:
			exitTimer.Stop()
		case <-exitTimer.C:
			killTree()
			<-waitDone
			return nil, s.attachStderr(model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "helper 输出终态后未在 1 秒内退出"), stderrBuf)
		}
		eofErr := <-eofCh
		if eofErr != nil {
			return nil, s.attachStderr(model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "helper 终态后存在非法输出", eofErr), stderrBuf)
		}
		if out.err != nil {
			return nil, s.attachStderr(out.err, stderrBuf)
		}
		if waitErr != nil {
			return nil, s.attachStderr(model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "helper 进程异常退出", waitErr), stderrBuf)
		}
		return out.result, nil

	case <-taskCtx.Done():
		// Cooperative cancel first: send cancel frame and close stdin, wait CancelGrace (1s), then tree-kill.
		cancelPayload, _ := json.Marshal(frame{V: ProtocolVersion, Type: "cancel", ID: taskID})
		cancelPayload = append(cancelPayload, '\n')
		_, _ = stdin.Write(cancelPayload)
		_ = stdin.Close()

		grace := time.NewTimer(s.cfg.CancelGrace)
		select {
		case <-waitDone:
			grace.Stop()
		case <-grace.C:
			killTree()
			<-waitDone
		}
		return nil, s.attachStderr(s.ctxErr(taskCtx.Err()), stderrBuf)
	}
}

func (s *Supervisor) ctxErr(err error) error {
	if errors.Is(err, context.DeadlineExceeded) {
		return model.WrapError(model.ErrDeadline, http.StatusGatewayTimeout, "helper 执行超时", err)
	}
	return model.WrapError(model.ErrCancelled, 499, "helper 任务已取消", err)
}

func (s *Supervisor) attachStderr(err error, buf *limitedTailWriter) error {
	tail := redactStderr(buf.String())
	if tail == "" {
		return err
	}
	if de, ok := model.AsDomainError(err); ok {
		return &model.DomainError{
			Code:       de.Code,
			Status:     de.Status,
			Detail:     de.Detail + " | stderr=" + tail,
			Provider:   de.Provider,
			RetryAfter: de.RetryAfter,
			Retryable:  de.Retryable,
			Cause:      de.Cause,
		}
	}
	return err
}

func mapHelperError(he *HelperError) *model.DomainError {
	code := model.ErrInternal
	switch model.ErrorCode(he.Code) {
	case model.ErrCancelled, model.ErrDeadline, model.ErrRateLimited, model.ErrAuthRequired,
		model.ErrUnavailable, model.ErrUnsupported, model.ErrInvalidResponse,
		model.ErrTransport, model.ErrInvalidInput, model.ErrForbidden, model.ErrNotFound:
		code = model.ErrorCode(he.Code)
	}
	de := model.NewError(code, 0, he.Message)
	de.Retryable = he.Retryable
	return de
}

func readBoundedLine(r *bufio.Reader, maxLine int, total *int, maxTotal int) ([]byte, error) {
	var line []byte
	for {
		frag, isPrefix, err := r.ReadLine()
		if len(frag) > 0 {
			if len(line)+len(frag) > maxLine {
				return nil, fmt.Errorf("单行输出超过上限 %d 字节", maxLine)
			}
			*total += len(frag)
			if *total > maxTotal {
				return nil, fmt.Errorf("stdout 总输出超过上限 %d 字节", maxTotal)
			}
			line = append(line, frag...)
		}
		if err != nil {
			if errors.Is(err, io.EOF) && len(line) > 0 {
				return line, nil
			}
			return nil, err
		}
		if !isPrefix {
			return line, nil
		}
	}
}

type limitedTailWriter struct {
	mu    sync.Mutex
	buf   bytes.Buffer
	limit int
}

func (w *limitedTailWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.limit <= 0 {
		return len(p), nil
	}
	if len(p) >= w.limit {
		w.buf.Reset()
		w.buf.Write(p[len(p)-w.limit:])
		return len(p), nil
	}
	if w.buf.Len()+len(p) > w.limit {
		keep := w.buf.Bytes()[w.buf.Len()+len(p)-w.limit:]
		tmp := append([]byte(nil), keep...)
		w.buf.Reset()
		w.buf.Write(tmp)
	}
	return w.buf.Write(p)
}

func (w *limitedTailWriter) String() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return strings.TrimSpace(w.buf.String())
}

var (
	urlInLogRe = regexp.MustCompile(`https?://[^\s'"]+|socks5h?://[^\s'"]+`)
	secretKVRe = regexp.MustCompile(`(?i)(cookie|authorization|token|password|secret|api_key)\s*[:=]\s*[^\s,;]+`)
)

func redactStderr(s string) string {
	if s == "" {
		return ""
	}
	s = urlInLogRe.ReplaceAllStringFunc(s, config.RedactURL)
	s = secretKVRe.ReplaceAllStringFunc(s, func(match string) string {
		sep := "="
		if idx := strings.IndexAny(match, ":="); idx >= 0 {
			sep = match[idx : idx+1]
			return match[:idx] + sep + config.HiddenPlaceholder
		}
		return config.HiddenPlaceholder
	})
	if len(s) > 1024 {
		s = s[len(s)-1024:]
	}
	return s
}
