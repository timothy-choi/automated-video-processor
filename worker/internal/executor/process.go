package executor

import (
	"context"
	"os/exec"
	"syscall"
)

// commandWithContext runs a media binary bound to ctx. On cancel it kills the
// process group so FFmpeg/ffprobe cannot leave encoder children behind.
func commandWithContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		if cmd.Process == nil {
			return nil
		}
		return syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
	}
	return cmd
}
