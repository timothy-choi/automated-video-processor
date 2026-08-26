package run

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
	"github.com/timothy-choi/automated-video-processor/worker/internal/workspace"
)

func TestExecuteMetadataFromS3DownloadsThenProbes(t *testing.T) {
	store := storage.NewMemoryStore()
	store.Put("media-input", "video.mp4", []byte("media"))
	deps := testDeps(store)
	var probed string
	deps.Probe = func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error) {
		probed = inputPath
		data, err := os.ReadFile(inputPath)
		if err != nil {
			t.Fatal(err)
		}
		if string(data) != "media" {
			t.Fatalf("probe input %q", data)
		}
		format := "mp4"
		return model.MetadataResult{FormatName: &format}, nil
	}

	result, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op-1",
		JobID:       "job-1",
		Type:        "METADATA",
		InputURI:    "s3://media-input/video.mp4",
	}, deps)
	if err != nil {
		t.Fatal(err)
	}
	if result.Metadata == nil || *result.Metadata.FormatName != "mp4" {
		t.Fatalf("metadata=%v", result.Metadata)
	}
	if probed == "" {
		t.Fatal("expected probe of downloaded file")
	}
	if _, err := os.Stat(filepath.Dir(probed)); !os.IsNotExist(err) {
		t.Fatalf("workspace should be cleaned up, err=%v", err)
	}
}

func TestExecuteThumbnailUploadsArtifact(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	jpeg := []byte{0xff, 0xd8, 0xff, 0xd9}
	deps.Thumbnail = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		if inputPath != source {
			t.Fatalf("input=%s", inputPath)
		}
		return os.WriteFile(outputPath, jpeg, 0o600)
	}

	result, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op-9",
		JobID:       "job-9",
		Type:        "THUMBNAIL",
		InputURI:    "file://" + source,
	}, deps)
	if err != nil {
		t.Fatal(err)
	}
	if result.Artifact == nil {
		t.Fatal("expected artifact")
	}
	wantURI := "s3://media-output/jobs/job-9/operations/op-9/thumbnail.jpg"
	if result.Artifact.ObjectURI != wantURI {
		t.Fatalf("uri=%s", result.Artifact.ObjectURI)
	}
	if result.Artifact.ContentType != "image/jpeg" || result.Artifact.SizeBytes != int64(len(jpeg)) {
		t.Fatalf("artifact=%+v", result.Artifact)
	}
	sum := sha256.Sum256(jpeg)
	if result.Artifact.Checksum != "sha256:"+hex.EncodeToString(sum[:]) {
		t.Fatalf("checksum=%s", result.Artifact.Checksum)
	}
	uploaded, ok := store.Get("media-output", "jobs/job-9/operations/op-9/thumbnail.jpg")
	if !ok || string(uploaded) != string(jpeg) {
		t.Fatalf("uploaded=%q ok=%v", uploaded, ok)
	}
	if _, err := os.Stat(source); err != nil {
		t.Fatalf("file:// source must not be deleted: %v", err)
	}
}

func TestExecuteAudioExtractionUploadsArtifact(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	audio := []byte("fake-m4a-bytes")
	deps.Audio = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		if inputPath != source {
			t.Fatalf("input=%s", inputPath)
		}
		if !strings.HasSuffix(outputPath, "audio.m4a") {
			t.Fatalf("output=%s", outputPath)
		}
		return os.WriteFile(outputPath, audio, 0o600)
	}

	result, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op-8",
		JobID:       "job-8",
		Type:        "AUDIO_EXTRACTION",
		InputURI:    "file://" + source,
	}, deps)
	if err != nil {
		t.Fatal(err)
	}
	if result.Artifact == nil {
		t.Fatal("expected artifact")
	}
	wantURI := "s3://media-output/jobs/job-8/operations/op-8/audio.m4a"
	if result.Artifact.ObjectURI != wantURI {
		t.Fatalf("uri=%s", result.Artifact.ObjectURI)
	}
	if result.Artifact.ContentType != "audio/mp4" || result.Artifact.SizeBytes != int64(len(audio)) {
		t.Fatalf("artifact=%+v", result.Artifact)
	}
	sum := sha256.Sum256(audio)
	if result.Artifact.Checksum != "sha256:"+hex.EncodeToString(sum[:]) {
		t.Fatalf("checksum=%s", result.Artifact.Checksum)
	}
	uploaded, ok := store.Get("media-output", "jobs/job-8/operations/op-8/audio.m4a")
	if !ok || string(uploaded) != string(audio) {
		t.Fatalf("uploaded=%q ok=%v", uploaded, ok)
	}
	if _, err := os.Stat(source); err != nil {
		t.Fatalf("file:// source must not be deleted: %v", err)
	}
}

func TestExecuteAudioEmptyOutputRejected(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	deps.Audio = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		return os.WriteFile(outputPath, []byte{}, 0o600)
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op",
		JobID:       "job",
		Type:        "AUDIO_EXTRACTION",
		InputURI:    "file://" + source,
	}, deps)
	if err == nil || !strings.Contains(err.Error(), "empty") {
		t.Fatalf("got %v", err)
	}
	if _, ok := store.Get("media-output", "jobs/job/operations/op/audio.m4a"); ok {
		t.Fatal("empty audio must not be uploaded")
	}
}

func TestExecuteAudioUploadFailure(t *testing.T) {
	store := storage.NewMemoryStore()
	store.UploadErr = errors.New("upload s3://media-output/x failed: object store request failed")
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	deps.Audio = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		return os.WriteFile(outputPath, []byte("m4a"), 0o600)
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op",
		JobID:       "job",
		Type:        "AUDIO_EXTRACTION",
		InputURI:    "file://" + source,
	}, deps)
	if err == nil || !strings.Contains(err.Error(), "upload") {
		t.Fatalf("got %v", err)
	}
}

func TestExecuteTranscodeUploadsArtifact(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	mp4 := []byte("fake-mp4-bytes")
	deps.Transcode = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		if inputPath != source {
			t.Fatalf("input=%s", inputPath)
		}
		if !strings.HasSuffix(outputPath, "video-1080p.mp4") {
			t.Fatalf("output=%s", outputPath)
		}
		return os.WriteFile(outputPath, mp4, 0o600)
	}

	result, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op-7",
		JobID:       "job-7",
		Type:        "TRANSCODE_1080P",
		InputURI:    "file://" + source,
	}, deps)
	if err != nil {
		t.Fatal(err)
	}
	if result.Artifact == nil {
		t.Fatal("expected artifact")
	}
	wantURI := "s3://media-output/jobs/job-7/operations/op-7/video-1080p.mp4"
	if result.Artifact.ObjectURI != wantURI {
		t.Fatalf("uri=%s", result.Artifact.ObjectURI)
	}
	if result.Artifact.ContentType != "video/mp4" || result.Artifact.SizeBytes != int64(len(mp4)) {
		t.Fatalf("artifact=%+v", result.Artifact)
	}
	sum := sha256.Sum256(mp4)
	if result.Artifact.Checksum != "sha256:"+hex.EncodeToString(sum[:]) {
		t.Fatalf("checksum=%s", result.Artifact.Checksum)
	}
	uploaded, ok := store.Get("media-output", "jobs/job-7/operations/op-7/video-1080p.mp4")
	if !ok || string(uploaded) != string(mp4) {
		t.Fatalf("uploaded=%q ok=%v", uploaded, ok)
	}
}

func TestExecuteTranscodeEmptyOutputRejected(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	deps.Transcode = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		return os.WriteFile(outputPath, []byte{}, 0o600)
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op",
		JobID:       "job",
		Type:        "TRANSCODE_1080P",
		InputURI:    "file://" + source,
	}, deps)
	if err == nil || !strings.Contains(err.Error(), "empty") {
		t.Fatalf("got %v", err)
	}
	if _, ok := store.Get("media-output", "jobs/job/operations/op/video-1080p.mp4"); ok {
		t.Fatal("empty transcode must not be uploaded")
	}
}

func TestExecuteTranscodeUploadFailure(t *testing.T) {
	store := storage.NewMemoryStore()
	store.UploadErr = errors.New("upload s3://media-output/x failed: object store request failed")
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	deps.Transcode = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		return os.WriteFile(outputPath, []byte("mp4"), 0o600)
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op",
		JobID:       "job",
		Type:        "TRANSCODE_1080P",
		InputURI:    "file://" + source,
	}, deps)
	if err == nil || !strings.Contains(err.Error(), "upload") {
		t.Fatalf("got %v", err)
	}
}

func TestExecuteAV1UploadsArtifact(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	codec := "h264"
	deps.Probe = func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error) {
		return model.MetadataResult{VideoCodec: &codec}, nil
	}
	av1 := []byte("fake-av1-bytes")
	deps.TranscodeAV1 = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		if inputPath != source {
			t.Fatalf("input=%s", inputPath)
		}
		if !strings.HasSuffix(outputPath, "video-av1.mp4") {
			t.Fatalf("output=%s", outputPath)
		}
		return os.WriteFile(outputPath, av1, 0o600)
	}

	result, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op-6",
		JobID:       "job-6",
		Type:        "H264_TO_AV1",
		InputURI:    "file://" + source,
	}, deps)
	if err != nil {
		t.Fatal(err)
	}
	if result.Artifact == nil {
		t.Fatal("expected artifact")
	}
	wantURI := "s3://media-output/jobs/job-6/operations/op-6/video-av1.mp4"
	if result.Artifact.ObjectURI != wantURI {
		t.Fatalf("uri=%s", result.Artifact.ObjectURI)
	}
	if result.Artifact.ContentType != "video/mp4" || result.Artifact.SizeBytes != int64(len(av1)) {
		t.Fatalf("artifact=%+v", result.Artifact)
	}
	sum := sha256.Sum256(av1)
	if result.Artifact.Checksum != "sha256:"+hex.EncodeToString(sum[:]) {
		t.Fatalf("checksum=%s", result.Artifact.Checksum)
	}
	uploaded, ok := store.Get("media-output", "jobs/job-6/operations/op-6/video-av1.mp4")
	if !ok || string(uploaded) != string(av1) {
		t.Fatalf("uploaded=%q ok=%v", uploaded, ok)
	}
}

func TestExecuteAV1RejectsNonH264(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	codec := "hevc"
	deps.Probe = func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error) {
		return model.MetadataResult{VideoCodec: &codec}, nil
	}
	deps.TranscodeAV1 = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		t.Fatal("must not encode non-h264")
		return nil
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op",
		JobID:       "job",
		Type:        "H264_TO_AV1",
		InputURI:    "file://" + source,
	}, deps)
	if err == nil || err.Error() != "input video codec is not h264" {
		t.Fatalf("got %v", err)
	}
	if _, ok := store.Get("media-output", "jobs/job/operations/op/video-av1.mp4"); ok {
		t.Fatal("must not upload")
	}
}

func TestExecuteAV1RejectsNoVideo(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.m4a")
	if err := os.WriteFile(source, []byte("audio"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	audio := "aac"
	deps.Probe = func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error) {
		return model.MetadataResult{AudioCodec: &audio}, nil
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		Type:     "H264_TO_AV1",
		InputURI: "file://" + source,
	}, deps)
	if err == nil || !strings.Contains(err.Error(), "no video stream") {
		t.Fatalf("got %v", err)
	}
}

func TestExecuteAV1EmptyOutputRejected(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	codec := "h264"
	deps.Probe = func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error) {
		return model.MetadataResult{VideoCodec: &codec}, nil
	}
	deps.TranscodeAV1 = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		return os.WriteFile(outputPath, []byte{}, 0o600)
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op",
		JobID:       "job",
		Type:        "H264_TO_AV1",
		InputURI:    "file://" + source,
	}, deps)
	if err == nil || !strings.Contains(err.Error(), "empty") {
		t.Fatalf("got %v", err)
	}
	if _, ok := store.Get("media-output", "jobs/job/operations/op/video-av1.mp4"); ok {
		t.Fatal("empty AV1 must not be uploaded")
	}
}

func TestExecuteAV1MissingEncoder(t *testing.T) {
	store := storage.NewMemoryStore()
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	deps.AV1Encoder = ""
	deps.TranscodeAV1 = nil
	codec := "h264"
	deps.Probe = func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error) {
		return model.MetadataResult{VideoCodec: &codec}, nil
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		Type:     "H264_TO_AV1",
		InputURI: "file://" + source,
	}, deps)
	if err == nil || !strings.Contains(err.Error(), "AV1 encoder is not available") {
		t.Fatalf("got %v", err)
	}
}

func TestExecuteUnsupportedType(t *testing.T) {
	_, err := Execute(context.Background(), &model.ClaimedOperation{Type: "TRANSCODE_4K_TO_1080P", InputURI: "file:///tmp/x.mp4"}, testDeps(storage.NewMemoryStore()))
	if err == nil || !strings.Contains(err.Error(), "unsupported operation type") {
		t.Fatalf("got %v", err)
	}
}

func TestExecuteS3DownloadFailure(t *testing.T) {
	store := storage.NewMemoryStore()
	store.DownloadErr = errors.New("download s3://media-input/missing.mp4 failed: object not found")
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		Type:     "METADATA",
		InputURI: "s3://media-input/missing.mp4",
	}, testDeps(store))
	if err == nil || !strings.Contains(err.Error(), "object not found") {
		t.Fatalf("got %v", err)
	}
}

func TestExecuteMalformedURI(t *testing.T) {
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		Type:     "METADATA",
		InputURI: "s3://bucket",
	}, testDeps(storage.NewMemoryStore()))
	if err == nil || !strings.Contains(err.Error(), "object key") {
		t.Fatalf("got %v", err)
	}
}

func TestExecuteUploadFailure(t *testing.T) {
	store := storage.NewMemoryStore()
	store.UploadErr = errors.New("upload s3://media-output/x failed: object store request failed")
	dir := t.TempDir()
	source := filepath.Join(dir, "source.mp4")
	if err := os.WriteFile(source, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	deps := testDeps(store)
	deps.Thumbnail = func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
		return os.WriteFile(outputPath, []byte("jpg"), 0o600)
	}
	_, err := Execute(context.Background(), &model.ClaimedOperation{
		OperationID: "op",
		JobID:       "job",
		Type:        "THUMBNAIL",
		InputURI:    "file://" + source,
	}, deps)
	if err == nil || !strings.Contains(err.Error(), "upload") {
		t.Fatalf("got %v", err)
	}
}

func testDeps(store storage.ObjectStore) Deps {
	return Deps{
		Store:        store,
		OutputBucket: "media-output",
		FfprobePath:  "ffprobe",
		FfmpegPath:   "ffmpeg",
		Probe: func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error) {
			return model.MetadataResult{}, nil
		},
		Thumbnail: func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
			return os.WriteFile(outputPath, []byte("x"), 0o600)
		},
		Audio: func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
			return os.WriteFile(outputPath, []byte("m4a"), 0o600)
		},
		Transcode: func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error {
			return os.WriteFile(outputPath, []byte("mp4"), 0o600)
		},
		NewWorkspace: workspace.New,
	}
}
