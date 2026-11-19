package operations

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

type Effect string

const (
	EffectRead          Effect = "read"
	EffectLocalWrite    Effect = "local_write"
	EffectExternalWrite Effect = "external_write"
	EffectMaintenance   Effect = "maintenance"
	EffectAdmin         Effect = "admin"
)

type IdempotencyPolicy string

const (
	IdempotencyRequired      IdempotencyPolicy = "required"
	IdempotencyOptional      IdempotencyPolicy = "optional"
	IdempotencyNotApplicable IdempotencyPolicy = "not_applicable"
)

// Spec describes a discoverable operation contract. Handlers are registered separately
// by domain or cmd wiring so operations never imports search/download/library/navidrome.
type Spec struct {
	ID                 string            `json:"id"`
	Alias              string            `json:"operation"` // METHOD /path
	Method             string            `json:"method"`
	Path               string            `json:"path"`
	Description        string            `json:"description"`
	Write              bool              `json:"write"`
	Effect             Effect            `json:"effect"`
	RequiredPermission auth.Permission   `json:"required_permission"`
	Idempotency        IdempotencyPolicy `json:"idempotency"`
	SensitiveFields    []string          `json:"sensitive_fields,omitempty"`
	InputSchema        map[string]any    `json:"input_schema,omitempty"`
	OutputSchema       map[string]any    `json:"output_schema,omitempty"`
}

type DiscoverEntry struct {
	Operation   string `json:"operation"`
	Description string `json:"description"`
	Write       bool   `json:"write"`
}

type Request struct {
	Operation      string
	Actor          auth.Actor
	RequestID      string
	IdempotencyKey string
	ForceNew       bool
	IntentNonce    string
	Payload        map[string]any
}

type Handler func(ctx context.Context, req Request) (any, error)

type IdempotencyRecord struct {
	ActorKey       string
	OperationID    string
	IdempotencyKey string
	PayloadHash    string
	ResultJSON     []byte
	CreatedAt      int64
}

type IdempotencyStore interface {
	Lookup(ctx context.Context, actorKey, operationID, key string) (IdempotencyRecord, bool, error)
	Save(ctx context.Context, rec IdempotencyRecord) error
}

type MemoryIdempotencyStore struct {
	mu      sync.Mutex
	records map[string]IdempotencyRecord
}

func NewMemoryIdempotencyStore() *MemoryIdempotencyStore {
	return &MemoryIdempotencyStore{records: make(map[string]IdempotencyRecord)}
}

func (s *MemoryIdempotencyStore) Lookup(_ context.Context, actorKey, operationID, key string) (IdempotencyRecord, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec, ok := s.records[actorKey+"\x00"+operationID+"\x00"+key]
	return rec, ok, nil
}

func (s *MemoryIdempotencyStore) Save(_ context.Context, rec IdempotencyRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	k := rec.ActorKey + "\x00" + rec.OperationID + "\x00" + rec.IdempotencyKey
	if existing, ok := s.records[k]; ok {
		if existing.PayloadHash != rec.PayloadHash {
			return model.NewError(model.ErrConflict, http.StatusConflict, "幂等键冲突：请求参数与此前提交不一致")
		}
		return nil
	}
	s.records[k] = rec
	return nil
}

type Registry struct {
	mu          sync.RWMutex
	specs       map[string]Spec
	aliases     map[string]string
	order       []string
	handlers    map[string]Handler
	idempotency IdempotencyStore
	flightMu    sync.Mutex
	flights     map[string]chan struct{}
}

func NewRegistry(store IdempotencyStore) *Registry {
	if store == nil {
		store = NewMemoryIdempotencyStore()
	}
	return &Registry{
		specs:       make(map[string]Spec),
		aliases:     make(map[string]string),
		handlers:    make(map[string]Handler),
		idempotency: store,
		flights:     make(map[string]chan struct{}),
	}
}

func (r *Registry) acquireFlight(ctx context.Context, key string) (func(), error) {
	for {
		r.flightMu.Lock()
		ch, inFlight := r.flights[key]
		if !inFlight {
			ch = make(chan struct{})
			r.flights[key] = ch
			r.flightMu.Unlock()
			return func() {
				r.flightMu.Lock()
				delete(r.flights, key)
				close(ch)
				r.flightMu.Unlock()
			}, nil
		}
		r.flightMu.Unlock()
		select {
		case <-ch:
		case <-ctx.Done():
			return nil, model.WrapError(model.ErrCancelled, 499, "等待幂等操作锁已取消", ctx.Err())
		}
	}
}

func (r *Registry) RegisterSpec(spec Spec) error {
	if spec.ID == "" {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "operation id 不能为空")
	}
	if spec.Alias == "" && spec.Method != "" && spec.Path != "" {
		spec.Alias = strings.ToUpper(spec.Method) + " " + spec.Path
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.specs[spec.ID]; !exists {
		r.order = append(r.order, spec.ID)
	}
	r.specs[spec.ID] = spec
	if spec.Alias != "" {
		r.aliases[spec.Alias] = spec.ID
	}
	return nil
}

func (r *Registry) RegisterHandler(op string, fn Handler) error {
	if fn == nil {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "operation handler 不能为空")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	id := r.resolveIDLocked(op)
	if id == "" {
		return model.NewError(model.ErrNotFound, http.StatusNotFound, "未注册的操作")
	}
	r.handlers[id] = fn
	return nil
}

func (r *Registry) resolveIDLocked(op string) string {
	if _, ok := r.specs[op]; ok {
		return op
	}
	if id, ok := r.aliases[op]; ok {
		return id
	}
	return ""
}

func (r *Registry) Resolve(op string) (Spec, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	id := r.resolveIDLocked(op)
	if id == "" {
		return Spec{}, false
	}
	spec, ok := r.specs[id]
	return spec, ok
}

// ListSpecs returns a deterministic snapshot of all registered operation specs.
func (r *Registry) ListSpecs() []Spec {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Spec, 0, len(r.order))
	for _, id := range r.order {
		if s, ok := r.specs[id]; ok {
			out = append(out, s)
		}
	}
	return out
}

// HasHandler reports whether a concrete handler is registered for op.
func (r *Registry) HasHandler(op string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	id := r.resolveIDLocked(op)
	if id == "" {
		return false
	}
	return r.handlers[id] != nil
}

func (r *Registry) Discover() []DiscoverEntry {
	r.mu.RLock()
	defer r.mu.RUnlock()
	entries := make([]DiscoverEntry, 0, len(r.order))
	for _, id := range r.order {
		s := r.specs[id]
		opName := s.Alias
		if opName == "" {
			opName = s.ID
		}
		entries = append(entries, DiscoverEntry{
			Operation:   opName,
			Description: s.Description,
			Write:       s.Write,
		})
	}
	return entries
}

// Dispatch validates actor authorization, read-only AI guard, force_new intent_nonce,
// canonical payload idempotency, and redacts sensitive fields from handler output.
func (r *Registry) Dispatch(ctx context.Context, req Request) (any, error) {
	spec, ok := r.Resolve(req.Operation)
	if !ok {
		return nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "操作不存在")
	}
	if !req.Actor.Authenticated {
		return nil, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if req.Actor.ReadOnlyAI && spec.Write {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "当前为只读模式，禁止执行写操作")
	}
	perm := spec.RequiredPermission
	if perm == "" {
		perm = defaultPermissionForEffect(spec.Effect)
	}
	if !req.Actor.Has(perm) {
		return nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "当前账号缺少执行该操作的权限")
	}
	if req.ForceNew && strings.TrimSpace(req.IntentNonce) == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "force_new 为 true 时必须提供非空 intent_nonce")
	}
	if spec.Idempotency == IdempotencyRequired && strings.TrimSpace(req.IdempotencyKey) == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少幂等键 idempotency_key")
	}

	var payloadHash string
	useIdempotency := spec.Idempotency != IdempotencyNotApplicable && strings.TrimSpace(req.IdempotencyKey) != ""
	if useIdempotency {
		flightKey := req.Actor.Key() + "\x00" + spec.ID + "\x00" + req.IdempotencyKey
		releaseFlight, fErr := r.acquireFlight(ctx, flightKey)
		if fErr != nil {
			return nil, fErr
		}
		defer releaseFlight()

		payloadHash = CanonicalPayloadHash(spec.ID, req.ForceNew, req.IntentNonce, req.Payload)
		rec, found, err := r.idempotency.Lookup(ctx, req.Actor.Key(), spec.ID, req.IdempotencyKey)
		if err != nil {
			return nil, err
		}
		if found {
			if rec.PayloadHash != payloadHash {
				return nil, model.NewError(model.ErrConflict, http.StatusConflict, "幂等键冲突：请求参数与此前提交不一致")
			}
			var cached any
			if err := json.Unmarshal(rec.ResultJSON, &cached); err != nil {
				return nil, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "幂等结果反序列化失败", err)
			}
			return cached, nil
		}
	}

	r.mu.RLock()
	fn := r.handlers[spec.ID]
	r.mu.RUnlock()
	if fn == nil {
		return nil, model.NewError(model.ErrUnsupported, http.StatusNotImplemented, "操作处理程序未注册")
	}

	res, err := fn(ctx, req)
	if err != nil {
		return nil, err
	}
	redacted := RedactOutput(res, spec.SensitiveFields)
	if useIdempotency {
		encoded, err := json.Marshal(redacted)
		if err != nil {
			return nil, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "幂等结果序列化失败", err)
		}
		if saveErr := r.idempotency.Save(ctx, IdempotencyRecord{
			ActorKey:       req.Actor.Key(),
			OperationID:    spec.ID,
			IdempotencyKey: req.IdempotencyKey,
			PayloadHash:    payloadHash,
			ResultJSON:     encoded,
			CreatedAt:      time.Now().Unix(),
		}); saveErr != nil {
			return nil, saveErr
		}
	}
	return redacted, nil
}

func defaultPermissionForEffect(effect Effect) auth.Permission {
	switch effect {
	case EffectRead:
		return auth.PermRead
	case EffectLocalWrite:
		return auth.PermLocalWrite
	case EffectExternalWrite:
		return auth.PermExternalWrite
	case EffectMaintenance:
		return auth.PermMaintenance
	default:
		return auth.PermAdmin
	}
}

// CanonicalPayloadHash produces a deterministic SHA-256 hex digest regardless of map key order.
func CanonicalPayloadHash(opID string, forceNew bool, intentNonce string, payload map[string]any) string {
	canon := canonicalize(map[string]any{
		"op":           opID,
		"force_new":    forceNew,
		"intent_nonce": intentNonce,
		"payload":      payload,
	})
	raw, _ := json.Marshal(canon)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func canonicalize(v any) any {
	switch x := v.(type) {
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		out := make([]any, 0, len(keys)*2)
		for _, k := range keys {
			out = append(out, k, canonicalize(x[k]))
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, item := range x {
			out[i] = canonicalize(item)
		}
		return out
	default:
		return v
	}
}

// RedactOutput masks sensitive fields and URLs before returning operation output to callers/AI.
func RedactOutput(v any, sensitiveFields []string) any {
	sens := make(map[string]bool, len(sensitiveFields)+6)
	for _, f := range sensitiveFields {
		sens[strings.ToLower(f)] = true
	}
	for _, f := range []string{"cookie", "cookies", "password", "token", "api_key", "secret"} {
		sens[f] = true
	}
	return redactAny(v, sens)
}

func redactAny(v any, sens map[string]bool) any {
	switch x := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(x))
		for k, val := range x {
			lk := strings.ToLower(k)
			if sens[lk] {
				if str, ok := val.(string); ok {
					out[k] = config.RedactValue(str)
				} else if val != nil {
					out[k] = config.HiddenPlaceholder
				} else {
					out[k] = nil
				}
				continue
			}
			if lk == "proxy" || lk == "proxy_url" || lk == "url" {
				if str, ok := val.(string); ok {
					out[k] = config.RedactURL(str)
					continue
				}
			}
			out[k] = redactAny(val, sens)
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, item := range x {
			out[i] = redactAny(item, sens)
		}
		return out
	default:
		return v
	}
}
