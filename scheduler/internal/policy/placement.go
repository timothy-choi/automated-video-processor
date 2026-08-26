package policy

import (
	"sort"
	"strings"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

func NextWorker(workerPolicy string, eligibleSorted []string, lastWorkerID string) string {
	if len(eligibleSorted) == 0 {
		return ""
	}
	if workerPolicy == RoundRobin {
		return nextRoundRobin(eligibleSorted, lastWorkerID)
	}
	return eligibleSorted[0]
}

func nextRoundRobin(eligibleSorted []string, lastWorkerID string) string {
	if lastWorkerID == "" {
		return eligibleSorted[0]
	}
	for _, workerID := range eligibleSorted {
		if workerID > lastWorkerID {
			return workerID
		}
	}
	return eligibleSorted[0]
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
	sort.Slice(out, func(i, j int) bool {
		return out[i].ID < out[j].ID
	})
	return out
}

func workerIDs(workers []model.Worker) []string {
	ids := make([]string, len(workers))
	for i, worker := range workers {
		ids[i] = worker.ID
	}
	return ids
}

func supports(supported []string, operationType string) bool {
	for _, item := range supported {
		if item == operationType {
			return true
		}
	}
	return false
}
