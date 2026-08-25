package workspace

import (
	"os"
	"path/filepath"
	"testing"
)

func TestWorkspaceCleanupRemovesOnlyTempDir(t *testing.T) {
	sourceDir := t.TempDir()
	source := filepath.Join(sourceDir, "original.mp4")
	if err := os.WriteFile(source, []byte("source"), 0o600); err != nil {
		t.Fatal(err)
	}

	ws, err := New("op-123")
	if err != nil {
		t.Fatal(err)
	}
	tempFile := ws.File("input.mp4")
	if err := os.WriteFile(tempFile, []byte("copied"), 0o600); err != nil {
		t.Fatal(err)
	}
	dir := ws.Dir
	if err := ws.Cleanup(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("workspace should be removed, err=%v", err)
	}
	if _, err := os.Stat(source); err != nil {
		t.Fatalf("original file:// source must remain: %v", err)
	}
}
