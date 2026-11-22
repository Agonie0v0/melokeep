package agent

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

func openTestEnv(t *testing.T) (*store.Store, *operations.Registry, *transport.Pool) {
	t.Helper()
	dbPath := filepath.Join(t.TempDir(), "go", "melokeep.db")
	st, err := store.Open(context.Background(), store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	reg := operations.NewRegistry(store.NewIdempotencyStore(st, time.Hour))
	if err := operations.RegisterCoreSpecs(reg); err != nil {
		t.Fatalf("register core specs: %v", err)
	}
	pool := transport.NewPool(transport.WithAllowTestLoopback(true))
	return st, reg, pool
}

func waitForTerminalSession(t *testing.T, svc *Service, id string, timeout time.Duration) PublicSessionDTO {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		pub, err := svc.GetSession(context.Background(), id)
		if err != nil {
			t.Fatalf("get session: %v", err)
		}
		switch pub.Status {
		case StatusComplete, StatusFailed, StatusInterrupted, StatusNeedsDecision:
			return pub
		}
		time.Sleep(10 * time.Millisecond)
	}
	pub, _ := svc.GetSession(context.Background(), id)
	t.Fatalf("timed out waiting for session %s, last status=%s detail=%s", id, pub.Status, pub.Detail)
	return PublicSessionDTO{}
}

func TestSettingsRedactionHiddenMergeAndImageLimits(t *testing.T) {
	st, reg, pool := openTestEnv(t)
	svc := NewService(st, reg, NewClient(pool, config.DefaultResourceBudgets()), Options{
		ProxyResolver: func() string { return "http://127.0.0.1:7890" },
	})
	defer svc.Close()
	if err := RegisterAgentHandlers(reg, svc); err != nil {
		t.Fatalf("RegisterAgentHandlers: %v", err)
	}

	ctx := context.Background()
	pub, err := svc.PutSettings(ctx, Settings{
		Enabled:   true,
		BaseURL:   "https://api.example.com/v1",
		Model:     "gpt-test",
		APIKey:    "sk-real-secret-key",
		APIType:   "chat_completions",
		ProxyMode: "direct",
	})
	if err != nil {
		t.Fatalf("PutSettings: %v", err)
	}
	if !pub.KeyConfigured || !pub.ProjectProxyConfigured {
		t.Fatalf("expected key and proxy configured flags: %+v", pub)
	}
	rawPub, _ := json.Marshal(pub)
	if strings.Contains(string(rawPub), "sk-real-secret-key") {
		t.Fatalf("public settings leaked secret: %s", string(rawPub))
	}

	// Sending [已隐藏] must preserve the existing secret.
	_, err = svc.PutSettings(ctx, Settings{
		Enabled:   true,
		BaseURL:   "https://api.example.com/v1",
		Model:     "gpt-test-2",
		APIKey:    config.HiddenPlaceholder,
		APIType:   "responses",
		ProxyMode: "project",
	})
	if err != nil {
		t.Fatalf("PutSettings hidden merge: %v", err)
	}
	loaded, err := svc.loadSettings(ctx)
	if err != nil {
		t.Fatalf("loadSettings: %v", err)
	}
	if loaded.APIKey != "sk-real-secret-key" || loaded.Model != "gpt-test-2" {
		t.Fatalf("secret not preserved across hidden merge: %+v", loaded)
	}

	// Operations handler for GET /api/agent/settings works and stays redacted.
	res, err := reg.Dispatch(ctx, operations.Request{
		Operation: "GET /api/agent/settings",
		Actor:     auth.WebAdminActor("admin"),
	})
	if err != nil {
		t.Fatalf("dispatch GET /api/agent/settings: %v", err)
	}
	if b, _ := json.Marshal(res); strings.Contains(string(b), "sk-real-secret-key") {
		t.Fatalf("operation output leaked secret: %s", string(b))
	}

	// Image limit check (>5 MiB rejected with 400).
	oversized := make([]byte, MaxImageBytes+100)
	copy(oversized, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
	badReq := TurnRequest{
		Message: "识别歌单",
		Image:   "data:image/png;base64," + base64.StdEncoding.EncodeToString(oversized),
	}
	err = ValidateTurnRequest(badReq, 5<<20)
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusBadRequest {
		t.Fatalf("expected 400 for oversized image, got %v", err)
	}
}

func TestChatCompletionsStreamAndResponsesProtocols(t *testing.T) {
	var authHeaderSeen atomic.Value
	var chatStep atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeaderSeen.Store(r.Header.Get("Authorization"))
		switch r.URL.Path {
		case "/v1/models":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"data": []map[string]any{{"id": "gpt-b"}, {"id": "gpt-a"}},
			})
		case "/v1/chat/completions":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			step := chatStep.Add(1)
			if stream, _ := body["stream"].(bool); stream {
				w.Header().Set("Content-Type", "text/event-stream")
				if step == 1 {
					_, _ = fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n")
					_, _ = fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"，已完成\",\"tool_calls\":[{\"index\":0,\"id\":\"call_s1\",\"function\":{\"name\":\"list_playlists\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n")
				} else {
					_, _ = fmt.Fprint(w, "data: {\"choices\":[{\"delta\":{\"content\":\"歌单已查询完毕\"},\"finish_reason\":\"stop\"}]}\n\n")
				}
				_, _ = fmt.Fprint(w, "data: [DONE]\n\n")
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{
					{"message": map[string]any{"content": "歌单已查询完毕"}},
				},
			})
		case "/v1/responses":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"status": "completed",
				"output": []map[string]any{
					{
						"type": "message",
						"content": []map[string]any{
							{"type": "output_text", "text": "Responses 模式回复"},
						},
					},
				},
			})
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	st, reg, pool := openTestEnv(t)
	_ = reg.RegisterHandler("GET /api/playlists", func(ctx context.Context, req operations.Request) (any, error) {
		return map[string]any{"playlists": []map[string]any{{"id": "pl_1", "name": "我喜欢的"}}}, nil
	})
	svc := NewService(st, reg, NewClient(pool, config.DefaultResourceBudgets()), Options{})
	defer svc.Close()

	ctx := context.Background()
	if _, err := svc.PutSettings(ctx, Settings{
		Enabled: true,
		BaseURL: srv.URL + "/v1",
		Model:   "gpt-a",
		APIKey:  "sk-live-abc",
		APIType: "chat_completions",
		Stream:  true,
	}); err != nil {
		t.Fatalf("PutSettings: %v", err)
	}

	modelsResp, err := svc.ListModels(ctx, nil)
	if err != nil {
		t.Fatalf("ListModels: %v", err)
	}
	if got := modelsResp["models"].([]string); len(got) != 2 || got[0] != "gpt-a" {
		t.Fatalf("unexpected models: %#v", got)
	}

	// Streaming chat completions with tool call.
	sess, err := svc.CreateSession(ctx)
	if err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	if _, err := svc.StartTurn(ctx, auth.WebAdminActor("admin"), sess.ID, TurnRequest{Message: "查看歌单"}); err != nil {
		t.Fatalf("StartTurn chat: %v", err)
	}
	done := waitForTerminalSession(t, svc, sess.ID, 5*time.Second)
	if done.Status != StatusComplete || len(done.Events) != 1 || done.Events[0].Status != "complete" {
		t.Fatalf("unexpected chat stream session result: %+v", done)
	}

	// Switch to responses protocol.
	if _, err := svc.PutSettings(ctx, Settings{
		Enabled: true,
		BaseURL: srv.URL + "/v1",
		Model:   "gpt-a",
		APIKey:  config.HiddenPlaceholder,
		APIType: "responses",
	}); err != nil {
		t.Fatalf("PutSettings responses: %v", err)
	}
	sess2, _ := svc.CreateSession(ctx)
	if _, err := svc.StartTurn(ctx, auth.WebAdminActor("admin"), sess2.ID, TurnRequest{Message: "测试 responses"}); err != nil {
		t.Fatalf("StartTurn responses: %v", err)
	}
	done2 := waitForTerminalSession(t, svc, sess2.ID, 5*time.Second)
	if done2.Status != StatusComplete || len(done2.Messages) < 2 || done2.Messages[len(done2.Messages)-1].Content != "Responses 模式回复" {
		t.Fatalf("unexpected responses session result: %+v", done2)
	}
	if gotAuth, _ := authHeaderSeen.Load().(string); gotAuth != "Bearer sk-live-abc" {
		t.Fatalf("expected bearer secret sent on wire, got %q", gotAuth)
	}
}

func TestReadOnlyRevocationIdempotencyForceNewAndRedaction(t *testing.T) {
	var step atomic.Int32
	var writeCalls atomic.Int32
	var writeAllowed atomic.Bool
	writeAllowed.Store(true)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := step.Add(1)
		switch n {
		case 1:
			// First call: try forbidden absolute path & actor spoof, plus valid search, plus force_new without nonce, plus create_playlist
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{{
					"message": map[string]any{
						"tool_calls": []map[string]any{
							{
								"id":   "c_abs",
								"type": "function",
								"function": map[string]any{
									"name":      "execute_project",
									"arguments": `{"operation":"DELETE /api/files","body":{"path":"C:\\secret\\a.mp3"}}`,
								},
							},
							{
								"id":   "c_spoof",
								"type": "function",
								"function": map[string]any{
									"name":      "execute_project",
									"arguments": `{"operation":"POST /api/playlists","body":{"name":"x","role":"admin"}}`,
								},
							},
							{
								"id":   "c_search",
								"type": "function",
								"function": map[string]any{
									"name":      "search_music",
									"arguments": `{"keyword":"星海微风"}`,
								},
							},
							{
								"id":   "c_forcenew_bad",
								"type": "function",
								"function": map[string]any{
									"name":      "execute_project",
									"arguments": `{"operation":"POST /api/downloads","force_new":true,"body":{"id":"trk_1"}}`,
								},
							},
						},
					},
				}},
			})
		case 2:
			// Second call: execute create_playlist, then revoke write permission before third call
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{{
					"message": map[string]any{
						"tool_calls": []map[string]any{{
							"id":   "c_pl_ok",
							"type": "function",
							"function": map[string]any{
								"name":      "create_playlist",
								"arguments": `{"name":"好听的歌"}`,
							},
						}},
					},
				}},
			})
		case 3:
			// Revoke writes mid-turn and attempt another write
			writeAllowed.Store(false)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{{
					"message": map[string]any{
						"tool_calls": []map[string]any{{
							"id":   "c_pl_revoked",
							"type": "function",
							"function": map[string]any{
								"name":      "create_playlist",
								"arguments": `{"name":"撤销后不应创建"}`,
							},
						}},
					},
				}},
			})
		default:
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{{
					"message": map[string]any{"content": "已按权限处理"},
				}},
			})
		}
	}))
	defer srv.Close()

	st, reg, pool := openTestEnv(t)
	_ = reg.RegisterHandler("POST /api/search", func(ctx context.Context, req operations.Request) (any, error) {
		return map[string]any{
			"tracks": []map[string]any{{"id": "trk_1", "title": "星海微风", "artist": "独立乐手"}},
			"cookie": "super-secret-cookie",
			"url":    "https://user:pass@cdn.example.com/a.mp3?token=abc",
		}, nil
	})
	_ = reg.RegisterHandler("POST /api/playlists", func(ctx context.Context, req operations.Request) (any, error) {
		writeCalls.Add(1)
		return map[string]any{"id": "pl_new", "detail": "已创建歌单"}, nil
	})
	_ = reg.RegisterHandler("POST /api/downloads", func(ctx context.Context, req operations.Request) (any, error) {
		writeCalls.Add(1)
		return map[string]any{"downloads": []map[string]any{{"id": "dl_1"}}}, nil
	})

	svc := NewService(st, reg, NewClient(pool, config.DefaultResourceBudgets()), Options{
		WriteToggle: func(ctx context.Context, actor auth.Actor) (bool, string) {
			if !writeAllowed.Load() {
				return false, "管理员已关闭 AI 写权限"
			}
			return true, ""
		},
	})
	defer svc.Close()

	ctx := context.Background()
	_, _ = svc.PutSettings(ctx, Settings{
		Enabled: true,
		BaseURL: srv.URL + "/v1",
		Model:   "gpt-guard",
		APIKey:  "sk-guard",
		APIType: "chat_completions",
	})

	sess, _ := svc.CreateSession(ctx)
	_, err := svc.StartTurn(ctx, auth.WebAdminActor("admin"), sess.ID, TurnRequest{Message: "执行安全测试"})
	if err != nil {
		t.Fatalf("StartTurn: %v", err)
	}
	done := waitForTerminalSession(t, svc, sess.ID, 5*time.Second)
	if done.Status != StatusComplete {
		t.Fatalf("expected StatusComplete, got %s (%s)", done.Status, done.Detail)
	}
	if writeCalls.Load() != 1 {
		t.Fatalf("expected exactly 1 write call before revocation, got %d", writeCalls.Load())
	}
	// Check event outcomes & secret redaction.
	rawEvents, _ := json.Marshal(done.Events)
	if strings.Contains(string(rawEvents), "super-secret-cookie") || strings.Contains(string(rawEvents), "token=abc") || strings.Contains(string(rawEvents), "user:pass") {
		t.Fatalf("tool output leaked secrets into events: %s", string(rawEvents))
	}
	if len(done.Events) != 6 {
		t.Fatalf("expected 6 events, got %d: %+v", len(done.Events), done.Events)
	}
	if done.Events[0].Status != "failed" || done.Events[1].Status != "failed" || done.Events[2].Status != "complete" ||
		done.Events[3].Status != "failed" || done.Events[4].Status != "complete" || done.Events[5].Status != "failed" {
		t.Fatalf("unexpected event statuses: %+v", done.Events)
	}

	// Test read-only AI actor directly rejects write tools.
	var roStep atomic.Int32
	roSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if roStep.Add(1) == 1 {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{{
					"message": map[string]any{
						"tool_calls": []map[string]any{{
							"id":   "c_ro",
							"type": "function",
							"function": map[string]any{
								"name":      "create_playlist",
								"arguments": `{"name":"只读不应创建"}`,
							},
						}},
					},
				}},
			})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{{"message": map[string]any{"content": "只读拦截"}}},
		})
	}))
	defer roSrv.Close()
	_, _ = svc.PutSettings(ctx, Settings{
		Enabled: true,
		BaseURL: roSrv.URL + "/v1",
		Model:   "gpt-guard",
		APIKey:  "sk-guard",
		APIType: "chat_completions",
	})
	sessRO, _ := svc.CreateSession(ctx)
	allowFalse := false
	_, _ = svc.StartTurn(ctx, auth.WebAdminActor("admin"), sessRO.ID, TurnRequest{Message: "只读", AllowActions: &allowFalse})
	doneRO := waitForTerminalSession(t, svc, sessRO.ID, 5*time.Second)
	if len(doneRO.Events) != 1 || doneRO.Events[0].Status != "failed" || writeCalls.Load() != 1 {
		t.Fatalf("read-only mode should block write tool without executing handler: %+v", doneRO.Events)
	}

	// Verify direct Registry idempotency conflict (same tool_call_id, different payload -> 409).
	_, err = reg.Dispatch(ctx, operations.Request{
		Operation:      "POST /api/playlists",
		Actor:          auth.WebAdminActor("admin"),
		IdempotencyKey: "ai:" + sess.ID + ":t1:c_pl_ok",
		Payload:        map[string]any{"name": "first"},
	})
	if err != nil {
		t.Fatalf("first idempotent dispatch failed: %v", err)
	}
	_, err = reg.Dispatch(ctx, operations.Request{
		Operation:      "POST /api/playlists",
		Actor:          auth.WebAdminActor("admin"),
		IdempotencyKey: "ai:" + sess.ID + ":t1:c_pl_ok",
		Payload:        map[string]any{"name": "different"},
	})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 idempotency conflict, got %v", err)
	}
}

func TestStopPreservesDownloadsAndStartupRecoveryNeedsDecision(t *testing.T) {
	var downloadCancelled atomic.Bool
	var turnCount atomic.Int32

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if turnCount.Add(1) == 1 {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{{
					"message": map[string]any{
						"tool_calls": []map[string]any{{
							"id":   "c_dl",
							"type": "function",
							"function": map[string]any{
								"name":      "download_music",
								"arguments": `{"track_ids":["trk_dl_1"]}`,
							},
						}},
					},
				}},
			})
			return
		}
		// Return no tool calls so the turn enters watch loop.
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{{"message": map[string]any{"content": "下载已入队，正在跟踪进度"}}},
		})
	}))
	defer srv.Close()

	st, reg, pool := openTestEnv(t)
	_ = reg.RegisterHandler("POST /api/downloads", func(ctx context.Context, req operations.Request) (any, error) {
		return map[string]any{"downloads": []map[string]any{{"id": "dl_independent_1", "status": "queued"}}}, nil
	})

	svc := NewService(st, reg, NewClient(pool, config.DefaultResourceBudgets()), Options{
		WaitPollInterval: 30 * time.Millisecond,
		TaskLookup: func(ctx context.Context, ids []string) ([]TaskState, error) {
			return []TaskState{{ID: "dl_independent_1", Status: "downloading", Terminal: false}}, nil
		},
	})
	defer svc.Close()

	ctx := context.Background()
	_, _ = svc.PutSettings(ctx, Settings{
		Enabled: true,
		BaseURL: srv.URL + "/v1",
		Model:   "gpt-stop",
		APIKey:  "sk-stop",
		APIType: "chat_completions",
	})
	sessPub, _ := svc.CreateSession(ctx)
	// Seed known track_id in session
	sess, _ := svc.loadSession(ctx, sessPub.ID)
	sess.TrackIDs = []string{"trk_dl_1"}
	_ = svc.saveSession(ctx, &sess)

	if _, err := svc.StartTurn(ctx, auth.WebAdminActor("admin"), sess.ID, TurnRequest{Message: "下载这首歌"}); err != nil {
		t.Fatalf("StartTurn: %v", err)
	}

	// Wait until session enters waiting_tasks.
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		cur, _ := svc.GetSession(ctx, sess.ID)
		if cur.Status == StatusWaitingTasks {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	stopped, err := svc.Stop(ctx, sess.ID)
	if err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if stopped.Status != StatusInterrupted {
		t.Fatalf("expected StatusInterrupted after Stop, got %s", stopped.Status)
	}
	if downloadCancelled.Load() {
		t.Fatalf("Stop must not cancel independent downloads")
	}

	// Simulate crash mid-write (PendingWrite.State == "started", unknown outcome).
	var replayCalls atomic.Int32
	_ = reg.RegisterHandler("POST /api/playlists", func(ctx context.Context, req operations.Request) (any, error) {
		replayCalls.Add(1)
		return map[string]any{"id": "pl"}, nil
	})
	crashed := Session{
		ID:           model.NewUUID(),
		Title:        "崩溃会话",
		Status:       StatusRunning,
		ActiveTurnID: "turn_crash",
		Messages:     []SessionMessage{{Role: "user", Content: "建歌单", CreatedAt: 1}},
		Events: []ExecutionEvent{{
			ID:        "ev1",
			TurnID:    "turn_crash",
			Tool:      "create_playlist",
			Operation: "POST /api/playlists",
			Status:    "running",
			Detail:    "正在执行…",
		}},
		PendingWrite: &PendingWrite{
			TurnID:         "turn_crash",
			ToolCallID:     "call_crash",
			ToolName:       "create_playlist",
			Operation:      "POST /api/playlists",
			IdempotencyKey: "ai:crash:turn_crash:call_crash",
			State:          "started",
		},
		ResumeTurn: &TurnRequest{Message: "建歌单"},
	}
	if err := svc.saveSession(ctx, &crashed); err != nil {
		t.Fatalf("save crashed session: %v", err)
	}

	// Recovery without reconciler proof must mark operation_unknown / needs_decision and NOT replay.
	if err := svc.RecoverOnStartup(ctx); err != nil {
		t.Fatalf("RecoverOnStartup: %v", err)
	}
	recovered, err := svc.GetSession(ctx, crashed.ID)
	if err != nil {
		t.Fatalf("GetSession recovered: %v", err)
	}
	if recovered.Status != StatusNeedsDecision || recovered.Events[0].Status != "operation_unknown" {
		t.Fatalf("expected needs_decision + operation_unknown, got %+v", recovered)
	}
	if replayCalls.Load() != 0 {
		t.Fatalf("recovery must not replay uncertain writes, got %d calls", replayCalls.Load())
	}
	if _, err := svc.Resume(ctx, auth.WebAdminActor("admin"), crashed.ID); err == nil {
		t.Fatalf("expected Resume to reject unresolved unknown write")
	}
}

func TestSafeUserErrorAndRedactEmbeddedURLs(t *testing.T) {
	timeoutErr := model.NewError(model.ErrDeadline, http.StatusGatewayTimeout, "上游请求超时")
	if got := safeUserError(timeoutErr); got != "上游请求超时" {
		t.Fatalf("expected Chinese text preserved without percent-encoding, got %q", got)
	}

	colonErr := model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "上游错误：连接失败")
	if got := safeUserError(colonErr); got != "上游错误：连接失败" {
		t.Fatalf("expected Chinese text with colon preserved, got %q", got)
	}

	embeddedErr := model.NewError(
		model.ErrTransport,
		http.StatusBadGateway,
		"请求 https://user:secretpass@api.example.com/v1/chat?api_key=sk-secret123&ok=1 失败（请检查 https://proxy:pw@127.0.0.1:7890?token=tok999）。",
	)
	got := safeUserError(embeddedErr)
	if strings.Contains(got, "secretpass") || strings.Contains(got, "sk-secret123") || strings.Contains(got, "pw@") || strings.Contains(got, "tok999") {
		t.Fatalf("expected embedded URL credentials redacted, got %q", got)
	}
	if !strings.HasPrefix(got, "请求 https://user:%2A%2A%2A@api.example.com/v1/chat?") || !strings.Contains(got, "ok=1") || !strings.Contains(got, "失败（请检查 https://proxy:%2A%2A%2A@127.0.0.1:7890?") {
		t.Fatalf("expected surrounding Chinese text and non-secret query preserved, got %q", got)
	}

	if generic := safeUserError(errors.New("internal raw error")); generic != "项目操作失败，请检查运行日志和服务状态" {
		t.Fatalf("unexpected generic error message: %q", generic)
	}
}

func TestClientTimeoutPreservesCauseAndStreamTimeouts(t *testing.T) {
	for _, stream := range []bool{false, true} {
		name := "non_stream"
		if stream {
			name = "stream"
		}
		t.Run(name, func(t *testing.T) {
			unblock := make(chan struct{})
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if stream {
					w.Header().Set("Content-Type", "text/event-stream")
					w.WriteHeader(http.StatusOK)
					if f, ok := w.(http.Flusher); ok {
						f.Flush()
					}
				}
				select {
				case <-r.Context().Done():
				case <-unblock:
				}
			}))
			defer srv.Close()
			defer close(unblock)

			pool := transport.NewPool(transport.WithAllowTestLoopback(true))
			defer pool.CloseAll()
			client := NewClient(pool, config.DefaultResourceBudgets())
			ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
			defer cancel()

			_, err := client.Complete(
				ctx,
				Settings{
					Enabled:   true,
					BaseURL:   srv.URL + "/v1",
					Model:     "gpt-slow",
					APIKey:    "sk-timeout-secret",
					APIType:   "chat_completions",
					ProxyMode: "direct",
					Stream:    stream,
				},
				"",
				"rev-1",
				[]LLMMessage{{Role: "user", Content: "hello"}},
				nil,
				"",
				func(string) {},
			)
			de, ok := model.AsDomainError(err)
			if !ok || de.Code != model.ErrDeadline {
				t.Fatalf("expected ErrDeadline, got %v", err)
			}
			if de.Detail != aiDeadlineMessage {
				t.Fatalf("unexpected deadline detail: %q", de.Detail)
			}
			if de.Cause == nil || !errors.Is(err, context.DeadlineExceeded) {
				t.Fatalf("expected underlying DeadlineExceeded cause preserved, got cause=%v err=%v", de.Cause, err)
			}
		})
	}
}

type capturedAgentLog struct {
	level    string
	message  string
	category string
	taskID   string
}

func TestRunTurnLogsModelFailureToolFailureCompletionAndNoPlaintextKey(t *testing.T) {
	const secretAPIKey = "sk-live-super-secret-key-98765"

	var mu sync.Mutex
	var logs []capturedAgentLog
	logger := func(level, message, category, taskID string) {
		mu.Lock()
		defer mu.Unlock()
		logs = append(logs, capturedAgentLog{
			level:    level,
			message:  message,
			category: category,
			taskID:   taskID,
		})
	}
	snapshotLogs := func() []capturedAgentLog {
		mu.Lock()
		defer mu.Unlock()
		out := make([]capturedAgentLog, len(logs))
		copy(out, logs)
		return out
	}

	// 1. Model request failure emits category="ai", level="error", and never leaks APIKey.
	failSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = fmt.Fprint(w, `{"error":"upstream failed"}`)
	}))
	defer failSrv.Close()

	st, reg, pool := openTestEnv(t)
	svc := NewService(st, reg, NewClient(pool, config.DefaultResourceBudgets()), Options{
		Logger: logger,
	})
	defer svc.Close()

	ctx := context.Background()
	_, err := svc.PutSettings(ctx, Settings{
		Enabled: true,
		BaseURL: failSrv.URL + "/v1",
		Model:   "gpt-fail-test",
		APIKey:  secretAPIKey,
		APIType: "chat_completions",
		Stream:  true,
	})
	if err != nil {
		t.Fatalf("PutSettings: %v", err)
	}
	sessFail, err := svc.CreateSession(ctx)
	if err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	if _, err := svc.StartTurn(ctx, auth.WebAdminActor("admin"), sessFail.ID, TurnRequest{Message: "测试报错日志"}); err != nil {
		t.Fatalf("StartTurn fail: %v", err)
	}
	doneFail := waitForTerminalSession(t, svc, sessFail.ID, 5*time.Second)
	if doneFail.Status != StatusFailed {
		t.Fatalf("expected StatusFailed, got %s", doneFail.Status)
	}
	if strings.Contains(doneFail.Detail, "%E4") || strings.Contains(doneFail.Detail, secretAPIKey) {
		t.Fatalf("unexpected detail: %q", doneFail.Detail)
	}

	foundModelErr := false
	for _, entry := range snapshotLogs() {
		if strings.Contains(entry.message, secretAPIKey) {
			t.Fatalf("plaintext API key leaked into log: %+v", entry)
		}
		if entry.category == "ai" && entry.level == "error" && entry.taskID == sessFail.ID {
			if strings.Contains(entry.message, "gpt-fail-test") &&
				strings.Contains(entry.message, "chat_completions") &&
				strings.Contains(entry.message, "stream=true") &&
				strings.Contains(entry.message, "steps=0") {
				foundModelErr = true
			}
		}
	}
	if !foundModelErr {
		t.Fatalf("expected ai error log with model metadata, got %+v", snapshotLogs())
	}

	// 2. Tool failure emits warning and completion emits info.
	var step atomic.Int32
	okSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if step.Add(1) == 1 {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"choices": []map[string]any{{
					"message": map[string]any{
						"tool_calls": []map[string]any{{
							"id":   "call_bad_tool",
							"type": "function",
							"function": map[string]any{
								"name":      "read_playlist",
								"arguments": `{"playlist_id":""}`,
							},
						}},
					},
				}},
			})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{{
				"message": map[string]any{"content": "处理完毕"},
			}},
		})
	}))
	defer okSrv.Close()

	_, _ = svc.PutSettings(ctx, Settings{
		Enabled: true,
		BaseURL: okSrv.URL + "/v1",
		Model:   "gpt-ok-test",
		APIKey:  secretAPIKey,
		APIType: "chat_completions",
		Stream:  false,
	})
	sessOK, _ := svc.CreateSession(ctx)
	if _, err := svc.StartTurn(ctx, auth.WebAdminActor("admin"), sessOK.ID, TurnRequest{Message: "测试工具日志"}); err != nil {
		t.Fatalf("StartTurn ok: %v", err)
	}
	doneOK := waitForTerminalSession(t, svc, sessOK.ID, 5*time.Second)
	if doneOK.Status != StatusComplete {
		t.Fatalf("expected StatusComplete, got %s (%s)", doneOK.Status, doneOK.Detail)
	}

	foundToolWarn := false
	foundDoneInfo := false
	for _, entry := range snapshotLogs() {
		if strings.Contains(entry.message, secretAPIKey) {
			t.Fatalf("plaintext API key leaked into log: %+v", entry)
		}
		if entry.taskID == sessOK.ID && entry.category == "ai" {
			if entry.level == "warning" && strings.Contains(entry.message, "read_playlist") {
				foundToolWarn = true
			}
			if entry.level == "info" && strings.Contains(entry.message, "1") {
				foundDoneInfo = true
			}
		}
	}
	if !foundToolWarn || !foundDoneInfo {
		t.Fatalf("expected tool warning and completion info logs, got %+v", snapshotLogs())
	}

	// 3. Cancellation / Stop emits warning log.
	started := make(chan struct{})
	var startOnce sync.Once
	stopUnblock := make(chan struct{})
	stopSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		startOnce.Do(func() { close(started) })
		select {
		case <-r.Context().Done():
		case <-stopUnblock:
		}
	}))
	defer stopSrv.Close()
	defer close(stopUnblock)

	_, _ = svc.PutSettings(ctx, Settings{
		Enabled: true,
		BaseURL: stopSrv.URL + "/v1",
		Model:   "gpt-stop-test",
		APIKey:  secretAPIKey,
		APIType: "chat_completions",
		Stream:  false,
	})
	sessStop, _ := svc.CreateSession(ctx)
	if _, err := svc.StartTurn(ctx, auth.WebAdminActor("admin"), sessStop.ID, TurnRequest{Message: "测试中断"}); err != nil {
		t.Fatalf("StartTurn stop: %v", err)
	}
	<-started
	stopped, err := svc.Stop(ctx, sessStop.ID)
	if err != nil || stopped.Status != StatusInterrupted {
		t.Fatalf("expected StatusInterrupted after Stop, got status=%s err=%v", stopped.Status, err)
	}
	foundStopWarn := false
	for _, entry := range snapshotLogs() {
		if entry.taskID == sessStop.ID && entry.category == "ai" && entry.level == "warning" {
			foundStopWarn = true
		}
	}
	if !foundStopWarn {
		t.Fatalf("expected warning log when turn interrupted, got %+v", snapshotLogs())
	}
}
