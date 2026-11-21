package download

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

var contentRangeRe = regexp.MustCompile(`^bytes (\d+)-(\d+)/(\d+)$`)

type progressFn func(bytesDone int64, total *int64, detail string)

func transferHTTP(
	ctx context.Context,
	pool *transport.Pool,
	st *store.Store,
	dl model.Download,
	resolved model.ResolvedMedia,
	rc provider.RequestContext,
	partPath string,
	maxBytes int64,
	timeout time.Duration,
	onProgress progressFn,
) (int64, *int64, string, error) {
	if err := os.MkdirAll(filepath.Dir(partPath), 0o755); err != nil {
		return 0, nil, "", model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "创建下载临时目录失败", err)
	}

	fpSum := sha256.Sum256([]byte(resolved.URL))
	urlFP := hex.EncodeToString(fpSum[:])

	var offset int64
	var saved store.CheckpointRecord
	var hasSaved bool
	if cp, ok, err := st.GetTransferCheckpoint(ctx, dl.ID); err == nil && ok {
		saved = cp
		hasSaved = true
	}

	if hasSaved && saved.URLFingerprint == urlFP {
		if info, err := os.Stat(partPath); err == nil {
			diskSize := info.Size()
			durable := saved.Checkpoint.Bytes
			if saved.Complete && saved.Checkpoint.TotalBytes != nil && diskSize == *saved.Checkpoint.TotalBytes && diskSize > 0 {
				if onProgress != nil {
					onProgress(diskSize, saved.Checkpoint.TotalBytes, "已校验完整临时文件")
				}
				return diskSize, saved.Checkpoint.TotalBytes, "", nil
			}
			if saved.Validator != "" && durable > 0 {
				if diskSize > durable {
					if tErr := os.Truncate(partPath, durable); tErr == nil {
						offset = durable
					} else {
						offset = 0
					}
				} else {
					offset = diskSize
				}
			}
		}
	}

	reqOpts := buildTransferTransportOpts(resolved, rc, maxBytes, timeout)

	doFetch := func(reqOffset int64, validator string) (*http.Response, error) {
		req, err := http.NewRequest(http.MethodGet, resolved.URL, nil)
		if err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的媒体下载地址", err)
		}
		for k, v := range resolved.Headers {
			if strings.TrimSpace(k) == "" || strings.TrimSpace(v) == "" {
				continue
			}
			lk := strings.ToLower(k)
			if lk == "range" || lk == "if-range" || lk == "accept-encoding" {
				continue
			}
			req.Header.Set(k, v)
		}
		if req.Header.Get("User-Agent") == "" {
			req.Header.Set("User-Agent", provider.DefaultUserAgent)
		}
		req.Header.Set("Accept-Encoding", "identity")
		if len(resolved.Cookies) > 0 {
			req.Header.Set("Cookie", provider.FormatCookieHeader(resolved.Cookies))
		}
		if reqOffset > 0 && validator != "" {
			req.Header.Set("Range", fmt.Sprintf("bytes=%d-", reqOffset))
			req.Header.Set("If-Range", validator)
		}
		return pool.Do(ctx, req, reqOpts)
	}

	resp, err := doFetch(offset, saved.Validator)
	if err != nil {
		return 0, nil, "", err
	}
	defer resp.Body.Close()

	if offset > 0 && resp.StatusCode == http.StatusRequestedRangeNotSatisfiable {
		cr := strings.TrimSpace(resp.Header.Get("Content-Range"))
		curVal := strings.TrimSpace(resp.Header.Get(saved.ValidatorType))
		if cr == fmt.Sprintf("bytes */%d", offset) &&
			saved.Checkpoint.TotalBytes != nil && *saved.Checkpoint.TotalBytes == offset &&
			curVal != "" && curVal == saved.Validator {
			_ = st.UpsertTransferCheckpoint(ctx, model.TransferCheckpoint{
				DownloadID: dl.ID,
				Attempt:    dl.Attempt,
				TempPath:   filepath.Base(partPath),
				Bytes:      offset,
				TotalBytes: saved.Checkpoint.TotalBytes,
			}, urlFP, saved.ValidatorType, saved.Validator, true)
			if onProgress != nil {
				onProgress(offset, saved.Checkpoint.TotalBytes, "已通过 416 校验完成下载")
			}
			return offset, saved.Checkpoint.TotalBytes, resp.Header.Get("Content-Type"), nil
		}
		_ = resp.Body.Close()
		offset = 0
		resp, err = doFetch(0, "")
		if err != nil {
			return 0, nil, "", err
		}
		defer resp.Body.Close()
	}

	ce := strings.ToLower(strings.TrimSpace(resp.Header.Get("Content-Encoding")))
	encoded := ce != "" && ce != "identity"
	if encoded && resp.StatusCode == http.StatusPartialContent {
		_ = resp.Body.Close()
		offset = 0
		resp, err = doFetch(0, "")
		if err != nil {
			return 0, nil, "", err
		}
		defer resp.Body.Close()
		ce = strings.ToLower(strings.TrimSpace(resp.Header.Get("Content-Encoding")))
		encoded = ce != "" && ce != "identity"
	}

	if resp.StatusCode == http.StatusTooManyRequests {
		return 0, nil, "", provider.ClassifyError(rc.Config.Runtime.Engine, resp.StatusCode, resp.Header.Get("Retry-After"), nil)
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return 0, nil, "", &model.DomainError{
			Code:      model.ErrAuthRequired,
			Status:    resp.StatusCode,
			Detail:    fmt.Sprintf("音源拒绝下载 (%d)，链接可能已过期", resp.StatusCode),
			Retryable: true,
		}
	}
	if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone {
		return 0, nil, "", &model.DomainError{
			Code:      model.ErrTransport,
			Status:    resp.StatusCode,
			Detail:    fmt.Sprintf("音源返回异常状态码 %d", resp.StatusCode),
			Retryable: false,
		}
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		return 0, nil, "", &model.DomainError{
			Code:      model.ErrTransport,
			Status:    http.StatusBadGateway,
			Detail:    fmt.Sprintf("音源返回异常状态码 %d", resp.StatusCode),
			Retryable: resp.StatusCode >= 500,
		}
	}

	var total *int64
	if resp.StatusCode == http.StatusPartialContent {
		m := contentRangeRe.FindStringSubmatch(strings.TrimSpace(resp.Header.Get("Content-Range")))
		if len(m) != 4 {
			return 0, nil, "", model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "音源返回了无效的续传范围")
		}
		start, _ := strconv.ParseInt(m[1], 10, 64)
		end, _ := strconv.ParseInt(m[2], 10, 64)
		tot, _ := strconv.ParseInt(m[3], 10, 64)
		if start != offset || end < start || end >= tot {
			return 0, nil, "", model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "音源返回了不匹配的续传偏移")
		}
		if offset > 0 && saved.Checkpoint.TotalBytes != nil && *saved.Checkpoint.TotalBytes != tot {
			return 0, nil, "", model.NewError(model.ErrConflict, http.StatusConflict, "音源文件大小已变化，请重试")
		}
		if offset > 0 && saved.ValidatorType != "" {
			cur := strings.TrimSpace(resp.Header.Get(saved.ValidatorType))
			if cur != "" && cur != saved.Validator {
				return 0, nil, "", model.NewError(model.ErrConflict, http.StatusConflict, "音源文件版本已变化，请重试")
			}
		}
		total = &tot
	} else {
		offset = 0
		if !encoded && resp.ContentLength >= 0 {
			tot := resp.ContentLength
			total = &tot
		}
	}

	mime := strings.ToLower(strings.TrimSpace(strings.SplitN(resp.Header.Get("Content-Type"), ";", 2)[0]))
	if mime == "text/html" || mime == "application/json" || mime == "application/xml" || mime == "text/xml" {
		return 0, nil, mime, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "音源返回了错误页面，未保存为音频")
	}

	etag := strings.TrimSpace(resp.Header.Get("ETag"))
	valType := "Last-Modified"
	validator := strings.TrimSpace(resp.Header.Get("Last-Modified"))
	if etag != "" && !strings.HasPrefix(etag, "W/") {
		valType = "ETag"
		validator = etag
	}
	if offset > 0 && validator == "" {
		valType = saved.ValidatorType
		validator = saved.Validator
	}
	if encoded {
		validator = ""
	}

	flag := os.O_CREATE | os.O_WRONLY
	if offset == 0 {
		flag |= os.O_TRUNC
	} else {
		flag |= os.O_APPEND
	}
	f, err := os.OpenFile(partPath, flag, 0o600)
	if err != nil {
		return 0, nil, mime, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "无法打开下载临时文件", err)
	}

	saveCP := func(durable int64, complete bool) {
		_ = st.UpsertTransferCheckpoint(context.Background(), model.TransferCheckpoint{
			DownloadID: dl.ID,
			Attempt:    dl.Attempt,
			TempPath:   filepath.Base(partPath),
			Bytes:      durable,
			TotalBytes: total,
		}, urlFP, valType, validator, complete)
	}
	saveCP(offset, false)

	detail := "正在下载"
	if offset > 0 {
		detail = fmt.Sprintf("断点续传，从 %.2f MB 继续", float64(offset)/(1024*1024))
	} else if encoded {
		detail = "音源不支持续传：使用压缩传输，重新下载"
	} else if hasSaved {
		detail = "音源不支持续传或文件已变化，重新下载"
	}
	if onProgress != nil {
		onProgress(offset, total, detail)
	}

	buf := make([]byte, 32*1024)
	written := offset
	var lastReport time.Time
	for {
		if err := ctx.Err(); err != nil {
			_ = f.Sync()
			_ = f.Close()
			saveCP(written, false)
			if errors.Is(err, context.DeadlineExceeded) {
				return written, total, mime, &model.DomainError{Code: model.ErrDeadline, Status: http.StatusGatewayTimeout, Detail: "下载传输超时", Retryable: true, Cause: err}
			}
			return written, total, mime, model.WrapError(model.ErrCancelled, 499, "下载已暂停或取消", err)
		}
		n, rErr := resp.Body.Read(buf)
		if n > 0 {
			chunk := buf[:n]
			if written == 0 && looksLikeTextError(chunk) {
				_ = f.Close()
				_ = os.Remove(partPath)
				return 0, nil, mime, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "音源返回了错误文本，未保存为音频")
			}
			if _, wErr := f.Write(chunk); wErr != nil {
				_ = f.Sync()
				_ = f.Close()
				saveCP(written, false)
				return written, total, mime, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "写入下载临时文件失败", wErr)
			}
			written += int64(n)
			if maxBytes > 0 && written > maxBytes {
				_ = f.Sync()
				_ = f.Close()
				saveCP(written, false)
				return written, total, mime, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "下载音频超过大小上限")
			}
			if onProgress != nil && time.Since(lastReport) >= 150*time.Millisecond {
				onProgress(written, total, detail)
				lastReport = time.Now()
			}
		}
		if rErr == io.EOF {
			break
		}
		if rErr != nil {
			_ = f.Sync()
			_ = f.Close()
			saveCP(written, false)
			if de, ok := model.AsDomainError(rErr); ok {
				return written, total, mime, de
			}
			return written, total, mime, &model.DomainError{
				Code:      model.ErrTransport,
				Status:    http.StatusBadGateway,
				Detail:    "下载流中断，已保留临时文件可继续下载",
				Retryable: true,
				Cause:     rErr,
			}
		}
	}

	if err := f.Sync(); err != nil {
		_ = f.Close()
		saveCP(written, false)
		return written, total, mime, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "刷新下载临时文件失败", err)
	}
	if err := f.Close(); err != nil {
		saveCP(written, false)
		return written, total, mime, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "关闭下载临时文件失败", err)
	}

	if written == 0 || (total != nil && written != *total) {
		saveCP(written, false)
		return written, total, mime, &model.DomainError{
			Code:      model.ErrTransport,
			Status:    http.StatusBadGateway,
			Detail:    "下载未完成，已保留临时文件可继续下载",
			Retryable: true,
		}
	}

	finalTotal := written
	saveCP(written, true)
	if onProgress != nil {
		onProgress(written, &finalTotal, "下载传输完成")
	}
	return written, &finalTotal, mime, nil
}

// downloadHLS fetches and decrypts an M3U8 HLS stream (supporting master playlists,
// #EXT-X-MAP, #EXT-X-BYTERANGE, and AES-128-CBC + PKCS7) and remuxes via FFmpeg.
func downloadHLS(
	ctx context.Context,
	pool *transport.Pool,
	runner *media.Runner,
	resolved model.ResolvedMedia,
	rc provider.RequestContext,
	stageDir string,
	dlID string,
	maxBytes int64,
	timeout time.Duration,
	onProgress progressFn,
) (string, int64, error) {
	if err := os.MkdirAll(stageDir, 0o755); err != nil {
		return "", 0, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "创建 HLS 临时目录失败", err)
	}
	opts := buildTransferTransportOpts(resolved, rc, 16<<20, timeout)
	fetchWithOpts := func(rawURL string, byteRange string, reqOpts transport.RequestOptions) ([]byte, error) {
		req, err := http.NewRequest(http.MethodGet, rawURL, nil)
		if err != nil {
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的 HLS 分片地址", err)
		}
		for k, v := range resolved.Headers {
			if strings.TrimSpace(k) == "" || strings.TrimSpace(v) == "" {
				continue
			}
			req.Header.Set(k, v)
		}
		if req.Header.Get("User-Agent") == "" {
			req.Header.Set("User-Agent", provider.DefaultUserAgent)
		}
		if len(resolved.Cookies) > 0 {
			req.Header.Set("Cookie", provider.FormatCookieHeader(resolved.Cookies))
		}
		if byteRange != "" {
			req.Header.Set("Range", "bytes="+byteRange)
		}
		resp, err := pool.Do(ctx, req, reqOpts)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
			return nil, &model.DomainError{
				Code:      model.ErrAuthRequired,
				Status:    resp.StatusCode,
				Detail:    fmt.Sprintf("HLS 请求被拒绝 (%d)", resp.StatusCode),
				Retryable: true,
			}
		}
		if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone {
			return nil, &model.DomainError{
				Code:      model.ErrTransport,
				Status:    resp.StatusCode,
				Detail:    fmt.Sprintf("HLS 请求返回状态码 %d", resp.StatusCode),
				Retryable: false,
			}
		}
		if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
			return nil, &model.DomainError{
				Code:      model.ErrTransport,
				Status:    http.StatusBadGateway,
				Detail:    fmt.Sprintf("HLS 请求返回状态码 %d", resp.StatusCode),
				Retryable: resp.StatusCode >= 500 || resp.StatusCode == http.StatusTooManyRequests,
			}
		}
		return io.ReadAll(resp.Body)
	}
	fetchBytes := func(rawURL string, byteRange string) ([]byte, error) {
		return fetchWithOpts(rawURL, byteRange, opts)
	}
	fetchKeyBytes := func(rawURL string) ([]byte, error) {
		keyOpts := opts
		keyOpts.AllowHTTPSDowngrade = false
		return fetchWithOpts(rawURL, "", keyOpts)
	}

	playlistURL := resolved.URL
	rawM3U8, err := fetchBytes(playlistURL, "")
	if err != nil {
		return "", 0, err
	}
	text := string(rawM3U8)
	if strings.Contains(text, "#EXT-X-STREAM-INF") {
		selected := selectMasterVariant(playlistURL, text)
		if selected == "" {
			return "", 0, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "HLS 主列表未包含可用变体")
		}
		playlistURL = selected
		rawM3U8, err = fetchBytes(playlistURL, "")
		if err != nil {
			return "", 0, err
		}
		text = string(rawM3U8)
	}

	segs, seqStart, mapSpec, keySpec, err := parseMediaPlaylist(playlistURL, text)
	if err != nil {
		return "", 0, err
	}
	if len(segs) == 0 {
		return "", 0, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "HLS 列表未包含媒体分片")
	}

	rawConcat := filepath.Join(stageDir, dlID+".hls.raw")
	rawFile, err := os.OpenFile(rawConcat, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return "", 0, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "创建 HLS 合并文件失败", err)
	}
	var totalWritten int64
	cleanupRaw := func() {
		_ = rawFile.Close()
		_ = os.Remove(rawConcat)
	}

	if mapSpec != nil {
		mapData, mErr := fetchHLSResource(fetchBytes, mapSpec.uri, mapSpec.byteRange)
		if mErr != nil {
			cleanupRaw()
			return "", 0, mErr
		}
		if _, wErr := rawFile.Write(mapData); wErr != nil {
			cleanupRaw()
			return "", 0, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "写入 HLS 初始化段失败", wErr)
		}
		totalWritten += int64(len(mapData))
	}

	keyCache := map[string][]byte{}
	for i, seg := range segs {
		if err := ctx.Err(); err != nil {
			cleanupRaw()
			return "", 0, model.WrapError(model.ErrCancelled, 499, "HLS 下载已取消", err)
		}
		data, sErr := fetchHLSResource(fetchBytes, seg.uri, seg.byteRange)
		if sErr != nil {
			cleanupRaw()
			return "", 0, sErr
		}
		effectiveKey := seg.key
		if effectiveKey == nil {
			effectiveKey = keySpec
		}
		if effectiveKey != nil && strings.EqualFold(effectiveKey.method, "AES-128") {
			keyBytes, ok := keyCache[effectiveKey.uri]
			if !ok {
				keyBytes, sErr = fetchKeyBytes(effectiveKey.uri)
				if sErr != nil {
					cleanupRaw()
					return "", 0, sErr
				}
				if len(keyBytes) != 16 {
					cleanupRaw()
					return "", 0, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "HLS AES-128 密钥长度无效")
				}
				keyCache[effectiveKey.uri] = keyBytes
			}
			iv := effectiveKey.iv
			if len(iv) != 16 {
				iv = make([]byte, 16)
				binary.BigEndian.PutUint64(iv[8:], uint64(seqStart+i))
			}
			data, sErr = decryptAES128CBC(data, keyBytes, iv)
			if sErr != nil {
				cleanupRaw()
				return "", 0, sErr
			}
		}
		if _, wErr := rawFile.Write(data); wErr != nil {
			cleanupRaw()
			return "", 0, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "写入 HLS 分片失败", wErr)
		}
		totalWritten += int64(len(data))
		if maxBytes > 0 && totalWritten > maxBytes {
			cleanupRaw()
			return "", 0, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "HLS 媒体体积超出上限")
		}
		if onProgress != nil {
			onProgress(totalWritten, nil, fmt.Sprintf("正在下载 HLS 分片 (%d/%d)", i+1, len(segs)))
		}
	}

	if err := rawFile.Sync(); err != nil {
		cleanupRaw()
		return "", 0, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "刷新 HLS 临时文件失败", err)
	}
	_ = rawFile.Close()
	defer os.Remove(rawConcat)

	outExt := codecToExt(resolved.Codec, ".m4a")
	if outExt == ".ts" || outExt == ".m3u8" || outExt == "" {
		outExt = ".m4a"
	}
	outPath := filepath.Join(stageDir, dlID+".hls"+outExt)
	if runner != nil && runner.Available("ffmpeg") {
		formatName := strings.TrimPrefix(outExt, ".")
		if err := runner.Convert(ctx, rawConcat, outPath, media.ConvertOptions{OutputFormat: formatName}); err != nil {
			_ = os.Remove(outPath)
			return "", 0, err
		}
	} else {
		if err := os.Rename(rawConcat, outPath); err != nil {
			return "", 0, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "重命名 HLS 输出失败", err)
		}
	}
	st, err := os.Stat(outPath)
	if err != nil {
		return "", 0, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取 HLS 输出失败", err)
	}
	return outPath, st.Size(), nil
}

type hlsKeySpec struct {
	method string
	uri    string
	iv     []byte
}

type hlsMapSpec struct {
	uri       string
	byteRange string
}

type hlsSegment struct {
	uri       string
	byteRange string
	key       *hlsKeySpec
}

func selectMasterVariant(baseURL, text string) string {
	lines := strings.Split(text, "\n")
	bestBW := -1
	bestURI := ""
	pendingBW := 0
	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-STREAM-INF:") {
			pendingBW = 0
			for _, part := range strings.Split(strings.TrimPrefix(line, "#EXT-X-STREAM-INF:"), ",") {
				kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
				if len(kv) == 2 && strings.EqualFold(kv[0], "BANDWIDTH") {
					pendingBW, _ = strconv.Atoi(kv[1])
				}
			}
			continue
		}
		if strings.HasPrefix(line, "#") {
			continue
		}
		resolved, err := resolveRelativeURL(baseURL, line)
		if err == nil && pendingBW >= bestBW {
			bestBW = pendingBW
			bestURI = resolved
		}
	}
	return bestURI
}

func parseMediaPlaylist(baseURL, text string) ([]hlsSegment, int, *hlsMapSpec, *hlsKeySpec, error) {
	lines := strings.Split(text, "\n")
	var segs []hlsSegment
	seq := 0
	var currentKey *hlsKeySpec
	var mapSpec *hlsMapSpec
	var nextRange string
	var nextOffset int64
	expectSeg := false
	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		switch {
		case strings.HasPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"):
			seq, _ = strconv.Atoi(strings.TrimPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"))
		case strings.HasPrefix(line, "#EXT-X-MAP:"):
			attrs := parseM3U8Attrs(strings.TrimPrefix(line, "#EXT-X-MAP:"))
			if uri := attrs["URI"]; uri != "" {
				resolved, err := resolveRelativeURL(baseURL, uri)
				if err != nil {
					return nil, 0, nil, nil, err
				}
				var br string
				if rawBR := attrs["BYTERANGE"]; rawBR != "" {
					br, nextOffset = formatByteRange(rawBR, 0)
				}
				mapSpec = &hlsMapSpec{uri: resolved, byteRange: br}
			}
		case strings.HasPrefix(line, "#EXT-X-KEY:"):
			attrs := parseM3U8Attrs(strings.TrimPrefix(line, "#EXT-X-KEY:"))
			method := attrs["METHOD"]
			if strings.EqualFold(method, "NONE") {
				currentKey = nil
				continue
			}
			uri, err := resolveRelativeURL(baseURL, attrs["URI"])
			if err != nil {
				return nil, 0, nil, nil, err
			}
			var ivBytes []byte
			if rawIV := strings.TrimPrefix(strings.ToLower(attrs["IV"]), "0x"); rawIV != "" {
				ivBytes, _ = hex.DecodeString(rawIV)
			}
			k := &hlsKeySpec{method: method, uri: uri, iv: ivBytes}
			currentKey = k
		case strings.HasPrefix(line, "#EXT-X-BYTERANGE:"):
			rawBR := strings.TrimPrefix(line, "#EXT-X-BYTERANGE:")
			nextRange, nextOffset = formatByteRange(rawBR, nextOffset)
		case strings.HasPrefix(line, "#EXTINF:"):
			expectSeg = true
		case strings.HasPrefix(line, "#"):
			continue
		default:
			if !expectSeg {
				continue
			}
			uri, err := resolveRelativeURL(baseURL, line)
			if err != nil {
				return nil, 0, nil, nil, err
			}
			segs = append(segs, hlsSegment{uri: uri, byteRange: nextRange, key: currentKey})
			nextRange = ""
			expectSeg = false
		}
	}
	return segs, seq, mapSpec, currentKey, nil
}

func formatByteRange(spec string, prevOffset int64) (string, int64) {
	parts := strings.SplitN(strings.TrimSpace(spec), "@", 2)
	length, _ := strconv.ParseInt(parts[0], 10, 64)
	start := prevOffset
	if len(parts) == 2 {
		start, _ = strconv.ParseInt(parts[1], 10, 64)
	}
	if length <= 0 {
		return "", prevOffset
	}
	end := start + length - 1
	return fmt.Sprintf("%d-%d", start, end), start + length
}

func parseM3U8Attrs(raw string) map[string]string {
	out := make(map[string]string)
	i := 0
	for i < len(raw) {
		for i < len(raw) && (raw[i] == ',' || raw[i] == ' ') {
			i++
		}
		eq := strings.IndexByte(raw[i:], '=')
		if eq < 0 {
			break
		}
		key := strings.TrimSpace(raw[i : i+eq])
		i += eq + 1
		var val string
		if i < len(raw) && raw[i] == '"' {
			i++
			end := strings.IndexByte(raw[i:], '"')
			if end < 0 {
				val = raw[i:]
				i = len(raw)
			} else {
				val = raw[i : i+end]
				i += end + 1
			}
		} else {
			comma := strings.IndexByte(raw[i:], ',')
			if comma < 0 {
				val = strings.TrimSpace(raw[i:])
				i = len(raw)
			} else {
				val = strings.TrimSpace(raw[i : i+comma])
				i += comma + 1
			}
		}
		out[strings.ToUpper(key)] = val
	}
	return out
}

func fetchHLSResource(fetch func(string, string) ([]byte, error), uri, byteRange string) ([]byte, error) {
	data, err := fetch(uri, byteRange)
	if err != nil {
		return nil, err
	}
	if byteRange != "" && len(data) > 0 {
		parts := strings.SplitN(byteRange, "-", 2)
		if len(parts) == 2 {
			start, _ := strconv.ParseInt(parts[0], 10, 64)
			end, _ := strconv.ParseInt(parts[1], 10, 64)
			wantLen := end - start + 1
			if int64(len(data)) > wantLen && end < int64(len(data)) {
				return data[start : end+1], nil
			}
		}
	}
	return data, nil
}

func decryptAES128CBC(ciphertext, key, iv []byte) ([]byte, error) {
	if len(ciphertext) == 0 || len(ciphertext)%aes.BlockSize != 0 {
		return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "HLS 加密分片长度不是 AES 块对齐")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	plain := make([]byte, len(ciphertext))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(plain, ciphertext)
	pad := int(plain[len(plain)-1])
	if pad == 0 || pad > aes.BlockSize || pad > len(plain) {
		return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "HLS PKCS7 填充无效")
	}
	for i := len(plain) - pad; i < len(plain); i++ {
		if int(plain[i]) != pad {
			return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "HLS 解密填充校验失败")
		}
	}
	return plain[:len(plain)-pad], nil
}

func resolveRelativeURL(baseURL, ref string) (string, error) {
	bu, err := url.Parse(baseURL)
	if err != nil {
		return "", err
	}
	ru, err := url.Parse(strings.TrimSpace(ref))
	if err != nil {
		return "", err
	}
	return bu.ResolveReference(ru).String(), nil
}

func buildTransferTransportOpts(resolved model.ResolvedMedia, rc provider.RequestContext, maxBytes int64, timeout time.Duration) transport.RequestOptions {
	proxyURL, _, acct := provider.SourceRequestOptions(rc, rc.Account.Source)
	domain := transport.DomainPublicMedia
	if len(resolved.Cookies) > 0 || resolved.Headers["Cookie"] != "" || resolved.Headers["Authorization"] != "" {
		domain = transport.DomainAccountProvider
	}
	return transport.RequestOptions{
		Domain:              domain,
		ConfigRevision:      rc.Config.Revision,
		ProxyURL:            proxyURL,
		Account:             acct,
		Timeout:             timeout,
		MaxResponseBytes:    maxBytes,
		Idempotent:          true,
		HasSideEffects:      false,
		AllowHTTPSDowngrade: true,
	}
}

func looksLikeTextError(b []byte) bool {
	trimmed := bytes.ToLower(bytes.TrimSpace(b))
	return bytes.HasPrefix(trimmed, []byte("<!doctype")) ||
		bytes.HasPrefix(trimmed, []byte("<html")) ||
		bytes.HasPrefix(trimmed, []byte("<?xml")) ||
		bytes.HasPrefix(trimmed, []byte("{")) ||
		bytes.HasPrefix(trimmed, []byte("["))
}

func codecToExt(codec, fallback string) string {
	c := strings.ToLower(strings.TrimSpace(codec))
	switch c {
	case "flac", "sq":
		return ".flac"
	case "mp3", "mpeg":
		return ".mp3"
	case "m4a", "aac", "mp4a":
		return ".m4a"
	case "ogg", "vorbis":
		return ".ogg"
	case "opus":
		return ".opus"
	case "wav", "pcm":
		return ".wav"
	case "wma", "asf":
		return ".wma"
	default:
		if strings.HasPrefix(c, ".") {
			return c
		}
		return fallback
	}
}

func detectAudioExt(path string, mime, codecFallback string) string {
	if ext := codecToExt(codecFallback, ""); ext != "" {
		return ext
	}
	if f, err := os.Open(path); err == nil {
		defer f.Close()
		var hdr [16]byte
		n, _ := f.Read(hdr[:])
		b := hdr[:n]
		switch {
		case bytes.HasPrefix(b, []byte("fLaC")):
			return ".flac"
		case bytes.HasPrefix(b, []byte("RIFF")) && len(b) >= 12 && string(b[8:12]) == "WAVE":
			return ".wav"
		case bytes.HasPrefix(b, []byte("OggS")):
			return ".ogg"
		case bytes.HasPrefix(b, []byte("ID3")):
			return ".mp3"
		case len(b) >= 8 && string(b[4:8]) == "ftyp":
			return ".m4a"
		case len(b) >= 2 && b[0] == 0xFF && (b[1]&0xE0) == 0xE0:
			return ".mp3"
		}
	}
	switch strings.ToLower(mime) {
	case "audio/flac", "audio/x-flac":
		return ".flac"
	case "audio/mpeg", "audio/mp3":
		return ".mp3"
	case "audio/mp4", "audio/aac", "audio/x-m4a":
		return ".m4a"
	case "audio/ogg":
		return ".ogg"
	case "audio/opus":
		return ".opus"
	case "audio/wav", "audio/x-wav":
		return ".wav"
	}
	return ".mp3"
}

func fetchBoundedAsset(ctx context.Context, pool *transport.Pool, rawURL string, rc provider.RequestContext) ([]byte, string, error) {
	if strings.TrimSpace(rawURL) == "" {
		return nil, "", nil
	}
	req, err := http.NewRequest(http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("User-Agent", provider.DefaultUserAgent)
	proxyURL, _, _ := provider.SourceRequestOptions(rc, rc.Account.Source)
	resp, err := pool.Do(ctx, req, transport.RequestOptions{
		Domain:              transport.DomainPublicMedia,
		ConfigRevision:      rc.Config.Revision,
		ProxyURL:            proxyURL,
		Timeout:             15 * time.Second,
		MaxResponseBytes:    8 << 20,
		Idempotent:          true,
		AllowHTTPSDowngrade: true,
	})
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, "", fmt.Errorf("asset HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, "", err
	}
	mime := strings.ToLower(strings.TrimSpace(strings.SplitN(resp.Header.Get("Content-Type"), ";", 2)[0]))
	return data, mime, nil
}
