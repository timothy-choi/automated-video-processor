package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/worker"
)

func main() {
	cfg := worker.Config{
		ControlServiceURL: envOr("CONTROL_SERVICE_URL", "http://localhost:8080"),
		PollInterval:      durationEnv("POLL_INTERVAL", time.Second),
		FfprobePath:       envOr("FFPROBE_PATH", "ffprobe"),
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := worker.New(cfg).Run(ctx); err != nil && err != context.Canceled {
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
