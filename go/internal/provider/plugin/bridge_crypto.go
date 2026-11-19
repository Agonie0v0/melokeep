package plugin

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"hash"
	"net/http"
	"net/url"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/dop251/goja"
)

const maxRandomBytesLen = 4096

func installCryptoAndEncodingBridge(vm *goja.Runtime, ctxObj *goja.Object) {
	installCryptoBridge(vm, ctxObj)
	installEncodingBridge(vm, ctxObj)
}

func installCryptoBridge(vm *goja.Runtime, ctxObj *goja.Object) {
	cryptoObj := vm.NewObject()

	_ = cryptoObj.Set("md5", func(call goja.FunctionCall) goja.Value {
		data := argBytes(call, 0)
		sum := md5.Sum(data)
		return vm.ToValue(hex.EncodeToString(sum[:]))
	})

	_ = cryptoObj.Set("sha1", func(call goja.FunctionCall) goja.Value {
		data := argBytes(call, 0)
		sum := sha1.Sum(data)
		return vm.ToValue(hex.EncodeToString(sum[:]))
	})

	_ = cryptoObj.Set("sha256", func(call goja.FunctionCall) goja.Value {
		data := argBytes(call, 0)
		sum := sha256.Sum256(data)
		return vm.ToValue(hex.EncodeToString(sum[:]))
	})

	_ = cryptoObj.Set("hmacSha1", func(call goja.FunctionCall) goja.Value {
		key := argBytes(call, 0)
		data := argBytes(call, 1)
		mac := hmac.New(sha1.New, key)
		_, _ = mac.Write(data)
		return vm.ToValue(hex.EncodeToString(mac.Sum(nil)))
	})

	_ = cryptoObj.Set("hmacSha256", func(call goja.FunctionCall) goja.Value {
		key := argBytes(call, 0)
		data := argBytes(call, 1)
		mac := hmac.New(sha256.New, key)
		_, _ = mac.Write(data)
		return vm.ToValue(hex.EncodeToString(mac.Sum(nil)))
	})

	_ = cryptoObj.Set("randomBytes", func(call goja.FunctionCall) goja.Value {
		if len(call.Arguments) == 0 || isNilOrUndefined(call.Arguments[0]) {
			panic(vm.NewGoError(model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "randomBytes 缺少长度参数")))
		}
		n := int(call.Arguments[0].ToInteger())
		if n < 0 || n > maxRandomBytesLen {
			panic(vm.NewGoError(model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("randomBytes 长度必须在 0..%d 之间", maxRandomBytesLen))))
		}
		if n == 0 {
			return vm.ToValue("")
		}
		buf := make([]byte, n)
		if _, err := rand.Read(buf); err != nil {
			panic(vm.NewGoError(model.WrapError(model.ErrInternal, http.StatusInternalServerError, "生成随机字节失败", err)))
		}
		return vm.ToValue(hex.EncodeToString(buf))
	})

	_ = cryptoObj.Set("aesEncrypt", func(call goja.FunctionCall) goja.Value {
		opts, err := parseAESOpts(vm, call, true)
		if err != nil {
			panic(vm.NewGoError(err))
		}
		out, err := aesEncryptOp(opts)
		if err != nil {
			panic(vm.NewGoError(err))
		}
		return vm.ToValue(out)
	})

	_ = cryptoObj.Set("aesDecrypt", func(call goja.FunctionCall) goja.Value {
		opts, err := parseAESOpts(vm, call, false)
		if err != nil {
			panic(vm.NewGoError(err))
		}
		out, err := aesDecryptOp(opts)
		if err != nil {
			panic(vm.NewGoError(err))
		}
		return vm.ToValue(out)
	})

	_ = cryptoObj.Set("rsaEncrypt", func(call goja.FunctionCall) goja.Value {
		out, err := rsaEncryptOp(vm, call)
		if err != nil {
			panic(vm.NewGoError(err))
		}
		return vm.ToValue(out)
	})

	_ = ctxObj.Set("crypto", cryptoObj)
}

func installEncodingBridge(vm *goja.Runtime, ctxObj *goja.Object) {
	encObj := vm.NewObject()

	b64Encode := func(call goja.FunctionCall) goja.Value {
		return vm.ToValue(base64.StdEncoding.EncodeToString(argBytes(call, 0)))
	}
	b64Decode := func(call goja.FunctionCall) goja.Value {
		raw := strings.TrimSpace(argString(call, 0))
		decoded, err := base64.StdEncoding.DecodeString(raw)
		if err != nil {
			panic(vm.NewGoError(model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "base64 解码失败", err)))
		}
		return vm.ToValue(string(decoded))
	}
	hexEncode := func(call goja.FunctionCall) goja.Value {
		return vm.ToValue(hex.EncodeToString(argBytes(call, 0)))
	}
	hexDecode := func(call goja.FunctionCall) goja.Value {
		raw := strings.TrimSpace(argString(call, 0))
		decoded, err := hex.DecodeString(raw)
		if err != nil {
			panic(vm.NewGoError(model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "hex 解码失败", err)))
		}
		return vm.ToValue(string(decoded))
	}
	urlEncode := func(call goja.FunctionCall) goja.Value {
		return vm.ToValue(url.QueryEscape(argString(call, 0)))
	}
	urlDecode := func(call goja.FunctionCall) goja.Value {
		decoded, err := url.QueryUnescape(argString(call, 0))
		if err != nil {
			panic(vm.NewGoError(model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "url 解码失败", err)))
		}
		return vm.ToValue(decoded)
	}

	_ = encObj.Set("base64Encode", b64Encode)
	_ = encObj.Set("base64Decode", b64Decode)
	_ = encObj.Set("hexEncode", hexEncode)
	_ = encObj.Set("hexDecode", hexDecode)
	_ = encObj.Set("urlEncode", urlEncode)
	_ = encObj.Set("urlDecode", urlDecode)

	b64Sub := vm.NewObject()
	_ = b64Sub.Set("encode", b64Encode)
	_ = b64Sub.Set("decode", b64Decode)
	_ = encObj.Set("base64", b64Sub)

	hexSub := vm.NewObject()
	_ = hexSub.Set("encode", hexEncode)
	_ = hexSub.Set("decode", hexDecode)
	_ = encObj.Set("hex", hexSub)

	urlSub := vm.NewObject()
	_ = urlSub.Set("encode", urlEncode)
	_ = urlSub.Set("decode", urlDecode)
	_ = encObj.Set("url", urlSub)

	_ = ctxObj.Set("encoding", encObj)
}

func argString(call goja.FunctionCall, idx int) string {
	if idx >= len(call.Arguments) || isNilOrUndefined(call.Arguments[idx]) {
		return ""
	}
	return call.Arguments[idx].String()
}

func argBytes(call goja.FunctionCall, idx int) []byte {
	if idx >= len(call.Arguments) || isNilOrUndefined(call.Arguments[idx]) {
		return nil
	}
	v := call.Arguments[idx]
	switch typed := v.Export().(type) {
	case []byte:
		return typed
	case goja.ArrayBuffer:
		return typed.Bytes()
	default:
		return []byte(v.String())
	}
}

type aesOpts struct {
	Data           []byte
	Key            []byte
	IV             []byte
	AAD            []byte
	Mode           string
	Padding        string
	OutputEncoding string
}

func parseAESOpts(vm *goja.Runtime, call goja.FunctionCall, encrypt bool) (aesOpts, error) {
	if len(call.Arguments) == 0 || isNilOrUndefined(call.Arguments[0]) {
		return aesOpts{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES 缺少参数")
	}
	obj, ok := call.Arguments[0].(*goja.Object)
	if !ok {
		return aesOpts{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES 参数必须为对象")
	}

	defaultInEnc := "utf8"
	defaultOutEnc := "base64"
	if !encrypt {
		defaultInEnc = "base64"
		defaultOutEnc = "utf8"
	}

	inEnc := strings.ToLower(strings.TrimSpace(readJSString(obj, "inputEncoding")))
	if inEnc == "" {
		inEnc = defaultInEnc
	}
	outEnc := strings.ToLower(strings.TrimSpace(readJSString(obj, "outputEncoding")))
	if outEnc == "" {
		outEnc = defaultOutEnc
	}
	keyEnc := strings.ToLower(strings.TrimSpace(readJSString(obj, "keyEncoding")))
	if keyEnc == "" {
		keyEnc = "utf8"
	}
	ivEnc := strings.ToLower(strings.TrimSpace(readJSString(obj, "ivEncoding")))
	if ivEnc == "" {
		ivEnc = "utf8"
	}

	dataBytes, err := decodeTextBytes(readJSString(obj, "data"), inEnc)
	if err != nil {
		return aesOpts{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "AES data 解码失败", err)
	}
	keyBytes, err := decodeTextBytes(readJSString(obj, "key"), keyEnc)
	if err != nil {
		return aesOpts{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "AES key 解码失败", err)
	}
	if len(keyBytes) != 16 && len(keyBytes) != 24 && len(keyBytes) != 32 {
		return aesOpts{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES key 长度必须为 16/24/32 字节")
	}

	rawIV := readJSString(obj, "iv", "nonce")
	ivBytes, err := decodeTextBytes(rawIV, ivEnc)
	if err != nil {
		return aesOpts{}, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "AES iv 解码失败", err)
	}
	var aadBytes []byte
	if aadStr := readJSString(obj, "aad"); aadStr != "" {
		aadBytes = []byte(aadStr)
	}

	mode := strings.ToUpper(strings.TrimSpace(readJSString(obj, "mode")))
	if mode == "" {
		mode = "CBC"
	}
	padding := strings.ToLower(strings.TrimSpace(readJSString(obj, "padding")))
	if padding == "" {
		padding = "pkcs7"
	}

	_ = vm
	return aesOpts{
		Data:           dataBytes,
		Key:            keyBytes,
		IV:             ivBytes,
		AAD:            aadBytes,
		Mode:           mode,
		Padding:        padding,
		OutputEncoding: outEnc,
	}, nil
}

func aesEncryptOp(opts aesOpts) (string, error) {
	block, err := aes.NewCipher(opts.Key)
	if err != nil {
		return "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "初始化 AES 失败", err)
	}

	var ciphertext []byte
	switch opts.Mode {
	case "CBC":
		if len(opts.IV) != aes.BlockSize {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES-CBC 需要 16 字节 iv")
		}
		plain, err := applyBlockPadding(opts.Data, aes.BlockSize, opts.Padding)
		if err != nil {
			return "", err
		}
		ciphertext = make([]byte, len(plain))
		cipher.NewCBCEncrypter(block, opts.IV).CryptBlocks(ciphertext, plain)
	case "ECB":
		plain, err := applyBlockPadding(opts.Data, aes.BlockSize, opts.Padding)
		if err != nil {
			return "", err
		}
		ciphertext = make([]byte, len(plain))
		for off := 0; off < len(plain); off += aes.BlockSize {
			block.Encrypt(ciphertext[off:off+aes.BlockSize], plain[off:off+aes.BlockSize])
		}
	case "GCM":
		if len(opts.IV) != 12 {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES-GCM 需要 12 字节 iv/nonce")
		}
		gcm, err := cipher.NewGCM(block)
		if err != nil {
			return "", model.WrapError(model.ErrInternal, http.StatusInternalServerError, "初始化 AES-GCM 失败", err)
		}
		ciphertext = gcm.Seal(nil, opts.IV, opts.Data, opts.AAD)
	default:
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的 AES 模式: %s", opts.Mode))
	}

	return encodeTextBytes(ciphertext, opts.OutputEncoding)
}

func aesDecryptOp(opts aesOpts) (string, error) {
	block, err := aes.NewCipher(opts.Key)
	if err != nil {
		return "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "初始化 AES 失败", err)
	}

	var plain []byte
	switch opts.Mode {
	case "CBC":
		if len(opts.IV) != aes.BlockSize {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES-CBC 需要 16 字节 iv")
		}
		if len(opts.Data) == 0 || len(opts.Data)%aes.BlockSize != 0 {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES-CBC 密文长度必须为 16 的倍数")
		}
		buf := make([]byte, len(opts.Data))
		cipher.NewCBCDecrypter(block, opts.IV).CryptBlocks(buf, opts.Data)
		plain, err = removeBlockPadding(buf, aes.BlockSize, opts.Padding)
		if err != nil {
			return "", err
		}
	case "ECB":
		if len(opts.Data) == 0 || len(opts.Data)%aes.BlockSize != 0 {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES-ECB 密文长度必须为 16 的倍数")
		}
		buf := make([]byte, len(opts.Data))
		for off := 0; off < len(opts.Data); off += aes.BlockSize {
			block.Decrypt(buf[off:off+aes.BlockSize], opts.Data[off:off+aes.BlockSize])
		}
		plain, err = removeBlockPadding(buf, aes.BlockSize, opts.Padding)
		if err != nil {
			return "", err
		}
	case "GCM":
		if len(opts.IV) != 12 {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "AES-GCM 需要 12 字节 iv/nonce")
		}
		gcm, err := cipher.NewGCM(block)
		if err != nil {
			return "", model.WrapError(model.ErrInternal, http.StatusInternalServerError, "初始化 AES-GCM 失败", err)
		}
		plain, err = gcm.Open(nil, opts.IV, opts.Data, opts.AAD)
		if err != nil {
			return "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "AES-GCM 解密校验失败", err)
		}
	default:
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的 AES 模式: %s", opts.Mode))
	}

	return encodeTextBytes(plain, opts.OutputEncoding)
}

func applyBlockPadding(data []byte, blockSize int, padding string) ([]byte, error) {
	switch padding {
	case "pkcs7", "pkcs5":
		pad := blockSize - (len(data) % blockSize)
		padded := make([]byte, len(data)+pad)
		copy(padded, data)
		copy(padded[len(data):], bytes.Repeat([]byte{byte(pad)}, pad))
		return padded, nil
	case "nopadding", "none":
		if len(data)%blockSize != 0 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "nopadding 模式下明文长度必须为 16 的倍数")
		}
		out := make([]byte, len(data))
		copy(out, data)
		return out, nil
	default:
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的 AES padding: %s", padding))
	}
}

func removeBlockPadding(data []byte, blockSize int, padding string) ([]byte, error) {
	switch padding {
	case "pkcs7", "pkcs5":
		if len(data) == 0 || len(data)%blockSize != 0 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "PKCS7 填充长度无效")
		}
		pad := int(data[len(data)-1])
		if pad < 1 || pad > blockSize || pad > len(data) {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "PKCS7 填充校验失败")
		}
		for i := len(data) - pad; i < len(data); i++ {
			if int(data[i]) != pad {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "PKCS7 填充校验失败")
			}
		}
		return data[:len(data)-pad], nil
	case "nopadding", "none":
		return data, nil
	default:
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的 AES padding: %s", padding))
	}
}

func rsaEncryptOp(_ *goja.Runtime, call goja.FunctionCall) (string, error) {
	if len(call.Arguments) == 0 || isNilOrUndefined(call.Arguments[0]) {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "RSA 缺少参数")
	}
	obj, ok := call.Arguments[0].(*goja.Object)
	if !ok {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "RSA 参数必须为对象")
	}

	pemStr := strings.TrimSpace(readJSString(obj, "publicKeyPem", "publicKey"))
	if pemStr == "" {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "RSA 缺少 publicKeyPem")
	}
	pubKey, err := parseRSAPublicKeyPEM(pemStr)
	if err != nil {
		return "", err
	}

	inEnc := strings.ToLower(strings.TrimSpace(readJSString(obj, "inputEncoding")))
	if inEnc == "" {
		inEnc = "utf8"
	}
	outEnc := strings.ToLower(strings.TrimSpace(readJSString(obj, "outputEncoding")))
	if outEnc == "" {
		outEnc = "base64"
	}
	dataBytes, err := decodeTextBytes(readJSString(obj, "data"), inEnc)
	if err != nil {
		return "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "RSA data 解码失败", err)
	}

	padding := strings.ToLower(strings.TrimSpace(readJSString(obj, "padding")))
	if padding == "" {
		padding = "pkcs1v15"
	}
	var encrypted []byte
	switch padding {
	case "pkcs1v15":
		encrypted, err = rsa.EncryptPKCS1v15(rand.Reader, pubKey, dataBytes)
	case "oaep":
		hashName := strings.ToLower(strings.TrimSpace(readJSString(obj, "hash")))
		var h hash.Hash
		switch hashName {
		case "", "sha256":
			h = sha256.New()
		case "sha1":
			h = sha1.New()
		default:
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的 RSA-OAEP 哈希算法: %s", hashName))
		}
		encrypted, err = rsa.EncryptOAEP(h, rand.Reader, pubKey, dataBytes, nil)
	default:
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的 RSA padding: %s", padding))
	}
	if err != nil {
		return "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "RSA 加密失败", err)
	}
	return encodeTextBytes(encrypted, outEnc)
}

func parseRSAPublicKeyPEM(rawPEM string) (*rsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(rawPEM))
	if block == nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "RSA 公钥 PEM 格式无效")
	}
	if pubAny, err := x509.ParsePKIXPublicKey(block.Bytes); err == nil {
		if rsaPub, ok := pubAny.(*rsa.PublicKey); ok {
			return rsaPub, nil
		}
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "PEM 公钥不是 RSA 类型")
	}
	if rsaPub, err := x509.ParsePKCS1PublicKey(block.Bytes); err == nil {
		return rsaPub, nil
	}
	return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "解析 RSA 公钥失败", errors.New("unsupported key encoding"))
}

func decodeTextBytes(s, enc string) ([]byte, error) {
	switch strings.ToLower(strings.TrimSpace(enc)) {
	case "", "utf8", "utf-8":
		return []byte(s), nil
	case "base64":
		return base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	case "hex":
		return hex.DecodeString(strings.TrimSpace(s))
	default:
		return nil, fmt.Errorf("不支持的输入编码: %s", enc)
	}
}

func encodeTextBytes(b []byte, enc string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(enc)) {
	case "", "utf8", "utf-8":
		return string(b), nil
	case "base64":
		return base64.StdEncoding.EncodeToString(b), nil
	case "hex":
		return hex.EncodeToString(b), nil
	default:
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的输出编码: %s", enc))
	}
}
