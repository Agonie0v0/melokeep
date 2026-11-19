package httpapi

import (
	"net/http"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/plugin"
)

const (
	pluginReadOnlyDetail = "只读验证模式禁止修改插件"
	// Plugin scripts are capped at 512 KiB; leave headroom for JSON escaping and other fields.
	pluginBodyLimit = 2 << 20
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
