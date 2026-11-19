package helper

import (
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

func findTestPython(t *testing.T) string {
	t.Helper()
	if explicit := strings.TrimSpace(os.Getenv("MELOKEEP_PYTHON")); explicit != "" {
		if exec.Command(explicit, "-c", "import sys").Run() == nil {
			return explicit
		}
	}
	var rawCandidates []string
	if runtime.GOOS == "windows" {
		rawCandidates = []string{
			filepath.Join("..", "..", "..", ".venv", "Scripts", "python.exe"),
			"python",
			"python3",
		}
	} else {
		rawCandidates = []string{
			filepath.Join("..", "..", "..", ".venv", "bin", "python"),
			filepath.Join("..", "..", "..", ".venv", "bin", "python3"),
			"python3",
			"python",
		}
	}
	var resolved []string
	seen := make(map[string]struct{})
	addCandidate := func(p string) {
		p = strings.TrimSpace(p)
		if p == "" {
			return
		}
		key := p
		if runtime.GOOS == "windows" {
			key = strings.ToLower(p)
		}
		if _, ok := seen[key]; ok {
			return
		}
		seen[key] = struct{}{}
		resolved = append(resolved, p)
	}
	for _, c := range rawCandidates {
		if strings.ContainsAny(c, `/\`) {
			abs, err := filepath.Abs(c)
			if err != nil {
				continue
			}
			if _, err := os.Stat(abs); err != nil {
				continue
			}
			addCandidate(abs)
		} else if looked, err := exec.LookPath(c); err == nil {
			addCandidate(looked)
		}
	}
	if runtime.GOOS == "windows" {
		if pyLauncher, err := exec.LookPath("py"); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			out, oErr := exec.CommandContext(ctx, pyLauncher, "-0p").Output()
			cancel()
			if oErr == nil {
				for _, rawLine := range strings.Split(string(out), "\n") {
					line := strings.TrimSpace(rawLine)
					idx := strings.Index(line, `:\`)
					if idx < 1 {
						continue
					}
					drive := line[idx-1]
					if !((drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z')) {
						continue
					}
					exePath := strings.TrimSpace(line[idx-1:])
					if _, sErr := os.Stat(exePath); sErr == nil {
						addCandidate(exePath)
					}
				}
			}
		}
	}
	probe := func(exe, snippet string) bool {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		return exec.CommandContext(ctx, exe, "-c", snippet).Run() == nil
	}
	for _, path := range resolved {
		if probe(path, "import mutagen, requests") {
			return path
		}
	}
	for _, path := range resolved {
		if probe(path, "import mutagen") {
			return path
		}
	}
	for _, path := range resolved {
		if probe(path, "import sys") {
			return path
		}
	}
	t.Skip("no python interpreter found for helper tests")
	return ""
}

func writeTempScript(t *testing.T, body string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "script.py")
	if err := os.WriteFile(p, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestSupervisorHelloTimeoutAndProtocolErrors(t *testing.T) {
	py := findTestPython(t)

	// 1. Hello timeout.
	hangHello := writeTempScript(t, "import time; time.sleep(5)\n")
	sup1 := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: hangHello,
		WorkRoot:     t.TempDir(),
		HelloTimeout: 150 * time.Millisecond,
	})
	defer sup1.Close()
	_, err := sup1.Run(context.Background(), HelperTagger, "read_tags", 2*time.Second, nil, nil, nil)
	if de, ok := model.AsDomainError(err); !ok || de.Code != model.ErrDeadline {
		t.Fatalf("expected ErrDeadline on hello timeout, got %v", err)
	}

	// 2. Non-monotonic seq rejected.
	badSeq := writeTempScript(t, `import json, sys
print(json.dumps({"v":1,"type":"hello","helper":"tagger"}), flush=True)
req = json.loads(sys.stdin.readline())
print(json.dumps({"v":1,"type":"progress","id":req["id"],"seq":2,"data":{}}), flush=True)
print(json.dumps({"v":1,"type":"result","id":req["id"],"seq":1,"data":{}}), flush=True)
`)
	sup2 := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: badSeq,
		WorkRoot:     t.TempDir(),
	})
	defer sup2.Close()
	if _, err := sup2.Run(context.Background(), HelperTagger, "read_tags", 5*time.Second, nil, nil, nil); err == nil {
		t.Fatal("expected non-monotonic seq error")
	}

	// 3. Multiple terminal frames rejected.
	multiTerm := writeTempScript(t, `import json, sys
print(json.dumps({"v":1,"type":"hello","helper":"tagger"}), flush=True)
req = json.loads(sys.stdin.readline())
print(json.dumps({"v":1,"type":"result","id":req["id"],"seq":1,"data":{"ok":True}}), flush=True)
print(json.dumps({"v":1,"type":"result","id":req["id"],"seq":2,"data":{"ok":True}}), flush=True)
`)
	sup3 := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: multiTerm,
		WorkRoot:     t.TempDir(),
	})
	defer sup3.Close()
	if _, err := sup3.Run(context.Background(), HelperTagger, "read_tags", 5*time.Second, nil, nil, nil); err == nil {
		t.Fatal("expected extra terminal frame rejection")
	}

	// 4. Late exit (>ExitGrace) after terminal result rejected & killed.
	lateExit := writeTempScript(t, `import json, sys, time
print(json.dumps({"v":1,"type":"hello","helper":"tagger"}), flush=True)
req = json.loads(sys.stdin.readline())
print(json.dumps({"v":1,"type":"result","id":req["id"],"seq":1,"data":{"ok":True}}), flush=True)
time.sleep(5)
`)
	sup4 := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: lateExit,
		WorkRoot:     t.TempDir(),
		ExitGrace:    150 * time.Millisecond,
	})
	defer sup4.Close()
	if _, err := sup4.Run(context.Background(), HelperTagger, "read_tags", 5*time.Second, nil, nil, nil); err == nil {
		t.Fatal("expected late exit error")
	}
}

func TestSupervisorLargeLineAndLimitsAndRedaction(t *testing.T) {
	py := findTestPython(t)
	budgets := config.DefaultResourceBudgets()

	// 1. >64 KiB single NDJSON line (128 KiB) must succeed (proving no bufio.Scanner 64 KiB trap).
	largeOK := writeTempScript(t, `import json, sys
print(json.dumps({"v":1,"type":"hello","helper":"tagger"}), flush=True)
req = json.loads(sys.stdin.readline())
print(json.dumps({"v":1,"type":"result","id":req["id"],"seq":1,"data":{"blob":"x"*131072}}), flush=True)
`)
	supOK := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: largeOK,
		WorkRoot:     t.TempDir(),
		Budgets:      budgets,
	})
	defer supOK.Close()
	res, err := supOK.Run(context.Background(), HelperTagger, "read_tags", 5*time.Second, nil, nil, nil)
	if err != nil {
		t.Fatalf("128 KiB frame should succeed, got %v", err)
	}
	if len(res) < 131072 {
		t.Fatalf("unexpected short result len=%d", len(res))
	}

	// 2. Exceeding HelperMaxLineBytes fails; stderr secrets are redacted; host secret env is NOT leaked.
	t.Setenv("MELOKEEP_ADMIN_PASSWORD", "super-secret-host-password")
	tooBig := writeTempScript(t, `import json, os, sys
sys.stderr.write("Cookie: secret_cookie_val token=mysecret123 https://user:pass@example.com/a?token=abc\n")
sys.stderr.flush()
print(json.dumps({"v":1,"type":"hello","helper":"tagger"}), flush=True)
req = json.loads(sys.stdin.readline())
assert "MELOKEEP_ADMIN_PASSWORD" not in os.environ, "host secret leaked into helper env"
print(json.dumps({"v":1,"type":"result","id":req["id"],"seq":1,"data":{"blob":"x"*4096}}), flush=True)
`)
	smallBudgets := budgets
	smallBudgets.HelperMaxLineBytes = 512
	supLimit := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: tooBig,
		WorkRoot:     t.TempDir(),
		Budgets:      smallBudgets,
	})
	defer supLimit.Close()
	_, err = supLimit.Run(context.Background(), HelperTagger, "read_tags", 5*time.Second, nil, map[string]string{"cookie": "stdin-only"}, nil)
	if err == nil {
		t.Fatal("expected line limit failure")
	}
	errText := err.Error()
	if strings.Contains(errText, "secret_cookie_val") || strings.Contains(errText, "mysecret123") || strings.Contains(errText, "super-secret-host-password") || strings.Contains(errText, "user:pass") {
		t.Fatalf("secret leaked in redacted error: %s", errText)
	}

	// 3. Exceeding HelperMaxOutputBytes across multiple progress frames fails.
	tooMuchOut := writeTempScript(t, `import json, sys
print(json.dumps({"v":1,"type":"hello","helper":"tagger"}), flush=True)
req = json.loads(sys.stdin.readline())
for i in range(1, 20):
    print(json.dumps({"v":1,"type":"progress","id":req["id"],"seq":i,"data":{"chunk":"y"*200}}), flush=True)
`)
	outBudgets := budgets
	outBudgets.HelperMaxLineBytes = 4096
	outBudgets.HelperMaxOutputBytes = 1024
	supOut := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: tooMuchOut,
		WorkRoot:     t.TempDir(),
		Budgets:      outBudgets,
	})
	defer supOut.Close()
	if _, err := supOut.Run(context.Background(), HelperTagger, "read_tags", 5*time.Second, nil, nil, nil); err == nil {
		t.Fatal("expected total stdout output limit error")
	}
}

func TestSupervisorCancelKillsGrandchildProcessTree(t *testing.T) {
	py := findTestPython(t)
	// Standard Win32 Python (non-MSIX AppExecAlias) stays inside the Windows Job Object.
	if _, err := os.Stat(`C:\Python314\python.exe`); err == nil {
		py = `C:\Python314\python.exe`
	}
	pidFile := filepath.Join(t.TempDir(), "pids.json")

	spawner := writeTempScript(t, fmt.Sprintf(`import json, os, subprocess, sys, time
import ctypes
k32 = ctypes.windll.kernel32 if os.name == "nt" else None
in_job = -1
if k32:
    k32.GetCurrentProcess.restype = ctypes.c_void_p
    k32.IsProcessInJob.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
    b = ctypes.c_int()
    k32.IsProcessInJob(k32.GetCurrentProcess(), None, ctypes.byref(b))
    in_job = b.value
p = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
with open(%q, "w", encoding="utf-8") as f:
    json.dump({"parent": os.getpid(), "child": p.pid, "in_job": in_job}, f)
print(json.dumps({"v":1,"type":"hello","helper":"tagger"}), flush=True)
req = json.loads(sys.stdin.readline())
# Ignore cancel intentionally to verify forced Job Object / pgid tree kill.
time.sleep(30)
`, pidFile))

	sup := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: spawner,
		WorkRoot:     t.TempDir(),
		CancelGrace:  120 * time.Millisecond,
	})
	defer sup.Close()

	ctx, cancel := context.WithCancel(context.Background())
	cancelCh := make(chan time.Time, 1)
	go func() {
		for i := 0; i < 200; i++ {
			if _, err := os.Stat(pidFile); err == nil {
				time.Sleep(80 * time.Millisecond)
				cancelCh <- time.Now()
				cancel()
				return
			}
			time.Sleep(25 * time.Millisecond)
		}
		cancelCh <- time.Now()
		cancel()
	}()

	_, err := sup.Run(ctx, HelperTagger, "read_tags", 10*time.Second, nil, nil, nil)
	if err == nil {
		t.Fatal("expected cancelled error")
	}
	cancelAt := <-cancelCh
	if elapsed := time.Since(cancelAt); elapsed > 3*time.Second {
		t.Fatalf("expected process tree killed quickly after cancel, took %v", elapsed)
	}

	raw, err := os.ReadFile(pidFile)
	if err != nil {
		t.Fatalf("expected pid file written: %v", err)
	}
	var pids struct {
		Parent int `json:"parent"`
		Child  int `json:"child"`
		InJob  int `json:"in_job"`
	}
	if err := json.Unmarshal(raw, &pids); err != nil {
		t.Fatal(err)
	}
	t.Logf("pids=%+v", pids)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if !IsPIDAlive(pids.Parent) && !IsPIDAlive(pids.Child) {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("process tree leaked: parentAlive=%v childAlive=%v", IsPIDAlive(pids.Parent), IsPIDAlive(pids.Child))
}

func TestSupervisorConcurrencyCaps(t *testing.T) {
	py := findTestPython(t)
	script := writeTempScript(t, `import json, sys, time
print(json.dumps({"v":1,"type":"hello","helper":"tagger"}), flush=True)
req = json.loads(sys.stdin.readline())
time.sleep(0.08)
print(json.dumps({"v":1,"type":"result","id":req["id"],"seq":1,"data":{"ok":True}}), flush=True)
`)
	budgets := config.DefaultResourceBudgets()
	budgets.TaggerHelpers = 1
	budgets.TotalHelpers = 2

	sup := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: script,
		WorkRoot:     t.TempDir(),
		Budgets:      budgets,
	})
	defer sup.Close()

	var running atomic.Int32
	var maxRunning atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 3; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := sup.Run(context.Background(), HelperTagger, "read_tags", 5*time.Second, nil, nil, func(_ json.RawMessage) {})
			if err != nil {
				t.Errorf("concurrent tagger run failed: %v", err)
			}
		}()
	}
	// Check supervisor slot tracking directly as well.
	for i := 0; i < 4; i++ {
		if err := sup.acquireSlot(context.Background(), HelperTagger); err == nil {
			cur := running.Add(1)
			if cur > maxRunning.Load() {
				maxRunning.Store(cur)
			}
			sup.releaseSlot(HelperTagger)
			running.Add(-1)
		}
	}
	wg.Wait()
	if maxRunning.Load() > 1 {
		t.Fatalf("tagger concurrency exceeded cap 1: %d", maxRunning.Load())
	}
}

func TestRealMutagenTagger7FormatsAndBatch200(t *testing.T) {
	py := findTestPython(t)
	if err := exec.Command(py, "-c", "import mutagen").Run(); err != nil {
		t.Skipf("mutagen unavailable in %s: %v", py, err)
	}
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		candidate := `E:\tool\ffmpeg\bin\ffmpeg.exe`
		if _, statErr := os.Stat(candidate); statErr == nil {
			ffmpeg = candidate
		} else {
			t.Skip("ffmpeg not found")
		}
	}
	taggerScript, err := filepath.Abs(filepath.Join("..", "..", "..", "helpers", "mutagen", "tagger.py"))
	if err != nil {
		t.Fatal(err)
	}

	workDir := t.TempDir()
	sup := NewSupervisor(SupervisorConfig{
		PythonExe:    py,
		TaggerScript: taggerScript,
		WorkRoot:     workDir,
	})
	defer sup.Close()
	client := NewTaggerClient(sup)

	// 1. Boundary check: refuse writing outside AllowedStageRoot.
	if _, err := client.WriteTags(context.Background(), WriteTagsRequest{
		AllowedStageRoot: filepath.Join(workDir, "task-stage"),
		StagePath:        filepath.Join(workDir, "outside.mp3"),
	}); err == nil {
		t.Fatal("expected WriteTags outside AllowedStageRoot to fail")
	}

	// 2. Batch > 200 files must be rejected cleanly.
	tooMany := make([]string, MaxBatchReadTags+1)
	for i := range tooMany {
		tooMany[i] = filepath.Join(workDir, strconv.Itoa(i)+".flac")
	}
	if _, err := client.BatchReadTags(context.Background(), tooMany); err == nil {
		t.Fatal("expected >200 files batch_read_tags to fail")
	}

	// 3. Create 64x64 PNG cover.
	stageRoot := filepath.Join(workDir, "stage-root")
	if err := os.MkdirAll(stageRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	coverPath := filepath.Join(stageRoot, "cover.png")
	img := image.NewRGBA(image.Rect(0, 0, 64, 64))
	for y := 0; y < 64; y++ {
		for x := 0; x < 64; x++ {
			img.Set(x, y, color.RGBA{R: 30, G: 140, B: 210, A: 255})
		}
	}
	cf, _ := os.Create(coverPath)
	_ = png.Encode(cf, img)
	_ = cf.Close()

	wantTags := map[string]string{
		"title": "晨光旋律", "artist": "测试音乐人", "album": "开放声音集", "album_artist": "测试音乐人",
		"date": "2025-01-15", "track_number": "3/11", "disc_number": "1/1", "genre": "Ambient",
		"composer": "测试作曲", "comment": "测试备注", "lyricist": "测试词作", "language": "zho",
		"copyright": "2025 CC-BY", "publisher": "Open Audio Lab", "grouping": "Instrumental", "bpm": "90",
		"isrc": "ZZTST2500103",
	}
	wantLyrics := "[00:01.00]清晨的风吹过\n"

	formats := []struct {
		ext  string
		args []string
	}{
		{".mp3", []string{"-c:a", "libmp3lame", "-b:a", "192k"}},
		{".flac", []string{"-c:a", "flac"}},
		{".m4a", []string{"-c:a", "aac", "-b:a", "192k"}},
		{".wav", []string{"-c:a", "pcm_s16le"}},
		{".ogg", []string{"-c:a", "libvorbis"}},
		{".opus", []string{"-c:a", "libopus"}},
		{".wma", []string{"-c:a", "wmav2"}},
	}

	pcmHash := func(p string) string {
		out, err := exec.Command(ffmpeg, "-v", "error", "-xerror", "-i", p, "-map", "0:a:0", "-vn", "-ar", "44100", "-ac", "2", "-c:a", "pcm_s32le", "-f", "hash", "-hash", "sha256", "-").CombinedOutput()
		if err != nil {
			t.Fatalf("pcm hash %s: %v (%s)", p, err, string(out))
		}
		return strings.TrimSpace(string(out))
	}

	for _, f := range formats {
		orig := filepath.Join(workDir, "orig"+f.ext)
		args := append([]string{"-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1"}, f.args...)
		args = append(args, orig)
		if out, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
			t.Fatalf("generate %s: %v (%s)", f.ext, err, string(out))
		}
		beforeHash := pcmHash(orig)
		stage := filepath.Join(stageRoot, "stage"+f.ext)
		rawAudio, _ := os.ReadFile(orig)
		_ = os.WriteFile(stage, rawAudio, 0o600)

		res, err := client.WriteTags(context.Background(), WriteTagsRequest{
			AllowedStageRoot: stageRoot,
			StagePath:        stage,
			Tags:             wantTags,
			Lyrics:           &wantLyrics,
			Cover:            &CoverSpec{Path: coverPath, Mime: "image/png"},
		})
		if err != nil {
			t.Fatalf("WriteTags %s: %v", f.ext, err)
		}
		afterHash := pcmHash(stage)
		if afterHash != beforeHash {
			t.Fatalf("%s audio PCM mutated: before=%s after=%s", f.ext, beforeHash, afterHash)
		}
		for k, v := range wantTags {
			if res.Tags[k] != v {
				t.Fatalf("%s tag %s mismatch: got %q want %q", f.ext, k, res.Tags[k], v)
			}
		}
		if res.Lyrics != wantLyrics || !res.HasCover {
			t.Fatalf("%s lyrics/cover mismatch: lyrics=%q hasCover=%v", f.ext, res.Lyrics, res.HasCover)
		}
	}

	// 4. 200-file batch_read_tags (199 valid FLAC + 1 corrupt file).
	batch := make([]string, 0, 200)
	srcFLAC, _ := os.ReadFile(filepath.Join(stageRoot, "stage.flac"))
	for i := 0; i < 199; i++ {
		p := filepath.Join(workDir, fmt.Sprintf("b-%03d.flac", i))
		_ = os.WriteFile(p, srcFLAC, 0o600)
		batch = append(batch, p)
	}
	corrupt := filepath.Join(workDir, "corrupt.flac")
	_ = os.WriteFile(corrupt, []byte("corrupt-audio"), 0o600)
	batch = append(batch, corrupt)

	items, err := client.BatchReadTags(context.Background(), batch)
	if err != nil {
		t.Fatalf("BatchReadTags: %v", err)
	}
	if len(items) != 200 {
		t.Fatalf("expected 200 items, got %d", len(items))
	}
	okCount, errCount := 0, 0
	for _, it := range items {
		if it.OK {
			okCount++
		} else {
			errCount++
		}
	}
	if okCount != 199 || errCount != 1 {
		t.Fatalf("expected 199 ok + 1 err, got ok=%d err=%d", okCount, errCount)
	}
}
