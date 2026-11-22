package agent

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
	"sort"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/transport"
)

type ToolDef struct {
	Type     string          `json:"type"`
	Function ToolFunctionDef `json:"function"`
}

type ToolFunctionDef struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	Parameters  map[string]any `json:"parameters"`
}

type Client struct {
	pool    *transport.Pool
	budgets config.ResourceBudgets
}

func NewClient(pool *transport.Pool, budgets config.ResourceBudgets) *Client {
	if pool == nil {
		pool = transport.NewPool()
	}
	if budgets.DefaultJSONMaxBytes <= 0 {
		budgets = config.DefaultResourceBudgets()
	}
	return &Client{pool: pool, budgets: budgets}
}

// APIURL normalizes base_url by trimming known OpenAI suffixes and appending endpoint.
func APIURL(baseURL, endpoint string) string {
	base := strings.TrimRight(strings.TrimSpace(baseURL), "/")
	for _, suffix := range []string{"/chat/completions", "/responses", "/models"} {
		if strings.HasSuffix(base, suffix) {
			base = strings.TrimSuffix(base, suffix)
			break
		}
	}
	return base + "/" + strings.TrimLeft(endpoint, "/")
}

func internalOriginForURL(rawURL string) (string, error) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AI API 地址格式无效")
	}
	return strings.ToLower(u.Scheme) + "://" + strings.ToLower(u.Host), nil
}

func (c *Client) Complete(
	ctx context.Context,
	settings Settings,
	proxyURL string,
	configRev string,
	messages []LLMMessage,
	tools []ToolDef,
	imageDataURL string,
	onDelta func(string),
) (LLMMessage, error) {
	if strings.TrimSpace(settings.BaseURL) == "" || strings.TrimSpace(settings.Model) == "" {
		return LLMMessage{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写 API 地址和模型")
	}
	if settings.APIType == "responses" {
		return c.completeResponses(ctx, settings, proxyURL, configRev, messages, tools, imageDataURL)
	}
	if settings.Stream && onDelta != nil {
		return c.completeChatStream(ctx, settings, proxyURL, configRev, messages, tools, imageDataURL, onDelta)
	}
	return c.completeChat(ctx, settings, proxyURL, configRev, messages, tools, imageDataURL)
}

func (c *Client) ListModels(ctx context.Context, settings Settings, proxyURL, configRev string) ([]string, string, error) {
	if strings.TrimSpace(settings.BaseURL) == "" {
		return nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请先填写 API 地址")
	}
	raw, err := c.doJSON(ctx, http.MethodGet, APIURL(settings.BaseURL, "models"), settings, proxyURL, configRev, nil, false)
	if err != nil {
		return nil, "", err
	}
	var resp struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil || resp.Data == nil || len(resp.Data) > 10000 {
		return nil, "", model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "服务商未返回有效模型列表，可以手动填写模型 ID")
	}
	seen := make(map[string]struct{}, len(resp.Data))
	models := make([]string, 0, len(resp.Data))
	for _, item := range resp.Data {
		if len(item.ID) > 0 && len(item.ID) <= 200 {
			if _, ok := seen[item.ID]; !ok {
				seen[item.ID] = struct{}{}
				models = append(models, item.ID)
			}
		}
	}
	sort.Strings(models)
	if len(models) > 2000 {
		models = models[:2000]
	}
	return models, fmt.Sprintf("服务商返回 %d 个模型；工具与图片能力请通过实际对话确认。", len(models)), nil
}

func (c *Client) completeChat(
	ctx context.Context,
	settings Settings,
	proxyURL, configRev string,
	messages []LLMMessage,
	tools []ToolDef,
	imageDataURL string,
) (LLMMessage, error) {
	payload := map[string]any{
		"model":    settings.Model,
		"messages": encodeChatMessages(messages, imageDataURL),
	}
	if len(tools) > 0 {
		payload["tools"] = tools
		payload["tool_choice"] = "auto"
	}
	raw, err := c.doJSON(ctx, http.MethodPost, APIURL(settings.BaseURL, "chat/completions"), settings, proxyURL, configRev, payload, imageDataURL != "")
	if err != nil {
		return LLMMessage{}, err
	}
	var resp struct {
		Choices []struct {
			Message struct {
				Content   *string       `json:"content"`
				Refusal   *string       `json:"refusal"`
				ToolCalls []LLMToolCall `json:"tool_calls"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil || len(resp.Choices) == 0 {
		return LLMMessage{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "AI 接口未返回有效对话，请检查所选接口类型以及模型的工具调用能力")
	}
	m := resp.Choices[0].Message
	var content string
	if m.Content != nil {
		content = *m.Content
	} else if m.Refusal != nil {
		content = *m.Refusal
	}
	return normalizeAssistantMessage(content, m.ToolCalls, nil, "", "")
}

func (c *Client) completeChatStream(
	ctx context.Context,
	settings Settings,
	proxyURL, configRev string,
	messages []LLMMessage,
	tools []ToolDef,
	imageDataURL string,
	onDelta func(string),
) (LLMMessage, error) {
	payload := map[string]any{
		"model":    settings.Model,
		"messages": encodeChatMessages(messages, imageDataURL),
		"stream":   true,
	}
	if len(tools) > 0 {
		payload["tools"] = tools
		payload["tool_choice"] = "auto"
	}
	resp, err := c.openRequest(ctx, http.MethodPost, APIURL(settings.BaseURL, "chat/completions"), settings, proxyURL, configRev, payload, imageDataURL != "", true)
	if err != nil {
		return LLMMessage{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return LLMMessage{}, statusError(resp.StatusCode, true)
	}

	maxBytes := c.maxPayloadBytes(false)
	reader := bufio.NewReaderSize(io.LimitReader(resp.Body, int64(maxBytes)+1), 16*1024)
	var contentBuf strings.Builder
	calls := make(map[int]*LLMToolCall)
	totalBytes := 0
	finished := false
	for {
		line, err := reader.ReadString('\n')
		if len(line) > 0 {
			totalBytes += len(line)
			if totalBytes > maxBytes {
				return LLMMessage{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "AI 流式响应过大")
			}
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "data:") {
				raw := strings.TrimSpace(strings.TrimPrefix(trimmed, "data:"))
				if raw == "[DONE]" {
					finished = true
					break
				}
				var chunk struct {
					Choices []struct {
						FinishReason *string `json:"finish_reason"`
						Delta        struct {
							Content   string `json:"content"`
							ToolCalls []struct {
								Index    int    `json:"index"`
								ID       string `json:"id"`
								Function struct {
									Name      string `json:"name"`
									Arguments string `json:"arguments"`
								} `json:"function"`
							} `json:"tool_calls"`
						} `json:"delta"`
					} `json:"choices"`
				}
				if json.Unmarshal([]byte(raw), &chunk) != nil {
					return LLMMessage{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "AI 流式响应格式无效")
				}
				if len(chunk.Choices) > 0 {
					ch := chunk.Choices[0]
					if ch.FinishReason != nil && *ch.FinishReason != "" {
						finished = true
					}
					if ch.Delta.Content != "" {
						contentBuf.WriteString(ch.Delta.Content)
						onDelta(ch.Delta.Content)
					}
					for _, part := range ch.Delta.ToolCalls {
						tc, ok := calls[part.Index]
						if !ok {
							tc = &LLMToolCall{Type: "function"}
							calls[part.Index] = tc
						}
						if part.ID != "" {
							tc.ID = part.ID
						}
						tc.Function.Name += part.Function.Name
						tc.Function.Arguments += part.Function.Arguments
					}
				}
			}
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				if contentBuf.Len() > 0 || len(calls) > 0 {
					finished = true
				}
				break
			}
			if isTimeoutError(err) {
				return LLMMessage{}, wrapDeadlineError(err)
			}
			if ctx.Err() != nil {
				return LLMMessage{}, model.NewError(model.ErrCancelled, 499, "AI 流式请求已取消")
			}
			return LLMMessage{}, model.NewError(model.ErrTransport, http.StatusBadGateway, "AI 流式连接失败，请检查地址或关闭流式输出")
		}
	}
	if !finished {
		return LLMMessage{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "AI 流式响应中断，请检查连接后重试")
	}
	idxs := make([]int, 0, len(calls))
	for idx := range calls {
		idxs = append(idxs, idx)
	}
	sort.Ints(idxs)
	orderedCalls := make([]LLMToolCall, 0, len(idxs))
	for _, idx := range idxs {
		orderedCalls = append(orderedCalls, *calls[idx])
	}
	return normalizeAssistantMessage(contentBuf.String(), orderedCalls, nil, "", "")
}

func (c *Client) completeResponses(
	ctx context.Context,
	settings Settings,
	proxyURL, configRev string,
	messages []LLMMessage,
	tools []ToolDef,
	imageDataURL string,
) (LLMMessage, error) {
	endpointURL := APIURL(settings.BaseURL, "responses")
	payload := map[string]any{
		"model":   settings.Model,
		"input":   encodeResponsesInput(messages, imageDataURL, settings.Model, endpointURL),
		"store":   false,
		"include": []string{"reasoning.encrypted_content"},
	}
	if len(tools) > 0 {
		respTools := make([]map[string]any, 0, len(tools))
		for _, t := range tools {
			respTools = append(respTools, map[string]any{
				"type":        "function",
				"name":        t.Function.Name,
				"description": t.Function.Description,
				"parameters":  t.Function.Parameters,
				"strict":      false,
			})
		}
		payload["tools"] = respTools
		payload["tool_choice"] = "auto"
	}
	raw, err := c.doJSON(ctx, http.MethodPost, endpointURL, settings, proxyURL, configRev, payload, imageDataURL != "")
	if err != nil {
		return LLMMessage{}, err
	}
	var resp struct {
		Status string            `json:"status"`
		Output []json.RawMessage `json:"output"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil || resp.Status != "completed" {
		return LLMMessage{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "Responses 接口未完成本轮响应，请检查模型、额度和接口配置后重试")
	}
	var keptOutput []any
	var texts []string
	var toolCalls []LLMToolCall
	for _, rawItem := range resp.Output {
		var base struct {
			Type    string `json:"type"`
			CallID  string `json:"call_id"`
			Name    string `json:"name"`
			Args    string `json:"arguments"`
			Content []struct {
				Type    string `json:"type"`
				Text    string `json:"text"`
				Refusal string `json:"refusal"`
			} `json:"content"`
		}
		if json.Unmarshal(rawItem, &base) != nil {
			return LLMMessage{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "AI 接口返回了无效输出项")
		}
		if base.Type == "message" || base.Type == "function_call" || base.Type == "reasoning" {
			var generic any
			_ = json.Unmarshal(rawItem, &generic)
			keptOutput = append(keptOutput, generic)
		}
		switch base.Type {
		case "message":
			for _, part := range base.Content {
				if part.Type == "output_text" || part.Type == "refusal" {
					if part.Text != "" {
						texts = append(texts, part.Text)
					} else if part.Refusal != "" {
						texts = append(texts, part.Refusal)
					}
				}
			}
		case "function_call":
			toolCalls = append(toolCalls, LLMToolCall{
				ID:   base.CallID,
				Type: "function",
				Function: LLMFunctionCall{
					Name:      base.Name,
					Arguments: base.Args,
				},
			})
		}
	}
	return normalizeAssistantMessage(strings.Join(texts, "\n"), toolCalls, keptOutput, settings.Model, endpointURL)
}

func normalizeAssistantMessage(content string, calls []LLMToolCall, respOutput []any, respModel, respOrigin string) (LLMMessage, error) {
	clean := LLMMessage{
		Role:    "assistant",
		Content: content,
	}
	if len(calls) > 0 {
		clean.ToolCalls = make([]LLMToolCall, 0, len(calls))
		for _, call := range calls {
			if (call.Type != "" && call.Type != "function") || call.ID == "" || call.Function.Name == "" {
				return LLMMessage{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "AI 接口未返回有效工具调用")
			}
			clean.ToolCalls = append(clean.ToolCalls, LLMToolCall{
				ID:   call.ID,
				Type: "function",
				Function: LLMFunctionCall{
					Name:      call.Function.Name,
					Arguments: call.Function.Arguments,
				},
			})
		}
	}
	if len(respOutput) > 0 {
		clean.ResponsesOutput = respOutput
		clean.ResponsesModel = respModel
		clean.ResponsesOrigin = respOrigin
	}
	if clean.Content == "" && len(clean.ToolCalls) == 0 {
		return LLMMessage{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "AI 接口返回空响应")
	}
	return clean, nil
}

func encodeChatMessages(messages []LLMMessage, imageDataURL string) []map[string]any {
	out := make([]map[string]any, 0, len(messages))
	lastUserIdx := -1
	if imageDataURL != "" {
		for i := len(messages) - 1; i >= 0; i-- {
			if messages[i].Role == "user" {
				lastUserIdx = i
				break
			}
		}
	}
	for i, m := range messages {
		item := map[string]any{"role": m.Role}
		if i == lastUserIdx && imageDataURL != "" {
			item["content"] = []map[string]any{
				{"type": "text", "text": m.Content},
				{"type": "image_url", "image_url": map[string]any{"url": imageDataURL}},
			}
		} else {
			item["content"] = m.Content
		}
		if m.ToolCallID != "" {
			item["tool_call_id"] = m.ToolCallID
		}
		if len(m.ToolCalls) > 0 {
			item["tool_calls"] = m.ToolCalls
		}
		out = append(out, item)
	}
	return out
}

func encodeResponsesInput(messages []LLMMessage, imageDataURL, currentModel, currentOrigin string) []any {
	var items []any
	lastUserIdx := -1
	if imageDataURL != "" {
		for i := len(messages) - 1; i >= 0; i-- {
			if messages[i].Role == "user" {
				lastUserIdx = i
				break
			}
		}
	}
	for i, m := range messages {
		if m.Role == "tool" {
			items = append(items, map[string]any{
				"type":    "function_call_output",
				"call_id": m.ToolCallID,
				"output":  m.Content,
			})
			continue
		}
		if m.ResponsesModel == currentModel && m.ResponsesOrigin == currentOrigin && len(m.ResponsesOutput) > 0 {
			items = append(items, m.ResponsesOutput...)
			continue
		}
		if i == lastUserIdx && imageDataURL != "" {
			items = append(items, map[string]any{
				"role": m.Role,
				"content": []map[string]any{
					{"type": "input_text", "text": m.Content},
					{"type": "input_image", "image_url": imageDataURL},
				},
			})
		} else if m.Content != "" {
			items = append(items, map[string]any{
				"role":    m.Role,
				"content": m.Content,
			})
		}
		for _, tc := range m.ToolCalls {
			items = append(items, map[string]any{
				"type":      "function_call",
				"call_id":   tc.ID,
				"name":      tc.Function.Name,
				"arguments": tc.Function.Arguments,
			})
		}
	}
	return items
}

func (c *Client) maxPayloadBytes(hasImage bool) int {
	if hasImage {
		if c.budgets.ImageJSONMaxBytes > 0 {
			return c.budgets.ImageJSONMaxBytes
		}
		return 5 << 20
	}
	if c.budgets.DefaultJSONMaxBytes > 0 {
		return c.budgets.DefaultJSONMaxBytes
	}
	return 1 << 20
}

const aiDeadlineMessage = "上游 AI 接口响应超时，请稍后重试或点击「核对状态并继续任务」（详见运行日志）"

func isTimeoutError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

func wrapDeadlineError(cause error) *model.DomainError {
	return model.WrapError(model.ErrDeadline, http.StatusGatewayTimeout, aiDeadlineMessage, cause)
}

func (c *Client) doJSON(
	ctx context.Context,
	method string,
	targetURL string,
	settings Settings,
	proxyURL, configRev string,
	payload any,
	hasImage bool,
) ([]byte, error) {
	resp, err := c.openRequest(ctx, method, targetURL, settings, proxyURL, configRev, payload, hasImage, false)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, statusError(resp.StatusCode, false)
	}
	maxBytes := c.maxPayloadBytes(false)
	raw, err := io.ReadAll(io.LimitReader(resp.Body, int64(maxBytes)+1))
	if err != nil {
		if isTimeoutError(err) {
			return nil, wrapDeadlineError(err)
		}
		if ctx.Err() != nil {
			return nil, model.NewError(model.ErrCancelled, 499, "AI 请求已取消")
		}
		return nil, model.NewError(model.ErrTransport, http.StatusBadGateway, "读取 AI 响应失败")
	}
	if len(raw) > maxBytes {
		return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "AI 接口响应过大")
	}
	return raw, nil
}

func (c *Client) openRequest(
	ctx context.Context,
	method string,
	targetURL string,
	settings Settings,
	proxyURL, configRev string,
	payload any,
	hasImage bool,
	streaming bool,
) (*http.Response, error) {
	origin, err := internalOriginForURL(targetURL)
	if err != nil {
		return nil, err
	}
	var bodyReader io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "AI 请求序列化失败", err)
		}
		limit := c.maxPayloadBytes(hasImage)
		// Account for base64 image overhead when image is present (5 MiB raw ~= 6.8 MiB data URL).
		if hasImage && limit < 7_200_000 {
			limit = 7_200_000
		}
		if len(encoded) > limit {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AI 请求体大小超出预算上限")
		}
		bodyReader = bytes.NewReader(encoded)
	}
	req, err := http.NewRequestWithContext(ctx, method, targetURL, bodyReader)
	if err != nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "构造 AI 请求失败")
	}
	req.Header.Set("Content-Type", "application/json")
	if strings.TrimSpace(settings.APIKey) != "" {
		req.Header.Set("Authorization", "Bearer "+settings.APIKey)
	}
	timeout := 20 * time.Second
	headerTimeout := 20 * time.Second
	if method == http.MethodPost {
		headerTimeout = 90 * time.Second
		if streaming {
			timeout = 240 * time.Second
		} else {
			timeout = 180 * time.Second
		}
	}
	resp, err := c.pool.Do(ctx, req, transport.RequestOptions{
		Domain:                 transport.DomainConfiguredInternal,
		ConfigRevision:         configRev,
		ProxyURL:               proxyURL,
		Timeout:                timeout,
		ResponseHeaderTimeout:  headerTimeout,
		MaxResponseBytes:       int64(c.maxPayloadBytes(false)),
		AllowedInternalOrigins: []string{origin},
		Idempotent:             method == http.MethodGet,
		HasSideEffects:         method != http.MethodGet,
	})
	if err != nil {
		var de *model.DomainError
		if errors.As(err, &de) {
			if de.Code == model.ErrDeadline || isTimeoutError(de) {
				return nil, wrapDeadlineError(de)
			}
			return nil, de
		}
		if isTimeoutError(err) {
			return nil, wrapDeadlineError(err)
		}
		if ctx.Err() != nil {
			return nil, model.NewError(model.ErrCancelled, 499, "AI 请求已取消")
		}
		return nil, model.NewError(model.ErrTransport, http.StatusBadGateway, "无法连接 AI 接口或请求超时，请检查服务地址、代理及网络")
	}
	return resp, nil
}

func statusError(status int, streaming bool) *model.DomainError {
	if streaming {
		return model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("AI 流式接口返回 HTTP %d，请检查服务商配置", status))
	}
	hint := "请检查地址、模型、接口类型和服务商状态"
	switch status {
	case http.StatusUnauthorized:
		hint = "请检查 API Key"
	case http.StatusForbidden:
		hint = "请检查账号权限、地区或代理"
	case http.StatusNotFound:
		hint = "请检查 API 地址及接口类型"
	case http.StatusTooManyRequests:
		hint = "请求受限，请检查额度或稍后重试"
	}
	return model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("AI 接口返回 HTTP %d，%s", status, hint))
}
