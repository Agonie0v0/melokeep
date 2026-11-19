package agent

import (
	"encoding/json"
	"net/http"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
)

type toolSpec struct {
	Name        string
	Description string
	Write       bool
	Parameters  map[string]any
}

var domainTags = map[string]string{
	"playlists": "歌单",
	"playlist":  "播放队列与歌单导入",
	"favorites": "收藏",
	"downloads": "下载任务",
	"files":     "本地音乐文件与标签",
	"library":   "音乐库刮削整理归档监控设置",
	"navidrome": "Navidrome 曲库歌单扫描",
	"engine":    "音源引擎配置更新",
	"config":    "音源代理下载配置",
	"bots":      "通知渠道与机器人配置",
	"logs":      "运行日志",
	"cache":     "缓存清理",
	"restart":   "重启服务",
	"agent":     "AI 助手配置",
}

var (
	absWinPathRe = regexp.MustCompile(`^[A-Za-z]:[/\\]`)
	schemeURLRe  = regexp.MustCompile(`(?i)^[a-z][a-z0-9+.-]*://`)
)

func builtinTools(allowActions bool, isChannel bool) []ToolDef {
	specs := []toolSpec{
		{
			Name:        "update_plan",
			Description: "保存并更新复合任务的目标及步骤；步骤状态必须依据实际结果，便于中断后接续。简单查询无需计划。",
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"goal": map[string]any{"type": "string", "minLength": 1, "maxLength": 500},
					"steps": map[string]any{
						"type": "array",
						"items": map[string]any{
							"type": "object",
							"properties": map[string]any{
								"title":  map[string]any{"type": "string"},
								"status": map[string]any{"type": "string", "enum": []string{"pending", "running", "complete", "blocked"}},
							},
							"required": []string{"title", "status"},
						},
					},
				},
				"required": []string{"goal", "steps"},
			},
		},
		{
			Name:        "request_decision",
			Description: "仅在确需用户决定或缺少关键信息时标记等待答复；先推进其他明确项目，不因常规步骤使用。",
			Parameters: map[string]any{
				"type":       "object",
				"properties": map[string]any{"question": map[string]any{"type": "string", "minLength": 1, "maxLength": 1000}},
				"required":   []string{"question"},
			},
		},
		{
			Name:        "prepare_import",
			Description: "仅在用户要求手动编辑清单或存在无法消除的批量识别歧义时，提供可编辑清单。",
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"songs": map[string]any{
						"type": "array",
						"items": map[string]any{
							"type": "object",
							"properties": map[string]any{
								"title":  map[string]any{"type": "string"},
								"artist": map[string]any{"type": "string"},
							},
							"required": []string{"title"},
						},
					},
				},
				"required": []string{"songs"},
			},
		},
		{
			Name:        "search_music",
			Description: "搜索真实音乐候选，返回歌曲 ID、歌手、专辑与音质。每首歌分别搜索。",
			Parameters: map[string]any{
				"type":       "object",
				"properties": map[string]any{"keyword": map[string]any{"type": "string", "minLength": 1, "maxLength": 200}},
				"required":   []string{"keyword"},
			},
		},
		{
			Name:        "list_playlists",
			Description: "列出 MeloKeep 本地歌单。",
			Parameters:  map[string]any{"type": "object", "properties": map[string]any{}},
		},
		{
			Name:        "read_playlist",
			Description: "读取指定本地歌单中的歌曲。",
			Parameters: map[string]any{
				"type":       "object",
				"properties": map[string]any{"playlist_id": map[string]any{"type": "string", "minLength": 1, "maxLength": 100}},
				"required":   []string{"playlist_id"},
			},
		},
		{
			Name:        "create_playlist",
			Description: "创建本地歌单；先列出歌单，避免创建重名歌单。",
			Write:       true,
			Parameters: map[string]any{
				"type":       "object",
				"properties": map[string]any{"name": map[string]any{"type": "string", "minLength": 1, "maxLength": 100}},
				"required":   []string{"name"},
			},
		},
		{
			Name:        "add_to_playlist",
			Description: "将搜索或歌单查询返回的歌曲 ID 加入本地歌单，自动去重。",
			Write:       true,
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"playlist_id": map[string]any{"type": "string"},
					"track_ids":   map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
				},
				"required": []string{"playlist_id", "track_ids"},
			},
		},
		{
			Name:        "download_music",
			Description: "下载真实候选；organize=true 时对本批下载自动刮削、整理入库。返回入队状态，不代表下载完成。",
			Write:       true,
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"track_ids":    map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
					"organize":     map[string]any{"type": "boolean"},
					"force_new":    map[string]any{"type": "boolean"},
					"intent_nonce": map[string]any{"type": "string"},
				},
				"required": []string{"track_ids"},
			},
		},
		{
			Name:        "get_status",
			Description: "查询最近下载和整理任务的真实状态及当前自动整理配置。",
			Parameters:  map[string]any{"type": "object", "properties": map[string]any{}},
		},
		{
			Name:        "get_task_status",
			Description: "按真实任务 ID 查询指定下载任务的当前状态、错误与整理状态；后台任务入队后用它核验。",
			Parameters: map[string]any{
				"type":       "object",
				"properties": map[string]any{"ids": map[string]any{"type": "array", "items": map[string]any{"type": "string"}}},
				"required":   []string{"ids"},
			},
		},
		{
			Name:        "recover_download",
			Description: "恢复已查询的失败或暂停下载。",
			Write:       true,
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"job_id":               map[string]any{"type": "string"},
					"alternative_track_id": map[string]any{"type": "string"},
				},
				"required": []string{"job_id"},
			},
		},
		{
			Name:        "browse_library",
			Description: "按歌名、歌手或文件名查询本地音乐库，返回文件令牌。",
			Parameters: map[string]any{
				"type":       "object",
				"properties": map[string]any{"keyword": map[string]any{"type": "string"}},
			},
		},
		{
			Name:        "preview_organize",
			Description: "为查询到的本地文件创建刮削整理预览；用户在刮削整理工作台审核后应用。",
			Write:       true,
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"paths":  map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
					"action": map[string]any{"type": "string", "enum": []string{"scrape", "organize", "both"}},
				},
				"required": []string{"paths"},
			},
		},
		{
			Name:        "list_project_operations",
			Description: "发现项目全部管理接口。keyword 留空列出全部，或输入英文路径关键词/中文分类；operation 填列表返回的完整标识可查询参数及 JSON schema。",
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"keyword":   map[string]any{"type": "string"},
					"operation": map[string]any{"type": "string"},
				},
			},
		},
		{
			Name:        "query_project",
			Description: "调用已发现的 GET 项目接口。",
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"operation":   map[string]any{"type": "string"},
					"path_params": map[string]any{"type": "object"},
					"query":       map[string]any{"type": "object"},
				},
				"required": []string{"operation"},
			},
		},
		{
			Name:        "execute_project",
			Description: "执行已发现的 POST/PUT/PATCH/DELETE 项目接口。",
			Write:       true,
			Parameters: map[string]any{
				"type": "object",
				"properties": map[string]any{
					"operation":    map[string]any{"type": "string"},
					"path_params":  map[string]any{"type": "object"},
					"query":        map[string]any{"type": "object"},
					"body":         map[string]any{},
					"force_new":    map[string]any{"type": "boolean"},
					"intent_nonce": map[string]any{"type": "string"},
				},
				"required": []string{"operation"},
			},
		},
		{
			Name:        "wait_for_tasks",
			Description: "等待后台下载、整理或扫描任务后再查询，避免频繁轮询。每次等待 1–60 秒。",
			Parameters: map[string]any{
				"type":       "object",
				"properties": map[string]any{"seconds": map[string]any{"type": "number", "minimum": 1, "maximum": 60}},
			},
		},
	}
	out := make([]ToolDef, 0, len(specs))
	for _, s := range specs {
		if s.Write && !allowActions {
			continue
		}
		if s.Name == "prepare_import" && isChannel {
			continue
		}
		out = append(out, ToolDef{
			Type: "function",
			Function: ToolFunctionDef{
				Name:        s.Name,
				Description: s.Description,
				Parameters:  s.Parameters,
			},
		})
	}
	return out
}

func isOperationExposedToAI(entry operations.DiscoverEntry) bool {
	parts := strings.SplitN(entry.Operation, " ", 2)
	if len(parts) != 2 {
		return false
	}
	method, path := parts[0], parts[1]
	if !strings.HasPrefix(path, "/api/") || strings.HasPrefix(path, "/api/auth/") {
		return false
	}
	if strings.HasPrefix(path, "/api/agent/") && !strings.HasPrefix(path, "/api/agent/settings") {
		return false
	}
	if entry.Write {
		switch path {
		case "/api/agent/settings", "/api/bots/config":
			return false
		}
	}
	if method == "GET" {
		switch path {
		case "/api/file", "/api/files/audio", "/api/files/cover", "/api/files/content",
			"/api/stream/{sid}", "/api/navidrome/cover/{ident}", "/api/navidrome/stream/{ident}",
			"/api/search/jobs/{ident}/events", "/api/library/jobs/{ident}/report":
			return false
		}
	}
	return true
}

func discoverOperations(reg *operations.Registry, keyword, op string) (map[string]any, error) {
	if reg == nil {
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "操作目录未就绪")
	}
	if strings.TrimSpace(op) != "" {
		spec, ok := reg.Resolve(op)
		if !ok || !isOperationExposedToAI(operations.DiscoverEntry{Operation: spec.Alias, Write: spec.Write}) {
			return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "项目操作不存在，请先查询操作列表")
		}
		return map[string]any{
			"operation":   spec.Alias,
			"description": spec.Description,
			"write":       spec.Write,
			"effect":      string(spec.Effect),
			"idempotency": string(spec.Idempotency),
			"input_schema": spec.InputSchema,
			"detail":      "请按参数定义调用；修改设置前先读取现有配置。",
		}, nil
	}
	all := reg.Discover()
	kw := strings.ToLower(strings.TrimSpace(keyword))
	items := make([]map[string]any, 0, len(all))
	for _, entry := range all {
		if !isOperationExposedToAI(entry) {
			continue
		}
		desc := entry.Description
		parts := strings.SplitN(entry.Operation, " ", 2)
		if len(parts) == 2 {
			segs := strings.Split(strings.TrimPrefix(parts[1], "/api/"), "/")
			if tag, ok := domainTags[segs[0]]; ok && !strings.Contains(desc, tag) {
				desc = tag + " · " + desc
			}
		}
		if kw != "" && !strings.Contains(strings.ToLower(entry.Operation+" "+desc), kw) {
			continue
		}
		items = append(items, map[string]any{
			"operation":   entry.Operation,
			"description": desc,
			"write":       entry.Write,
		})
	}
	return map[string]any{
		"operations": items,
		"detail":     "先用 operation 查询具体参数，再调用查询或执行工具。文件删除、应用整理和配置修改等按用户明确要求执行。",
	}, nil
}

// validateModelArguments rejects model attempts to spoof actors, inject raw URLs/credentials,
// or pass host-absolute file paths.
func validateModelArguments(args map[string]any) error {
	for k, v := range args {
		lk := strings.ToLower(k)
		switch lk {
		case "actor", "actor_key", "role", "permissions", "owner", "owner_key", "authenticated", "read_only_ai":
			return model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止由模型指定权限或身份字段")
		case "cookie", "cookies", "password", "token", "api_key", "secret", "authorization":
			if str, ok := v.(string); ok && strings.TrimSpace(str) != "" && str != config.HiddenPlaceholder {
				return model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止由模型注入凭据字段")
			}
		}
		if strings.HasSuffix(lk, "_allowed_users") {
			return model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止由模型修改用户白名单")
		}
		switch x := v.(type) {
		case string:
			if lk == "path" || lk == "file" || lk == "target" || lk == "source_path" || lk == "target_path" || lk == "root" || lk == "work_dir" || lk == "data_dir" {
				if isAbsoluteOrUnsafePath(x) {
					return model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止由模型指定绝对路径或越界路径")
				}
			}
			isURLKey := lk == "url" || lk == "proxy" || strings.HasSuffix(lk, "_url") || strings.HasSuffix(lk, "_webhook")
			if isURLKey {
				trimmed := strings.TrimSpace(x)
				if trimmed != "" && trimmed != config.HiddenPlaceholder && (schemeURLRe.MatchString(trimmed) || lk == "public_url" || strings.HasSuffix(lk, "_url") || strings.HasSuffix(lk, "_webhook")) {
					return model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止由模型指定任意外部 URL")
				}
			}
		case map[string]any:
			if err := validateModelArguments(x); err != nil {
				return err
			}
		case []any:
			for _, item := range x {
				if m, ok := item.(map[string]any); ok {
					if err := validateModelArguments(m); err != nil {
						return err
					}
				} else if str, ok := item.(string); ok && (lk == "paths" || lk == "files") {
					if isAbsoluteOrUnsafePath(str) {
						return model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止由模型指定绝对路径或越界路径")
					}
				}
			}
		}
	}
	return nil
}

func isAbsoluteOrUnsafePath(raw string) bool {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return false
	}
	if strings.HasPrefix(trimmed, "/") || strings.HasPrefix(trimmed, `\`) || absWinPathRe.MatchString(trimmed) || filepath.IsAbs(trimmed) {
		return true
	}
	for _, part := range strings.FieldsFunc(trimmed, func(r rune) bool { return r == '/' || r == '\\' }) {
		if part == ".." {
			return true
		}
	}
	return false
}

func sanitizeForModel(v any) map[string]any {
	raw, err := json.Marshal(v)
	if err != nil {
		redacted := operations.RedactOutput(v, nil)
		if m, ok := redacted.(map[string]any); ok {
			return m
		}
		return map[string]any{"value": redacted}
	}
	var generic any
	if json.Unmarshal(raw, &generic) != nil {
		return map[string]any{"value": operations.RedactOutput(v, nil)}
	}
	redacted := operations.RedactOutput(generic, nil)
	if m, ok := redacted.(map[string]any); ok {
		return m
	}
	return map[string]any{"data": redacted}
}

func extractStringSlice(m map[string]any, key string) []string {
	raw, ok := m[key]
	if !ok || raw == nil {
		return nil
	}
	switch xs := raw.(type) {
	case []string:
		return append([]string(nil), xs...)
	case []any:
		out := make([]string, 0, len(xs))
		for _, item := range xs {
			if s, ok := item.(string); ok && s != "" {
				out = append(out, s)
			}
		}
		return out
	default:
		return nil
	}
}

func extractBool(m map[string]any, key string) bool {
	if m == nil {
		return false
	}
	b, _ := m[key].(bool)
	return b
}

func extractString(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	s, _ := m[key].(string)
	return s
}

func rememberResultIDs(session *Session, val any) {
	switch x := val.(type) {
	case []any:
		for _, item := range x {
			rememberResultIDs(session, item)
		}
	case map[string]any:
		if id, ok := x["id"].(string); ok && id != "" {
			_, hasTitle := x["title"]
			_, hasArtist := x["artist"]
			if hasTitle && hasArtist && !containsString(session.TrackIDs, id) {
				session.TrackIDs = append(session.TrackIDs, id)
			}
		}
		if p, ok := x["path"].(string); ok && p != "" {
			_, hasName := x["name"]
			_, hasTrack := x["track"]
			if (hasName || hasTrack) && !containsString(session.Paths, p) {
				session.Paths = append(session.Paths, p)
			}
		}
		for _, nested := range x {
			rememberResultIDs(session, nested)
		}
	}
}

func containsString(list []string, target string) bool {
	for _, s := range list {
		if s == target {
			return true
		}
	}
	return false
}

// buildDispatchActor produces the effective Actor for a tool call, enforcing ReadOnlyAI
// when either the turn is read-only or dynamic write toggle has revoked writes.
func buildDispatchActor(base auth.Actor, readOnly bool) auth.Actor {
	eff := base
	if len(base.Permissions) > 0 {
		eff.Permissions = make(map[auth.Permission]bool, len(base.Permissions))
		for k, v := range base.Permissions {
			eff.Permissions[k] = v
		}
	}
	eff.ReadOnlyAI = readOnly || base.ReadOnlyAI
	return eff
}
