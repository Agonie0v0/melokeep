package risks

import (
	"bufio"
	"encoding/json"
	"os/exec"
	"testing"
	"time"
)

func TestJobObjectKillsParentAndGrandchild(t *testing.T) {
	script := `
import json, subprocess, sys, time
child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
print(json.dumps({"parent": __import__("os").getpid(), "grandchild": child.pid}), flush=True)
time.sleep(60)
`
	cmd := exec.Command("py", "-3.13", "-c", script)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatalf("StdoutPipe: %v", err)
	}
	tree, err := StartInJob(cmd)
	if err != nil {
		t.Fatalf("StartInJob: %v", err)
	}
	defer func() { _ = tree.Kill() }()

	scanner := bufio.NewScanner(stdout)
	if !scanner.Scan() {
		t.Fatalf("failed reading PIDs: %v", scanner.Err())
	}
	var pids struct {
		Parent     int `json:"parent"`
		Grandchild int `json:"grandchild"`
	}
	if err := json.Unmarshal(scanner.Bytes(), &pids); err != nil {
		t.Fatalf("decode PIDs: %v", err)
	}
	if pids.Parent <= 0 || pids.Grandchild <= 0 {
		t.Fatalf("invalid PIDs: %+v", pids)
	}
	if !IsPIDAlive(pids.Parent) || !IsPIDAlive(pids.Grandchild) {
		t.Fatalf("expected parent and grandchild alive before kill: %+v", pids)
	}

	start := time.Now()
	if err := tree.Kill(); err != nil {
		t.Fatalf("tree.Kill: %v", err)
	}
	_ = cmd.Wait()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if !IsPIDAlive(pids.Parent) && !IsPIDAlive(pids.Grandchild) {
			t.Logf("killed parent=%d and grandchild=%d in %v", pids.Parent, pids.Grandchild, time.Since(start))
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("leaked process after kill: parentAlive=%v grandchildAlive=%v", IsPIDAlive(pids.Parent), IsPIDAlive(pids.Grandchild))
}
