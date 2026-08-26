package policy

import (
	"sort"
	"strings"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

// FIFOPolicy orders operations oldest-first and places the oldest operation
// onto the lexicographically first AVAILABLE worker that advertises the type.
//
// This worker rule is an explicit, reproducible baseline. It is not Round
// Robin, Least Loaded, or adaptive placement.
type FIFOPolicy struct{}

func (FIFOPolicy) Name() string {
	return FIFO
}

func (FIFOPolicy) Select(operations []model.Operation, workers []model.Worker) (model.Placement, bool) {
	if len(operations) == 0 {
		return model.Placement{}, false
	}
	ops := append([]model.Operation(nil), operations...)
	sort.SliceStable(ops, func(i, j int) bool {
		a, b := ops[i], ops[j]
		if !a.CreatedAt.Equal(b.CreatedAt) {
			return a.CreatedAt.Before(b.CreatedAt)
		}
		if a.OperationOrder != b.OperationOrder {
			return a.OperationOrder < b.OperationOrder
		}
		return a.OperationID < b.OperationID
	})
	op := ops[0]
	eligible := eligibleWorkers(op.Type, workers)
	if len(eligible) == 0 {
		return model.Placement{OperationID: op.OperationID, Policy: FIFO}, false
	}
	sort.Slice(eligible, func(i, j int) bool {
		return eligible[i].ID < eligible[j].ID
	})
	return model.Placement{
		OperationID: op.OperationID,
		WorkerID:    eligible[0].ID,
		Policy:      FIFO,
	}, true
}

func eligibleWorkers(operationType string, workers []model.Worker) []model.Worker {
	var out []model.Worker
	for _, worker := range workers {
		if !strings.EqualFold(worker.Status, "AVAILABLE") {
			continue
		}
		if !supports(worker.SupportedOperations, operationType) {
			continue
		}
		out = append(out, worker)
	}
	return out
}

func supports(supported []string, operationType string) bool {
	for _, item := range supported {
		if item == operationType {
			return true
		}
	}
	return false
}
