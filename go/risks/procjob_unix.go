//go:build !windows

package risks

import (
	"os/exec"
	"syscall"
)

type ProcessTree struct {
	pgid int
}

// StartInJob on Unix sets a dedicated process group; Linux implementations also set Pdeathsig=SIGKILL.
func StartInJob(cmd *exec.Cmd) (*ProcessTree, error) {
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
	return syscall.Kill(-pt.pgid, syscall.SIGKILL)
}

func IsPIDAlive(pid int) bool {
	return syscall.Kill(pid, 0) == nil
}
