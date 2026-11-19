package provider

import (
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

// Metadata describes static UI/inventory metadata for a registered Provider.
type Metadata struct {
	Label            string
	Category         string
	Origin           string
	PluginID         string
	Version          string
	Description      string
	ProxyRecommended bool
}

// Entry pairs a Provider implementation with its display metadata.
type Entry struct {
	Provider model.Provider
	Meta     Metadata
}

// Registry is a thread-safe provider catalog separating built-in sources from dynamic plugins.
type Registry struct {
	mu             sync.RWMutex
	builtinOrdered []Entry
	pluginOrdered  []Entry
	builtinByKey   map[string]Entry
	byKey          map[string]Entry
}

// NewRegistry builds a Registry seeded with built-in provider entries.
func NewRegistry(entries []Entry) (*Registry, error) {
	r := &Registry{
		builtinOrdered: make([]Entry, 0, len(entries)),
		pluginOrdered:  make([]Entry, 0),
		builtinByKey:   make(map[string]Entry, len(entries)*3),
		byKey:          make(map[string]Entry, len(entries)*3),
	}
	for _, e := range entries {
		if e.Provider == nil {
			continue
		}
		if strings.TrimSpace(e.Meta.Origin) == "" {
			e.Meta.Origin = "builtin"
		}
		keys, err := entryLookupKeys(e.Provider)
		if err != nil {
			return nil, err
		}
		for _, k := range keys {
			if _, exists := r.builtinByKey[k]; exists {
				return nil, fmt.Errorf("duplicate provider registration: %s", e.Provider.EngineName())
			}
		}
		r.builtinOrdered = append(r.builtinOrdered, e)
		for _, k := range keys {
			r.builtinByKey[k] = e
			r.byKey[k] = e
		}
	}
	return r, nil
}

func entryLookupKeys(p model.Provider) ([]string, error) {
	if p == nil {
		return nil, fmt.Errorf("provider cannot be nil")
	}
	id := strings.TrimSpace(p.ID())
	eng := strings.TrimSpace(p.EngineName())
	if id == "" || eng == "" {
		return nil, fmt.Errorf("provider must declare non-empty ID and EngineName")
	}
	raw := []string{id, eng}
	if canonical := config.CanonicalEngineName(id); canonical != "" {
		raw = append(raw, canonical)
	}
	if pub := config.PublicSourceID(eng); pub != "" {
		raw = append(raw, pub)
	}
	seen := make(map[string]bool, len(raw))
	keys := make([]string, 0, len(raw))
	for _, item := range raw {
		k := strings.ToLower(strings.TrimSpace(item))
		if k != "" && !seen[k] {
			seen[k] = true
			keys = append(keys, k)
		}
	}
	return keys, nil
}

func normalizePluginEntry(entry Entry) (Entry, []string, error) {
	if entry.Provider == nil {
		return Entry{}, nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件 provider 实例不能为空")
	}
	keys, err := entryLookupKeys(entry.Provider)
	if err != nil {
		return Entry{}, nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "插件必须声明非空 ID 与 EngineName", err)
	}
	entry.Meta.Origin = "plugin"
	if strings.TrimSpace(entry.Meta.PluginID) == "" {
		entry.Meta.PluginID = strings.TrimSpace(entry.Provider.ID())
	}
	return entry, keys, nil
}

func (r *Registry) rebuildIndexLocked() {
	next := make(map[string]Entry, len(r.builtinByKey)+len(r.pluginOrdered)*3)
	for k, e := range r.builtinByKey {
		next[k] = e
	}
	for _, e := range r.pluginOrdered {
		keys, err := entryLookupKeys(e.Provider)
		if err != nil {
			continue
		}
		for _, k := range keys {
			next[k] = e
		}
	}
	r.byKey = next
}

func samePluginEntry(existing Entry, id, pluginID string) bool {
	if strings.EqualFold(strings.TrimSpace(existing.Provider.ID()), id) {
		return true
	}
	if pluginID != "" && existing.Meta.PluginID != "" && strings.EqualFold(strings.TrimSpace(existing.Meta.PluginID), pluginID) {
		return true
	}
	return false
}

// RegisterPlugin registers or updates a dynamic plugin provider without allowing overrides of built-in providers.
func (r *Registry) RegisterPlugin(entry Entry) error {
	if r == nil {
		return model.NewError(model.ErrInternal, http.StatusInternalServerError, "provider registry 未初始化")
	}
	normalized, keys, err := normalizePluginEntry(entry)
	if err != nil {
		return err
	}
	id := strings.TrimSpace(normalized.Provider.ID())
	pluginID := strings.TrimSpace(normalized.Meta.PluginID)

	r.mu.Lock()
	defer r.mu.Unlock()

	for _, k := range keys {
		if _, conflict := r.builtinByKey[k]; conflict {
			return model.NewError(model.ErrConflict, http.StatusConflict, fmt.Sprintf("插件源 %q 与内置源冲突", id))
		}
	}

	replaceIdx := -1
	for i, existing := range r.pluginOrdered {
		if samePluginEntry(existing, id, pluginID) {
			replaceIdx = i
			continue
		}
		existingKeys, err := entryLookupKeys(existing.Provider)
		if err != nil {
			continue
		}
		keySet := make(map[string]bool, len(existingKeys))
		for _, ek := range existingKeys {
			keySet[ek] = true
		}
		for _, k := range keys {
			if keySet[k] {
				return model.NewError(model.ErrConflict, http.StatusConflict, fmt.Sprintf("插件源 %q 与已注册插件 %q 冲突", id, existing.Provider.ID()))
			}
		}
	}

	if replaceIdx >= 0 {
		r.pluginOrdered[replaceIdx] = normalized
	} else {
		r.pluginOrdered = append(r.pluginOrdered, normalized)
	}
	r.rebuildIndexLocked()
	return nil
}

// UnregisterPlugin removes a registered plugin provider by ID, EngineName, or PluginID.
func (r *Registry) UnregisterPlugin(id string) {
	if r == nil {
		return
	}
	target := strings.TrimSpace(id)
	if target == "" {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()

	filtered := make([]Entry, 0, len(r.pluginOrdered))
	removed := false
	for _, e := range r.pluginOrdered {
		if strings.EqualFold(strings.TrimSpace(e.Provider.ID()), target) ||
			strings.EqualFold(strings.TrimSpace(e.Provider.EngineName()), target) ||
			(e.Meta.PluginID != "" && strings.EqualFold(strings.TrimSpace(e.Meta.PluginID), target)) {
			removed = true
			continue
		}
		filtered = append(filtered, e)
	}
	if !removed {
		return
	}
	r.pluginOrdered = filtered
	r.rebuildIndexLocked()
}

// ReplacePlugins atomically replaces all dynamic plugin providers while preserving built-in providers.
func (r *Registry) ReplacePlugins(entries []Entry) error {
	if r == nil {
		return model.NewError(model.ErrInternal, http.StatusInternalServerError, "provider registry 未初始化")
	}
	nextPlugins := make([]Entry, 0, len(entries))
	seenKeys := make(map[string]string, len(entries)*2)

	r.mu.Lock()
	defer r.mu.Unlock()

	for _, raw := range entries {
		if raw.Provider == nil {
			continue
		}
		normalized, keys, err := normalizePluginEntry(raw)
		if err != nil {
			return err
		}
		id := strings.TrimSpace(normalized.Provider.ID())
		for _, k := range keys {
			if _, conflict := r.builtinByKey[k]; conflict {
				return model.NewError(model.ErrConflict, http.StatusConflict, fmt.Sprintf("插件源 %q 与内置源冲突", id))
			}
			if otherID, dup := seenKeys[k]; dup {
				return model.NewError(model.ErrConflict, http.StatusConflict, fmt.Sprintf("插件源 %q 与 %q 存在重复标识", id, otherID))
			}
		}
		for _, k := range keys {
			seenKeys[k] = id
		}
		nextPlugins = append(nextPlugins, normalized)
	}

	r.pluginOrdered = nextPlugins
	r.rebuildIndexLocked()
	return nil
}

// HasBuiltin reports whether idOrName matches a built-in provider ID or EngineName.
func (r *Registry) HasBuiltin(id string) bool {
	if r == nil {
		return false
	}
	key := strings.ToLower(strings.TrimSpace(id))
	if key == "" {
		return false
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	if _, ok := r.builtinByKey[key]; ok {
		return true
	}
	if canonical := config.CanonicalEngineName(id); canonical != "" {
		if _, ok := r.builtinByKey[strings.ToLower(canonical)]; ok {
			return true
		}
	}
	return false
}

// Get looks up a Provider by public ID, alias, or engine name.
func (r *Registry) Get(idOrName string) (model.Provider, bool) {
	if r == nil {
		return nil, false
	}
	key := strings.ToLower(strings.TrimSpace(idOrName))
	if key == "" {
		return nil, false
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	if e, ok := r.byKey[key]; ok {
		return e.Provider, true
	}
	if canonical := config.CanonicalEngineName(idOrName); canonical != "" {
		if e, ok := r.byKey[strings.ToLower(canonical)]; ok {
			return e.Provider, true
		}
	}
	return nil, false
}

// List returns all registered built-in and plugin providers in deterministic registration order.
func (r *Registry) List() []model.Provider {
	if r == nil {
		return nil
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]model.Provider, 0, len(r.builtinOrdered)+len(r.pluginOrdered))
	for _, e := range r.builtinOrdered {
		out = append(out, e.Provider)
	}
	for _, e := range r.pluginOrdered {
		out = append(out, e.Provider)
	}
	return out
}

// PluginEntries returns a snapshot copy of currently registered plugin entries.
func (r *Registry) PluginEntries() []Entry {
	if r == nil {
		return nil
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Entry, len(r.pluginOrdered))
	copy(out, r.pluginOrdered)
	return out
}

// AvailableSources returns AvailableSourceDTO entries for all built-in and plugin providers.
func (r *Registry) AvailableSources() []model.AvailableSourceDTO {
	if r == nil {
		return nil
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]model.AvailableSourceDTO, 0, len(r.builtinOrdered)+len(r.pluginOrdered))
	appendEntries := func(entries []Entry, defaultOrigin string) {
		for _, e := range entries {
			cap := e.Provider.Capabilities()
			capDTO := &model.SourceCapabilityDTO{
				Tier:     cap.Tier,
				Note:     cap.Note,
				Search:   cap.Search,
				Playlist: cap.Playlist,
				Lyrics:   cap.Lyrics,
				Metadata: cap.Metadata,
				Episodes: cap.Episodes,
				Partial:  cap.Partial,
			}
			label := e.Meta.Label
			if label == "" {
				label = e.Provider.ID()
			}
			origin := strings.TrimSpace(e.Meta.Origin)
			if origin == "" {
				origin = defaultOrigin
			}
			out = append(out, model.AvailableSourceDTO{
				ID:               e.Provider.ID(),
				Label:            label,
				Category:         e.Meta.Category,
				EngineName:       e.Provider.EngineName(),
				Origin:           origin,
				PluginID:         e.Meta.PluginID,
				Version:          e.Meta.Version,
				Description:      e.Meta.Description,
				ProxyRecommended: e.Meta.ProxyRecommended,
				InitError:        nil,
				Capability:       capDTO,
			})
		}
	}
	appendEntries(r.builtinOrdered, "builtin")
	appendEntries(r.pluginOrdered, "plugin")
	return out
}

// IDs returns all canonical public IDs in sorted order.
func (r *Registry) IDs() []string {
	if r == nil {
		return nil
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]string, 0, len(r.builtinOrdered)+len(r.pluginOrdered))
	for _, e := range r.builtinOrdered {
		out = append(out, e.Provider.ID())
	}
	for _, e := range r.pluginOrdered {
		out = append(out, e.Provider.ID())
	}
	sort.Strings(out)
	return out
}
