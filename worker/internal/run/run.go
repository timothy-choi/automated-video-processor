package run

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/executor"
	"github.com/timothy-choi/automated-video-processor/worker/internal/inputuri"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/otelx"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
	"github.com/timothy-choi/automated-video-processor/worker/internal/workspace"
	"go.opentelemetry.io/otel/attribute"
)

type Deps struct {
	Store        storage.ObjectStore
	OutputBucket string
	FfprobePath  string
	FfmpegPath   string
	AV1Encoder   string
	Probe        func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error)
	Thumbnail    func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error
	Audio        func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error
	Transcode    func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error
	TranscodeAV1 func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error
	NewWorkspace func(operationID string) (*workspace.Workspace, error)
}

type Result struct {
	RuntimeMs int64
	Metadata  *model.MetadataResult
	Artifact  *model.ArtifactResult
}

func DefaultDeps(store storage.ObjectStore, outputBucket, ffprobePath, ffmpegPath string) Deps {
	return Deps{
		Store:        store,
		OutputBucket: outputBucket,
		FfprobePath:  ffprobePath,
		FfmpegPath:   ffmpegPath,
		Probe:        executor.ProbeFile,
		Thumbnail:    executor.ExtractThumbnail,
		Audio:        executor.ExtractAudio,
		Transcode:    executor.Transcode1080p,
		NewWorkspace: workspace.New,
	}
}

func Execute(ctx context.Context, claimed *model.ClaimedOperation, deps Deps) (Result, error) {
	start := time.Now()
	ws, err := deps.NewWorkspace(claimed.OperationID)
	if err != nil {
		return Result{RuntimeMs: time.Since(start).Milliseconds()}, fmt.Errorf("workspace: %w", err)
	}
	defer ws.Cleanup()

	inputPath, err := resolveInput(ctx, claimed.InputURI, ws, deps.Store)
	if err != nil {
		return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
	}

	switch claimed.Type {
	case "METADATA":
		var metadata model.MetadataResult
		err := withMediaSpan(ctx, "ffprobe.metadata", claimed, func(ctx context.Context) error {
			var probeErr error
			metadata, probeErr = deps.Probe(ctx, deps.FfprobePath, inputPath)
			return probeErr
		})
		result := Result{RuntimeMs: time.Since(start).Milliseconds()}
		if err != nil {
			return result, err
		}
		result.Metadata = &metadata
		return result, nil
	case "THUMBNAIL":
		outputPath := ws.File("thumbnail.jpg")
		if err := withMediaSpan(ctx, "ffmpeg.thumbnail", claimed, func(ctx context.Context) error {
			return deps.Thumbnail(ctx, deps.FfmpegPath, inputPath, outputPath)
		}); err != nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
		}
		return finishArtifact(ctx, claimed, deps, outputPath, storage.ThumbnailObjectKey(claimed.JobID, claimed.OperationID), "image/jpeg", start)
	case "AUDIO_EXTRACTION":
		outputPath := ws.File("audio.m4a")
		if deps.Audio == nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, fmt.Errorf("audio extraction is not configured")
		}
		if err := withMediaSpan(ctx, "ffmpeg.audio_extract", claimed, func(ctx context.Context) error {
			return deps.Audio(ctx, deps.FfmpegPath, inputPath, outputPath)
		}); err != nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
		}
		return finishArtifact(ctx, claimed, deps, outputPath, storage.AudioObjectKey(claimed.JobID, claimed.OperationID), executor.AudioContentType, start)
	case "TRANSCODE_1080P":
		outputPath := ws.File("video-1080p.mp4")
		if deps.Transcode == nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, fmt.Errorf("transcode is not configured")
		}
		if err := withMediaSpan(ctx, "ffmpeg.transcode_1080p", claimed, func(ctx context.Context) error {
			return deps.Transcode(ctx, deps.FfmpegPath, inputPath, outputPath)
		}); err != nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
		}
		return finishArtifact(ctx, claimed, deps, outputPath, storage.Transcode1080pObjectKey(claimed.JobID, claimed.OperationID), executor.TranscodeContentType, start)
	case "H264_TO_AV1":
		if err := requireH264Video(ctx, inputPath, deps); err != nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
		}
		outputPath := ws.File("video-av1.mp4")
		encode := deps.TranscodeAV1
		if encode == nil {
			if deps.AV1Encoder == "" {
				return Result{RuntimeMs: time.Since(start).Milliseconds()}, fmt.Errorf("AV1 encoder is not available")
			}
			encode = func(ctx context.Context, ffmpegPath, in, out string) error {
				return executor.TranscodeAV1(ctx, ffmpegPath, in, out, deps.AV1Encoder)
			}
		}
		if err := withMediaSpan(ctx, "ffmpeg.h264_to_av1", claimed, func(ctx context.Context) error {
			return encode(ctx, deps.FfmpegPath, inputPath, outputPath)
		}); err != nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
		}
		return finishArtifact(ctx, claimed, deps, outputPath, storage.AV1ObjectKey(claimed.JobID, claimed.OperationID), executor.AV1ContentType, start)
	default:
		return Result{RuntimeMs: time.Since(start).Milliseconds()}, fmt.Errorf("unsupported operation type %s", claimed.Type)
	}
}

func requireH264Video(ctx context.Context, inputPath string, deps Deps) error {
	if deps.Probe == nil {
		return fmt.Errorf("probe is not configured")
	}
	meta, err := deps.Probe(ctx, deps.FfprobePath, inputPath)
	if err != nil {
		return err
	}
	if meta.VideoCodec == nil || strings.TrimSpace(*meta.VideoCodec) == "" {
		return fmt.Errorf("input has no video stream")
	}
	if !isH264(*meta.VideoCodec) {
		return fmt.Errorf("input video codec is not h264")
	}
	return nil
}

func isH264(codec string) bool {
	return strings.EqualFold(strings.TrimSpace(codec), "h264")
}

func finishArtifact(
	ctx context.Context,
	claimed *model.ClaimedOperation,
	deps Deps,
	outputPath, key, contentType string,
	start time.Time,
) (Result, error) {
	checksum, size, err := storage.SHA256File(outputPath)
	if err != nil {
		return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
	}
	if size <= 0 {
		return Result{RuntimeMs: time.Since(start).Milliseconds()}, fmt.Errorf("empty output file")
	}
	if err := deps.Store.Upload(ctx, deps.OutputBucket, key, outputPath, contentType); err != nil {
		return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
	}
	return Result{
		RuntimeMs: time.Since(start).Milliseconds(),
		Artifact: &model.ArtifactResult{
			ObjectURI:   storage.ObjectURI(deps.OutputBucket, key),
			ContentType: contentType,
			SizeBytes:   size,
			Checksum:    checksum,
		},
	}, nil
}

func resolveInput(ctx context.Context, rawURI string, ws *workspace.Workspace, store storage.ObjectStore) (string, error) {
	ref, err := inputuri.Parse(rawURI)
	if err != nil {
		return "", err
	}
	switch ref.Kind {
	case inputuri.KindFile:
		return ref.Path, nil
	case inputuri.KindS3:
		if store == nil {
			return "", fmt.Errorf("object store is not configured")
		}
		name := "input" + filepath.Ext(ref.Key)
		if name == "input" {
			name = "input.bin"
		}
		dest := ws.File(name)
		if err := store.Download(ctx, ref.Bucket, ref.Key, dest); err != nil {
			return "", err
		}
		return dest, nil
	default:
		return "", fmt.Errorf("unsupported URI scheme")
	}
}

func withMediaSpan(ctx context.Context, name string, claimed *model.ClaimedOperation, fn func(context.Context) error) error {
	ctx, span := otelx.Tracer().Start(ctx, name)
	defer span.End()
	span.SetAttributes(
		attribute.String("media.operation.type", claimed.Type),
		attribute.String("media.job.id", claimed.JobID),
		attribute.String("media.operation.id", claimed.OperationID),
	)
	err := fn(ctx)
	if err != nil {
		if errors.Is(err, context.Canceled) {
			span.SetAttributes(attribute.Bool("operation.cancelled", true))
			return err
		}
		otelx.RecordError(span, err)
	}
	return err
}
