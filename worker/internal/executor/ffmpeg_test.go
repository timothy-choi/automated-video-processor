package executor

import (
	"context"
	"encoding/json"
	"fmt"
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

func TestAudioArgsAreAudioOnlyAacM4a(t *testing.T) {
	args := AudioArgs("/tmp/in.mp4", "/tmp/out.m4a")
	if !containsAll(args, "-i", "/tmp/in.mp4", "-vn", "-map", "0:a", "-c:a", "aac", "-b:a", "192k", "/tmp/out.m4a") {
		t.Fatalf("args=%v", args)
	}
	joined := strings.Join(args, " ")
	if strings.Contains(joined, "ffmpeg ") {
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

func TestExtractAudioFromGeneratedSample(t *testing.T) {
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
		"-f", "lavfi",
		"-i", "sine=frequency=440:duration=1",
		"-pix_fmt", "yuv420p",
		"-c:v", "libx264",
		"-c:a", "aac",
		"-shortest",
		sample,
	)
	if out, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate failed: %v\n%s", err, out)
	}

	output := filepath.Join(dir, "audio.m4a")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := ExtractAudio(ctx, "ffmpeg", sample, output); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(output)
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() == 0 {
		t.Fatal("expected non-empty audio")
	}
	probe := exec.Command("ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", output)
	out, err := probe.CombinedOutput()
	if err != nil {
		t.Fatalf("ffprobe failed: %v\n%s", err, out)
	}
	types := strings.TrimSpace(string(out))
	if types != "audio" {
		t.Fatalf("expected audio-only stream, got %q", types)
	}
}

func TestExtractAudioFailsWhenInputHasNoAudio(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "silent.mp4")
	generate := exec.Command(
		"ffmpeg",
		"-y",
		"-f", "lavfi",
		"-i", "testsrc=duration=1:size=320x240:rate=30",
		"-pix_fmt", "yuv420p",
		"-an",
		sample,
	)
	if out, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate failed: %v\n%s", err, out)
	}

	output := filepath.Join(dir, "audio.m4a")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := ExtractAudio(ctx, "ffmpeg", sample, output)
	if err == nil {
		t.Fatal("expected no-audio failure")
	}
	if !strings.Contains(err.Error(), "no audio stream") {
		t.Fatalf("got %v", err)
	}
	if _, statErr := os.Stat(output); statErr == nil {
		info, _ := os.Stat(output)
		if info != nil && info.Size() > 0 {
			t.Fatal("should not leave a successful audio artifact")
		}
	}
}

func TestTranscodeArgsAreH264Mp4WithOptionalAudio(t *testing.T) {
	args := TranscodeArgs("/tmp/in.mp4", "/tmp/out.mp4")
	if !containsAll(args, "-i", "/tmp/in.mp4", "-map", "0:v:0", "0:a?", "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", "/tmp/out.mp4") {
		t.Fatalf("args=%v", args)
	}
	if !containsAll(args, "-vf", ScaleFilter1080p) {
		t.Fatalf("missing scale filter in %v", args)
	}
	if !strings.Contains(ScaleFilter1080p, "min(iw,1920)") || !strings.Contains(ScaleFilter1080p, "min(ih,1080)") {
		t.Fatalf("scale must cap without upscaling: %s", ScaleFilter1080p)
	}
	if !strings.Contains(ScaleFilter1080p, "force_original_aspect_ratio=decrease") {
		t.Fatalf("scale must preserve aspect ratio: %s", ScaleFilter1080p)
	}
	if !strings.Contains(ScaleFilter1080p, "force_divisible_by=2") {
		t.Fatalf("scale must produce even dimensions: %s", ScaleFilter1080p)
	}
}

func TestWrapTranscodeErrorNoVideo(t *testing.T) {
	err := wrapTranscodeError(fmt.Errorf("ffmpeg failed: Stream map '0:v:0' matches no streams."))
	if err == nil || err.Error() != "input has no video stream" {
		t.Fatalf("got %v", err)
	}
}

func TestTranscodeDownscalesAbove1080p(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "1440p.mp4")
	generateSample(t, sample, "testsrc=duration=0.5:size=2560x1440:rate=10", true)
	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := Transcode1080p(ctx, "ffmpeg", sample, output); err != nil {
		t.Fatal(err)
	}
	w, h, vcodec, acodec, vstreams, astreams := probeMedia(t, output)
	if vcodec != "h264" {
		t.Fatalf("codec=%s", vcodec)
	}
	if w > 1920 || h > 1080 {
		t.Fatalf("output %dx%d exceeds 1920x1080", w, h)
	}
	if w != 1920 || h != 1080 {
		t.Fatalf("expected 1920x1080 from 16:9 1440p, got %dx%d", w, h)
	}
	if vstreams != 1 || astreams != 1 || acodec != "aac" {
		t.Fatalf("streams video=%d audio=%d acodec=%s", vstreams, astreams, acodec)
	}
	info, err := os.Stat(output)
	if err != nil || info.Size() == 0 {
		t.Fatalf("empty output: %v", err)
	}
}

func TestTranscodeDoesNotUpscale720p(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "720p.mp4")
	generateSample(t, sample, "testsrc=duration=0.5:size=1280x720:rate=10", true)
	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	if err := Transcode1080p(ctx, "ffmpeg", sample, output); err != nil {
		t.Fatal(err)
	}
	w, h, vcodec, _, _, _ := probeMedia(t, output)
	if vcodec != "h264" {
		t.Fatalf("codec=%s", vcodec)
	}
	if w != 1280 || h != 720 {
		t.Fatalf("720p must not be upscaled, got %dx%d", w, h)
	}
}

func TestTranscodePreserves43AspectAndEvenDimensions(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "4by3.mp4")
	generateSample(t, sample, "testsrc=duration=0.5:size=1920x1440:rate=10", false)
	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := Transcode1080p(ctx, "ffmpeg", sample, output); err != nil {
		t.Fatal(err)
	}
	w, h, _, _, _, astreams := probeMedia(t, output)
	if w != 1440 || h != 1080 {
		t.Fatalf("expected 1440x1080 from 1920x1440, got %dx%d", w, h)
	}
	if w%2 != 0 || h%2 != 0 {
		t.Fatalf("odd dimensions %dx%d", w, h)
	}
	if astreams != 0 {
		t.Fatal("no-audio input should produce video-only output")
	}

	odd := filepath.Join(dir, "odd.mp4")
	generateOddSource(t, odd)
	oddOut := filepath.Join(dir, "odd-out.mp4")
	if err := Transcode1080p(ctx, "ffmpeg", odd, oddOut); err != nil {
		t.Fatal(err)
	}
	ow, oh, _, _, _, _ := probeMedia(t, oddOut)
	if ow%2 != 0 || oh%2 != 0 {
		t.Fatalf("odd input produced odd output %dx%d", ow, oh)
	}
	if ow > 1920 || oh > 1080 {
		t.Fatalf("odd output %dx%d exceeds 1080p box", ow, oh)
	}
}

func TestTranscodeSucceedsWithoutAudio(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "silent.mp4")
	generateSample(t, sample, "testsrc=duration=0.5:size=320x240:rate=10", false)
	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := Transcode1080p(ctx, "ffmpeg", sample, output); err != nil {
		t.Fatal(err)
	}
	_, _, vcodec, _, vstreams, astreams := probeMedia(t, output)
	if vcodec != "h264" || vstreams != 1 || astreams != 0 {
		t.Fatalf("expected video-only h264, video=%d audio=%d codec=%s", vstreams, astreams, vcodec)
	}
}

func TestTranscodeFailsWhenInputHasNoVideo(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "audio-only.m4a")
	generate := exec.Command(
		"ffmpeg",
		"-y",
		"-f", "lavfi",
		"-i", "sine=frequency=440:duration=0.5",
		"-c:a", "aac",
		sample,
	)
	if out, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate failed: %v\n%s", err, out)
	}

	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := Transcode1080p(ctx, "ffmpeg", sample, output)
	if err == nil {
		t.Fatal("expected no-video failure")
	}
	if !strings.Contains(err.Error(), "no video stream") {
		t.Fatalf("got %v", err)
	}
}

func TestAV1ArgsLibsvtav1AreMp4WithoutScale(t *testing.T) {
	args := AV1Args("/tmp/in.mp4", "/tmp/out.mp4", AV1EncoderLibSvt)
	if !containsAll(args, "-i", "/tmp/in.mp4", "-map", "0:v:0", "0:a?", "-c:v", AV1EncoderLibSvt, "-preset", "8", "-crf", "35", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", "/tmp/out.mp4") {
		t.Fatalf("args=%v", args)
	}
	joined := strings.Join(args, " ")
	if strings.Contains(joined, "scale=") || strings.Contains(joined, ScaleFilter1080p) {
		t.Fatalf("H264_TO_AV1 must not resize: %v", args)
	}
}

func TestAV1ArgsLibaomAreMp4WithoutScale(t *testing.T) {
	args := AV1Args("/tmp/in.mp4", "/tmp/out.mp4", AV1EncoderLibAom)
	if !containsAll(args, "-c:v", AV1EncoderLibAom, "-crf", "32", "-b:v", "0", "-cpu-used", "8", "-row-mt", "1", "-pix_fmt", "yuv420p") {
		t.Fatalf("args=%v", args)
	}
	if strings.Contains(strings.Join(args, " "), "scale=") {
		t.Fatalf("must not resize: %v", args)
	}
}

func TestTranscodeAV1RejectsUnknownEncoder(t *testing.T) {
	err := TranscodeAV1(context.Background(), "ffmpeg", "/tmp/in.mp4", "/tmp/out.mp4", "librav1e")
	if err == nil || !strings.Contains(err.Error(), "AV1 encoder is not available") {
		t.Fatalf("got %v", err)
	}
}

func TestFfmpegFailureMessageDropsSvtInfoBanner(t *testing.T) {
	stderr := "Svt[info]: -------------------------------------------\nSvt[info]: SVT [version]:\tSVT-AV1 Encoder Lib v4.1.0\nError while opening encoder\n"
	got := ffmpegFailureMessage(stderr, fmt.Errorf("exit status 1"))
	if strings.Contains(got, "Svt[info]") || strings.Contains(got, "\t") {
		t.Fatalf("banner leaked: %q", got)
	}
	if got != "Error while opening encoder" {
		t.Fatalf("got %q", got)
	}
	got = ffmpegFailureMessage("Svt[info]: banner only\n", fmt.Errorf("signal: killed"))
	if got != "signal: killed" {
		t.Fatalf("got %q", got)
	}
}

func TestFFmpegCommandContextKillsLongEncode(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	dir := t.TempDir()
	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithCancel(context.Background())
	errCh := make(chan error, 1)
	go func() {
		errCh <- runFFmpeg(ctx, "ffmpeg",
			"-y",
			"-f", "lavfi",
			"-i", "testsrc=duration=120:size=320x240:rate=30",
			"-c:v", "libx264",
			"-preset", "ultrafast",
			output,
		)
	}()
	deadline := time.Now().Add(3 * time.Second)
	for {
		if _, err := os.Stat(output); err == nil {
			break
		}
		if time.Now().After(deadline) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	started := time.Now()
	cancel()
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected ffmpeg to fail after cancel")
		}
		if time.Since(started) > 5*time.Second {
			t.Fatalf("ffmpeg took too long to die: %s", time.Since(started))
		}
	case <-time.After(8 * time.Second):
		t.Fatal("ffmpeg did not exit after cancel")
	}
}

func TestTranscodeAV1ContextCancelStopsBeforeCompletion(t *testing.T) {
	encoder := requireAV1Encoder(t)
	dir := t.TempDir()
	sample := filepath.Join(dir, "h264.mp4")
	generateSample(t, sample, "testsrc=duration=4:size=640x360:rate=24", true)
	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithCancel(context.Background())
	errCh := make(chan error, 1)
	go func() {
		errCh <- TranscodeAV1(ctx, "ffmpeg", sample, output, encoder)
	}()
	deadline := time.Now().Add(3 * time.Second)
	for {
		if _, err := os.Stat(output); err == nil {
			break
		}
		if time.Now().After(deadline) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	started := time.Now()
	cancel()
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected AV1 encode to fail after cancel")
		}
		if time.Since(started) > 8*time.Second {
			t.Fatalf("AV1 ffmpeg took too long to die: %s", time.Since(started))
		}
	case <-time.After(15 * time.Second):
		t.Fatal("AV1 ffmpeg did not exit after cancel")
	}
}

func TestTranscodeAV1FromH264Sample(t *testing.T) {
	encoder := requireAV1Encoder(t)
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "h264.mp4")
	generateSample(t, sample, "testsrc=duration=0.4:size=1280x720:rate=10", true)
	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	if err := TranscodeAV1(ctx, "ffmpeg", sample, output, encoder); err != nil {
		t.Fatal(err)
	}
	w, h, vcodec, acodec, vstreams, astreams := probeMedia(t, output)
	if vcodec != "av1" {
		t.Fatalf("codec=%s", vcodec)
	}
	if w != 1280 || h != 720 {
		t.Fatalf("resolution must be preserved, got %dx%d", w, h)
	}
	if vstreams != 1 || astreams != 1 || acodec != "aac" {
		t.Fatalf("streams video=%d audio=%d acodec=%s", vstreams, astreams, acodec)
	}
}

func TestTranscodeAV1SucceedsWithoutAudio(t *testing.T) {
	encoder := requireAV1Encoder(t)
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}

	dir := t.TempDir()
	sample := filepath.Join(dir, "silent.mp4")
	generateSample(t, sample, "testsrc=duration=0.4:size=320x240:rate=10", false)
	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	if err := TranscodeAV1(ctx, "ffmpeg", sample, output, encoder); err != nil {
		t.Fatal(err)
	}
	_, _, vcodec, _, vstreams, astreams := probeMedia(t, output)
	if vcodec != "av1" || vstreams != 1 || astreams != 0 {
		t.Fatalf("expected video-only av1, video=%d audio=%d codec=%s", vstreams, astreams, vcodec)
	}
}

func TestTranscodeAV1FailsWhenInputHasNoVideo(t *testing.T) {
	encoder := requireAV1Encoder(t)

	dir := t.TempDir()
	sample := filepath.Join(dir, "audio-only.m4a")
	generate := exec.Command(
		"ffmpeg",
		"-y",
		"-f", "lavfi",
		"-i", "sine=frequency=440:duration=0.4",
		"-c:a", "aac",
		sample,
	)
	if out, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate failed: %v\n%s", err, out)
	}

	output := filepath.Join(dir, "out.mp4")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := TranscodeAV1(ctx, "ffmpeg", sample, output, encoder)
	if err == nil {
		t.Fatal("expected no-video failure")
	}
	if !strings.Contains(err.Error(), "no video stream") {
		t.Fatalf("got %v", err)
	}
}

func requireAV1Encoder(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	out, err := exec.Command("ffmpeg", "-encoders").CombinedOutput()
	if err != nil {
		t.Skip("ffmpeg -encoders failed")
	}
	text := string(out)
	if strings.Contains(text, "libsvtav1") {
		return AV1EncoderLibSvt
	}
	if strings.Contains(text, "libaom-av1") {
		return AV1EncoderLibAom
	}
	t.Skip("no usable AV1 encoder (libsvtav1 or libaom-av1)")
	return ""
}

func generateSample(t *testing.T, path, videoSpec string, withAudio bool) {
	t.Helper()
	args := []string{"-y", "-f", "lavfi", "-i", videoSpec}
	if withAudio {
		args = append(args, "-f", "lavfi", "-i", "sine=frequency=440:duration=0.5")
	}
	args = append(args, "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast")
	if withAudio {
		args = append(args, "-c:a", "aac", "-shortest")
	} else {
		args = append(args, "-an")
	}
	args = append(args, path)
	cmd := exec.Command("ffmpeg", args...)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg generate failed: %v\n%s", err, out)
	}
}

func generateOddSource(t *testing.T, path string) {
	t.Helper()
	cmd := exec.Command(
		"ffmpeg",
		"-y",
		"-f", "lavfi",
		"-i", "testsrc=duration=0.4:size=641x481:rate=10",
		"-pix_fmt", "yuv444p",
		"-c:v", "libx264",
		"-preset", "ultrafast",
		"-an",
		path,
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg odd generate failed: %v\n%s", err, out)
	}
}

func probeMedia(t *testing.T, path string) (width, height int, videoCodec, audioCodec string, videoStreams, audioStreams int) {
	t.Helper()
	cmd := exec.Command(
		"ffprobe",
		"-v", "error",
		"-show_entries", "stream=codec_type,codec_name,width,height",
		"-of", "json",
		path,
	)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("ffprobe failed: %v\n%s", err, out)
	}
	var parsed struct {
		Streams []struct {
			CodecType string `json:"codec_type"`
			CodecName string `json:"codec_name"`
			Width     int    `json:"width"`
			Height    int    `json:"height"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(out, &parsed); err != nil {
		t.Fatalf("ffprobe json: %v\n%s", err, out)
	}
	for _, stream := range parsed.Streams {
		switch stream.CodecType {
		case "video":
			videoStreams++
			videoCodec = stream.CodecName
			width = stream.Width
			height = stream.Height
		case "audio":
			audioStreams++
			audioCodec = stream.CodecName
		}
	}
	return
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
