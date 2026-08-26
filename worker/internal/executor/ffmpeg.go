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

const AudioContentType = "audio/mp4"

func AudioArgs(inputPath, outputPath string) []string {
	return []string{
		"-y",
		"-v", "error",
		"-i", inputPath,
		"-vn",
		"-map", "0:a",
		"-c:a", "aac",
		"-b:a", "192k",
		outputPath,
	}
}

func ExtractAudio(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
	err := runFFmpeg(ctx, ffmpegPath, AudioArgs(inputPath, outputPath)...)
	if err != nil {
		return wrapAudioError(err)
	}
	if !nonEmpty(outputPath) {
		return fmt.Errorf("ffmpeg produced empty audio output")
	}
	return nil
}

func wrapAudioError(err error) error {
	msg := strings.ToLower(err.Error())
	if strings.Contains(msg, "matches no streams") ||
		strings.Contains(msg, "does not contain any stream") ||
		strings.Contains(msg, "does not contain an audio stream") {
		return fmt.Errorf("input has no audio stream")
	}
	return err
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
