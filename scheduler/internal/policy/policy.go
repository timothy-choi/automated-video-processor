package policy

import (
	"fmt"
	"strings"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

const FIFO = "FIFO"

type Policy interface {
	Name() string
	Select(operations []model.Operation, workers []model.Worker) (model.Placement, bool)
}

func New(name string) (Policy, error) {
	switch strings.ToUpper(strings.TrimSpace(name)) {
	case "", FIFO:
		return FIFOPolicy{}, nil
	default:
		return nil, fmt.Errorf("unsupported scheduling policy %q; implemented: FIFO", strings.TrimSpace(name))
	}
}
