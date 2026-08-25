//go:build darwin

package capability

import (
	"fmt"

	"golang.org/x/sys/unix"
)

func systemMemoryBytes() (int64, error) {
	bytes, err := unix.SysctlUint64("hw.memsize")
	if err != nil {
		return 0, fmt.Errorf("sysctl hw.memsize: %w", err)
	}
	return int64(bytes), nil
}
