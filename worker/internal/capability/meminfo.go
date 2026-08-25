package capability

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"
)

func ParseMeminfo(content string) (int64, error) {
	scanner := bufio.NewScanner(strings.NewReader(content))
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "MemTotal:") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			return 0, fmt.Errorf("invalid MemTotal line")
		}
		kb, err := strconv.ParseInt(fields[1], 10, 64)
		if err != nil {
			return 0, fmt.Errorf("invalid MemTotal value: %w", err)
		}
		return kb * 1024, nil
	}
	return 0, fmt.Errorf("MemTotal not found")
}

func linuxMemoryBytes() (int64, error) {
	data, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0, err
	}
	return ParseMeminfo(string(data))
}
