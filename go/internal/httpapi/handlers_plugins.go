package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/plugin"
)

var _ PluginGateway = (*plugin.Manager)(nil)

const (
	pluginReadOnlyDetail = "只读验证模式禁止修改插件"
	// Plugin scripts are capped at 512 KiB; leave headroom for JSON escaping and other fields.
	pluginBodyLimit = 2 << 20
	// Archive uploads are capped at 16 MiB plus 1 MiB headroom for multipart boundaries and form fields.
	pluginArchiveBodyLimit       = plugin.MaxArchiveBytes + (1 << 20)
	pluginArchiveMultipartMemory = 4 << 20
)

func (s *Server) pluginsGateway(w http.ResponseWriter) PluginGateway {
	if s.deps.Plugins == nil {
		writeError(w, http.StatusServiceUnavailable, "插件服务未初始化")
		return nil
	}
	return s.deps.Plugins
}

// pluginWriteGuard returns false (after writing a 403) when plugin mutation is not permitted.
func (s *Server) pluginWriteGuard(w http.ResponseWriter) bool {
	if s.deps.ReadOnly {
		writeError(w, http.StatusForbidden, pluginReadOnlyDetail)
		return false
	}
	return true
}

// invalidatePluginCaches drops resolve/search caches that may reference a changed plugin.
func (s *Server) invalidatePluginCaches() {
	s.resolveMu.Lock()
	clear(s.resolveCache)
	s.resolveMu.Unlock()
	if s.deps.Search != nil {
		s.deps.Search.ClearCache()
	}
}

func (s *Server) handlePluginsList(w http.ResponseWriter, r *http.Request) {
	gw := s.pluginsGateway(w)
	if gw == nil {
		return
	}
	list, err := gw.List(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if list == nil {
		list = []model.ProviderPluginDTO{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"plugins": list})
}

type pluginInspectRequest struct {
	Code      string `json:"code"`
	SourceURL string `json:"source_url"`
}

func (s *Server) handlePluginsInspect(w http.ResponseWriter, r *http.Request) {
	gw := s.pluginsGateway(w)
	if gw == nil {
		return
	}
	var req pluginInspectRequest
	if !decodeJSON(w, r, pluginBodyLimit, &req) {
		return
	}
	res, err := gw.Inspect(r.Context(), req.Code, req.SourceURL)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handlePluginsInspectArchive(w http.ResponseWriter, r *http.Request) {
	gw := s.pluginsGateway(w)
	if gw == nil {
		return
	}
	archiveReq, ok := readPluginArchiveUpload(w, r, false)
	if !ok {
		return
	}
	res, err := gw.InspectArchive(r.Context(), archiveReq.ArchiveBytes)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handlePluginsInstallArchive(w http.ResponseWriter, r *http.Request) {
	if !s.pluginWriteGuard(w) {
		return
	}
	gw := s.pluginsGateway(w)
	if gw == nil {
		return
	}
	archiveReq, ok := readPluginArchiveUpload(w, r, true)
	if !ok {
		return
	}
	res, err := gw.InstallArchive(r.Context(), archiveReq)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if res.InstalledCount > 0 {
		s.invalidatePluginCaches()
		ids := make([]string, 0, len(res.Installed))
		for _, item := range res.Installed {
			ids = append(ids, item.ID)
		}
		s.pushLog(
			"info",
			fmt.Sprintf("批量导入插件压缩包: 成功 %d 个，失败 %d 个 (%s)", res.InstalledCount, res.FailedCount, strings.Join(ids, ", ")),
			"plugin",
			"",
		)
	}
	writeJSON(w, http.StatusOK, res)
}

func readPluginArchiveUpload(w http.ResponseWriter, r *http.Request, parseInstallFields bool) (plugin.InstallArchiveRequest, bool) {
	var out plugin.InstallArchiveRequest

	if r.ContentLength > pluginArchiveBodyLimit {
		writeError(w, http.StatusRequestEntityTooLarge, "插件压缩包大小超出 16 MiB 上限")
		return out, false
	}

	mediaType, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	mediaType = strings.ToLower(strings.TrimSpace(mediaType))

	switch mediaType {
	case "multipart/form-data":
		r.Body = http.MaxBytesReader(w, r.Body, pluginArchiveBodyLimit)
		if err := r.ParseMultipartForm(pluginArchiveMultipartMemory); err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) || errors.Is(err, multipart.ErrMessageTooLarge) {
				writeError(w, http.StatusRequestEntityTooLarge, "插件压缩包大小超出 16 MiB 上限")
				return out, false
			}
			writeError(w, http.StatusBadRequest, "解析插件压缩包上传表单失败")
			return out, false
		}
		defer func() {
			if r.MultipartForm != nil {
				_ = r.MultipartForm.RemoveAll()
			}
		}()

		file, _, err := r.FormFile("file")
		if err != nil {
			writeError(w, http.StatusBadRequest, "请通过 file 字段上传 .zip 插件压缩包")
			return out, false
		}
		defer file.Close()

		data, err := io.ReadAll(io.LimitReader(file, plugin.MaxArchiveBytes+1))
		if err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) {
				writeError(w, http.StatusRequestEntityTooLarge, "插件压缩包大小超出 16 MiB 上限")
				return out, false
			}
			writeError(w, http.StatusBadRequest, "读取插件压缩包失败")
			return out, false
		}
		if len(data) > plugin.MaxArchiveBytes {
			writeError(w, http.StatusRequestEntityTooLarge, "插件压缩包大小超出 16 MiB 上限")
			return out, false
		}
		if len(data) == 0 {
			writeError(w, http.StatusBadRequest, "插件压缩包不能为空")
			return out, false
		}
		out.ArchiveBytes = data

		if parseInstallFields {
			enabled, err := parseOptionalBoolField(r.FormValue("enabled"), "enabled")
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return out, false
			}
			out.Enabled = enabled

			var rawSelected, rawOrigins []string
			if r.MultipartForm != nil {
				rawSelected = r.MultipartForm.Value["selected_ids"]
				rawOrigins = r.MultipartForm.Value["allowed_origins"]
			}
			selectedIDs, err := parseStringListFormField(rawSelected, "selected_ids")
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return out, false
			}
			allowedOrigins, err := parseStringListFormField(rawOrigins, "allowed_origins")
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return out, false
			}
			out.SelectedIDs = selectedIDs
			out.AllowedOrigins = allowedOrigins
		}
		return out, true

	case "application/zip", "application/x-zip-compressed", "application/octet-stream":
		if r.ContentLength > plugin.MaxArchiveBytes {
			writeError(w, http.StatusRequestEntityTooLarge, "插件压缩包大小超出 16 MiB 上限")
			return out, false
		}
		r.Body = http.MaxBytesReader(w, r.Body, plugin.MaxArchiveBytes+1)
		data, err := io.ReadAll(io.LimitReader(r.Body, plugin.MaxArchiveBytes+1))
		if err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) {
				writeError(w, http.StatusRequestEntityTooLarge, "插件压缩包大小超出 16 MiB 上限")
				return out, false
			}
			writeError(w, http.StatusBadRequest, "读取插件压缩包失败")
			return out, false
		}
		if len(data) > plugin.MaxArchiveBytes {
			writeError(w, http.StatusRequestEntityTooLarge, "插件压缩包大小超出 16 MiB 上限")
			return out, false
		}
		if len(data) == 0 {
			writeError(w, http.StatusBadRequest, "插件压缩包不能为空")
			return out, false
		}
		out.ArchiveBytes = data

		if parseInstallFields {
			q := r.URL.Query()
			enabled, err := parseOptionalBoolField(q.Get("enabled"), "enabled")
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return out, false
			}
			out.Enabled = enabled

			selectedIDs, err := parseStringListFormField(q["selected_ids"], "selected_ids")
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return out, false
			}
			allowedOrigins, err := parseStringListFormField(q["allowed_origins"], "allowed_origins")
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return out, false
			}
			out.SelectedIDs = selectedIDs
			out.AllowedOrigins = allowedOrigins
		}
		return out, true

	default:
		writeError(w, http.StatusBadRequest, "请使用 multipart/form-data (file) 或 application/zip 上传插件压缩包")
		return out, false
	}
}

func parseOptionalBoolField(raw, fieldName string) (*bool, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, nil
	}
	switch strings.ToLower(trimmed) {
	case "true", "1":
		v := true
		return &v, nil
	case "false", "0":
		v := false
		return &v, nil
	default:
		return nil, fmt.Errorf("%s 参数必须为 true 或 false", fieldName)
	}
}

func parseStringListFormField(values []string, fieldName string) ([]string, error) {
	if len(values) == 0 {
		return nil, nil
	}
	out := make([]string, 0, len(values))
	for _, raw := range values {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "[") {
			var arr []string
			if err := json.Unmarshal([]byte(trimmed), &arr); err != nil {
				return nil, fmt.Errorf("%s 必须为有效的 JSON 字符串数组或逗号分隔列表", fieldName)
			}
			for _, item := range arr {
				if s := strings.TrimSpace(item); s != "" {
					out = append(out, s)
				}
			}
			continue
		}
		for _, part := range strings.Split(trimmed, ",") {
			if s := strings.TrimSpace(part); s != "" {
				out = append(out, s)
			}
		}
	}
	return out, nil
}

func (s *Server) handlePluginsInstall(w http.ResponseWriter, r *http.Request) {
	if !s.pluginWriteGuard(w) {
		return
	}
	gw := s.pluginsGateway(w)
	if gw == nil {
		return
	}
	var req plugin.InstallRequest
	if !decodeJSON(w, r, pluginBodyLimit, &req) {
		return
	}
	dto, err := gw.Install(r.Context(), req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.invalidatePluginCaches()
	s.pushLog("info", "已安装/更新插件: "+dto.ID, "plugin", dto.ID)
	writeJSON(w, http.StatusOK, map[string]any{"plugin": dto})
}

func (s *Server) handlePluginsUpdate(w http.ResponseWriter, r *http.Request) {
	if !s.pluginWriteGuard(w) {
		return
	}
	gw := s.pluginsGateway(w)
	if gw == nil {
		return
	}
	ident := strings.TrimSpace(r.PathValue("ident"))
	var req plugin.UpdateRequest
	if !decodeJSON(w, r, pluginBodyLimit, &req) {
		return
	}
	dto, err := gw.Update(r.Context(), ident, req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.invalidatePluginCaches()
	s.pushLog("info", "已更新插件配置: "+dto.ID, "plugin", dto.ID)
	writeJSON(w, http.StatusOK, map[string]any{"plugin": dto})
}

func (s *Server) handlePluginsDelete(w http.ResponseWriter, r *http.Request) {
	if !s.pluginWriteGuard(w) {
		return
	}
	gw := s.pluginsGateway(w)
	if gw == nil {
		return
	}
	ident := strings.TrimSpace(r.PathValue("ident"))
	deleted, err := gw.Delete(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if !deleted {
		writeError(w, http.StatusNotFound, "插件不存在")
		return
	}
	s.invalidatePluginCaches()
	s.pushLog("info", "已卸载插件: "+ident, "plugin", ident)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "id": ident})
}

func (s *Server) handlePluginsTest(w http.ResponseWriter, r *http.Request) {
	gw := s.pluginsGateway(w)
	if gw == nil {
		return
	}
	ident := strings.TrimSpace(r.PathValue("ident"))
	var req plugin.TestRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	var logFn provider.LogFunc
	if s.deps.Logs != nil {
		logFn = s.deps.Logs.Push
	}
	testCtx := provider.WithRequestContext(r.Context(), provider.RequestContext{
		Config:  s.currentSnapshot(),
		TaskID:  ident,
		LogFunc: logFn,
	})
	res, err := gw.Test(testCtx, ident, req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}
