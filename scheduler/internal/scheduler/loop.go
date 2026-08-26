package scheduler

import (
	"context"
	"log"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/client"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/policy"
)

type ControlClient interface {
	Snapshot(ctx context.Context) (model.Snapshot, error)
	Assign(ctx context.Context, placement model.Placement) (model.AssignResponse, error)
}

type Loop struct {
	Client       ControlClient
	Policy       policy.Policy
	PollInterval time.Duration
}

func (l *Loop) Run(ctx context.Context) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		wait := l.tick(ctx)
		if wait <= 0 {
			continue
		}
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}

func (l *Loop) tick(ctx context.Context) time.Duration {
	interval := l.PollInterval
	if interval <= 0 {
		interval = 500 * time.Millisecond
	}
	snapshot, err := l.Client.Snapshot(ctx)
	if err != nil {
		if ctx.Err() != nil {
			return 0
		}
		log.Printf("event=snapshot_failed err=%v", err)
		return interval
	}
	if len(snapshot.Operations) == 0 {
		log.Printf("event=scheduler_idle policy=%s operations=0", l.Policy.Name())
		return interval
	}
	placement, ok := l.Policy.Select(snapshot.Operations, snapshot.Workers)
	if !ok {
		opType := ""
		for _, op := range snapshot.Operations {
			if op.OperationID == placement.OperationID {
				opType = op.Type
				break
			}
		}
		log.Printf(
			"event=no_eligible_worker policy=%s operationId=%s type=%s queued=%d",
			l.Policy.Name(),
			placement.OperationID,
			opType,
			len(snapshot.Operations),
		)
		return interval
	}
	assigned, err := l.Client.Assign(ctx, placement)
	if err != nil {
		if client.IsConflict(err) {
			log.Printf(
				"event=assign_conflict operationId=%s workerId=%s err=%v",
				placement.OperationID,
				placement.WorkerID,
				err,
			)
			return 0
		}
		log.Printf(
			"event=assign_failed operationId=%s workerId=%s err=%v",
			placement.OperationID,
			placement.WorkerID,
			err,
		)
		return interval
	}
	log.Printf(
		"event=assigned operationId=%s workerId=%s policy=%s decisionId=%s routingKey=%s",
		assigned.OperationID,
		assigned.WorkerID,
		assigned.Policy,
		assigned.DecisionID,
		assigned.RoutingKey,
	)
	return 0
}
