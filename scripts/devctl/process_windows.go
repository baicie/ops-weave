package main

import (
	"context"
	"fmt"
	"os/exec"
	"syscall"
	"time"
	"unsafe"
)

func configureChild(cmd *exec.Cmd) { cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true} }
func configureDaemon(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x00000008 | 0x00000200}
}

func processAlive(pid int) bool {
	dll := syscall.NewLazyDLL("kernel32.dll")
	handle, _, _ := dll.NewProc("OpenProcess").Call(0x1000, 0, uintptr(pid))
	if handle == 0 {
		return false
	}
	defer dll.NewProc("CloseHandle").Call(handle)
	var code uint32
	ok, _, _ := dll.NewProc("GetExitCodeProcess").Call(handle, uintptr(unsafe.Pointer(&code)))
	return ok != 0 && code == 259
}

func stopTreeOS(p *ownedProcess) error {
	if p.exited() {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "taskkill.exe", "/PID", fmt.Sprint(p.cmd.Process.Pid), "/T", "/F")
	configureChild(cmd)
	if err := cmd.Run(); err != nil && !p.exited() {
		return fmt.Errorf("停止进程树失败")
	}
	select {
	case <-p.done:
		return nil
	case <-ctx.Done():
		return fmt.Errorf("停止进程树超时")
	}
}
