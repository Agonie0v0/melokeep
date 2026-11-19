package bot

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

const (
	MaxCallbackBodyBytes = 65536
	CallbackWindowSecs   = 600
)

var (
	reHex32 = regexp.MustCompile(`^[a-f0-9]{32}$`)
	reHex64 = regexp.MustCompile(`^[a-f0-9]{64}$`)
)

func checkFreshTimestamp(tsRaw string, milliseconds bool, now time.Time) error {
	val, err := strconv.ParseInt(strings.TrimSpace(tsRaw), 10, 64)
	if err != nil {
		return errors.New("invalid timestamp")
	}
	if milliseconds {
		val /= 1000
	}
	diff := now.Unix() - val
	if diff < 0 {
		diff = -diff
	}
	if diff > CallbackWindowSecs {
		return errors.New("expired callback")
	}
	return nil
}

// ParseXMLFields safely parses flat <xml><tag>val</tag></xml> payloads while rejecting DTD/entities.
func ParseXMLFields(raw []byte) (map[string]string, error) {
	if len(raw) > MaxCallbackBodyBytes {
		return nil, errors.New("xml payload too large")
	}
	upper := bytes.ToUpper(raw)
	if bytes.Contains(upper, []byte("<!DOCTYPE")) || bytes.Contains(upper, []byte("<!ENTITY")) {
		return nil, errors.New("dtd/entity forbidden")
	}
	dec := xml.NewDecoder(bytes.NewReader(raw))
	dec.Strict = true
	dec.Entity = map[string]string{}
	var rootSeen bool
	var currentTag string
	fields := make(map[string]string)
	depth := 0
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if depth == 0 {
				if t.Name.Local != "xml" {
					return nil, errors.New("invalid XML root")
				}
				rootSeen = true
			} else if depth == 1 {
				currentTag = t.Name.Local
				if _, exists := fields[currentTag]; !exists {
					fields[currentTag] = ""
				}
			}
			depth++
		case xml.EndElement:
			if depth == 2 {
				currentTag = ""
			}
			depth--
		case xml.CharData:
			if depth == 2 && currentTag != "" {
				fields[currentTag] += string(t)
			}
		case xml.Directive:
			return nil, errors.New("xml directive forbidden")
		}
	}
	if !rootSeen {
		return nil, errors.New("missing xml root")
	}
	return fields, nil
}

// WeComCrypt implements WeCom AES-256-CBC (32-byte PKCS7) and SHA1 callback verification.
type WeComCrypt struct {
	token  string
	corpID []byte
	key    []byte
}

func NewWeComCrypt(token, aesKey, corpID string) (*WeComCrypt, error) {
	token = strings.TrimSpace(token)
	aesKey = strings.TrimSpace(aesKey)
	corpID = strings.TrimSpace(corpID)
	if token == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请配置企业微信 Token")
	}
	if len(aesKey) != 43 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "EncodingAESKey 必须为 43 位")
	}
	rawKey, err := base64.StdEncoding.DecodeString(aesKey + "=")
	if err != nil || len(rawKey) != 32 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "无效的 EncodingAESKey")
	}
	return &WeComCrypt{
		token:  token,
		corpID: []byte(corpID),
		key:    rawKey,
	}, nil
}

func (w *WeComCrypt) Decrypt(encrypted, signature, timestamp, nonce string) ([]byte, error) {
	if encrypted == "" || signature == "" || timestamp == "" || nonce == "" {
		return nil, errors.New("missing callback fields")
	}
	parts := []string{w.token, timestamp, nonce, encrypted}
	sort.Strings(parts)
	h := sha1.Sum([]byte(strings.Join(parts, "")))
	expected := hex.EncodeToString(h[:])
	if !hmac.Equal([]byte(expected), []byte(strings.ToLower(signature))) {
		return nil, errors.New("invalid callback signature")
	}
	ciphertext, err := base64.StdEncoding.DecodeString(encrypted)
	if err != nil || len(ciphertext) == 0 || len(ciphertext)%aes.BlockSize != 0 {
		return nil, errors.New("invalid ciphertext")
	}
	block, err := aes.NewCipher(w.key)
	if err != nil {
		return nil, err
	}
	plain := make([]byte, len(ciphertext))
	cipher.NewCBCDecrypter(block, w.key[:16]).CryptBlocks(plain, ciphertext)
	pad := int(plain[len(plain)-1])
	if pad < 1 || pad > 32 || pad > len(plain) {
		return nil, errors.New("invalid padding")
	}
	for i := len(plain) - pad; i < len(plain); i++ {
		if int(plain[i]) != pad {
			return nil, errors.New("invalid padding bytes")
		}
	}
	plain = plain[:len(plain)-pad]
	if len(plain) < 20 {
		return nil, errors.New("invalid callback body")
	}
	msgLen := int(binary.BigEndian.Uint32(plain[16:20]))
	if msgLen < 0 || 20+msgLen > len(plain) {
		return nil, errors.New("invalid callback length")
	}
	if !bytes.Equal(plain[20+msgLen:], w.corpID) {
		return nil, errors.New("invalid callback receiver")
	}
	return plain[20 : 20+msgLen], nil
}

func (w *WeComCrypt) EncryptForTest(plaintext []byte, randPrefix [16]byte) (string, error) {
	buf := make([]byte, 0, 20+len(plaintext)+len(w.corpID)+32)
	buf = append(buf, randPrefix[:]...)
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(plaintext)))
	buf = append(buf, lenBuf[:]...)
	buf = append(buf, plaintext...)
	buf = append(buf, w.corpID...)
	pad := 32 - (len(buf) % 32)
	for i := 0; i < pad; i++ {
		buf = append(buf, byte(pad))
	}
	block, err := aes.NewCipher(w.key)
	if err != nil {
		return "", err
	}
	cipher.NewCBCEncrypter(block, w.key[:16]).CryptBlocks(buf, buf)
	return base64.StdEncoding.EncodeToString(buf), nil
}

func WeComSignature(token, timestamp, nonce, encrypted string) string {
	parts := []string{token, timestamp, nonce, encrypted}
	sort.Strings(parts)
	h := sha1.Sum([]byte(strings.Join(parts, "")))
	return hex.EncodeToString(h[:])
}

// DecryptFeishuPayload decrypts an AES-256-CBC (SHA256(encryptKey)) Feishu event body.
func DecryptFeishuPayload(encryptKey, encryptedB64 string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(encryptedB64)
	if err != nil || len(raw) <= aes.BlockSize || (len(raw)-aes.BlockSize)%aes.BlockSize != 0 {
		return nil, errors.New("invalid feishu ciphertext")
	}
	key := sha256.Sum256([]byte(encryptKey))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	iv := raw[:aes.BlockSize]
	ct := raw[aes.BlockSize:]
	plain := make([]byte, len(ct))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(plain, ct)
	pad := int(plain[len(plain)-1])
	if pad < 1 || pad > aes.BlockSize || pad > len(plain) {
		return nil, errors.New("invalid feishu padding")
	}
	for i := len(plain) - pad; i < len(plain); i++ {
		if int(plain[i]) != pad {
			return nil, errors.New("invalid feishu padding bytes")
		}
	}
	return plain[:len(plain)-pad], nil
}

func VerifyFeishuSignature(timestamp, nonce, encryptKey, signature string, rawBody []byte, now time.Time) error {
	if err := checkFreshTimestamp(timestamp, false, now); err != nil {
		return err
	}
	h := sha256.New()
	_, _ = h.Write([]byte(timestamp + nonce + encryptKey))
	_, _ = h.Write(rawBody)
	expected := hex.EncodeToString(h.Sum(nil))
	if !hmac.Equal([]byte(expected), []byte(strings.ToLower(signature))) {
		return errors.New("bad feishu signature")
	}
	return nil
}

func VerifyDingTalkSignature(secret, timestamp, sign string, now time.Time) error {
	if err := checkFreshTimestamp(timestamp, true, now); err != nil {
		return err
	}
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(timestamp + "\n" + secret))
	expected := base64.StdEncoding.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(expected), []byte(sign)) {
		return errors.New("bad dingtalk signature")
	}
	return nil
}

func VerifyDiscordSignature(pubHex, timestamp, sigHex string, rawBody []byte, now time.Time) error {
	if err := checkFreshTimestamp(timestamp, false, now); err != nil {
		return err
	}
	pub, err := hex.DecodeString(pubHex)
	if err != nil || len(pub) != ed25519.PublicKeySize {
		return errors.New("invalid discord public key")
	}
	sig, err := hex.DecodeString(sigHex)
	if err != nil || len(sig) != ed25519.SignatureSize {
		return errors.New("invalid discord signature")
	}
	msg := make([]byte, 0, len(timestamp)+len(rawBody))
	msg = append(msg, timestamp...)
	msg = append(msg, rawBody...)
	if !ed25519.Verify(ed25519.PublicKey(pub), msg, sig) {
		return errors.New("bad discord signature")
	}
	return nil
}

type linkRecord struct {
	ID      string `json:"id"`
	Path    string `json:"path"`
	Expires int64  `json:"expires"`
	Size    int64  `json:"size"`
	MTimeNS int64  `json:"mtime_ns"`
}

// DownloadLinks signs and verifies temporary download URLs backed by store.DurableRecords.
type DownloadLinks struct {
	store   *store.Store
	baseURL string
	ttl     int
	key     []byte
	resolve func(ctx context.Context, relPath string) (string, int64, int64, error)
}

func NewDownloadLinks(ctx context.Context, st *store.Store, baseURL string, ttl int, resolve func(ctx context.Context, relPath string) (string, int64, int64, error)) (*DownloadLinks, error) {
	var keyBytes []byte
	if st != nil {
		rec, ok, err := st.GetDurableRecord(ctx, "bot_setting", "link_key")
		if err != nil {
			return nil, err
		}
		if ok {
			var hexStr string
			if err := json.Unmarshal([]byte(rec.BodyJSON), &hexStr); err == nil && len(hexStr) == 64 {
				keyBytes, _ = hex.DecodeString(hexStr)
			}
		}
		if len(keyBytes) != 32 {
			keyBytes = make([]byte, 32)
			if _, err := rand.Read(keyBytes); err != nil {
				return nil, err
			}
			raw, _ := json.Marshal(hex.EncodeToString(keyBytes))
			if _, err := st.PutDurableRecord(ctx, "bot_setting", "link_key", string(raw), 0); err != nil {
				return nil, err
			}
		}
	} else {
		keyBytes = make([]byte, 32)
		_, _ = rand.Read(keyBytes)
	}
	return &DownloadLinks{
		store:   st,
		baseURL: strings.TrimRight(baseURL, "/"),
		ttl:     ttl,
		key:     keyBytes,
		resolve: resolve,
	}, nil
}

func (l *DownloadLinks) Signature(ident string, expires int64) string {
	mac := hmac.New(sha256.New, l.key)
	_, _ = mac.Write([]byte(fmt.Sprintf("%s:%d", ident, expires)))
	return hex.EncodeToString(mac.Sum(nil))
}

func (l *DownloadLinks) Create(ctx context.Context, job model.DownloadJobDTO, now time.Time) (string, error) {
	if job.Status != "complete" || job.File == nil || *job.File == "" {
		return "", model.NewError(model.ErrConflict, http.StatusConflict, "任务尚未完成或文件已删除")
	}
	if l.resolve == nil {
		return "", model.NewError(model.ErrInternal, http.StatusInternalServerError, "文件解析器未配置")
	}
	_, size, mtimeNS, err := l.resolve(ctx, *job.File)
	if err != nil {
		return "", err
	}
	var identBytes [16]byte
	if _, err := rand.Read(identBytes[:]); err != nil {
		return "", err
	}
	ident := hex.EncodeToString(identBytes[:])
	expires := now.Unix() + int64(l.ttl)
	rec := linkRecord{
		ID:      ident,
		Path:    *job.File,
		Expires: expires,
		Size:    size,
		MTimeNS: mtimeNS,
	}
	if l.store != nil {
		raw, _ := json.Marshal(rec)
		if _, err := l.store.PutDurableRecord(ctx, "bot_link", ident, string(raw), 0); err != nil {
			return "", err
		}
	}
	q := url.Values{}
	q.Set("expires", strconv.FormatInt(expires, 10))
	q.Set("signature", l.Signature(ident, expires))
	return fmt.Sprintf("%s/downloads/%s?%s", l.baseURL, ident, q.Encode()), nil
}

func (l *DownloadLinks) Verify(ctx context.Context, ident string, expires int64, signature string, now time.Time) (string, error) {
	if !reHex32.MatchString(ident) || !reHex64.MatchString(signature) {
		return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "下载链接无效")
	}
	if expires <= now.Unix() || !hmac.Equal([]byte(l.Signature(ident, expires)), []byte(signature)) {
		return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "下载链接无效或已过期，请向机器人重新获取")
	}
	if l.store == nil || l.resolve == nil {
		return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "下载链接无效")
	}
	recRow, ok, err := l.store.GetDurableRecord(ctx, "bot_link", ident)
	if err != nil {
		return "", err
	}
	if !ok {
		return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "下载链接无效")
	}
	var rec linkRecord
	if err := json.Unmarshal([]byte(recRow.BodyJSON), &rec); err != nil || rec.Expires != expires {
		return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "下载链接无效")
	}
	absPath, size, mtimeNS, err := l.resolve(ctx, rec.Path)
	if err != nil {
		return "", model.NewError(model.ErrNotFound, http.StatusGone, "文件已删除或不可访问")
	}
	if size != rec.Size || mtimeNS != rec.MTimeNS {
		return "", model.NewError(model.ErrConflict, http.StatusGone, "文件已变化，请重新获取链接")
	}
	return absPath, nil
}
