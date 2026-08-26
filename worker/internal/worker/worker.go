package worker

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/broker"
	"github.com/timothy-choi/automated-video-processor/worker/internal/capability"
	"github.com/timothy-choi/automated-video-processor/worker/internal/client"
	"github.com/timothy-choi/automated-video-processor/worker/internal/consumer"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/run"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
)

const registerAttempts = 6

type Config struct {
	WorkerID            string
	Hostname            string
	ControlServiceURL   string
	RabbitMQURL         string
	Prefetch            int
	FfprobePath         string
	FfmpegPath          string
	OutputBucket        string
	HeartbeatInterval   time.Duration
	SupportedOperations []string
	ObjectStore         storage.Config
}

type Worker struct {
	cfg       Config
	client    *client.Client
	deps      run.Deps
	detect    func(context.Context) (capability.Snapshot, error)
	register  func(context.Context, model.RegisterWorkerRequest) (model.RegisterWorkerResponse, error)
	heartbeat func(context.Context) error
	consume   func(context.Context, []string) error
}

func New(cfg Config, store storage.ObjectStore) *Worker {
	if cfg.Prefetch <= 0 {
		cfg.Prefetch = 1
	}
	if cfg.HeartbeatInterval <= 0 {
		cfg.HeartbeatInterval = DefaultHeartbeatInterval
	}
	w := &Worker{
		cfg:    cfg,
		client: client.New(cfg.ControlServiceURL, 30*time.Second),
		deps:   run.DefaultDeps(store, cfg.OutputBucket, cfg.FfprobePath, cfg.FfmpegPath),
	}
	w.detect = func(ctx context.Context) (capability.Snapshot, error) {
		return capability.Detect(ctx, capability.Probe{
			FFmpegPath:            cfg.FfmpegPath,
			FfprobePath:           cfg.FfprobePath,
			Hostname:              cfg.Hostname,
			ImplementedOperations: capability.ImplementedOperations(),
			RestrictOperations:    cfg.SupportedOperations,
		})
	}
	w.register = w.client.RegisterWorker
	w.heartbeat = func(ctx context.Context) error {
		_, err := w.client.Heartbeat(ctx, w.cfg.WorkerID)
		return err
	}
	w.consume = func(ctx context.Context, supported []string) error {
		exec := func(execCtx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
			return run.Execute(execCtx, claimed, w.deps)
		}
		return broker.New(broker.Config{
			URL:                 w.cfg.RabbitMQURL,
			WorkerID:            w.cfg.WorkerID,
			Prefetch:            w.cfg.Prefetch,
			SupportedOperations: supported,
		}, w.client, consumer.Executor(exec)).Run(ctx)
	}
	return w
}

func (w *Worker) Run(ctx context.Context) error {
	if strings.TrimSpace(w.cfg.WorkerID) == "" {
		return fmt.Errorf("WORKER_ID is required")
	}
	snap, err := w.detect(ctx)
	if err != nil {
		return fmt.Errorf("detect capabilities: %w", err)
	}
	req := capability.RegistrationRequest(w.cfg.WorkerID, snap)
	log.Printf(
		"worker_id=%s hostname=%s cpu_arch=%s cpu_cores=%d memory_bytes=%d ffmpeg_version=%s supported_operations=%s supported_codecs=%s event=registering",
		req.WorkerID,
		req.Hostname,
		req.CPUArchitecture,
		req.CPUCores,
		req.MemoryBytes,
		req.FFmpegVersion,
		strings.Join(req.SupportedOperations, ","),
		strings.Join(req.SupportedCodecs, ","),
	)
	registered, err := w.registerWithRetry(ctx, req)
	if err != nil {
		return err
	}
	log.Printf(
		"worker_id=%s hostname=%s cpu_arch=%s cpu_cores=%d memory_bytes=%d ffmpeg_version=%s supported_operations=%s supported_codecs=%s status=%s event=registered",
		req.WorkerID,
		req.Hostname,
		req.CPUArchitecture,
		req.CPUCores,
		req.MemoryBytes,
		req.FFmpegVersion,
		strings.Join(req.SupportedOperations, ","),
		strings.Join(req.SupportedCodecs, ","),
		registered.Status,
	)
	heartbeatCtx, stopHeartbeat := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() {
		defer close(done)
		w.runHeartbeatLoop(heartbeatCtx)
	}()
	defer func() {
		stopHeartbeat()
		<-done
	}()
	return w.consume(ctx, req.SupportedOperations)
}

func (w *Worker) registerWithRetry(ctx context.Context, req model.RegisterWorkerRequest) (model.RegisterWorkerResponse, error) {
	backoff := time.Second
	var last error
	for attempt := 1; attempt <= registerAttempts; attempt++ {
		if err := ctx.Err(); err != nil {
			return model.RegisterWorkerResponse{}, err
		}
		registered, err := w.register(ctx, req)
		if err == nil {
			return registered, nil
		}
		last = err
		if !isRetryableRegistration(err) {
			return model.RegisterWorkerResponse{}, fmt.Errorf("worker registration rejected: %w", err)
		}
		if attempt == registerAttempts {
			break
		}
		log.Printf("worker_id=%s event=register_retry attempt=%d err=%v retry_in=%s", req.WorkerID, attempt, err, backoff)
		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			return model.RegisterWorkerResponse{}, ctx.Err()
		case <-timer.C:
		}
		backoff *= 2
		if backoff > 8*time.Second {
			backoff = 8 * time.Second
		}
	}
	return model.RegisterWorkerResponse{}, fmt.Errorf("worker registration failed after %d attempts: %w", registerAttempts, last)
}

func isRetryableRegistration(err error) bool {
	var statusErr *client.StatusError
	if errors.As(err, &statusErr) {
		return statusErr.Status >= 500 || statusErr.Status == 429
	}
	return true
}
