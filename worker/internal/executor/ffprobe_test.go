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

func TestParseProbeJSONExtractsVideoAndAudio(t *testing.T) {
	raw := []byte(`{
		"format": {
			"format_name": "mov,mp4,m4a,3gp,3g2,mj2",
			"duration": "2.000000",
			"size": "12345"
		},
		"streams": [
			{
				"codec_type": "video",
				"codec_name": "h264",
				"width": 320,
				"height": 240,
				"avg_frame_rate": "30/1"
			},
			{
				"codec_type": "audio",
				"codec_name": "aac"
			}
		]
	}`)

	result, err := ParseProbeJSON(raw)
	if err != nil {
		t.Fatal(err)
	}
	if result.FormatName == nil || *result.FormatName != "mov,mp4,m4a,3gp,3g2,mj2" {
		t.Fatalf("formatName = %v", result.FormatName)
	}
	if result.DurationSeconds == nil || *result.DurationSeconds != 2 {
		t.Fatalf("durationSeconds = %v", result.DurationSeconds)
	}
	if result.SizeBytes == nil || *result.SizeBytes != 12345 {
		t.Fatalf("sizeBytes = %v", result.SizeBytes)
	}
	if result.VideoCodec == nil || *result.VideoCodec != "h264" {
		t.Fatalf("videoCodec = %v", result.VideoCodec)
	}
	if result.AudioCodec == nil || *result.AudioCodec != "aac" {
		t.Fatalf("audioCodec = %v", result.AudioCodec)
	}
	if result.Width == nil || *result.Width != 320 {
		t.Fatalf("width = %v", result.Width)
	}
	if result.Height == nil || *result.Height != 240 {
		t.Fatalf("height = %v", result.Height)
	}
	if result.FrameRate == nil || *result.FrameRate != 30 {
		t.Fatalf("frameRate = %v", result.FrameRate)
	}
}

func TestParseProbeJSONVideoOnly(t *testing.T) {
	raw := []byte(`{
		"format": {"format_name": "rawvideo"},
		"streams": [{"codec_type": "video", "codec_name": "rawvideo", "width": 64, "height": 64, "r_frame_rate": "25/1"}]
	}`)
	result, err := ParseProbeJSON(raw)
	if err != nil {
		t.Fatal(err)
	}
	if result.AudioCodec != nil {
		t.Fatalf("audioCodec should be absent, got %v", result.AudioCodec)
	}
	if result.FrameRate == nil || *result.FrameRate != 25 {
		t.Fatalf("frameRate = %v", result.FrameRate)
	}
}

func TestParseProbeJSONRejectsInvalidJSON(t *testing.T) {
	if _, err := ParseProbeJSON([]byte("{")); err == nil {
		t.Fatal("expected JSON parse error")
	}
}

func TestProbeFileWithGeneratedSample(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "sample.mp4")
	generate := exec.Command(
		"ffmpeg",
		"-y",
		"-f", "lavfi",
		"-i", "testsrc=duration=1:size=320x240:rate=30",
		"-pix_fmt", "yuv420p",
		sample,
	)
	if out, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate failed: %v\n%s", err, out)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	result, err := ProbeFile(ctx, "ffprobe", sample)
	if err != nil {
		t.Fatal(err)
	}
	if result.Width == nil || *result.Width != 320 {
		t.Fatalf("width = %v", result.Width)
	}
	if result.Height == nil || *result.Height != 240 {
		t.Fatalf("height = %v", result.Height)
	}
	if result.VideoCodec == nil {
		t.Fatal("expected a video codec")
	}
	if _, err := os.Stat(sample); err != nil {
		t.Fatal(err)
	}
}

func TestProbeFileMissingInputFails(t *testing.T) {
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, err := ProbeFile(ctx, "ffprobe", filepath.Join(t.TempDir(), "does-not-exist.mp4"))
	if err == nil {
		t.Fatal("expected ffprobe failure for missing input")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "ffprobe failed") {
		t.Fatalf("error should mention ffprobe failure, got %v", err)
	}
}
