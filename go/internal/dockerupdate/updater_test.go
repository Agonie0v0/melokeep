package dockerupdate

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestExtractContainerIDAndNormalizeImageRef(t *testing.T) {
	wantID := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	mountInfo := "123 100 0:45 /docker/containers/" + wantID + "/resolv.conf /etc/resolv.conf rw - ext4 /dev/sda1 rw\n"
	if got := ExtractContainerIDFromMountInfo(mountInfo); got != wantID {
		t.Fatalf("ExtractContainerIDFromMountInfo = %q, want %q", got, wantID)
	}

	cgroup := "0::/system.slice/docker-" + wantID + ".scope\n"
	if got := ExtractContainerIDFromCgroup(cgroup); got != wantID {
		t.Fatalf("ExtractContainerIDFromCgroup = %q, want %q", got, wantID)
	}

	if got := NormalizeImageRef("ghcr.io/agonie0v0/melokeep"); got != "ghcr.io/agonie0v0/melokeep:latest" {
		t.Fatalf("NormalizeImageRef untagged = %q", got)
	}
	if got := NormalizeImageRef("127.0.0.1:5000/melokeep"); got != "127.0.0.1:5000/melokeep:latest" {
		t.Fatalf("NormalizeImageRef registry port untagged = %q", got)
	}
	repo, tag := SplitImageRef("127.0.0.1:5000/melokeep:v1.2.3")
	if repo != "127.0.0.1:5000/melokeep" || tag != "v1.2.3" {
		t.Fatalf("SplitImageRef = (%q, %q)", repo, tag)
	}
}

func TestProbePriorityAndPermissionErrors(t *testing.T) {
	ctx := context.Background()

	// 1. Webhook takes highest priority.
	mWebhook := NewManager(Options{
		WebhookURL: "https://hooks.example.com/update",
	})
	st := mWebhook.Probe(ctx)
	if !st.Supported || st.Mode != "webhook" {
		t.Fatalf("expected webhook mode supported, got %+v", st)
	}

	// 2. Missing socket in container returns mode=none with compose mount guide.
	mMissing := NewManager(Options{
		InContainer: true,
		SocketPath:  filepath.Join(t.TempDir(), "nonexistent.sock"),
	})
	st = mMissing.Probe(ctx)
	if st.Supported || st.Mode != "none" || st.Reason != ReasonSocketMissing {
		t.Fatalf("expected missing socket reason, got %+v", st)
	}

	// 3. Socket exists but returns 403 Forbidden -> mode=docker_socket, supported=false, ReasonSocketPermDenied.
	permSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer permSrv.Close()
	mPerm := NewManager(Options{
		InContainer: true,
		SocketPath:  permSrv.URL,
	})
	st = mPerm.Probe(ctx)
	if st.Supported || st.Mode != "docker_socket" || st.Reason != ReasonSocketPermDenied {
		t.Fatalf("expected permission denied reason, got %+v", st)
	}

	// 4. Disabled via AllowSelfUpdate=false.
	allowFalse := false
	mDisabled := NewManager(Options{
		InContainer:     true,
		SocketPath:      permSrv.URL,
		AllowSelfUpdate: &allowFalse,
	})
	st = mDisabled.Probe(ctx)
	if st.Supported || st.Mode != "none" {
		t.Fatalf("expected disabled self update to report mode=none, got %+v", st)
	}
}

func TestManagerStartUpToDateAndRecreate(t *testing.T) {
	ctx := context.Background()
	selfID := "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

	var mu sync.Mutex
	pulledImageSHA := "sha256:old-image"
	var helperCreated bool
	var helperStarted bool
	var helperCmd []any
	blockPull := make(chan struct{})
	close(blockPull)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/_ping":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("OK"))
		case r.Method == http.MethodGet && r.URL.Path == "/containers/"+selfID+"/json":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"Id":    selfID,
				"Name":  "/melokeep",
				"Image": "sha256:old-image",
				"Config": map[string]any{
					"Image": "ghcr.io/agonie0v0/melokeep:latest",
				},
			})
		case r.Method == http.MethodPost && r.URL.Path == "/images/create":
			mu.Lock()
			ch := blockPull
			mu.Unlock()
			<-ch
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"Downloading","id":"layer1","progressDetail":{"current":50,"total":100}}` + "\n"))
			_, _ = w.Write([]byte(`{"status":"Pull complete","id":"layer1","progressDetail":{"current":100,"total":100}}` + "\n"))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/images/"):
			mu.Lock()
			sha := pulledImageSHA
			mu.Unlock()
			_ = json.NewEncoder(w).Encode(map[string]any{"Id": sha})
		case r.Method == http.MethodPost && r.URL.Path == "/containers/create":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			mu.Lock()
			helperCreated = true
			helperCmd, _ = body["Cmd"].([]any)
			mu.Unlock()
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]any{"Id": "helper-123"})
		case r.Method == http.MethodPost && r.URL.Path == "/containers/helper-123/start":
			mu.Lock()
			helperStarted = true
			mu.Unlock()
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	mountFile := filepath.Join(t.TempDir(), "mountinfo")
	_ = os.WriteFile(mountFile, []byte("/docker/containers/"+selfID+"/hostname\n"), 0o644)

	mgr := NewManager(Options{
		InContainer:   true,
		SocketPath:    srv.URL,
		MountInfoPath: mountFile,
	})

	// Case 1: Image SHA unchanged -> stage="up_to_date" without creating helper container.
	if _, err := mgr.Start(ctx, ""); err != nil {
		t.Fatalf("Start up_to_date: %v", err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		st := mgr.Status(ctx)
		if !st.InProgress {
			if st.Stage != "up_to_date" || st.Progress != 100 {
				t.Fatalf("expected stage=up_to_date progress=100, got %+v", st)
			}
			break
		}
		time.Sleep(15 * time.Millisecond)
	}
	mu.Lock()
	if helperCreated {
		t.Fatalf("helper container should not be created when image is already up_to_date")
	}
	// Case 2: Image SHA updated -> spawns helper container and transitions to stage="restarting".
	pulledImageSHA = "sha256:new-image"
	gate := make(chan struct{})
	blockPull = gate
	mu.Unlock()

	if _, err := mgr.Start(ctx, ""); err != nil {
		t.Fatalf("Start recreate: %v", err)
	}
	// Concurrent Start while in_progress must return 409 Conflict.
	if _, err := mgr.Start(ctx, ""); err == nil {
		t.Fatalf("expected 409 conflict on concurrent Start")
	}
	close(gate)

	for time.Now().Before(time.Now().Add(3 * time.Second)) {
		st := mgr.Status(ctx)
		if st.Stage == "restarting" {
			break
		}
		time.Sleep(15 * time.Millisecond)
	}
	stFinal := mgr.Status(ctx)
	if stFinal.Stage != "restarting" {
		t.Fatalf("expected stage=restarting, got %+v", stFinal)
	}
	mu.Lock()
	defer mu.Unlock()
	if !helperCreated || !helperStarted || len(helperCmd) == 0 {
		t.Fatalf("expected helper created and started, created=%v started=%v cmd=%v", helperCreated, helperStarted, helperCmd)
	}
}

func TestRunHelperSwapAndRollback(t *testing.T) {
	ctx := context.Background()
	targetID := "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff"

	t.Run("successful swap stops renames creates starts and removes rollback", func(t *testing.T) {
		var mu sync.Mutex
		var events []string
		var createdConfig map[string]any

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			mu.Lock()
			defer mu.Unlock()
			switch {
			case r.Method == http.MethodGet && r.URL.Path == "/containers/"+targetID+"/json":
				events = append(events, "inspect")
				_ = json.NewEncoder(w).Encode(map[string]any{
					"Id":    targetID,
					"Name":  "/melokeep",
					"Image": "sha256:old",
					"Config": map[string]any{
						"Image":    "ghcr.io/agonie0v0/melokeep:latest",
						"Hostname": "112233445566",
						"Env":      []string{"MELOKEEP_PORT=8765"},
					},
					"HostConfig": map[string]any{
						"Binds": []string{"/host/data:/data", "/host/music:/music"},
					},
					"NetworkSettings": map[string]any{
						"Networks": map[string]any{
							"bridge":     map[string]any{},
							"custom_net": map[string]any{"Aliases": []string{"melokeep"}},
						},
					},
				})
			case r.Method == http.MethodPost && r.URL.Path == "/containers/"+targetID+"/stop":
				events = append(events, "stop_old")
				w.WriteHeader(http.StatusNoContent)
			case r.Method == http.MethodPost && r.URL.Path == "/containers/"+targetID+"/rename":
				name := r.URL.Query().Get("name")
				if strings.HasPrefix(name, "melokeep-rollback-") {
					events = append(events, "rename_to_rollback")
				} else {
					events = append(events, "rename_to_"+name)
				}
				w.WriteHeader(http.StatusNoContent)
			case r.Method == http.MethodPost && r.URL.Path == "/containers/create":
				events = append(events, "create_new:"+r.URL.Query().Get("name"))
				_ = json.NewDecoder(r.Body).Decode(&createdConfig)
				w.WriteHeader(http.StatusCreated)
				_ = json.NewEncoder(w).Encode(map[string]any{"Id": "new-container-999"})
			case r.Method == http.MethodPost && r.URL.Path == "/networks/custom_net/connect":
				events = append(events, "connect_custom_net")
				w.WriteHeader(http.StatusOK)
			case r.Method == http.MethodPost && r.URL.Path == "/containers/new-container-999/start":
				events = append(events, "start_new")
				w.WriteHeader(http.StatusNoContent)
			case r.Method == http.MethodDelete && r.URL.Path == "/containers/"+targetID:
				events = append(events, "remove_old")
				w.WriteHeader(http.StatusNoContent)
			default:
				http.NotFound(w, r)
			}
		}))
		defer srv.Close()

		err := RunHelper(ctx, HelperOptions{
			SocketPath:  srv.URL,
			TargetID:    targetID,
			TargetImage: "ghcr.io/agonie0v0/melokeep:latest",
		})
		if err != nil {
			t.Fatalf("RunHelper failed: %v", err)
		}

		wantOrder := []string{
			"inspect",
			"stop_old",
			"rename_to_rollback",
			"create_new:melokeep",
			"connect_custom_net",
			"start_new",
			"remove_old",
		}
		if !slices.Equal(events, wantOrder) {
			t.Fatalf("unexpected helper lifecycle order:\ngot:  %v\nwant: %v", events, wantOrder)
		}
		if hn, _ := createdConfig["Hostname"].(string); hn != "" {
			t.Fatalf("expected short container ID hostname cleared on new container, got %q", hn)
		}
	})

	t.Run("failure to start new container rolls back to original container", func(t *testing.T) {
		var mu sync.Mutex
		var events []string

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			mu.Lock()
			defer mu.Unlock()
			switch {
			case r.Method == http.MethodGet && r.URL.Path == "/containers/"+targetID+"/json":
				events = append(events, "inspect")
				_ = json.NewEncoder(w).Encode(map[string]any{
					"Id":     targetID,
					"Name":   "/melokeep",
					"Config": map[string]any{"Image": "ghcr.io/agonie0v0/melokeep:latest"},
				})
			case r.Method == http.MethodPost && r.URL.Path == "/containers/"+targetID+"/stop":
				events = append(events, "stop_old")
				w.WriteHeader(http.StatusNoContent)
			case r.Method == http.MethodPost && r.URL.Path == "/containers/"+targetID+"/rename":
				name := r.URL.Query().Get("name")
				if strings.HasPrefix(name, "melokeep-rollback-") {
					events = append(events, "rename_to_rollback")
				} else if name == "melokeep" {
					events = append(events, "rollback_rename_orig")
				}
				w.WriteHeader(http.StatusNoContent)
			case r.Method == http.MethodPost && r.URL.Path == "/containers/create":
				events = append(events, "create_new")
				w.WriteHeader(http.StatusCreated)
				_ = json.NewEncoder(w).Encode(map[string]any{"Id": "broken-new-id"})
			case r.Method == http.MethodPost && r.URL.Path == "/containers/broken-new-id/start":
				events = append(events, "start_new_fail")
				w.WriteHeader(http.StatusInternalServerError)
				_, _ = w.Write([]byte(`{"message":"port bind conflict"}`))
			case r.Method == http.MethodDelete && r.URL.Path == "/containers/broken-new-id":
				events = append(events, "remove_broken_new")
				w.WriteHeader(http.StatusNoContent)
			case r.Method == http.MethodPost && r.URL.Path == "/containers/"+targetID+"/start":
				events = append(events, "rollback_start_old")
				w.WriteHeader(http.StatusNoContent)
			default:
				http.NotFound(w, r)
			}
		}))
		defer srv.Close()

		err := RunHelper(ctx, HelperOptions{
			SocketPath:  srv.URL,
			TargetID:    targetID,
			TargetImage: "ghcr.io/agonie0v0/melokeep:latest",
		})
		if err == nil {
			t.Fatalf("expected RunHelper error when new container fails to start")
		}
		wantOrder := []string{
			"inspect",
			"stop_old",
			"rename_to_rollback",
			"create_new",
			"start_new_fail",
			"remove_broken_new",
			"rollback_rename_orig",
			"rollback_start_old",
		}
		if !slices.Equal(events, wantOrder) {
			t.Fatalf("unexpected rollback sequence:\ngot:  %v\nwant: %v", events, wantOrder)
		}
	})
}
