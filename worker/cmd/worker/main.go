package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/auth"
	"github.com/timothy-choi/automated-video-processor/worker/internal/capability"
	"github.com/timothy-choi/automated-video-processor/worker/internal/otelx"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
	"github.com/timothy-choi/automated-video-processor/worker/internal/worker"
	"go.opentelemetry.io/otel/attribute"
)

func main() {
	workerID := os.Getenv("WORKER_ID")
	if workerID == "" {
		log.Fatal("WORKER_ID is required")
	}
	restrict, err := capability.ParseSupportedOperationsEnv(os.Getenv("SUPPORTED_OPERATIONS"))
	if err != nil {
		log.Fatal(err)
	}
	if err := auth.RequireWorkerToken(workerID, os.Getenv("WORKER_SERVICE_TOKEN")); err != nil {
		log.Fatal(err)
	}
	hostname := os.Getenv("WORKER_HOSTNAME")
	if hostname == "" {
		hostname, _ = os.Hostname()
	}

	cfg := worker.Config{
		WorkerID:            workerID,
		Hostname:            hostname,
		ControlServiceURL:   envOr("CONTROL_SERVICE_URL", "http://localhost:8080"),
		RabbitMQURL:         envOr("RABBITMQ_URL", defaultRabbitURL()),
		Prefetch:            intEnv("PREFETCH", 1),
		FfprobePath:         envOr("FFPROBE_PATH", "ffprobe"),
		FfmpegPath:          envOr("FFMPEG_PATH", "ffmpeg"),
		OutputBucket:        envOr("OUTPUT_BUCKET", "media-output"),
		HeartbeatInterval:   heartbeatInterval(),
		LeaseRenewInterval:  leaseRenewInterval(),
		ExecutionTimeout:    executionTimeout(),
		SupportedOperations: restrict,
		ServiceToken:        os.Getenv("WORKER_SERVICE_TOKEN"),
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

	shutdownTelemetry, err := otelx.Setup(context.Background(), otelx.Config{
		ServiceName: "media-worker",
		Attributes:  []attribute.KeyValue{attribute.String("worker.id", workerID)},
	})
	if err != nil {
		log.Printf("event=otel_setup_failed err=%v", err)
	}
	defer func() {
		flushCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if shutdownTelemetry != nil {
			_ = shutdownTelemetry(flushCtx)
		}
	}()

	store, err := storage.NewS3Store(ctx, cfg.ObjectStore)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("worker=%s object store endpoint=%s region=%s pathStyle=%t outputBucket=%s",
		cfg.WorkerID, cfg.ObjectStore.Endpoint, cfg.ObjectStore.Region, cfg.ObjectStore.ForcePathStyle, cfg.OutputBucket)

	if err := worker.New(cfg, store).Run(ctx); err != nil && err != context.Canceled {
		log.Fatal(err)
	}
}

func heartbeatInterval() time.Duration {
	parsed, err := worker.ParseHeartbeatInterval(os.Getenv("HEARTBEAT_INTERVAL"))
	if err != nil {
		log.Fatal(err)
	}
	return parsed
}

func leaseRenewInterval() time.Duration {
	parsed, err := worker.ParseLeaseRenewInterval(os.Getenv("LEASE_RENEW_INTERVAL"))
	if err != nil {
		log.Fatal(err)
	}
	return parsed
}

func executionTimeout() time.Duration {
	parsed, err := worker.ParseExecutionTimeout(os.Getenv("EXECUTION_TIMEOUT"))
	if err != nil {
		log.Fatal(err)
	}
	return parsed
}

func defaultRabbitURL() string {
	host := envOr("RABBITMQ_HOST", "localhost")
	port := envOr("RABBITMQ_PORT", "5672")
	user := envOr("RABBITMQ_USERNAME", "media_platform")
	pass := envOr("RABBITMQ_PASSWORD", "media_platform")
	vhost := envOr("RABBITMQ_VHOST", "/")
	if vhost == "/" {
		return "amqp://" + user + ":" + pass + "@" + host + ":" + port + "/"
	}
	return "amqp://" + user + ":" + pass + "@" + host + ":" + port + "/" + vhost
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func intEnv(key string, fallback int) int {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(raw)
	if err != nil || parsed <= 0 {
		log.Fatalf("invalid %s: %s", key, raw)
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
