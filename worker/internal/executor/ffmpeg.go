package executor

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
)

func ThumbnailArgs(inputPath, outputPath, seekSeconds string) []string {
	return []string{
		"-y",
		"-v", "error",
		"-ss", seekSeconds,
		"-i", inputPath,
		"-frames:v", "1",
		"-q:v", "2",
		outputPath,
	}
}

func ExtractThumbnail(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
	err := runFFmpeg(ctx, ffmpegPath, ThumbnailArgs(inputPath, outputPath, "1")...)
	if err == nil && nonEmpty(outputPath) {
		return nil
	}
	_ = os.Remove(outputPath)
	if err := runFFmpeg(ctx, ffmpegPath, ThumbnailArgs(inputPath, outputPath, "0")...); err != nil {
		return err
	}
	if !nonEmpty(outputPath) {
		return fmt.Errorf("ffmpeg produced an empty thumbnail")
	}
	return nil
}

func runFFmpeg(ctx context.Context, ffmpegPath string, args ...string) error {
	cmd := exec.CommandContext(ctx, ffmpegPath, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		return fmt.Errorf("ffmpeg failed: %s", truncate(message, 4000))
	}
	return nil
}

func nonEmpty(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Size() > 0
}
