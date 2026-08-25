package worker

import (
	"context"
	"log"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/client"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/run"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
)

type Config struct {
	ControlServiceURL string
	PollInterval      time.Duration
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
	return &Worker{
		cfg:    cfg,
		client: client.New(cfg.ControlServiceURL, 30*time.Second),
		deps:   run.DefaultDeps(store, cfg.OutputBucket, cfg.FfprobePath, cfg.FfmpegPath),
	}
}

func (w *Worker) Run(ctx context.Context) error {
	log.Printf("worker polling %s every %s", w.cfg.ControlServiceURL, w.cfg.PollInterval)
	for {
		if err := ctx.Err(); err != nil {
			log.Printf("shutdown requested; stopping poll loop")
			return err
		}

		claimed, ok, err := w.client.Claim(ctx)
		if err != nil {
			if ctx.Err() != nil {
				log.Printf("shutdown requested during claim")
				return ctx.Err()
			}
			log.Printf("claim error: %v", err)
			if !sleep(ctx, w.cfg.PollInterval) {
				return ctx.Err()
			}
			continue
		}
		if !ok {
			if !sleep(ctx, w.cfg.PollInterval) {
				return ctx.Err()
			}
			continue
		}

		w.execute(ctx, claimed)
	}
}

func (w *Worker) execute(runCtx context.Context, claimed *model.ClaimedOperation) {
	execCtx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	outcome, err := run.Execute(execCtx, claimed, w.deps)
	if err != nil {
		w.reportFailure(claimed.OperationID, outcome.RuntimeMs, err.Error())
		return
	}

	reportCtx, reportCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer reportCancel()
	complete := model.CompleteRequest{ActualRuntimeMs: outcome.RuntimeMs}
	if outcome.Metadata != nil {
		complete.Metadata = outcome.Metadata
	}
	if outcome.Artifact != nil {
		complete.Artifact = outcome.Artifact
	}
	if err := w.client.Complete(reportCtx, claimed.OperationID, complete); err != nil {
		log.Printf("complete report failed for %s: %v", claimed.OperationID, err)
		return
	}
	log.Printf("completed operation %s (%s) in %dms", claimed.OperationID, claimed.Type, outcome.RuntimeMs)
	_ = runCtx
}

func (w *Worker) reportFailure(operationID string, runtimeMs int64, reason string) {
	reportCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := w.client.Fail(reportCtx, operationID, &runtimeMs, reason); err != nil {
		log.Printf("fail report failed for %s: %v", operationID, err)
		return
	}
	log.Printf("failed operation %s: %s", operationID, reason)
}

func sleep(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
