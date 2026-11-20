package dockerupdate

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

const (
	DefaultSocketPath      = "/var/run/docker.sock"
	DefaultHelperBinary    = "/usr/local/bin/melokeep"
	ReasonSocketMissing    = "请在 docker-compose.yml 中挂载 /var/run/docker.sock:/var/run/docker.sock 后重启容器，即可启用单容器一键更新"
	ReasonSocketPermDenied = "已检测到 /var/run/docker.sock 但无读写权限，请检查宿主机 docker.sock 权限"
)

var (
	mountInfoContainerIDRe = regexp.MustCompile(`/docker/containers/([0-9a-fA-F]{64})\b`)
	hex64Re                = regexp.MustCompile(`([0-9a-fA-F]{64})`)
)

// Options configures self-update probing and execution.
type Options struct {
	InContainer     bool
	SocketPath      string
	WebhookURL      string
	WebhookToken    string
	AllowSelfUpdate *bool
	SelfContainerID string
	MountInfoPath   string
	CgroupPath      string
	HostnameFunc    func() (string, error)
	HelperBinary    string
	Logger          func(level, message string)
}

// ContainerInspect represents the subset of Docker Engine /containers/{id}/json fields used for recreation.
type ContainerInspect struct {
	ID              string         `json:"Id"`
	Name            string         `json:"Name"`
	Image           string         `json:"Image"`
	Config          map[string]any `json:"Config"`
	HostConfig      map[string]any `json:"HostConfig"`
	NetworkSettings struct {
		Networks map[string]any `json:"Networks"`
	} `json:"NetworkSettings"`
}

func (c ContainerInspect) ConfigImage() string {
	if c.Config == nil {
		return ""
	}
	v, _ := c.Config["Image"].(string)
	return strings.TrimSpace(v)
}

// ImageInspect represents the subset of Docker Engine /images/{name}/json fields.
type ImageInspect struct {
	ID string `json:"Id"`
}

// Client talks to the Docker Engine API over a Unix domain socket or HTTP test endpoint.
type Client struct {
	socketPath string
	baseURL    string
	httpClient *http.Client
}

// NewClient constructs a Docker Engine API client for socketPath (Unix socket path or http(s):// test URL).
func NewClient(socketPath string) *Client {
	sock := strings.TrimSpace(socketPath)
	if sock == "" {
		sock = DefaultSocketPath
	}
	if isHTTPURL(sock) {
		return &Client{
			socketPath: sock,
			baseURL:    strings.TrimRight(sock, "/"),
			httpClient: &http.Client{Timeout: 10 * time.Minute},
		}
	}
	tr := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", sock)
		},
	}
	return &Client{
		socketPath: sock,
		baseURL:    "http://docker",
		httpClient: &http.Client{
			Transport: tr,
			Timeout:   10 * time.Minute,
		},
	}
}

func isHTTPURL(s string) bool {
	return strings.HasPrefix(s, "http://") || strings.HasPrefix(s, "https://")
}

func isPermissionError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, os.ErrPermission) {
		return true
	}
	lower := strings.ToLower(err.Error())
	return strings.Contains(lower, "permission denied") ||
		strings.Contains(lower, "access is denied") ||
		strings.Contains(lower, "operation not permitted") ||
		strings.Contains(lower, "eacces") ||
		strings.Contains(lower, "eperm")
}

func (c *Client) do(ctx context.Context, method, path string, body any) (*http.Response, error) {
	var rd io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rd = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, rd)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return c.httpClient.Do(req)
}

func decodeDockerError(resp *http.Response) error {
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	var payload struct {
		Message string `json:"message"`
	}
	if json.Unmarshal(raw, &payload) == nil && strings.TrimSpace(payload.Message) != "" {
		return fmt.Errorf("Docker API HTTP %d: %s", resp.StatusCode, strings.TrimSpace(payload.Message))
	}
	text := strings.TrimSpace(string(raw))
	if text == "" {
		text = http.StatusText(resp.StatusCode)
	}
	return fmt.Errorf("Docker API HTTP %d: %s", resp.StatusCode, text)
}

func (c *Client) Ping(ctx context.Context) error {
	pingCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	resp, err := c.do(pingCtx, http.MethodGet, "/_ping", nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return os.ErrPermission
	}
	if resp.StatusCode != http.StatusOK {
		return decodeDockerError(resp)
	}
	return nil
}

func (c *Client) InspectContainer(ctx context.Context, id string) (ContainerInspect, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return ContainerInspect{}, errors.New("容器 ID 不能为空")
	}
	resp, err := c.do(ctx, http.MethodGet, "/containers/"+url.PathEscape(id)+"/json", nil)
	if err != nil {
		return ContainerInspect{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ContainerInspect{}, decodeDockerError(resp)
	}
	var out ContainerInspect
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return ContainerInspect{}, err
	}
	return out, nil
}

func (c *Client) InspectImage(ctx context.Context, imageRef string) (ImageInspect, error) {
	resp, err := c.do(ctx, http.MethodGet, "/images/"+url.PathEscape(strings.TrimSpace(imageRef))+"/json", nil)
	if err != nil {
		return ImageInspect{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ImageInspect{}, decodeDockerError(resp)
	}
	var out ImageInspect
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return ImageInspect{}, err
	}
	return out, nil
}

type pullStreamEvent struct {
	Status         string `json:"status"`
	ID             string `json:"id"`
	Error          string `json:"error"`
	ProgressDetail struct {
		Current int64 `json:"current"`
		Total   int64 `json:"total"`
	} `json:"progressDetail"`
	ErrorDetail struct {
		Message string `json:"message"`
	} `json:"errorDetail"`
}

func (c *Client) PullImage(ctx context.Context, imageRef string, onProgress func(progress int, message string)) error {
	fromImage, tag := SplitImageRef(imageRef)
	q := url.Values{}
	q.Set("fromImage", fromImage)
	if tag != "" {
		q.Set("tag", tag)
	}
	resp, err := c.do(ctx, http.MethodPost, "/images/create?"+q.Encode(), nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return decodeDockerError(resp)
	}

	type layerBytes struct {
		current int64
		total   int64
	}
	layers := make(map[string]layerBytes)
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	eventsCount := 0

	for scanner.Scan() {
		line := bytes.TrimSpace(scanner.Bytes())
		if len(line) == 0 {
			continue
		}
		var ev pullStreamEvent
		if err := json.Unmarshal(line, &ev); err != nil {
			continue
		}
		errMsg := strings.TrimSpace(ev.ErrorDetail.Message)
		if errMsg == "" {
			errMsg = strings.TrimSpace(ev.Error)
		}
		if errMsg != "" {
			return errors.New(errMsg)
		}
		eventsCount++
		if ev.ID != "" && ev.ProgressDetail.Total > 0 {
			cur := ev.ProgressDetail.Current
			if cur > ev.ProgressDetail.Total {
				cur = ev.ProgressDetail.Total
			}
			layers[ev.ID] = layerBytes{current: cur, total: ev.ProgressDetail.Total}
		}
		var sumCur, sumTotal int64
		for _, lb := range layers {
			sumCur += lb.current
			sumTotal += lb.total
		}
		pct := 15
		if sumTotal > 0 {
			pct = 10 + int(float64(sumCur)/float64(sumTotal)*75.0)
		} else {
			pct = min(80, 10+eventsCount*5)
		}
		if pct < 10 {
			pct = 10
		}
		if pct > 85 {
			pct = 85
		}
		msg := strings.TrimSpace(ev.Status)
		if ev.ID != "" && msg != "" {
			msg = ev.ID + ": " + msg
		}
		if msg == "" {
			msg = "正在拉取镜像 " + imageRef
		}
		if onProgress != nil {
			onProgress(pct, msg)
		}
	}
	return scanner.Err()
}

func (c *Client) CreateContainer(ctx context.Context, name string, configBody map[string]any) (string, error) {
	path := "/containers/create"
	if trimmed := strings.TrimSpace(name); trimmed != "" {
		path += "?name=" + url.QueryEscape(trimmed)
	}
	resp, err := c.do(ctx, http.MethodPost, path, configBody)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		return "", decodeDockerError(resp)
	}
	var out struct {
		ID string `json:"Id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if strings.TrimSpace(out.ID) == "" {
		return "", errors.New("Docker 未返回新容器 ID")
	}
	return out.ID, nil
}

func (c *Client) StartContainer(ctx context.Context, id string) error {
	resp, err := c.do(ctx, http.MethodPost, "/containers/"+url.PathEscape(strings.TrimSpace(id))+"/start", nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusNotModified && resp.StatusCode != http.StatusOK {
		return decodeDockerError(resp)
	}
	return nil
}

func (c *Client) StopContainer(ctx context.Context, id string, timeoutSec int) error {
	if timeoutSec <= 0 {
		timeoutSec = 15
	}
	path := fmt.Sprintf("/containers/%s/stop?t=%d", url.PathEscape(strings.TrimSpace(id)), timeoutSec)
	resp, err := c.do(ctx, http.MethodPost, path, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusNotModified && resp.StatusCode != http.StatusOK {
		return decodeDockerError(resp)
	}
	return nil
}

func (c *Client) RenameContainer(ctx context.Context, id, newName string) error {
	path := fmt.Sprintf("/containers/%s/rename?name=%s", url.PathEscape(strings.TrimSpace(id)), url.QueryEscape(strings.TrimSpace(newName)))
	resp, err := c.do(ctx, http.MethodPost, path, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return decodeDockerError(resp)
	}
	return nil
}

func (c *Client) RemoveContainer(ctx context.Context, id string, force bool) error {
	f := "false"
	if force {
		f = "true"
	}
	path := fmt.Sprintf("/containers/%s?v=false&force=%s", url.PathEscape(strings.TrimSpace(id)), f)
	resp, err := c.do(ctx, http.MethodDelete, path, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNotFound {
		return decodeDockerError(resp)
	}
	return nil
}

func (c *Client) ConnectNetwork(ctx context.Context, networkName, containerID string, endpointConfig any) error {
	networkName = strings.TrimSpace(networkName)
	if networkName == "" || networkName == "bridge" || networkName == "host" || networkName == "none" {
		return nil
	}
	body := map[string]any{
		"Container":      containerID,
		"EndpointConfig": endpointConfig,
	}
	resp, err := c.do(ctx, http.MethodPost, "/networks/"+url.PathEscape(networkName)+"/connect", body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		return decodeDockerError(resp)
	}
	return nil
}

// NormalizeImageRef appends :latest if an image reference has neither a tag nor a digest.
func NormalizeImageRef(imageRef string) string {
	ref := strings.TrimSpace(imageRef)
	if ref == "" {
		return "ghcr.io/agonie0v0/melokeep:latest"
	}
	if strings.Contains(ref, "@") {
		return ref
	}
	lastSlash := strings.LastIndex(ref, "/")
	afterSlash := ref[lastSlash+1:]
	if !strings.Contains(afterSlash, ":") {
		return ref + ":latest"
	}
	return ref
}

// SplitImageRef splits a normalized image reference into (fromImage, tag).
func SplitImageRef(imageRef string) (string, string) {
	ref := NormalizeImageRef(imageRef)
	if idx := strings.Index(ref, "@"); idx >= 0 {
		return ref[:idx], ref[idx+1:]
	}
	lastSlash := strings.LastIndex(ref, "/")
	colon := strings.LastIndex(ref, ":")
	if colon > lastSlash {
		return ref[:colon], ref[colon+1:]
	}
	return ref, "latest"
}

// ExtractContainerIDFromMountInfo extracts a 64-character hex Docker container ID from /proc/self/mountinfo content.
func ExtractContainerIDFromMountInfo(content string) string {
	if m := mountInfoContainerIDRe.FindStringSubmatch(content); len(m) == 2 {
		return strings.ToLower(m[1])
	}
	return ""
}

// ExtractContainerIDFromCgroup extracts a 64-character hex container ID from /proc/self/cgroup content.
func ExtractContainerIDFromCgroup(content string) string {
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if m := mountInfoContainerIDRe.FindStringSubmatch(line); len(m) == 2 {
			return strings.ToLower(m[1])
		}
		matches := hex64Re.FindAllString(line, -1)
		if len(matches) > 0 {
			return strings.ToLower(matches[len(matches)-1])
		}
	}
	return ""
}

// Manager coordinates probing and running asynchronous container self-updates.
type Manager struct {
	opts  Options
	mu    sync.Mutex
	state *model.SelfUpdateStatusDTO
}

func NewManager(opts Options) *Manager {
	return &Manager{opts: opts}
}

func (m *Manager) log(level, msg string) {
	if m != nil && m.opts.Logger != nil && strings.TrimSpace(msg) != "" {
		m.opts.Logger(level, msg)
	}
}

func (m *Manager) effectiveWebhook() (string, string) {
	u := strings.TrimSpace(m.opts.WebhookURL)
	if u == "" {
		u = strings.TrimSpace(os.Getenv("MELOKEEP_UPDATE_WEBHOOK_URL"))
	}
	tok := strings.TrimSpace(m.opts.WebhookToken)
	if tok == "" {
		tok = strings.TrimSpace(os.Getenv("MELOKEEP_UPDATE_WEBHOOK_TOKEN"))
	}
	return u, tok
}

func (m *Manager) effectiveSocketPath() string {
	if s := strings.TrimSpace(m.opts.SocketPath); s != "" {
		return s
	}
	if s := strings.TrimSpace(os.Getenv("MELOKEEP_DOCKER_SOCKET")); s != "" {
		return s
	}
	return DefaultSocketPath
}

func (m *Manager) isSelfUpdateAllowed() bool {
	if m.opts.AllowSelfUpdate != nil {
		return *m.opts.AllowSelfUpdate
	}
	raw := strings.ToLower(strings.TrimSpace(os.Getenv("MELOKEEP_ALLOW_DOCKER_SELF_UPDATE")))
	switch raw {
	case "0", "false", "no", "off":
		return false
	default:
		return true
	}
}

func (m *Manager) isInContainer() bool {
	if m.opts.InContainer {
		return true
	}
	if strings.TrimSpace(os.Getenv("MELOKEEP_CONTAINER")) != "" {
		return true
	}
	if strings.TrimSpace(m.opts.SocketPath) != "" || strings.TrimSpace(os.Getenv("MELOKEEP_DOCKER_SOCKET")) != "" {
		return true
	}
	return false
}

// Probe evaluates whether webhook or docker_socket self-update is currently available.
func (m *Manager) Probe(ctx context.Context) model.SelfUpdateStatusDTO {
	if webhookURL, _ := m.effectiveWebhook(); webhookURL != "" {
		return model.SelfUpdateStatusDTO{
			Supported: true,
			Mode:      "webhook",
			Stage:     "idle",
		}
	}
	if !m.isSelfUpdateAllowed() || !m.isInContainer() {
		return model.SelfUpdateStatusDTO{
			Supported: false,
			Mode:      "none",
			Stage:     "idle",
			Reason:    ReasonSocketMissing,
		}
	}
	sockPath := m.effectiveSocketPath()
	if !isHTTPURL(sockPath) {
		if _, err := os.Stat(sockPath); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return model.SelfUpdateStatusDTO{
					Supported: false,
					Mode:      "none",
					Stage:     "idle",
					Reason:    ReasonSocketMissing,
				}
			}
			if isPermissionError(err) {
				return model.SelfUpdateStatusDTO{
					Supported: false,
					Mode:      "docker_socket",
					Stage:     "idle",
					Reason:    ReasonSocketPermDenied,
				}
			}
			return model.SelfUpdateStatusDTO{
				Supported: false,
				Mode:      "docker_socket",
				Stage:     "idle",
				Reason:    "无法访问 docker.sock：" + err.Error(),
			}
		}
	}
	client := NewClient(sockPath)
	if err := client.Ping(ctx); err != nil {
		if isPermissionError(err) {
			return model.SelfUpdateStatusDTO{
				Supported: false,
				Mode:      "docker_socket",
				Stage:     "idle",
				Reason:    ReasonSocketPermDenied,
			}
		}
		return model.SelfUpdateStatusDTO{
			Supported: false,
			Mode:      "docker_socket",
			Stage:     "idle",
			Reason:    "已检测到 docker.sock 但无法连接 Docker 守护进程：" + err.Error(),
		}
	}
	return model.SelfUpdateStatusDTO{
		Supported: true,
		Mode:      "docker_socket",
		Stage:     "idle",
	}
}

// Status returns the current self-update capability and active/last task state.
func (m *Manager) Status(ctx context.Context) model.SelfUpdateStatusDTO {
	m.mu.Lock()
	var current *model.SelfUpdateStatusDTO
	if m.state != nil {
		cp := *m.state
		current = &cp
	}
	m.mu.Unlock()

	if current != nil && current.InProgress {
		return *current
	}
	probe := m.Probe(ctx)
	if current != nil {
		current.Supported = probe.Supported
		current.Mode = probe.Mode
		current.Reason = probe.Reason
		return *current
	}
	return probe
}

// Start initiates an asynchronous container self-update if supported and not already running.
func (m *Manager) Start(ctx context.Context, targetImageOverride string) (model.SelfUpdateStatusDTO, error) {
	m.mu.Lock()
	if m.state != nil && m.state.InProgress {
		cp := *m.state
		m.mu.Unlock()
		return cp, model.NewError(model.ErrConflict, http.StatusConflict, "系统自更新已在执行中，请勿重复触发")
	}
	m.mu.Unlock()

	probe := m.Probe(ctx)
	if !probe.Supported {
		reason := strings.TrimSpace(probe.Reason)
		if reason == "" {
			reason = ReasonSocketMissing
		}
		return probe, model.NewError(model.ErrConflict, http.StatusConflict, reason)
	}

	m.mu.Lock()
	if m.state != nil && m.state.InProgress {
		cp := *m.state
		m.mu.Unlock()
		return cp, model.NewError(model.ErrConflict, http.StatusConflict, "系统自更新已在执行中，请勿重复触发")
	}
	stage := "pulling"
	msg := "正在连接 Docker 守护进程并准备拉取最新镜像…"
	if probe.Mode == "webhook" {
		stage = "restarting"
		msg = "正在触发外部更新 Webhook…"
	}
	next := model.SelfUpdateStatusDTO{
		Supported:  true,
		Mode:       probe.Mode,
		InProgress: true,
		Stage:      stage,
		Message:    msg,
		Progress:   5,
		StartedAt:  time.Now().UTC().Format(time.RFC3339),
	}
	m.state = &next
	m.mu.Unlock()

	go m.runAsync(probe.Mode, strings.TrimSpace(targetImageOverride))
	return next, nil
}

func (m *Manager) updateState(fn func(st *model.SelfUpdateStatusDTO)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.state == nil {
		m.state = &model.SelfUpdateStatusDTO{}
	}
	fn(m.state)
}

func (m *Manager) failState(err error) {
	msg := ""
	if err != nil {
		msg = err.Error()
	}
	m.log("error", "系统自更新失败："+msg)
	m.updateState(func(st *model.SelfUpdateStatusDTO) {
		st.InProgress = false
		st.Stage = "failed"
		st.Error = msg
		st.Message = "自更新失败：" + msg
	})
}

func (m *Manager) runAsync(mode, targetImageOverride string) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()

	if mode == "webhook" {
		m.runWebhook(ctx)
		return
	}
	m.runDockerSocket(ctx, targetImageOverride)
}

func (m *Manager) runWebhook(ctx context.Context) {
	webhookURL, token := m.effectiveWebhook()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, webhookURL, nil)
	if err != nil {
		m.failState(fmt.Errorf("构造 Webhook 请求失败: %w", err))
		return
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		m.failState(fmt.Errorf("调用更新 Webhook 失败: %w", err))
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		m.failState(fmt.Errorf("更新 Webhook 返回异常状态 HTTP %d", resp.StatusCode))
		return
	}
	m.log("info", "已成功触发外部更新 Webhook，等待容器重启")
	m.updateState(func(st *model.SelfUpdateStatusDTO) {
		st.InProgress = true
		st.Stage = "restarting"
		st.Progress = 95
		st.Message = "已触发更新 Webhook，正在等待新版本容器重启…"
	})
}

func (m *Manager) resolveSelfContainer(ctx context.Context, client *Client) (ContainerInspect, error) {
	var candidates []string
	addCand := func(id string) {
		id = strings.TrimSpace(id)
		if id == "" {
			return
		}
		for _, existing := range candidates {
			if existing == id {
				return
			}
		}
		candidates = append(candidates, id)
	}

	addCand(m.opts.SelfContainerID)
	addCand(os.Getenv("MELOKEEP_CONTAINER_ID"))

	mountInfoPath := m.opts.MountInfoPath
	if mountInfoPath == "" {
		mountInfoPath = "/proc/self/mountinfo"
	}
	if raw, err := os.ReadFile(mountInfoPath); err == nil {
		addCand(ExtractContainerIDFromMountInfo(string(raw)))
	}

	cgroupPath := m.opts.CgroupPath
	if cgroupPath == "" {
		cgroupPath = "/proc/self/cgroup"
	}
	if raw, err := os.ReadFile(cgroupPath); err == nil {
		addCand(ExtractContainerIDFromCgroup(string(raw)))
	}

	hostFn := m.opts.HostnameFunc
	if hostFn == nil {
		hostFn = os.Hostname
	}
	if hn, err := hostFn(); err == nil {
		addCand(hn)
	}

	var lastErr error
	for _, cand := range candidates {
		inspected, err := client.InspectContainer(ctx, cand)
		if err == nil && strings.TrimSpace(inspected.ID) != "" {
			return inspected, nil
		}
		lastErr = err
	}
	if lastErr != nil {
		return ContainerInspect{}, fmt.Errorf("无法定位当前容器 ID: %w", lastErr)
	}
	return ContainerInspect{}, errors.New("无法定位当前容器 ID")
}

func (m *Manager) runDockerSocket(ctx context.Context, targetImageOverride string) {
	sockPath := m.effectiveSocketPath()
	client := NewClient(sockPath)

	selfCont, err := m.resolveSelfContainer(ctx, client)
	if err != nil {
		m.failState(err)
		return
	}

	targetImage := strings.TrimSpace(targetImageOverride)
	if targetImage == "" {
		targetImage = NormalizeImageRef(selfCont.ConfigImage())
	} else {
		targetImage = NormalizeImageRef(targetImage)
	}

	m.log("info", "开始拉取最新镜像: "+targetImage)
	m.updateState(func(st *model.SelfUpdateStatusDTO) {
		st.TargetImage = targetImage
		st.Stage = "pulling"
		st.Progress = 10
		st.Message = "正在拉取最新镜像 " + targetImage + "…"
	})

	err = client.PullImage(ctx, targetImage, func(progress int, message string) {
		m.updateState(func(st *model.SelfUpdateStatusDTO) {
			st.Stage = "pulling"
			st.Progress = progress
			st.Message = message
		})
	})
	if err != nil {
		m.failState(fmt.Errorf("拉取镜像 %s 失败: %w", targetImage, err))
		return
	}

	pulledImg, err := client.InspectImage(ctx, targetImage)
	if err != nil {
		m.failState(fmt.Errorf("校验拉取后的镜像失败: %w", err))
		return
	}
	if strings.TrimSpace(pulledImg.ID) != "" && strings.TrimSpace(pulledImg.ID) == strings.TrimSpace(selfCont.Image) {
		m.log("info", "容器镜像已是最新 ("+targetImage+")，无需重建容器")
		m.updateState(func(st *model.SelfUpdateStatusDTO) {
			st.InProgress = false
			st.Stage = "up_to_date"
			st.Progress = 100
			st.Message = "当前运行的容器镜像已是最新版本，无需重启"
		})
		return
	}

	m.log("info", "新镜像拉取完成，正在创建一次性更新助手容器")
	m.updateState(func(st *model.SelfUpdateStatusDTO) {
		st.Stage = "recreating"
		st.Progress = 90
		st.Message = "镜像拉取完成，正在创建平滑更新助手容器…"
	})

	helperBin := strings.TrimSpace(m.opts.HelperBinary)
	if helperBin == "" {
		helperBin = DefaultHelperBinary
	}
	bindSource := sockPath
	if isHTTPURL(sockPath) {
		bindSource = DefaultSocketPath
	}
	helperName := fmt.Sprintf("melokeep-updater-%d", time.Now().Unix())
	helperSpec := map[string]any{
		"Image":      targetImage,
		"Entrypoint": []string{helperBin, "self-update-helper"},
		"Cmd":        []string{"--socket", sockPath, "--target", selfCont.ID, "--image", targetImage},
		"HostConfig": map[string]any{
			"Binds":      []string{bindSource + ":" + DefaultSocketPath},
			"AutoRemove": true,
		},
	}
	helperID, err := client.CreateContainer(ctx, helperName, helperSpec)
	if err != nil && strings.TrimSpace(selfCont.Image) != "" {
		helperSpec["Image"] = selfCont.Image
		helperID, err = client.CreateContainer(ctx, helperName, helperSpec)
	}
	if err != nil {
		m.failState(fmt.Errorf("创建更新助手容器失败: %w", err))
		return
	}
	if err := client.StartContainer(ctx, helperID); err != nil {
		_ = client.RemoveContainer(ctx, helperID, true)
		m.failState(fmt.Errorf("启动更新助手容器失败: %w", err))
		return
	}

	m.log("info", "更新助手容器已启动 ("+helperName+")，即将切换并重启服务")
	m.updateState(func(st *model.SelfUpdateStatusDTO) {
		st.InProgress = true
		st.Stage = "restarting"
		st.Progress = 95
		st.Message = "正在平滑切换并重启新版本容器，页面将在服务恢复后自动刷新…"
	})
}

// HelperOptions configures the one-shot self-update-helper process.
type HelperOptions struct {
	SocketPath     string
	TargetID       string
	TargetImage    string
	WaitBeforeStop time.Duration
}

// RunHelper stops the target container, renames it to a rollback name, creates & starts the replacement
// container with the original configuration and name, and rolls back automatically if creation or startup fails.
func RunHelper(ctx context.Context, opts HelperOptions) error {
	sockPath := strings.TrimSpace(opts.SocketPath)
	if sockPath == "" {
		sockPath = DefaultSocketPath
	}
	if !isHTTPURL(sockPath) {
		if _, err := os.Stat(sockPath); err != nil && sockPath != DefaultSocketPath {
			if _, dErr := os.Stat(DefaultSocketPath); dErr == nil {
				sockPath = DefaultSocketPath
			}
		}
	}
	targetID := strings.TrimSpace(opts.TargetID)
	if targetID == "" {
		return errors.New("缺少 --target 目标容器 ID")
	}
	targetImage := strings.TrimSpace(opts.TargetImage)
	if targetImage == "" {
		return errors.New("缺少 --image 目标镜像名称")
	}
	if opts.WaitBeforeStop > 0 {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(opts.WaitBeforeStop):
		}
	}

	client := NewClient(sockPath)
	orig, err := client.InspectContainer(ctx, targetID)
	if err != nil {
		return fmt.Errorf("读取目标容器信息失败: %w", err)
	}
	origName := strings.TrimPrefix(strings.TrimSpace(orig.Name), "/")
	if origName == "" {
		origName = "melokeep"
	}

	if err := client.StopContainer(ctx, orig.ID, 15); err != nil {
		return fmt.Errorf("停止旧容器失败: %w", err)
	}

	rollbackName := fmt.Sprintf("%s-rollback-%d", origName, time.Now().Unix())
	if err := client.RenameContainer(ctx, orig.ID, rollbackName); err != nil {
		_ = client.StartContainer(ctx, orig.ID)
		return fmt.Errorf("重命名旧容器为回滚备份失败: %w", err)
	}

	createBody := make(map[string]any, len(orig.Config)+2)
	for k, v := range orig.Config {
		createBody[k] = v
	}
	createBody["Image"] = targetImage
	if hn, ok := createBody["Hostname"].(string); ok && hn != "" {
		if strings.HasPrefix(strings.ToLower(orig.ID), strings.ToLower(strings.TrimSpace(hn))) {
			createBody["Hostname"] = ""
		}
	}
	if orig.HostConfig != nil {
		createBody["HostConfig"] = orig.HostConfig
	}

	newID, createErr := client.CreateContainer(ctx, origName, createBody)
	if createErr == nil {
		for netName, epCfg := range orig.NetworkSettings.Networks {
			_ = client.ConnectNetwork(ctx, netName, newID, epCfg)
		}
		if startErr := client.StartContainer(ctx, newID); startErr != nil {
			createErr = startErr
		}
	}

	if createErr != nil {
		if newID != "" {
			_ = client.RemoveContainer(ctx, newID, true)
		}
		_ = client.RenameContainer(ctx, orig.ID, origName)
		_ = client.StartContainer(ctx, orig.ID)
		return fmt.Errorf("启动新容器失败，已回滚恢复旧容器: %w", createErr)
	}

	_ = client.RemoveContainer(ctx, orig.ID, true)
	return nil
}
