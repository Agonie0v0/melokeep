package httpapi

import (
	"archive/zip"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/agent"
	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/library"
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

func (s *Server) handleArtistAvatar(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if name == "" || library.IsIgnoredArtist(name) {
		writeError(w, http.StatusNotFound, "歌手头像不存在")
		return
	}
	snap := s.currentSnapshot()
	var (
		data []byte
		mime string
		err  error
	)
	if s.deps.Library != nil {
		data, mime, err = s.deps.Library.ResolveArtistAvatar(r.Context(), snap, name)
	} else {
		fetcher := library.NewArtistArtworkFetcher(s.deps.Transport, s.deps.Store, s.deps.Files, library.ArtistArtworkOptions{})
		data, mime, err = fetcher.ResolveArtistAvatar(r.Context(), snap, name)
	}
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if mime == "" {
		mime = "image/jpeg"
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(data)
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

const maxNavidromeArchiveTracks = 500

func (s *Server) handleNavidromeDownload(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	ident := strings.TrimSpace(r.PathValue("ident"))
	if ident == "" {
		writeError(w, http.StatusBadRequest, "曲目 ID 不能为空")
		return
	}
	statusCode, hdr, body, err := s.deps.Navidrome.OpenDownload(r.Context(), ident, r.Header.Get("Range"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	defer body.Close()

	track, _ := s.deps.Navidrome.GetSong(r.Context(), ident)
	filename := navidromeDownloadFilename(track, ident, hdr)
	for _, k := range []string{"Content-Length", "Content-Range", "Accept-Ranges", "Content-Type"} {
		if v := hdr.Get(k); v != "" {
			w.Header().Set(k, v)
		}
	}
	if w.Header().Get("Content-Type") == "" {
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	w.Header().Set("Content-Disposition", formatAttachmentContentDisposition(filename))
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(statusCode)
	if r.Method != http.MethodHead {
		_, _ = io.Copy(w, body)
	}
}

func (s *Server) handleNavidromeDownloadArchive(w http.ResponseWriter, r *http.Request) {
	if !s.requireNavidrome(w) {
		return
	}
	var req struct {
		SongIDs []string `json:"song_ids"`
		All     bool     `json:"all"`
		Query   string   `json:"query"`
		AlbumID string   `json:"album_id"`
	}
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	tracks, err := s.resolveArchiveTracks(r, req.SongIDs, req.All, req.Query, req.AlbumID)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if len(tracks) == 0 {
		writeError(w, http.StatusBadRequest, "请选择至少一首待下载的曲目")
		return
	}
	if len(tracks) > maxNavidromeArchiveTracks {
		writeError(w, http.StatusBadRequest, "单次打包下载最多支持 500 首，请分批或按专辑下载")
		return
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", formatAttachmentContentDisposition("navidrome-tracks.zip"))
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)

	zw := zip.NewWriter(w)
	usedNames := make(map[string]int, len(tracks)+1)
	var errLines []string
	for _, track := range tracks {
		if r.Context().Err() != nil {
			break
		}
		if errLine := s.streamTrackIntoZip(r, zw, track, usedNames); errLine != "" {
			errLines = append(errLines, errLine)
		}
	}
	if len(errLines) > 0 && r.Context().Err() == nil {
		errName := uniqueZipEntryName("_download_errors.txt", usedNames)
		if ew, err := zw.CreateHeader(&zip.FileHeader{
			Name:     errName,
			Method:   zip.Store,
			Modified: time.Now(),
		}); err == nil {
			_, _ = io.WriteString(ew, strings.Join(errLines, "\n")+"\n")
		}
	}
	_ = zw.Close()
}

func (s *Server) resolveArchiveTracks(r *http.Request, songIDs []string, all bool, query, albumID string) ([]model.TrackDTO, error) {
	ctx := r.Context()
	cleanAlbum := strings.TrimSpace(albumID)
	cleanQuery := strings.TrimSpace(query)
	var tracks []model.TrackDTO

	switch {
	case cleanAlbum != "":
		raw, err := s.deps.Navidrome.GetAlbum(ctx, cleanAlbum)
		if err != nil {
			return nil, err
		}
		tracks, _ = decodeTracksEnvelope(raw)
	case all && cleanQuery != "":
		offset := 0
		for page := 0; page < 100; page++ {
			raw, err := s.deps.Navidrome.Search(ctx, cleanQuery, offset)
			if err != nil {
				return nil, err
			}
			pageTracks, hasMore := decodeTracksEnvelope(raw)
			if len(pageTracks) == 0 {
				break
			}
			tracks = append(tracks, pageTracks...)
			if len(tracks) > maxNavidromeArchiveTracks {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "单次打包下载最多支持 500 首，请分批或按专辑下载")
			}
			if !hasMore {
				break
			}
			offset += len(pageTracks)
		}
	case all:
		offset := 0
		for page := 0; page < 100; page++ {
			raw, err := s.deps.Navidrome.ListSongs(ctx, offset, 100)
			if err != nil {
				return nil, err
			}
			pageTracks, hasMore := decodeTracksEnvelope(raw)
			if len(pageTracks) == 0 {
				break
			}
			tracks = append(tracks, pageTracks...)
			if len(tracks) > maxNavidromeArchiveTracks {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "单次打包下载最多支持 500 首，请分批或按专辑下载")
			}
			if !hasMore {
				break
			}
			offset += len(pageTracks)
		}
	default:
		seen := make(map[string]struct{}, len(songIDs))
		for _, rawID := range songIDs {
			id := strings.TrimPrefix(strings.TrimSpace(rawID), "navidrome-")
			if id == "" {
				continue
			}
			if _, ok := seen[id]; ok {
				continue
			}
			seen[id] = struct{}{}
			if len(seen) > maxNavidromeArchiveTracks {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "单次打包下载最多支持 500 首，请分批或按专辑下载")
			}
			track, err := s.deps.Navidrome.GetSong(ctx, id)
			if err != nil {
				if ctx.Err() != nil {
					return nil, err
				}
				track = model.TrackDTO{ID: "navidrome-" + id, SourceID: id}
			}
			tracks = append(tracks, track)
		}
		return tracks, nil
	}

	seen := make(map[string]struct{}, len(tracks))
	deduped := make([]model.TrackDTO, 0, len(tracks))
	for _, t := range tracks {
		id := trackNavidromeID(t)
		if id == "" {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		deduped = append(deduped, t)
	}
	return deduped, nil
}

func (s *Server) streamTrackIntoZip(r *http.Request, zw *zip.Writer, track model.TrackDTO, usedNames map[string]int) string {
	id := trackNavidromeID(track)
	_, hdr, body, err := s.deps.Navidrome.OpenDownload(r.Context(), id, "")
	if err != nil {
		label := strings.TrimSpace(track.Title)
		if label == "" {
			label = id
		}
		return fmt.Sprintf("%s (%s): %v", label, id, err)
	}
	defer body.Close()

	entryName := uniqueZipEntryName(navidromeDownloadFilename(track, id, hdr), usedNames)
	ew, err := zw.CreateHeader(&zip.FileHeader{
		Name:     entryName,
		Method:   zip.Store,
		Modified: time.Now(),
	})
	if err != nil {
		return fmt.Sprintf("%s (%s): 创建 ZIP 条目失败: %v", entryName, id, err)
	}
	if _, err := io.Copy(ew, body); err != nil {
		return fmt.Sprintf("%s (%s): 写入音频流失败: %v", entryName, id, err)
	}
	return ""
}

func decodeTracksEnvelope(raw any) ([]model.TrackDTO, bool) {
	if raw == nil {
		return nil, false
	}
	encoded, err := json.Marshal(raw)
	if err != nil {
		return nil, false
	}
	var env struct {
		Tracks  []model.TrackDTO `json:"tracks"`
		HasMore bool             `json:"has_more"`
	}
	if err := json.Unmarshal(encoded, &env); err != nil {
		return nil, false
	}
	return env.Tracks, env.HasMore
}

func trackNavidromeID(track model.TrackDTO) string {
	if id := strings.TrimSpace(track.SourceID); id != "" {
		return id
	}
	return strings.TrimPrefix(strings.TrimSpace(track.ID), "navidrome-")
}

func navidromeDownloadFilename(track model.TrackDTO, ident string, hdr http.Header) string {
	var upstreamName string
	if disp := strings.TrimSpace(hdr.Get("Content-Disposition")); disp != "" {
		if _, params, err := mime.ParseMediaType(disp); err == nil {
			upstreamName = strings.TrimSpace(params["filename"])
		}
	}

	ext := sanitizeAudioExt(filepath.Ext(upstreamName))
	if ext == "" {
		ext = extFromContentType(hdr.Get("Content-Type"))
	}
	if ext == "" && track.Playback.Codec != nil {
		ext = extFromCodec(*track.Playback.Codec)
	}
	if ext == "" && len(track.Qualities) > 0 {
		ext = extFromCodec(track.Qualities[0].Codec)
	}
	if ext == "" {
		ext = ".bin"
	}

	title := strings.TrimSpace(track.Title)
	artist := strings.TrimSpace(track.Artist)
	var base string
	switch {
	case artist != "" && title != "":
		base = artist + " - " + title
	case title != "":
		base = title
	case upstreamName != "":
		base = strings.TrimSuffix(filepath.Base(upstreamName), filepath.Ext(upstreamName))
	default:
		base = "track-" + strings.TrimPrefix(strings.TrimSpace(ident), "navidrome-")
	}
	base = sanitizeArchiveFilenameStem(base)
	if base == "" {
		base = "track"
	}
	return base + ext
}

func sanitizeAudioExt(raw string) string {
	ext := strings.ToLower(strings.TrimSpace(raw))
	if ext == "" || !strings.HasPrefix(ext, ".") || len(ext) > 10 {
		return ""
	}
	for _, r := range ext[1:] {
		if !((r >= 'a' && r <= 'z') || (r >= '0' && r <= '9')) {
			return ""
		}
	}
	return ext
}

func extFromContentType(raw string) string {
	mimeOnly := strings.ToLower(strings.TrimSpace(strings.Split(raw, ";")[0]))
	switch mimeOnly {
	case "audio/flac", "audio/x-flac":
		return ".flac"
	case "audio/mpeg", "audio/mp3":
		return ".mp3"
	case "audio/mp4", "audio/x-m4a", "audio/m4a":
		return ".m4a"
	case "audio/aac":
		return ".aac"
	case "audio/ogg", "application/ogg":
		return ".ogg"
	case "audio/opus":
		return ".opus"
	case "audio/wav", "audio/x-wav", "audio/wave":
		return ".wav"
	case "audio/aiff", "audio/x-aiff":
		return ".aiff"
	case "audio/ape", "audio/x-ape":
		return ".ape"
	case "audio/wavpack":
		return ".wv"
	default:
		return ""
	}
}

func extFromCodec(raw string) string {
	c := strings.ToLower(strings.TrimSpace(raw))
	c = strings.TrimPrefix(c, ".")
	switch c {
	case "flac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "aiff", "ape", "wv":
		return "." + c
	case "alac":
		return ".m4a"
	case "vorbis":
		return ".ogg"
	default:
		return ""
	}
}

func sanitizeArchiveFilenameStem(raw string) string {
	var b strings.Builder
	for _, r := range strings.TrimSpace(raw) {
		switch r {
		case '/', '\\', ':', '*', '?', '"', '<', '>', '|', ';':
			b.WriteByte('_')
		default:
			if r < 0x20 || r == 0x7f {
				b.WriteByte('_')
			} else {
				b.WriteRune(r)
			}
		}
	}
	cleaned := strings.Trim(b.String(), " .")
	runes := []rune(cleaned)
	if len(runes) > 160 {
		cleaned = strings.Trim(string(runes[:160]), " .")
	}
	return cleaned
}

func uniqueZipEntryName(candidate string, used map[string]int) string {
	key := strings.ToLower(candidate)
	if used[key] == 0 {
		used[key] = 1
		return candidate
	}
	ext := filepath.Ext(candidate)
	stem := strings.TrimSuffix(candidate, ext)
	n := used[key] + 1
	for {
		next := fmt.Sprintf("%s (%d)%s", stem, n, ext)
		nextKey := strings.ToLower(next)
		if used[nextKey] == 0 {
			used[key] = n
			used[nextKey] = 1
			return next
		}
		n++
	}
}

func formatAttachmentContentDisposition(filename string) string {
	ext := filepath.Ext(filename)
	var ascii strings.Builder
	hasAlnum := false
	for _, r := range strings.TrimSuffix(filename, ext) {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			ascii.WriteRune(r)
			hasAlnum = true
		} else if r == '.' || r == '-' || r == '_' || r == ' ' {
			ascii.WriteRune(r)
		} else {
			ascii.WriteByte('_')
		}
	}
	asciiStem := strings.Trim(ascii.String(), " .")
	if !hasAlnum || asciiStem == "" {
		asciiStem = "download"
	}
	return fmt.Sprintf(`attachment; filename="%s%s"; filename*=UTF-8''%s`, asciiStem, ext, url.PathEscape(filename))
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
