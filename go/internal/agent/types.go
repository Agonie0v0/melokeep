package agent

import (
	"bytes"
	"context"
	"encoding/base64"
	"net/http"
	"net/url"
	"regexp"
	"strings"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

const (
	NamespaceSettings = "ai_settings"
	NamespaceSession  = "ai_session"
	NamespaceWaiting  = "ai_waiting"
	MainSettingsID    = "main"
	MaxSessions       = 50
	MaxImageBytes     = 5 * 1024 * 1024
)

type SessionStatus string

const (
	StatusIdle          SessionStatus = "idle"
	StatusRunning       SessionStatus = "running"
	StatusWaitingTasks  SessionStatus = "waiting_tasks"
	StatusComplete      SessionStatus = "complete"
	StatusStopping      SessionStatus = "stopping"
	StatusInterrupted   SessionStatus = "interrupted"
	StatusFailed        SessionStatus = "failed"
	StatusNeedsDecision SessionStatus = "needs_decision"
)

// Settings stores OpenAI-compatible AI assistant settings. APIKey is never returned in public DTOs.
type Settings struct {
	Enabled     bool   `json:"enabled"`
	BaseURL     string `json:"base_url"`
	Model       string `json:"model"`
	APIKey      string `json:"api_key,omitempty"`
	ClearKey    bool   `json:"clear_key,omitempty"`
	APIType     string `json:"api_type"`   // "chat_completions" | "responses"
	ProxyMode   string `json:"proxy_mode"` // "direct" | "project"
	Stream      bool   `json:"stream"`
	Preferences string `json:"preferences"`
}

func DefaultSettings() Settings {
	return Settings{
		APIType:   "chat_completions",
		ProxyMode: "direct",
	}
}

// PublicSettings is the redacted DTO returned by /api/agent/settings.
type PublicSettings struct {
	Enabled                bool   `json:"enabled"`
	BaseURL                string `json:"base_url"`
	Model                  string `json:"model"`
	APIType                string `json:"api_type"`
	ProxyMode              string `json:"proxy_mode"`
	Stream                 bool   `json:"stream"`
	Preferences            string `json:"preferences"`
	KeyConfigured          bool   `json:"key_configured"`
	ProjectProxyConfigured bool   `json:"project_proxy_configured"`
}

func (s Settings) Validate() error {
	if len(s.BaseURL) > 2000 || len(s.Model) > 200 || len(s.APIKey) > 2000 || len(s.Preferences) > 1000 {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AI 配置字段长度超出限制")
	}
	if s.APIType != "" && s.APIType != "chat_completions" && s.APIType != "responses" {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "api_type 仅支持 chat_completions 或 responses")
	}
	if s.ProxyMode != "" && s.ProxyMode != "direct" && s.ProxyMode != "project" {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "proxy_mode 仅支持 direct 或 project")
	}
	if raw := strings.TrimSpace(s.BaseURL); raw != "" {
		if strings.Contains(raw, "\\") {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写不含凭据和查询参数的 HTTP(S) API 地址")
		}
		u, err := url.Parse(raw)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Port() == "0" {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写不含凭据和查询参数的 HTTP(S) API 地址")
		}
	}
	return nil
}

func (s Settings) Normalized() Settings {
	out := s
	out.BaseURL = strings.TrimRight(strings.TrimSpace(out.BaseURL), "/")
	out.Model = strings.TrimSpace(out.Model)
	out.Preferences = strings.TrimSpace(out.Preferences)
	if out.APIType == "" {
		out.APIType = "chat_completions"
	}
	if out.ProxyMode == "" {
		out.ProxyMode = "direct"
	}
	out.ClearKey = false
	return out
}

func MergeSettings(existing Settings, patch Settings) (Settings, error) {
	if err := patch.Validate(); err != nil {
		return Settings{}, err
	}
	merged := patch.Normalized()
	if patch.ClearKey {
		merged.APIKey = ""
	} else if patch.APIKey == "" || patch.APIKey == config.HiddenPlaceholder {
		merged.APIKey = existing.APIKey
	} else {
		merged.APIKey = patch.APIKey
	}
	merged.ClearKey = false
	return merged, nil
}

func (s Settings) Public(projectProxy string) PublicSettings {
	return PublicSettings{
		Enabled:                s.Enabled,
		BaseURL:                s.BaseURL,
		Model:                  s.Model,
		APIType:                s.APIType,
		ProxyMode:              s.ProxyMode,
		Stream:                 s.Stream,
		Preferences:            s.Preferences,
		KeyConfigured:          strings.TrimSpace(s.APIKey) != "",
		ProjectProxyConfigured: strings.TrimSpace(projectProxy) != "",
	}
}

type TurnRequest struct {
	Message      string   `json:"message"`
	Image        string   `json:"image,omitempty"`
	AllowActions *bool    `json:"allow_actions,omitempty"`
	TrackIDs     []string `json:"track_ids,omitempty"`
	RequestID    string   `json:"request_id,omitempty"`
}

func (r TurnRequest) ActionsAllowed() bool {
	if r.AllowActions == nil {
		return true
	}
	return *r.AllowActions
}

var imageDataPattern = regexp.MustCompile(`^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$`)

func ValidateTurnRequest(req TurnRequest, maxImagePayloadBytes int) error {
	if len(req.Message) > 8000 {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "消息长度不能超过 8000 字符")
	}
	if len(req.TrackIDs) > 30 {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "单轮最多选择 30 首歌曲")
	}
	if strings.TrimSpace(req.Message) == "" && strings.TrimSpace(req.Image) == "" {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请输入需求或选择图片")
	}
	if req.Image != "" {
		if maxImagePayloadBytes <= 0 {
			maxImagePayloadBytes = 5 << 20
		}
		m := imageDataPattern.FindStringSubmatch(req.Image)
		if m == nil {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "仅支持 PNG、JPEG、WebP 图片")
		}
		raw, err := base64.StdEncoding.DecodeString(m[2])
		if err != nil || len(raw) == 0 || len(raw) > MaxImageBytes || len(raw) > maxImagePayloadBytes {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "图片格式无效或超过 5 MB")
		}
		switch m[1] {
		case "png":
			if !bytes.HasPrefix(raw, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}) {
				return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "图片格式无效或超过 5 MB")
			}
		case "jpeg":
			if !bytes.HasPrefix(raw, []byte{0xff, 0xd8, 0xff}) {
				return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "图片格式无效或超过 5 MB")
			}
		case "webp":
			if len(raw) < 12 || !bytes.HasPrefix(raw, []byte("RIFF")) || !bytes.Equal(raw[8:12], []byte("WEBP")) {
				return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "图片格式无效或超过 5 MB")
			}
		}
	}
	return nil
}

type PlanStep struct {
	Title  string `json:"title"`
	Status string `json:"status"`
}

type Plan struct {
	Goal  string     `json:"goal"`
	Steps []PlanStep `json:"steps"`
}

type SessionMessage struct {
	Role      string `json:"role"`
	Content   string `json:"content"`
	HasImage  bool   `json:"has_image,omitempty"`
	TurnID    string `json:"turn_id,omitempty"`
	CreatedAt int64  `json:"created_at"`
}

type ExecutionEvent struct {
	ID        string         `json:"id"`
	TurnID    string         `json:"turn_id"`
	CreatedAt int64          `json:"created_at"`
	Tool      string         `json:"tool"`
	Operation string         `json:"operation,omitempty"`
	Status    string         `json:"status"` // running, complete, failed, interrupted, operation_unknown
	Detail    string         `json:"detail"`
	Result    map[string]any `json:"result,omitempty"`
}

// PendingWrite records an in-flight write tool call before execution so crash recovery
// can reconcile actual side effects instead of blindly replaying.
type PendingWrite struct {
	TurnID         string         `json:"turn_id"`
	ToolCallID     string         `json:"tool_call_id"`
	ToolName       string         `json:"tool_name"`
	Operation      string         `json:"operation"`
	IdempotencyKey string         `json:"idempotency_key"`
	State          string         `json:"state"` // started, completed, unknown
	Payload        map[string]any `json:"payload,omitempty"`
	StartedAt      int64          `json:"started_at"`
}

type WaitingCheckpoint struct {
	SessionID    string   `json:"session_id"`
	TurnID       string   `json:"turn_id"`
	Checkpoint   string   `json:"checkpoint"` // executing, waiting_tasks, needs_decision, stopped
	WatchIDs     []string `json:"watch_ids,omitempty"`
	Decision     string   `json:"decision,omitempty"`
	ResumePrompt string   `json:"resume_prompt,omitempty"`
	UpdatedAt    int64    `json:"updated_at"`
}

// LLMMessage represents a model conversation entry stored in sanitized session history.
type LLMMessage struct {
	Role            string        `json:"role"`
	Content         string        `json:"content,omitempty"`
	ToolCallID      string        `json:"tool_call_id,omitempty"`
	ToolCalls       []LLMToolCall `json:"tool_calls,omitempty"`
	ResponsesOutput []any         `json:"_responses_output,omitempty"`
	ResponsesModel  string        `json:"_responses_model,omitempty"`
	ResponsesOrigin string        `json:"_responses_origin,omitempty"`
}

type LLMToolCall struct {
	ID       string          `json:"id"`
	Type     string          `json:"type"`
	Function LLMFunctionCall `json:"function"`
}

type LLMFunctionCall struct {
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

type Session struct {
	ID           string           `json:"id"`
	Title        string           `json:"title"`
	Status       SessionStatus    `json:"status"`
	Detail       string           `json:"detail"`
	Partial      string           `json:"partial,omitempty"`
	Decision     string           `json:"decision,omitempty"`
	Plan         *Plan            `json:"plan,omitempty"`
	Messages     []SessionMessage `json:"messages"`
	Events       []ExecutionEvent `json:"events"`
	History      []LLMMessage     `json:"history,omitempty"`
	TrackIDs     []string         `json:"track_ids,omitempty"`
	Paths        []string         `json:"paths,omitempty"`
	WatchIDs     []string         `json:"watch_ids,omitempty"`
	ActiveTurnID string           `json:"active_turn_id,omitempty"`
	RequestID    string           `json:"request_id,omitempty"`
	Checkpoint   string           `json:"checkpoint,omitempty"`
	ResumeTurn   *TurnRequest     `json:"resume_turn,omitempty"`
	PendingWrite *PendingWrite    `json:"pending_write,omitempty"`
	ActorChannel string           `json:"actor_channel,omitempty"`
	ActorUserID  string           `json:"actor_user_id,omitempty"`
	Revision     int64            `json:"-"`
}

// PublicSessionDTO omits internal history, track/path allowlists, and pending internal fields.
type PublicSessionDTO struct {
	ID       string           `json:"id"`
	Title    string           `json:"title"`
	Status   SessionStatus    `json:"status"`
	Detail   string           `json:"detail"`
	Partial  string           `json:"partial,omitempty"`
	Decision string           `json:"decision,omitempty"`
	Plan     *Plan            `json:"plan,omitempty"`
	Messages []SessionMessage `json:"messages"`
	Events   []ExecutionEvent `json:"events"`
}

type SessionSummaryDTO struct {
	ID     string        `json:"id"`
	Title  string        `json:"title"`
	Status SessionStatus `json:"status"`
}

func (s Session) Public() PublicSessionDTO {
	return PublicSessionDTO{
		ID:       s.ID,
		Title:    s.Title,
		Status:   s.Status,
		Detail:   s.Detail,
		Partial:  s.Partial,
		Decision: s.Decision,
		Plan:     s.Plan,
		Messages: s.Messages,
		Events:   s.Events,
	}
}

type EventDeleteRequest struct {
	IDs      []string `json:"ids"`
	Indices  []int    `json:"indices"`
	Statuses []string `json:"statuses"`
	ClearAll bool     `json:"clear_all"`
}

type ImportSearchRequest struct {
	Title  string `json:"title"`
	Artist string `json:"artist"`
}

type ImportExecuteRequest struct {
	TrackIDs   []string `json:"track_ids"`
	PlaylistID string   `json:"playlist_id"`
	Download   bool     `json:"download"`
	Organize   bool     `json:"organize"`
}

// TaskState describes observed state of an external/background task during wait or startup recovery.
type TaskState struct {
	ID         string         `json:"id"`
	Status     string         `json:"status"`
	Detail     string         `json:"detail"`
	Processing map[string]any `json:"processing,omitempty"`
	Title      string         `json:"title,omitempty"`
	Completed  bool           `json:"completed"`
	Terminal   bool           `json:"terminal"`
}

// WriteToggleFunc dynamically evaluates whether write actions are currently permitted for actor.
// Returning false immediately revokes subsequent write tool calls.
type WriteToggleFunc func(ctx context.Context, actor auth.Actor) (allowed bool, reason string)

// TaskStateLookupFunc queries actual download/library task state for watch loops and recovery reconciliation.
type TaskStateLookupFunc func(ctx context.Context, ids []string) ([]TaskState, error)

// PendingWriteReconciler checks whether a previously started write operation actually committed.
type PendingWriteReconciler func(ctx context.Context, pw PendingWrite) (resolved bool, outcome map[string]any, err error)

// ProxyResolver returns the active project proxy URL when ProxyMode == "project".
type ProxyResolver func() string
