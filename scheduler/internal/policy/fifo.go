package policy

import (
	"sort"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

func queueTime(op model.Operation) time.Time {
	if !op.QueuedAt.IsZero() {
		return op.QueuedAt
	}
	return op.CreatedAt
}

func fifoHead(operations []model.Operation) model.Operation {
	ops := append([]model.Operation(nil), operations...)
	sort.SliceStable(ops, func(i, j int) bool {
		a, b := ops[i], ops[j]
		qa, qb := queueTime(a), queueTime(b)
		if !qa.Equal(qb) {
			return qa.Before(qb)
		}
		if a.OperationOrder != b.OperationOrder {
			return a.OperationOrder < b.OperationOrder
		}
		return a.OperationID < b.OperationID
	})
	return ops[0]
}
