//go:build !linux && !darwin

package capability

import (
	"fmt"
	"runtime"
)

func systemMemoryBytes() (int64, error) {
	return 0, fmt.Errorf("total memory detection is not supported on %s", runtime.GOOS)
}
