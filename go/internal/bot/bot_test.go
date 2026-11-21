package bot

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

func newBotTestStore(t *testing.T) *store.Store {
	t.Helper()
	st, err := store.Open(context.Background(), store.Options{
		Path: filepath.Join(t.TempDir(), "melokeep.db"),
	})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

type mockBackend struct {
	mu           sync.Mutex
	searchTracks []model.TrackDTO
	searchCalls  int
	jobs         map[string]model.DownloadJobDTO
	files        map[string]string // relPath -> absPath
	controlled   []string
	aiCalls      []string
	lastAIActor  auth.Actor
	libPage      model.LibraryFilesPageDTO
}

func newMockBackend() *mockBackend {
	return &mockBackend{
		jobs:  make(map[string]model.DownloadJobDTO),
		files: make(map[string]string),
	}
}

func (m *mockBackend) SearchTracks(_ context.Context, _ auth.Actor, keyword string, _ []string) ([]model.TrackDTO, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.searchCalls++
	out := make([]model.TrackDTO, len(m.searchTracks))
	copy(out, m.searchTracks)
	return out, false, nil
}

func (m *mockBackend) AvailableSources() []model.AvailableSourceDTO { return nil }

func (m *mockBackend) EnqueueDownload(_ context.Context, _ auth.Actor, publicID, qualityID, _ string) (model.DownloadJobDTO, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id := fmt.Sprintf("aabbccdd%08x", len(m.jobs)+1)
	job := model.DownloadJobDTO{
		ID:        id,
		QualityID: qualityID,
		Status:    "queued",
		Track: model.TrackDTO{
			ID:    publicID,
			Title: "测试歌曲-" + publicID,
		},
	}
	m.jobs[id] = job
	return job, nil
}

func (m *mockBackend) GetDownload(_ context.Context, _ auth.Actor, downloadID string) (model.DownloadJobDTO, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	j, ok := m.jobs[downloadID]
	if !ok {
		return model.DownloadJobDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "not found")
	}
	return j, nil
}

func (m *mockBackend) ListDownloads(_ context.Context, _ auth.Actor, _ int) ([]model.DownloadJobDTO, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []model.DownloadJobDTO
	for _, j := range m.jobs {
		out = append(out, j)
	}
	return out, nil
}

func (m *mockBackend) ControlDownload(_ context.Context, _ auth.Actor, downloadID, action string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.controlled = append(m.controlled, action+":"+downloadID)
	return nil
}

func (m *mockBackend) QueryLibraryFiles(_ context.Context, _ store.LibraryFilesQuery) (model.LibraryFilesPageDTO, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.libPage, nil
}

func (m *mockBackend) ResolveDownloadFile(_ context.Context, relPath string) (string, int64, int64, error) {
	m.mu.Lock()
	abs, ok := m.files[relPath]
	m.mu.Unlock()
	if !ok {
		return "", 0, 0, model.NewError(model.ErrNotFound, http.StatusNotFound, "file not found")
	}
	st, err := os.Stat(abs)
	if err != nil {
		return "", 0, 0, err
	}
	return abs, st.Size(), st.ModTime().UnixNano(), nil
}

func (m *mockBackend) RunAICommand(_ context.Context, actor auth.Actor, command, text string, _ []byte) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.lastAIActor = actor
	m.aiCalls = append(m.aiCalls, command+":"+text)
	return "AI回复:" + text, nil
}

func encryptFeishuTest(encryptKey string, plaintext []byte) string {
	key := sha256.Sum256([]byte(encryptKey))
	block, _ := aes.NewCipher(key[:])
	iv := make([]byte, aes.BlockSize)
	_, _ = rand.Read(iv)
	pad := aes.BlockSize - (len(plaintext) % aes.BlockSize)
	padded := append([]byte{}, plaintext...)
	for i := 0; i < pad; i++ {
		padded = append(padded, byte(pad))
	}
	ct := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(ct, padded)
	return base64.StdEncoding.EncodeToString(append(iv, ct...))
}

func TestConfigValidationMergeAndRedaction(t *testing.T) {
	st := newBotTestStore(t)
	ctx := context.Background()

	badTTL := DefaultConfig()
	badTTL.LinkTTLSeconds = 10
	if _, err := ValidateConfig(badTTL); err == nil {
		t.Fatal("expected LinkTTLSeconds < 60 to fail")
	}

	badWeCom := DefaultConfig()
	badWeCom.WeComAPIURL = "http://qyapi.weixin.qq.com"
	if _, err := ValidateConfig(badWeCom); err == nil {
		t.Fatal("expected non-HTTPS WeComAPIURL to fail when AllowTestLoopback=false")
	}

	badTG := DefaultConfig()
	badTG.TGEnabled = true
	badTG.TGToken = "12345:secret-token"
	badTG.TGAllowedUsers = "not-a-number"
	if _, err := ValidateConfig(badTG); err == nil {
		t.Fatal("expected non-numeric TGAllowedUsers to fail")
	}

	reserved := DefaultConfig()
	reserved.AllowTestLoopback = true
	reserved.FeishuBotEnabled = true
	reserved.FeishuAllowedUsers = "downloads"
	reserved.FeishuAppID = "app"
	reserved.FeishuAppSecret = "sec"
	reserved.FeishuVerificationToken = "tok"
	reserved.FeishuEncryptKey = "enc"
	if _, err := ValidateConfig(reserved); err == nil {
		t.Fatal("expected reserved 'downloads' in FeishuAllowedUsers to fail")
	}

	initCfg := DefaultConfig()
	initCfg.TGEnabled = true
	initCfg.TGToken = "tg-secret-123"
	initCfg.TGAllowedUsers = "1001"
	initCfg.DiscordEnabled = true
	initCfg.DiscordWebhook = "https://discord.com/api/webhooks/12345/secret-hook"
	svc, err := NewService(ctx, ServiceOptions{Store: st, Initial: initCfg})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	defer svc.Close()

	if _, err := svc.UpdateConfig(ctx, auth.Actor{}, initCfg, nil); err == nil {
		t.Fatal("expected non-admin UpdateConfig to fail")
	}

	// Merge preserves HiddenPlaceholder and clears explicit clearSecrets.
	admin := auth.WebAdminActor("admin")
	incoming := svc.Config()
	incoming.TGToken = config.HiddenPlaceholder
	incoming.DiscordWebhook = config.HiddenPlaceholder
	preserved := MergeConfig(svc.Config(), incoming, nil)
	if preserved.DiscordWebhook != initCfg.DiscordWebhook {
		t.Fatalf("expected DiscordWebhook preserved via placeholder, got %q", preserved.DiscordWebhook)
	}
	incoming.DiscordEnabled = false
	incoming.PublicURL = "https://music.example.com"
	pub, err := svc.UpdateConfig(ctx, admin, incoming, []string{"discord_webhook"})
	if err != nil {
		t.Fatalf("UpdateConfig: %v", err)
	}
	if svc.Config().TGToken != "tg-secret-123" {
		t.Fatalf("expected TGToken preserved, got %q", svc.Config().TGToken)
	}
	if svc.Config().DiscordWebhook != "" {
		t.Fatalf("expected DiscordWebhook cleared, got %q", svc.Config().DiscordWebhook)
	}
	if pub["callback_url"] != "https://music.example.com/bot/wecom" {
		t.Fatalf("unexpected callback_url: %v", pub["callback_url"])
	}
	rawPub, _ := json.Marshal(pub)
	if strings.Contains(string(rawPub), "tg-secret-123") {
		t.Fatal("PublicConfig leaked TGToken")
	}
	redacted := svc.RedactError(errors.New("failed with tg-secret-123"))
	if strings.Contains(redacted, "tg-secret-123") || !strings.Contains(redacted, config.HiddenPlaceholder) {
		t.Fatalf("RedactError failed: %q", redacted)
	}
}

func TestChannelCryptoSignaturesAndDTD(t *testing.T) {
	now := time.Unix(1700000000, 0)

	// 1. XML DTD / Entity rejection and oversized payload rejection.
	if _, err := ParseXMLFields([]byte(`<!DOCTYPE foo><xml><Content>hi</Content></xml>`)); err == nil {
		t.Fatal("expected <!DOCTYPE rejection")
	}
	if _, err := ParseXMLFields([]byte(`<!ENTITY xxe SYSTEM "file:///etc/passwd"><xml><Content>hi</Content></xml>`)); err == nil {
		t.Fatal("expected <!ENTITY rejection")
	}
	bigXML := make([]byte, MaxCallbackBodyBytes+1)
	if _, err := ParseXMLFields(bigXML); err == nil {
		t.Fatal("expected oversized XML rejection")
	}
	parsed, err := ParseXMLFields([]byte(`<xml><ToUserName><![CDATA[corp1]]></ToUserName><MsgType>text</MsgType></xml>`))
	if err != nil || parsed["ToUserName"] != "corp1" || parsed["MsgType"] != "text" {
		t.Fatalf("unexpected XML parse: %+v err=%v", parsed, err)
	}

	// 2. WeCom crypt.
	aesKey := strings.Repeat("a", 43)
	wc, err := NewWeComCrypt("tok123", aesKey, "corp1")
	if err != nil {
		t.Fatalf("NewWeComCrypt: %v", err)
	}
	var prefix [16]byte
	enc, err := wc.EncryptForTest([]byte("hello-wecom"), prefix)
	if err != nil {
		t.Fatalf("EncryptForTest: %v", err)
	}
	sig := WeComSignature("tok123", "1700000000", "n1", enc)
	plain, err := wc.Decrypt(enc, sig, "1700000000", "n1")
	if err != nil || string(plain) != "hello-wecom" {
		t.Fatalf("WeCom Decrypt failed: %q err=%v", string(plain), err)
	}
	if _, err := wc.Decrypt(enc, "bad", "1700000000", "n1"); err == nil {
		t.Fatal("expected bad WeCom signature rejection")
	}
	wcOther, _ := NewWeComCrypt("tok123", aesKey, "corp2")
	if _, err := wcOther.Decrypt(enc, sig, "1700000000", "n1"); err == nil {
		t.Fatal("expected mismatched CorpID rejection")
	}

	// 3. Feishu encrypt & signature.
	feishuBody := []byte(`{"encrypt":"abc"}`)
	ts := strconv.FormatInt(now.Unix(), 10)
	h := sha256.New()
	_, _ = h.Write([]byte(ts + "nonce1" + "feishu-key"))
	_, _ = h.Write(feishuBody)
	fSig := hex.EncodeToString(h.Sum(nil))
	if err := VerifyFeishuSignature(ts, "nonce1", "feishu-key", fSig, feishuBody, now); err != nil {
		t.Fatalf("VerifyFeishuSignature: %v", err)
	}
	if err := VerifyFeishuSignature(strconv.FormatInt(now.Unix()-601, 10), "nonce1", "feishu-key", fSig, feishuBody, now); err == nil {
		t.Fatal("expected expired Feishu signature rejection")
	}
	ct := encryptFeishuTest("feishu-key", []byte(`{"challenge":"ok"}`))
	pt, err := DecryptFeishuPayload("feishu-key", ct)
	if err != nil || string(pt) != `{"challenge":"ok"}` {
		t.Fatalf("DecryptFeishuPayload failed: %q err=%v", string(pt), err)
	}

	// 4. DingTalk ms timestamp + HMAC.
	tsMs := strconv.FormatInt(now.UnixMilli(), 10)
	st := newBotTestStore(t)
	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.DingTalkBotEnabled = true
	cfg.DingTalkAllowedUsers = "staff1"
	cfg.DingTalkClientID = "cid"
	cfg.DingTalkClientSecret = "dt-secret"
	cfg.DingTalkRobotCode = "rc1"
	svc, err := NewService(context.Background(), ServiceOptions{
		Store:   st,
		Initial: cfg,
		Now:     func() time.Time { return now },
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	defer svc.Close()
	// Build valid DingTalk signature via crypto/hmac indirectly through HandleDingTalkCallback negative + positive check.
	if _, err := svc.HandleDingTalkCallback(context.Background(), []byte(`{}`), strconv.FormatInt(now.Add(-11*time.Minute).UnixMilli(), 10), "bad"); err == nil {
		t.Fatal("expected expired DingTalk callback rejection")
	}
	mac := hmac.New(sha256.New, []byte("dt-secret"))
	_, _ = mac.Write([]byte(tsMs + "\ndt-secret"))
	dtSig := base64.StdEncoding.EncodeToString(mac.Sum(nil))
	dtBody := []byte(`{"robotCode":"rc1","conversationType":"1","msgtype":"text","senderStaffId":"staff1","msgId":"dt-1","text":{"content":"/help"}}`)
	dtRes, err := svc.HandleDingTalkCallback(context.Background(), dtBody, tsMs, dtSig)
	if err != nil || dtRes["errmsg"] != "ok" {
		t.Fatalf("HandleDingTalkCallback valid failed: %+v err=%v", dtRes, err)
	}
	if _, err := svc.HandleDingTalkCallback(context.Background(), dtBody, tsMs, "bad-sig"); err == nil {
		t.Fatal("expected bad DingTalk signature rejection")
	}
	// Also verify VerifyDiscordSignature with ed25519 keypair.
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dBody := []byte(`{"id":"1","application_id":"999","type":1}`)
	dSig := hex.EncodeToString(ed25519.Sign(priv, append([]byte(ts), dBody...)))
	if err := VerifyDiscordSignature(hex.EncodeToString(pub), ts, dSig, dBody, now); err != nil {
		t.Fatalf("VerifyDiscordSignature: %v", err)
	}
	if err := VerifyDiscordSignature(hex.EncodeToString(pub), ts, dSig, []byte(`tampered`), now); err == nil {
		t.Fatal("expected tampered Discord body rejection")
	}
}

func TestInboundCallbacksDedupOwnershipLibraryAndRateLimit(t *testing.T) {
	ctx := context.Background()
	now := time.Unix(1700001000, 0)
	var mu sync.Mutex
	curTime := now
	nowFn := func() time.Time {
		mu.Lock()
		defer mu.Unlock()
		return curTime
	}
	advance := func(d time.Duration) {
		mu.Lock()
		curTime = curTime.Add(d)
		mu.Unlock()
	}

	st := newBotTestStore(t)
	be := newMockBackend()
	be.searchTracks = make([]model.TrackDTO, 10)
	for i := 0; i < 10; i++ {
		be.searchTracks[i] = model.TrackDTO{
			ID:     fmt.Sprintf("mock_alpha:s%d", i+1),
			Source: "mock_alpha",
			Title:  fmt.Sprintf("Morning Breeze %d", i+1),
			Artist: "Open Horizon",
			Qualities: []model.QualityDTO{
				{ID: "flac", Label: "FLAC"},
			},
		}
	}
	be.libPage = model.LibraryFilesPageDTO{
		IndexRevision: 7,
		Items: []model.LibraryFileDTO{
			{Path: "Open Horizon/Morning Breeze.flac", Name: "Morning Breeze.flac", Size: 1024},
		},
	}

	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	aesKey := strings.Repeat("b", 43)
	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.PublicURL = "http://127.0.0.1:9999"
	cfg.TGEnabled = true
	cfg.TGToken = "tg-tok"
	cfg.TGAllowedUsers = "100,200"
	cfg.WeComEnabled = true
	cfg.WeComCorpID = "corp1"
	cfg.WeComAgentID = "100001"
	cfg.WeComSecret = "wc-sec"
	cfg.WeComToken = "wc-tok"
	cfg.WeComAESKey = aesKey
	cfg.WeComAllowedUsers = "alice"
	cfg.FeishuBotEnabled = true
	cfg.FeishuAllowedUsers = "ou_alice"
	cfg.FeishuAppID = "fs_app"
	cfg.FeishuAppSecret = "fs_sec"
	cfg.FeishuVerificationToken = "fs_vtok"
	cfg.FeishuEncryptKey = "fs_ekey"
	cfg.DiscordBotEnabled = true
	cfg.DiscordAllowedUsers = "555"
	cfg.DiscordApplicationID = "999"
	cfg.DiscordPublicKey = hex.EncodeToString(pub)
	cfg.DiscordBotToken = "disc_tok"

	svc, err := NewService(ctx, ServiceOptions{
		Store:   st,
		Backend: be,
		Initial: cfg,
		Now:     nowFn,
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	defer svc.Close()

	// 1. WeCom verify + encrypted message + replay dedup.
	wc, _ := NewWeComCrypt("wc-tok", aesKey, "corp1")
	var pfx [16]byte
	echoEnc, _ := wc.EncryptForTest([]byte("echo-ok"), pfx)
	ts := strconv.FormatInt(nowFn().Unix(), 10)
	echoPlain, err := svc.HandleWeComVerify(WeComSignature("wc-tok", ts, "n1", echoEnc), ts, "n1", echoEnc)
	if err != nil || string(echoPlain) != "echo-ok" {
		t.Fatalf("HandleWeComVerify: %q err=%v", string(echoPlain), err)
	}
	msgXML := `<xml><ToUserName>corp1</ToUserName><FromUserName>alice</FromUserName><MsgType>text</MsgType><Content>/help</Content><MsgId>wc-m1</MsgId><AgentID>100001</AgentID></xml>`
	msgEnc, _ := wc.EncryptForTest([]byte(msgXML), pfx)
	rawWeComBody := []byte(fmt.Sprintf(`<xml><Encrypt>%s</Encrypt></xml>`, msgEnc))
	ok, err := svc.HandleWeComCallback(ctx, rawWeComBody, WeComSignature("wc-tok", ts, "n2", msgEnc), ts, "n2")
	if err != nil || !ok {
		t.Fatalf("HandleWeComCallback #1: ok=%v err=%v", ok, err)
	}
	ok2, err := svc.HandleWeComCallback(ctx, rawWeComBody, WeComSignature("wc-tok", ts, "n2", msgEnc), ts, "n2")
	if err != nil || ok2 {
		t.Fatalf("HandleWeComCallback duplicate should be ignored: ok=%v err=%v", ok2, err)
	}

	// 2. Feishu url_verification + encrypted callback.
	fsChallenge := encryptFeishuTest("fs_ekey", []byte(`{"token":"fs_vtok","type":"url_verification","challenge":"c123"}`))
	fsChallengeBody, _ := json.Marshal(map[string]string{"encrypt": fsChallenge})
	chHash := sha256.New()
	_, _ = chHash.Write([]byte(ts + "n1" + "fs_ekey"))
	_, _ = chHash.Write(fsChallengeBody)
	resFS, err := svc.HandleFeishuCallback(ctx, fsChallengeBody, ts, "n1", hex.EncodeToString(chHash.Sum(nil)))
	if err != nil || resFS["challenge"] != "c123" {
		t.Fatalf("Feishu url_verification: %+v err=%v", resFS, err)
	}
	fsEventPlain := `{"header":{"event_type":"im.message.receive_v1","app_id":"fs_app","token":"fs_vtok"},"event":{"sender":{"sender_type":"user","sender_id":{"open_id":"ou_alice"}},"message":{"message_id":"fs-m1","chat_type":"p2p","message_type":"text","content":"{\"text\":\"/help\"}"}}}`
	fsEnc := encryptFeishuTest("fs_ekey", []byte(fsEventPlain))
	fsBody, _ := json.Marshal(map[string]string{"encrypt": fsEnc})
	fh := sha256.New()
	_, _ = fh.Write([]byte(ts + "n2" + "fs_ekey"))
	_, _ = fh.Write(fsBody)
	if _, err := svc.HandleFeishuCallback(ctx, fsBody, ts, "n2", hex.EncodeToString(fh.Sum(nil))); err != nil {
		t.Fatalf("Feishu receive_v1 failed: %v", err)
	}

	// 3. Discord PING + /melokeep interaction + non-allowlisted user ephemeral denial.
	pingBody := []byte(`{"id":"d0","application_id":"999","type":1}`)
	pingSig := hex.EncodeToString(ed25519.Sign(priv, append([]byte(ts), pingBody...)))
	pingRes, err := svc.HandleDiscordCallback(ctx, pingBody, ts, pingSig)
	if err != nil || pingRes["type"] != 1 {
		t.Fatalf("Discord PING failed: %+v err=%v", pingRes, err)
	}
	unlistedBody := []byte(`{"id":"d1","application_id":"999","type":2,"user":{"id":"888"},"data":{"name":"melokeep","options":[{"name":"text","value":"/help"}]}}`)
	unlistedSig := hex.EncodeToString(ed25519.Sign(priv, append([]byte(ts), unlistedBody...)))
	unlistedRes, err := svc.HandleDiscordCallback(ctx, unlistedBody, ts, unlistedSig)
	if err != nil || unlistedRes["type"] != 4 {
		t.Fatalf("Discord unlisted response: %+v err=%v", unlistedRes, err)
	}
	listedBody := []byte(`{"id":"d2","application_id":"999","type":2,"user":{"id":"555"},"data":{"name":"melokeep","options":[{"name":"text","value":"/help"}]}}`)
	listedSig := hex.EncodeToString(ed25519.Sign(priv, append([]byte(ts), listedBody...)))
	if _, err := svc.HandleDiscordCallback(ctx, listedBody, ts, listedSig); err != nil {
		t.Fatalf("Discord listed callback failed: %v", err)
	}

	// 4. Telegram search, replay dedup, rate limit, pagination callback retaining EditMessageID, /library, AI, and ownership isolation.
	mkTGUpdate := func(updID, userID int64, text string) TelegramUpdate {
		u := TelegramUpdate{UpdateID: updID}
		u.Message = &TelegramMessage{MessageID: updID, Text: text}
		u.Message.Chat.ID = userID
		u.Message.Chat.Type = "private"
		u.Message.From.ID = userID
		return u
	}

	accepted, err := svc.HandleTelegramUpdate(ctx, mkTGUpdate(1001, 100, "Morning Breeze"))
	if err != nil || !accepted {
		t.Fatalf("TG search #1: accepted=%v err=%v", accepted, err)
	}
	// Replay same update_id -> ignored.
	acceptedDup, err := svc.HandleTelegramUpdate(ctx, mkTGUpdate(1001, 100, "Morning Breeze"))
	if err != nil || acceptedDup {
		t.Fatalf("TG replay should be ignored: accepted=%v err=%v", acceptedDup, err)
	}
	// Immediate second update within 1s -> rate limited.
	if _, err := svc.HandleTelegramUpdate(ctx, mkTGUpdate(1002, 100, "/library")); err == nil {
		t.Fatal("expected rate limit within 1s")
	}
	advance(2 * time.Second)
	if err := svc.DrainPending(ctx); err != nil {
		t.Fatalf("DrainPending #1: %v", err)
	}
	if be.searchCalls != 1 {
		t.Fatalf("expected SearchTracks called once, got %d", be.searchCalls)
	}

	// Fetch current searchID for tg:100 and send pagination callback retaining EditMessageID=77.
	sess, _, err := svc.currentSearch(ctx, "tg:100")
	if err != nil {
		t.Fatalf("currentSearch: %v", err)
	}
	cbUpd := TelegramUpdate{UpdateID: 1003}
	cbUpd.CallbackQuery = &TelegramCallbackQuery{
		ID:   "cb1",
		Data: fmt.Sprintf("/results %s 2", sess.SearchID),
	}
	cbUpd.CallbackQuery.From.ID = 100
	cbUpd.CallbackQuery.Message = &struct {
		MessageID int64 `json:"message_id"`
		Chat      struct {
			ID   int64  `json:"id"`
			Type string `json:"type"`
		} `json:"chat"`
	}{MessageID: 77}
	cbUpd.CallbackQuery.Message.Chat.ID = 100
	cbUpd.CallbackQuery.Message.Chat.Type = "private"
	if ok, err := svc.HandleTelegramUpdate(ctx, cbUpd); err != nil || !ok {
		t.Fatalf("TG callback query failed: ok=%v err=%v", ok, err)
	}
	advance(2 * time.Second)
	if ok, err := svc.HandleTelegramUpdate(ctx, mkTGUpdate(1004, 100, "/download 1")); err != nil || !ok {
		t.Fatalf("TG /download 1 failed: ok=%v err=%v", ok, err)
	}
	advance(2 * time.Second)
	if ok, err := svc.HandleTelegramUpdate(ctx, mkTGUpdate(1005, 100, "/library Morning Breeze")); err != nil || !ok {
		t.Fatalf("TG /library failed: ok=%v err=%v", ok, err)
	}
	advance(2 * time.Second)
	if ok, err := svc.HandleTelegramUpdate(ctx, mkTGUpdate(1006, 100, "/ai 推荐歌曲")); err != nil || !ok {
		t.Fatalf("TG /ai failed: ok=%v err=%v", ok, err)
	}
	if err := svc.DrainPending(ctx); err != nil {
		t.Fatalf("DrainPending #2: %v", err)
	}

	// Check tg:100's job ID and ensure tg:200 cannot link/file/cancel/resume it.
	var jobID string
	for id := range be.jobs {
		jobID = id
	}
	if jobID == "" {
		t.Fatal("expected download job created for tg:100")
	}
	for idx, cmd := range []string{"/link " + jobID, "/file " + jobID, "/cancel " + jobID, "/resume " + jobID} {
		err := svc.handleCommand(ctx, InboundMessage{
			ID: fmt.Sprintf("cross%03d", idx), Channel: ChannelTelegram, User: "200", PlatformMsgID: fmt.Sprintf("x%d", idx), Text: cmd,
		})
		de, ok := model.AsDomainError(err)
		if !ok || de.Status != http.StatusForbidden {
			t.Fatalf("expected 403 for tg:200 running %q, got err=%v", cmd, err)
		}
	}
	if len(be.controlled) != 0 {
		t.Fatalf("tg:200 should not have controlled tg:100 task: %v", be.controlled)
	}

	// Verify outbox payloads include EditMessageID=77, library result, and AI reply.
	rows, err := st.Reader().QueryContext(ctx, `SELECT payload_json FROM outbox WHERE channel = 'tg' ORDER BY created_at ASC`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var sawEdit77, sawLib, sawAI bool
	for rows.Next() {
		var raw string
		_ = rows.Scan(&raw)
		var p OutboxPayload
		_ = json.Unmarshal([]byte(raw), &p)
		if p.EditMessageID != nil && *p.EditMessageID == 77 {
			sawEdit77 = true
		}
		if strings.Contains(p.Text, "索引版本 7") && strings.Contains(p.Text, "Morning Breeze.flac") {
			sawLib = true
		}
		if strings.Contains(p.Text, "AI回复:推荐歌曲") {
			sawAI = true
		}
	}
	if !sawEdit77 || !sawLib || !sawAI {
		t.Fatalf("missing expected outbox payloads: sawEdit77=%v sawLib=%v sawAI=%v", sawEdit77, sawLib, sawAI)
	}
	if !be.lastAIActor.Has(auth.PermExternalWrite) {
		t.Fatal("expected AI actor to have PermExternalWrite when BotAIAllowActions=true")
	}
}

func TestTelegram413FallbackAndDownloadLinks(t *testing.T) {
	ctx := context.Background()
	now := time.Unix(1700002000, 0)
	st := newBotTestStore(t)
	be := newMockBackend()

	audioPath := filepath.Join(t.TempDir(), "song.flac")
	if err := os.WriteFile(audioPath, []byte("fake-flac-bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	rel := "song.flac"
	be.files[rel] = audioPath
	job := model.DownloadJobDTO{
		ID:     "11223344",
		Status: "complete",
		File:   &rel,
		Track:  model.TrackDTO{Title: "Open Sky"},
	}
	be.jobs[job.ID] = job

	var methods []string
	var sentTexts []string
	tgSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
		method := parts[len(parts)-1]
		methods = append(methods, method)
		if method == "sendAudio" {
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			_, _ = io.WriteString(w, `{"ok":false,"error_code":413,"description":"Request Entity Too Large"}`)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		sentTexts = append(sentTexts, string(raw))
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"ok":true,"result":{"message_id":99}}`)
	}))
	defer tgSrv.Close()

	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.PublicURL = "http://127.0.0.1:8080"
	cfg.LinkTTLSeconds = 600
	cfg.TGEnabled = true
	cfg.TGToken = "tg-tok"
	cfg.TGAllowedUsers = "100"
	cfg.TGAPIURL = tgSrv.URL

	svc, err := NewService(ctx, ServiceOptions{
		Store:   st,
		Backend: be,
		Initial: cfg,
		Now:     func() time.Time { return now },
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	defer svc.Close()

	if err := svc.enqueueReply(ctx, ChannelTelegram, "100", "ev413", OutboxPayload{
		Text:  "发送音频",
		JobID: job.ID,
		File:  true,
	}); err != nil {
		t.Fatal(err)
	}
	processed, err := svc.ProcessOutboxOnce(ctx, 10)
	if err != nil || processed != 1 {
		t.Fatalf("ProcessOutboxOnce: processed=%d err=%v", processed, err)
	}
	if len(methods) != 2 || methods[0] != "sendAudio" || methods[1] != "sendMessage" {
		t.Fatalf("expected sendAudio -> 413 -> sendMessage fallback, got %v", methods)
	}
	if len(sentTexts) != 1 || !strings.Contains(sentTexts[0], "/downloads/") || !strings.Contains(sentTexts[0], "signature=") {
		t.Fatalf("expected signed download link in fallback message, got %v", sentTexts)
	}

	// Verify DownloadLinks: valid -> tamper -> expiry -> file modification (410).
	rawURL, err := svc.Links().Create(ctx, job, now)
	if err != nil {
		t.Fatalf("Links.Create: %v", err)
	}
	// Parse ident, expires, signature from URL.
	qIdx := strings.IndexByte(rawURL, '?')
	pathPart := rawURL[:qIdx]
	ident := pathPart[strings.LastIndexByte(pathPart, '/')+1:]
	var exp int64
	var sig string
	for _, kv := range strings.Split(rawURL[qIdx+1:], "&") {
		p := strings.SplitN(kv, "=", 2)
		if p[0] == "expires" {
			exp, _ = strconv.ParseInt(p[1], 10, 64)
		} else if p[0] == "signature" {
			sig = p[1]
		}
	}
	if got, err := svc.Links().Verify(ctx, ident, exp, sig, now); err != nil || got != audioPath {
		t.Fatalf("Links.Verify valid failed: got=%q err=%v", got, err)
	}
	if _, err := svc.Links().Verify(ctx, ident, exp, strings.Repeat("0", 64), now); err == nil {
		t.Fatal("expected tampered signature rejection")
	}
	if _, err := svc.Links().Verify(ctx, ident, exp, sig, now.Add(601*time.Second)); err == nil {
		t.Fatal("expected expired link rejection")
	}
	if err := os.WriteFile(audioPath, []byte("modified-audio-content-longer"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Links().Verify(ctx, ident, exp, sig, now); err == nil {
		t.Fatal("expected 410 when file size/mtime changes")
	} else if de, ok := model.AsDomainError(err); !ok || de.Status != http.StatusGone {
		t.Fatalf("expected 410 Gone, got %+v", err)
	}
}

func TestOutboxCrashRecoveryNonBlockingAndAdminResend(t *testing.T) {
	ctx := context.Background()
	now := time.Unix(1700003000, 0)
	st := newBotTestStore(t)

	var tgCalls atomic.Int32
	var delivered []string
	tgSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tgCalls.Add(1)
		raw, _ := io.ReadAll(r.Body)
		delivered = append(delivered, string(raw))
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"ok":true,"result":{"message_id":1}}`)
	}))
	defer tgSrv.Close()

	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.TGEnabled = true
	cfg.TGToken = "tg-tok"
	cfg.TGAllowedUsers = "100"
	cfg.TGAPIURL = tgSrv.URL

	svc, err := NewService(ctx, ServiceOptions{
		Store:   st,
		Initial: cfg,
		Now:     func() time.Time { return now },
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	defer svc.Close()

	// Seed one 'sending' outbox item (crashed mid-flight) and one 'pending' item.
	if err := st.EnqueueOutbox(ctx, store.OutboxEnqueueParams{
		ID: "out-stuck", Channel: ChannelTelegram, RecipientKey: "100", EventKey: "ev-stuck",
		PayloadJSON: `{"text":"crashed-mid-send"}`,
	}); err != nil {
		t.Fatal(err)
	}
	if err := st.UpdateOutboxState(ctx, "out-stuck", model.OutboxSending, "{}", "", 0); err != nil {
		t.Fatal(err)
	}
	if err := st.EnqueueOutbox(ctx, store.OutboxEnqueueParams{
		ID: "out-next", Channel: ChannelTelegram, RecipientKey: "100", EventKey: "ev-next",
		PayloadJSON: `{"text":"fresh-message"}`,
	}); err != nil {
		t.Fatal(err)
	}

	recovered, err := svc.RecoverOnStartup(ctx)
	if err != nil || recovered != 1 {
		t.Fatalf("RecoverOnStartup: recovered=%d err=%v", recovered, err)
	}

	// ProcessOutboxOnce delivers 'out-next' without blocking and NEVER auto-resends 'out-stuck'.
	processed, err := svc.ProcessOutboxOnce(ctx, 10)
	if err != nil || processed != 1 {
		t.Fatalf("ProcessOutboxOnce: processed=%d err=%v", processed, err)
	}
	if tgCalls.Load() != 1 || !strings.Contains(delivered[0], "fresh-message") {
		t.Fatalf("expected only fresh-message delivered, got calls=%d delivered=%v", tgCalls.Load(), delivered)
	}

	var stuckState string
	if err := st.Reader().QueryRowContext(ctx, `SELECT state FROM outbox WHERE id = 'out-stuck'`).Scan(&stuckState); err != nil {
		t.Fatal(err)
	}
	if stuckState != string(model.OutboxDeliveryUnknown) {
		t.Fatalf("expected out-stuck in delivery_unknown, got %q", stuckState)
	}

	// Non-admin cannot ResendOutbox.
	if err := svc.ResendOutbox(ctx, auth.Actor{Authenticated: true}, "out-stuck"); err == nil {
		t.Fatal("expected non-admin ResendOutbox to fail")
	} else if de, ok := model.AsDomainError(err); !ok || de.Status != http.StatusForbidden {
		t.Fatalf("expected 403, got %v", err)
	}

	// Admin explicitly resends out-stuck -> sent.
	admin := auth.WebAdminActor("admin")
	if err := svc.ResendOutbox(ctx, admin, "out-stuck"); err != nil {
		t.Fatalf("admin ResendOutbox: %v", err)
	}
	if tgCalls.Load() != 2 || !strings.Contains(delivered[1], "crashed-mid-send") {
		t.Fatalf("expected admin resend to deliver crashed-mid-send, calls=%d delivered=%v", tgCalls.Load(), delivered)
	}
	if err := st.Reader().QueryRowContext(ctx, `SELECT state FROM outbox WHERE id = 'out-stuck'`).Scan(&stuckState); err != nil {
		t.Fatal(err)
	}
	if stuckState != string(model.OutboxSent) {
		t.Fatalf("expected out-stuck sent after admin resend, got %q", stuckState)
	}
}

func TestAllChannelOutboundDispatch(t *testing.T) {
	ctx := context.Background()
	st := newBotTestStore(t)
	be := newMockBackend()

	var mu sync.Mutex
	var paths []string
	record := func(p string) {
		mu.Lock()
		paths = append(paths, p)
		mu.Unlock()
	}

	mockSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		record(r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasPrefix(r.URL.Path, "/cgi-bin/gettoken"):
			_, _ = io.WriteString(w, `{"errcode":0,"access_token":"wc-tok","expires_in":7200}`)
		case strings.HasPrefix(r.URL.Path, "/cgi-bin/media/upload"):
			_, _ = io.WriteString(w, `{"errcode":0,"media_id":"med1"}`)
		case strings.HasPrefix(r.URL.Path, "/cgi-bin/message/send"):
			_, _ = io.WriteString(w, `{"errcode":0,"msgid":"m1"}`)
		case r.URL.Path == "/open-apis/auth/v3/tenant_access_token/internal":
			_, _ = io.WriteString(w, `{"code":0,"tenant_access_token":"fs-tok","expire":7200}`)
		case r.URL.Path == "/open-apis/im/v1/messages", strings.HasPrefix(r.URL.Path, "/open-apis/bot/v2/hook/"):
			_, _ = io.WriteString(w, `{"code":0}`)
		case r.URL.Path == "/v1.0/oauth2/accessToken":
			_, _ = io.WriteString(w, `{"accessToken":"dt-tok","expireIn":7200}`)
		case r.URL.Path == "/v1.0/robot/oToMessages/batchSend", r.URL.Path == "/robot/send":
			_, _ = io.WriteString(w, `{"errcode":0}`)
		case r.URL.Path == "/api/v10/users/@me/channels":
			_, _ = io.WriteString(w, `{"id":"dm-123"}`)
		case strings.HasPrefix(r.URL.Path, "/api/v10/channels/"), strings.HasPrefix(r.URL.Path, "/api/webhooks/"):
			_, _ = io.WriteString(w, `{"id":"msg-1"}`)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer mockSrv.Close()

	filePath := filepath.Join(t.TempDir(), "track.mp3")
	_ = os.WriteFile(filePath, []byte("mp3bytes"), 0o600)
	rel := "track.mp3"
	be.files[rel] = filePath
	be.jobs["job1"] = model.DownloadJobDTO{ID: "job1", Status: "complete", File: &rel, Track: model.TrackDTO{Title: "Track"}}

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.PublicURL = "http://127.0.0.1:8080"
	cfg.WeComEnabled = true
	cfg.WeComCorpID = "corp"
	cfg.WeComAgentID = "1"
	cfg.WeComSecret = "sec"
	cfg.WeComToken = "tok"
	cfg.WeComAESKey = strings.Repeat("c", 43)
	cfg.WeComAPIURL = mockSrv.URL
	cfg.WeComAllowedUsers = "u1"
	cfg.FeishuEnabled = true
	cfg.FeishuWebhook = mockSrv.URL + "/open-apis/bot/v2/hook/abc"
	cfg.FeishuSignSecret = "fs-hook-sec"
	cfg.FeishuBotEnabled = true
	cfg.FeishuAllowedUsers = "ou_1"
	cfg.FeishuAppID = "app1"
	cfg.FeishuAppSecret = "sec1"
	cfg.FeishuVerificationToken = "vt"
	cfg.FeishuEncryptKey = "ek"
	cfg.FeishuAPIURL = mockSrv.URL
	cfg.DingTalkEnabled = true
	cfg.DingTalkWebhook = mockSrv.URL + "/robot/send?access_token=t"
	cfg.DingTalkSignSecret = "dt-hook-sec"
	cfg.DingTalkBotEnabled = true
	cfg.DingTalkAllowedUsers = "staff1"
	cfg.DingTalkClientID = "cid"
	cfg.DingTalkClientSecret = "csec"
	cfg.DingTalkRobotCode = "rc"
	cfg.DingTalkAPIURL = mockSrv.URL
	cfg.DiscordEnabled = true
	cfg.DiscordWebhook = mockSrv.URL + "/api/webhooks/1/tok"
	cfg.DiscordBotEnabled = true
	cfg.DiscordAllowedUsers = "12345"
	cfg.DiscordApplicationID = "999"
	cfg.DiscordPublicKey = hex.EncodeToString(pub)
	cfg.DiscordBotToken = "bot-tok"
	cfg.DiscordAPIURL = mockSrv.URL

	svc, err := NewService(ctx, ServiceOptions{Store: st, Backend: be, Initial: cfg})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	defer svc.Close()

	_ = svc.EnqueueNotification(ctx, ChannelWeCom, "u1", "k1", OutboxPayload{Text: "hi", JobID: "job1", File: true})
	_ = svc.EnqueueNotification(ctx, ChannelFeishu, "ou_1", "k2", OutboxPayload{Text: "hi feishu"})
	_ = svc.EnqueueNotification(ctx, ChannelFeishu, "downloads", "k3", OutboxPayload{Text: "done"})
	_ = svc.EnqueueNotification(ctx, ChannelDingTalk, "staff1", "k4", OutboxPayload{Text: "hi dt"})
	_ = svc.EnqueueNotification(ctx, ChannelDingTalk, "downloads", "k5", OutboxPayload{Text: "done"})
	_ = svc.EnqueueNotification(ctx, ChannelDiscord, "12345", "k6", OutboxPayload{Text: "hi discord"})
	_ = svc.EnqueueNotification(ctx, ChannelDiscord, "downloads", "k7", OutboxPayload{Text: "done"})

	n, err := svc.ProcessOutboxOnce(ctx, 20)
	if err != nil || n != 7 {
		t.Fatalf("ProcessOutboxOnce: n=%d err=%v", n, err)
	}
	joined := strings.Join(paths, ",")
	for _, want := range []string{
		"/cgi-bin/media/upload",
		"/cgi-bin/message/send",
		"/open-apis/im/v1/messages",
		"/open-apis/bot/v2/hook/abc",
		"/v1.0/robot/oToMessages/batchSend",
		"/robot/send",
		"/api/v10/channels/dm-123/messages",
		"/api/webhooks/1/tok",
	} {
		if !strings.Contains(joined, want) {
			t.Fatalf("missing outbound call %q in %v", want, paths)
		}
	}
}

func TestHelperLocalBotAPIProcess(t *testing.T) {
	var listenAddr string
	var httpMode bool
	for _, a := range os.Args {
		if strings.HasPrefix(a, "--listen=") {
			listenAddr = strings.TrimPrefix(a, "--listen=")
		}
		if a == "--http=1" {
			httpMode = true
		}
	}
	if listenAddr == "" {
		return
	}
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		t.Fatalf("helper listen %s: %v", listenAddr, err)
	}
	defer ln.Close()
	if httpMode {
		_ = http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			switch {
			case strings.HasSuffix(r.URL.Path, "/getMe"):
				_, _ = w.Write([]byte(`{"ok":true,"result":{"id":1,"is_bot":true,"username":"melokeep_local_bot"}}`))
			default:
				_, _ = w.Write([]byte(`{"ok":true,"result":[]}`))
			}
		}))
		return
	}
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		_ = conn.Close()
	}
}

func TestLocalBotAPIProcessLifecycle(t *testing.T) {
	if _, _, err := ValidateLocalCredentials("0", strings.Repeat("a", 32)); err == nil {
		t.Fatal("expected apiID=0 to fail")
	}
	if _, _, err := ValidateLocalCredentials("12345", "bad-hash"); err == nil {
		t.Fatal("expected invalid apiHash to fail")
	}

	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	nonLoop := NewLocalBotAPI(LocalBotAPISpec{
		BinaryPath: exe,
		ListenAddr: "0.0.0.0:18081",
		DataDir:    t.TempDir(),
	})
	if err := nonLoop.Start(context.Background(), "12345", strings.Repeat("a", 32)); err == nil {
		t.Fatal("expected non-loopback listen addr to be rejected")
	}

	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := probe.Addr().String()
	_ = probe.Close()

	local := NewLocalBotAPI(LocalBotAPISpec{
		BinaryPath: exe,
		ListenAddr: addr,
		DataDir:    t.TempDir(),
		Args:       []string{"-test.run=^TestHelperLocalBotAPIProcess$", "--", "--listen=" + addr},
	})
	t.Cleanup(local.Stop)

	if err := local.Start(context.Background(), "12345", strings.Repeat("a", 32)); err != nil {
		t.Fatalf("LocalBotAPI.Start: %v", err)
	}
	st := local.Status()
	if !st.Running || !st.Available || st.URL != "http://"+addr {
		t.Fatalf("unexpected LocalBotAPIStatus: %+v", st)
	}
	if !local.Matches("12345", strings.Repeat("a", 32)) {
		t.Fatal("expected Matches=true for active credentials")
	}

	local.Stop()
	if local.Status().Running {
		t.Fatal("expected Running=false after Stop")
	}

	// Port must be released immediately after Stop kills the process tree.
	var rebound bool
	for i := 0; i < 20; i++ {
		ln, err := net.Listen("tcp", addr)
		if err == nil {
			_ = ln.Close()
			rebound = true
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !rebound {
		t.Fatalf("port %s still occupied after LocalBotAPI.Stop", addr)
	}
}

func TestBotTestConnectionAndRegisterDiscordCommands(t *testing.T) {
	st := newBotTestStore(t)
	ctx := context.Background()
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	t.Cleanup(pool.CloseAll)

	var tgCalls, wecomCalls, discordCalls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/getMe"):
			tgCalls.Add(1)
			_, _ = w.Write([]byte(`{"ok":true,"result":{"id":1,"is_bot":true,"username":"melokeep_bot"}}`))
		case r.URL.Path == "/cgi-bin/gettoken":
			wecomCalls.Add(1)
			_, _ = w.Write([]byte(`{"errcode":0,"errmsg":"ok","access_token":"tok1","expires_in":7200}`))
		case r.URL.Path == "/api/v10/applications/123456789012345678/commands":
			discordCalls.Add(1)
			if r.Header.Get("Authorization") != "Bot disc-secret" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			_, _ = w.Write([]byte(`{"id":"cmd1","name":"melokeep"}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.PublicURL = "https://music.example.com"
	cfg.TGAPIURL = srv.URL
	cfg.WeComAPIURL = srv.URL
	cfg.DiscordAPIURL = srv.URL
	cfg.DiscordBotEnabled = true
	cfg.DiscordAllowedUsers = "987654321012345678"
	cfg.DiscordApplicationID = "123456789012345678"
	cfg.DiscordPublicKey = strings.Repeat("aa", 32)
	cfg.DiscordBotToken = "disc-secret"

	svc, err := NewService(ctx, ServiceOptions{
		Store: st, Pool: pool, Backend: newMockBackend(), Initial: cfg,
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	t.Cleanup(svc.Close)

	// Draft TG token test must hit getMe and return detail.
	draftTG := cfg
	draftTG.TGToken = "123456:draft-token"
	resTG, err := svc.TestConnection(ctx, ChannelTelegram, &draftTG, nil)
	if err != nil || tgCalls.Load() != 1 || resTG["detail"] == "" {
		t.Fatalf("TestConnection tg failed: res=%+v calls=%d err=%v", resTG, tgCalls.Load(), err)
	}

	// Draft WeCom test must hit gettoken and return detail.
	draftWeCom := cfg
	draftWeCom.WeComCorpID = "ww123"
	draftWeCom.WeComSecret = "sec123"
	resWC, err := svc.TestConnection(ctx, ChannelWeCom, &draftWeCom, nil)
	if err != nil || wecomCalls.Load() != 1 || resWC["detail"] == "" {
		t.Fatalf("TestConnection wecom failed: res=%+v calls=%d err=%v", resWC, wecomCalls.Load(), err)
	}

	// Webhook validation rejects non-official URL and accepts valid URL with detail.
	badHook := cfg
	badHook.FeishuWebhook = "https://evil.example.com/hook"
	if _, err := svc.TestConnection(ctx, ChannelFeishu, &badHook, nil); err == nil {
		t.Fatal("expected invalid feishu webhook to fail")
	}
	goodHook := cfg
	goodHook.FeishuWebhook = "https://open.feishu.cn/open-apis/bot/v2/hook/abc-123"
	if resFH, err := svc.TestConnection(ctx, ChannelFeishu, &goodHook, nil); err != nil || resFH["detail"] == "" {
		t.Fatalf("TestConnection feishu failed: res=%+v err=%v", resFH, err)
	}

	// RegisterDiscordCommands must POST /commands and return detail.
	resDC, err := svc.RegisterDiscordCommands(ctx)
	if err != nil || discordCalls.Load() != 1 || resDC["detail"] == "" {
		t.Fatalf("RegisterDiscordCommands failed: res=%+v calls=%d err=%v", resDC, discordCalls.Load(), err)
	}
}

func TestBotBlankSecretPreservationAndClearSecrets(t *testing.T) {
	st := newBotTestStore(t)
	ctx := context.Background()

	initCfg := DefaultConfig()
	initCfg.TGEnabled = true
	initCfg.TGToken = "123456:saved-secret-token"
	initCfg.TGAllowedUsers = "1001"
	initCfg.TGLocalEnabled = true
	initCfg.TGLocalAPIID = "12345"
	initCfg.TGLocalAPIHash = strings.Repeat("b", 32)
	svc, err := NewService(ctx, ServiceOptions{Store: st, Initial: initCfg})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	defer svc.Close()

	// Empty incoming secrets (as submitted by BotSettings UI when inputs stay blank) must preserve saved secrets.
	blankIncoming := initCfg
	blankIncoming.TGToken = ""
	blankIncoming.TGLocalAPIHash = ""
	blankIncoming.TGAllowedUsers = "1001,1002"
	if _, err := svc.UpdateConfig(ctx, auth.WebAdminActor("admin"), blankIncoming, nil); err != nil {
		t.Fatalf("UpdateConfig blank secrets: %v", err)
	}
	if got := svc.Config().TGToken; got != initCfg.TGToken {
		t.Fatalf("expected blank TGToken to preserve saved secret, got %q", got)
	}
	if got := svc.Config().TGLocalAPIHash; got != initCfg.TGLocalAPIHash {
		t.Fatalf("expected blank TGLocalAPIHash to preserve saved secret, got %q", got)
	}
	if got := svc.Config().TGAllowedUsers; got != "1001,1002" {
		t.Fatalf("expected non-secret field updated, got %q", got)
	}

	// Explicit clear_secrets must clear the specified secret while keeping others.
	disableLocal := blankIncoming
	disableLocal.TGLocalEnabled = false
	if _, err := svc.UpdateConfig(ctx, auth.WebAdminActor("admin"), disableLocal, []string{"tg_local_api_hash"}); err != nil {
		t.Fatalf("UpdateConfig clear tg_local_api_hash: %v", err)
	}
	if svc.Config().TGLocalAPIHash != "" {
		t.Fatalf("expected tg_local_api_hash cleared, got %q", svc.Config().TGLocalAPIHash)
	}
	if svc.Config().TGToken != initCfg.TGToken {
		t.Fatalf("expected TGToken preserved when clearing hash, got %q", svc.Config().TGToken)
	}
}

func TestBotTelegramLocalAPIStartTestAndMissingBinary(t *testing.T) {
	st := newBotTestStore(t)
	ctx := context.Background()
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	t.Cleanup(pool.CloseAll)

	// 1. Missing binary must return an explicit unavailable error instead of looping on "save and restart".
	missingLocal := NewLocalBotAPI(LocalBotAPISpec{
		BinaryPath: filepath.Join(t.TempDir(), "nonexistent-telegram-bot-api"),
		DataDir:    t.TempDir(),
	})
	svcMissing, err := NewService(ctx, ServiceOptions{
		Store:    st,
		Pool:     pool,
		LocalAPI: missingLocal,
		Backend:  newMockBackend(),
		Initial:  Config{AllowTestLoopback: true},
	})
	if err != nil {
		t.Fatalf("NewService missingLocal: %v", err)
	}
	t.Cleanup(svcMissing.Close)

	missingDraft := DefaultConfig()
	missingDraft.AllowTestLoopback = true
	missingDraft.TGToken = "123456:tok"
	missingDraft.TGLocalEnabled = true
	missingDraft.TGLocalAPIID = "12345"
	missingDraft.TGLocalAPIHash = strings.Repeat("a", 32)
	if _, err := svcMissing.TestConnection(ctx, ChannelTelegram, &missingDraft, nil); err == nil || !strings.Contains(err.Error(), "telegram-bot-api") {
		t.Fatalf("expected missing telegram-bot-api error, got %v", err)
	}

	// 2. Real helper subprocess: saving TGEnabled+TGLocalEnabled auto-starts LocalBotAPI,
	//    and a subsequent TestConnection with blank password fields verifies getMe end-to-end.
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := probe.Addr().String()
	_ = probe.Close()

	local := NewLocalBotAPI(LocalBotAPISpec{
		BinaryPath: exe,
		ListenAddr: addr,
		DataDir:    t.TempDir(),
		Args:       []string{"-test.run=^TestHelperLocalBotAPIProcess$", "--", "--listen=" + addr, "--http=1"},
	})
	svc, err := NewService(ctx, ServiceOptions{
		Store:    newBotTestStore(t),
		Pool:     pool,
		LocalAPI: local,
		Backend:  newMockBackend(),
		Initial:  Config{AllowTestLoopback: true},
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	t.Cleanup(svc.Close)

	saveCfg := DefaultConfig()
	saveCfg.AllowTestLoopback = true
	saveCfg.TGEnabled = true
	saveCfg.TGToken = "123456:local-token"
	saveCfg.TGAllowedUsers = "1001"
	saveCfg.TGLocalEnabled = true
	saveCfg.TGLocalAPIID = "12345"
	saveCfg.TGLocalAPIHash = strings.Repeat("c", 32)

	// Before saving, local service is not started yet -> check returns save instruction.
	preTest, err := svc.TestConnection(ctx, ChannelTelegram, &saveCfg, nil)
	if err != nil || preTest["ok"] != true {
		t.Fatalf("pre-save TestConnection failed: res=%+v err=%v", preTest, err)
	}

	// Saving must auto-start the local process.
	pub, err := svc.UpdateConfig(ctx, auth.WebAdminActor("admin"), saveCfg, nil)
	if err != nil {
		t.Fatalf("UpdateConfig local TG: %v", err)
	}
	stMap, _ := pub["local_api_status"].(LocalBotAPIStatus)
	if !stMap.Running {
		t.Fatalf("expected LocalBotAPI running after UpdateConfig, got %+v", pub["local_api_status"])
	}

	// Simulating browser test after save (blank secret inputs) must succeed against live local getMe.
	postDraft := saveCfg
	postDraft.TGToken = ""
	postDraft.TGLocalAPIHash = ""
	res, err := svc.TestConnection(ctx, ChannelTelegram, &postDraft, nil)
	if err != nil || !strings.Contains(fmt.Sprint(res["detail"]), "连接测试成功") {
		t.Fatalf("post-save TestConnection expected live success, got res=%+v err=%v", res, err)
	}

	// Disabling TGLocalEnabled via UpdateConfig must stop LocalBotAPI.
	stopCfg := saveCfg
	stopCfg.TGToken = ""
	stopCfg.TGLocalAPIHash = ""
	stopCfg.TGLocalEnabled = false
	if _, err := svc.UpdateConfig(ctx, auth.WebAdminActor("admin"), stopCfg, nil); err != nil {
		t.Fatalf("UpdateConfig disable local: %v", err)
	}
	if local.Status().Running {
		t.Fatal("expected LocalBotAPI stopped when TGLocalEnabled=false")
	}
}

func TestBotTelegramPollingAndWorkers(t *testing.T) {
	st := newBotTestStore(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	t.Cleanup(pool.CloseAll)

	var gotUpdatesCalls atomic.Int32
	var sentMessages atomic.Int32
	var lastSentText string
	var mu sync.Mutex

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/getUpdates"):
			n := gotUpdatesCalls.Add(1)
			if n == 1 {
				_, _ = w.Write([]byte(`{"ok":true,"result":[{"update_id":42,"message":{"message_id":7,"chat":{"id":1001,"type":"private"},"from":{"id":1001},"text":"/status"}}]}`))
				return
			}
			_, _ = w.Write([]byte(`{"ok":true,"result":[]}`))
		case strings.HasSuffix(r.URL.Path, "/sendMessage"):
			raw, _ := io.ReadAll(r.Body)
			mu.Lock()
			lastSentText = string(raw)
			mu.Unlock()
			sentMessages.Add(1)
			_, _ = w.Write([]byte(`{"ok":true,"result":{"message_id":99}}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.TGEnabled = true
	cfg.TGToken = "123456:poll-token"
	cfg.TGAllowedUsers = "1001"
	cfg.TGAPIURL = srv.URL

	svc, err := NewService(ctx, ServiceOptions{
		Store:   st,
		Pool:    pool,
		Backend: newMockBackend(),
		Initial: cfg,
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	t.Cleanup(svc.Close)

	var offset int64
	accepted, err := svc.PollTelegramOnce(ctx, &offset, 0)
	if err != nil || accepted != 1 || offset != 43 {
		t.Fatalf("PollTelegramOnce unexpected: accepted=%d offset=%d err=%v", accepted, offset, err)
	}

	// StartWorkers must drain pendingCh via ExecuteInbound and enqueue the /status reply into outbox.
	svc.StartWorkers(ctx)
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		sent, err := svc.ProcessOutboxOnce(ctx, 10)
		if err != nil {
			t.Fatalf("ProcessOutboxOnce: %v", err)
		}
		if sent > 0 || sentMessages.Load() > 0 {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	if sentMessages.Load() == 0 {
		t.Fatal("expected inbound /status reply to be dispatched to Telegram sendMessage")
	}
	mu.Lock()
	gotBody := lastSentText
	mu.Unlock()
	if !strings.Contains(gotBody, "1001") {
		t.Fatalf("unexpected Telegram sendMessage payload: %s", gotBody)
	}
}

type catalogMockBackend struct {
	*mockBackend
	sources []model.AvailableSourceDTO
	bySrc   map[string][]model.TrackDTO
}

func (c *catalogMockBackend) AvailableSources() []model.AvailableSourceDTO {
	return c.sources
}

func (c *catalogMockBackend) SearchTracks(_ context.Context, _ auth.Actor, keyword string, sources []string) ([]model.TrackDTO, bool, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.searchCalls++
	var out []model.TrackDTO
	if len(sources) == 1 && len(c.bySrc) > 0 {
		out = append(out, c.bySrc[sources[0]]...)
	} else {
		out = append(out, c.searchTracks...)
	}
	return out, false, nil
}

func TestBotSanitizeHiddenPlaceholderAndPersistTGOffsetAndRedact(t *testing.T) {
	ctx := context.Background()
	st := newBotTestStore(t)

	// Legacy dirty config with literal "[已隐藏]" must be scrubbed by SanitizeSecrets & MergeConfig.
	dirty := DefaultConfig()
	dirty.AllowTestLoopback = true
	dirty.TGEnabled = true
	dirty.TGToken = config.HiddenPlaceholder
	dirty.TGLocalAPIHash = config.HiddenPlaceholder
	dirty.TGAllowedUsers = "1001"
	if cleaned := SanitizeSecrets(dirty); cleaned.TGToken != "" || cleaned.TGLocalAPIHash != "" {
		t.Fatalf("SanitizeSecrets failed to clear HiddenPlaceholder: %+v", cleaned)
	}
	merged := MergeConfig(dirty, DefaultConfig(), nil)
	if merged.TGToken != "" || merged.TGLocalAPIHash != "" {
		t.Fatalf("MergeConfig preserved dirty HiddenPlaceholder: %+v", merged)
	}

	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/getUpdates"):
			_, _ = w.Write([]byte(`{"ok":true,"result":[{"update_id":88,"message":{"message_id":9,"chat":{"id":1001,"type":"private"},"from":{"id":1001},"text":"/status"}}]}`))
		default:
			_, _ = w.Write([]byte(`{"ok":true,"result":{"id":1}}`))
		}
	}))
	defer srv.Close()

	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.TGEnabled = true
	cfg.TGToken = "123456:real+tok/abc"
	cfg.TGAllowedUsers = "1001"
	cfg.TGAPIURL = srv.URL

	svc, err := NewService(ctx, ServiceOptions{Store: st, Backend: newMockBackend(), Initial: cfg})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	t.Cleanup(svc.Close)

	// Dirty callTelegram with placeholder token must refuse locally without hitting network.
	prevCalls := calls.Load()
	dirtyCallCfg := cfg
	dirtyCallCfg.TGToken = config.HiddenPlaceholder
	if _, err := svc.channels.callTelegram(ctx, dirtyCallCfg, "rev-dirty", nil, "", "getUpdates", nil, "", "", ""); err == nil || calls.Load() != prevCalls {
		t.Fatalf("expected literal HiddenPlaceholder token rejected before HTTP call: err=%v calls=%d", err, calls.Load()-prevCalls)
	}

	// RedactError must mask raw token, escaped token, and /bot.../ segments.
	leakErr := errors.New(`Post "` + srv.URL + `/bot123456%3Areal%2Btok%2Fabc/getUpdates?t=` + url.QueryEscape(cfg.TGToken) + `": timeout`)
	redacted := svc.RedactError(leakErr)
	if strings.Contains(redacted, "real") || strings.Contains(redacted, "123456") || !strings.Contains(redacted, config.HiddenPlaceholder) {
		t.Fatalf("RedactError leaked sensitive token: %q", redacted)
	}

	// PollTelegramOnce with nil offset loads and persists tg_offset=89, and resets to 0 on token change.
	accepted, err := svc.PollTelegramOnce(ctx, nil, 0)
	if err != nil || accepted != 1 {
		t.Fatalf("PollTelegramOnce nil offset: accepted=%d err=%v", accepted, err)
	}
	if got := svc.loadTGOffset(ctx); got != 89 {
		t.Fatalf("expected persisted tg_offset=89, got %d", got)
	}
	cfg2 := cfg
	cfg2.TGToken = "999999:new-token"
	if _, err := svc.UpdateConfig(ctx, auth.WebAdminActor("admin"), cfg2, nil); err != nil {
		t.Fatalf("UpdateConfig new token: %v", err)
	}
	if got := svc.loadTGOffset(ctx); got != 0 {
		t.Fatalf("expected tg_offset reset to 0 after token change, got %d", got)
	}

	// Blank GET /bot/wecom verification query returns explicit 403 guidance.
	cfgWC := DefaultConfig()
	cfgWC.AllowTestLoopback = true
	cfgWC.PublicURL = "http://127.0.0.1:8080"
	cfgWC.WeComEnabled = true
	cfgWC.WeComCorpID = "corp"
	cfgWC.WeComAgentID = "1"
	cfgWC.WeComSecret = "sec"
	cfgWC.WeComToken = "tok"
	cfgWC.WeComAESKey = strings.Repeat("d", 43)
	cfgWC.WeComAllowedUsers = "alice"
	svcWC, err := NewService(ctx, ServiceOptions{Store: newBotTestStore(t), Initial: cfgWC})
	if err != nil {
		t.Fatalf("NewService wecom: %v", err)
	}
	t.Cleanup(svcWC.Close)
	_, vErr := svcWC.HandleWeComVerify("", "", "", "")
	if de, ok := model.AsDomainError(vErr); !ok || de.Status != http.StatusForbidden || !strings.Contains(de.Detail, "请勿直接在浏览器打开") {
		t.Fatalf("expected 403 browser access guidance on blank WeCom verify, got %v", vErr)
	}
}

func TestBotPreRewriteChannelAdaptationParity(t *testing.T) {
	ctx := context.Background()
	st := newBotTestStore(t)
	now := time.Unix(1700010000, 0)

	// 1. rankTracks deprioritizes derivative versions unless keyword asks for them.
	ranked := rankTracks([]model.TrackDTO{
		{ID: "dj", Title: "Morning Breeze (DJ版)", Artist: "Open Horizon", Qualities: []model.QualityDTO{{ID: "q1", Label: "MP3 320k", Bitrate: intPtr(320)}}},
		{ID: "orig", Title: "Morning Breeze", Artist: "Open Horizon", Album: "Open Air", Qualities: []model.QualityDTO{{ID: "q2", Label: "FLAC 无损", Codec: "flac", Lossless: true}}},
	}, "Morning Breeze")
	if len(ranked) != 2 || ranked[0].ID != "orig" {
		t.Fatalf("expected original version ranked above DJ version, got %+v", ranked)
	}

	// Verify sourceLabelForTrack uses availableSources Label, builtinSourceLabels fallback, and raw ID fallback.
	avail := []model.AvailableSourceDTO{
		{ID: "custom_plugin", Label: "Custom Audio", EngineName: "custom_plugin"},
	}
	if got := sourceLabelForTrack(model.TrackDTO{Source: "custom_plugin"}, avail); got != "Custom Audio" {
		t.Fatalf("expected Custom Audio from availableSources, got %q", got)
	}
	if got := sourceLabelForTrack(model.TrackDTO{Source: "AudiusMusicClient"}, nil); got != "Audius" {
		t.Fatalf("expected Audius fallback label, got %q", got)
	}
	if got := sourceLabelForTrack(model.TrackDTO{Source: "unknown_src"}, avail); got != "unknown_src" {
		t.Fatalf("expected raw source ID fallback, got %q", got)
	}

	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	t.Cleanup(pool.CloseAll)

	var mu sync.Mutex
	var tgMethods []string
	var tgBodies []string
	var hookCalls []string
	tgSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
		m := parts[len(parts)-1]
		raw, _ := io.ReadAll(r.Body)
		mu.Lock()
		tgMethods = append(tgMethods, m)
		tgBodies = append(tgBodies, string(raw))
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if m == "sendAudio" {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = io.WriteString(w, `{"ok":false,"error_code":400,"description":"Bad Request: wrong audio format"}`)
			return
		}
		_, _ = io.WriteString(w, `{"ok":true,"result":{"message_id":321}}`)
	}))
	defer tgSrv.Close()

	hookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		mu.Lock()
		hookCalls = append(hookCalls, r.URL.Path+":"+string(raw))
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"code":0}`)
	}))
	defer hookSrv.Close()

	audioPath := filepath.Join(t.TempDir(), "morning_breeze.flac")
	_ = os.WriteFile(audioPath, []byte("flac-bytes"), 0o600)
	rel := "morning_breeze.flac"

	base := newMockBackend()
	base.files[rel] = audioPath
	// Seed one pre-existing download before enabling webhook -> must be skipped on init.
	base.jobs["old-job"] = model.DownloadJobDTO{ID: "old-job", Status: "complete", File: &rel, Track: model.TrackDTO{Title: "Old Song", Artist: "Tester"}}

	be := &catalogMockBackend{
		mockBackend: base,
		sources: []model.AvailableSourceDTO{
			{ID: "AudiusMusicClient", Label: "Audius", EngineName: "AudiusMusicClient"},
			{ID: "FMAMusicClient", Label: "FMA", EngineName: "FMAMusicClient"},
		},
		bySrc: map[string][]model.TrackDTO{
			"AudiusMusicClient": {
				{ID: "au-1", Source: "AudiusMusicClient", Title: "Morning Breeze", Artist: "Open Horizon", Album: "Open Air", Duration: 269, Qualities: []model.QualityDTO{{ID: "q-flac", Label: "FLAC 无损", Lossless: true}}},
			},
			"FMAMusicClient": {
				{ID: "fma-1", Source: "FMAMusicClient", Title: "Morning Breeze (Live)", Artist: "Open Horizon", Duration: 280, Qualities: []model.QualityDTO{{ID: "q-320", Label: "MP3 320k"}}},
			},
		},
	}

	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.PublicURL = "http://127.0.0.1:8080"
	cfg.TGEnabled = true
	cfg.TGToken = "123:tok"
	cfg.TGAllowedUsers = "100"
	cfg.TGAPIURL = tgSrv.URL
	cfg.FeishuEnabled = true
	cfg.FeishuWebhook = hookSrv.URL + "/open-apis/bot/v2/hook/abc"
	cfg.FeishuBotEnabled = true
	cfg.FeishuAllowedUsers = "ou_1"
	cfg.FeishuAppID = "app1"
	cfg.FeishuAppSecret = "sec1"
	cfg.FeishuVerificationToken = "vt1"
	cfg.FeishuEncryptKey = "ek1"
	cfg.FeishuAPIURL = hookSrv.URL

	svc, err := NewService(ctx, ServiceOptions{
		Store: st, Pool: pool, Backend: be, Initial: cfg, Now: func() time.Time { return now },
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	t.Cleanup(svc.Close)

	// 2. Initialize webhook notices (marks old-job as already seen) then complete a new download with active -> complete processing.
	if err := svc.ProcessDownloadNoticesOnce(ctx); err != nil {
		t.Fatalf("init ProcessDownloadNoticesOnce: %v", err)
	}
	base.mu.Lock()
	base.jobs["new-job"] = model.DownloadJobDTO{
		ID: "new-job", Status: "complete", File: &rel,
		Track:      model.TrackDTO{Title: "Morning Breeze", Artist: "Open Horizon"},
		Processing: &model.DownloadProcessingDTO{ID: "p1", Status: "tagging", Detail: "刮削中"},
	}
	base.mu.Unlock()
	if err := svc.ProcessDownloadNoticesOnce(ctx); err != nil {
		t.Fatalf("ProcessDownloadNoticesOnce while active: %v", err)
	}
	if _, err := svc.ProcessOutboxOnce(ctx, 20); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	if len(hookCalls) != 0 {
		t.Fatalf("expected no webhook notification while processing is still tagging, got %v", hookCalls)
	}
	mu.Unlock()

	base.mu.Lock()
	j := base.jobs["new-job"]
	j.Processing = &model.DownloadProcessingDTO{ID: "p1", Status: "complete", Detail: "已整理入库"}
	base.jobs["new-job"] = j
	base.mu.Unlock()
	if err := svc.ProcessDownloadNoticesOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.ProcessOutboxOnce(ctx, 20); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	if len(hookCalls) != 1 || !strings.Contains(hookCalls[0], "Morning Breeze") || !strings.Contains(hookCalls[0], "/downloads/") || !strings.Contains(hookCalls[0], "已整理入库") {
		t.Fatalf("expected webhook download notice after processing completed, got %v", hookCalls)
	}
	mu.Unlock()

	// 3. TG catalog search produces source card with inline buttons and saves CardMsg, then editMessageText on source switch.
	if err := svc.handleCommand(ctx, InboundMessage{
		ID: "aaaaaaaaaaaaaaaa", Channel: ChannelTelegram, User: "100", PlatformMsgID: "tg-s1", Text: "Morning Breeze",
	}); err != nil {
		t.Fatalf("TG catalog search: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		_, _ = svc.ProcessOutboxOnce(ctx, 20)
		mu.Lock()
		joinedM := strings.Join(tgMethods, ",")
		mu.Unlock()
		if strings.Contains(joinedM, "sendMessage") && strings.Contains(joinedM, "editMessageText") {
			break
		}
		time.Sleep(30 * time.Millisecond)
	}
	mu.Lock()
	methodsSnapshot := append([]string(nil), tgMethods...)
	bodiesSnapshot := append([]string(nil), tgBodies...)
	mu.Unlock()
	if len(methodsSnapshot) < 2 || methodsSnapshot[0] != "sendMessage" || !strings.Contains(bodiesSnapshot[0], "Audius") || !strings.Contains(bodiesSnapshot[0], "FMA") {
		t.Fatalf("expected TG sendMessage source card + editMessageText update, got methods=%v bodies=%v", methodsSnapshot, bodiesSnapshot)
	}

	// 4. Text channel (Feishu) catalog search sends text_search_summary without inline buttons, then supports 音源 2 & 下载 2 1.
	if err := svc.handleCommand(ctx, InboundMessage{
		ID: "bbbbbbbbbbbbbbbb", Channel: ChannelFeishu, User: "ou_1", PlatformMsgID: "fs-s1", Text: "Morning Breeze",
	}); err != nil {
		t.Fatalf("Feishu catalog search: %v", err)
	}
	var fsSummaryFound bool
	summaryDeadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(summaryDeadline) {
		rows, _ := st.Reader().QueryContext(ctx, `SELECT payload_json FROM outbox WHERE channel = 'feishu' AND recipient_key = 'ou_1'`)
		if rows != nil {
			for rows.Next() {
				var raw string
				_ = rows.Scan(&raw)
				var p OutboxPayload
				_ = json.Unmarshal([]byte(raw), &p)
				if strings.Contains(p.Text, "【搜索结果") && strings.Contains(p.Text, "▸ 搜索源：Audius") && strings.Contains(p.Text, "▸ 搜索源：FMA") && len(p.Buttons) == 0 {
					fsSummaryFound = true
				}
			}
			_ = rows.Close()
		}
		if fsSummaryFound {
			break
		}
		time.Sleep(30 * time.Millisecond)
	}
	if !fsSummaryFound {
		t.Fatal("expected Feishu text_search_summary with multi-source groups and zero buttons")
	}
	if err := svc.handleCommand(ctx, InboundMessage{
		ID: "cccccccccccccccc", Channel: ChannelFeishu, User: "ou_1", PlatformMsgID: "fs-dl1", Text: "下载 2 1",
	}); err != nil {
		t.Fatalf("Feishu 下载 2 1 failed: %v", err)
	}
	if _, ok := base.jobs["aabbccdd00000003"]; !ok {
		// Verify FMAMusicClient track fma-1 was enqueued.
		foundFMA1 := false
		for _, j := range base.jobs {
			if j.Track.ID == "fma-1" {
				foundFMA1 = true
			}
		}
		if !foundFMA1 {
			t.Fatalf("expected 下载 2 1 to enqueue fma-1 from FMA source, got jobs=%+v", base.jobs)
		}
	}

	// 5. TG sendAudio HTTP 400 format rejection falls back to sendDocument.
	if err := svc.enqueueReply(ctx, ChannelTelegram, "100", "fmt-fallback", OutboxPayload{
		Text:  "完成测试",
		JobID: "new-job",
		File:  true,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.ProcessOutboxOnce(ctx, 10); err != nil {
		t.Fatalf("sendAudio fallback ProcessOutboxOnce: %v", err)
	}
	mu.Lock()
	joinedTG := strings.Join(tgMethods, ",")
	mu.Unlock()
	if !strings.Contains(joinedTG, "sendAudio,sendDocument") {
		t.Fatalf("expected sendAudio 400 format error to fallback to sendDocument, got methods=%v", tgMethods)
	}
}

func intPtr(v int) *int { return &v }

func TestCompletedBotTaskChoicePromptAndShortPrefixCommands(t *testing.T) {
	ctx := context.Background()
	st := newBotTestStore(t)
	backend := newMockBackend()

	tmpDir := t.TempDir()
	audioPath := filepath.Join(tmpDir, "demo.flac")
	if err := os.WriteFile(audioPath, []byte("fLaC-demo-audio-payload"), 0o644); err != nil {
		t.Fatalf("write audio: %v", err)
	}
	backend.files["asset:asset-demo-1"] = audioPath
	backend.files["asset:asset-demo-2"] = audioPath

	tgJobID := "688a5d76-1111-2222-3333-444455556666"
	wecomJobID := "75ced4d1-1111-2222-3333-444455557777"
	feishuJobID := "91ab2c3d-1111-2222-3333-444455558888"
	tok1 := "asset:asset-demo-1"
	tok2 := "asset:asset-demo-2"
	now := time.Now().Unix()

	backend.jobs[tgJobID] = model.DownloadJobDTO{
		ID:        tgJobID,
		QualityID: "flac",
		Status:    "complete",
		Bytes:     23,
		File:      &tok1,
		Track:     model.TrackDTO{ID: "pub-tg-1", Title: "晨风序曲 (Live)", Artist: "林风"},
	}
	backend.jobs[wecomJobID] = model.DownloadJobDTO{
		ID:        wecomJobID,
		QualityID: "flac",
		Status:    "complete",
		Bytes:     23,
		File:      &tok2,
		Track:     model.TrackDTO{ID: "pub-wecom-1", Title: "晨风序曲", Artist: "林风"},
	}
	backend.jobs[feishuJobID] = model.DownloadJobDTO{
		ID:        feishuJobID,
		QualityID: "flac",
		Status:    "complete",
		Bytes:     23,
		File:      &tok2,
		Track:     model.TrackDTO{ID: "pub-feishu-1", Title: "海风轻吟", Artist: "林风"},
	}

	putTaskOwner := func(jobID, channel, user string) {
		raw, _ := json.Marshal(botTaskOwner{
			ID:      jobID,
			Owner:   channel + ":" + user,
			Channel: channel,
			User:    user,
		})
		if _, err := st.PutDurableRecord(ctx, nsBotTask, jobID, string(raw), 0); err != nil {
			t.Fatalf("PutDurableRecord %s: %v", jobID, err)
		}
	}
	putTaskOwner(tgJobID, ChannelTelegram, "1001")
	putTaskOwner(wecomJobID, ChannelWeCom, "wecom-u1")
	putTaskOwner(feishuJobID, ChannelFeishu, "fs-u1")

	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.PublicURL = "https://music.example.com"
	cfg.TGEnabled = true
	cfg.TGToken = "123456:tok"
	cfg.TGAllowedUsers = "1001,1002"
	cfg.WeComEnabled = true
	cfg.WeComCorpID = "ww123"
	cfg.WeComAgentID = "1000001"
	cfg.WeComSecret = "sec"
	cfg.WeComToken = "wc-tok"
	cfg.WeComAESKey = strings.Repeat("b", 43)
	cfg.WeComAllowedUsers = "wecom-u1"
	cfg.FeishuBotEnabled = true
	cfg.FeishuAppID = "cli_123"
	cfg.FeishuAppSecret = "sec"
	cfg.FeishuVerificationToken = "fs_vtok"
	cfg.FeishuEncryptKey = "fs_ekey"
	cfg.FeishuAllowedUsers = "fs-u1"

	svc, err := NewService(ctx, ServiceOptions{Store: st, Backend: backend, Initial: cfg})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	t.Cleanup(svc.Close)

	if err := svc.ProcessDownloadNoticesOnce(ctx); err != nil {
		t.Fatalf("ProcessDownloadNoticesOnce: %v", err)
	}

	readLatestOutbox := func(channel, user string) OutboxPayload {
		var payloadJSON string
		if err := st.Reader().QueryRowContext(ctx, `
			SELECT payload_json FROM outbox WHERE channel = ? AND recipient_key = ? ORDER BY created_at DESC LIMIT 1
		`, channel, user).Scan(&payloadJSON); err != nil {
			t.Fatalf("query outbox (%s/%s): %v", channel, user, err)
		}
		var payload OutboxPayload
		if err := json.Unmarshal([]byte(payloadJSON), &payload); err != nil {
			t.Fatalf("decode OutboxPayload: %v", err)
		}
		return payload
	}

	// 1. TG completion notice must prompt user to choose without auto-uploading audio.
	tgNotice := readLatestOutbox(ChannelTelegram, "1001")
	if tgNotice.File {
		t.Fatalf("expected TG completion notice not to auto-send file, got File=true")
	}
	if !strings.Contains(tgNotice.Text, "下载完成") || !strings.Contains(tgNotice.Text, "688a5d76") {
		t.Fatalf("unexpected TG completion prompt text: %q", tgNotice.Text)
	}
	var cbList []string
	for _, row := range tgNotice.Buttons {
		for _, btn := range row {
			cbList = append(cbList, btn.Text+"=>"+btn.CallbackData)
		}
	}
	joinedCB := strings.Join(cbList, "|")
	if !strings.Contains(joinedCB, "获取链接=>/link 688a5d76") || !strings.Contains(joinedCB, "发送文件=>/file 688a5d76") {
		t.Fatalf("expected choice buttons on TG completion prompt, got %v", cbList)
	}

	// 2. WeCom plain-text channel completion notice instructs 获取链接 / 链接 <shortID>.
	wecomNotice := readLatestOutbox(ChannelWeCom, "wecom-u1")
	if wecomNotice.File {
		t.Fatalf("expected WeCom completion notice not to set File=true")
	}
	if !strings.Contains(wecomNotice.Text, "获取链接") || !strings.Contains(wecomNotice.Text, "75ced4d1") {
		t.Fatalf("unexpected WeCom completion prompt text: %q", wecomNotice.Text)
	}

	// 3. TG user sends plain Chinese "获取链接" without task ID -> resolves latest completed task.
	if err := svc.ExecuteInbound(ctx, InboundMessage{
		ID:        "msg-tg-link-latest",
		Channel:   ChannelTelegram,
		User:      "1001",
		Text:      "获取链接",
		CreatedAt: now + 10,
	}); err != nil {
		t.Fatalf("获取链接 failed: %v", err)
	}
	tgLinkPayload := readLatestOutbox(ChannelTelegram, "1001")
	if tgLinkPayload.URL == "" || !strings.Contains(tgLinkPayload.URL, "/downloads/") || tgLinkPayload.JobID != tgJobID {
		t.Fatalf("expected signed URL for TG job %s, got %+v", tgJobID, tgLinkPayload)
	}

	// 4. TG user sends "/link 688a5d76" (8-char short prefix) -> resolves exact job.
	if err := svc.ExecuteInbound(ctx, InboundMessage{
		ID:        "msg-tg-link-short",
		Channel:   ChannelTelegram,
		User:      "1001",
		Text:      "/link 688a5d76",
		CreatedAt: now + 11,
	}); err != nil {
		t.Fatalf("/link 688a5d76 failed: %v", err)
	}
	if p := readLatestOutbox(ChannelTelegram, "1001"); !strings.Contains(p.URL, "/downloads/") || p.JobID != tgJobID {
		t.Fatalf("expected /link 688a5d76 URL for %s, got %+v", tgJobID, p)
	}

	// 5. TG user sends "发送文件" -> schedules File=true delivery.
	if err := svc.ExecuteInbound(ctx, InboundMessage{
		ID:        "msg-tg-send-file",
		Channel:   ChannelTelegram,
		User:      "1001",
		Text:      "发送文件",
		CreatedAt: now + 12,
	}); err != nil {
		t.Fatalf("发送文件 failed: %v", err)
	}
	if p := readLatestOutbox(ChannelTelegram, "1001"); !p.File || p.JobID != tgJobID {
		t.Fatalf("expected 发送文件 to enqueue File=true for %s, got %+v", tgJobID, p)
	}

	// 6. Another TG user cannot access 1001's task via short prefix.
	if err := svc.ExecuteInbound(ctx, InboundMessage{
		ID:        "msg-tg-other-user",
		Channel:   ChannelTelegram,
		User:      "1002",
		Text:      "/link 688a5d76",
		CreatedAt: now + 13,
	}); err != nil {
		t.Fatalf("ExecuteInbound for user 1002 failed unexpectedly: %v", err)
	}
	if p := readLatestOutbox(ChannelTelegram, "1002"); !strings.Contains(p.Text, "任务不存在或不属于你") || p.URL != "" {
		t.Fatalf("expected ownership rejection message for user 1002, got %+v", p)
	}

	// 7. Plain-text channel Feishu invoking /file <shortID> falls back cleanly to signed link.
	if err := svc.ExecuteInbound(ctx, InboundMessage{
		ID:        "msg-fs-file-fallback",
		Channel:   ChannelFeishu,
		User:      "fs-u1",
		Text:      "/file 91ab2c3d",
		CreatedAt: now + 14,
	}); err != nil {
		t.Fatalf("Feishu /file 91ab2c3d failed: %v", err)
	}
	if p := readLatestOutbox(ChannelFeishu, "fs-u1"); p.File || !strings.Contains(p.URL, "/downloads/") || p.JobID != feishuJobID {
		t.Fatalf("expected Feishu /file fallback to signed link without File=true for %s, got %+v", feishuJobID, p)
	}
}

func TestWeComEmptyAllowedUsersPermitsEnterpriseMembers(t *testing.T) {
	ctx := context.Background()
	cfg := DefaultConfig()
	cfg.PublicURL = "https://bot.example.com"
	cfg.WeComEnabled = true
	cfg.WeComCorpID = "ww_test"
	cfg.WeComAgentID = "1000001"
	cfg.WeComSecret = "wc_sec"
	cfg.WeComToken = "wc_tok"
	cfg.WeComAESKey = strings.Repeat("a", 43)
	cfg.WeComAllowedUsers = ""

	svc, err := NewService(ctx, ServiceOptions{Store: newBotTestStore(t), Initial: cfg})
	if err != nil {
		t.Fatalf("NewService with empty WeComAllowedUsers failed: %v", err)
	}
	t.Cleanup(svc.Close)

	if !svc.Allowed(ChannelWeCom, "zhangsan") {
		t.Fatal("expected empty WeComAllowedUsers to allow visible enterprise members")
	}

	bad := cfg
	bad.WeComAllowedUsers = "zhangsan, downloads"
	if _, err := ValidateConfig(bad); err == nil {
		t.Fatal("expected ValidateConfig to reject reserved user identifier downloads")
	}
}

func TestTelegramDownloadSizeAndMergedSingleMessageEdit(t *testing.T) {
	ctx := context.Background()
	st := newBotTestStore(t)
	backend := newMockBackend()

	tmpDir := t.TempDir()
	audioPath := filepath.Join(tmpDir, "merged.flac")
	audioBytes := []byte("fLaC-test-merged-payload-123456")
	if err := os.WriteFile(audioPath, audioBytes, 0o644); err != nil {
		t.Fatalf("write audio: %v", err)
	}
	fileTok := "asset:asset-merged-1"
	backend.files[fileTok] = audioPath

	var mu sync.Mutex
	var tgMethods []string
	var sentBodies []map[string]any
	var nextMsgID int64 = 700
	failEditWith400 := false

	tgSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &body)

		mu.Lock()
		defer mu.Unlock()
		parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
		method := parts[len(parts)-1]
		tgMethods = append(tgMethods, method)
		sentBodies = append(sentBodies, body)

		if method == "editMessageText" && failEditWith400 {
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"ok":          false,
				"error_code":  400,
				"description": "Bad Request: message to edit not found",
			})
			return
		}

		msgID := nextMsgID
		if method == "sendMessage" {
			nextMsgID++
		} else if mid, ok := body["message_id"].(float64); ok && mid > 0 {
			msgID = int64(mid)
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"ok": true,
			"result": map[string]any{
				"message_id": msgID,
			},
		})
	}))
	defer tgSrv.Close()

	cfg := DefaultConfig()
	cfg.AllowTestLoopback = true
	cfg.PublicURL = "https://music.example.com"
	cfg.TGEnabled = true
	cfg.TGAPIURL = tgSrv.URL
	cfg.TGToken = "123456:tok"
	cfg.TGAllowedUsers = "1001"

	svc, err := NewService(ctx, ServiceOptions{Store: st, Backend: backend, Initial: cfg})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	t.Cleanup(svc.Close)

	now := time.Now().Unix()
	sz := int64(25 * 1024 * 1024) // 25.0 MB
	searchID := "abcd1234"
	searchKey := "tg:1001:" + searchID
	card := botSearchResult{
		ID:      searchKey,
		Keyword: "星辰",
		Expires: now + 600,
		View:    "source",
		SourceStates: []botSourceState{{
			ID:     "src1",
			Label:  "TestSource",
			State:  "ready",
			Tracks: []model.TrackDTO{{
				ID:        "pub-star-1",
				Title:     "星辰大海",
				Artist:    "测试歌手",
				SizeBytes: &sz,
				Qualities: []model.QualityDTO{{ID: "flac", Label: "无损 FLAC"}},
			}},
		}},
	}
	if err := svc.saveSearchResult(ctx, card); err != nil {
		t.Fatalf("saveSearchResult: %v", err)
	}

	// 1. Trigger download via TG inline button: even when mockBackend.EnqueueDownload
	// returns job.Track without SizeBytes, bot falls back to search card size and
	// tags TaskCardID so initial sendMessage records message_id=700.
	if err := svc.ExecuteInbound(ctx, InboundMessage{
		ID:        "cb-dl-1",
		Channel:   ChannelTelegram,
		User:      "1001",
		Text:      "/download " + searchID + " 1 0",
		CreatedAt: now,
	}); err != nil {
		t.Fatalf("ExecuteInbound /dl: %v", err)
	}
	if _, err := svc.ProcessOutboxOnce(ctx, 10); err != nil {
		t.Fatalf("ProcessOutboxOnce #1: %v", err)
	}

	mu.Lock()
	if len(tgMethods) != 1 || tgMethods[0] != "sendMessage" {
		t.Fatalf("expected 1 sendMessage for enqueue, got %v", tgMethods)
	}
	enqText, _ := sentBodies[0]["text"].(string)
	if !strings.Contains(enqText, "预计大小：25.0 MB") {
		t.Fatalf("expected predicted size 25.0 MB in TG enqueue message, got %q", enqText)
	}
	mu.Unlock()

	// Locate the job created by mockBackend and mark it completed.
	var createdJobID string
	backend.mu.Lock()
	for id, j := range backend.jobs {
		createdJobID = id
		j.Status = "complete"
		j.File = &fileTok
		j.Bytes = int64(len(audioBytes))
		backend.jobs[id] = j
	}
	backend.mu.Unlock()
	if createdJobID == "" {
		t.Fatal("expected mockBackend job to be created")
	}

	// Verify botTaskOwner persisted MessageID=700.
	if mid := svc.loadTaskCardMessageID(ctx, createdJobID); mid == nil || *mid != 700 {
		t.Fatalf("expected persisted task card message_id=700, got %v", mid)
	}

	// 2. Process completion notice -> must use editMessageText on message_id=700!
	if err := svc.ProcessDownloadNoticesOnce(ctx); err != nil {
		t.Fatalf("ProcessDownloadNoticesOnce: %v", err)
	}
	if _, err := svc.ProcessOutboxOnce(ctx, 10); err != nil {
		t.Fatalf("ProcessOutboxOnce #2: %v", err)
	}

	mu.Lock()
	if len(tgMethods) != 2 || tgMethods[1] != "editMessageText" {
		t.Fatalf("expected 2nd TG call to be editMessageText merging queue+complete, got %v", tgMethods)
	}
	if gotID, _ := sentBodies[1]["message_id"].(float64); int64(gotID) != 700 {
		t.Fatalf("expected editMessageText on message_id=700, got %v", sentBodies[1]["message_id"])
	}
	completeText, _ := sentBodies[1]["text"].(string)
	if !strings.Contains(completeText, "下载完成") {
		t.Fatalf("expected merged message to show 下载完成, got %q", completeText)
	}
	mu.Unlock()

	// 3. Simulate missing message (HTTP 400 on editMessageText): must gracefully fallback to sendMessage.
	mu.Lock()
	failEditWith400 = true
	mu.Unlock()
	if err := svc.enqueueReply(ctx, ChannelTelegram, "1001", "taskcard:"+createdJobID+":retrytest", OutboxPayload{
		Text:       "重试完成",
		TaskCardID: createdJobID,
	}); err != nil {
		t.Fatalf("enqueueReply fallback test: %v", err)
	}
	if _, err := svc.ProcessOutboxOnce(ctx, 10); err != nil {
		t.Fatalf("ProcessOutboxOnce fallback: %v", err)
	}
	mu.Lock()
	if len(tgMethods) != 4 || tgMethods[2] != "editMessageText" || tgMethods[3] != "sendMessage" {
		t.Fatalf("expected editMessageText then sendMessage fallback, got %v", tgMethods)
	}
	mu.Unlock()
}

var _ = transport.NewPool
