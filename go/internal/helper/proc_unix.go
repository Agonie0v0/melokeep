//go:build !windows && !linux

package helper

import (
	"os/exec"
	"syscall"
)

type ProcessTree struct {
	pgid int
}

func StartInTree(cmd *exec.Cmd) (*ProcessTree, error) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.Setpgid = true
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	return &ProcessTree{pgid: cmd.Process.Pid}, nil
}

func (pt *ProcessTree) Kill() error {
	if pt == nil || pt.pgid <= 0 {
		return nil
	}
	err := syscall.Kill(-pt.pgid, syscall.SIGKILL)
	pt.pgid = 0
	return err
}

func IsPIDAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	return syscall.Kill(pid, 0) == nil
}
