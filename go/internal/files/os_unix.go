//go:build !windows

package files

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"syscall"

	"github.com/agonie0v0/melokeep/internal/model"
)

// FileIdentity captures OS-level file identity and timestamps for ownership and tamper checks.
type FileIdentity struct {
	Dev     uint64 `json:"dev"`
	Ino     uint64 `json:"ino"`
	Size    int64  `json:"size"`
	MTimeNS int64  `json:"mtime_ns"`
}

func StatIdentity(path string) (FileIdentity, error) {
	st, err := os.Lstat(path)
	if err != nil {
		return FileIdentity{}, err
	}
	sys, ok := st.Sys().(*syscall.Stat_t)
	if !ok {
		return FileIdentity{Size: st.Size(), MTimeNS: st.ModTime().UnixNano()}, nil
	}
	return FileIdentity{
		Dev:     uint64(sys.Dev),
		Ino:     uint64(sys.Ino),
		Size:    st.Size(),
		MTimeNS: st.ModTime().UnixNano(),
	}, nil
}

func IsReparseOrSymlink(_ string, info os.FileInfo) (bool, error) {
	if info == nil {
		return false, nil
	}
	return info.Mode()&os.ModeSymlink != 0, nil
}

func SameVolume(pathA, pathB string) (bool, error) {
	devA, err := pathDev(pathA)
	if err != nil {
		return false, err
	}
	devB, err := pathDev(pathB)
	if err != nil {
		return false, err
	}
	return devA == devB, nil
}

func pathDev(path string) (uint64, error) {
	cur := path
	for {
		if st, err := os.Lstat(cur); err == nil {
			if sys, ok := st.Sys().(*syscall.Stat_t); ok {
				return uint64(sys.Dev), nil
			}
			return 0, nil
		}
		parent := filepath.Dir(cur)
		if parent == cur {
			return 0, fmt.Errorf("stat failed for %s", path)
		}
		cur = parent
	}
}

func IsBusyHandleError(_ error) bool {
	return false
}

func ReplaceSamePath(ctx context.Context, stage, target string, _ int) error {
	if err := ctx.Err(); err != nil {
		return model.WrapError(model.ErrCancelled, 499, "文件替换已取消", err)
	}
	if err := os.Rename(stage, target); err != nil {
		return model.WrapError(model.ErrInternal, http.StatusInternalServerError, fmt.Sprintf("原子替换文件失败: %v", err), err)
	}
	_ = SyncDir(filepath.Dir(target))
	return nil
}

func SyncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
