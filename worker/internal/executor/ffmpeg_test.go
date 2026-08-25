package executor

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestThumbnailArgsAreSeparateAndSeekable(t *testing.T) {
	args := ThumbnailArgs("/tmp/in.mp4", "/tmp/out.jpg", "1")
	if !containsAll(args, "-ss", "1", "-i", "/tmp/in.mp4", "-frames:v", "1", "/tmp/out.jpg") {
		t.Fatalf("args=%v", args)
	}
	if strings.Contains(strings.Join(args, " "), "ffmpeg -ss") {
		t.Fatal("args should not include the executable")
	}
}

func TestExtractThumbnailFromGeneratedSample(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "sample.mp4")
	generate := exec.Command(
		"ffmpeg",
		"-y",
		"-f", "lavfi",
		"-i", "testsrc=duration=2:size=320x240:rate=30",
		"-pix_fmt", "yuv420p",
		sample,
	)
	if out, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate failed: %v\n%s", err, out)
	}

	output := filepath.Join(dir, "thumb.jpg")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := ExtractThumbnail(ctx, "ffmpeg", sample, output); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(output)
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() == 0 {
		t.Fatal("expected non-empty jpeg")
	}
	header, err := os.ReadFile(output)
	if err != nil {
		t.Fatal(err)
	}
	if len(header) < 2 || header[0] != 0xff || header[1] != 0xd8 {
		t.Fatalf("expected JPEG magic, got %x", header[:min(4, len(header))])
	}
}

func TestExtractThumbnailMissingInputFails(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	err := ExtractThumbnail(ctx, "ffmpeg", filepath.Join(t.TempDir(), "missing.mp4"), filepath.Join(t.TempDir(), "out.jpg"))
	if err == nil {
		t.Fatal("expected ffmpeg failure")
	}
	if !strings.Contains(err.Error(), "ffmpeg failed") {
		t.Fatalf("got %v", err)
	}
}

func containsAll(args []string, want ...string) bool {
	set := map[string]bool{}
	for _, a := range args {
		set[a] = true
	}
	for _, w := range want {
		if !set[w] {
			return false
		}
	}
	return true
}
