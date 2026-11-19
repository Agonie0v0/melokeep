package risks

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type ndjsonMsg struct {
	V         int             `json:"v"`
	Type      string          `json:"type"`
	ID        string          `json:"id,omitempty"`
	Seq       int             `json:"seq,omitempty"`
	Helper    string          `json:"helper,omitempty"`
	Operation string          `json:"operation,omitempty"`
	TimeoutMS int64           `json:"timeout_ms,omitempty"`
	Input     any             `json:"input,omitempty"`
	Data      json.RawMessage `json:"data,omitempty"`
	Error     *struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

func runHelperTask(ctx context.Context, scriptPath, taskID, op string, input any) (json.RawMessage, error) {
	cmd := exec.Command("py", "-3.13", scriptPath)
	cmd.Env = append(os.Environ(), "PYTHONUTF8=1", "PYTHONIOENCODING=utf-8")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	var stderrBuf bytes.Buffer
	cmd.Stderr = &limitedTailWriter{buf: &stderrBuf, limit: 256 * 1024}

	tree, err := StartInJob(cmd)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tree.Kill() }()

	doneCh := make(chan error, 1)
	var result json.RawMessage

	go func() {
		reader := bufio.NewReaderSize(stdout, 64*1024)
		readLine := func(maxLine int) ([]byte, error) {
			var line []byte
			for {
				frag, isPrefix, err := reader.ReadLine()
				if err != nil {
					return nil, err
				}
				line = append(line, frag...)
				if len(line) > maxLine {
					return nil, fmt.Errorf("line exceeded %d bytes", maxLine)
				}
				if !isPrefix {
					return line, nil
				}
			}
		}

		helloRaw, err := readLine(1 << 20)
		if err != nil {
			doneCh <- fmt.Errorf("hello read: %w", err)
			return
		}
		var hello ndjsonMsg
		if err := json.Unmarshal(helloRaw, &hello); err != nil || hello.V != 1 || hello.Type != "hello" {
			doneCh <- fmt.Errorf("invalid hello: %s (%v)", string(helloRaw), err)
			return
		}

		req := ndjsonMsg{V: 1, Type: "run", ID: taskID, Operation: op, TimeoutMS: 30000, Input: input}
		b, _ := json.Marshal(req)
		b = append(b, '\n')
		if _, err := stdin.Write(b); err != nil {
			doneCh <- err
			return
		}
		_ = stdin.Close()

		total := len(helloRaw)
		for {
			raw, err := readLine(1 << 20)
			if err != nil {
				doneCh <- fmt.Errorf("frame read: %w (stderr=%s)", err, stderrBuf.String())
				return
			}
			total += len(raw)
			if total > 16<<20 {
				doneCh <- fmt.Errorf("stdout exceeded 16 MiB")
				return
			}
			var msg ndjsonMsg
			if err := json.Unmarshal(raw, &msg); err != nil {
				doneCh <- fmt.Errorf("invalid json: %w", err)
				return
			}
			if msg.ID != taskID {
				doneCh <- fmt.Errorf("mismatched task id %q", msg.ID)
				return
			}
			if msg.Type == "result" {
				result = msg.Data
				doneCh <- nil
				return
			}
			if msg.Type == "error" {
				doneCh <- fmt.Errorf("helper error %s: %s", msg.Error.Code, msg.Error.Message)
				return
			}
		}
	}()

	helloTimer := time.NewTimer(30 * time.Second)
	defer helloTimer.Stop()
	select {
	case err := <-doneCh:
		if err != nil {
			return nil, err
		}
		waitErr := cmd.Wait()
		if waitErr != nil {
			return nil, fmt.Errorf("helper exit error: %w (stderr=%s)", waitErr, stderrBuf.String())
		}
		return result, nil
	case <-ctx.Done():
		_ = tree.Kill()
		_ = cmd.Wait()
		return nil, ctx.Err()
	case <-helloTimer.C:
		_ = tree.Kill()
		_ = cmd.Wait()
		return nil, fmt.Errorf("helper timeout")
	}
}

type limitedTailWriter struct {
	buf   *bytes.Buffer
	limit int
}

func (w *limitedTailWriter) Write(p []byte) (int, error) {
	if len(p) >= w.limit {
		w.buf.Reset()
		w.buf.Write(p[len(p)-w.limit:])
		return len(p), nil
	}
	if w.buf.Len()+len(p) > w.limit {
		keep := w.buf.Bytes()[w.buf.Len()+len(p)-w.limit:]
		tmp := append([]byte(nil), keep...)
		w.buf.Reset()
		w.buf.Write(tmp)
	}
	return w.buf.Write(p)
}

var _ io.Writer = (*limitedTailWriter)(nil)

func pcmSHA256(t *testing.T, ffmpeg, path string) string {
	t.Helper()
	cmd := exec.Command(ffmpeg, "-v", "error", "-xerror", "-i", path, "-map", "0:a:0", "-vn", "-ar", "44100", "-ac", "2", "-c:a", "pcm_s32le", "-f", "hash", "-hash", "sha256", "-")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("pcm hash %s: %v (%s)", path, err, string(out))
	}
	return strings.TrimSpace(string(out))
}

func TestMutagenHelper7FormatsAnd200BatchRead(t *testing.T) {
	ffmpeg := `E:\tool\ffmpeg\bin\ffmpeg.exe`
	scriptPath, err := filepath.Abs(filepath.Join("taghelper", "helper.py"))
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()

	// Create 64x64 PNG cover.
	coverPath := filepath.Join(dir, "cover.png")
	img := image.NewRGBA(image.Rect(0, 0, 64, 64))
	for y := 0; y < 64; y++ {
		for x := 0; x < 64; x++ {
			img.Set(x, y, color.RGBA{R: 40, G: 120, B: 220, A: 255})
		}
	}
	cf, _ := os.Create(coverPath)
	_ = png.Encode(cf, img)
	_ = cf.Close()

	formats := []struct {
		ext    string
		args   []string
		cover  bool
		lyrics bool
	}{
		{".mp3", []string{"-c:a", "libmp3lame", "-b:a", "192k"}, true, true},
		{".flac", []string{"-c:a", "flac"}, true, true},
		{".m4a", []string{"-c:a", "aac", "-b:a", "192k"}, true, true},
		{".wav", []string{"-c:a", "pcm_s16le"}, true, true},
		{".ogg", []string{"-c:a", "libvorbis"}, true, true},
		{".opus", []string{"-c:a", "libopus"}, true, true},
		{".wma", []string{"-c:a", "wmav2"}, true, true},
	}

	wantTags := map[string]string{
		"title": "晨风序曲", "artist": "独立乐手", "album": "晨风集", "album_artist": "独立乐手",
		"date": "2024-07-31", "track_number": "3/11", "disc_number": "1/1", "genre": "Pop",
		"composer": "独立乐手", "comment": "测试备注", "lyricist": "独立乐手", "language": "zho",
		"copyright": "2024 CC-BY", "publisher": "Open Sound", "grouping": "Instrumental", "bpm": "90",
		"isrc": "XXA002400103",
	}
	wantLyrics := "[00:01.00]清晨微风吹过原野\n"

	var taggedPaths []string
	for _, f := range formats {
		orig := filepath.Join(dir, "sample"+f.ext)
		args := append([]string{"-v", "error", "-f", "lavfi", "-i", "sine=frequency=523.25:duration=1"}, f.args...)
		args = append(args, orig)
		if out, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
			t.Fatalf("generate %s: %v (%s)", f.ext, err, string(out))
		}
		beforeHash := pcmSHA256(t, ffmpeg, orig)
		stage := filepath.Join(dir, "stage"+f.ext)
		b, _ := os.ReadFile(orig)
		_ = os.WriteFile(stage, b, 0o600)

		inp := map[string]any{
			"stage_path": stage,
			"tags":       wantTags,
			"lyrics":     wantLyrics,
			"cover":      map[string]string{"path": coverPath, "mime": "image/png"},
		}
		raw, err := runHelperTask(context.Background(), scriptPath, "write-"+f.ext, "write_tags", inp)
		if err != nil {
			t.Fatalf("write_tags %s: %v", f.ext, err)
		}
		afterHash := pcmSHA256(t, ffmpeg, stage)
		if afterHash != beforeHash {
			t.Fatalf("%s audio PCM changed! before=%s after=%s", f.ext, beforeHash, afterHash)
		}
		var res struct {
			Tags     map[string]string `json:"tags"`
			Lyrics   string            `json:"lyrics"`
			HasCover bool              `json:"has_cover"`
		}
		if err := json.Unmarshal(raw, &res); err != nil {
			t.Fatalf("decode write_tags result %s: %v", f.ext, err)
		}
		for k, v := range wantTags {
			if res.Tags[k] != v {
				t.Fatalf("%s tag %s mismatch: got %q want %q", f.ext, k, res.Tags[k], v)
			}
		}
		if res.Lyrics != wantLyrics || !res.HasCover {
			t.Fatalf("%s lyrics/cover mismatch: lyrics=%q has_cover=%v", f.ext, res.Lyrics, res.HasCover)
		}
		taggedPaths = append(taggedPaths, stage)
	}

	// 200-file batch_read_tags test (199 valid copies + 1 corrupt file to verify per-item isolation).
	var batch []string
	srcFLAC, _ := os.ReadFile(filepath.Join(dir, "stage.flac"))
	for i := 0; i < 199; i++ {
		p := filepath.Join(dir, fmt.Sprintf("b-%03d.flac", i))
		_ = os.WriteFile(p, srcFLAC, 0o600)
		batch = append(batch, p)
	}
	corruptPath := filepath.Join(dir, "corrupt.flac")
	_ = os.WriteFile(corruptPath, []byte("not-audio"), 0o600)
	batch = append(batch, corruptPath)

	start := time.Now()
	rawBatch, err := runHelperTask(context.Background(), scriptPath, "batch-200", "batch_read_tags", map[string]any{"paths": batch})
	if err != nil {
		t.Fatalf("batch_read_tags failed: %v", err)
	}
	elapsed := time.Since(start)
	var batchRes struct {
		Items []struct {
			Path  string `json:"path"`
			OK    bool   `json:"ok"`
			Error string `json:"error"`
		} `json:"items"`
	}
	if err := json.Unmarshal(rawBatch, &batchRes); err != nil {
		t.Fatalf("decode batch: %v", err)
	}
	if len(batchRes.Items) != 200 {
		t.Fatalf("expected 200 batch items, got %d", len(batchRes.Items))
	}
	okCount, errCount := 0, 0
	for _, it := range batchRes.Items {
		if it.OK {
			okCount++
		} else {
			errCount++
		}
	}
	if okCount != 199 || errCount != 1 {
		t.Fatalf("expected 199 ok + 1 error, got ok=%d err=%d", okCount, errCount)
	}
	t.Logf("7 formats write+PCM verified; batch_read_tags(200 files) completed in %v", elapsed)
}
