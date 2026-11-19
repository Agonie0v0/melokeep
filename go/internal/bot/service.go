package bot

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const (
	nsBotSetting = "bot_setting"
	nsBotSearch  = "bot_search"
	nsBotSession = "bot_session"
	nsBotTask    = "bot_task"
	nsBotNotice  = "bot_notice"
	inboxTTL     = 7 * 24 * time.Hour
	wecomFileMax = 20 * 1024 * 1024
	tgAudioLimit = 50 * 1024 * 1024
	botImageMax  = 5 * 1024 * 1024
)

var (
	reHex8to32    = regexp.MustCompile(`^[a-f0-9-]{8,36}$`)
	reHex8        = regexp.MustCompile(`^[a-f0-9]{8}$`)
	rePageNum     = regexp.MustCompile(`^第\s*([0-9]+)\s*页$`)
	reCNArg       = regexp.MustCompile(`^(下载|音源|翻页|结果)\s*([0-9]+(?:\s+[0-9]+)?)$`)
	reCNTask      = regexp.MustCompile(`^(链接|获取链接|文件|发送文件|取消|继续)(?:\s*([a-f0-9-]{8,36}))?$`)
	reDigits      = regexp.MustCompile(`^[0-9]+$`)
	reBotTokenURL = regexp.MustCompile(`/bot[^/\s"?#]+/`)
	reURLInError  = regexp.MustCompile(`(?i)(?:https?|socks5h?)://[^\s"'<>]+`)
)

var commandAliases = map[string]string{
	"搜索": "search", "下载": "download", "状态": "status", "取消": "cancel",
	"继续": "resume", "链接": "link", "获取链接": "link", "帮助": "help", "菜单": "help", "menu": "help", "start": "help",
	"下一页": "next", "上一页": "prev", "next": "next", "prev": "prev", "翻页": "results",
	"结果": "results", "音源": "source", "曲库": "library", "文件": "file", "发送文件": "file",
}

type Service struct {
	store    *store.Store
	pool     *transport.Pool
	channels *channelTransport
	localAPI *LocalBotAPI
	backend  Backend
	nowFn    func() time.Time
	readOnly bool
	onLog    func(level, msg, cat, taskID string)

	mu           sync.RWMutex
	cfg          Config
	rev          string
	wecom        *WeComCrypt
	links        *DownloadLinks
	pendingCh    chan InboundMessage
	tgWakeCh     chan struct{}
	seenRate     map[string]time.Time
	searchMu     sync.Mutex
	searchResMu  sync.Mutex
	searchCancel map[string]context.CancelFunc
	bgCtxMu      sync.RWMutex
	bgCtx        context.Context
	cardSeq      atomic.Int64
	lastMenuRev  string

	projectProxy func() string
}

type ServiceOptions struct {
	Store        *store.Store
	Pool         *transport.Pool
	LocalAPI     *LocalBotAPI
	Backend      Backend
	Initial      Config
	ReadOnly     bool
	OnLog        func(level, msg, cat, taskID string)
	ProjectProxy func() string
	Now          func() time.Time
}

func NewService(ctx context.Context, opts ServiceOptions) (*Service, error) {
	if opts.Store == nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "bot service requires store")
	}
	nowFn := opts.Now
	if nowFn == nil {
		nowFn = time.Now
	}
	if opts.Pool == nil {
		opts.Pool = transport.NewPool()
	}
	local := opts.LocalAPI
	if local == nil {
		local = NewLocalBotAPI(LocalBotAPISpec{})
	}
	s := &Service{
		store:        opts.Store,
		pool:         opts.Pool,
		channels:     newChannelTransport(opts.Pool),
		localAPI:     local,
		backend:      opts.Backend,
		nowFn:        nowFn,
		readOnly:     opts.ReadOnly,
		onLog:        opts.OnLog,
		pendingCh:    make(chan InboundMessage, 100),
		tgWakeCh:     make(chan struct{}, 1),
		seenRate:     make(map[string]time.Time),
		projectProxy: opts.ProjectProxy,
	}
	if err := s.applyConfig(ctx, opts.Initial); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Service) applyConfig(ctx context.Context, cfg Config) error {
	validated, err := ValidateConfig(cfg)
	if err != nil {
		return err
	}
	var wc *WeComCrypt
	if validated.WeComEnabled {
		wc, err = NewWeComCrypt(validated.WeComToken, validated.WeComAESKey, validated.WeComCorpID)
		if err != nil {
			return err
		}
	}
	var links *DownloadLinks
	if validated.PublicURL != "" && s.backend != nil {
		links, err = NewDownloadLinks(ctx, s.store, validated.PublicURL, validated.LinkTTLSeconds, s.backend.ResolveDownloadFile)
		if err != nil {
			return err
		}
	}
	raw, _ := json.Marshal(validated)
	sum := sha256.Sum256(raw)
	rev := hex.EncodeToString(sum[:16])

	s.mu.Lock()
	oldRev := s.rev
	s.cfg = validated
	s.rev = rev
	s.wecom = wc
	s.links = links
	s.mu.Unlock()

	if oldRev != "" && oldRev != rev && s.pool != nil {
		s.pool.InvalidateRevision(oldRev)
	}
	return nil
}

func (s *Service) Config() Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg
}

func (s *Service) Revision() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.rev
}

func (s *Service) Links() *DownloadLinks {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.links
}

func (s *Service) LocalAPI() *LocalBotAPI {
	return s.localAPI
}

func (s *Service) PublicConfig() map[string]any {
	return s.Config().PublicConfig(s.localAPI.Status())
}

func (s *Service) TestConnection(ctx context.Context, channel string, incoming *Config, clearSecrets []string) (map[string]any, error) {
	channel = strings.ToLower(strings.TrimSpace(channel))
	switch channel {
	case ChannelTelegram, ChannelWeCom, ChannelFeishu, ChannelDingTalk, ChannelDiscord:
	default:
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的通知渠道")
	}
	cfg := s.Config()
	if incoming != nil {
		cfg = MergeConfig(cfg, *incoming, clearSecrets)
	}
	testRev := "test-" + strconv.FormatInt(s.nowFn().UnixNano(), 10)
	proxy := ""
	if s.projectProxy != nil {
		proxy = s.projectProxy()
	}
	switch channel {
	case ChannelTelegram:
		if strings.TrimSpace(cfg.TGToken) == "" {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写 Telegram Bot Token 后再测试")
		}
		if cfg.TGLocalEnabled {
			if _, _, err := ValidateLocalCredentials(cfg.TGLocalAPIID, cfg.TGLocalAPIHash); err != nil {
				return nil, err
			}
			if s.localAPI == nil || s.localAPI.ResolveBinary() == "" {
				return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "未找到官方 telegram-bot-api 程序，请确认已安装并加入 PATH")
			}
			if !s.localAPI.Matches(cfg.TGLocalAPIID, cfg.TGLocalAPIHash) {
				saved := s.Config()
				if saved.TGEnabled && saved.TGLocalEnabled && saved.TGLocalAPIID == cfg.TGLocalAPIID && saved.TGLocalAPIHash == cfg.TGLocalAPIHash {
					if st := s.localAPI.Status(); st.Detail != "" {
						return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, st.Detail)
					}
					return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "内置 Telegram Bot API 服务未运行，请重新保存通知配置或重启应用")
				}
				return map[string]any{
					"ok":      true,
					"channel": channel,
					"detail":  "内置服务配置格式检查通过。请勾选「启用 Telegram」并点击「保存通知配置」启动内置服务后，再次测试可验证 Telegram 连接。",
				}, nil
			}
		}
		if _, err := s.channels.callTelegram(ctx, cfg, testRev, s.localAPI, proxy, "getMe", nil, "", "", ""); err != nil {
			return nil, model.NewError(model.ErrUnavailable, http.StatusBadGateway, "Telegram 连接测试失败: "+s.redactErrorWithConfig(err, cfg))
		}
		return map[string]any{"ok": true, "channel": channel, "detail": "连接测试成功；机器人凭据可用，未发送消息。"}, nil
	case ChannelWeCom:
		if strings.TrimSpace(cfg.WeComCorpID) == "" || strings.TrimSpace(cfg.WeComSecret) == "" {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写企业微信 CorpID 与 Secret 后再测试")
		}
		if _, err := s.channels.wecomToken(ctx, cfg, testRev); err != nil {
			return nil, model.NewError(model.ErrUnavailable, http.StatusBadGateway, "企业微信连接测试失败: "+s.redactErrorWithConfig(err, cfg))
		}
		return map[string]any{"ok": true, "channel": channel, "detail": "连接测试成功；凭据可用。企业微信回调和白名单成员权限请在平台单独验证。"}, nil
	default:
		var webhook string
		switch channel {
		case ChannelFeishu:
			webhook = cfg.FeishuWebhook
		case ChannelDingTalk:
			webhook = cfg.DingTalkWebhook
		case ChannelDiscord:
			webhook = cfg.DiscordWebhook
		}
		if strings.TrimSpace(webhook) == "" {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写对应渠道的 Webhook 地址后测试")
		}
		if err := validateWebhookURL(channel, true, webhook); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "channel": channel, "detail": "Webhook 格式与配置有效，未发送消息；实际权限会在首次通知时验证。"}, nil
	}
}

func (s *Service) RegisterDiscordCommands(ctx context.Context) (map[string]any, error) {
	cfg := s.Config()
	if !cfg.DiscordBotEnabled || strings.TrimSpace(cfg.DiscordApplicationID) == "" || strings.TrimSpace(cfg.DiscordBotToken) == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Discord 机器人尚未配置完整")
	}
	endpoint := s.channels.discordOrigin(cfg) + "/api/v10/applications/" + url.PathEscape(cfg.DiscordApplicationID) + "/commands"
	hdr := http.Header{"Authorization": []string{"Bot " + cfg.DiscordBotToken}}
	payload := map[string]any{
		"name":        "melokeep",
		"description": "搜索音乐或使用 MeloKeep AI 助手，结果通过机器人私信发送",
		"type":        1,
		"options": []map[string]any{
			{"name": "text", "description": "歌名、/search、/status 或 /ai 你的需求", "type": 3, "max_length": 8000},
			{"name": "image", "description": "歌单图片（需启用 AI，最大 5 MB）", "type": 11},
		},
	}
	proxy := ""
	if s.projectProxy != nil {
		proxy = s.projectProxy()
	}
	status, raw, err := s.channels.doJSON(ctx, cfg, s.Revision(), http.MethodPost, endpoint, hdr, payload, proxy, true)
	if err != nil {
		return nil, model.NewError(model.ErrUnavailable, http.StatusBadGateway, "Discord 命令注册失败: "+s.redactErrorWithConfig(err, cfg))
	}
	if status < 200 || status >= 300 {
		return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("Discord 命令注册失败（HTTP %d）: %s", status, s.RedactError(fmt.Errorf("%s", strings.TrimSpace(string(raw))))))
	}
	return map[string]any{
		"ok":             true,
		"application_id": cfg.DiscordApplicationID,
		"detail":         "已注册 /melokeep 命令；全局命令可能需要稍后才显示。",
	}, nil
}

func (s *Service) UpdateConfig(ctx context.Context, actor auth.Actor, incoming Config, clearSecrets []string) (map[string]any, error) {
	if !actor.Authenticated || !actor.Has(auth.PermAdmin) {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "需要管理员权限修改通知配置")
	}
	prevCfg := s.Config()
	prevRev := s.Revision()
	prevOrigin := s.channels.telegramOrigin(prevCfg, s.localAPI)
	merged := MergeConfig(prevCfg, incoming, clearSecrets)
	if err := s.applyConfig(ctx, merged); err != nil {
		return nil, err
	}
	if merged.TGToken != prevCfg.TGToken {
		_ = s.saveTGOffset(ctx, 0)
	}
	raw, _ := json.Marshal(merged)
	if _, err := s.store.PutDurableRecord(ctx, nsBotSetting, "config", string(raw), 0); err != nil {
		return nil, err
	}
	if !s.readOnly {
		if err := s.SyncLocalAPI(ctx); err != nil && s.onLog != nil {
			s.onLog("warning", "内置 Telegram Bot API 启动失败: "+s.RedactError(err), "bot", "")
		}
		newOrigin := s.channels.telegramOrigin(merged, s.localAPI)
		if prevCfg.TGEnabled && prevCfg.TGToken != "" && merged.TGToken == prevCfg.TGToken && prevOrigin != newOrigin {
			proxy := ""
			if s.projectProxy != nil {
				proxy = s.projectProxy()
			}
			// Use a non-local call with explicit origin if switching off local API; SyncLocalAPI may have stopped local.
			if prevOrigin != "" && (!prevCfg.TGLocalEnabled || (s.localAPI != nil && s.localAPI.Status().Running)) {
				if _, err := s.channels.callTelegram(ctx, prevCfg, prevRev, s.localAPI, proxy, "logOut", map[string]any{}, "", "", ""); err != nil && s.onLog != nil {
					s.onLog("warning", "Telegram 旧 API 退出失败，新配置仍已保存；如新 API 提示迁移冲突，可稍后重试: "+s.RedactError(err), "bot", "")
				}
			}
		}
		s.wakeTelegramPoller()
	}
	return s.PublicConfig(), nil
}

func (s *Service) Allowed(channel, user string) bool {
	cfg := s.Config()
	user = strings.TrimSpace(user)
	if user == "" {
		return false
	}
	switch channel {
	case ChannelTelegram:
		if !cfg.TGEnabled || cfg.TGToken == "" {
			return false
		}
		return inCSV(cfg.TGAllowedUsers, user)
	case ChannelWeCom:
		s.mu.RLock()
		wc := s.wecom
		s.mu.RUnlock()
		if !cfg.WeComEnabled || wc == nil {
			return false
		}
		list := splitCSV(cfg.WeComAllowedUsers)
		return len(list) == 0 || inCSV(cfg.WeComAllowedUsers, user)
	case ChannelFeishu:
		if user == "downloads" {
			return cfg.FeishuEnabled
		}
		return cfg.FeishuBotEnabled && inCSV(cfg.FeishuAllowedUsers, user)
	case ChannelDingTalk:
		if user == "downloads" {
			return cfg.DingTalkEnabled
		}
		return cfg.DingTalkBotEnabled && inCSV(cfg.DingTalkAllowedUsers, user)
	case ChannelDiscord:
		if user == "downloads" {
			return cfg.DiscordEnabled
		}
		return cfg.DiscordBotEnabled && inCSV(cfg.DiscordAllowedUsers, user)
	default:
		return false
	}
}

func inCSV(csv, target string) bool {
	for _, x := range splitCSV(csv) {
		if x == target {
			return true
		}
	}
	return false
}

func (s *Service) ActorFor(channel, user string) auth.Actor {
	perms := []auth.Permission{auth.PermRead, auth.PermLocalWrite}
	if s.Config().BotAIAllowActions {
		perms = append(perms, auth.PermExternalWrite)
	}
	return auth.ChannelActor(channel, user, perms...)
}

// RecoverOnStartup transitions any 'sending' outbox entries left from a crash into 'delivery_unknown'
// so external notifications are never blindly re-sent after an unknown failure boundary.
func (s *Service) RecoverOnStartup(ctx context.Context) (int64, error) {
	return s.store.RecoverSendingOutbox(ctx)
}

// HandleWeComVerify handles GET /bot/wecom URL verification.
func (s *Service) HandleWeComVerify(msgSignature, timestamp, nonce, echostr string) ([]byte, error) {
	s.mu.RLock()
	cfg, wc := s.cfg, s.wecom
	s.mu.RUnlock()
	if !cfg.WeComEnabled || wc == nil {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "企业微信未启用")
	}
	if msgSignature == "" && timestamp == "" && nonce == "" && echostr == "" {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败（此地址仅限企业微信服务端携带签名参数自动访问，请勿直接在浏览器打开）")
	}
	if err := checkFreshTimestamp(timestamp, false, s.nowFn()); err != nil {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
	}
	plain, err := wc.Decrypt(echostr, msgSignature, timestamp, nonce)
	if err != nil {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
	}
	return plain, nil
}

// HandleWeComCallback verifies, deduplicates via store.RecordInbox, and enqueues WeCom inbound messages.
func (s *Service) HandleWeComCallback(ctx context.Context, rawBody []byte, msgSignature, timestamp, nonce string) (bool, error) {
	s.mu.RLock()
	cfg, wc := s.cfg, s.wecom
	s.mu.RUnlock()
	if !cfg.WeComEnabled || wc == nil {
		return false, model.NewError(model.ErrNotFound, http.StatusNotFound, "企业微信未启用")
	}
	if len(rawBody) > MaxCallbackBodyBytes {
		return false, model.NewError(model.ErrInvalidInput, http.StatusRequestEntityTooLarge, "消息过大")
	}
	if err := checkFreshTimestamp(timestamp, false, s.nowFn()); err != nil {
		return false, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
	}
	outer, err := ParseXMLFields(rawBody)
	if err != nil {
		return false, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
	}
	plain, err := wc.Decrypt(outer["Encrypt"], msgSignature, timestamp, nonce)
	if err != nil {
		return false, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
	}
	fields, err := ParseXMLFields(plain)
	if err != nil {
		return false, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
	}
	if fields["ToUserName"] != cfg.WeComCorpID || fields["AgentID"] != cfg.WeComAgentID {
		return false, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
	}
	msgType := fields["MsgType"]
	if msgType != "text" && msgType != "image" {
		return false, nil
	}
	user := fields["FromUserName"]
	msgID := fields["MsgId"]
	if user == "" || msgID == "" {
		return false, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
	}
	var img *ImageRef
	if msgType == "image" {
		if fields["MediaId"] == "" {
			return false, model.NewError(model.ErrForbidden, http.StatusForbidden, "回调验证失败")
		}
		img = &ImageRef{ID: fields["MediaId"]}
	}
	return s.accept(ctx, ChannelWeCom, user, msgID, fields["Content"], nil, img)
}

// HandleFeishuCallback verifies Feishu signature/decryption, handles url_verification, and records inbox.
func (s *Service) HandleFeishuCallback(ctx context.Context, rawBody []byte, timestamp, nonce, signature string) (map[string]any, error) {
	cfg := s.Config()
	if !cfg.FeishuBotEnabled {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "飞书对话机器人未启用")
	}
	if len(rawBody) > MaxCallbackBodyBytes {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusRequestEntityTooLarge, "消息过大")
	}
	var envelope map[string]any
	if err := json.Unmarshal(rawBody, &envelope); err != nil {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "飞书回调验证失败")
	}
	if _, hasEnc := envelope["encrypt"].(string); hasEnc && strings.TrimSpace(cfg.FeishuEncryptKey) != "" {
		if err := VerifyFeishuSignature(timestamp, nonce, cfg.FeishuEncryptKey, signature, rawBody, s.nowFn()); err != nil {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "飞书回调验证失败")
		}
	}
	if enc, ok := envelope["encrypt"].(string); ok && enc != "" {
		plain, err := DecryptFeishuPayload(cfg.FeishuEncryptKey, enc)
		if err != nil {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "飞书回调验证失败")
		}
		if err := json.Unmarshal(plain, &envelope); err != nil {
			return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "飞书回调验证失败")
		}
	}
	header, _ := envelope["header"].(map[string]any)
	token, _ := envelope["token"].(string)
	if token == "" && header != nil {
		token, _ = header["token"].(string)
	}
	if !hmac.Equal([]byte(token), []byte(cfg.FeishuVerificationToken)) {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "飞书回调验证失败")
	}
	if typ, _ := envelope["type"].(string); typ == "url_verification" {
		if strings.TrimSpace(cfg.FeishuEncryptKey) != "" {
			if err := VerifyFeishuSignature(timestamp, nonce, cfg.FeishuEncryptKey, signature, rawBody, s.nowFn()); err != nil {
				return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "飞书回调验证失败")
			}
		}
		ch, _ := envelope["challenge"].(string)
		return map[string]any{"challenge": ch}, nil
	}
	if err := VerifyFeishuSignature(timestamp, nonce, cfg.FeishuEncryptKey, signature, rawBody, s.nowFn()); err != nil {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "飞书回调验证失败")
	}
	if appID, _ := header["app_id"].(string); appID != cfg.FeishuAppID {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "飞书回调验证失败")
	}
	if evType, _ := header["event_type"].(string); evType != "im.message.receive_v1" {
		return map[string]any{"code": 0}, nil
	}
	ev, _ := envelope["event"].(map[string]any)
	sender, _ := ev["sender"].(map[string]any)
	msg, _ := ev["message"].(map[string]any)
	if sender["sender_type"] != "user" || msg["chat_type"] != "p2p" {
		return map[string]any{"code": 0}, nil
	}
	senderID, _ := sender["sender_id"].(map[string]any)
	user, _ := senderID["open_id"].(string)
	msgID, _ := msg["message_id"].(string)
	msgType, _ := msg["message_type"].(string)
	contentRaw, _ := msg["content"].(string)
	var content map[string]any
	_ = json.Unmarshal([]byte(contentRaw), &content)
	var img *ImageRef
	var text string
	if msgType == "image" {
		imgKey, _ := content["image_key"].(string)
		img = &ImageRef{ID: imgKey, MessageID: msgID}
	} else if msgType == "text" {
		text, _ = content["text"].(string)
	} else {
		return map[string]any{"code": 0}, nil
	}
	if _, err := s.accept(ctx, ChannelFeishu, user, msgID, text, nil, img); err != nil {
		return nil, err
	}
	return map[string]any{"code": 0}, nil
}

// HandleDingTalkCallback verifies DingTalk HMAC-SHA256 signature and records inbox.
func (s *Service) HandleDingTalkCallback(ctx context.Context, rawBody []byte, timestamp, sign string) (map[string]any, error) {
	cfg := s.Config()
	if !cfg.DingTalkBotEnabled {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "钉钉对话机器人未启用")
	}
	if len(rawBody) > MaxCallbackBodyBytes {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusRequestEntityTooLarge, "消息过大")
	}
	if err := VerifyDingTalkSignature(cfg.DingTalkClientSecret, timestamp, sign, s.nowFn()); err != nil {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "钉钉回调验证失败")
	}
	var data struct {
		RobotCode        string `json:"robotCode"`
		ConversationType string `json:"conversationType"`
		MsgType          string `json:"msgtype"`
		SenderStaffID    string `json:"senderStaffId"`
		MsgID            string `json:"msgId"`
		Text             struct {
			Content string `json:"content"`
		} `json:"text"`
		Content struct {
			DownloadCode string `json:"downloadCode"`
		} `json:"content"`
	}
	if err := json.Unmarshal(rawBody, &data); err != nil || data.RobotCode != cfg.DingTalkRobotCode {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "钉钉回调验证失败")
	}
	if data.ConversationType != "1" {
		return map[string]any{"errcode": 0, "errmsg": "ok"}, nil
	}
	var img *ImageRef
	if data.MsgType == "picture" {
		img = &ImageRef{ID: data.Content.DownloadCode}
	} else if data.MsgType != "text" {
		return map[string]any{"errcode": 0, "errmsg": "ok"}, nil
	}
	if _, err := s.accept(ctx, ChannelDingTalk, data.SenderStaffID, data.MsgID, data.Text.Content, nil, img); err != nil {
		return nil, err
	}
	return map[string]any{"errcode": 0, "errmsg": "ok"}, nil
}

// HandleDiscordCallback verifies Ed25519 signature, responds to PING, and records slash commands.
func (s *Service) HandleDiscordCallback(ctx context.Context, rawBody []byte, timestamp, sigHex string) (map[string]any, error) {
	cfg := s.Config()
	if !cfg.DiscordBotEnabled {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "Discord 对话机器人未启用")
	}
	if len(rawBody) > MaxCallbackBodyBytes {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusRequestEntityTooLarge, "消息过大")
	}
	if err := VerifyDiscordSignature(cfg.DiscordPublicKey, timestamp, sigHex, rawBody, s.nowFn()); err != nil {
		return nil, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "Discord 回调验证失败")
	}
	var data struct {
		ID            string `json:"id"`
		ApplicationID string `json:"application_id"`
		Type          int    `json:"type"`
		Member        struct {
			User struct {
				ID string `json:"id"`
			} `json:"user"`
		} `json:"member"`
		User struct {
			ID string `json:"id"`
		} `json:"user"`
		Data struct {
			Name    string `json:"name"`
			Options []struct {
				Name  string `json:"name"`
				Value any    `json:"value"`
			} `json:"options"`
			Resolved struct {
				Attachments map[string]struct {
					URL         string `json:"url"`
					Size        int64  `json:"size"`
					ContentType string `json:"content_type"`
				} `json:"attachments"`
			} `json:"resolved"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rawBody, &data); err != nil || data.ApplicationID != cfg.DiscordApplicationID {
		return nil, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "Discord 回调验证失败")
	}
	if data.Type == 1 {
		return map[string]any{"type": 1}, nil
	}
	if data.Type != 2 || data.Data.Name != "melokeep" {
		return nil, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "Discord 回调验证失败")
	}
	user := data.Member.User.ID
	if user == "" {
		user = data.User.ID
	}
	if !s.Allowed(ChannelDiscord, user) {
		return map[string]any{"type": 4, "data": map[string]any{"content": "该用户不在 MeloKeep 机器人白名单中。", "flags": 64}}, nil
	}
	var text string
	var img *ImageRef
	for _, opt := range data.Data.Options {
		switch opt.Name {
		case "text":
			if str, ok := opt.Value.(string); ok {
				text = str
			}
		case "image":
			attID := fmt.Sprint(opt.Value)
			if att, ok := data.Data.Resolved.Attachments[attID]; ok {
				if !strings.HasPrefix(att.ContentType, "image/") {
					return nil, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "Discord 回调验证失败")
				}
				img = &ImageRef{ID: att.URL, Size: att.Size}
			}
		}
	}
	if text == "" && img == nil {
		text = "/help"
	}
	if _, err := s.accept(ctx, ChannelDiscord, user, data.ID, text, nil, img); err != nil {
		return nil, err
	}
	return map[string]any{
		"type": 4,
		"data": map[string]any{
			"content":          "已收到，结果将通过 MeloKeep 机器人私信发送。请允许机器人发送私信。",
			"flags":            64,
			"allowed_mentions": map[string]any{"parse": []string{}},
		},
	}, nil
}

type TelegramPhotoSize struct {
	FileID   string `json:"file_id"`
	FileSize int64  `json:"file_size"`
}

type TelegramDocument struct {
	FileID   string `json:"file_id"`
	MimeType string `json:"mime_type"`
	FileSize int64  `json:"file_size"`
}

type TelegramMessage struct {
	MessageID int64 `json:"message_id"`
	Chat      struct {
		ID   int64  `json:"id"`
		Type string `json:"type"`
	} `json:"chat"`
	From struct {
		ID int64 `json:"id"`
	} `json:"from"`
	Text     string              `json:"text"`
	Caption  string              `json:"caption"`
	Photo    []TelegramPhotoSize `json:"photo"`
	Document *TelegramDocument   `json:"document"`
}

type TelegramCallbackQuery struct {
	ID   string `json:"id"`
	From struct {
		ID int64 `json:"id"`
	} `json:"from"`
	Data    string `json:"data"`
	Message *struct {
		MessageID int64 `json:"message_id"`
		Chat      struct {
			ID   int64  `json:"id"`
			Type string `json:"type"`
		} `json:"chat"`
	} `json:"message"`
}

type TelegramUpdate struct {
	UpdateID      int64                  `json:"update_id"`
	Message       *TelegramMessage       `json:"message"`
	CallbackQuery *TelegramCallbackQuery `json:"callback_query"`
}

func (s *Service) HandleTelegramUpdate(ctx context.Context, upd TelegramUpdate) (bool, error) {
	var user string
	var text string
	var private bool
	var replyMsgID *int64
	var img *ImageRef
	var cbID string

	if upd.CallbackQuery != nil && upd.CallbackQuery.Message != nil {
		user = strconv.FormatInt(upd.CallbackQuery.From.ID, 10)
		private = upd.CallbackQuery.Message.Chat.Type == "private" && strconv.FormatInt(upd.CallbackQuery.Message.Chat.ID, 10) == user
		text = upd.CallbackQuery.Data
		mid := upd.CallbackQuery.Message.MessageID
		replyMsgID = &mid
		cbID = upd.CallbackQuery.ID
	} else if upd.Message != nil {
		user = strconv.FormatInt(upd.Message.From.ID, 10)
		private = upd.Message.Chat.Type == "private" && strconv.FormatInt(upd.Message.Chat.ID, 10) == user
		text = upd.Message.Text
		if text == "" {
			text = upd.Message.Caption
		}
		if private && s.Allowed(ChannelTelegram, user) {
			if len(upd.Message.Photo) > 0 {
				var chosen *struct {
					FileID   string
					FileSize int64
				}
				for i := len(upd.Message.Photo) - 1; i >= 0; i-- {
					p := upd.Message.Photo[i]
					if p.FileSize <= botImageMax {
						chosen = &struct {
							FileID   string
							FileSize int64
						}{FileID: p.FileID, FileSize: p.FileSize}
						break
					}
				}
				if chosen == nil {
					p := upd.Message.Photo[len(upd.Message.Photo)-1]
					chosen = &struct {
						FileID   string
						FileSize int64
					}{FileID: p.FileID, FileSize: p.FileSize}
				}
				img = &ImageRef{ID: chosen.FileID, Size: chosen.FileSize}
			} else if d := upd.Message.Document; d != nil {
				if d.MimeType == "image/png" || d.MimeType == "image/jpeg" || d.MimeType == "image/webp" {
					img = &ImageRef{ID: d.FileID, Size: d.FileSize}
				}
			}
		}
	}
	if !private || (text == "" && img == nil) {
		return false, nil
	}
	ok, err := s.accept(ctx, ChannelTelegram, user, strconv.FormatInt(upd.UpdateID, 10), text, replyMsgID, img)
	if cbID != "" && s.Allowed(ChannelTelegram, user) && !s.readOnly {
		go func(id string) {
			cfg := s.Config()
			proxy := ""
			if s.projectProxy != nil {
				proxy = s.projectProxy()
			}
			cCtx, cancel := context.WithTimeout(s.workerCtx(), 10*time.Second)
			defer cancel()
			_, _ = s.channels.callTelegram(cCtx, cfg, s.Revision(), s.localAPI, proxy, "answerCallbackQuery", map[string]any{"callback_query_id": id}, "", "", "")
		}(cbID)
	}
	return ok, err
}

func (s *Service) accept(ctx context.Context, channel, user, platformMsgID, text string, replyMsgID *int64, img *ImageRef) (bool, error) {
	if user == "" || platformMsgID == "" {
		return false, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "消息身份无效")
	}
	if (channel == ChannelFeishu || channel == ChannelDingTalk || channel == ChannelDiscord) && user == "downloads" {
		return false, nil
	}
	if !s.Allowed(channel, user) {
		return false, nil
	}
	if len([]rune(text)) > 8000 {
		return false, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "消息不能超过 8000 字符")
	}
	if seenDup, dErr := s.store.HasInboxEvent(ctx, channel, platformMsgID); dErr != nil {
		return false, dErr
	} else if seenDup {
		return false, nil
	}
	key := channel + ":" + user
	now := s.nowFn()
	s.mu.Lock()
	last := s.seenRate[key]
	queueFull := len(s.pendingCh) >= cap(s.pendingCh)
	s.mu.Unlock()
	if !last.IsZero() && now.Sub(last) < time.Second {
		return false, model.NewError(model.ErrRateLimited, http.StatusTooManyRequests, "命令过于频繁，请稍后重试")
	}
	if queueFull {
		return false, model.NewError(model.ErrRateLimited, http.StatusTooManyRequests, "命令队列已满，请稍后重试")
	}

	dup, err := s.store.RecordInbox(ctx, model.InboxEvent{
		Channel:           channel,
		PlatformMessageID: platformMsgID,
		SenderID:          user,
		ReceivedAt:        s.nowFn().UTC().UnixMilli(),
	}, inboxTTL)
	if err != nil {
		return false, err
	}
	if dup {
		return false, nil // Replay deduplication: already seen, ignore cleanly.
	}

	s.mu.Lock()
	s.seenRate[key] = now
	s.mu.Unlock()

	sum := sha256.Sum256([]byte(channel + ":" + user + ":" + platformMsgID))
	msg := InboundMessage{
		ID:             hex.EncodeToString(sum[:]),
		Channel:        channel,
		User:           user,
		PlatformMsgID:  platformMsgID,
		Text:           text,
		ReplyMessageID: replyMsgID,
		Image:          img,
		CreatedAt:      now.Unix(),
	}
	select {
	case s.pendingCh <- msg:
		return true, nil
	default:
		return false, model.NewError(model.ErrRateLimited, http.StatusTooManyRequests, "命令队列已满，请稍后重试")
	}
}

// DrainPending executes all currently queued inbound commands synchronously (useful for tests/workers).
func (s *Service) DrainPending(ctx context.Context) error {
	for {
		select {
		case msg := <-s.pendingCh:
			if err := s.ExecuteInbound(ctx, msg); err != nil {
				return err
			}
		default:
			return nil
		}
	}
}

type botSourceState struct {
	ID         string           `json:"id"`
	Label      string           `json:"label"`
	EngineName string           `json:"engine_name,omitempty"`
	State      string           `json:"state"`
	Tracks     []model.TrackDTO `json:"tracks"`
}

type botFlatTrack struct {
	Track       model.TrackDTO `json:"track"`
	SourceIndex int            `json:"source_index"`
}

type botSearchResult struct {
	ID               string           `json:"id"`
	Keyword          string           `json:"keyword"`
	Expires          int64            `json:"expires"`
	Tracks           []model.TrackDTO `json:"tracks,omitempty"`
	Partial          bool             `json:"partial,omitempty"`
	Selected         int              `json:"selected"`
	Page             int              `json:"page"`
	View             string           `json:"view,omitempty"`
	ResultsPerSource int              `json:"results_per_source,omitempty"`
	SourceStates     []botSourceState `json:"source_states,omitempty"`
	FlatTracks       []botFlatTrack   `json:"flat_tracks,omitempty"`
	CardMsg          *int64           `json:"message_id,omitempty"`
}

type botSessionState struct {
	ID       string `json:"id"`
	SearchID string `json:"search_id"`
	Page     int    `json:"page"`
	Expires  int64  `json:"expires"`
}

type botTaskOwner struct {
	ID       string `json:"id"`
	Owner    string `json:"owner"`
	Channel  string `json:"channel"`
	User     string `json:"user"`
	Notified string `json:"notified,omitempty"`
}

func (s *Service) ExecuteInbound(ctx context.Context, msg InboundMessage) error {
	if !s.Allowed(msg.Channel, msg.User) {
		return nil
	}
	if err := s.handleCommand(ctx, msg); err != nil {
		detail := s.RedactError(err)
		if de, ok := model.AsDomainError(err); ok {
			detail = de.Detail
		}
		return s.enqueueReply(ctx, msg.Channel, msg.User, "cmd_err:"+msg.ID, OutboxPayload{Text: detail})
	}
	return nil
}

func (s *Service) handleCommand(ctx context.Context, msg InboundMessage) error {
	text := strings.TrimSpace(msg.Text)
	fields := strings.Fields(text)
	var cmd, arg string
	if len(fields) > 0 {
		cmd = fields[0]
		if idx := strings.Index(text, cmd); idx >= 0 {
			arg = strings.TrimSpace(text[idx+len(cmd):])
		}
	}
	if strings.HasPrefix(text, "搜索") && text != "搜索" {
		cmd, arg = "搜索", strings.TrimSpace(strings.TrimPrefix(text, "搜索"))
	} else if m := reCNArg.FindStringSubmatch(text); m != nil {
		cmd, arg = m[1], m[2]
	} else if m := reCNTask.FindStringSubmatch(text); m != nil {
		cmd, arg = m[1], m[2]
	}
	cmd = strings.TrimPrefix(cmd, "/")
	if at := strings.IndexByte(cmd, '@'); at >= 0 {
		cmd = cmd[:at]
	}
	cmd = strings.ToLower(cmd)
	if mapped, ok := commandAliases[cmd]; ok {
		cmd = mapped
	}
	if m := rePageNum.FindStringSubmatch(text); m != nil {
		cmd, arg = "results", m[1]
	}
	known := map[string]bool{
		"help": true, "search": true, "download": true, "status": true,
		"link": true, "file": true, "cancel": true, "resume": true,
		"results": true, "next": true, "prev": true, "library": true, "source": true,
	}
	if cmd == "ai" || strings.HasPrefix(cmd, "ai_") {
		return s.runAICommand(ctx, msg, cmd, arg)
	}
	if msg.Image != nil || (text != "" && !strings.HasPrefix(text, "/") && !known[cmd]) {
		if msg.Image != nil || s.aiEnabledForChannel(msg.Channel) {
			return s.runAICommand(ctx, msg, "ai", text)
		}
		cmd, arg = "search", text
	}

	owner := msg.Channel + ":" + msg.User
	actor := s.ActorFor(msg.Channel, msg.User)
	switch cmd {
	case "help":
		return s.enqueueReply(ctx, msg.Channel, msg.User, "help:"+msg.ID, OutboxPayload{
			Text:    helpMenuText,
			Buttons: s.menuButtons(),
		})
	case "library":
		if s.backend == nil {
			return model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "曲库服务不可用")
		}
		page, err := s.backend.QueryLibraryFiles(ctx, store.LibraryFilesQuery{Limit: 10, Query: arg})
		if err != nil {
			return err
		}
		lines := []string{fmt.Sprintf("曲库文件（索引版本 %d）", page.IndexRevision)}
		for i, item := range page.Items {
			lines = append(lines, fmt.Sprintf("%d. %s", i+1, item.Name))
		}
		if len(page.Items) == 0 {
			lines = append(lines, "暂无匹配曲库文件。")
		}
		return s.enqueueReply(ctx, msg.Channel, msg.User, "lib:"+msg.ID, OutboxPayload{Text: strings.Join(lines, "\n")})
	case "search":
		kw := strings.TrimSpace(arg)
		if kw == "" {
			return s.enqueueReply(ctx, msg.Channel, msg.User, "search_hint:"+msg.ID, OutboxPayload{
				Text:    "搜索音乐\n\n请直接发送歌名或歌手，例如：\nMorning Breeze\n\n也可使用：/search Morning Breeze",
				Buttons: s.menuButtons(),
			})
		}
		if len([]rune(kw)) > 200 {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请输入 1–200 字的搜索关键词")
		}
		if s.backend == nil {
			return model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "搜索服务不可用")
		}
		if catalog := s.backend.AvailableSources(); len(catalog) > 0 {
			return s.startCatalogSearch(ctx, actor, msg, kw, catalog)
		}
		sources := splitCSV(s.Config().BotSources)
		tracks, partial, err := s.backend.SearchTracks(ctx, actor, kw, sources)
		if err != nil {
			return err
		}
		tracks = rankTracks(tracks, kw)
		if len(tracks) > 20 {
			tracks = tracks[:20]
		}
		if len(tracks) == 0 {
			return s.enqueueReply(ctx, msg.Channel, msg.User, "search_empty:"+msg.ID, OutboxPayload{Text: "没有可下载结果，请换关键词。"})
		}
		searchID := msg.ID[:8]
		res := botSearchResult{
			ID:      owner + ":" + searchID,
			Keyword: kw,
			Expires: s.nowFn().Unix() + 1800,
			Tracks:  tracks,
			Partial: partial,
			Page:    1,
		}
		rawRes, _ := json.Marshal(res)
		if _, err := s.store.PutDurableRecord(ctx, nsBotSearch, res.ID, string(rawRes), 0); err != nil {
			return err
		}
		return s.sendSearchPage(ctx, msg, searchID, res, 1)
	case "source":
		args := strings.Fields(arg)
		var searchID string
		var idx int
		var res botSearchResult
		if len(args) == 1 && reDigits.MatchString(args[0]) {
			sess, loaded, err := s.currentSearch(ctx, owner)
			if err != nil {
				return err
			}
			searchID = sess.SearchID
			idx, _ = strconv.Atoi(args[0])
			idx--
			res = loaded
		} else if len(args) == 2 && reHex8.MatchString(args[0]) && reDigits.MatchString(args[1]) {
			searchID = args[0]
			idx, _ = strconv.Atoi(args[1])
			loaded, ok, err := s.loadSearchResult(ctx, owner+":"+searchID)
			if err != nil {
				return err
			}
			if !ok {
				return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索结果已过期，请重新搜索")
			}
			res = loaded
		} else {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "发送 音源 序号，例如：音源 2")
		}
		if res.Expires <= s.nowFn().Unix() || idx < 0 || idx >= len(res.SourceStates) {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "音源序号无效或搜索已过期")
		}
		res.Selected = idx
		res.View = "source"
		return s.sendSourcePage(ctx, msg, searchID, res, 1, &idx)
	case "results", "next", "prev":
		args := strings.Fields(arg)
		var searchID string
		var page int
		var res botSearchResult
		if cmd == "next" || cmd == "prev" || (len(args) == 1 && reDigits.MatchString(args[0])) {
			sess, loaded, err := s.currentSearch(ctx, owner)
			if err != nil {
				return err
			}
			searchID = sess.SearchID
			res = loaded
			if cmd == "next" {
				page = sess.Page + 1
			} else if cmd == "prev" {
				page = sess.Page - 1
			} else {
				page, _ = strconv.Atoi(args[0])
			}
			pages := s.countSearchPages(msg.Channel, res)
			if page < 1 {
				return s.enqueueReply(ctx, msg.Channel, msg.User, "page_first:"+msg.ID, OutboxPayload{Text: "已经是第一页。"})
			}
			if page > pages {
				return s.enqueueReply(ctx, msg.Channel, msg.User, "page_last:"+msg.ID, OutboxPayload{Text: "已经是最后一页。"})
			}
		} else if len(args) >= 2 && reHex8.MatchString(args[0]) && reDigits.MatchString(args[1]) {
			searchID = args[0]
			page, _ = strconv.Atoi(args[1])
			loaded, ok, err := s.loadSearchResult(ctx, owner+":"+searchID)
			if err != nil {
				return err
			}
			if !ok || loaded.Expires <= s.nowFn().Unix() {
				return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索结果已过期，请重新搜索")
			}
			res = loaded
			if len(args) >= 3 {
				srcIdx, err := strconv.Atoi(args[2])
				if err != nil || srcIdx < 0 || srcIdx >= len(res.SourceStates) {
					return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "音源序号无效")
				}
				res.Selected = srcIdx
				res.View = "source"
			}
		} else {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "分页指令格式无效")
		}
		if len(res.SourceStates) > 0 {
			if msg.Channel != ChannelTelegram && res.View == "all" {
				return s.sendTextSearchSummary(ctx, msg, searchID, res, page)
			}
			return s.sendSourcePage(ctx, msg, searchID, res, page, nil)
		}
		return s.sendSearchPage(ctx, msg, searchID, res, page)
	case "download":
		args := strings.Fields(arg)
		var searchID string
		var idx int
		var srcIdx *int
		if len(args) == 1 && reDigits.MatchString(args[0]) {
			sess, _, err := s.currentSearch(ctx, owner)
			if err != nil {
				return err
			}
			searchID = sess.SearchID
			idx, _ = strconv.Atoi(args[0])
		} else if len(args) == 2 && reDigits.MatchString(args[0]) && len(args[0]) < 8 && reDigits.MatchString(args[1]) {
			sess, _, err := s.currentSearch(ctx, owner)
			if err != nil {
				return err
			}
			searchID = sess.SearchID
			sVal, _ := strconv.Atoi(args[0])
			sVal--
			srcIdx = &sVal
			idx, _ = strconv.Atoi(args[1])
		} else if len(args) >= 2 && reHex8.MatchString(args[0]) && reDigits.MatchString(args[1]) {
			searchID = args[0]
			idx, _ = strconv.Atoi(args[1])
			if len(args) >= 3 && reDigits.MatchString(args[2]) {
				sVal, _ := strconv.Atoi(args[2])
				srcIdx = &sVal
			}
		} else {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "发送 下载 序号，例如：下载 1")
		}
		res, ok, err := s.loadSearchResult(ctx, owner+":"+searchID)
		if err != nil {
			return err
		}
		if !ok || res.Expires <= s.nowFn().Unix() {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索结果已过期，请重新搜索")
		}
		var tracks []model.TrackDTO
		if len(res.SourceStates) > 0 {
			if srcIdx != nil {
				if *srcIdx < 0 || *srcIdx >= len(res.SourceStates) {
					return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "音源序号无效")
				}
				tracks = res.SourceStates[*srcIdx].Tracks
			} else if msg.Channel != ChannelTelegram && res.View == "all" && len(res.FlatTracks) > 0 {
				tracks = make([]model.TrackDTO, len(res.FlatTracks))
				for i, ft := range res.FlatTracks {
					tracks[i] = ft.Track
				}
			} else if res.Selected >= 0 && res.Selected < len(res.SourceStates) {
				tracks = res.SourceStates[res.Selected].Tracks
			}
		} else {
			tracks = res.Tracks
		}
		if idx < 1 || idx > len(tracks) {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索序号无效或结果已过期")
		}
		track := tracks[idx-1]
		qualID := ""
		if len(track.Qualities) > 0 {
			qualID = track.Qualities[0].ID
		}
		job, err := s.backend.EnqueueDownload(ctx, actor, track.ID, qualID, "bot:"+msg.ID)
		if err != nil {
			return err
		}
		own := botTaskOwner{ID: job.ID, Owner: owner, Channel: msg.Channel, User: msg.User}
		rawOwn, _ := json.Marshal(own)
		if _, err := s.store.PutDurableRecord(ctx, nsBotTask, job.ID, string(rawOwn), 0); err != nil {
			return err
		}
		delivery := "完成后将通知你，届时可选择「发送文件」或「获取链接」。"
		if msg.Channel != ChannelTelegram {
			delivery = "完成后将通知你，届时可发送「获取链接」或「链接 <任务编号>」获取原文件下载链接。"
		}
		taskLabel := shortTaskID(job.ID)
		replyLines := []string{
			messageHeading(msg.Channel, "已加入下载队列", ""),
			"",
			conciseText(job.Track.Title, 60),
			"预计大小：" + formatTrackSize(job.Track),
			"任务：" + taskLabel,
			"",
			delivery,
		}
		if hint := commandHint(msg.Channel, false, true, true); hint != "" {
			replyLines = append(replyLines, hint)
		}
		var dlButtons [][]InlineButton
		if tBtns := s.taskButtons(job.ID, job.Status, ""); len(tBtns) > 0 {
			dlButtons = append(dlButtons, tBtns)
		}
		dlButtons = append(dlButtons, []InlineButton{{Text: "我的任务", CallbackData: "/status"}})
		return s.enqueueReply(ctx, msg.Channel, msg.User, "dl_enq:"+msg.ID, OutboxPayload{
			Text:    strings.Join(replyLines, "\n"),
			Buttons: dlButtons,
			JobID:   job.ID,
		})
	case "status":
		jobs, err := s.backend.ListDownloads(ctx, actor, 10)
		if err != nil {
			return err
		}
		lines := []string{messageHeading(msg.Channel, "我的任务", ""), ""}
		var buttons [][]InlineButton
		count := 0
		for _, j := range jobs {
			rec, ok, _ := s.store.GetDurableRecord(ctx, nsBotTask, j.ID)
			if ok {
				var own botTaskOwner
				_ = json.Unmarshal([]byte(rec.BodyJSON), &own)
				if own.Owner != owner {
					continue
				}
			}
			count++
			if count > 5 {
				break
			}
			taskLabel := shortTaskID(j.ID)
			progressLine := formatJobProgress(j)
			if j.Status == "complete" {
				progressLine = formatJobSize(ctx, s, j)
			}
			lines = append(lines,
				fmt.Sprintf("%d. %s", count, conciseText(j.Track.Title, 52)),
				"   状态："+statusLabelText(j.Status),
				"   任务："+taskLabel,
				"   "+progressLine,
				"",
			)
			if actions := s.taskButtons(j.ID, j.Status, fmt.Sprintf("%d. ", count)); len(actions) > 0 {
				buttons = append(buttons, actions)
			}
		}
		buttons = append(buttons, []InlineButton{
			{Text: "刷新任务", CallbackData: "/status"},
			{Text: "功能菜单", CallbackData: "/menu"},
		})
		if count == 0 {
			return s.enqueueReply(ctx, msg.Channel, msg.User, "status:"+msg.ID, OutboxPayload{
				Text:    "我的任务\n\n暂无任务，先搜索喜欢的音乐。",
				Buttons: buttons,
			})
		}
		if hint := commandHint(msg.Channel, false, true, true); hint != "" {
			lines = append(lines, hint)
		}
		return s.enqueueReply(ctx, msg.Channel, msg.User, "status:"+msg.ID, OutboxPayload{
			Text:    strings.Join(lines, "\n"),
			Buttons: buttons,
		})
	case "link", "file", "cancel", "resume":
		own, job, err := s.resolveBotTaskForCommand(ctx, actor, owner, cmd, arg)
		if err != nil {
			return err
		}
		ident := own.ID
		switch cmd {
		case "link":
			links := s.Links()
			if links == nil {
				return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请在通知设置中填写访问地址后获取链接")
			}
			u, err := links.Create(ctx, job, s.nowFn())
			if err != nil {
				return err
			}
			linkText := strings.Join([]string{
				messageHeading(msg.Channel, "下载链接", ""),
				"",
				conciseText(job.Track.Title, 60),
				"任务：" + shortTaskID(job.ID),
				formatJobSize(ctx, s, job),
				"链接持有者可下载，请勿转发。",
			}, "\n")
			return s.enqueueReply(ctx, msg.Channel, msg.User, "link:"+msg.ID, OutboxPayload{
				Text:  linkText,
				URL:   u,
				JobID: ident,
			})
		case "file":
			if job.Status != "complete" || job.File == nil || *job.File == "" {
				return model.NewError(model.ErrConflict, http.StatusConflict, "任务尚未完成或文件已删除")
			}
			return s.enqueueCompletedTaskNotice(ctx, msg.Channel, msg.User, "file:"+msg.ID, job)
		default:
			if err := s.backend.ControlDownload(ctx, actor, ident, cmd); err != nil {
				return err
			}
			label := "已继续下载。"
			if cmd == "resume" {
				own.Notified = ""
				rawOwn, _ := json.Marshal(own)
				_, _ = s.store.PutDurableRecord(ctx, nsBotTask, own.ID, string(rawOwn), 0)
			} else if cmd == "cancel" {
				label = "已取消任务。"
			}
			return s.enqueueReply(ctx, msg.Channel, msg.User, cmd+":"+msg.ID, OutboxPayload{Text: label})
		}
	default:
		return s.enqueueReply(ctx, msg.Channel, msg.User, "help:"+msg.ID, OutboxPayload{
			Text:    helpMenuText,
			Buttons: s.menuButtons(),
		})
	}
}

func (s *Service) resolveBotTaskForCommand(ctx context.Context, actor auth.Actor, owner, cmd, arg string) (botTaskOwner, model.DownloadJobDTO, error) {
	ident := strings.ToLower(strings.TrimSpace(arg))
	if ident == "" {
		if s.backend == nil {
			return botTaskOwner{}, model.DownloadJobDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "任务不存在或不属于你")
		}
		jobs, err := s.backend.ListDownloads(ctx, actor, 20)
		if err != nil {
			return botTaskOwner{}, model.DownloadJobDTO{}, err
		}
		for _, j := range jobs {
			rec, ok, _ := s.store.GetDurableRecord(ctx, nsBotTask, j.ID)
			if !ok {
				continue
			}
			var own botTaskOwner
			if json.Unmarshal([]byte(rec.BodyJSON), &own) != nil || own.Owner != owner {
				continue
			}
			switch cmd {
			case "link", "file":
				if j.Status == "complete" {
					return own, j, nil
				}
			case "resume":
				if j.Status == "paused" || j.Status == "failed" {
					return own, j, nil
				}
			case "cancel":
				if j.Status == "queued" || j.Status == "resolving" || j.Status == "downloading" || j.Status == "verifying" || j.Status == "publishing" || j.Status == "enriching" || j.Status == "paused" || j.Status == "failed" {
					return own, j, nil
				}
			}
		}
		if cmd == "link" || cmd == "file" {
			return botTaskOwner{}, model.DownloadJobDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "暂无已完成的下载任务，请先搜索并下载音乐")
		}
		return botTaskOwner{}, model.DownloadJobDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "暂无符合当前操作的任务，请发送 /status 查看任务列表")
	}

	if !reHex8to32.MatchString(ident) && !model.IsValidUUID(ident) {
		return botTaskOwner{}, model.DownloadJobDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "任务不存在或不属于你")
	}
	if rec, ok, err := s.store.GetDurableRecord(ctx, nsBotTask, strings.TrimSpace(arg)); err != nil {
		return botTaskOwner{}, model.DownloadJobDTO{}, err
	} else if ok {
		var own botTaskOwner
		_ = json.Unmarshal([]byte(rec.BodyJSON), &own)
		if own.Owner != owner {
			return botTaskOwner{}, model.DownloadJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "任务不存在或不属于你")
		}
		job, gErr := s.backend.GetDownload(ctx, actor, own.ID)
		return own, job, gErr
	}

	recs, err := s.store.ListDurableRecords(ctx, nsBotTask, 200)
	if err != nil {
		return botTaskOwner{}, model.DownloadJobDTO{}, err
	}
	var ownedMatch *botTaskOwner
	sawOther := false
	for _, rec := range recs {
		if !strings.HasPrefix(strings.ToLower(rec.ID), ident) {
			continue
		}
		var own botTaskOwner
		if json.Unmarshal([]byte(rec.BodyJSON), &own) != nil {
			continue
		}
		if own.Owner == owner {
			copyOwn := own
			ownedMatch = &copyOwn
			break
		}
		sawOther = true
	}
	if ownedMatch == nil {
		if sawOther {
			return botTaskOwner{}, model.DownloadJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "任务不存在或不属于你")
		}
		return botTaskOwner{}, model.DownloadJobDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "任务不存在或不属于你")
	}
	job, gErr := s.backend.GetDownload(ctx, actor, ownedMatch.ID)
	return *ownedMatch, job, gErr
}

func (s *Service) aiEnabledForChannel(channel string) bool {
	cfg := s.Config()
	switch channel {
	case ChannelTelegram:
		return cfg.TGAIEnabled
	case ChannelWeCom:
		return cfg.WeComAIEnabled
	case ChannelFeishu:
		return cfg.FeishuAIEnabled
	case ChannelDingTalk:
		return cfg.DingTalkAIEnabled
	case ChannelDiscord:
		return cfg.DiscordAIEnabled
	default:
		return false
	}
}

func (s *Service) runAICommand(ctx context.Context, msg InboundMessage, cmd, arg string) error {
	if s.backend == nil {
		return model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "AI 助手服务不可用")
	}
	var imgBytes []byte
	if msg.Image != nil {
		b, err := s.fetchInboundImage(ctx, msg)
		if err != nil {
			return err
		}
		imgBytes = b
	}
	reply, err := s.backend.RunAICommand(ctx, s.ActorFor(msg.Channel, msg.User), cmd, arg, imgBytes)
	if err != nil {
		return err
	}
	return s.enqueueReply(ctx, msg.Channel, msg.User, "ai:"+msg.ID, OutboxPayload{Text: reply})
}

func (s *Service) fetchInboundImage(ctx context.Context, msg InboundMessage) ([]byte, error) {
	if msg.Image == nil {
		return nil, nil
	}
	if msg.Image.Size > botImageMax {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "图片超过 5 MB，请压缩后重新发送")
	}
	cfg := s.Config()
	rev := s.Revision()
	var data []byte
	var err error
	switch msg.Channel {
	case ChannelTelegram:
		proxy := ""
		if s.projectProxy != nil {
			proxy = s.projectProxy()
		}
		raw, gErr := s.channels.callTelegram(ctx, cfg, rev, s.localAPI, proxy, "getFile", map[string]any{"file_id": msg.Image.ID}, "", "", "")
		if gErr != nil {
			return nil, model.WrapError(model.ErrUnavailable, http.StatusBadGateway, "获取 Telegram 图片信息失败", gErr)
		}
		var info struct {
			FilePath string `json:"file_path"`
			FileSize int64  `json:"file_size"`
		}
		if json.Unmarshal(raw, &info) != nil || info.FilePath == "" || info.FileSize > botImageMax {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "图片不可用或超过 5 MB，请重新发送")
		}
		if cfg.TGLocalEnabled && filepath.IsAbs(info.FilePath) && s.localAPI != nil {
			root := s.localAPI.spec.DataDir
			if root == "" {
				root = filepath.Join("data", "telegram-bot-api")
			}
			absRoot, _ := filepath.Abs(root)
			realRoot, rErrRoot := filepath.EvalSymlinks(absRoot)
			if rErrRoot == nil {
				absRoot = realRoot
			}
			absTarget, _ := filepath.Abs(info.FilePath)
			lst, lErr := os.Lstat(absTarget)
			if lErr != nil || lst.Mode()&os.ModeSymlink != 0 || lst.IsDir() || lst.Size() > botImageMax {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Telegram 图片不可用或为符号链接")
			}
			realTarget, rErrTarget := filepath.EvalSymlinks(absTarget)
			if rErrTarget != nil {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Telegram 图片路径解析失败")
			}
			rel, rErr := filepath.Rel(absRoot, realTarget)
			if rErr != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Telegram 图片不在内置服务缓存目录中")
			}
			data, err = os.ReadFile(realTarget)
		} else {
			if filepath.IsAbs(info.FilePath) || strings.Contains(info.FilePath, "\\") || strings.Contains(info.FilePath, "..") {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Telegram 图片路径无效")
			}
			data, err = s.channels.fetchTelegramFile(ctx, cfg, rev, s.localAPI, proxy, info.FilePath, botImageMax)
		}
	case ChannelWeCom:
		data, err = s.channels.fetchWeComMedia(ctx, cfg, rev, msg.Image.ID, botImageMax)
	default:
		proxy := ""
		if s.projectProxy != nil {
			proxy = s.projectProxy()
		}
		data, err = s.channels.fetchInboundChannelImage(ctx, cfg, rev, msg.Channel, *msg.Image, proxy, botImageMax)
	}
	if err != nil {
		return nil, model.WrapError(model.ErrUnavailable, http.StatusBadGateway, "无法获取机器人图片，请重新发送或检查渠道配置", err)
	}
	if len(data) > botImageMax {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "图片超过 5 MB，请压缩后重新发送")
	}
	return data, nil
}

func (s *Service) countSearchPages(channel string, res botSearchResult) int {
	size := searchPageSize(channel)
	if len(res.SourceStates) > 0 {
		if channel != ChannelTelegram && res.ResultsPerSource > 0 {
			size = res.ResultsPerSource
		}
		if channel != ChannelTelegram && res.View == "all" {
			pages := 1
			for _, src := range res.SourceStates {
				p := (len(src.Tracks) + size - 1) / size
				if p > pages {
					pages = p
				}
			}
			return pages
		}
		if res.Selected >= 0 && res.Selected < len(res.SourceStates) {
			pages := (len(res.SourceStates[res.Selected].Tracks) + size - 1) / size
			if pages < 1 {
				return 1
			}
			return pages
		}
		return 1
	}
	pages := (len(res.Tracks) + size - 1) / size
	if pages < 1 {
		return 1
	}
	return pages
}

func (s *Service) currentSearch(ctx context.Context, owner string) (botSessionState, botSearchResult, error) {
	rec, ok, err := s.store.GetDurableRecord(ctx, nsBotSession, owner)
	if err != nil || !ok {
		return botSessionState{}, botSearchResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "没有有效搜索结果，请先发送歌名重新搜索")
	}
	var sess botSessionState
	_ = json.Unmarshal([]byte(rec.BodyJSON), &sess)
	resRec, ok, err := s.store.GetDurableRecord(ctx, nsBotSearch, owner+":"+sess.SearchID)
	if err != nil || !ok {
		return botSessionState{}, botSearchResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "没有有效搜索结果，请先发送歌名重新搜索")
	}
	var res botSearchResult
	_ = json.Unmarshal([]byte(resRec.BodyJSON), &res)
	if res.Expires <= s.nowFn().Unix() {
		return botSessionState{}, botSearchResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索结果已过期，请重新搜索")
	}
	return sess, res, nil
}

func (s *Service) sendSearchPage(ctx context.Context, msg InboundMessage, searchID string, res botSearchResult, page int) error {
	size := searchPageSize(msg.Channel)
	pages := (len(res.Tracks) + size - 1) / size
	if pages < 1 {
		pages = 1
	}
	if page < 1 || page > pages {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索页码无效")
	}
	start := (page - 1) * size
	end := start + size
	if end > len(res.Tracks) {
		end = len(res.Tracks)
	}
	subtitle := fmt.Sprintf("第 %d/%d 页 · 共 %d 首", page, pages, len(res.Tracks))
	if res.Keyword != "" {
		subtitle += "\n关键词：" + conciseText(res.Keyword, 60)
	}
	var availableSources []model.AvailableSourceDTO
	if s.backend != nil {
		availableSources = s.backend.AvailableSources()
	}
	lines := []string{messageHeading(msg.Channel, "搜索结果", subtitle), ""}
	for i, t := range res.Tracks[start:end] {
		lines = append(lines, formatTrackLines(start+i+1, t, sourceLabelForTrack(t, availableSources), msg.Channel == ChannelTelegram, true)...)
		lines = append(lines, "")
	}
	if msg.Channel != ChannelTelegram {
		lines = append(lines, "操作提示", fmt.Sprintf("下载：下载 %d（先打开网页试听确认版本）", start+1), commandHint(msg.Channel, false, false, true))
		if links := s.Links(); links != nil && (msg.Channel == ChannelFeishu || msg.Channel == ChannelDingTalk || msg.Channel == ChannelDiscord) {
			lines = append(lines, "试听："+links.baseURL)
		}
		if pages > 1 {
			lines = append(lines, "翻页：下一页 / 上一页 / 第2页（30 分钟内有效）")
		}
	}
	if res.Partial {
		lines = append(lines, "", "提示：部分音源失败或超时，以上为已返回结果。")
	}

	var buttons [][]InlineButton
	if msg.Channel == ChannelTelegram {
		var row []InlineButton
		for i := start + 1; i <= end; i++ {
			row = append(row, InlineButton{Text: strconv.Itoa(i), CallbackData: fmt.Sprintf("/download %s %d", searchID, i)})
		}
		buttons = append(buttons, buttonRows(row, size)...)
		var nav []InlineButton
		if page > 1 {
			nav = append(nav, InlineButton{Text: "上一页", CallbackData: fmt.Sprintf("/results %s %d", searchID, page-1)})
		}
		if page < pages {
			nav = append(nav, InlineButton{Text: "下一页", CallbackData: fmt.Sprintf("/results %s %d", searchID, page+1)})
		}
		if len(nav) > 0 {
			buttons = append(buttons, nav)
		}
		if links := s.Links(); links != nil {
			buttons = append(buttons, []InlineButton{{Text: "打开网页试听", URL: links.baseURL}})
		}
		buttons = append(buttons, []InlineButton{
			{Text: "功能菜单", CallbackData: "/menu"},
			{Text: "我的任务", CallbackData: "/status"},
		})
	}

	if err := s.saveSession(ctx, msg.Channel+":"+msg.User, searchID, page, res.Expires); err != nil {
		return err
	}
	payload := OutboxPayload{
		Text:          strings.Join(lines, "\n"),
		Buttons:       buttons,
		EditMessageID: msg.ReplyMessageID,
	}
	return s.enqueueReply(ctx, msg.Channel, msg.User, fmt.Sprintf("page:%s:%d:%s", searchID, page, msg.ID), payload)
}

func (s *Service) enqueueReply(ctx context.Context, channel, recipient, eventKey string, payload OutboxPayload) error {
	if channel != ChannelTelegram {
		payload.Buttons = nil
	}
	chunks, err := splitReplyChunks(channel, payload.Text, payload.URL)
	if err != nil {
		return err
	}
	for i, chunk := range chunks {
		last := i == len(chunks)-1
		itemPayload := OutboxPayload{
			Text: chunk,
		}
		if last {
			itemPayload.URL = payload.URL
			itemPayload.Buttons = payload.Buttons
			itemPayload.JobID = payload.JobID
			itemPayload.File = payload.File
			itemPayload.CardKey = payload.CardKey
			itemPayload.MediaID = payload.MediaID
			itemPayload.MediaUntil = payload.MediaUntil
		}
		if len(chunks) == 1 && payload.EditMessageID != nil {
			itemPayload.EditMessageID = payload.EditMessageID
		}
		raw, _ := json.Marshal(itemPayload)
		partKey := eventKey
		if len(chunks) > 1 {
			partKey = fmt.Sprintf("%s#%d", eventKey, i)
		}
		params := store.OutboxEnqueueParams{
			Channel:      channel,
			RecipientKey: recipient,
			EventKey:     partKey,
			PayloadJSON:  string(raw),
		}
		if last && payload.CardKey != "" {
			if err := s.store.ReplacePendingOutboxByPrefix(ctx, "card:"+payload.CardKey+":", params); err != nil {
				return err
			}
			continue
		}
		if err := s.store.EnqueueOutbox(ctx, params); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) EnqueueNotification(ctx context.Context, channel, recipient, eventKey string, payload OutboxPayload) error {
	if !s.Allowed(channel, recipient) {
		return nil
	}
	return s.enqueueReply(ctx, channel, recipient, eventKey, payload)
}

// ProcessOutboxOnce claims due pending/retry_wait outbox messages (leaving delivery_unknown untouched),
// dispatches them to the respective channel, and records receipt/backoff state.
func (s *Service) ProcessOutboxOnce(ctx context.Context, limit int) (processed int, err error) {
	items, err := s.store.ClaimOutbox(ctx, limit)
	if err != nil {
		return 0, err
	}
	var firstErr error
	for _, item := range items {
		processed++
		dErr := func() error {
			dCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
			defer cancel()
			return s.deliverOne(dCtx, item)
		}()
		if dErr != nil {
			if firstErr == nil {
				firstErr = dErr
			}
			if s.onLog != nil {
				s.onLog("warning", "发件箱消息处理失败("+item.ID+"): "+s.RedactError(dErr), "bot", "")
			}
			continue
		}
	}
	return processed, firstErr
}

// ResendOutbox explicitly retries a message in delivery_unknown or failed state at an admin's request.
func (s *Service) ResendOutbox(ctx context.Context, admin auth.Actor, outboxID string) error {
	if !admin.Authenticated || !admin.Has(auth.PermAdmin) {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "需要管理员权限重发未知状态通知")
	}
	var msg model.OutboxMessage
	var stStr string
	row := s.store.Reader().QueryRowContext(ctx, `
		SELECT id, channel, recipient_key, payload_json, state, attempt, next_attempt_at, last_error, created_at, updated_at
		FROM outbox WHERE id = ?
	`, outboxID)
	if err := row.Scan(&msg.ID, &msg.Channel, &msg.Recipient, &msg.PayloadJSON, &stStr, &msg.Attempt, &msg.NextAttempt, &msg.LastError, &msg.CreatedAt, &msg.UpdatedAt); err != nil {
		return model.NewError(model.ErrNotFound, http.StatusNotFound, "发件箱记录不存在")
	}
	msg.Status = model.OutboxStatus(stStr)
	if msg.Status != model.OutboxDeliveryUnknown && msg.Status != model.OutboxFailed {
		return model.NewError(model.ErrConflict, http.StatusConflict, "仅支持重发 delivery_unknown 或 failed 状态的通知")
	}
	msg.Attempt++
	if err := s.store.UpdateOutboxState(ctx, msg.ID, model.OutboxSending, "{}", "", 0); err != nil {
		return err
	}
	return s.deliverOne(ctx, msg)
}

func (s *Service) deliverOne(ctx context.Context, item model.OutboxMessage) error {
	var payload OutboxPayload
	if err := json.Unmarshal([]byte(item.PayloadJSON), &payload); err != nil {
		return s.store.UpdateOutboxState(ctx, item.ID, model.OutboxFailed, "{}", "无效通知负载", 0)
	}
	receipt, sendErr := s.dispatchChannel(ctx, item, &payload)
	if sendErr == nil {
		return s.store.UpdateOutboxState(ctx, item.ID, model.OutboxSent, receipt, "", 0)
	}
	safeErr := s.RedactError(sendErr)
	if item.Attempt >= 8 {
		return s.store.UpdateOutboxState(ctx, item.ID, model.OutboxFailed, "{}", safeErr, 0)
	}
	delay := 5 * int64(1<<min(item.Attempt, 10))
	if delay > 3600 {
		delay = 3600
	}
	nextAt := s.nowFn().UnixMilli() + delay*1000
	return s.store.UpdateOutboxState(ctx, item.ID, model.OutboxRetryWait, "{}", safeErr, nextAt)
}

func (s *Service) dispatchChannel(ctx context.Context, item model.OutboxMessage, payload *OutboxPayload) (string, error) {
	cfg := s.Config()
	rev := s.Revision()
	var proxy string
	if s.projectProxy != nil {
		proxy = s.projectProxy()
	}

	var filePath string
	var cachedJob model.DownloadJobDTO
	var hasJob bool
	if payload.File && payload.JobID != "" && s.backend != nil {
		job, err := s.backend.GetDownload(ctx, auth.SystemActor(), payload.JobID)
		if err == nil {
			cachedJob = job
			hasJob = true
			if job.Status == "complete" && job.File != nil && *job.File != "" {
				if abs, _, _, err := s.backend.ResolveDownloadFile(ctx, *job.File); err == nil {
					filePath = abs
				}
			}
		}
		if filePath == "" {
			payload.File = false
			payload.URL = ""
			payload.Text = "文件已删除或不可访问，请重新下载。"
		}
	}

	switch item.Channel {
	case ChannelTelegram:
		if payload.File && filePath != "" {
			ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(filePath)), ".")
			isAudio := ext == "mp3" || ext == "m4a" || ext == "flac"
			method, field := "sendDocument", "document"
			mime := ""
			if isAudio {
				method, field = "sendAudio", "audio"
				if ext == "flac" {
					mime = "audio/flac"
				}
			}
			caption := payload.Text
			if len([]rune(caption)) > 1000 {
				caption = string([]rune(caption)[:1000])
			}
			body := map[string]any{
				"chat_id": item.Recipient,
				"caption": caption,
			}
			if isAudio && hasJob {
				if strings.TrimSpace(cachedJob.Track.Title) != "" {
					body["title"] = cachedJob.Track.Title
				}
				if strings.TrimSpace(cachedJob.Track.Artist) != "" {
					body["performer"] = cachedJob.Track.Artist
				}
			}
			res, err := s.channels.callTelegram(ctx, cfg, rev, s.localAPI, proxy, method, body, field, filePath, mime)
			if err != nil && isAudio && !IsTelegramSizeError(err) && isTelegramFormatReject(err) {
				if s.onLog != nil {
					s.onLog("warning", "Telegram sendAudio 拒绝音频格式: "+s.RedactError(err)+"；改用 sendDocument 发送原文件", "bot", payload.JobID)
				}
				res, err = s.channels.callTelegram(ctx, cfg, rev, s.localAPI, proxy, "sendDocument", map[string]any{
					"chat_id": item.Recipient,
					"caption": caption,
				}, "document", filePath, "")
			}
			if err == nil {
				return string(res), nil
			}
			if !IsTelegramSizeError(err) {
				return "", err
			}
			// Fallback from oversized Telegram file to signed download link text message.
			var linkURL string
			if links := s.Links(); links != nil && s.backend != nil && payload.JobID != "" {
				if !hasJob {
					if job, jErr := s.backend.GetDownload(ctx, auth.SystemActor(), payload.JobID); jErr == nil {
						cachedJob = job
						hasJob = true
					}
				}
				if hasJob {
					if u, lErr := links.Create(ctx, cachedJob, s.nowFn()); lErr == nil {
						linkURL = u
					}
				}
			}
			payload.File = false
			payload.URL = linkURL
			if linkURL != "" {
				payload.Text += "\nTelegram 拒绝了过大文件，已改为原文件下载链接。"
			} else {
				payload.Text += "\nTelegram 拒绝了过大文件，请在 Web 下载原文件。"
			}
		}
		body := map[string]any{
			"chat_id":              item.Recipient,
			"text":                 payload.Text,
			"link_preview_options": map[string]bool{"is_disabled": true},
		}
		if payload.URL != "" {
			body["reply_markup"] = map[string]any{
				"inline_keyboard": [][]InlineButton{
					{{Text: "下载到手机", URL: payload.URL}},
					{{Text: "我的任务", CallbackData: "/status"}, {Text: "功能菜单", CallbackData: "/menu"}},
				},
			}
		} else if len(payload.Buttons) > 0 {
			body["reply_markup"] = map[string]any{"inline_keyboard": payload.Buttons}
		}
		var editID int64
		if payload.EditMessageID != nil && *payload.EditMessageID > 0 {
			editID = *payload.EditMessageID
		} else if payload.CardKey != "" {
			if card, ok, _ := s.loadSearchResult(ctx, payload.CardKey); ok && card.CardMsg != nil && *card.CardMsg > 0 {
				editID = *card.CardMsg
			}
		}
		method := "sendMessage"
		if editID > 0 {
			method = "editMessageText"
			body["message_id"] = editID
		}
		res, err := s.channels.callTelegram(ctx, cfg, rev, s.localAPI, proxy, method, body, "", "", "")
		if err != nil {
			return "", err
		}
		if method == "sendMessage" && payload.CardKey != "" {
			var sentMsg struct {
				MessageID int64 `json:"message_id"`
			}
			if json.Unmarshal(res, &sentMsg) == nil && sentMsg.MessageID > 0 {
				s.searchResMu.Lock()
				if card, ok, _ := s.loadSearchResult(ctx, payload.CardKey); ok {
					mid := sentMsg.MessageID
					card.CardMsg = &mid
					_ = s.saveSearchResult(ctx, card)
				}
				s.searchResMu.Unlock()
			}
		}
		return string(res), nil

	case ChannelWeCom:
		return s.channels.sendWeCom(ctx, cfg, rev, item.Recipient, *payload, filePath)
	case ChannelFeishu, ChannelDingTalk, ChannelDiscord:
		return s.channels.sendWebhookOrDM(ctx, cfg, rev, item.Channel, item.Recipient, item.ID, proxy, *payload)
	default:
		return "", fmt.Errorf("unsupported channel %s", item.Channel)
	}
}

func isTelegramFormatReject(err error) bool {
	if err == nil {
		return false
	}
	lower := strings.ToLower(err.Error())
	if !strings.Contains(lower, "error 400") {
		return false
	}
	for _, kw := range []string{"audio", "file", "content", "format", "media"} {
		if strings.Contains(lower, kw) {
			return true
		}
	}
	return false
}

func (s *Service) RedactError(err error) string {
	return s.redactErrorWithConfig(err, s.Config())
}

func (s *Service) redactErrorWithConfig(err error, extra Config) string {
	if err == nil {
		return ""
	}
	msg := err.Error()
	seen := make(map[string]bool)
	for _, c := range []Config{s.Config(), extra} {
		for _, sec := range c.SecretValues() {
			if sec == "" || seen[sec] {
				continue
			}
			seen[sec] = true
			msg = strings.ReplaceAll(msg, sec, config.HiddenPlaceholder)
			if qEsc := url.QueryEscape(sec); qEsc != "" && qEsc != sec {
				msg = strings.ReplaceAll(msg, qEsc, config.HiddenPlaceholder)
			}
			if pEsc := url.PathEscape(sec); pEsc != "" && pEsc != sec {
				msg = strings.ReplaceAll(msg, pEsc, config.HiddenPlaceholder)
			}
		}
	}
	msg = reBotTokenURL.ReplaceAllString(msg, "/bot"+config.HiddenPlaceholder+"/")
	msg = reURLInError.ReplaceAllStringFunc(msg, config.RedactURL)
	if len(msg) > 2000 {
		msg = msg[:2000]
	}
	return msg
}

// SyncLocalAPI aligns the optional built-in telegram-bot-api child process with the active config.
func (s *Service) SyncLocalAPI(ctx context.Context) error {
	if s.readOnly || s.localAPI == nil {
		return nil
	}
	cfg := s.Config()
	if !cfg.TGEnabled || !cfg.TGLocalEnabled {
		s.localAPI.Stop()
		return nil
	}
	if s.localAPI.Matches(cfg.TGLocalAPIID, cfg.TGLocalAPIHash) {
		return nil
	}
	return s.localAPI.Start(ctx, cfg.TGLocalAPIID, cfg.TGLocalAPIHash)
}

func (s *Service) wakeTelegramPoller() {
	if s.tgWakeCh == nil {
		return
	}
	select {
	case s.tgWakeCh <- struct{}{}:
	default:
	}
}

func (s *Service) workerCtx() context.Context {
	s.bgCtxMu.RLock()
	ctx := s.bgCtx
	s.bgCtxMu.RUnlock()
	if ctx != nil {
		return ctx
	}
	return context.Background()
}

// StartWorkers launches background workers that execute queued inbound commands,
// long-poll Telegram updates, register Telegram menus, and deliver finished download notices.
func (s *Service) StartWorkers(ctx context.Context) {
	if s.readOnly {
		return
	}
	s.bgCtxMu.Lock()
	s.bgCtx = ctx
	s.bgCtxMu.Unlock()
	go s.runInboundWorker(ctx)
	go s.runTelegramPollLoop(ctx)
	go s.runDeliveryNoticeLoop(ctx)
}

func (s *Service) runInboundWorker(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case msg := <-s.pendingCh:
			if err := s.ExecuteInbound(ctx, msg); err != nil && ctx.Err() == nil && s.onLog != nil {
				s.onLog("warning", "执行机器人指令失败: "+s.RedactError(err), "bot", "")
			}
		}
	}
}

func (s *Service) loadTGOffset(ctx context.Context) int64 {
	rec, ok, err := s.store.GetDurableRecord(ctx, nsBotSetting, "tg_offset")
	if err != nil || !ok {
		return 0
	}
	var val int64
	if json.Unmarshal([]byte(rec.BodyJSON), &val) == nil && val > 0 {
		return val
	}
	if n, pErr := strconv.ParseInt(strings.TrimSpace(rec.BodyJSON), 10, 64); pErr == nil && n > 0 {
		return n
	}
	return 0
}

func (s *Service) saveTGOffset(ctx context.Context, offset int64) error {
	raw, _ := json.Marshal(offset)
	_, err := s.store.PutDurableRecord(ctx, nsBotSetting, "tg_offset", string(raw), 0)
	return err
}

func (s *Service) maybeRegisterTelegramMenu(ctx context.Context, cfg Config) {
	menuRev := cfg.TGToken + "|" + strconv.FormatBool(cfg.TGLocalEnabled) + "|" + cfg.TGAPIURL
	s.mu.RLock()
	already := s.lastMenuRev == menuRev
	s.mu.RUnlock()
	if already {
		return
	}
	proxy := ""
	if s.projectProxy != nil {
		proxy = s.projectProxy()
	}
	regCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	for _, lang := range []string{"", "zh"} {
		if _, err := s.channels.callTelegram(regCtx, cfg, s.Revision(), s.localAPI, proxy, "setMyCommands", map[string]any{
			"commands":      tgMenuCommands,
			"scope":         map[string]string{"type": "all_private_chats"},
			"language_code": lang,
		}, "", "", ""); err != nil {
			if s.onLog != nil {
				s.onLog("warning", "Telegram 菜单注册失败，将在后台重试: "+s.RedactError(err), "bot", "")
			}
			return
		}
	}
	_, _ = s.channels.callTelegram(regCtx, cfg, s.Revision(), s.localAPI, proxy, "setChatMenuButton", map[string]any{
		"menu_button": map[string]string{"type": "commands"},
	}, "", "", "")
	s.mu.Lock()
	s.lastMenuRev = menuRev
	s.mu.Unlock()
	if s.onLog != nil {
		s.onLog("info", "Telegram 命令菜单已注册；可发送 /start 或 /menu 打开功能入口", "bot", "")
	}
}

func (s *Service) runTelegramPollLoop(ctx context.Context) {
	offset := s.loadTGOffset(ctx)
	var lastToken string
	var lastLocal bool
	var nextMenuAttempt time.Time
	for {
		if ctx.Err() != nil {
			return
		}
		cfg := s.Config()
		if sanitizeBotSecret(cfg.TGToken) != lastToken || cfg.TGLocalEnabled != lastLocal {
			offset = s.loadTGOffset(ctx)
			lastToken = sanitizeBotSecret(cfg.TGToken)
			lastLocal = cfg.TGLocalEnabled
			nextMenuAttempt = time.Time{}
		}
		ready := cfg.TGEnabled && sanitizeBotSecret(cfg.TGToken) != ""
		if ready && cfg.TGLocalEnabled && (s.localAPI == nil || !s.localAPI.Matches(cfg.TGLocalAPIID, cfg.TGLocalAPIHash)) {
			ready = false
		}
		if !ready {
			select {
			case <-ctx.Done():
				return
			case <-s.tgWakeCh:
			case <-time.After(2 * time.Second):
			}
			continue
		}

		if time.Now().After(nextMenuAttempt) {
			nextMenuAttempt = time.Now().Add(60 * time.Second)
			go s.maybeRegisterTelegramMenu(ctx, cfg)
		}

		pollCtx, cancel := context.WithTimeout(ctx, 40*time.Second)
		_, err := s.PollTelegramOnce(pollCtx, &offset, 20)
		cancel()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if s.onLog != nil {
				s.onLog("warning", "Telegram 轮询消息失败: "+s.RedactError(err)+"（请检查代理、Token 及是否已关闭 Webhook）", "bot", "")
			}
			select {
			case <-ctx.Done():
				return
			case <-s.tgWakeCh:
			case <-time.After(3 * time.Second):
			}
		}
	}
}

// PollTelegramOnce fetches and enqueues one batch of Telegram getUpdates, advancing and persisting offset.
func (s *Service) PollTelegramOnce(ctx context.Context, offset *int64, timeoutSec int) (int, error) {
	cfg := s.Config()
	if !cfg.TGEnabled || sanitizeBotSecret(cfg.TGToken) == "" {
		return 0, nil
	}
	if cfg.TGLocalEnabled && (s.localAPI == nil || !s.localAPI.Matches(cfg.TGLocalAPIID, cfg.TGLocalAPIHash)) {
		return 0, nil
	}
	if timeoutSec < 0 {
		timeoutSec = 0
	}
	var curOffset int64
	if offset != nil {
		curOffset = *offset
	} else {
		curOffset = s.loadTGOffset(ctx)
	}
	proxy := ""
	if s.projectProxy != nil {
		proxy = s.projectProxy()
	}
	params := map[string]any{
		"timeout":         timeoutSec,
		"allowed_updates": []string{"message", "callback_query"},
	}
	if curOffset > 0 {
		params["offset"] = curOffset
	}
	raw, err := s.channels.callTelegram(ctx, cfg, s.Revision(), s.localAPI, proxy, "getUpdates", params, "", "", "")
	if err != nil {
		return 0, err
	}
	var updates []TelegramUpdate
	if err := json.Unmarshal(raw, &updates); err != nil {
		return 0, fmt.Errorf("decode Telegram getUpdates: %w", err)
	}
	accepted := 0
	advanced := curOffset
	for _, upd := range updates {
		ok, err := s.HandleTelegramUpdate(ctx, upd)
		if err != nil {
			if de, isDE := model.AsDomainError(err); isDE && de.Status == http.StatusTooManyRequests && strings.Contains(de.Detail, "队列") {
				// Keep offset before the rejected update when inbound queue is temporarily full.
				break
			}
			if s.onLog != nil {
				s.onLog("warning", "处理 Telegram 更新失败: "+s.RedactError(err), "bot", "")
			}
			var uid int64
			if upd.CallbackQuery != nil {
				uid = upd.CallbackQuery.From.ID
			} else if upd.Message != nil {
				uid = upd.Message.From.ID
			}
			if uid > 0 {
				userStr := strconv.FormatInt(uid, 10)
				if s.Allowed(ChannelTelegram, userStr) {
					detail := s.RedactError(err)
					if de, isDE := model.AsDomainError(err); isDE {
						detail = de.Detail
					}
					_ = s.enqueueReply(ctx, ChannelTelegram, userStr, fmt.Sprintf("rej:%d", upd.UpdateID), OutboxPayload{Text: detail})
				}
			}
		}
		if ok {
			accepted++
		}
		if upd.UpdateID >= advanced {
			advanced = upd.UpdateID + 1
		}
	}
	if advanced > curOffset {
		if offset != nil {
			*offset = advanced
		}
		_ = s.saveTGOffset(ctx, advanced)
	}
	return accepted, nil
}

func (s *Service) runDeliveryNoticeLoop(ctx context.Context) {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_ = s.ProcessDownloadNoticesOnce(ctx)
		}
	}
}

func isJobProcessingActive(job model.DownloadJobDTO) bool {
	if job.Processing == nil {
		return false
	}
	switch job.Processing.Status {
	case "queued", "identifying", "tagging", "organizing", "indexing", "restoring", "applying":
		return true
	default:
		return false
	}
}

// ProcessDownloadNoticesOnce queues completion/failure notifications for webhook channels
// and interactive bot download tasks once associated auto-organize processing finishes.
func (s *Service) ProcessDownloadNoticesOnce(ctx context.Context) error {
	if s.backend == nil {
		return nil
	}
	jobs, err := s.backend.ListDownloads(ctx, auth.SystemActor(), 50)
	if err != nil {
		return err
	}
	jobMap := make(map[string]model.DownloadJobDTO, len(jobs))
	for _, j := range jobs {
		jobMap[j.ID] = j
	}
	cfg := s.Config()

	// 1. Webhook download notifications (Feishu / DingTalk / Discord).
	webhookEnabled := map[string]bool{
		ChannelFeishu:   cfg.FeishuEnabled && strings.TrimSpace(cfg.FeishuWebhook) != "",
		ChannelDingTalk: cfg.DingTalkEnabled && strings.TrimSpace(cfg.DingTalkWebhook) != "",
		ChannelDiscord:  cfg.DiscordEnabled && strings.TrimSpace(cfg.DiscordWebhook) != "",
	}
	for _, ch := range []string{ChannelFeishu, ChannelDingTalk, ChannelDiscord} {
		if !webhookEnabled[ch] {
			continue
		}
		initKey := ch + "_initialized"
		if _, initOK, _ := s.store.GetDurableRecord(ctx, nsBotSetting, initKey); !initOK {
			for _, j := range jobs {
				rawSt, _ := json.Marshal(j.Status)
				_, _ = s.store.PutDurableRecord(ctx, nsBotNotice, ch+":"+j.ID, string(rawSt), 0)
			}
			_, _ = s.store.PutDurableRecord(ctx, nsBotSetting, initKey, "true", 0)
			continue
		}
		for _, j := range jobs {
			if j.Status != "complete" && j.Status != "failed" && j.Status != "cancelled" {
				continue
			}
			if isJobProcessingActive(j) {
				continue
			}
			noticeID := ch + ":" + j.ID
			if rec, ok, _ := s.store.GetDurableRecord(ctx, nsBotNotice, noticeID); ok {
				var prev string
				_ = json.Unmarshal([]byte(rec.BodyJSON), &prev)
				if prev == j.Status {
					continue
				}
			}
			lines := []string{
				messageHeading(ch, statusLabelText(j.Status), ""),
				"",
				conciseText(j.Track.Title, 60),
				"歌手：" + conciseText(j.Track.Artist, 36),
				"任务：" + j.ID[:min(len(j.ID), 8)],
			}
			var linkURL string
			if j.Status == "complete" {
				lines = append(lines, formatJobSize(ctx, s, j))
				if j.Processing != nil && strings.TrimSpace(j.Processing.Detail) != "" {
					lines = append(lines, conciseText(j.Processing.Detail, 120))
				}
				if links := s.Links(); links != nil {
					if u, lErr := links.Create(ctx, j, s.nowFn()); lErr == nil {
						linkURL = u
					} else {
						lines = append(lines, "文件已删除或不可访问。")
					}
				} else {
					lines = append(lines, "可在 Web 音乐文件页下载；填写通知访问地址后可推送链接。")
				}
			}
			if err := s.enqueueReply(ctx, ch, "downloads", "notice:"+ch+":"+j.ID+":"+j.Status, OutboxPayload{
				Text:  strings.Join(lines, "\n"),
				URL:   linkURL,
				JobID: j.ID,
			}); err == nil {
				rawSt, _ := json.Marshal(j.Status)
				_, _ = s.store.PutDurableRecord(ctx, nsBotNotice, noticeID, string(rawSt), 0)
			}
		}
	}

	// 2. Interactive bot task deliveries (TG / WeCom / Feishu Bot / DingTalk Bot / Discord Bot).
	taskRecs, err := s.store.ListDurableRecords(ctx, nsBotTask, 100)
	if err != nil {
		return err
	}
	for _, rec := range taskRecs {
		var own botTaskOwner
		if json.Unmarshal([]byte(rec.BodyJSON), &own) != nil || own.ID == "" {
			continue
		}
		if !s.Allowed(own.Channel, own.User) {
			continue
		}
		job, ok := jobMap[own.ID]
		if !ok {
			if fetched, gErr := s.backend.GetDownload(ctx, auth.SystemActor(), own.ID); gErr == nil {
				job = fetched
				ok = true
			}
		}
		if !ok {
			continue
		}
		if job.Status != "complete" && job.Status != "failed" && job.Status != "paused" && job.Status != "cancelled" {
			continue
		}
		if own.Notified == job.Status || isJobProcessingActive(job) {
			continue
		}
		eventKey := fmt.Sprintf("task:%s:%s", own.ID, job.Status)
		if job.Status == "complete" {
			if job.File == nil || *job.File == "" {
				_ = s.enqueueReply(ctx, own.Channel, own.User, eventKey, OutboxPayload{
					Text: "文件已删除或变化，请重新下载。任务编号：" + shortTaskID(job.ID),
				})
			} else {
				_ = s.enqueueCompletedTaskPrompt(ctx, own.Channel, own.User, eventKey, job)
			}
		} else {
			text := strings.Join([]string{
				statusLabelText(job.Status),
				"",
				conciseText(job.Track.Title, 60),
				"任务 " + shortTaskID(job.ID),
				"",
				"打开 /status 管理任务。",
			}, "\n")
			var btns [][]InlineButton
			if tBtns := s.taskButtons(job.ID, job.Status, ""); len(tBtns) > 0 {
				btns = append(btns, tBtns)
			}
			btns = append(btns, []InlineButton{{Text: "我的任务", CallbackData: "/status"}})
			_ = s.enqueueReply(ctx, own.Channel, own.User, eventKey, OutboxPayload{
				Text:    text,
				Buttons: btns,
				JobID:   job.ID,
			})
		}
		own.Notified = job.Status
		rawOwn, _ := json.Marshal(own)
		_, _ = s.store.PutDurableRecord(ctx, nsBotTask, own.ID, string(rawOwn), 0)
	}
	return nil
}

func (s *Service) Close() {
	s.searchMu.Lock()
	for _, c := range s.searchCancel {
		if c != nil {
			c()
		}
	}
	s.searchCancel = nil
	s.searchMu.Unlock()
	s.localAPI.Stop()
}

// unused math import guard if needed
var _ = math.Abs
