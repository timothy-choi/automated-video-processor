package policy

import (
	"fmt"
	"strings"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

const (
	FIFO          = "FIFO"
	Lexicographic = "LEXICOGRAPHIC"
	RoundRobin    = "ROUND_ROBIN"
)

type Selector struct {
	OperationPolicy string
	WorkerPolicy    string
}

func New(operationPolicy, workerPolicy string) (Selector, error) {
	op := strings.ToUpper(strings.TrimSpace(operationPolicy))
	if op == "" {
		op = FIFO
	}
	if op != FIFO {
		return Selector{}, fmt.Errorf("unsupported operation policy %q; implemented: FIFO", strings.TrimSpace(operationPolicy))
	}
	worker := strings.ToUpper(strings.TrimSpace(workerPolicy))
	if worker == "" {
		worker = Lexicographic
	}
	switch worker {
	case Lexicographic, RoundRobin:
	default:
		return Selector{}, fmt.Errorf("unsupported worker placement policy %q; implemented: LEXICOGRAPHIC, ROUND_ROBIN", strings.TrimSpace(workerPolicy))
	}
	return Selector{OperationPolicy: FIFO, WorkerPolicy: worker}, nil
}

func (s Selector) Name() string {
	return s.OperationPolicy + "+" + s.WorkerPolicy
}

func (s Selector) Select(snapshot model.Snapshot) (model.Placement, bool) {
	if len(snapshot.Operations) == 0 {
		return model.Placement{}, false
	}
	op := fifoHead(snapshot.Operations)
	eligible := eligibleWorkers(op.Type, snapshot.Workers)
	if len(eligible) == 0 {
		return model.Placement{
			OperationID:     op.OperationID,
			OperationPolicy: s.OperationPolicy,
			WorkerPolicy:    s.WorkerPolicy,
		}, false
	}
	last := ""
	if s.WorkerPolicy == RoundRobin && snapshot.RoundRobinCursors != nil {
		last = snapshot.RoundRobinCursors[op.Type]
	}
	workerID := NextWorker(s.WorkerPolicy, workerIDs(eligible), last)
	return model.Placement{
		OperationID:     op.OperationID,
		WorkerID:        workerID,
		OperationPolicy: s.OperationPolicy,
		WorkerPolicy:    s.WorkerPolicy,
	}, true
}
