package worker

import (
	"context"
	"log"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/client"
	"github.com/timothy-choi/automated-video-processor/worker/internal/executor"
	"github.com/timothy-choi/automated-video-processor/worker/internal/inputuri"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
)

type Config struct {
	ControlServiceURL string
	PollInterval      time.Duration
	FfprobePath       string
}

type Worker struct {
	cfg    Config
	client *client.Client
}

func New(cfg Config) *Worker {
	return &Worker{
		cfg:    cfg,
		client: client.New(cfg.ControlServiceURL, 30*time.Second),
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
	start := time.Now()
	execCtx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	if claimed.Type != "METADATA" {
		w.reportFailure(runCtx, claimed.OperationID, time.Since(start), "unsupported operation type "+claimed.Type)
		return
	}

	path, err := inputuri.PathFromFileURI(claimed.InputURI)
	if err != nil {
		w.reportFailure(runCtx, claimed.OperationID, time.Since(start), err.Error())
		return
	}

	result, err := executor.ProbeFile(execCtx, w.cfg.FfprobePath, path)
	runtimeMs := time.Since(start).Milliseconds()
	if err != nil {
		w.reportFailure(runCtx, claimed.OperationID, time.Duration(runtimeMs)*time.Millisecond, err.Error())
		return
	}

	reportCtx, reportCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer reportCancel()
	if err := w.client.Complete(reportCtx, claimed.OperationID, runtimeMs, result); err != nil {
		log.Printf("complete report failed for %s: %v", claimed.OperationID, err)
		return
	}
	log.Printf("completed operation %s in %dms", claimed.OperationID, runtimeMs)
}

func (w *Worker) reportFailure(runCtx context.Context, operationID string, runtime time.Duration, reason string) {
	reportCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	ms := runtime.Milliseconds()
	if err := w.client.Fail(reportCtx, operationID, &ms, reason); err != nil {
		log.Printf("fail report failed for %s: %v", operationID, err)
		return
	}
	log.Printf("failed operation %s: %s", operationID, reason)
	_ = runCtx
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
