//go:build windows

package files

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"syscall"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"golang.org/x/sys/windows"
)

const (
	winErrSharingViolation syscall.Errno = 32
	winErrLockViolation    syscall.Errno = 33
)

// FileIdentity captures OS-level file identity and timestamps for ownership and tamper checks.
type FileIdentity struct {
	Dev     uint64 `json:"dev"`
	Ino     uint64 `json:"ino"`
	Size    int64  `json:"size"`
	MTimeNS int64  `json:"mtime_ns"`
}

func StatIdentity(path string) (FileIdentity, error) {
	pPtr, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return FileIdentity{}, err
	}
	h, err := windows.CreateFile(
		pPtr,
		0,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil,
		windows.OPEN_EXISTING,
		windows.FILE_FLAG_BACKUP_SEMANTICS|windows.FILE_FLAG_OPEN_REPARSE_POINT,
		0,
	)
	if err != nil {
		return FileIdentity{}, err
	}
	defer windows.CloseHandle(h)

	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(h, &info); err != nil {
		return FileIdentity{}, err
	}
	st, err := os.Lstat(path)
	if err != nil {
		return FileIdentity{}, err
	}
	ino := (uint64(info.FileIndexHigh) << 32) | uint64(info.FileIndexLow)
	return FileIdentity{
		Dev:     uint64(info.VolumeSerialNumber),
		Ino:     ino,
		Size:    st.Size(),
		MTimeNS: st.ModTime().UnixNano(),
	}, nil
}

func IsReparseOrSymlink(path string, info os.FileInfo) (bool, error) {
	if info != nil && info.Mode()&os.ModeSymlink != 0 {
		return true, nil
	}
	pPtr, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return false, err
	}
	attrs, err := windows.GetFileAttributes(pPtr)
	if err != nil {
		return false, err
	}
	return attrs&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0, nil
}

func SameVolume(pathA, pathB string) (bool, error) {
	volA, err := volumeSerial(pathA)
	if err != nil {
		return false, err
	}
	volB, err := volumeSerial(pathB)
	if err != nil {
		return false, err
	}
	return volA == volB, nil
}

func volumeSerial(path string) (uint32, error) {
	cur := path
	for {
		if _, err := os.Lstat(cur); err == nil {
			break
		}
		parent := filepath.Dir(cur)
		if parent == cur {
			break
		}
		cur = parent
	}
	pPtr, err := windows.UTF16PtrFromString(cur)
	if err != nil {
		return 0, err
	}
	h, err := windows.CreateFile(
		pPtr,
		0,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil,
		windows.OPEN_EXISTING,
		windows.FILE_FLAG_BACKUP_SEMANTICS,
		0,
	)
	if err != nil {
		return 0, err
	}
	defer windows.CloseHandle(h)
	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(h, &info); err != nil {
		return 0, err
	}
	return info.VolumeSerialNumber, nil
}

func IsBusyHandleError(err error) bool {
	var errno syscall.Errno
	if errors.As(err, &errno) {
		return errno == winErrSharingViolation || errno == winErrLockViolation || errno == windows.ERROR_ACCESS_DENIED
	}
	return false
}

func ReplaceSamePath(ctx context.Context, stage, target string, maxRetries int) error {
	if maxRetries <= 0 {
		maxRetries = 5
	}
	var lastErr error
	for attempt := 0; attempt < maxRetries; attempt++ {
		if err := ctx.Err(); err != nil {
			return model.WrapError(model.ErrCancelled, 499, "文件替换已取消", err)
		}
		err := os.Rename(stage, target)
		if err == nil {
			return nil
		}
		lastErr = err
		if !IsBusyHandleError(err) {
			return model.WrapError(model.ErrInternal, http.StatusInternalServerError, fmt.Sprintf("原子替换文件失败: %v", err), err)
		}
		timer := time.NewTimer(50 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return model.WrapError(model.ErrCancelled, 499, "文件替换已取消", ctx.Err())
		case <-timer.C:
		}
	}
	return &model.DomainError{
		Code:      model.ErrConflict,
		Status:    http.StatusConflict,
		Detail:    "目标文件正被其他播放器或程序占用，已暂停操作并保留原件，请停止播放后重试",
		Retryable: true,
		Cause:     lastErr,
	}
}

func SyncDir(_ string) error {
	return nil
}
