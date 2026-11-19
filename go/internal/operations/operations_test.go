package operations_test

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
)

func TestCoreSpecsMatchContractsV1Operations(t *testing.T) {
	reg := operations.NewRegistry(nil)
	if err := operations.RegisterCoreSpecs(reg); err != nil {
		t.Fatal(err)
	}

	path := filepath.Join("..", "..", "..", "contracts", "v1", "operations.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read operations.json: %v", err)
	}
	var contract struct {
		Count    int `json:"count"`
		Discover struct {
			Operations []operations.DiscoverEntry `json:"operations"`
		} `json:"discover"`
	}
	if err := json.Unmarshal(raw, &contract); err != nil {
		t.Fatal(err)
	}

	got := reg.Discover()
	if len(got) != contract.Count || len(got) != len(contract.Discover.Operations) {
		t.Fatalf("operation count mismatch: got=%d want=%d", len(got), contract.Count)
	}
	wantMap := make(map[string]operations.DiscoverEntry, len(contract.Discover.Operations))
	for _, op := range contract.Discover.Operations {
		wantMap[op.Operation] = op
	}
	for _, g := range got {
		w, ok := wantMap[g.Operation]
		if !ok {
			t.Fatalf("unexpected operation in registry: %s", g.Operation)
		}
		if g.Write != w.Write {
			t.Fatalf("write flag mismatch for %s: got=%v want=%v", g.Operation, g.Write, w.Write)
		}
	}
}

func TestDispatchPermissionsForceNewIdempotencyAndRedaction(t *testing.T) {
	reg := operations.NewRegistry(nil)
	if err := operations.RegisterCoreSpecs(reg); err != nil {
		t.Fatal(err)
	}
	calls := 0
	if err := reg.RegisterHandler("POST /api/downloads", func(_ context.Context, req operations.Request) (any, error) {
		calls++
		return map[string]any{
			"id":     "dl-1",
			"cookie": "secret-cookie",
			"proxy":  "http://user:pass@127.0.0.1:7890?token=xyz",
		}, nil
	}); err != nil {
		t.Fatal(err)
	}

	// Read-only AI cannot execute write operation -> 403
	aiRO := auth.ChannelActor("ai", "u1", auth.PermRead, auth.PermLocalWrite)
	aiRO.ReadOnlyAI = true
	if _, err := reg.Dispatch(context.Background(), operations.Request{
		Operation: "POST /api/downloads",
		Actor:     aiRO,
	}); err == nil {
		t.Fatal("expected 403 for read-only AI write")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != 403 {
		t.Fatalf("expected 403, got %d", de.HTTPStatus())
	}

	admin := auth.WebAdminActor("admin")
	// force_new without intent_nonce -> 400
	if _, err := reg.Dispatch(context.Background(), operations.Request{
		Operation: "POST /api/downloads",
		Actor:     admin,
		ForceNew:  true,
	}); err == nil {
		t.Fatal("expected 400 when force_new lacks intent_nonce")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != 400 {
		t.Fatalf("expected 400, got %d", de.HTTPStatus())
	}

	// Same idempotency key + same payload replays cached redacted result without re-running handler
	res1, err := reg.Dispatch(context.Background(), operations.Request{
		Operation:      "POST /api/downloads",
		Actor:          admin,
		IdempotencyKey: "req-1",
		Payload:        map[string]any{"track_id": "t1", "quality": "flac"},
	})
	if err != nil {
		t.Fatal(err)
	}
	m1 := res1.(map[string]any)
	if m1["cookie"] != "[已隐藏]" || m1["proxy"] != "http://user:%2A%2A%2A@127.0.0.1:7890?token=%2A%2A%2A" {
		t.Fatalf("expected redacted output, got %v", m1)
	}
	res2, err := reg.Dispatch(context.Background(), operations.Request{
		Operation:      "POST /api/downloads",
		Actor:          admin,
		IdempotencyKey: "req-1",
		Payload:        map[string]any{"quality": "flac", "track_id": "t1"},
	})
	if err != nil || calls != 1 || res2.(map[string]any)["id"] != "dl-1" {
		t.Fatalf("expected idempotent replay with calls=1, got calls=%d res=%v err=%v", calls, res2, err)
	}

	// Same idempotency key + different payload -> 409
	if _, err := reg.Dispatch(context.Background(), operations.Request{
		Operation:      "POST /api/downloads",
		Actor:          admin,
		IdempotencyKey: "req-1",
		Payload:        map[string]any{"track_id": "t2", "quality": "flac"},
	}); err == nil {
		t.Fatal("expected 409 on idempotency key payload mismatch")
	} else if de, _ := model.AsDomainError(err); de.HTTPStatus() != 409 {
		t.Fatalf("expected 409, got %d", de.HTTPStatus())
	}
}
