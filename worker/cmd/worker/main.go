package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
	"github.com/timothy-choi/automated-video-processor/worker/internal/worker"
)

func main() {
	cfg := worker.Config{
		ControlServiceURL: envOr("CONTROL_SERVICE_URL", "http://localhost:8080"),
		PollInterval:      durationEnv("POLL_INTERVAL", time.Second),
		FfprobePath:       envOr("FFPROBE_PATH", "ffprobe"),
		FfmpegPath:        envOr("FFMPEG_PATH", "ffmpeg"),
		OutputBucket:      envOr("OUTPUT_BUCKET", "media-output"),
		ObjectStore: storage.Config{
			Endpoint:       envOr("OBJECT_STORE_ENDPOINT", "http://localhost:9000"),
			Region:         envOr("OBJECT_STORE_REGION", "us-east-1"),
			AccessKey:      envOr("OBJECT_STORE_ACCESS_KEY", "minioadmin"),
			SecretKey:      envOr("OBJECT_STORE_SECRET_KEY", "minioadmin"),
			ForcePathStyle: boolEnv("OBJECT_STORE_FORCE_PATH_STYLE", true),
		},
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	store, err := storage.NewS3Store(ctx, cfg.ObjectStore)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("object store endpoint=%s region=%s pathStyle=%t outputBucket=%s",
		cfg.ObjectStore.Endpoint, cfg.ObjectStore.Region, cfg.ObjectStore.ForcePathStyle, cfg.OutputBucket)

	if err := worker.New(cfg, store).Run(ctx); err != nil && err != context.Canceled {
		log.Fatal(err)
	}
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func durationEnv(key string, fallback time.Duration) time.Duration {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(raw)
	if err != nil {
		log.Fatalf("invalid %s: %v", key, err)
	}
	return parsed
}

func boolEnv(key string, fallback bool) bool {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(raw)
	if err != nil {
		log.Fatalf("invalid %s: %v", key, err)
	}
	return parsed
}
