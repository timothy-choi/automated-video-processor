package run

import (
	"context"
	"fmt"
	"path/filepath"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/executor"
	"github.com/timothy-choi/automated-video-processor/worker/internal/inputuri"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
	"github.com/timothy-choi/automated-video-processor/worker/internal/workspace"
)

type Deps struct {
	Store        storage.ObjectStore
	OutputBucket string
	FfprobePath  string
	FfmpegPath   string
	Probe        func(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error)
	Thumbnail    func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error
	Audio        func(ctx context.Context, ffmpegPath, inputPath, outputPath string) error
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
		metadata, err := deps.Probe(ctx, deps.FfprobePath, inputPath)
		result := Result{RuntimeMs: time.Since(start).Milliseconds()}
		if err != nil {
			return result, err
		}
		result.Metadata = &metadata
		return result, nil
	case "THUMBNAIL":
		outputPath := ws.File("thumbnail.jpg")
		if err := deps.Thumbnail(ctx, deps.FfmpegPath, inputPath, outputPath); err != nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
		}
		return finishArtifact(ctx, claimed, deps, outputPath, storage.ThumbnailObjectKey(claimed.JobID, claimed.OperationID), "image/jpeg", start)
	case "AUDIO_EXTRACTION":
		outputPath := ws.File("audio.m4a")
		if deps.Audio == nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, fmt.Errorf("audio extraction is not configured")
		}
		if err := deps.Audio(ctx, deps.FfmpegPath, inputPath, outputPath); err != nil {
			return Result{RuntimeMs: time.Since(start).Milliseconds()}, err
		}
		return finishArtifact(ctx, claimed, deps, outputPath, storage.AudioObjectKey(claimed.JobID, claimed.OperationID), executor.AudioContentType, start)
	default:
		return Result{RuntimeMs: time.Since(start).Milliseconds()}, fmt.Errorf("unsupported operation type %s", claimed.Type)
	}
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
