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

const TranscodeContentType = "video/mp4"

// ScaleFilter1080p fits within 1920x1080 without upscaling, preserves aspect
// ratio, and rounds to even dimensions required by H.264 yuv420p.
const ScaleFilter1080p = "scale='min(iw,1920)':'min(ih,1080)':force_original_aspect_ratio=decrease:force_divisible_by=2"

func TranscodeArgs(inputPath, outputPath string) []string {
	return []string{
		"-y",
		"-v", "error",
		"-i", inputPath,
		"-map", "0:v:0",
		"-map", "0:a?",
		"-vf", ScaleFilter1080p,
		"-c:v", "libx264",
		"-preset", "medium",
		"-crf", "23",
		"-pix_fmt", "yuv420p",
		"-c:a", "aac",
		"-b:a", "192k",
		"-movflags", "+faststart",
		outputPath,
	}
}

func Transcode1080p(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
	err := runFFmpeg(ctx, ffmpegPath, TranscodeArgs(inputPath, outputPath)...)
	if err != nil {
		return wrapTranscodeError(err)
	}
	if !nonEmpty(outputPath) {
		return fmt.Errorf("ffmpeg produced empty transcode output")
	}
	return nil
}

func wrapTranscodeError(err error) error {
	msg := strings.ToLower(err.Error())
	if strings.Contains(msg, "matches no streams") ||
		strings.Contains(msg, "does not contain any stream") ||
		strings.Contains(msg, "does not contain a video stream") {
		return fmt.Errorf("input has no video stream")
	}
	return err
}

const AV1ContentType = "video/mp4"

const (
	AV1EncoderLibSvt = "libsvtav1"
	AV1EncoderLibAom = "libaom-av1"
)

func AV1Args(inputPath, outputPath, encoder string) []string {
	args := []string{
		"-y",
		"-v", "error",
		"-i", inputPath,
		"-map", "0:v:0",
		"-map", "0:a?",
		"-c:v", encoder,
	}
	switch encoder {
	case AV1EncoderLibSvt:
		args = append(args, "-preset", "8", "-crf", "35")
	case AV1EncoderLibAom:
		args = append(args, "-crf", "32", "-b:v", "0", "-cpu-used", "8", "-row-mt", "1")
	}
	args = append(args,
		"-pix_fmt", "yuv420p",
		"-c:a", "aac",
		"-b:a", "192k",
		"-movflags", "+faststart",
		outputPath,
	)
	return args
}

func TranscodeAV1(ctx context.Context, ffmpegPath, inputPath, outputPath, encoder string) error {
	if encoder != AV1EncoderLibSvt && encoder != AV1EncoderLibAom {
		return fmt.Errorf("AV1 encoder is not available")
	}
	err := runFFmpeg(ctx, ffmpegPath, AV1Args(inputPath, outputPath, encoder)...)
	if err != nil {
		return wrapTranscodeError(err)
	}
	if !nonEmpty(outputPath) {
		return fmt.Errorf("ffmpeg produced empty AV1 output")
	}
	return nil
}

func runFFmpeg(ctx context.Context, ffmpegPath string, args ...string) error {
	cmd := exec.CommandContext(ctx, ffmpegPath, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("ffmpeg failed: %s", ffmpegFailureMessage(stderr.String(), err))
	}
	return nil
}

// ffmpegFailureMessage keeps bounded FFmpeg stderr but drops SVT-AV1 info
// banners, which otherwise hide the real exit error and inject tab characters.
func ffmpegFailureMessage(stderr string, runErr error) string {
	var kept []string
	for _, line := range strings.Split(stderr, "\n") {
		trim := strings.TrimSpace(line)
		if trim == "" || strings.HasPrefix(trim, "Svt[info]:") {
			continue
		}
		kept = append(kept, trim)
	}
	message := strings.Join(kept, "\n")
	if message == "" && runErr != nil {
		message = runErr.Error()
	}
	return truncate(message, 4000)
}

func nonEmpty(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Size() > 0
}
