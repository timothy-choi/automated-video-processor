package inputuri

import (
	"strings"
	"testing"
)

func TestPathFromFileURI(t *testing.T) {
	path, err := PathFromFileURI("file:///tmp/sample.mp4")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if path != "/tmp/sample.mp4" {
		t.Fatalf("got %q, want /tmp/sample.mp4", path)
	}
}

func TestPathFromFileURIRejectsS3(t *testing.T) {
	_, err := PathFromFileURI("s3://media-input/video.mp4")
	if err == nil {
		t.Fatal("expected unsupported scheme error")
	}
	if !strings.Contains(err.Error(), "file://") {
		t.Fatalf("error should mention file:// support, got %v", err)
	}
}

func TestPathFromFileURIRejectsEmpty(t *testing.T) {
	if _, err := PathFromFileURI("   "); err == nil {
		t.Fatal("expected error for empty URI")
	}
}
