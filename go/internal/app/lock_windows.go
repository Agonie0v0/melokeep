//go:build windows

package app

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"golang.org/x/sys/windows"
)

// FileLock holds an exclusive OS-level file lock on a database or music root directory.
// It never deletes lock files solely based on stale PID metadata.
type FileLock struct {
	path   string
	file   *os.File
	handle windows.Handle
}

type lockMetadata struct {
	Version   string `json:"version"`
	PID       int    `json:"pid"`
	Scope     string `json:"scope"`
	StartedAt string `json:"started_at"`
}

func AcquireFileLock(path, scope, version string) (*FileLock, error) {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "锁文件路径无效", err)
	}
	if err := os.MkdirAll(filepath.Dir(absPath), 0o755); err != nil {
		return nil, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "创建锁文件目录失败", err)
	}
	f, err := os.OpenFile(absPath, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, model.WrapError(model.ErrConflict, http.StatusConflict, fmt.Sprintf("无法打开实例锁 %s", absPath), err)
	}
	handle := windows.Handle(f.Fd())
	var ol windows.Overlapped
	err = windows.LockFileEx(
		handle,
		windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY,
		0,
		1,
		0,
		&ol,
	)
	if err != nil {
		_ = f.Close()
		return nil, model.WrapError(
			model.ErrConflict,
			http.StatusConflict,
			fmt.Sprintf("已有另一个 MeloKeep 实例持有写锁 (%s): 请先停止旧服务再启动", absPath),
			err,
		)
	}

	meta, _ := json.Marshal(lockMetadata{
		Version:   version,
		PID:       os.Getpid(),
		Scope:     scope,
		StartedAt: time.Now().UTC().Format(time.RFC3339),
	})
	_ = f.Truncate(0)
	_, _ = f.Seek(0, 0)
	_, _ = f.Write(append(meta, '\n'))
	_ = f.Sync()

	return &FileLock{
		path:   absPath,
		file:   f,
		handle: handle,
	}, nil
}

func (l *FileLock) Release() error {
	if l == nil || l.file == nil {
		return nil
	}
	var ol windows.Overlapped
	_ = windows.UnlockFileEx(l.handle, 0, 1, 0, &ol)
	err := l.file.Close()
	l.file = nil
	l.handle = 0
	return err
}
