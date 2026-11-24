package files

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"

	"github.com/agonie0v0/melokeep/internal/model"
)

func wrapDstWriteError(dst, fallbackMsg string, err error) error {
	if errors.Is(err, os.ErrPermission) {
		return formatPermissionError("目标目录无写入权限", filepath.Dir(dst), err)
	}
	return model.WrapError(model.ErrInternal, http.StatusInternalServerError, fallbackMsg, err)
}

func HashFileSHA256(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// CopyFileDurable copies src to dst cleanly, fsyncs dst and its parent dir,
// and removes partial dst on any write error without ever modifying src.
func CopyFileDurable(src, dst string) (string, int64, error) {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", 0, wrapDstWriteError(dst, "创建目标目录失败", err)
	}
	in, err := os.Open(src)
	if err != nil {
		return "", 0, model.WrapError(model.ErrNotFound, http.StatusNotFound, "打开源文件失败", err)
	}
	defer in.Close()

	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return "", 0, wrapDstWriteError(dst, "创建暂存或备份文件失败", err)
	}
	h := sha256.New()
	n, copyErr := io.Copy(io.MultiWriter(out, h), in)
	if copyErr != nil {
		_ = out.Close()
		_ = os.Remove(dst)
		return "", 0, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "写入暂存或备份文件失败，已保留原文件", copyErr)
	}
	if syncErr := out.Sync(); syncErr != nil {
		_ = out.Close()
		_ = os.Remove(dst)
		return "", 0, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "落盘同步失败，已保留原文件", syncErr)
	}
	if closeErr := out.Close(); closeErr != nil {
		_ = os.Remove(dst)
		return "", 0, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "关闭写入文件失败，已保留原文件", closeErr)
	}
	_ = SyncDir(filepath.Dir(dst))
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

func WriteFileDurable(dst string, data []byte) (string, error) {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", wrapDstWriteError(dst, "创建目标目录失败", err)
	}
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return "", wrapDstWriteError(dst, "写入文件失败", err)
	}
	if _, err := out.Write(data); err != nil {
		_ = out.Close()
		_ = os.Remove(dst)
		return "", model.WrapError(model.ErrInternal, http.StatusInternalServerError, "写入文件内容失败", err)
	}
	if err := out.Sync(); err != nil {
		_ = out.Close()
		_ = os.Remove(dst)
		return "", model.WrapError(model.ErrInternal, http.StatusInternalServerError, "同步文件失败", err)
	}
	if err := out.Close(); err != nil {
		_ = os.Remove(dst)
		return "", model.WrapError(model.ErrInternal, http.StatusInternalServerError, "关闭文件失败", err)
	}
	_ = SyncDir(filepath.Dir(dst))
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:]), nil
}

func SameIdentity(a, b FileIdentity) bool {
	if a.Ino == 0 || b.Ino == 0 {
		return false
	}
	return a.Dev == b.Dev && a.Ino == b.Ino
}
