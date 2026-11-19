package media

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/model"
)

const (
	maxToolStdoutBytes = 4 << 20
	maxToolStderrBytes = 256 << 10
)

var FormatArgs = map[string][]string{
	"flac": {"-c:a", "flac"},
	"mp3":  {"-c:a", "libmp3lame"},
	"m4a":  {"-c:a", "aac"},
	"opus": {"-c:a", "libopus"},
	"wav":  {"-c:a", "pcm_s16le"},
}

type ConvertOptions struct {
	OutputFormat string
	Bitrate      int
	SampleRate   int
	Start        *float64
	End          *float64
}

type CUETrack struct {
	TrackNumber string
	Title       string
	Artist      string
	Album       string
	AlbumArtist string
	File        string
	Start       float64
	End         float64
}

type FingerprintResult struct {
	Duration    float64 `json:"duration"`
	Fingerprint string  `json:"fingerprint"`
}

type AudioInspection struct {
	Duration      float64 `json:"duration"`
	Bitrate       *int    `json:"bitrate,omitempty"`
	SampleRate    *int    `json:"sample_rate,omitempty"`
	Channels      *int    `json:"channels,omitempty"`
	BitsPerSample *int    `json:"bits_per_sample,omitempty"`
}

type Runner struct {
	sem         chan struct{}
	ffmpegPath  string
	ffprobePath string
	fpcalcPath  string
}

func NewRunner(budgets config.ResourceBudgets, ffmpegOverride, ffprobeOverride, fpcalcOverride string) *Runner {
	conc := budgets.AudioToolsConcurrency
	if conc <= 0 {
		conc = 1
	}
	return &Runner{
		sem:         make(chan struct{}, conc),
		ffmpegPath:  ffmpegOverride,
		ffprobePath: ffprobeOverride,
		fpcalcPath:  fpcalcOverride,
	}
}

func (r *Runner) Executable(name string) (string, error) {
	var override string
	switch name {
	case "ffmpeg":
		override = r.ffmpegPath
		if override == "" {
			override = os.Getenv("MELOKEEP_FFMPEG")
		}
	case "ffprobe":
		override = r.ffprobePath
		if override == "" {
			override = os.Getenv("MELOKEEP_FFPROBE")
		}
		if override == "" && r.ffmpegPath != "" {
			cand := filepath.Join(filepath.Dir(r.ffmpegPath), "ffprobe"+filepath.Ext(r.ffmpegPath))
			if _, err := os.Stat(cand); err == nil {
				override = cand
			}
		}
	case "fpcalc":
		override = r.fpcalcPath
		if override == "" {
			override = os.Getenv("MELOKEEP_FPCALC")
		}
	}
	if override != "" {
		resolved, err := exec.LookPath(override)
		if err != nil {
			return "", model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable,
				fmt.Sprintf("配置的 %s 路径不可用，请检查配置", name), err)
		}
		return resolved, nil
	}
	resolved, err := exec.LookPath(name)
	if err != nil {
		return "", model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable,
			fmt.Sprintf("未找到 %s，请安装后配置 MELOKEEP_%s", name, strings.ToUpper(name)), err)
	}
	return resolved, nil
}

func (r *Runner) Available(name string) bool {
	_, err := r.Executable(name)
	return err == nil
}

func (r *Runner) run(ctx context.Context, exe string, args []string, timeout time.Duration) ([]byte, error) {
	if timeout <= 0 {
		timeout = 30 * time.Minute
	}
	select {
	case r.sem <- struct{}{}:
		defer func() { <-r.sem }()
	case <-ctx.Done():
		return nil, model.WrapError(model.ErrCancelled, 499, "音频处理已取消", ctx.Err())
	}

	runCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	cmd := exec.Command(exe, args...)
	cmd.Env = cleanToolEnv()

	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderrPipe, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}

	tree, err := helper.StartInTree(cmd)
	if err != nil {
		return nil, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "启动音频处理工具失败", err)
	}

	var stdoutBuf limitedBuffer
	stdoutBuf.limit = maxToolStdoutBytes
	var stderrBuf tailBuffer
	stderrBuf.limit = maxToolStderrBytes

	errCh := make(chan error, 2)
	go func() {
		_, copyErr := io.Copy(&stdoutBuf, stdoutPipe)
		errCh <- copyErr
	}()
	go func() {
		_, copyErr := io.Copy(&stderrBuf, stderrPipe)
		errCh <- copyErr
	}()

	waitDone := make(chan error, 1)
	go func() {
		<-errCh
		<-errCh
		waitDone <- cmd.Wait()
	}()

	select {
	case wErr := <-waitDone:
		_ = tree.Kill()
		if wErr != nil {
			detail := strings.TrimSpace(stderrBuf.String())
			if len(detail) > 500 {
				detail = detail[:500]
			}
			return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "音频处理失败："+detail, wErr)
		}
		return stdoutBuf.Bytes(), nil
	case <-runCtx.Done():
		_ = tree.Kill()
		<-waitDone
		if ctx.Err() != nil {
			return nil, model.WrapError(model.ErrCancelled, 499, "处理已取消，原文件已保留", ctx.Err())
		}
		return nil, model.NewError(model.ErrDeadline, http.StatusGatewayTimeout, "音频处理超时，原文件已保留")
	}
}

func (r *Runner) AudioHash(ctx context.Context, path string) (string, error) {
	exe, err := r.Executable("ffmpeg")
	if err != nil {
		return "", err
	}
	out, err := r.run(ctx, exe, []string{
		"-v", "error", "-xerror", "-nostdin", "-i", path,
		"-map", "0:a:0", "-vn", "-ar", "44100", "-ac", "2",
		"-c:a", "pcm_s32le", "-f", "hash", "-hash", "sha256", "-",
	}, 5*time.Minute)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

func (r *Runner) InspectAudio(ctx context.Context, path string) (AudioInspection, error) {
	ffmpegExe, err := r.Executable("ffmpeg")
	if err != nil {
		return AudioInspection{}, err
	}
	if _, err := r.run(ctx, ffmpegExe, []string{
		"-v", "error", "-xerror", "-nostdin", "-i", path, "-map", "0:a:0", "-f", "null", "-",
	}, 5*time.Minute); err != nil {
		return AudioInspection{}, err
	}
	var ins AudioInspection
	if ffprobeExe, err := r.Executable("ffprobe"); err == nil {
		out, pErr := r.run(ctx, ffprobeExe, []string{
			"-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", "-select_streams", "a:0", path,
		}, 30*time.Second)
		if pErr == nil {
			var parsed struct {
				Format struct {
					Duration string `json:"duration"`
					BitRate  string `json:"bit_rate"`
				} `json:"format"`
				Streams []struct {
					SampleRate    string `json:"sample_rate"`
					Channels      int    `json:"channels"`
					BitsPerSample int    `json:"bits_per_sample"`
					BitRate       string `json:"bit_rate"`
				} `json:"streams"`
			}
			if json.Unmarshal(out, &parsed) == nil {
				if d, err := strconv.ParseFloat(parsed.Format.Duration, 64); err == nil {
					ins.Duration = d
				}
				if b, err := strconv.Atoi(parsed.Format.BitRate); err == nil && b > 0 {
					ins.Bitrate = &b
				}
				if len(parsed.Streams) > 0 {
					st := parsed.Streams[0]
					if sr, err := strconv.Atoi(st.SampleRate); err == nil && sr > 0 {
						ins.SampleRate = &sr
					}
					if st.Channels > 0 {
						ch := st.Channels
						ins.Channels = &ch
					}
					if st.BitsPerSample > 0 {
						bps := st.BitsPerSample
						ins.BitsPerSample = &bps
					}
				}
			}
		}
	}
	return ins, nil
}

// Convert transcodes source strictly into a caller-supplied stageTarget path.
func (r *Runner) Convert(ctx context.Context, source, stageTarget string, opts ConvertOptions) error {
	exe, err := r.Executable("ffmpeg")
	if err != nil {
		return err
	}
	fmtName := opts.OutputFormat
	if fmtName == "" {
		fmtName = "flac"
	}
	fmtArgs, ok := FormatArgs[fmtName]
	if !ok {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的输出音频格式")
	}
	args := []string{"-v", "error", "-xerror", "-nostdin", "-n", "-i", source}
	if opts.Start != nil {
		args = append(args, "-ss", strconv.FormatFloat(*opts.Start, 'f', -1, 64))
	}
	if opts.End != nil {
		startVal := 0.0
		if opts.Start != nil {
			startVal = *opts.Start
		}
		args = append(args, "-t", strconv.FormatFloat(*opts.End-startVal, 'f', -1, 64))
	}
	args = append(args, "-map", "0:a:0", "-vn", "-map_metadata", "-1")
	args = append(args, fmtArgs...)
	if fmtName == "mp3" || fmtName == "m4a" || fmtName == "opus" {
		br := opts.Bitrate
		if br <= 0 {
			if fmtName == "opus" {
				br = 128
			} else {
				br = 320
			}
		}
		args = append(args, "-b:a", fmt.Sprintf("%dk", br))
	}
	if opts.SampleRate > 0 {
		args = append(args, "-ar", strconv.Itoa(opts.SampleRate))
	}
	args = append(args, stageTarget)
	_, err = r.run(ctx, exe, args, 30*time.Minute)
	return err
}

func (r *Runner) Fingerprint(ctx context.Context, path string) (FingerprintResult, error) {
	exe, err := r.Executable("fpcalc")
	if err != nil {
		return FingerprintResult{}, err
	}
	out, err := r.run(ctx, exe, []string{"-json", "-length", "120", path}, 120*time.Second)
	if err != nil {
		return FingerprintResult{}, err
	}
	var res FingerprintResult
	if err := json.Unmarshal(out, &res); err != nil || res.Fingerprint == "" || res.Duration <= 0 {
		return FingerprintResult{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "未能计算音频指纹")
	}
	return res, nil
}

var (
	cueFileRe  = regexp.MustCompile(`(?i)^FILE\s+"([^"]+)"\s+\S+$`)
	cueTrackRe = regexp.MustCompile(`(?i)^TRACK\s+(\d+)\s+AUDIO$`)
	cueMetaRe  = regexp.MustCompile(`(?i)^(TITLE|PERFORMER)\s+"?(.*?)"?$`)
	cueIndexRe = regexp.MustCompile(`(?i)^INDEX\s+01\s+(\d+):(\d{2}):(\d{2})$`)
)

func ParseCUE(audioPath, cueText string, duration float64) ([]CUETrack, error) {
	lines := strings.Split(strings.ReplaceAll(cueText, "\r\n", "\n"), "\n")
	var tracks []CUETrack
	curIdx := -1
	curFile := ""
	album, performer := "", ""

	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		if m := cueFileRe.FindStringSubmatch(line); m != nil {
			curFile = m[1]
			curIdx = -1
			continue
		}
		if m := cueTrackRe.FindStringSubmatch(line); m != nil {
			num, _ := strconv.Atoi(m[1])
			tracks = append(tracks, CUETrack{
				TrackNumber: strconv.Itoa(num),
				Artist:      performer,
				File:        curFile,
				Start:       -1,
			})
			curIdx = len(tracks) - 1
			continue
		}
		if m := cueMetaRe.FindStringSubmatch(line); m != nil {
			val := strings.TrimSuffix(m[2], `"`)
			if curIdx < 0 {
				if strings.EqualFold(m[1], "TITLE") {
					album = val
				} else {
					performer = val
				}
			} else {
				if strings.EqualFold(m[1], "TITLE") {
					tracks[curIdx].Title = val
				} else {
					tracks[curIdx].Artist = val
				}
			}
			continue
		}
		if m := cueIndexRe.FindStringSubmatch(line); m != nil && curIdx >= 0 {
			mins, _ := strconv.Atoi(m[1])
			secs, _ := strconv.Atoi(m[2])
			frames, _ := strconv.Atoi(m[3])
			if secs >= 60 || frames >= 75 {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "CUE 时间格式无效")
			}
			tracks[curIdx].Start = float64(mins*60+secs) + float64(frames)/75.0
		}
	}

	targetName := filepath.Base(audioPath)
	var selected []CUETrack
	for _, tr := range tracks {
		if tr.File == "" {
			continue
		}
		fBase := filepath.Base(strings.ReplaceAll(tr.File, `\`, `/`))
		if strings.EqualFold(fBase, targetName) {
			selected = append(selected, tr)
		}
	}
	if len(selected) > 1000 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "每次最多分割 1000 条音轨")
	}
	if len(selected) == 0 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "CUE 没有匹配当前音频的 FILE / INDEX 01")
	}
	for i := range selected {
		if selected[i].Start < 0 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "CUE 没有匹配当前音频的 FILE / INDEX 01")
		}
		end := duration
		if i+1 < len(selected) {
			end = selected[i+1].Start
		}
		if duration <= 0 || selected[i].Start < 0 || selected[i].Start >= end || end > duration+0.1 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "CUE 分轨时间超出音频或不是递增顺序")
		}
		selected[i].End = end
		selected[i].Album = album
		selected[i].AlbumArtist = performer
		if selected[i].Title == "" {
			selected[i].Title = "Track " + selected[i].TrackNumber
		}
	}
	return selected, nil
}

func cleanToolEnv() []string {
	allow := []string{"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR", "HOME", "LANG", "LC_ALL"}
	var out []string
	for _, k := range allow {
		if v := os.Getenv(k); v != "" {
			out = append(out, k+"="+v)
		}
	}
	return out
}

type limitedBuffer struct {
	buf   bytes.Buffer
	limit int
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	if b.buf.Len()+len(p) > b.limit {
		return 0, fmt.Errorf("tool stdout exceeded %d bytes", b.limit)
	}
	return b.buf.Write(p)
}

func (b *limitedBuffer) Bytes() []byte { return b.buf.Bytes() }

type tailBuffer struct {
	buf   []byte
	limit int
}

func (b *tailBuffer) Write(p []byte) (int, error) {
	b.buf = append(b.buf, p...)
	if len(b.buf) > b.limit {
		b.buf = append([]byte(nil), b.buf[len(b.buf)-b.limit:]...)
	}
	return len(p), nil
}

func (b *tailBuffer) String() string { return string(b.buf) }
