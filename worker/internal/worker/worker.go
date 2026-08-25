package worker

import (
	"context"
	"log"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/broker"
	"github.com/timothy-choi/automated-video-processor/worker/internal/client"
	"github.com/timothy-choi/automated-video-processor/worker/internal/consumer"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/run"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
)

type Config struct {
	WorkerID          string
	ControlServiceURL string
	RabbitMQURL       string
	Prefetch          int
	FfprobePath       string
	FfmpegPath        string
	OutputBucket      string
	ObjectStore       storage.Config
}

type Worker struct {
	cfg    Config
	client *client.Client
	deps   run.Deps
}

func New(cfg Config, store storage.ObjectStore) *Worker {
	if cfg.WorkerID == "" {
		cfg.WorkerID = "worker"
	}
	if cfg.Prefetch <= 0 {
		cfg.Prefetch = 1
	}
	return &Worker{
		cfg:    cfg,
		client: client.New(cfg.ControlServiceURL, 30*time.Second),
		deps:   run.DefaultDeps(store, cfg.OutputBucket, cfg.FfprobePath, cfg.FfmpegPath),
	}
}

func (w *Worker) Run(ctx context.Context) error {
	log.Printf("worker=%s event=start control=%s rabbitmq=%s prefetch=%d", w.cfg.WorkerID, w.cfg.ControlServiceURL, w.cfg.RabbitMQURL, w.cfg.Prefetch)
	exec := func(execCtx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Execute(execCtx, claimed, w.deps)
	}
	return broker.New(broker.Config{
		URL:      w.cfg.RabbitMQURL,
		WorkerID: w.cfg.WorkerID,
		Prefetch: w.cfg.Prefetch,
	}, w.client, consumer.Executor(exec)).Run(ctx)
}
