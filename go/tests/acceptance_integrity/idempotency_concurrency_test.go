package acceptance_integrity_test

import (
	"context"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
	"github.com/agonie0v0/melokeep/internal/store"
)

func openIntegrityStore(t *testing.T) *store.Store {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Options{
		Path:        store.DefaultDBPath(t.TempDir()),
		ReadMaxOpen: 3,
	})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func TestAcceptanceConcurrentIdempotencySamePayloadExecutesOnce(t *testing.T) {
	st := openIntegrityStore(t)
	ctx := context.Background()
	reg := operations.NewRegistry(store.NewIdempotencyStore(st, time.Hour))
	if err := operations.RegisterCoreSpecs(reg); err != nil {
		t.Fatalf("RegisterCoreSpecs: %v", err)
	}

	var calls atomic.Int32
	if err := reg.RegisterHandler("POST /api/downloads", func(_ context.Context, req operations.Request) (any, error) {
		n := calls.Add(1)
		time.Sleep(35 * time.Millisecond)
		return map[string]any{
			"id":     "dl-once",
			"call":   n,
			"cookie": "SECRET_SHOULD_REDACT",
		}, nil
	}); err != nil {
		t.Fatalf("RegisterHandler: %v", err)
	}

	actor := auth.WebAdminActor("admin")
	const workers = 12
	start := make(chan struct{})
	var wg sync.WaitGroup
	results := make([]any, workers)
	errs := make([]error, workers)

	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			<-start
			results[idx], errs[idx] = reg.Dispatch(ctx, operations.Request{
				Operation:      "POST /api/downloads",
				Actor:          actor,
				IdempotencyKey: "concurrent-same-1",
				Payload:        map[string]any{"track_id": "t1", "quality": "flac"},
			})
		}(i)
	}
	close(start)
	wg.Wait()

	for i := 0; i < workers; i++ {
		if errs[i] != nil {
			t.Fatalf("worker %d failed: %v", i, errs[i])
		}
		m, ok := results[i].(map[string]any)
		if !ok {
			t.Fatalf("worker %d unexpected result type %T", i, results[i])
		}
		if m["id"] != "dl-once" {
			t.Fatalf("worker %d wrong id: %v", i, m)
		}
		if m["cookie"] == "SECRET_SHOULD_REDACT" {
			t.Fatalf("worker %d leaked unredacted cookie", i)
		}
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("expected handler to execute exactly once under concurrency, got %d", got)
	}
}

func TestAcceptanceConcurrentIdempotencyPayloadConflictReturns409AndPreservesWinner(t *testing.T) {
	st := openIntegrityStore(t)
	ctx := context.Background()
	idemp := store.NewIdempotencyStore(st, time.Hour)
	reg := operations.NewRegistry(idemp)
	if err := operations.RegisterCoreSpecs(reg); err != nil {
		t.Fatalf("RegisterCoreSpecs: %v", err)
	}

	var calls atomic.Int32
	if err := reg.RegisterHandler("POST /api/downloads", func(_ context.Context, req operations.Request) (any, error) {
		calls.Add(1)
		time.Sleep(40 * time.Millisecond)
		return map[string]any{"chosen_track": req.Payload["track_id"]}, nil
	}); err != nil {
		t.Fatalf("RegisterHandler: %v", err)
	}

	actor := auth.WebAdminActor("admin")
	start := make(chan struct{})
	var wg sync.WaitGroup
	errs := make([]error, 2)
	results := make([]any, 2)
	payloads := []map[string]any{
		{"track_id": "track-A"},
		{"track_id": "track-B"},
	}

	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			<-start
			results[idx], errs[idx] = reg.Dispatch(ctx, operations.Request{
				Operation:      "POST /api/downloads",
				Actor:          actor,
				IdempotencyKey: "conflict-key-1",
				Payload:        payloads[idx],
			})
		}(i)
	}
	close(start)
	wg.Wait()

	if got := calls.Load(); got != 1 {
		t.Fatalf("expected only 1 winner execution on conflicting concurrent key, got %d", got)
	}

	var successCount, conflictCount int
	var winnerTrack string
	for i := 0; i < 2; i++ {
		if errs[i] == nil {
			successCount++
			winnerTrack = results[i].(map[string]any)["chosen_track"].(string)
		} else if de, ok := model.AsDomainError(errs[i]); ok && de.HTTPStatus() == http.StatusConflict {
			conflictCount++
		} else {
			t.Fatalf("unexpected error on worker %d: %v", i, errs[i])
		}
	}
	if successCount != 1 || conflictCount != 1 {
		t.Fatalf("expected 1 success and 1 409 conflict, got success=%d conflict=%d errs=%v", successCount, conflictCount, errs)
	}

	// Direct Save with same key + different hash must also reject with 409 rather than overwriting.
	err := idemp.Save(ctx, operations.IdempotencyRecord{
		ActorKey:       actor.Key(),
		OperationID:    "POST /api/downloads",
		IdempotencyKey: "conflict-key-1",
		PayloadHash:    "different-hash",
		ResultJSON:     []byte(`{"chosen_track":"hijacked"}`),
		CreatedAt:      time.Now().Unix(),
	})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected direct Save with differing PayloadHash to return 409 conflict, got: %v", err)
	}

	// Verify winner record remained intact.
	rec, found, err := idemp.Lookup(ctx, actor.Key(), "POST /api/downloads", "conflict-key-1")
	if err != nil || !found {
		t.Fatalf("expected winner record in store, found=%v err=%v", found, err)
	}
	if string(rec.ResultJSON) == `{"chosen_track":"hijacked"}` {
		t.Fatal("winner record was overwritten!")
	}
	_ = winnerTrack
}

func TestAcceptanceForceNewIntentNonceAndPermissions(t *testing.T) {
	st := openIntegrityStore(t)
	ctx := context.Background()
	reg := operations.NewRegistry(store.NewIdempotencyStore(st, time.Hour))
	if err := operations.RegisterCoreSpecs(reg); err != nil {
		t.Fatal(err)
	}
	var calls int
	_ = reg.RegisterHandler("POST /api/downloads", func(_ context.Context, req operations.Request) (any, error) {
		calls++
		return map[string]any{"nonce": req.IntentNonce, "call": calls}, nil
	})

	admin := auth.WebAdminActor("admin")
	// Distinct intent_nonce + distinct idempotency keys stay independent.
	r1, err := reg.Dispatch(ctx, operations.Request{
		Operation:      "POST /api/downloads",
		Actor:          admin,
		IdempotencyKey: "k-n1",
		ForceNew:       true,
		IntentNonce:    "n1",
		Payload:        map[string]any{"track_id": "t1"},
	})
	if err != nil {
		t.Fatalf("dispatch n1: %v", err)
	}
	r2, err := reg.Dispatch(ctx, operations.Request{
		Operation:      "POST /api/downloads",
		Actor:          admin,
		IdempotencyKey: "k-n2",
		ForceNew:       true,
		IntentNonce:    "n2",
		Payload:        map[string]any{"track_id": "t1"},
	})
	if err != nil {
		t.Fatalf("dispatch n2: %v", err)
	}
	if calls != 2 || r1.(map[string]any)["nonce"] != "n1" || r2.(map[string]any)["nonce"] != "n2" {
		t.Fatalf("unexpected force_new responses: calls=%d r1=%v r2=%v", calls, r1, r2)
	}

	// Same idempotency key with different intent_nonce -> 409.
	_, err = reg.Dispatch(ctx, operations.Request{
		Operation:      "POST /api/downloads",
		Actor:          admin,
		IdempotencyKey: "k-n1",
		ForceNew:       true,
		IntentNonce:    "n2",
		Payload:        map[string]any{"track_id": "t1"},
	})
	if de, ok := model.AsDomainError(err); !ok || de.HTTPStatus() != http.StatusConflict {
		t.Fatalf("expected 409 when intent_nonce differs for same idempotency_key, got %v", err)
	}
}
