//go:build linux

package capability

func systemMemoryBytes() (int64, error) {
	return linuxMemoryBytes()
}
