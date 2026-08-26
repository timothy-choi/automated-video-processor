package policy

import (
	"sort"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

func fifoHead(operations []model.Operation) model.Operation {
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
	return ops[0]
}
