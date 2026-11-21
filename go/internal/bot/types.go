package bot

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

const (
	ChannelTelegram = "tg"
	ChannelWeCom    = "wecom"
	ChannelFeishu   = "feishu"
	ChannelDingTalk = "dingtalk"
	ChannelDiscord  = "discord"
)

var (
	reTGUserID   = regexp.MustCompile(`^[1-9][0-9]*$`)
	reDiscordApp = regexp.MustCompile(`^[0-9]+$`)
	reDiscordHex = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)
	reWebhookDis = regexp.MustCompile(`^/api(?:/v\d+)?/webhooks/\d+/[^/]+$`)
	reWebhookFsh = regexp.MustCompile(`^/open-apis/bot/v2/hook/[^/]+$`)
)

// Config holds multi-channel bot and notification settings.
type Config struct {
	PublicURL         string `json:"public_url"`
	LinkTTLSeconds    int    `json:"link_ttl_seconds"`
	BotSources        string `json:"bot_sources"`
	TGAIEnabled       bool   `json:"tg_ai_enabled"`
	WeComAIEnabled    bool   `json:"wecom_ai_enabled"`
	BotAIAllowActions bool   `json:"bot_ai_allow_actions"`

	TGEnabled      bool   `json:"tg_enabled"`
	TGToken        string `json:"tg_token,omitempty"`
	TGAllowedUsers string `json:"tg_allowed_users"`
	TGProxy        string `json:"tg_proxy,omitempty"`
	TGProxyMode    string `json:"tg_proxy_mode"`
	TGAPIURL       string `json:"tg_api_url"`
	TGLocalEnabled bool   `json:"tg_local_enabled"`
	TGLocalAPIID   string `json:"tg_local_api_id"`
	TGLocalAPIHash string `json:"tg_local_api_hash,omitempty"`

	WeComEnabled      bool   `json:"wecom_enabled"`
	WeComCorpID       string `json:"wecom_corp_id"`
	WeComAgentID      string `json:"wecom_agent_id"`
	WeComSecret       string `json:"wecom_secret,omitempty"`
	WeComAPIURL       string `json:"wecom_api_url"`
	WeComToken        string `json:"wecom_token,omitempty"`
	WeComAESKey       string `json:"wecom_aes_key,omitempty"`
	WeComAllowedUsers string `json:"wecom_allowed_users"`

	FeishuEnabled           bool   `json:"feishu_enabled"`
	FeishuWebhook           string `json:"feishu_webhook,omitempty"`
	FeishuSignSecret        string `json:"feishu_sign_secret,omitempty"`
	FeishuBotEnabled        bool   `json:"feishu_bot_enabled"`
	FeishuAIEnabled         bool   `json:"feishu_ai_enabled"`
	FeishuAllowedUsers      string `json:"feishu_allowed_users"`
	FeishuAppID             string `json:"feishu_app_id"`
	FeishuAppSecret         string `json:"feishu_app_secret,omitempty"`
	FeishuVerificationToken string `json:"feishu_verification_token,omitempty"`
	FeishuEncryptKey        string `json:"feishu_encrypt_key,omitempty"`
	FeishuRegion            string `json:"feishu_region"`
	FeishuAPIURL            string `json:"feishu_api_url,omitempty"` // optional test override

	DingTalkEnabled      bool   `json:"dingtalk_enabled"`
	DingTalkWebhook      string `json:"dingtalk_webhook,omitempty"`
	DingTalkSignSecret   string `json:"dingtalk_sign_secret,omitempty"`
	DingTalkBotEnabled   bool   `json:"dingtalk_bot_enabled"`
	DingTalkAIEnabled    bool   `json:"dingtalk_ai_enabled"`
	DingTalkAllowedUsers string `json:"dingtalk_allowed_users"`
	DingTalkClientID     string `json:"dingtalk_client_id"`
	DingTalkClientSecret string `json:"dingtalk_client_secret,omitempty"`
	DingTalkRobotCode    string `json:"dingtalk_robot_code"`
	DingTalkAPIURL       string `json:"dingtalk_api_url,omitempty"` // optional test override

	DiscordEnabled       bool   `json:"discord_enabled"`
	DiscordWebhook       string `json:"discord_webhook,omitempty"`
	DiscordBotEnabled    bool   `json:"discord_bot_enabled"`
	DiscordAIEnabled     bool   `json:"discord_ai_enabled"`
	DiscordAllowedUsers  string `json:"discord_allowed_users"`
	DiscordApplicationID string `json:"discord_application_id"`
	DiscordPublicKey     string `json:"discord_public_key"`
	DiscordBotToken      string `json:"discord_bot_token,omitempty"`
	DiscordAPIURL        string `json:"discord_api_url,omitempty"` // optional test override

	// AllowTestLoopback disables strict public-URL scheme/host checks in unit tests only.
	AllowTestLoopback bool `json:"-"`
}

func DefaultConfig() Config {
	return Config{
		LinkTTLSeconds:    86400,
		BotAIAllowActions: true,
		TGProxyMode:       "direct",
		TGAPIURL:          "https://api.telegram.org",
		WeComAPIURL:       "https://qyapi.weixin.qq.com",
		FeishuRegion:      "feishu",
	}
}

// PublicConfig returns a redacted projection suitable for GET /api/bots/config.
func (c Config) PublicConfig(localStatus LocalBotAPIStatus) map[string]any {
	configured := map[string]bool{
		"tg_token":                  c.TGToken != "",
		"tg_proxy":                  c.TGProxy != "",
		"tg_local_api_hash":         c.TGLocalAPIHash != "",
		"wecom_secret":              c.WeComSecret != "",
		"wecom_token":               c.WeComToken != "",
		"wecom_aes_key":             c.WeComAESKey != "",
		"feishu_webhook":            c.FeishuWebhook != "",
		"feishu_sign_secret":        c.FeishuSignSecret != "",
		"feishu_app_secret":         c.FeishuAppSecret != "",
		"feishu_verification_token": c.FeishuVerificationToken != "",
		"feishu_encrypt_key":        c.FeishuEncryptKey != "",
		"dingtalk_webhook":          c.DingTalkWebhook != "",
		"dingtalk_sign_secret":      c.DingTalkSignSecret != "",
		"dingtalk_client_secret":    c.DingTalkClientSecret != "",
		"discord_webhook":           c.DiscordWebhook != "",
		"discord_bot_token":         c.DiscordBotToken != "",
	}
	callbackURL := ""
	if c.PublicURL != "" {
		callbackURL = strings.TrimRight(c.PublicURL, "/") + "/bot/wecom"
	}
	return map[string]any{
		"public_url":             c.PublicURL,
		"link_ttl_seconds":       c.LinkTTLSeconds,
		"bot_sources":            c.BotSources,
		"tg_ai_enabled":          c.TGAIEnabled,
		"wecom_ai_enabled":       c.WeComAIEnabled,
		"bot_ai_allow_actions":   c.BotAIAllowActions,
		"tg_enabled":             c.TGEnabled,
		"tg_allowed_users":       c.TGAllowedUsers,
		"tg_proxy_mode":          c.TGProxyMode,
		"tg_api_url":             c.TGAPIURL,
		"tg_local_enabled":       c.TGLocalEnabled,
		"tg_local_api_id":        c.TGLocalAPIID,
		"wecom_enabled":          c.WeComEnabled,
		"wecom_corp_id":          c.WeComCorpID,
		"wecom_agent_id":         c.WeComAgentID,
		"wecom_api_url":          c.WeComAPIURL,
		"wecom_allowed_users":    c.WeComAllowedUsers,
		"feishu_enabled":         c.FeishuEnabled,
		"feishu_bot_enabled":     c.FeishuBotEnabled,
		"feishu_ai_enabled":      c.FeishuAIEnabled,
		"feishu_allowed_users":   c.FeishuAllowedUsers,
		"feishu_app_id":          c.FeishuAppID,
		"feishu_region":          c.FeishuRegion,
		"dingtalk_enabled":       c.DingTalkEnabled,
		"dingtalk_bot_enabled":   c.DingTalkBotEnabled,
		"dingtalk_ai_enabled":    c.DingTalkAIEnabled,
		"dingtalk_allowed_users": c.DingTalkAllowedUsers,
		"dingtalk_client_id":     c.DingTalkClientID,
		"dingtalk_robot_code":    c.DingTalkRobotCode,
		"discord_enabled":        c.DiscordEnabled,
		"discord_bot_enabled":    c.DiscordBotEnabled,
		"discord_ai_enabled":     c.DiscordAIEnabled,
		"discord_allowed_users":  c.DiscordAllowedUsers,
		"discord_application_id": c.DiscordApplicationID,
		"discord_public_key":     c.DiscordPublicKey,
		"local_api_status":       localStatus,
		"configured":             configured,
		"status": map[string]bool{
			"tg":       c.TGEnabled && c.TGToken != "",
			"wecom":    c.WeComEnabled && c.WeComCorpID != "" && c.WeComSecret != "",
			"feishu":   c.FeishuEnabled && c.FeishuWebhook != "",
			"dingtalk": c.DingTalkEnabled && c.DingTalkWebhook != "",
			"discord":  c.DiscordEnabled && c.DiscordWebhook != "",
		},
		"callback_url": callbackURL,
	}
}

func sanitizeBotSecret(v string) string {
	trimmed := strings.TrimSpace(v)
	if trimmed == "" || trimmed == config.HiddenPlaceholder {
		return ""
	}
	return trimmed
}

// SanitizeSecrets clears blank or HiddenPlaceholder sentinel values stored mistakenly as literal secrets.
func SanitizeSecrets(c Config) Config {
	c.TGToken = sanitizeBotSecret(c.TGToken)
	c.TGProxy = sanitizeBotSecret(c.TGProxy)
	c.TGLocalAPIHash = sanitizeBotSecret(c.TGLocalAPIHash)
	c.WeComSecret = sanitizeBotSecret(c.WeComSecret)
	c.WeComToken = sanitizeBotSecret(c.WeComToken)
	c.WeComAESKey = sanitizeBotSecret(c.WeComAESKey)
	c.FeishuWebhook = sanitizeBotSecret(c.FeishuWebhook)
	c.FeishuSignSecret = sanitizeBotSecret(c.FeishuSignSecret)
	c.FeishuAppSecret = sanitizeBotSecret(c.FeishuAppSecret)
	c.FeishuVerificationToken = sanitizeBotSecret(c.FeishuVerificationToken)
	c.FeishuEncryptKey = sanitizeBotSecret(c.FeishuEncryptKey)
	c.DingTalkWebhook = sanitizeBotSecret(c.DingTalkWebhook)
	c.DingTalkSignSecret = sanitizeBotSecret(c.DingTalkSignSecret)
	c.DingTalkClientSecret = sanitizeBotSecret(c.DingTalkClientSecret)
	c.DiscordWebhook = sanitizeBotSecret(c.DiscordWebhook)
	c.DiscordBotToken = sanitizeBotSecret(c.DiscordBotToken)
	return c
}

func mergeBotSecret(existing, incoming string) string {
	ex := sanitizeBotSecret(existing)
	inc := sanitizeBotSecret(incoming)
	if inc == "" {
		return ex
	}
	return inc
}

// MergeConfig applies incoming updates while preserving blank or HiddenPlaceholder secrets and honoring clearSecrets.
func MergeConfig(existing, incoming Config, clearSecrets []string) Config {
	existing = SanitizeSecrets(existing)
	out := incoming
	out.TGToken = mergeBotSecret(existing.TGToken, incoming.TGToken)
	out.TGProxy = mergeBotSecret(existing.TGProxy, incoming.TGProxy)
	out.TGLocalAPIHash = mergeBotSecret(existing.TGLocalAPIHash, incoming.TGLocalAPIHash)
	out.WeComSecret = mergeBotSecret(existing.WeComSecret, incoming.WeComSecret)
	out.WeComToken = mergeBotSecret(existing.WeComToken, incoming.WeComToken)
	out.WeComAESKey = mergeBotSecret(existing.WeComAESKey, incoming.WeComAESKey)
	out.FeishuWebhook = mergeBotSecret(existing.FeishuWebhook, incoming.FeishuWebhook)
	out.FeishuSignSecret = mergeBotSecret(existing.FeishuSignSecret, incoming.FeishuSignSecret)
	out.FeishuAppSecret = mergeBotSecret(existing.FeishuAppSecret, incoming.FeishuAppSecret)
	out.FeishuVerificationToken = mergeBotSecret(existing.FeishuVerificationToken, incoming.FeishuVerificationToken)
	out.FeishuEncryptKey = mergeBotSecret(existing.FeishuEncryptKey, incoming.FeishuEncryptKey)
	out.DingTalkWebhook = mergeBotSecret(existing.DingTalkWebhook, incoming.DingTalkWebhook)
	out.DingTalkSignSecret = mergeBotSecret(existing.DingTalkSignSecret, incoming.DingTalkSignSecret)
	out.DingTalkClientSecret = mergeBotSecret(existing.DingTalkClientSecret, incoming.DingTalkClientSecret)
	out.DiscordWebhook = mergeBotSecret(existing.DiscordWebhook, incoming.DiscordWebhook)
	out.DiscordBotToken = mergeBotSecret(existing.DiscordBotToken, incoming.DiscordBotToken)

	for _, field := range clearSecrets {
		switch field {
		case "tg_token":
			out.TGToken = ""
		case "tg_proxy":
			out.TGProxy = ""
		case "tg_local_api_hash":
			out.TGLocalAPIHash = ""
		case "wecom_secret":
			out.WeComSecret = ""
		case "wecom_token":
			out.WeComToken = ""
		case "wecom_aes_key":
			out.WeComAESKey = ""
		case "feishu_webhook":
			out.FeishuWebhook = ""
		case "feishu_sign_secret":
			out.FeishuSignSecret = ""
		case "feishu_app_secret":
			out.FeishuAppSecret = ""
		case "feishu_verification_token":
			out.FeishuVerificationToken = ""
		case "feishu_encrypt_key":
			out.FeishuEncryptKey = ""
		case "dingtalk_webhook":
			out.DingTalkWebhook = ""
		case "dingtalk_sign_secret":
			out.DingTalkSignSecret = ""
		case "dingtalk_client_secret":
			out.DingTalkClientSecret = ""
		case "discord_webhook":
			out.DiscordWebhook = ""
		case "discord_bot_token":
			out.DiscordBotToken = ""
		}
	}
	return out
}

func ValidateOriginURL(raw string, requireHTTPS bool) (string, error) {
	v := strings.TrimSpace(raw)
	if v == "" {
		return "", nil
	}
	if !strings.Contains(v, "://") {
		v = "http://" + v
	}
	v = strings.TrimRight(v, "/")
	u, err := url.Parse(v)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" ||
		u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") ||
		strings.ContainsAny(v, " \t\r\n\\") {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "访问地址必须是 HTTP / HTTPS 域名或 IP，可带端口，不能包含路径、账号或查询参数")
	}
	if requireHTTPS && u.Scheme != "https" {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "该地址必须使用 HTTPS")
	}
	return u.Scheme + "://" + u.Host, nil
}

// ValidateConfig normalizes and checks channel configuration invariants.
func ValidateConfig(c Config) (Config, error) {
	c = SanitizeSecrets(c)
	if c.LinkTTLSeconds == 0 {
		c.LinkTTLSeconds = 86400
	}
	if c.LinkTTLSeconds < 60 || c.LinkTTLSeconds > 604800 {
		return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "下载链接有效期须为 60–604800 秒")
	}
	if c.TGProxyMode == "" {
		if c.TGProxy != "" {
			c.TGProxyMode = "custom"
		} else {
			c.TGProxyMode = "direct"
		}
	}
	if c.TGProxyMode != "direct" && c.TGProxyMode != "project" && c.TGProxyMode != "custom" {
		return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "无效的 Telegram 代理模式")
	}
	if c.FeishuRegion == "" {
		c.FeishuRegion = "feishu"
	}
	if c.FeishuRegion != "feishu" && c.FeishuRegion != "lark" {
		return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "飞书区域须为 feishu 或 lark")
	}
	if c.TGAPIURL == "" {
		c.TGAPIURL = "https://api.telegram.org"
	}
	var err error
	if c.TGAPIURL, err = ValidateOriginURL(c.TGAPIURL, false); err != nil {
		return Config{}, err
	}
	if c.WeComAPIURL == "" {
		c.WeComAPIURL = "https://qyapi.weixin.qq.com"
	}
	if c.WeComAPIURL, err = ValidateOriginURL(c.WeComAPIURL, !c.AllowTestLoopback); err != nil {
		return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "企业微信 API 转发地址必须为 HTTPS 域名或 IP")
	}
	if c.PublicURL != "" {
		if c.PublicURL, err = ValidateOriginURL(c.PublicURL, false); err != nil {
			return Config{}, err
		}
	}
	if c.TGEnabled {
		if strings.TrimSpace(c.TGToken) == "" {
			return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写 Telegram Bot Token")
		}
		if c.TGLocalEnabled {
			if _, _, err := ValidateLocalCredentials(c.TGLocalAPIID, c.TGLocalAPIHash); err != nil {
				return Config{}, err
			}
		}
		users := splitCSV(c.TGAllowedUsers)
		if len(users) == 0 {
			return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请配置 Telegram 数字用户 ID 白名单")
		}
		for _, u := range users {
			if !reTGUserID.MatchString(u) {
				return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Telegram 白名单必须为正整数用户 ID")
			}
		}
	}
	if c.WeComEnabled {
		if strings.TrimSpace(c.WeComCorpID) == "" || strings.TrimSpace(c.WeComSecret) == "" {
			return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "企业微信需要 CorpID、AgentID 和 Secret")
		}
		if _, err := strconv.Atoi(strings.TrimSpace(c.WeComAgentID)); err != nil {
			return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "企业微信 AgentID 必须为数字")
		}
		if !c.AllowTestLoopback && c.PublicURL == "" {
			return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "企业微信需要访问地址以生成消息回调 URL")
		}
		if strings.TrimSpace(c.WeComToken) == "" {
			return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请配置企业微信回调 Token")
		}
		for _, u := range splitCSV(c.WeComAllowedUsers) {
			if u == "downloads" {
				return Config{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "downloads 是通知队列保留标识，不能作为对话用户")
			}
		}
		if _, err := NewWeComCrypt(c.WeComToken, c.WeComAESKey, c.WeComCorpID); err != nil {
			return Config{}, err
		}
	}
	for _, ch := range []string{ChannelFeishu, ChannelDingTalk, ChannelDiscord} {
		if err := validateChannelBot(c, ch); err != nil {
			return Config{}, err
		}
	}
	if !c.AllowTestLoopback {
		if err := validateWebhookURL(ChannelDiscord, c.DiscordEnabled, c.DiscordWebhook); err != nil {
			return Config{}, err
		}
		if err := validateWebhookURL(ChannelFeishu, c.FeishuEnabled, c.FeishuWebhook); err != nil {
			return Config{}, err
		}
		if err := validateWebhookURL(ChannelDingTalk, c.DingTalkEnabled, c.DingTalkWebhook); err != nil {
			return Config{}, err
		}
	}
	return c, nil
}

func validateChannelBot(c Config, ch string) error {
	var enabled bool
	var allowed string
	switch ch {
	case ChannelFeishu:
		enabled, allowed = c.FeishuBotEnabled, c.FeishuAllowedUsers
	case ChannelDingTalk:
		enabled, allowed = c.DingTalkBotEnabled, c.DingTalkAllowedUsers
	case ChannelDiscord:
		enabled, allowed = c.DiscordBotEnabled, c.DiscordAllowedUsers
	}
	if !enabled {
		return nil
	}
	users := splitCSV(allowed)
	if (!c.AllowTestLoopback && c.PublicURL == "") || len(users) == 0 {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("%s 对话机器人需要公网访问地址和用户白名单", ch))
	}
	for _, u := range users {
		if u == "downloads" {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "downloads 是通知队列保留标识，不能作为对话用户")
		}
	}
	switch ch {
	case ChannelFeishu:
		if c.FeishuAppID == "" || c.FeishuAppSecret == "" || c.FeishuVerificationToken == "" || c.FeishuEncryptKey == "" {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "feishu 对话机器人凭据不完整")
		}
	case ChannelDingTalk:
		if c.DingTalkClientID == "" || c.DingTalkClientSecret == "" || c.DingTalkRobotCode == "" {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "dingtalk 对话机器人凭据不完整")
		}
	case ChannelDiscord:
		if !reDiscordApp.MatchString(c.DiscordApplicationID) || !reDiscordHex.MatchString(c.DiscordPublicKey) || c.DiscordBotToken == "" {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Discord 需要数字 Application ID、64 位十六进制 Public Key 与 Bot Token")
		}
		for _, u := range users {
			if !reDiscordApp.MatchString(u) {
				return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Discord 白名单需要数字用户 ID")
			}
		}
	}
	return nil
}

func validateWebhookURL(ch string, enabled bool, raw string) error {
	if !enabled {
		return nil
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Fragment != "" {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, ch+" Webhook 地址无效，请使用平台生成的 HTTPS 地址")
	}
	host := strings.ToLower(u.Hostname())
	switch ch {
	case ChannelDiscord:
		if (host != "discord.com" && host != "discordapp.com") || !reWebhookDis.MatchString(u.Path) {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "discord Webhook 地址无效")
		}
	case ChannelFeishu:
		if (host != "open.feishu.cn" && host != "open.larksuite.com") || !reWebhookFsh.MatchString(u.Path) {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "feishu Webhook 地址无效")
		}
	case ChannelDingTalk:
		if host != "oapi.dingtalk.com" || u.Path != "/robot/send" || u.Query().Get("access_token") == "" {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "dingtalk Webhook 地址无效")
		}
	}
	return nil
}

func splitCSV(raw string) []string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func (c Config) SecretValues() []string {
	vals := []string{
		c.TGToken, c.TGProxy, c.TGLocalAPIHash,
		c.WeComSecret, c.WeComToken, c.WeComAESKey,
		c.FeishuWebhook, c.FeishuSignSecret, c.FeishuAppSecret, c.FeishuVerificationToken, c.FeishuEncryptKey,
		c.DingTalkWebhook, c.DingTalkSignSecret, c.DingTalkClientSecret,
		c.DiscordWebhook, c.DiscordBotToken,
	}
	var out []string
	for _, v := range vals {
		if sv := sanitizeBotSecret(v); sv != "" {
			out = append(out, sv)
		}
	}
	return out
}

// InboundMessage represents a validated inbound message ready for command execution.
type InboundMessage struct {
	ID             string     `json:"id"`
	Channel        string     `json:"channel"`
	User           string     `json:"user"`
	PlatformMsgID  string     `json:"platform_msg_id"`
	Text           string     `json:"text"`
	ReplyMessageID *int64     `json:"reply_message_id,omitempty"`
	Image          *ImageRef  `json:"image,omitempty"`
	CreatedAt      int64      `json:"created_at"`
}

type ImageRef struct {
	ID        string `json:"id"`
	MessageID string `json:"message_id,omitempty"`
	Size      int64  `json:"size,omitempty"`
}

type OutboxPayload struct {
	Text          string             `json:"text"`
	URL           string             `json:"url,omitempty"`
	Buttons       [][]InlineButton   `json:"buttons,omitempty"`
	JobID         string             `json:"job_id,omitempty"`
	File          bool               `json:"file,omitempty"`
	CardKey       string             `json:"card_key,omitempty"`
	TaskCardID    string             `json:"task_card_id,omitempty"`
	EditMessageID *int64             `json:"edit_message_id,omitempty"`
	MediaID       string             `json:"media_id,omitempty"`
	MediaUntil    int64              `json:"media_until,omitempty"`
}

type InlineButton struct {
	Text         string `json:"text"`
	CallbackData string `json:"callback_data,omitempty"`
	URL          string `json:"url,omitempty"`
}

// Backend defines the injectable application services used by Bot commands.
// Bot never reads or writes sibling domain SQLite tables directly.
type Backend interface {
	SearchTracks(ctx context.Context, actor auth.Actor, keyword string, sources []string) ([]model.TrackDTO, bool, error)
	AvailableSources() []model.AvailableSourceDTO
	EnqueueDownload(ctx context.Context, actor auth.Actor, publicID, qualityID, idempotencyKey string) (model.DownloadJobDTO, error)
	GetDownload(ctx context.Context, actor auth.Actor, downloadID string) (model.DownloadJobDTO, error)
	ListDownloads(ctx context.Context, actor auth.Actor, limit int) ([]model.DownloadJobDTO, error)
	ControlDownload(ctx context.Context, actor auth.Actor, downloadID, action string) error
	QueryLibraryFiles(ctx context.Context, q store.LibraryFilesQuery) (model.LibraryFilesPageDTO, error)
	ResolveDownloadFile(ctx context.Context, relPath string) (string, int64, int64, error) // absPath, size, mtimeNS
	RunAICommand(ctx context.Context, actor auth.Actor, command, text string, imageBytes []byte) (string, error)
}

// StreamSearchBackend is an optional Backend extension providing incremental search updates
// and configured per-source pagination size (matching pre-rewrite source_catalog behavior).
type StreamSearchBackend interface {
	SearchTracksStream(ctx context.Context, actor auth.Actor, keyword string, sources []string, onTrack func(model.TrackDTO)) ([]model.TrackDTO, bool, error)
	ResultsPerSource() int
}
