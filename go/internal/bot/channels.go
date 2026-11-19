package bot

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/transport"
)

var reTGSizeErr = regexp.MustCompile(`(?i)error 413\b|file (?:is )?too (?:big|large)|(?:request entity|request payload|payload) too large`)

func IsTelegramSizeError(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ReplaceAll(err.Error(), "_", " ")
	return reTGSizeErr.MatchString(s)
}

type tokenEntry struct {
	token string
	until time.Time
}

type channelTransport struct {
	pool *transport.Pool

	mu         sync.Mutex
	tokens     map[string]tokenEntry
	dmChannels map[string]string
}

func newChannelTransport(pool *transport.Pool) *channelTransport {
	if pool == nil {
		pool = transport.NewPool()
	}
	return &channelTransport{
		pool:       pool,
		tokens:     make(map[string]tokenEntry),
		dmChannels: make(map[string]string),
	}
}

func (ct *channelTransport) clearToken(key string) {
	ct.mu.Lock()
	delete(ct.tokens, key)
	ct.mu.Unlock()
}

func (ct *channelTransport) doJSON(ctx context.Context, cfg Config, rev, method, rawURL string, headers http.Header, body any, proxyURL string, hasSideEffects bool) (int, []byte, error) {
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return 0, nil, err
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, rawURL, reader)
	if err != nil {
		return 0, nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, vals := range headers {
		for _, v := range vals {
			req.Header.Add(k, v)
		}
	}
	u, _ := url.Parse(rawURL)
	origin := ""
	if u != nil && u.Scheme != "" && u.Host != "" {
		origin = u.Scheme + "://" + u.Host
	}
	resp, err := ct.pool.Do(ctx, req, transport.RequestOptions{
		Domain:                 transport.DomainConfiguredInternal,
		ConfigRevision:         rev,
		ProxyURL:               proxyURL,
		Timeout:                30 * time.Second,
		MaxResponseBytes:       4 << 20,
		AllowedInternalOrigins: []string{origin},
		HasSideEffects:         hasSideEffects,
	})
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	return resp.StatusCode, raw, err
}

func (ct *channelTransport) telegramOrigin(cfg Config, local *LocalBotAPI) string {
	if cfg.TGLocalEnabled && local != nil {
		return local.URL()
	}
	if cfg.TGAPIURL != "" {
		return cfg.TGAPIURL
	}
	return "https://api.telegram.org"
}

func (ct *channelTransport) telegramProxy(cfg Config, origin, projectProxy string) string {
	u, err := url.Parse(origin)
	if err == nil {
		h := strings.ToLower(u.Hostname())
		if h == "127.0.0.1" || h == "localhost" || h == "::1" {
			return ""
		}
	}
	switch cfg.TGProxyMode {
	case "project":
		return strings.TrimSpace(projectProxy)
	case "custom":
		return strings.TrimSpace(cfg.TGProxy)
	default:
		return ""
	}
}

func (ct *channelTransport) callTelegram(ctx context.Context, cfg Config, rev string, local *LocalBotAPI, projectProxy, method string, body map[string]any, fileField, filePath, fileMIME string) (json.RawMessage, error) {
	token := sanitizeBotSecret(cfg.TGToken)
	if token == "" {
		return nil, fmt.Errorf("Telegram Bot Token 未配置或无效")
	}
	origin := ct.telegramOrigin(cfg, local)
	proxy := ct.telegramProxy(cfg, origin, projectProxy)
	targetURL := strings.TrimRight(origin, "/") + "/bot" + token + "/" + method

	var reqBody io.Reader
	contentType := "application/json"
	if fileField != "" && filePath != "" {
		pr, pw := io.Pipe()
		mw := multipart.NewWriter(pw)
		contentType = mw.FormDataContentType()
		go func() {
			var writeErr error
			defer func() {
				_ = mw.Close()
				if writeErr != nil {
					_ = pw.CloseWithError(writeErr)
				} else {
					_ = pw.Close()
				}
			}()
			for k, v := range body {
				var valStr string
				switch tv := v.(type) {
				case string:
					valStr = tv
				default:
					b, _ := json.Marshal(tv)
					valStr = string(b)
				}
				if err := mw.WriteField(k, valStr); err != nil {
					writeErr = err
					return
				}
			}
			h := make(textproto.MIMEHeader)
			h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="%s"; filename="%s"`, fileField, filepath.Base(filePath)))
			if fileMIME != "" {
				h.Set("Content-Type", fileMIME)
			} else {
				h.Set("Content-Type", "application/octet-stream")
			}
			part, err := mw.CreatePart(h)
			if err != nil {
				writeErr = err
				return
			}
			f, err := os.Open(filePath)
			if err != nil {
				writeErr = err
				return
			}
			defer f.Close()
			if _, err := io.Copy(part, f); err != nil {
				writeErr = err
				return
			}
		}()
		reqBody = pr
	} else {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reqBody = bytes.NewReader(b)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, targetURL, reqBody)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", contentType)
	u, _ := url.Parse(origin)
	reqTimeout := 30 * time.Second
	hdrTimeout := 25 * time.Second
	if fileField != "" && filePath != "" {
		reqTimeout = 10 * time.Minute
		hdrTimeout = 60 * time.Second
	} else if method == "getUpdates" {
		pollSec := 20
		if rawT, ok := body["timeout"]; ok {
			switch tv := rawT.(type) {
			case int:
				pollSec = tv
			case int64:
				pollSec = int(tv)
			case float64:
				pollSec = int(tv)
			}
		}
		if pollSec < 0 {
			pollSec = 0
		}
		reqTimeout = time.Duration(pollSec+15) * time.Second
		hdrTimeout = time.Duration(pollSec+10) * time.Second
	}
	resp, err := ct.pool.Do(ctx, req, transport.RequestOptions{
		Domain:                 transport.DomainConfiguredInternal,
		ConfigRevision:         rev,
		ProxyURL:               proxy,
		Timeout:                reqTimeout,
		ResponseHeaderTimeout:  hdrTimeout,
		MaxResponseBytes:       4 << 20,
		AllowedInternalOrigins: []string{u.Scheme + "://" + u.Host},
		HasSideEffects:         method != "getUpdates" && method != "getMe" && method != "getFile",
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusRequestEntityTooLarge {
		return nil, fmt.Errorf("Telegram API error 413: file too big")
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	var env struct {
		OK          bool            `json:"ok"`
		ErrorCode   int             `json:"error_code"`
		Description string          `json:"description"`
		Result      json.RawMessage `json:"result"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, fmt.Errorf("Telegram API error %d: invalid JSON", resp.StatusCode)
	}
	if !env.OK {
		if method == "editMessageText" && env.ErrorCode == 400 && strings.Contains(strings.ToLower(env.Description), "message is not modified") {
			return json.RawMessage(`true`), nil
		}
		return nil, fmt.Errorf("Telegram API error %d: %s", env.ErrorCode, env.Description)
	}
	return env.Result, nil
}

func (ct *channelTransport) wecomToken(ctx context.Context, cfg Config, rev string) (string, error) {
	ct.mu.Lock()
	ent, ok := ct.tokens["wecom:"+rev]
	if ok && time.Now().Before(ent.until) {
		ct.mu.Unlock()
		return ent.token, nil
	}
	ct.mu.Unlock()

	q := url.Values{"corpid": {cfg.WeComCorpID}, "corpsecret": {cfg.WeComSecret}}
	u := strings.TrimRight(cfg.WeComAPIURL, "/") + "/cgi-bin/gettoken?" + q.Encode()
	status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodGet, u, nil, nil, "", false)
	if err != nil {
		return "", err
	}
	if status != http.StatusOK {
		return "", fmt.Errorf("wecom token http %d", status)
	}
	var data struct {
		ErrCode     int    `json:"errcode"`
		ErrMsg      string `json:"errmsg"`
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(raw, &data); err != nil || data.ErrCode != 0 || data.AccessToken == "" {
		return "", fmt.Errorf("wecom token error %d: %s", data.ErrCode, data.ErrMsg)
	}
	ttl := data.ExpiresIn - 120
	if ttl < 1 {
		ttl = 1
	}
	ct.mu.Lock()
	ct.tokens["wecom:"+rev] = tokenEntry{token: data.AccessToken, until: time.Now().Add(time.Duration(ttl) * time.Second)}
	ct.mu.Unlock()
	return data.AccessToken, nil
}

func (ct *channelTransport) sendWeCom(ctx context.Context, cfg Config, rev, recipient string, payload OutboxPayload, filePath string) (string, error) {
	tok, err := ct.wecomToken(ctx, cfg, rev)
	if err != nil {
		return "", err
	}
	agentID, _ := strconv.Atoi(strings.TrimSpace(cfg.WeComAgentID))
	body := map[string]any{
		"touser":                 recipient,
		"agentid":                agentID,
		"enable_duplicate_check": 0,
	}
	if payload.File && filePath != "" {
		mediaID, err := ct.uploadWeComMedia(ctx, cfg, rev, tok, filePath)
		if err != nil {
			return "", err
		}
		body["msgtype"] = "file"
		body["file"] = map[string]string{"media_id": mediaID}
	} else if payload.URL != "" {
		body["msgtype"] = "news"
		body["news"] = map[string]any{
			"articles": []map[string]string{{
				"title":       "下载到手机",
				"description": payload.Text,
				"url":         payload.URL,
			}},
		}
	} else {
		body["msgtype"] = "text"
		body["text"] = map[string]string{"content": payload.Text}
	}
	u := strings.TrimRight(cfg.WeComAPIURL, "/") + "/cgi-bin/message/send?access_token=" + url.QueryEscape(tok)
	status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, u, nil, body, "", true)
	if err != nil {
		return "", err
	}
	if status != http.StatusOK {
		return "", fmt.Errorf("wecom send http %d", status)
	}
	var res struct {
		ErrCode     int    `json:"errcode"`
		ErrMsg      string `json:"errmsg"`
		MsgID       string `json:"msgid"`
		InvalidUser string `json:"invaliduser"`
	}
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", err
	}
	if res.ErrCode == 40014 || res.ErrCode == 42001 {
		ct.clearToken("wecom:" + rev)
	}
	if res.ErrCode != 0 || res.InvalidUser != "" {
		return "", fmt.Errorf("wecom send error %d: %s", res.ErrCode, res.ErrMsg)
	}
	receipt, _ := json.Marshal(map[string]any{"channel": "wecom", "msgid": res.MsgID})
	return string(receipt), nil
}

func (ct *channelTransport) uploadWeComMedia(ctx context.Context, cfg Config, rev, tok, filePath string) (string, error) {
	pr, pw := io.Pipe()
	mw := multipart.NewWriter(pw)
	go func() {
		var writeErr error
		defer func() {
			_ = mw.Close()
			if writeErr != nil {
				_ = pw.CloseWithError(writeErr)
			} else {
				_ = pw.Close()
			}
		}()
		part, err := mw.CreateFormFile("media", filepath.Base(filePath))
		if err != nil {
			writeErr = err
			return
		}
		f, err := os.Open(filePath)
		if err != nil {
			writeErr = err
			return
		}
		defer f.Close()
		_, writeErr = io.Copy(part, f)
	}()
	u := strings.TrimRight(cfg.WeComAPIURL, "/") + "/cgi-bin/media/upload?access_token=" + url.QueryEscape(tok) + "&type=file"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, pr)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	pu, _ := url.Parse(cfg.WeComAPIURL)
	resp, err := ct.pool.Do(ctx, req, transport.RequestOptions{
		Domain:                 transport.DomainConfiguredInternal,
		ConfigRevision:         rev,
		Timeout:                120 * time.Second,
		MaxResponseBytes:       2 << 20,
		AllowedInternalOrigins: []string{pu.Scheme + "://" + pu.Host},
		HasSideEffects:         true,
	})
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	var res struct {
		ErrCode int    `json:"errcode"`
		ErrMsg  string `json:"errmsg"`
		MediaID string `json:"media_id"`
	}
	if err := json.Unmarshal(raw, &res); err != nil || res.ErrCode != 0 || res.MediaID == "" {
		return "", fmt.Errorf("wecom media upload error %d: %s", res.ErrCode, res.ErrMsg)
	}
	return res.MediaID, nil
}

func (ct *channelTransport) feishuOrigin(cfg Config) string {
	if cfg.FeishuAPIURL != "" {
		return strings.TrimRight(cfg.FeishuAPIURL, "/")
	}
	if cfg.FeishuRegion == "lark" {
		return "https://open.larksuite.com"
	}
	return "https://open.feishu.cn"
}

func (ct *channelTransport) feishuToken(ctx context.Context, cfg Config, rev string) (string, error) {
	ct.mu.Lock()
	ent, ok := ct.tokens["feishu:"+rev]
	if ok && time.Now().Before(ent.until) {
		ct.mu.Unlock()
		return ent.token, nil
	}
	ct.mu.Unlock()

	u := ct.feishuOrigin(cfg) + "/open-apis/auth/v3/tenant_access_token/internal"
	status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, u, nil, map[string]string{
		"app_id":     cfg.FeishuAppID,
		"app_secret": cfg.FeishuAppSecret,
	}, "", false)
	if err != nil || status != http.StatusOK {
		return "", fmt.Errorf("feishu token failed: status=%d err=%v", status, err)
	}
	var res struct {
		Code  int    `json:"code"`
		Token string `json:"tenant_access_token"`
		Exp   int    `json:"expire"`
	}
	if err := json.Unmarshal(raw, &res); err != nil || res.Code != 0 || res.Token == "" {
		return "", fmt.Errorf("feishu token error code=%d", res.Code)
	}
	ttl := res.Exp - 120
	if ttl < 1 {
		ttl = 1
	}
	ct.mu.Lock()
	ct.tokens["feishu:"+rev] = tokenEntry{token: res.Token, until: time.Now().Add(time.Duration(ttl) * time.Second)}
	ct.mu.Unlock()
	return res.Token, nil
}

func (ct *channelTransport) dingTalkOrigin(cfg Config) string {
	if cfg.DingTalkAPIURL != "" {
		return strings.TrimRight(cfg.DingTalkAPIURL, "/")
	}
	return "https://api.dingtalk.com"
}

func (ct *channelTransport) dingTalkToken(ctx context.Context, cfg Config, rev string) (string, error) {
	ct.mu.Lock()
	ent, ok := ct.tokens["dingtalk:"+rev]
	if ok && time.Now().Before(ent.until) {
		ct.mu.Unlock()
		return ent.token, nil
	}
	ct.mu.Unlock()

	u := ct.dingTalkOrigin(cfg) + "/v1.0/oauth2/accessToken"
	status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, u, nil, map[string]string{
		"appKey":    cfg.DingTalkClientID,
		"appSecret": cfg.DingTalkClientSecret,
	}, "", false)
	if err != nil || status != http.StatusOK {
		return "", fmt.Errorf("dingtalk token failed: status=%d err=%v", status, err)
	}
	var res struct {
		AccessToken string `json:"accessToken"`
		ExpireIn    int    `json:"expireIn"`
	}
	if err := json.Unmarshal(raw, &res); err != nil || res.AccessToken == "" {
		return "", fmt.Errorf("dingtalk token invalid")
	}
	ttl := res.ExpireIn - 120
	if ttl < 1 {
		ttl = 1
	}
	ct.mu.Lock()
	ct.tokens["dingtalk:"+rev] = tokenEntry{token: res.AccessToken, until: time.Now().Add(time.Duration(ttl) * time.Second)}
	ct.mu.Unlock()
	return res.AccessToken, nil
}

func (ct *channelTransport) discordOrigin(cfg Config) string {
	if cfg.DiscordAPIURL != "" {
		return strings.TrimRight(cfg.DiscordAPIURL, "/")
	}
	return "https://discord.com"
}

func (ct *channelTransport) sendWebhookOrDM(ctx context.Context, cfg Config, rev, channel, recipient, outboxID, projectProxy string, payload OutboxPayload) (string, error) {
	text := payload.Text
	if payload.URL != "" {
		text += "\n" + payload.URL
	}
	if recipient == "downloads" {
		var hookURL, secret string
		var body map[string]any
		switch channel {
		case ChannelDiscord:
			hookURL = cfg.DiscordWebhook
			if !strings.Contains(hookURL, "wait=") {
				if strings.Contains(hookURL, "?") {
					hookURL += "&wait=true"
				} else {
					hookURL += "?wait=true"
				}
			}
			body = map[string]any{"content": "MeloKeep · " + text, "allowed_mentions": map[string]any{"parse": []string{}}}
		case ChannelFeishu:
			hookURL, secret = cfg.FeishuWebhook, cfg.FeishuSignSecret
			body = map[string]any{"msg_type": "text", "content": map[string]string{"text": "MeloKeep · " + text}}
			if secret != "" {
				ts := strconv.FormatInt(time.Now().Unix(), 10)
				key := []byte(ts + "\n" + secret)
				mac := hmac.New(sha256.New, key)
				body["timestamp"] = ts
				body["sign"] = base64.StdEncoding.EncodeToString(mac.Sum(nil))
			}
		case ChannelDingTalk:
			hookURL, secret = cfg.DingTalkWebhook, cfg.DingTalkSignSecret
			body = map[string]any{"msgtype": "text", "text": map[string]string{"content": "MeloKeep · " + text}}
			if secret != "" {
				ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
				mac := hmac.New(sha256.New, []byte(secret))
				_, _ = mac.Write([]byte(ts + "\n" + secret))
				sig := base64.StdEncoding.EncodeToString(mac.Sum(nil))
				sep := "?"
				if strings.Contains(hookURL, "?") {
					sep = "&"
				}
				hookURL += sep + "timestamp=" + url.QueryEscape(ts) + "&sign=" + url.QueryEscape(sig)
			}
		}
		reqProxy := ""
		if channel == ChannelDiscord {
			reqProxy = projectProxy
		}
		status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, hookURL, nil, body, reqProxy, true)
		if err != nil {
			return "", err
		}
		if status < 200 || status >= 300 {
			return "", fmt.Errorf("%s webhook status %d", channel, status)
		}
		if len(raw) > 0 {
			var check map[string]any
			if err := json.Unmarshal(raw, &check); err == nil {
				if code, ok := check["errcode"].(float64); ok && code != 0 {
					return "", fmt.Errorf("%s webhook errcode %v", channel, code)
				}
				if code, ok := check["code"].(float64); ok && code != 0 {
					return "", fmt.Errorf("%s webhook code %v", channel, code)
				}
			}
		}
		return string(raw), nil
	}

	switch channel {
	case ChannelFeishu:
		tok, err := ct.feishuToken(ctx, cfg, rev)
		if err != nil {
			return "", err
		}
		hdr := http.Header{"Authorization": {"Bearer " + tok}}
		contentJSON, _ := json.Marshal(map[string]string{"text": text})
		u := ct.feishuOrigin(cfg) + "/open-apis/im/v1/messages?receive_id_type=open_id"
		status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, u, hdr, map[string]any{
			"receive_id": recipient,
			"msg_type":   "text",
			"content":    string(contentJSON),
			"uuid":       outboxID,
		}, "", true)
		if err != nil || status != http.StatusOK {
			ct.clearToken("feishu:" + rev)
			return "", fmt.Errorf("feishu send status=%d err=%v", status, err)
		}
		return string(raw), nil

	case ChannelDingTalk:
		tok, err := ct.dingTalkToken(ctx, cfg, rev)
		if err != nil {
			return "", err
		}
		hdr := http.Header{"x-acs-dingtalk-access-token": {tok}}
		paramJSON, _ := json.Marshal(map[string]string{"content": text})
		u := ct.dingTalkOrigin(cfg) + "/v1.0/robot/oToMessages/batchSend"
		status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, u, hdr, map[string]any{
			"robotCode": cfg.DingTalkRobotCode,
			"userIds":   []string{recipient},
			"msgKey":    "sampleText",
			"msgParam":  string(paramJSON),
		}, "", true)
		if err != nil || status != http.StatusOK {
			ct.clearToken("dingtalk:" + rev)
			return "", fmt.Errorf("dingtalk send status=%d err=%v", status, err)
		}
		return string(raw), nil

	case ChannelDiscord:
		hdr := http.Header{"Authorization": {"Bot " + cfg.DiscordBotToken}}
		ct.mu.Lock()
		dmID := ct.dmChannels[recipient]
		ct.mu.Unlock()
		if dmID == "" {
			u := ct.discordOrigin(cfg) + "/api/v10/users/@me/channels"
			status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, u, hdr, map[string]string{"recipient_id": recipient}, projectProxy, false)
			if err != nil || status < 200 || status >= 300 {
				return "", fmt.Errorf("discord create dm status=%d err=%v", status, err)
			}
			var dm struct {
				ID string `json:"id"`
			}
			if err := json.Unmarshal(raw, &dm); err != nil || dm.ID == "" {
				return "", fmt.Errorf("discord invalid dm response")
			}
			dmID = dm.ID
			ct.mu.Lock()
			ct.dmChannels[recipient] = dmID
			ct.mu.Unlock()
		}
		nonce := outboxID
		if len(nonce) > 24 {
			nonce = nonce[:24]
		}
		u := ct.discordOrigin(cfg) + "/api/v10/channels/" + url.PathEscape(dmID) + "/messages"
		status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, u, hdr, map[string]any{
			"content":          text,
			"allowed_mentions": map[string]any{"parse": []string{}},
			"nonce":            nonce,
			"enforce_nonce":    true,
		}, projectProxy, true)
		if err != nil || status < 200 || status >= 300 {
			return "", fmt.Errorf("discord send status=%d err=%v", status, err)
		}
		return string(raw), nil
	}
	return "", fmt.Errorf("unsupported channel %s", channel)
}

func (ct *channelTransport) fetchTelegramFile(ctx context.Context, cfg Config, rev string, local *LocalBotAPI, projectProxy, filePath string, maxBytes int64) ([]byte, error) {
	token := sanitizeBotSecret(cfg.TGToken)
	if token == "" {
		return nil, fmt.Errorf("Telegram Bot Token 未配置或无效")
	}
	origin := ct.telegramOrigin(cfg, local)
	proxy := ct.telegramProxy(cfg, origin, projectProxy)
	cleanPath := strings.TrimPrefix(strings.ReplaceAll(filePath, "\\", "/"), "/")
	targetURL := strings.TrimRight(origin, "/") + "/file/bot" + token + "/" + cleanPath
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
	if err != nil {
		return nil, err
	}
	u, _ := url.Parse(origin)
	resp, err := ct.pool.Do(ctx, req, transport.RequestOptions{
		Domain:                 transport.DomainConfiguredInternal,
		ConfigRevision:         rev,
		ProxyURL:               proxy,
		Timeout:                30 * time.Second,
		MaxResponseBytes:       maxBytes,
		AllowedInternalOrigins: []string{u.Scheme + "://" + u.Host},
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Telegram file http %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))
}

func (ct *channelTransport) fetchWeComMedia(ctx context.Context, cfg Config, rev, mediaID string, maxBytes int64) ([]byte, error) {
	tok, err := ct.wecomToken(ctx, cfg, rev)
	if err != nil {
		return nil, err
	}
	u := strings.TrimRight(cfg.WeComAPIURL, "/") + "/cgi-bin/media/get?access_token=" + url.QueryEscape(tok) + "&media_id=" + url.QueryEscape(mediaID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	pu, _ := url.Parse(cfg.WeComAPIURL)
	resp, err := ct.pool.Do(ctx, req, transport.RequestOptions{
		Domain:                 transport.DomainConfiguredInternal,
		ConfigRevision:         rev,
		Timeout:                30 * time.Second,
		MaxResponseBytes:       maxBytes,
		AllowedInternalOrigins: []string{pu.Scheme + "://" + pu.Host},
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("wecom media http %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))
}

func (ct *channelTransport) fetchInboundChannelImage(ctx context.Context, cfg Config, rev, channel string, img ImageRef, projectProxy string, maxBytes int64) ([]byte, error) {
	switch channel {
	case ChannelFeishu:
		tok, err := ct.feishuToken(ctx, cfg, rev)
		if err != nil {
			return nil, err
		}
		origin := ct.feishuOrigin(cfg)
		u := origin + "/open-apis/im/v1/messages/" + url.PathEscape(img.MessageID) + "/resources/" + url.PathEscape(img.ID) + "?type=image"
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+tok)
		ou, _ := url.Parse(origin)
		resp, err := ct.pool.Do(ctx, req, transport.RequestOptions{
			Domain:                 transport.DomainConfiguredInternal,
			ConfigRevision:         rev,
			Timeout:                30 * time.Second,
			MaxResponseBytes:       maxBytes,
			AllowedInternalOrigins: []string{ou.Scheme + "://" + ou.Host},
		})
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("feishu image http %d", resp.StatusCode)
		}
		return io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))

	case ChannelDingTalk:
		tok, err := ct.dingTalkToken(ctx, cfg, rev)
		if err != nil {
			return nil, err
		}
		hdr := http.Header{"x-acs-dingtalk-access-token": {tok}}
		u := ct.dingTalkOrigin(cfg) + "/v1.0/robot/messageFiles/download"
		status, raw, err := ct.doJSON(ctx, cfg, rev, http.MethodPost, u, hdr, map[string]string{
			"robotCode":    cfg.DingTalkRobotCode,
			"downloadCode": img.ID,
		}, "", false)
		if err != nil || status != http.StatusOK {
			return nil, fmt.Errorf("dingtalk download url failed: status=%d err=%v", status, err)
		}
		var res struct {
			DownloadURL string `json:"downloadUrl"`
		}
		if err := json.Unmarshal(raw, &res); err != nil || res.DownloadURL == "" {
			return nil, fmt.Errorf("dingtalk missing downloadUrl")
		}
		du, err := url.Parse(res.DownloadURL)
		if err != nil || strings.ToLower(du.Scheme) != "https" {
			return nil, fmt.Errorf("图片下载地址必须为 HTTPS")
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, res.DownloadURL, nil)
		if err != nil {
			return nil, err
		}
		resp, err := ct.pool.Do(ctx, req, transport.RequestOptions{
			Domain:           transport.DomainPublicMedia,
			ConfigRevision:   rev,
			ProxyURL:         projectProxy,
			Timeout:          30 * time.Second,
			MaxResponseBytes: maxBytes,
		})
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("dingtalk image http %d", resp.StatusCode)
		}
		return io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))

	case ChannelDiscord:
		du, err := url.Parse(img.ID)
		if err != nil || strings.ToLower(du.Scheme) != "https" ||
			(strings.ToLower(du.Hostname()) != "cdn.discordapp.com" && strings.ToLower(du.Hostname()) != "media.discordapp.net") ||
			!strings.HasPrefix(du.Path, "/attachments/") {
			return nil, fmt.Errorf("Discord 图片地址无效")
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, img.ID, nil)
		if err != nil {
			return nil, err
		}
		resp, err := ct.pool.Do(ctx, req, transport.RequestOptions{
			Domain:           transport.DomainPublicMedia,
			ConfigRevision:   rev,
			ProxyURL:         projectProxy,
			Timeout:          30 * time.Second,
			MaxResponseBytes: maxBytes,
		})
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("discord image http %d", resp.StatusCode)
		}
		return io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))
	}
	return nil, fmt.Errorf("unsupported image channel %s", channel)
}
