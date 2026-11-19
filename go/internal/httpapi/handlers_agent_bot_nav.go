package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/agent"
	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/search"
)

// --- AI Agent handlers (15 routes) ---

func (s *Server) handleAgentSettingsRead(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	pub, err := s.deps.Agent.GetSettings(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, pub)
}

func (s *Server) handleAgentSettingsWrite(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	var patch agent.Settings
	if !decodeJSON(w, r, s.defaultJSONLimit(), &patch) {
		return
	}
	pub, err := s.deps.Agent.PutSettings(r.Context(), patch)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已保存 AI 助手配置", "system", "")
	writeJSON(w, http.StatusOK, pub)
}

func (s *Server) handleAgentSessions(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeJSON(w, http.StatusOK, []agent.SessionSummaryDTO{})
		return
	}
	sessions, err := s.deps.Agent.ListSessions(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if sessions == nil {
		sessions = []agent.SessionSummaryDTO{}
	}
	writeJSON(w, http.StatusOK, sessions)
}

func (s *Server) handleAgentTest(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	var override agent.Settings
	present, ok := decodeJSONOptional(w, r, s.defaultJSONLimit(), &override)
	if !ok {
		return
	}
	var ptr *agent.Settings
	if present {
		ptr = &override
	}
	res, err := s.deps.Agent.TestConnection(r.Context(), ptr)
	if err != nil {
		s.pushLog("warning", "AI 助手连接测试失败："+err.Error(), "system", "")
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "AI 助手连接测试完成", "system", "")
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleAgentTestCapability(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	capability := r.PathValue("capability")
	var override agent.Settings
	present, ok := decodeJSONOptional(w, r, s.defaultJSONLimit(), &override)
	if !ok {
		return
	}
	var ptr *agent.Settings
	if present {
		ptr = &override
	}
	res, err := s.deps.Agent.TestCapability(r.Context(), capability, ptr)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleAgentModels(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	var override agent.Settings
	present, ok := decodeJSONOptional(w, r, s.defaultJSONLimit(), &override)
	if !ok {
		return
	}
	var ptr *agent.Settings
	if present {
		ptr = &override
	}
	res, err := s.deps.Agent.ListModels(r.Context(), ptr)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleAgentCreateSession(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	sess, err := s.deps.Agent.CreateSession(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, sess)
}

func (s *Server) handleAgentReadSession(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	sess, err := s.deps.Agent.GetSession(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, sess)
}

func (s *Server) handleAgentDeleteSession(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	if err := s.deps.Agent.DeleteSession(r.Context(), ident); err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"deleted": true})
}

func (s *Server) handleAgentSendMessage(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	var req agent.TurnRequest
	if !decodeJSON(w, r, s.imageJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	sess, err := s.deps.Agent.StartTurn(r.Context(), actor, ident, req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "AI 助手已接收新指令", "system", ident)
	writeJSON(w, http.StatusOK, sess)
}

func (s *Server) handleAgentStop(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	sess, err := s.deps.Agent.Stop(r.Context(), ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, sess)
}

func (s *Server) handleAgentResume(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	actor := actorFromRequest(r)
	sess, err := s.deps.Agent.Resume(r.Context(), actor, ident)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, sess)
}

func (s *Server) handleAgentDeleteEvents(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	var req agent.EventDeleteRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	sess, err := s.deps.Agent.DeleteEvents(r.Context(), ident, req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, sess)
}

func (s *Server) handleAgentImportSearch(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	if _, err := s.deps.Agent.GetSession(r.Context(), ident); err != nil {
		writeDomainError(w, err)
		return
	}
	var req agent.ImportSearchRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	keyword := strings.TrimSpace(req.Title + " " + req.Artist)
	if keyword == "" {
		writeError(w, http.StatusBadRequest, "搜索关键词不能为空")
		return
	}
	if s.deps.Search == nil {
		writeJSON(w, http.StatusOK, map[string]any{"tracks": []model.TrackDTO{}})
		return
	}
	actor := actorFromRequest(r)
	snap := s.currentSnapshot()
	jobID, err := s.deps.Search.CreateSearch(r.Context(), actor, snap, search.CreateRequest{
		Keyword: keyword,
		Sources: snap.Runtime.Sources,
	})
	if err != nil {
		writeDomainError(w, err)
		return
	}
	sub, err := s.deps.Search.Subscribe(r.Context(), jobID, 0, "")
	if err != nil {
		writeDomainError(w, err)
		return
	}
	defer sub.Close()
	var tracks []model.TrackDTO
	for {
		ev, sErr := sub.Next(r.Context())
		if sErr != nil {
			break
		}
		if ev.Type == "track" {
			var dto model.TrackDTO
			if json.Unmarshal(ev.Data, &dto) == nil {
				tracks = append(tracks, dto)
			}
		}
	}
	if tracks == nil {
		tracks = []model.TrackDTO{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"tracks": tracks})
}

func (s *Server) handleAgentImportExecute(w http.ResponseWriter, r *http.Request) {
	if s.deps.Agent == nil {
		writeError(w, http.StatusServiceUnavailable, "AI 助手服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	if _, err := s.deps.Agent.GetSession(r.Context(), ident); err != nil {
		writeDomainError(w, err)
		return
	}
	var req agent.ImportExecuteRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	actor := actorFromRequest(r)
	snap := s.currentSnapshot()
	var downloads []model.DownloadJobDTO
	if req.Download && s.deps.Download != nil {
		for _, tid := range req.TrackIDs {
			dto, found, _ := s.loadTrackDTO(r.Context(), tid)
			if !found {
				continue
			}
			qID := ""
			if len(dto.Qualities) > 0 {
				qID = dto.Qualities[0].ID
			}
			var org *download.AutoOrganizeConfig
			if req.Organize {
				org = &download.AutoOrganizeConfig{Enabled: true, Action: "both"}
			}
			res, dErr := s.deps.Download.Enqueue(r.Context(), actor, snap, download.EnqueueRequest{
				PublicID:     tid,
				QualityID:    qID,
				AutoOrganize: org,
			})
			if dErr == nil {
				if jobDTO, tErr := s.deps.Download.ToDTO(r.Context(), res.Download); tErr == nil {
					downloads = append(downloads, jobDTO)
				}
			}
		}
	}
	if req.PlaylistID != "" && s.deps.Store != nil && len(req.TrackIDs) > 0 {
		if col, _, ok, err := s.deps.Store.GetCollection(r.Context(), req.PlaylistID); err == nil && ok {
			rev, _ := strconv.ParseInt(col.Revision, 10, 64)
			_, _ = s.deps.Store.AddCollectionItems(r.Context(), req.PlaylistID, req.TrackIDs, rev)
		}
	}
	if downloads == nil {
		downloads = []model.DownloadJobDTO{}
	}
	s.pushLog("info", fmt.Sprintf("AI 批量导入执行：选中 %d 首，创建下载=%d", len(req.TrackIDs), len(downloads)), "system", ident)
	writeJSON(w, http.StatusOK, map[string]any{"downloads": downloads})
}

// --- Bot & Notification handlers (10 routes) ---

func (s *Server) requireBot(w http.ResponseWriter) bool {
	if s.deps.Bot == nil {
		writeError(w, http.StatusServiceUnavailable, "机器人服务未初始化")
		return false
	}
	return true
}

func (s *Server) handleBotReadConfig(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	res, err := s.deps.Bot.ReadConfig(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleBotWriteConfig(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	var body map[string]any
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	res, err := s.deps.Bot.WriteConfig(r.Context(), actorFromRequest(r), body)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已保存通知与机器人配置", "bot", "")
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleBotTestConnection(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	var body map[string]any
	present, ok := decodeJSONOptional(w, r, s.defaultJSONLimit(), &body)
	if !ok {
		return
	}
	if !present || body == nil {
		body = map[string]any{}
	}
	res, err := s.deps.Bot.TestConnection(r.Context(), body)
	if err != nil {
		s.pushLog("warning", "通知/机器人测试发送失败："+err.Error(), "bot", "")
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "通知/机器人测试发送成功", "bot", "")
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleBotSignedDownload(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	ident := r.PathValue("ident")
	expires := r.URL.Query().Get("expires")
	signature := r.URL.Query().Get("signature")
	if err := s.deps.Bot.ServeSignedDownload(w, r, ident, expires, signature); err != nil {
		writeDomainError(w, err)
	}
}

func (s *Server) handleBotVerifyWeCom(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	if err := s.deps.Bot.VerifyWeCom(w, r); err != nil {
		writeDomainError(w, err)
	}
}

func (s *Server) handleBotReceiveWeCom(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	if err := s.deps.Bot.ReceiveWeCom(w, r); err != nil {
		writeDomainError(w, err)
	}
}

func (s *Server) handleBotReceiveFeishu(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	if err := s.deps.Bot.ReceiveFeishu(w, r); err != nil {
		writeDomainError(w, err)
	}
}

func (s *Server) handleBotReceiveDingTalk(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	if err := s.deps.Bot.ReceiveDingTalk(w, r); err != nil {
		writeDomainError(w, err)
	}
}

func (s *Server) handleBotReceiveDiscord(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	if err := s.deps.Bot.ReceiveDiscord(w, r); err != nil {
		writeDomainError(w, err)
	}
}

func (s *Server) handleBotRegisterDiscord(w http.ResponseWriter, r *http.Request) {
	if !s.requireBot(w) {
		return
	}
	res, err := s.deps.Bot.RegisterDiscordCommands(r.Context())
	if err != nil {
		s.pushLog("warning", "注册 Discord 斜杠命令失败："+err.Error(), "bot", "")
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已注册 Discord 斜杠命令", "bot", "")
	writeJSON(w, http.StatusOK, res)
}

// --- Navidrome handlers (21 routes) ---

func (s *Server) requireNavidrome(w http.ResponseWriter) bool {
	if s.deps.Navidrome == nil {
		writeError(w, http.StatusServiceUnavailable, "Navidrome 服务未配置")
		return false
	}
	return true
}

func parseQueryInt(r *http.Request, key string, def int) int {
	raw := strings.TrimSpace(r.URL.Query().Get(key))
	if raw == "" {
		return def
	}
	v, err := strconv.Atoi(raw)
	if err != nil {
		return def
	}
	return v
}

func (s *Server) handleNavidromeCapabilities(w http.ResponseWriter, r *http.Request) {
	if s.deps.Navidrome == nil {
		writeJSON(w, http.StatusOK, map[string]any{"delete_songs": false})
		return
	}
	res, err := s.deps.Navidrome.Capabilities(r.Context())
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"delete_songs": false})
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeDeletePreview(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.DeletePreview(r.Context(), r.PathValue("ident"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeDeleteSong(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.DeleteSong(r.Context(), r.PathValue("ident"), r.URL.Query().Get("revision"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已删除 Navidrome 歌曲", "library", r.PathValue("ident"))
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromePlaylists(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.ListPlaylists(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromePlaylist(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.GetPlaylist(r.Context(), r.PathValue("ident"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeCreatePlaylist(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	var body map[string]any
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	res, err := s.deps.Navidrome.CreatePlaylist(r.Context(), actorFromRequest(r), body)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已创建 Navidrome 歌单", "playlist", "")
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeRenamePlaylist(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	var body map[string]any
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	res, err := s.deps.Navidrome.RenamePlaylist(r.Context(), actorFromRequest(r), r.PathValue("ident"), body)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已重命名 Navidrome 歌单", "playlist", r.PathValue("ident"))
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeDeletePlaylist(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.DeletePlaylist(r.Context(), actorFromRequest(r), r.PathValue("ident"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已删除 Navidrome 歌单", "playlist", r.PathValue("ident"))
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeAddSongs(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	var body map[string]any
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	res, err := s.deps.Navidrome.AddPlaylistTracks(r.Context(), actorFromRequest(r), r.PathValue("ident"), body)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已向 Navidrome 歌单添加曲目", "playlist", r.PathValue("ident"))
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeRemoveSongs(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	var body map[string]any
	if !decodeJSON(w, r, s.defaultJSONLimit(), &body) {
		return
	}
	res, err := s.deps.Navidrome.RemovePlaylistTracks(r.Context(), actorFromRequest(r), r.PathValue("ident"), body)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已从 Navidrome 歌单移除曲目", "playlist", r.PathValue("ident"))
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeSongs(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.ListSongs(r.Context(), parseQueryInt(r, "offset", 0), parseQueryInt(r, "size", 50))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeAlbums(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.ListAlbums(r.Context(), parseQueryInt(r, "offset", 0), parseQueryInt(r, "size", 50), r.URL.Query().Get("sort"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeAlbum(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.GetAlbum(r.Context(), r.PathValue("ident"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeArtists(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.ListArtists(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeArtist(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.GetArtist(r.Context(), r.PathValue("ident"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeSearch(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.Search(r.Context(), r.URL.Query().Get("query"), parseQueryInt(r, "offset", 0))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeCover(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	if err := s.deps.Navidrome.ProxyCover(w, r, r.PathValue("ident")); err != nil {
		writeDomainError(w, err)
	}
}

func (s *Server) handleNavidromeStream(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	if err := s.deps.Navidrome.ProxyStream(w, r, r.PathValue("ident")); err != nil {
		writeDomainError(w, err)
	}
}

func (s *Server) handleNavidromeTest(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.TestConnection(r.Context())
	if err != nil {
		s.pushLog("warning", "Navidrome 连接测试失败："+err.Error(), "library", "")
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "Navidrome 连接测试成功", "library", "")
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeScanStatus(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.ScanStatus(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleNavidromeScan(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	res, err := s.deps.Navidrome.StartScan(r.Context(), actorFromRequest(r))
	if err != nil {
		s.pushLog("warning", "触发 Navidrome 扫库失败："+err.Error(), "library", "")
		writeDomainError(w, err)
		return
	}
	s.pushLog("info", "已触发 Navidrome 曲库扫描", "library", "")
	writeJSON(w, http.StatusOK, res)
}
