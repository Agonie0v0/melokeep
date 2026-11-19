//go:build windows

package risks

import (
	"fmt"
	"os/exec"
	"unsafe"

	"golang.org/x/sys/windows"
)

type ProcessTree struct {
	job windows.Handle
}

// StartInJob launches cmd hidden and suspended, binds it to a kill-on-close Job Object, then resumes it.
func StartInJob(cmd *exec.Cmd) (*ProcessTree, error) {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return nil, fmt.Errorf("CreateJobObject: %w", err)
	}
	var info windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(
		job,
		windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)),
		uint32(unsafe.Sizeof(info)),
	); err != nil {
		_ = windows.CloseHandle(job)
		return nil, fmt.Errorf("SetInformationJobObject: %w", err)
	}

	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &windows.SysProcAttr{}
	}
	cmd.SysProcAttr.HideWindow = true
	cmd.SysProcAttr.CreationFlags |= windows.CREATE_NO_WINDOW | windows.CREATE_SUSPENDED

	if err := cmd.Start(); err != nil {
		_ = windows.CloseHandle(job)
		return nil, err
	}

	procHandle, err := windows.OpenProcess(windows.PROCESS_ALL_ACCESS, false, uint32(cmd.Process.Pid))
	if err != nil {
		_ = cmd.Process.Kill()
		_ = windows.CloseHandle(job)
		return nil, fmt.Errorf("OpenProcess: %w", err)
	}
	defer windows.CloseHandle(procHandle)

	if err := windows.AssignProcessToJobObject(job, procHandle); err != nil {
		_ = cmd.Process.Kill()
		_ = windows.CloseHandle(job)
		return nil, fmt.Errorf("AssignProcessToJobObject: %w", err)
	}

	if err := resumeMainThread(uint32(cmd.Process.Pid)); err != nil {
		_ = windows.TerminateJobObject(job, 1)
		_ = windows.CloseHandle(job)
		return nil, fmt.Errorf("resumeMainThread: %w", err)
	}
	return &ProcessTree{job: job}, nil
}

func resumeMainThread(pid uint32) error {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPTHREAD, 0)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(snap)
	var te windows.ThreadEntry32
	te.Size = uint32(unsafe.Sizeof(te))
	if err := windows.Thread32First(snap, &te); err != nil {
		return err
	}
	resumed := 0
	for {
		if te.OwnerProcessID == pid {
			th, err := windows.OpenThread(windows.THREAD_SUSPEND_RESUME, false, te.ThreadID)
			if err == nil {
				_, _ = windows.ResumeThread(th)
				_ = windows.CloseHandle(th)
				resumed++
			}
		}
		if err := windows.Thread32Next(snap, &te); err != nil {
			break
		}
	}
	if resumed == 0 {
		return fmt.Errorf("no threads found for pid %d", pid)
	}
	return nil
}

func (pt *ProcessTree) Kill() error {
	if pt == nil || pt.job == 0 {
		return nil
	}
	err := windows.TerminateJobObject(pt.job, 1)
	closeErr := windows.CloseHandle(pt.job)
	pt.job = 0
	if err != nil {
		return err
	}
	return closeErr
}

func IsPIDAlive(pid int) bool {
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, uint32(pid))
	if err != nil {
		return false
	}
	defer windows.CloseHandle(h)
	var code uint32
	if err := windows.GetExitCodeProcess(h, &code); err != nil {
		return false
	}
	return code == 259 // STILL_ACTIVE
}
