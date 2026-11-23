package app

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/agent"
	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/bot"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/library"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/navidrome"
	"github.com/agonie0v0/melokeep/internal/operations"
	"github.com/agonie0v0/melokeep/internal/search"
	"github.com/agonie0v0/melokeep/internal/store"
)

type botBackendAdapter struct {
	app *App
}

func (b *botBackendAdapter) SearchTracks(ctx context.Context, actor auth.Actor, keyword string, sources []string) ([]model.TrackDTO, bool, error) {
	return b.SearchTracksStream(ctx, actor, keyword, sources, nil)
}

func (b *botBackendAdapter) ResultsPerSource() int {
	if b.app == nil {
		return 0
	}
	return b.app.Snapshot().Runtime.ResultsPerSource
}

func (b *botBackendAdapter) SearchTracksStream(ctx context.Context, actor auth.Actor, keyword string, sources []string, onTrack func(model.TrackDTO)) ([]model.TrackDTO, bool, error) {
	if b.app == nil || b.app.Search == nil {
		return nil, false, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "搜索服务未初始化")
	}
	snap := b.app.Snapshot()
	jobID, err := b.app.Search.CreateSearch(ctx, actor, snap, search.CreateRequest{
		Keyword: keyword,
		Sources: sources,
		Limit:   snap.Runtime.ResultsPerSource,
	})
	if err != nil {
		return nil, false, err
	}
	sub, err := b.app.Search.Subscribe(ctx, jobID, 0, "")
	if err != nil {
		return nil, false, err
	}
	defer sub.Close()

	tracks := make([]model.TrackDTO, 0)
	seen := make(map[string]int)
	partial := false
	for {
		ev, err := sub.Next(ctx)
		if err != nil {
			return tracks, true, nil
		}
		switch ev.Type {
		case "track":
			var tr model.TrackDTO
			if json.Unmarshal(ev.Data, &tr) == nil && tr.ID != "" {
				if idx, ok := seen[tr.ID]; ok {
					tracks[idx] = tr
				} else {
					seen[tr.ID] = len(tracks)
					tracks = append(tracks, tr)
					if onTrack != nil {
						onTrack(tr)
					}
				}
			}
		case "source":
			var sp search.SourceEventPayload
			if json.Unmarshal(ev.Data, &sp) == nil && sp.Status.State == "failed" {
				partial = true
			}
		case "done":
			return tracks, partial, nil
		}
	}
}

func (b *botBackendAdapter) AvailableSources() []model.AvailableSourceDTO {
	if b.app == nil || b.app.Providers == nil {
		return nil
	}
	all := b.app.Providers.AvailableSources()
	snap := b.app.Snapshot()
	if len(snap.Runtime.Sources) == 0 {
		return nil
	}
	byKey := make(map[string]model.AvailableSourceDTO, len(all)*2)
	for _, src := range all {
		if src.EngineName != "" {
			byKey[src.EngineName] = src
		}
		if src.ID != "" {
			byKey[src.ID] = src
			if canon := config.CanonicalEngineName(src.ID); canon != "" {
				byKey[canon] = src
			}
		}
	}
	seen := make(map[string]bool, len(snap.Runtime.Sources))
	filtered := make([]model.AvailableSourceDTO, 0, len(snap.Runtime.Sources))
	for _, raw := range snap.Runtime.Sources {
		canon := config.CanonicalEngineName(raw)
		src, ok := byKey[canon]
		if !ok {
			src, ok = byKey[strings.TrimSpace(raw)]
		}
		if !ok {
			continue
		}
		dedupeKey := src.EngineName
		if dedupeKey == "" {
			dedupeKey = src.ID
		}
		if seen[dedupeKey] {
			continue
		}
		seen[dedupeKey] = true
		filtered = append(filtered, src)
	}
	return filtered
}

func (b *botBackendAdapter) EnqueueDownload(ctx context.Context, actor auth.Actor, publicID, qualityID, idempotencyKey string) (model.DownloadJobDTO, error) {
	if b.app == nil || b.app.Download == nil {
		return model.DownloadJobDTO{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "下载服务未初始化")
	}
	if b.app.Cfg.ReadOnly {
		return model.DownloadJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止创建下载")
	}
	snap := b.app.Snapshot()
	var autoOrg *download.AutoOrganizeConfig
	if b.app.Library != nil {
		if ls, err := b.app.Library.Settings(ctx); err == nil && (ls.AutoScrape || ls.AutoOrganize) {
			action := "organize"
			switch {
			case ls.AutoScrape && ls.AutoOrganize:
				action = "both"
			case ls.AutoScrape:
				action = "scrape"
			}
			autoOrg = &download.AutoOrganizeConfig{Enabled: true, Action: action}
		}
	}
	res, err := b.app.Download.Enqueue(ctx, actor, snap, download.EnqueueRequest{
		PublicID:       publicID,
		QualityID:      qualityID,
		IdempotencyKey: idempotencyKey,
		AutoOrganize:   autoOrg,
	})
	if err != nil {
		return model.DownloadJobDTO{}, err
	}
	return b.app.Download.ToDTO(ctx, res.Download)
}

func (b *botBackendAdapter) GetDownload(ctx context.Context, _ auth.Actor, downloadID string) (model.DownloadJobDTO, error) {
	if b.app == nil || b.app.Download == nil {
		return model.DownloadJobDTO{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "下载服务未初始化")
	}
	dl, ok, err := b.app.Download.Get(ctx, downloadID)
	if err != nil {
		return model.DownloadJobDTO{}, err
	}
	if !ok {
		return model.DownloadJobDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "下载任务不存在")
	}
	return b.app.Download.ToDTO(ctx, dl)
}

func (b *botBackendAdapter) ListDownloads(ctx context.Context, _ auth.Actor, limit int) ([]model.DownloadJobDTO, error) {
	if b.app == nil || b.app.Download == nil {
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "下载服务未初始化")
	}
	rows, err := b.app.Download.List(ctx, limit)
	if err != nil {
		return nil, err
	}
	out := make([]model.DownloadJobDTO, 0, len(rows))
	for _, r := range rows {
		dto, err := b.app.Download.ToDTO(ctx, r)
		if err == nil {
			out = append(out, dto)
		}
	}
	return out, nil
}

func (b *botBackendAdapter) ControlDownload(ctx context.Context, actor auth.Actor, downloadID, action string) error {
	if b.app == nil || b.app.Download == nil {
		return model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "下载服务未初始化")
	}
	if b.app.Cfg.ReadOnly {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止修改下载状态")
	}
	switch strings.ToLower(strings.TrimSpace(action)) {
	case "pause":
		_, err := b.app.Download.Pause(ctx, actor, downloadID)
		return err
	case "cancel":
		_, err := b.app.Download.Cancel(ctx, actor, downloadID)
		return err
	case "resume":
		_, err := b.app.Download.Resume(ctx, actor, downloadID, b.app.Snapshot())
		return err
	case "retry":
		_, err := b.app.Download.Retry(ctx, actor, downloadID, b.app.Snapshot())
		return err
	case "delete", "remove":
		return b.app.Download.Delete(ctx, actor, downloadID)
	default:
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的下载控制动作")
	}
}

func (b *botBackendAdapter) QueryLibraryFiles(ctx context.Context, q store.LibraryFilesQuery) (model.LibraryFilesPageDTO, error) {
	if b.app == nil || b.app.Indexer == nil {
		return model.LibraryFilesPageDTO{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "索引服务未初始化")
	}
	return b.app.Indexer.QueryFiles(ctx, q)
}

func (b *botBackendAdapter) ResolveDownloadFile(ctx context.Context, relPath string) (string, int64, int64, error) {
	if b.app == nil || b.app.Files == nil {
		return "", 0, 0, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件服务未初始化")
	}
	if ctx == nil {
		ctx = context.Background()
	}
	raw := strings.TrimSpace(relPath)
	if raw == "" {
		return "", 0, 0, model.NewError(model.ErrNotFound, http.StatusNotFound, "文件不存在或不在托管根目录内")
	}
	roots := b.app.Files.Roots()
	resolveAsset := func(asset store.AssetRecord) (string, int64, int64, bool) {
		if asset.Status == "deleted" {
			return "", 0, 0, false
		}
		rootRec, rOK, rErr := b.app.Store.GetRoot(ctx, asset.RootID)
		if rErr != nil || !rOK {
			return "", 0, 0, false
		}
		cand := filepath.Join(rootRec.CanonicalPath, filepath.FromSlash(asset.RelPath))
		vp, vErr := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: true})
		if vErr != nil {
			return "", 0, 0, false
		}
		st, statErr := os.Stat(vp.AbsPath)
		if statErr != nil {
			return "", 0, 0, false
		}
		return vp.AbsPath, st.Size(), st.ModTime().UnixNano(), true
	}

	if b.app.Store != nil {
		if strings.HasPrefix(raw, "asset:") {
			assetID := strings.TrimPrefix(raw, "asset:")
			if asset, ok, err := b.app.Store.GetAsset(ctx, assetID); err == nil && ok {
				if p, sz, mt, hit := resolveAsset(asset); hit {
					return p, sz, mt, nil
				}
			}
		}
		if asset, ok, err := b.app.Store.ResolveAssetAlias(ctx, raw); err == nil && ok {
			if p, sz, mt, hit := resolveAsset(asset); hit {
				return p, sz, mt, nil
			}
		}
	}

	for _, r := range roots {
		cand := raw
		if !filepath.IsAbs(cand) {
			cand = filepath.Join(r.CanonicalPath, filepath.FromSlash(raw))
		}
		vp, err := files.ValidatePath([]files.Root{r}, cand, files.ValidateOptions{MustExist: true})
		if err == nil {
			st, statErr := os.Stat(vp.AbsPath)
			if statErr != nil {
				continue
			}
			return vp.AbsPath, st.Size(), st.ModTime().UnixNano(), nil
		}
	}
	return "", 0, 0, model.NewError(model.ErrNotFound, http.StatusNotFound, "文件不存在或不在托管根目录内")
}

type botAISessionBind struct {
	SessionID    string `json:"session_id"`
	AllowActions *bool  `json:"allow_actions,omitempty"`
}

func (b *botBackendAdapter) RunAICommand(ctx context.Context, actor auth.Actor, command, text string, imageBytes []byte) (string, error) {
	if b.app == nil || b.app.Agent == nil {
		return "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "AI 助手服务未初始化")
	}
	const nsBotAISession = "bot_ai_session"
	actorKey := actor.Key()

	loadBind := func() (botAISessionBind, int64) {
		if b.app.Store == nil {
			return botAISessionBind{}, 0
		}
		if rec, ok, err := b.app.Store.GetDurableRecord(ctx, nsBotAISession, actorKey); err == nil && ok {
			var bind botAISessionBind
			if json.Unmarshal([]byte(rec.BodyJSON), &bind) == nil {
				return bind, rec.Revision
			}
		}
		return botAISessionBind{}, 0
	}
	saveBind := func(bind botAISessionBind) {
		if b.app.Store == nil {
			return
		}
		raw, _ := json.Marshal(bind)
		_, _ = b.app.Store.PutDurableRecord(ctx, nsBotAISession, actorKey, string(raw), 0)
	}

	sub := strings.ToLower(strings.TrimSpace(command))
	sub = strings.TrimPrefix(sub, "/")
	if sub == "ai" {
		sub = ""
	} else {
		sub = strings.TrimPrefix(sub, "ai_")
	}
	prompt := strings.TrimSpace(text)
	if sub == "" && prompt != "" && len(imageBytes) == 0 {
		fields := strings.Fields(prompt)
		if len(fields) > 0 {
			first := strings.ToLower(fields[0])
			switch first {
			case "status", "new", "reset", "stop", "readonly", "write":
				sub = first
				prompt = strings.TrimSpace(prompt[len(fields[0]):])
			}
		}
	}

	bind, _ := loadBind()
	switch sub {
	case "status":
		sessions, err := b.app.Agent.ListSessions(ctx)
		if err != nil {
			return "", err
		}
		mode := "可读写"
		if b.app.Cfg.ReadOnly || actor.ReadOnlyAI || (bind.AllowActions != nil && !*bind.AllowActions) {
			mode = "仅只读"
		}
		if bind.SessionID != "" {
			if cur, gErr := b.app.Agent.GetSession(ctx, bind.SessionID); gErr == nil {
				return "当前会话 " + cur.ID + "（状态: " + string(cur.Status) + "，模式: " + mode + "，消息数: " + strconv.Itoa(len(cur.Messages)) + "）；总会话数: " + strconv.Itoa(len(sessions)), nil
			}
		}
		return "暂无绑定 AI 会话（模式: " + mode + "）；总会话数: " + strconv.Itoa(len(sessions)), nil
	case "stop":
		if bind.SessionID == "" {
			return "当前没有活跃的 AI 会话", nil
		}
		stopped, err := b.app.Agent.Stop(ctx, bind.SessionID)
		if err != nil {
			return "", err
		}
		return "已请求停止会话 " + stopped.ID + "（当前状态: " + string(stopped.Status) + "）", nil
	case "readonly":
		v := false
		bind.AllowActions = &v
		saveBind(bind)
		return "当前机器人 AI 助手已切换为只读模式", nil
	case "write":
		if b.app.Cfg.ReadOnly || actor.ReadOnlyAI {
			return "系统或账号当前限制为只读模式，无法开启写操作", nil
		}
		v := true
		bind.AllowActions = &v
		saveBind(bind)
		return "当前机器人 AI 助手已开启可执行写入模式", nil
	case "new", "reset":
		sess, err := b.app.Agent.CreateSession(ctx)
		if err != nil {
			return "", err
		}
		bind.SessionID = sess.ID
		saveBind(bind)
		if prompt == "" && len(imageBytes) == 0 {
			return "已开启新的 AI 会话: " + sess.ID, nil
		}
	}

	var curSess agent.PublicSessionDTO
	hasValidSess := false
	if bind.SessionID != "" {
		if existing, gErr := b.app.Agent.GetSession(ctx, bind.SessionID); gErr == nil {
			curSess = existing
			hasValidSess = true
		}
	}
	if !hasValidSess {
		created, err := b.app.Agent.CreateSession(ctx)
		if err != nil {
			return "", err
		}
		bind.SessionID = created.ID
		saveBind(bind)
		curSess = created
	}

	var imageDataURI string
	if len(imageBytes) > 0 {
		mime := "image/jpeg"
		switch {
		case bytes.HasPrefix(imageBytes, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}):
			mime = "image/png"
		case len(imageBytes) >= 12 && bytes.HasPrefix(imageBytes, []byte("RIFF")) && bytes.Equal(imageBytes[8:12], []byte("WEBP")):
			mime = "image/webp"
		}
		imageDataURI = "data:" + mime + ";base64," + base64.StdEncoding.EncodeToString(imageBytes)
	}
	allow := !b.app.Cfg.ReadOnly && !actor.ReadOnlyAI
	if allow && bind.AllowActions != nil {
		allow = *bind.AllowActions
	}
	prevMsgCount := len(curSess.Messages)
	updated, err := b.app.Agent.StartTurn(ctx, actor, curSess.ID, agent.TurnRequest{
		Message:      prompt,
		Image:        imageDataURI,
		AllowActions: &allow,
	})
	if err != nil {
		return "", err
	}

	waitDeadline := time.Now().Add(90 * time.Second)
	for (updated.Status == agent.StatusRunning || updated.Status == agent.StatusWaitingTasks || updated.Status == agent.StatusStopping) && time.Now().Before(waitDeadline) {
		select {
		case <-ctx.Done():
			break
		case <-time.After(250 * time.Millisecond):
		}
		if ctx.Err() != nil {
			break
		}
		nextSess, gErr := b.app.Agent.GetSession(ctx, curSess.ID)
		if gErr != nil {
			break
		}
		updated = nextSess
	}

	for i := len(updated.Messages) - 1; i >= prevMsgCount && i >= 0; i-- {
		if updated.Messages[i].Role == "assistant" && strings.TrimSpace(updated.Messages[i].Content) != "" {
			return updated.Messages[i].Content, nil
		}
	}
	if updated.Status == agent.StatusNeedsDecision && strings.TrimSpace(updated.Decision) != "" {
		return updated.Decision, nil
	}
	for i := len(updated.Messages) - 1; i >= 0; i-- {
		if updated.Messages[i].Role == "assistant" && strings.TrimSpace(updated.Messages[i].Content) != "" {
			return updated.Messages[i].Content, nil
		}
	}
	if strings.TrimSpace(updated.Partial) != "" {
		return updated.Partial, nil
	}
	if strings.TrimSpace(updated.Detail) != "" {
		return "会话 " + updated.ID + "（" + string(updated.Status) + "）：" + updated.Detail, nil
	}
	return "会话 " + updated.ID + " 状态: " + string(updated.Status), nil
}

type botGatewayAdapter struct {
	svc               *bot.Service
	allowTestLoopback bool
}

func (g *botGatewayAdapter) ReadConfig(_ context.Context) (any, error) {
	return g.svc.PublicConfig(), nil
}

func (g *botGatewayAdapter) WriteConfig(ctx context.Context, actor auth.Actor, body map[string]any) (any, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的机器人配置格式", err)
	}
	cfg := bot.DefaultConfig()
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "机器人配置解析失败", err)
	}
	cfg.AllowTestLoopback = g.allowTestLoopback
	var clearSecrets []string
	if cs, ok := body["clear_secrets"].([]any); ok {
		for _, item := range cs {
			if s, ok := item.(string); ok && s != "" {
				clearSecrets = append(clearSecrets, s)
			}
		}
	}
	return g.svc.UpdateConfig(ctx, actor, cfg, clearSecrets)
}

func (g *botGatewayAdapter) TestConnection(ctx context.Context, body map[string]any) (any, error) {
	channel, _ := body["channel"].(string)
	var incoming *bot.Config
	var clearSecrets []string
	if cfgMap, ok := body["config"].(map[string]any); ok {
		raw, err := json.Marshal(cfgMap)
		if err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的机器人配置格式", err)
		}
		cfg := bot.DefaultConfig()
		if err := json.Unmarshal(raw, &cfg); err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "机器人配置解析失败", err)
		}
		cfg.AllowTestLoopback = g.allowTestLoopback
		incoming = &cfg
		if cs, ok := cfgMap["clear_secrets"].([]any); ok {
			for _, item := range cs {
				if s, ok := item.(string); ok && s != "" {
					clearSecrets = append(clearSecrets, s)
				}
			}
		}
	}
	return g.svc.TestConnection(ctx, channel, incoming, clearSecrets)
}

func (g *botGatewayAdapter) ServeSignedDownload(w http.ResponseWriter, r *http.Request, ident, expires, signature string) error {
	links := g.svc.Links()
	if links == nil {
		return model.NewError(model.ErrNotFound, http.StatusNotFound, "下载链接服务未配置")
	}
	exp, err := strconv.ParseInt(strings.TrimSpace(expires), 10, 64)
	if err != nil {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "下载链接无效")
	}
	absPath, err := links.Verify(r.Context(), ident, exp, signature, time.Now())
	if err != nil {
		return err
	}
	f, err := os.Open(absPath)
	if err != nil {
		return model.NewError(model.ErrNotFound, http.StatusGone, "文件已删除或不可访问")
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return model.NewError(model.ErrNotFound, http.StatusGone, "文件已删除或不可访问")
	}
	w.Header().Set("Content-Disposition", `attachment; filename="`+filepath.Base(absPath)+`"`)
	http.ServeContent(w, r, filepath.Base(absPath), st.ModTime(), f)
	return nil
}

func (g *botGatewayAdapter) VerifyWeCom(w http.ResponseWriter, r *http.Request) error {
	q := r.URL.Query()
	plain, err := g.svc.HandleWeComVerify(q.Get("msg_signature"), q.Get("timestamp"), q.Get("nonce"), q.Get("echostr"))
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(plain)
	return nil
}

func (g *botGatewayAdapter) ReceiveWeCom(w http.ResponseWriter, r *http.Request) error {
	body, err := io.ReadAll(io.LimitReader(r.Body, bot.MaxCallbackBodyBytes+1))
	if err != nil {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "读取请求体失败")
	}
	q := r.URL.Query()
	if _, err := g.svc.HandleWeComCallback(r.Context(), body, q.Get("msg_signature"), q.Get("timestamp"), q.Get("nonce")); err != nil {
		return err
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("success"))
	return nil
}

func (g *botGatewayAdapter) ReceiveFeishu(w http.ResponseWriter, r *http.Request) error {
	body, err := io.ReadAll(io.LimitReader(r.Body, bot.MaxCallbackBodyBytes+1))
	if err != nil {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "读取请求体失败")
	}
	res, err := g.svc.HandleFeishuCallback(
		r.Context(),
		body,
		r.Header.Get("X-Lark-Request-Timestamp"),
		r.Header.Get("X-Lark-Request-Nonce"),
		r.Header.Get("X-Lark-Signature"),
	)
	if err != nil {
		return err
	}
	return writeRawJSON(w, http.StatusOK, res)
}

func (g *botGatewayAdapter) ReceiveDingTalk(w http.ResponseWriter, r *http.Request) error {
	body, err := io.ReadAll(io.LimitReader(r.Body, bot.MaxCallbackBodyBytes+1))
	if err != nil {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "读取请求体失败")
	}
	res, err := g.svc.HandleDingTalkCallback(
		r.Context(),
		body,
		r.Header.Get("timestamp"),
		r.Header.Get("sign"),
	)
	if err != nil {
		return err
	}
	return writeRawJSON(w, http.StatusOK, res)
}

func (g *botGatewayAdapter) ReceiveDiscord(w http.ResponseWriter, r *http.Request) error {
	body, err := io.ReadAll(io.LimitReader(r.Body, bot.MaxCallbackBodyBytes+1))
	if err != nil {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "读取请求体失败")
	}
	res, err := g.svc.HandleDiscordCallback(
		r.Context(),
		body,
		r.Header.Get("X-Signature-Timestamp"),
		r.Header.Get("X-Signature-Ed25519"),
	)
	if err != nil {
		return err
	}
	return writeRawJSON(w, http.StatusOK, res)
}

func (g *botGatewayAdapter) RegisterDiscordCommands(ctx context.Context) (any, error) {
	return g.svc.RegisterDiscordCommands(ctx)
}

type navidromeGatewayAdapter struct {
	client *navidrome.Client
	app    *App
}

type navDeleteTarget struct {
	song     navidrome.SongFileInfo
	target   files.ValidatedPath
	size     int64
	revision string
}

func relRemoteSubpath(remoteRoot, remoteSong string) ([]string, error) {
	isWin := (len(remoteRoot) >= 2 && remoteRoot[1] == ':' && ((remoteRoot[0] >= 'a' && remoteRoot[0] <= 'z') || (remoteRoot[0] >= 'A' && remoteRoot[0] <= 'Z'))) ||
		strings.HasPrefix(remoteRoot, `\\`) || strings.Contains(remoteRoot, `\`)
	var rootParts, songParts []string
	if isWin {
		nr := strings.ReplaceAll(strings.TrimSpace(remoteRoot), `\`, "/")
		ns := strings.ReplaceAll(strings.TrimSpace(remoteSong), `\`, "/")
		if len(nr) < 2 || nr[1] != ':' || len(ns) < 2 || ns[1] != ':' || !strings.EqualFold(nr[:2], ns[:2]) {
			return nil, model.NewError(model.ErrConflict, http.StatusConflict, "歌曲未返回映射目录内的真实绝对路径。请在 Navidrome 的播放器设置中为 melokeep 启用 Report Real Path")
		}
		rootCleaned := path.Clean("/" + strings.TrimLeft(nr[2:], "/"))
		songCleaned := path.Clean("/" + strings.TrimLeft(ns[2:], "/"))
		if rootCleaned != "/" && !strings.HasPrefix(strings.ToLower(songCleaned), strings.ToLower(rootCleaned)+"/") {
			return nil, model.NewError(model.ErrConflict, http.StatusConflict, "歌曲未返回映射目录内的真实绝对路径。请在 Navidrome 的播放器设置中为 melokeep 启用 Report Real Path")
		}
		rel := strings.TrimLeft(songCleaned[len(rootCleaned):], "/")
		if rel == "" {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "歌曲文件路径无效")
		}
		rootParts = strings.Split(strings.Trim(remoteSong, `/\`), "/")
		songParts = strings.Split(rel, "/")
	} else {
		rClean := path.Clean(strings.TrimSpace(remoteRoot))
		sClean := path.Clean(strings.TrimSpace(remoteSong))
		if !strings.HasPrefix(rClean, "/") || !strings.HasPrefix(sClean, "/") {
			return nil, model.NewError(model.ErrConflict, http.StatusConflict, "歌曲未返回映射目录内的真实绝对路径。请在 Navidrome 的播放器设置中为 melokeep 启用 Report Real Path")
		}
		prefix := rClean
		if prefix != "/" {
			prefix += "/"
		}
		if !strings.HasPrefix(sClean, prefix) {
			return nil, model.NewError(model.ErrConflict, http.StatusConflict, "歌曲未返回映射目录内的真实绝对路径。请在 Navidrome 的播放器设置中为 melokeep 启用 Report Real Path")
		}
		rel := strings.TrimPrefix(sClean, prefix)
		if rel == "" {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "歌曲文件路径无效")
		}
		rootParts = strings.Split(strings.Trim(remoteRoot, "/"), "/")
		songParts = strings.Split(rel, "/")
	}
	for _, p := range rootParts {
		if p == ".." {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "共享音乐目录路径不能包含 ..")
		}
	}
	for _, p := range songParts {
		if p == "" || p == "." || p == ".." || strings.ContainsAny(p, `/\:`) {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "歌曲文件路径无效")
		}
	}
	return songParts, nil
}

func (n *navidromeGatewayAdapter) resolveDeleteRoot() (string, string, error) {
	s := n.client.Settings()
	if !s.Enabled || !s.DeleteEnabled {
		return "", "", model.NewError(model.ErrConflict, http.StatusConflict, "Navidrome 曲库文件删除未启用")
	}
	localRoot := strings.TrimSpace(s.MusicRoot)
	remoteRoot := strings.TrimSpace(s.ServerMusicRoot)
	if localRoot == "" || remoteRoot == "" || !filepath.IsAbs(localRoot) {
		return "", "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写共享音乐目录的两个绝对路径")
	}
	if n.app == nil || n.app.Files == nil {
		return "", "", model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件协调服务未初始化")
	}
	roots := n.app.Files.Roots()
	absDir := filepath.Clean(localRoot)
	matchedRoot := false
	for _, r := range roots {
		rel, rErr := filepath.Rel(filepath.Clean(r.CanonicalPath), absDir)
		if rErr == nil && rel == "." {
			matchedRoot = true
			break
		}
	}
	if !matchedRoot {
		vp, err := files.ValidatePath(roots, absDir, files.ValidateOptions{MustExist: false})
		if err != nil {
			return "", "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "共享音乐目录必须已挂载到 MeloKeep 音乐根目录内: "+err.Error())
		}
		absDir = vp.AbsPath
	}
	st, statErr := os.Lstat(absDir)
	if statErr != nil || !st.IsDir() {
		return "", "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "共享音乐目录必须为有效目录")
	}
	if isLink, lErr := files.IsReparseOrSymlink(absDir, st); lErr != nil || isLink {
		return "", "", model.NewError(model.ErrForbidden, http.StatusForbidden, "共享目录不能使用符号链接或目录联接")
	}
	return absDir, remoteRoot, nil
}

func (n *navidromeGatewayAdapter) resolveDeleteTarget(ctx context.Context, ident string) (navDeleteTarget, error) {
	if err := n.client.CanDelete(auth.WebAdminActor("web")); err != nil {
		return navDeleteTarget{}, err
	}
	localDir, remoteRoot, err := n.resolveDeleteRoot()
	if err != nil {
		return navDeleteTarget{}, err
	}
	songInfo, err := n.client.GetSongFileInfo(ctx, ident)
	if err != nil {
		return navDeleteTarget{}, err
	}
	relParts, err := relRemoteSubpath(remoteRoot, songInfo.Path)
	if err != nil {
		return navDeleteTarget{}, err
	}
	targetCand := filepath.Join(append([]string{localDir}, relParts...)...)
	targetVP, err := files.ValidatePath(n.app.Files.Roots(), targetCand, files.ValidateOptions{MustExist: true, AudioOnly: true})
	if err != nil {
		return navDeleteTarget{}, err
	}
	st, statErr := os.Stat(targetVP.AbsPath)
	if statErr != nil {
		return navDeleteTarget{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "共享歌曲文件不存在")
	}
	if songInfo.Size == nil || *songInfo.Size != st.Size() {
		return navDeleteTarget{}, model.NewError(model.ErrConflict, http.StatusConflict, "共享文件大小与 Navidrome 记录不一致，请扫描曲库并检查目录映射")
	}
	fpRaw, _ := json.Marshal([]any{ident, localDir, remoteRoot, targetVP.AbsPath, st.Size(), st.ModTime().UnixNano()})
	sum := sha256.Sum256(fpRaw)
	return navDeleteTarget{
		song:     songInfo,
		target:   targetVP,
		size:     st.Size(),
		revision: hex.EncodeToString(sum[:]),
	}, nil
}

func (n *navidromeGatewayAdapter) GetPublicConfig(_ context.Context) (model.NavidromeConfigDTO, error) {
	return n.client.PublicConfig(), nil
}

func (n *navidromeGatewayAdapter) SaveConfig(ctx context.Context, actor auth.Actor, body map[string]any) (model.NavidromeConfigDTO, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return model.NavidromeConfigDTO{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的 Navidrome 配置", err)
	}
	var upd navidrome.SettingsUpdate
	if err := json.Unmarshal(raw, &upd); err != nil {
		return model.NavidromeConfigDTO{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "Navidrome 配置解析失败", err)
	}
	navidrome.NormalizeSettingsUpdate(body, &upd)
	return n.client.UpdateSettings(ctx, actor, upd)
}

func (n *navidromeGatewayAdapter) Capabilities(_ context.Context) (map[string]any, error) {
	s := n.client.Settings()
	delReady := false
	if s.Enabled && s.DeleteEnabled {
		if _, _, err := n.resolveDeleteRoot(); err == nil {
			delReady = true
		}
	}
	return map[string]any{
		"enabled":      s.Enabled,
		"delete_songs": delReady,
		"auto_scan":    s.AutoScan,
	}, nil
}

func (n *navidromeGatewayAdapter) DeletePreview(ctx context.Context, ident string) (map[string]any, error) {
	plan, err := n.resolveDeleteTarget(ctx, ident)
	if err != nil {
		return nil, err
	}
	title := plan.song.Title
	if strings.TrimSpace(title) == "" {
		title = plan.target.Name
	}
	return map[string]any{
		"id":         ident,
		"path":       plan.target.AbsPath,
		"title":      title,
		"size_bytes": plan.size,
		"revision":   plan.revision,
	}, nil
}

func (n *navidromeGatewayAdapter) DeleteSong(ctx context.Context, ident, revision string) (map[string]any, error) {
	if n.app != nil && n.app.Cfg.ReadOnly {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止删除共享曲库文件")
	}
	plan, err := n.resolveDeleteTarget(ctx, ident)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(revision) == "" || plan.revision != revision {
		return nil, model.NewError(model.ErrConflict, http.StatusConflict, "歌曲文件或目录映射已变化，请重新确认删除")
	}
	var assetID string
	var expectedRev int64
	if n.app != nil && n.app.Store != nil {
		pathKey := files.ComputePathKey(plan.target.Root.CaseMode, plan.target.RelPath)
		if asset, ok, aErr := n.app.Store.GetAssetByRootAndPathKey(ctx, plan.target.Root.ID, pathKey); aErr == nil && ok {
			assetID = asset.ID
			expectedRev = asset.Revision
		}
	}
	if _, err := n.app.Files.Execute(ctx, files.OperationRequest{
		Kind:             "delete",
		AssetID:          assetID,
		ExpectedRevision: expectedRev,
		SourcePath:       plan.target.AbsPath,
	}); err != nil {
		return nil, err
	}
	trackIDs := []string{"navidrome-" + ident}
	if assetID != "" {
		trackIDs = append(trackIDs, "asset:"+assetID, "local-"+assetID)
		if n.app != nil && n.app.Store != nil {
			_ = n.app.Store.DeleteDurableRecord(ctx, "favorite", "local-"+assetID)
			_ = n.app.Store.DeleteDurableRecord(ctx, "track_dto", "local-"+assetID)
		}
	}
	if n.app != nil && n.app.Store != nil {
		_ = n.app.Store.DeleteDurableRecord(ctx, "favorite", "navidrome-"+ident)
		_ = n.app.Store.DeleteDurableRecord(ctx, "track_dto", "navidrome-"+ident)
	}
	res := map[string]any{
		"deleted":   true,
		"path":      plan.target.AbsPath,
		"track_ids": trackIDs,
	}
	if scan, sErr := n.client.StartScan(ctx, auth.SystemActor()); sErr == nil {
		res["scan"] = scan
	} else {
		res["warning"] = "音乐文件已删除，但 Navidrome 扫描未启动，请稍后手动扫描服务器曲库"
	}
	return res, nil
}

func (n *navidromeGatewayAdapter) GetLyrics(ctx context.Context, ident, artist, title string) string {
	return n.client.GetLyrics(ctx, ident, artist, title)
}

func (n *navidromeGatewayAdapter) ListPlaylists(ctx context.Context) (any, error) {
	return n.client.GetPlaylists(ctx)
}

func (n *navidromeGatewayAdapter) GetPlaylist(ctx context.Context, ident string) (any, error) {
	return n.client.GetPlaylist(ctx, ident)
}

func (n *navidromeGatewayAdapter) CreatePlaylist(ctx context.Context, actor auth.Actor, body map[string]any) (any, error) {
	name, _ := body["name"].(string)
	return n.client.CreatePlaylist(ctx, actor, name, stringSliceAny(body["song_ids"]))
}

func (n *navidromeGatewayAdapter) RenamePlaylist(ctx context.Context, actor auth.Actor, ident string, body map[string]any) (any, error) {
	name, _ := body["name"].(string)
	return n.client.RenamePlaylist(ctx, actor, ident, name)
}

func (n *navidromeGatewayAdapter) DeletePlaylist(ctx context.Context, actor auth.Actor, ident string) (any, error) {
	if err := n.client.DeletePlaylist(ctx, actor, ident); err != nil {
		return nil, err
	}
	return map[string]any{"deleted": true, "id": ident}, nil
}

func (n *navidromeGatewayAdapter) AddPlaylistTracks(ctx context.Context, actor auth.Actor, ident string, body map[string]any) (any, error) {
	return n.client.AddPlaylistTracks(ctx, actor, ident, stringSliceAny(body["song_ids"]))
}

func (n *navidromeGatewayAdapter) RemovePlaylistTracks(ctx context.Context, actor auth.Actor, ident string, body map[string]any) (any, error) {
	rev, _ := body["revision"].(string)
	var indexes []int
	if raw, ok := body["indexes"].([]any); ok {
		for _, item := range raw {
			switch v := item.(type) {
			case float64:
				indexes = append(indexes, int(v))
			case int:
				indexes = append(indexes, v)
			}
		}
	}
	return n.client.RemovePlaylistTracks(ctx, actor, ident, indexes, rev)
}

func (n *navidromeGatewayAdapter) ListSongs(ctx context.Context, offset, size int) (any, error) {
	return n.client.GetSongs(ctx, offset, size)
}

func (n *navidromeGatewayAdapter) GetSong(ctx context.Context, ident string) (model.TrackDTO, error) {
	return n.client.GetSong(ctx, ident)
}

func (n *navidromeGatewayAdapter) ListAlbums(ctx context.Context, offset, size int, sort string) (any, error) {
	return n.client.GetAlbums(ctx, offset, size, sort)
}

func (n *navidromeGatewayAdapter) GetAlbum(ctx context.Context, ident string) (any, error) {
	return n.client.GetAlbum(ctx, ident)
}

func (n *navidromeGatewayAdapter) ListArtists(ctx context.Context) (any, error) {
	return n.client.GetArtists(ctx)
}

func (n *navidromeGatewayAdapter) GetArtist(ctx context.Context, ident string) (any, error) {
	return n.client.GetArtist(ctx, ident)
}

func (n *navidromeGatewayAdapter) Search(ctx context.Context, query string, offset int) (any, error) {
	return n.client.Search(ctx, query, offset)
}

func (n *navidromeGatewayAdapter) ProxyCover(w http.ResponseWriter, r *http.Request, ident string) error {
	return n.proxyMedia(w, r, "getCoverArt", ident)
}

func (n *navidromeGatewayAdapter) ProxyStream(w http.ResponseWriter, r *http.Request, ident string) error {
	return n.proxyMedia(w, r, "stream", ident)
}

func (n *navidromeGatewayAdapter) OpenDownload(ctx context.Context, ident, rangeHeader string) (int, http.Header, io.ReadCloser, error) {
	ms, err := n.client.OpenMedia(ctx, "download", ident, rangeHeader)
	if err != nil {
		return 0, nil, nil, err
	}
	return ms.StatusCode, ms.Headers, ms.Body, nil
}

func (n *navidromeGatewayAdapter) proxyMedia(w http.ResponseWriter, r *http.Request, endpoint, ident string) error {
	ms, err := n.client.OpenMedia(r.Context(), endpoint, ident, r.Header.Get("Range"))
	if err != nil {
		return err
	}
	defer ms.Body.Close()
	for k, vals := range ms.Headers {
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(ms.StatusCode)
	if r.Method != http.MethodHead {
		_, _ = io.Copy(w, ms.Body)
	}
	return nil
}

func (n *navidromeGatewayAdapter) TestConnection(ctx context.Context) (any, error) {
	return n.client.Ping(ctx)
}

func (n *navidromeGatewayAdapter) ScanStatus(ctx context.Context) (any, error) {
	return n.client.GetScanStatus(ctx)
}

func (n *navidromeGatewayAdapter) StartScan(ctx context.Context, actor auth.Actor) (any, error) {
	return n.client.StartScan(ctx, actor)
}

// RegisterDomainOperations wires domain handlers into operations.Registry at assembly time
// without introducing reverse imports in internal/operations or internal/agent.
func RegisterDomainOperations(reg *operations.Registry, a *App) error {
	if reg == nil || a == nil {
		return nil
	}
	pathParam := func(req operations.Request, key string) string {
		if pp, ok := req.Payload["path_params"].(map[string]any); ok {
			if v, ok := pp[key].(string); ok && strings.TrimSpace(v) != "" {
				return strings.TrimSpace(v)
			}
		}
		if v, ok := req.Payload[key].(string); ok {
			return strings.TrimSpace(v)
		}
		return ""
	}
	bodyMap := func(req operations.Request) map[string]any {
		if b, ok := req.Payload["body"].(map[string]any); ok {
			return b
		}
		return req.Payload
	}
	queryMap := func(req operations.Request) map[string]any {
		if q, ok := req.Payload["query"].(map[string]any); ok {
			return q
		}
		return req.Payload
	}

	regIf := func(op string, fn operations.Handler) error {
		if _, ok := reg.Resolve(op); !ok {
			return nil
		}
		return reg.RegisterHandler(op, fn)
	}

	_ = regIf("GET /api/health", func(_ context.Context, _ operations.Request) (any, error) {
		return map[string]any{"status": "ok", "read_only": a.Cfg.ReadOnly, "version": a.Cfg.EngineVersion}, nil
	})
	_ = regIf("GET /api/config", func(_ context.Context, _ operations.Request) (any, error) {
		snap := a.Snapshot()
		var avail []model.AvailableSourceDTO
		if a.Providers != nil {
			avail = a.Providers.AvailableSources()
		}
		return snap.PublicDTO(avail), nil
	})
	_ = regIf("GET /api/files", func(ctx context.Context, req operations.Request) (any, error) {
		qm := queryMap(req)
		q, _ := qm["query"].(string)
		folder, _ := qm["folder"].(string)
		cursor, _ := qm["cursor"].(string)
		limit := 100
		if v, ok := qm["limit"].(float64); ok && int(v) > 0 {
			limit = int(v)
		}
		page, err := a.Indexer.QueryFiles(ctx, store.LibraryFilesQuery{
			Query:  q,
			Folder: folder,
			Cursor: cursor,
			Limit:  limit,
		})
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"items":          page.Items,
			"files":          page.Items,
			"next_cursor":    page.NextCursor,
			"index_revision": page.IndexRevision,
			"indexing":       page.Indexing,
		}, nil
	})
	_ = regIf("POST /api/search", func(ctx context.Context, req operations.Request) (any, error) {
		bm := bodyMap(req)
		kw, _ := bm["keyword"].(string)
		sources := stringSliceAny(bm["sources"])
		be := &botBackendAdapter{app: a}
		tracks, partial, err := be.SearchTracks(ctx, req.Actor, kw, sources)
		if err != nil {
			return nil, err
		}
		return map[string]any{"tracks": tracks, "partial": partial}, nil
	})
	_ = regIf("POST /api/search/jobs", func(ctx context.Context, req operations.Request) (any, error) {
		bm := bodyMap(req)
		kw, _ := bm["keyword"].(string)
		sources := stringSliceAny(bm["sources"])
		refresh, _ := bm["refresh"].(bool)
		snap := a.Snapshot()
		id, err := a.Search.CreateSearch(ctx, req.Actor, snap, search.CreateRequest{
			Keyword: kw,
			Sources: sources,
			Refresh: refresh,
			Limit:   snap.Runtime.ResultsPerSource,
		})
		if err != nil {
			return nil, err
		}
		return map[string]any{"id": id}, nil
	})
	_ = regIf("DELETE /api/search/jobs/{ident}", func(ctx context.Context, req operations.Request) (any, error) {
		ident := pathParam(req, "ident")
		if err := a.Search.Cancel(ctx, req.Actor, ident); err != nil {
			return nil, err
		}
		return map[string]any{"cancelled": true}, nil
	})
	_ = regIf("GET /api/downloads", func(ctx context.Context, _ operations.Request) (any, error) {
		rows, err := a.Download.List(ctx, 200)
		if err != nil {
			return nil, err
		}
		out := make([]model.DownloadJobDTO, 0, len(rows))
		for _, row := range rows {
			dto, err := a.Download.ToDTO(ctx, row)
			if err == nil {
				out = append(out, dto)
			}
		}
		return map[string]any{"downloads": out}, nil
	})
	_ = regIf("POST /api/downloads", func(ctx context.Context, req operations.Request) (any, error) {
		if a.Cfg.ReadOnly {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止创建下载")
		}
		bm := bodyMap(req)
		tids := stringSliceAny(bm["track_ids"])
		if len(tids) == 0 {
			if one, _ := bm["track_id"].(string); one != "" {
				tids = append(tids, one)
			} else if pub, _ := bm["public_id"].(string); pub != "" {
				tids = append(tids, pub)
			}
		}
		if len(tids) == 0 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "track_ids 不能为空")
		}
		qualityID, _ := bm["quality_id"].(string)
		organize, _ := bm["organize"].(bool)
		var autoOrg *download.AutoOrganizeConfig
		if organize {
			autoOrg = &download.AutoOrganizeConfig{Enabled: true, Action: "both"}
		}
		dtos := make([]model.DownloadJobDTO, 0, len(tids))
		for idx, tid := range tids {
			ikey := req.IdempotencyKey
			if ikey != "" && len(tids) > 1 {
				ikey = ikey + ":" + strconv.Itoa(idx)
			}
			res, err := a.Download.Enqueue(ctx, req.Actor, a.Snapshot(), download.EnqueueRequest{
				PublicID:       tid,
				QualityID:      qualityID,
				IdempotencyKey: ikey,
				ForceNew:       req.ForceNew,
				IntentNonce:    req.IntentNonce,
				AutoOrganize:   autoOrg,
			})
			if err != nil {
				return nil, err
			}
			dto, err := a.Download.ToDTO(ctx, res.Download)
			if err != nil {
				return nil, err
			}
			dtos = append(dtos, dto)
		}
		return map[string]any{"downloads": dtos}, nil
	})
	_ = regIf("POST /api/downloads/{ident}/{action}", func(ctx context.Context, req operations.Request) (any, error) {
		ident := pathParam(req, "ident")
		action := pathParam(req, "action")
		be := &botBackendAdapter{app: a}
		if err := be.ControlDownload(ctx, req.Actor, ident, action); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "id": ident, "action": action}, nil
	})
	_ = regIf("GET /api/library/jobs", func(ctx context.Context, _ operations.Request) (any, error) {
		return a.Library.ListJobs(ctx, 100)
	})
	_ = regIf("POST /api/library/jobs", func(ctx context.Context, req operations.Request) (any, error) {
		if a.Cfg.ReadOnly {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止整理写入")
		}
		bm := bodyMap(req)
		raw, _ := json.Marshal(bm)
		var sreq library.SubmitRequest
		if err := json.Unmarshal(raw, &sreq); err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的整理请求", err)
		}
		return a.Library.Submit(ctx, req.Actor, sreq)
	})
	_ = regIf("GET /api/favorites", func(ctx context.Context, _ operations.Request) (any, error) {
		recs, err := a.Store.ListDurableRecords(ctx, "favorite", 5000)
		if err != nil {
			return nil, err
		}
		out := make([]model.TrackDTO, 0, len(recs))
		for _, rec := range recs {
			var dto model.TrackDTO
			if json.Unmarshal([]byte(rec.BodyJSON), &dto) == nil {
				out = append(out, dto)
			}
		}
		return out, nil
	})
	_ = regIf("GET /api/playlists", func(ctx context.Context, req operations.Request) (any, error) {
		cols, err := a.Store.ListCollections(ctx, req.Actor.Key(), "playlist")
		if err != nil {
			return nil, err
		}
		return map[string]any{"playlists": cols}, nil
	})
	_ = regIf("POST /api/playlists", func(ctx context.Context, req operations.Request) (any, error) {
		if a.Cfg.ReadOnly {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止创建歌单")
		}
		bm := bodyMap(req)
		name, _ := bm["name"].(string)
		col, err := a.Store.CreateCollection(ctx, store.CreateCollectionParams{
			Name:     strings.TrimSpace(name),
			Kind:     "playlist",
			OwnerKey: req.Actor.Key(),
		})
		if err != nil {
			return nil, err
		}
		tids := stringSliceAny(bm["track_ids"])
		if len(tids) > 0 {
			_, _ = a.Store.AddCollectionItems(ctx, col.ID, tids, 0)
		}
		return col, nil
	})
	_ = regIf("GET /api/playlists/{ident}", func(ctx context.Context, req operations.Request) (any, error) {
		ident := pathParam(req, "ident")
		col, items, ok, err := a.Store.GetCollection(ctx, ident)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "歌单不存在")
		}
		return map[string]any{"playlist": col, "tracks": items}, nil
	})
	_ = regIf("POST /api/playlists/{ident}/tracks", func(ctx context.Context, req operations.Request) (any, error) {
		if a.Cfg.ReadOnly {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止修改歌单")
		}
		ident := pathParam(req, "ident")
		bm := bodyMap(req)
		tids := stringSliceAny(bm["track_ids"])
		rev, err := a.Store.AddCollectionItems(ctx, ident, tids, 0)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "playlist_id": ident, "revision": rev}, nil
	})
	_ = regIf("GET /api/navidrome/capabilities", func(ctx context.Context, _ operations.Request) (any, error) {
		gw := &navidromeGatewayAdapter{client: a.Navidrome, app: a}
		return gw.Capabilities(ctx)
	})
	_ = regIf("POST /api/navidrome/scan", func(ctx context.Context, req operations.Request) (any, error) {
		if a.Cfg.ReadOnly {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止触发外部扫描")
		}
		return a.Navidrome.StartScan(ctx, req.Actor)
	})
	_ = regIf("GET /api/bots/config", func(ctx context.Context, _ operations.Request) (any, error) {
		return a.Bot.PublicConfig(), nil
	})
	if a.HTTP != nil {
		for _, spec := range reg.ListSpecs() {
			if reg.HasHandler(spec.ID) {
				continue
			}
			curSpec := spec
			if err := reg.RegisterHandler(curSpec.ID, func(ctx context.Context, req operations.Request) (any, error) {
				if a.Cfg.ReadOnly && curSpec.Write {
					return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "只读模式禁止执行写操作: "+curSpec.ID)
				}
				return a.HTTP.DispatchOperationInProcess(ctx, req.Actor, curSpec.Method, curSpec.Path, req.Payload)
			}); err != nil {
				return err
			}
		}
	}
	return nil
}

func stringSliceAny(v any) []string {
	raw, ok := v.([]any)
	if !ok {
		if ss, ok2 := v.([]string); ok2 {
			return ss
		}
		return nil
	}
	out := make([]string, 0, len(raw))
	for _, item := range raw {
		if s, ok := item.(string); ok && strings.TrimSpace(s) != "" {
			out = append(out, strings.TrimSpace(s))
		}
	}
	return out
}

func writeRawJSON(w http.ResponseWriter, status int, payload any) error {
	data, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.WriteHeader(status)
	_, _ = w.Write(data)
	return nil
}
