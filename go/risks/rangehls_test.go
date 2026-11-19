package risks

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

type checkpoint struct {
	URL          string `json:"url"`
	Validator    string `json:"validator"`
	Total        int64  `json:"total"`
	DurableBytes int64  `json:"durable_bytes"`
	Complete     bool   `json:"complete"`
}

func downloadResumable(client *http.Client, urlStr, targetPath string) error {
	partPath := targetPath + ".part"
	cpPath := targetPath + ".part.json"
	var cp checkpoint
	if raw, err := os.ReadFile(cpPath); err == nil {
		_ = json.Unmarshal(raw, &cp)
	}
	var offset int64
	if cp.URL == urlStr && cp.Validator != "" {
		if st, err := os.Stat(partPath); err == nil {
			offset = min(st.Size(), cp.DurableBytes)
		}
	}

	req, _ := http.NewRequest(http.MethodGet, urlStr, nil)
	req.Header.Set("Accept-Encoding", "identity")
	if offset > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", offset))
		req.Header.Set("If-Range", cp.Validator)
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusRequestedRangeNotSatisfiable && offset > 0 {
		cr := resp.Header.Get("Content-Range")
		etag := resp.Header.Get("ETag")
		if cr == fmt.Sprintf("bytes */%d", offset) && cp.Total == offset && etag == cp.Validator {
			cp.Complete = true
			writeCheckpoint(cpPath, cp)
			return os.Rename(partPath, targetPath)
		}
		_ = resp.Body.Close()
		offset = 0
		req2, _ := http.NewRequest(http.MethodGet, urlStr, nil)
		resp, err = client.Do(req2)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		return fmt.Errorf("unexpected HTTP status %d", resp.StatusCode)
	}
	if resp.StatusCode == http.StatusOK {
		offset = 0
	}
	flag := os.O_CREATE | os.O_WRONLY
	if offset == 0 {
		flag |= os.O_TRUNC
	} else {
		flag |= os.O_APPEND
	}
	f, err := os.OpenFile(partPath, flag, 0o600)
	if err != nil {
		return err
	}
	written, copyErr := io.Copy(f, resp.Body)
	syncErr := f.Sync()
	closeErr := f.Close()
	if copyErr != nil || syncErr != nil || closeErr != nil {
		cp.URL = urlStr
		cp.Validator = resp.Header.Get("ETag")
		cp.DurableBytes = offset + written
		_ = writeCheckpoint(cpPath, cp)
		return fmt.Errorf("transfer interrupted: copy=%v sync=%v close=%v", copyErr, syncErr, closeErr)
	}

	total := offset + written
	cp = checkpoint{URL: urlStr, Validator: resp.Header.Get("ETag"), Total: total, DurableBytes: total, Complete: true}
	if err := writeCheckpoint(cpPath, cp); err != nil {
		return err
	}
	return os.Rename(partPath, targetPath)
}

func writeCheckpoint(path string, cp checkpoint) error {
	b, err := json.Marshal(cp)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func TestRangeResumable200_206_416_IfRange(t *testing.T) {
	payload := bytes.Repeat([]byte("MELOKEEP-AUDIO-RANGE-TEST-"), 4096)
	wantHash := sha256.Sum256(payload)
	etag := `"v1-etag"`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("ETag", etag)
		rng := r.Header.Get("Range")
		if rng == "" || (r.Header.Get("If-Range") != "" && r.Header.Get("If-Range") != etag) {
			w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(payload)
			return
		}
		var start int
		_, _ = fmt.Sscanf(rng, "bytes=%d-", &start)
		if start >= len(payload) {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", len(payload)))
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, len(payload)-1, len(payload)))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(payload[start:])
	}))
	defer srv.Close()

	dir := t.TempDir()
	// Test 206 resume from valid partial checkpoint.
	target := filepath.Join(dir, "song.bin")
	half := len(payload) / 2
	if err := os.WriteFile(target+".part", payload[:half], 0o600); err != nil {
		t.Fatal(err)
	}
	if err := writeCheckpoint(target+".part.json", checkpoint{URL: srv.URL + "/a", Validator: etag, DurableBytes: int64(half)}); err != nil {
		t.Fatal(err)
	}
	if err := downloadResumable(srv.Client(), srv.URL+"/a", target); err != nil {
		t.Fatalf("206 resume failed: %v", err)
	}
	got, _ := os.ReadFile(target)
	if sha256.Sum256(got) != wantHash {
		t.Fatal("206 resume hash mismatch")
	}

	// Test 416 exact completion recovery.
	target416 := filepath.Join(dir, "song416.bin")
	_ = os.WriteFile(target416+".part", payload, 0o600)
	_ = writeCheckpoint(target416+".part.json", checkpoint{URL: srv.URL + "/a", Validator: etag, Total: int64(len(payload)), DurableBytes: int64(len(payload))})
	if err := downloadResumable(srv.Client(), srv.URL+"/a", target416); err != nil {
		t.Fatalf("416 exact completion failed: %v", err)
	}
	got416, _ := os.ReadFile(target416)
	if sha256.Sum256(got416) != wantHash {
		t.Fatal("416 hash mismatch")
	}

	// Test If-Range mismatch triggers clean 200 reset (not corrupt append!).
	targetIf := filepath.Join(dir, "song-ifrange.bin")
	_ = os.WriteFile(targetIf+".part", bytes.Repeat([]byte("OLD"), 100), 0o600)
	_ = writeCheckpoint(targetIf+".part.json", checkpoint{URL: srv.URL + "/a", Validator: `"old-etag"`, DurableBytes: 300})
	if err := downloadResumable(srv.Client(), srv.URL+"/a", targetIf); err != nil {
		t.Fatalf("If-Range mismatch fallback failed: %v", err)
	}
	gotIf, _ := os.ReadFile(targetIf)
	if sha256.Sum256(gotIf) != wantHash {
		t.Fatal("If-Range fallback hash mismatch")
	}
}

func pkcs7Pad(b []byte, bs int) []byte {
	pad := bs - len(b)%bs
	return append(b, bytes.Repeat([]byte{byte(pad)}, pad)...)
}

func pkcs7Unpad(b []byte) ([]byte, error) {
	if len(b) == 0 || len(b)%aes.BlockSize != 0 {
		return nil, fmt.Errorf("invalid padded length %d", len(b))
	}
	pad := int(b[len(b)-1])
	if pad == 0 || pad > aes.BlockSize || pad > len(b) {
		return nil, fmt.Errorf("invalid pkcs7 padding %d", pad)
	}
	for _, v := range b[len(b)-pad:] {
		if int(v) != pad {
			return nil, fmt.Errorf("corrupt pkcs7 padding")
		}
	}
	return b[:len(b)-pad], nil
}

func TestHLSAES128MapAndByteRangeWithFFmpeg(t *testing.T) {
	ffmpeg := `E:\tool\ffmpeg\bin\ffmpeg.exe`
	dir := t.TempDir()

	// Generate real AAC fMP4 via FFmpeg, then split moov init vs moof/mdat segment to test #EXT-X-MAP + BYTERANGE + AES-128.
	srcMP4 := filepath.Join(dir, "src.mp4")
	cmd := exec.Command(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:a", "aac", "-movflags", "frag_keyframe+empty_moov+default_base_moof", srcMP4)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate fmp4: %v (%s)", err, string(out))
	}
	rawMP4, err := os.ReadFile(srcMP4)
	if err != nil {
		t.Fatal(err)
	}
	moofIdx := bytes.Index(rawMP4, []byte("moof")) - 4
	if moofIdx <= 0 {
		t.Fatalf("failed locating moof atom in %d bytes", len(rawMP4))
	}
	initData := rawMP4[:moofIdx]
	mediaPlain := rawMP4[moofIdx:]

	key := []byte("0123456789abcdef")
	iv := []byte("fedcba9876543210")
	block, _ := aes.NewCipher(key)
	padded := pkcs7Pad(mediaPlain, aes.BlockSize)
	encMedia := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(encMedia, padded)

	// Pack init + junk + encrypted media in one combined blob to test both MAP BYTERANGE and segment BYTERANGE.
	blob := append([]byte{}, initData...)
	blob = append(blob, []byte("JUNK")...)
	mediaOffset := len(blob)
	blob = append(blob, encMedia...)

	m3u8 := fmt.Sprintf("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"/blob.mp4\",BYTERANGE=\"%d@0\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"/key.bin\",IV=0x%s\n#EXTINF:2.0,\n#EXT-X-BYTERANGE:%d@%d\n/blob.mp4\n#EXT-X-ENDLIST\n",
		len(initData), hex.EncodeToString(iv), len(encMedia), mediaOffset)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/stream.m3u8":
			_, _ = io.WriteString(w, m3u8)
		case "/key.bin":
			_, _ = w.Write(key)
		case "/blob.mp4":
			rng := r.Header.Get("Range")
			var start, end int
			if _, err := fmt.Sscanf(rng, "bytes=%d-%d", &start, &end); err != nil {
				http.Error(w, "missing range", http.StatusBadRequest)
				return
			}
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(blob[start : end+1])
		}
	}))
	defer srv.Close()

	// Download & decrypt HLS stream.
	resp, err := srv.Client().Get(srv.URL + "/stream.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	playlistBytes, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()

	fetchRange := func(path string, off, length int) ([]byte, error) {
		req, _ := http.NewRequest(http.MethodGet, srv.URL+path, nil)
		if length > 0 {
			req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", off, off+length-1))
		}
		r, err := srv.Client().Do(req)
		if err != nil {
			return nil, err
		}
		defer r.Body.Close()
		return io.ReadAll(r.Body)
	}

	var curKey, curIV []byte
	var pendingOffset, pendingLen int
	var seq uint64
	rawStream := filepath.Join(dir, "raw.mp4")
	outFile, err := os.Create(rawStream)
	if err != nil {
		t.Fatal(err)
	}
	reMap := regexp.MustCompile(`URI="([^"]+)",BYTERANGE="(\d+)@(\d+)"`)
	reKey := regexp.MustCompile(`METHOD=([^,]+),URI="([^"]+)"(?:,IV=0x([0-9A-Fa-f]+))?`)
	for _, line := range strings.Split(strings.ReplaceAll(string(playlistBytes), "\r\n", "\n"), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-MAP:") {
			m := reMap.FindStringSubmatch(line)
			ln, _ := strconv.Atoi(m[2])
			off, _ := strconv.Atoi(m[3])
			data, err := fetchRange(m[1], off, ln)
			if err != nil {
				t.Fatal(err)
			}
			_, _ = outFile.Write(data)
		} else if strings.HasPrefix(line, "#EXT-X-KEY:") {
			m := reKey.FindStringSubmatch(line)
			kResp, err := srv.Client().Get(srv.URL + m[2])
			if err != nil {
				t.Fatal(err)
			}
			curKey, _ = io.ReadAll(kResp.Body)
			_ = kResp.Body.Close()
			if m[3] != "" {
				curIV, _ = hex.DecodeString(m[3])
			}
		} else if strings.HasPrefix(line, "#EXT-X-BYTERANGE:") {
			spec := strings.TrimPrefix(line, "#EXT-X-BYTERANGE:")
			parts := strings.Split(spec, "@")
			pendingLen, _ = strconv.Atoi(parts[0])
			pendingOffset, _ = strconv.Atoi(parts[1])
		} else if !strings.HasPrefix(line, "#") {
			seg, err := fetchRange(line, pendingOffset, pendingLen)
			if err != nil {
				t.Fatal(err)
			}
			if curKey != nil {
				segIV := curIV
				if segIV == nil {
					segIV = make([]byte, 16)
					binary.BigEndian.PutUint64(segIV[8:], seq)
				}
				blk, _ := aes.NewCipher(curKey)
				dec := make([]byte, len(seg))
				cipher.NewCBCDecrypter(blk, segIV).CryptBlocks(dec, seg)
				seg, err = pkcs7Unpad(dec)
				if err != nil {
					t.Fatalf("unpad: %v", err)
				}
			}
			_, _ = outFile.Write(seg)
			seq++
		}
	}
	_ = outFile.Close()

	finalM4A := filepath.Join(dir, "final.m4a")
	remux := exec.Command(ffmpeg, "-v", "error", "-xerror", "-i", rawStream, "-c", "copy", finalM4A)
	if out, err := remux.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg remux: %v (%s)", err, string(out))
	}
	verify := exec.Command(ffmpeg, "-v", "error", "-xerror", "-i", finalM4A, "-map", "0:a:0", "-f", "null", "-")
	if out, err := verify.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg verify decoded stream: %v (%s)", err, string(out))
	}
}
