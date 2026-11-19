package files

import (
	"context"
	"net/http"
	"sort"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

type assetLockEntry struct {
	readers       int
	writerActive  bool
	writerPending bool
}

// AssetLockManager enforces per-asset read/write usage locks.
// Reading (playback/streaming) acquires a read lease; writing/moving/deleting stops new readers
// and waits up to WriteWaitTimeout without killing existing active playback handles.
type AssetLockManager struct {
	mu               sync.Mutex
	cond             *sync.Cond
	entries          map[string]*assetLockEntry
	WriteWaitTimeout time.Duration
}

func NewAssetLockManager(writeWait time.Duration) *AssetLockManager {
	if writeWait <= 0 {
		writeWait = 5 * time.Second
	}
	m := &AssetLockManager{
		entries:          make(map[string]*assetLockEntry),
		WriteWaitTimeout: writeWait,
	}
	m.cond = sync.NewCond(&m.mu)
	return m
}

func (m *AssetLockManager) entryLocked(assetID string) *assetLockEntry {
	e, ok := m.entries[assetID]
	if !ok {
		e = &assetLockEntry{}
		m.entries[assetID] = e
	}
	return e
}

func (m *AssetLockManager) cleanupLocked(assetID string, e *assetLockEntry) {
	if e.readers == 0 && !e.writerActive && !e.writerPending {
		delete(m.entries, assetID)
	}
}

// AcquireRead registers a playback/read lease unless a writer is pending or active.
func (m *AssetLockManager) AcquireRead(assetID string) (func(), error) {
	if assetID == "" {
		return func() {}, nil
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entryLocked(assetID)
	if e.writerActive || e.writerPending {
		m.cleanupLocked(assetID, e)
		return nil, &model.DomainError{
			Code:      model.ErrConflict,
			Status:    http.StatusConflict,
			Detail:    "文件正在编辑或发布中，请稍后重试",
			Retryable: true,
		}
	}
	e.readers++
	var once sync.Once
	return func() {
		once.Do(func() {
			m.mu.Lock()
			defer m.mu.Unlock()
			if e.readers > 0 {
				e.readers--
			}
			m.cleanupLocked(assetID, e)
			m.cond.Broadcast()
		})
	}, nil
}

// AcquireWrite acquires exclusive write access for assetID, waiting up to WriteWaitTimeout
// for existing readers to finish. Never steals or aborts active readers.
func (m *AssetLockManager) AcquireWrite(ctx context.Context, assetID string) (func(), error) {
	return m.AcquireWriteMulti(ctx, []string{assetID})
}

// AcquireWriteMulti acquires exclusive write locks in canonical sorted asset_id order.
func (m *AssetLockManager) AcquireWriteMulti(ctx context.Context, assetIDs []string) (func(), error) {
	sorted := dedupeAndSort(assetIDs)
	if len(sorted) == 0 {
		return func() {}, nil
	}
	acquired := make([]string, 0, len(sorted))
	releaseAll := func() {
		m.mu.Lock()
		for i := len(acquired) - 1; i >= 0; i-- {
			id := acquired[i]
			if e, ok := m.entries[id]; ok {
				e.writerActive = false
				e.writerPending = false
				m.cleanupLocked(id, e)
			}
		}
		m.cond.Broadcast()
		m.mu.Unlock()
	}

	for _, id := range sorted {
		if err := m.acquireOneWrite(ctx, id); err != nil {
			releaseAll()
			return nil, err
		}
		acquired = append(acquired, id)
	}

	var once sync.Once
	return func() {
		once.Do(releaseAll)
	}, nil
}

func (m *AssetLockManager) acquireOneWrite(ctx context.Context, assetID string) error {
	deadline := time.Now().Add(m.WriteWaitTimeout)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}

	doneCh := make(chan struct{})
	defer close(doneCh)

	go func() {
		timer := time.NewTimer(time.Until(deadline))
		defer timer.Stop()
		select {
		case <-ctx.Done():
		case <-timer.C:
		case <-doneCh:
			return
		}
		m.mu.Lock()
		m.cond.Broadcast()
		m.mu.Unlock()
	}()

	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entryLocked(assetID)
	for e.writerActive || e.writerPending {
		if ctx.Err() != nil {
			m.cleanupLocked(assetID, e)
			return model.WrapError(model.ErrCancelled, 499, "等待文件写锁已取消", ctx.Err())
		}
		if time.Now().After(deadline) {
			m.cleanupLocked(assetID, e)
			return &model.DomainError{
				Code:      model.ErrConflict,
				Status:    http.StatusConflict,
				Detail:    "文件正被其他修改任务占用，请稍后重试",
				Retryable: true,
			}
		}
		m.cond.Wait()
		e = m.entryLocked(assetID)
	}

	e.writerPending = true
	for e.readers > 0 {
		if ctx.Err() != nil {
			e.writerPending = false
			m.cleanupLocked(assetID, e)
			m.cond.Broadcast()
			return model.WrapError(model.ErrCancelled, 499, "等待文件播放结束已取消", ctx.Err())
		}
		if time.Now().After(deadline) {
			e.writerPending = false
			m.cleanupLocked(assetID, e)
			m.cond.Broadcast()
			return &model.DomainError{
				Code:      model.ErrConflict,
				Status:    http.StatusConflict,
				Detail:    "文件正在播放，请先停止播放后再试",
				Retryable: true,
			}
		}
		m.cond.Wait()
	}

	e.writerPending = false
	e.writerActive = true
	return nil
}

// IsWriteActive reports whether an asset currently has an active or pending write transaction,
// allowing background scanners to skip it instead of competing for locks.
func (m *AssetLockManager) IsWriteActive(assetID string) bool {
	if assetID == "" {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.entries[assetID]
	return ok && (e.writerActive || e.writerPending)
}

func dedupeAndSort(ids []string) []string {
	seen := make(map[string]bool, len(ids))
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, id)
	}
	sort.Strings(out)
	return out
}
