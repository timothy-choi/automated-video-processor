package storage

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
)

func SHA256File(path string) (checksum string, size int64, err error) {
	file, err := os.Open(path)
	if err != nil {
		return "", 0, fmt.Errorf("checksum: %w", err)
	}
	defer file.Close()

	hash := sha256.New()
	n, err := io.Copy(hash, file)
	if err != nil {
		return "", 0, fmt.Errorf("checksum: %w", err)
	}
	return FormatSHA256(hash.Sum(nil)), n, nil
}

func FormatSHA256(sum []byte) string {
	return "sha256:" + hex.EncodeToString(sum)
}
