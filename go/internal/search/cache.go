package search

import (
	"container/list"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

type flightKey struct {
	ConfigRevision string
	EngineName     string
	Keyword        string
	Limit          int
}

func makeFlightKey(configRevision, engineName, keyword string, limit int) flightKey {
	return flightKey{
		ConfigRevision: configRevision,
		EngineName:     engineName,
		Keyword:        strings.TrimSpace(keyword),
		Limit:          limit,
	}
}

func (k flightKey) digest() string {
	sum := sha256.Sum256([]byte(fmt.Sprintf("%s\x00%s\x00%s\x00%d", k.ConfigRevision, k.EngineName, k.Keyword, k.Limit)))
	return hex.EncodeToString(sum[:16])
}

type cacheEntry struct {
	key       string
	expiresAt time.Time
	sizeBytes int64
	tracks    []model.ProviderTrack
}

type resultCache struct {
	ttl        time.Duration
	maxEntries int
	maxBytes   int64
	usedBytes  int64
	ll         *list.List
	entries    map[string]*list.Element
	now        func() time.Time
}

func newResultCache(ttl time.Duration, maxEntries int, maxBytes int64, nowFn func() time.Time) *resultCache {
	if maxEntries <= 0 {
		maxEntries = 32
	}
	if maxBytes <= 0 {
		maxBytes = 8 << 20
	}
	if nowFn == nil {
		nowFn = time.Now
	}
	return &resultCache{
		ttl:        ttl,
		maxEntries: maxEntries,
		maxBytes:   maxBytes,
		ll:         list.New(),
		entries:    make(map[string]*list.Element),
		now:        nowFn,
	}
}

func (c *resultCache) pruneExpired(now time.Time) {
	for el := c.ll.Back(); el != nil; {
		prev := el.Prev()
		ent := el.Value.(*cacheEntry)
		if !ent.expiresAt.After(now) {
			c.removeElement(el)
		}
		el = prev
	}
}

func (c *resultCache) removeElement(el *list.Element) {
	ent := el.Value.(*cacheEntry)
	c.ll.Remove(el)
	delete(c.entries, ent.key)
	c.usedBytes -= ent.sizeBytes
}

func (c *resultCache) Invalidate(k flightKey) {
	dk := k.digest()
	if el, ok := c.entries[dk]; ok {
		c.removeElement(el)
	}
}

func (c *resultCache) Get(k flightKey) ([]model.ProviderTrack, bool) {
	now := c.now()
	c.pruneExpired(now)
	dk := k.digest()
	el, ok := c.entries[dk]
	if !ok {
		return nil, false
	}
	c.ll.MoveToFront(el)
	ent := el.Value.(*cacheEntry)
	out := make([]model.ProviderTrack, len(ent.tracks))
	copy(out, ent.tracks)
	return out, true
}

func (c *resultCache) Put(k flightKey, tracks []model.ProviderTrack) {
	if c.ttl <= 0 || len(tracks) == 0 {
		return
	}
	raw, err := json.Marshal(tracks)
	if err != nil {
		return
	}
	sz := int64(len(raw))
	if sz > c.maxBytes {
		return
	}
	now := c.now()
	c.pruneExpired(now)
	dk := k.digest()
	if el, ok := c.entries[dk]; ok {
		c.removeElement(el)
	}
	for c.ll.Len() > 0 && (c.usedBytes+sz > c.maxBytes || c.ll.Len() >= c.maxEntries) {
		c.removeElement(c.ll.Back())
	}
	cloned := make([]model.ProviderTrack, len(tracks))
	copy(cloned, tracks)
	ent := &cacheEntry{
		key:       dk,
		expiresAt: now.Add(c.ttl),
		sizeBytes: sz,
		tracks:    cloned,
	}
	el := c.ll.PushFront(ent)
	c.entries[dk] = el
	c.usedBytes += sz
}

func (c *resultCache) Clear() {
	c.ll.Init()
	c.entries = make(map[string]*list.Element)
	c.usedBytes = 0
}
