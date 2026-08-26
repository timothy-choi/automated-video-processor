package assignment

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
)

type Assignment struct {
	SchemaVersion int       `json:"schemaVersion"`
	OperationID   string    `json:"operationId"`
	JobID         string    `json:"jobId"`
	Type          string    `json:"type"`
	InputURI      string    `json:"inputUri"`
	WorkerID      string    `json:"workerId"`
	Policy        string    `json:"policy"`
	DispatchedAt  time.Time `json:"dispatchedAt"`
	ScheduledAt   time.Time `json:"scheduledAt"`
}

func Parse(body []byte) (Assignment, error) {
	var assignment Assignment
	if err := json.Unmarshal(body, &assignment); err != nil {
		return Assignment{}, fmt.Errorf("malformed assignment JSON: %w", err)
	}
	if assignment.SchemaVersion != 1 && assignment.SchemaVersion != 2 {
		return Assignment{}, fmt.Errorf("unsupported schemaVersion %d", assignment.SchemaVersion)
	}
	if strings.TrimSpace(assignment.OperationID) == "" {
		return Assignment{}, fmt.Errorf("operationId is required")
	}
	if strings.TrimSpace(assignment.JobID) == "" {
		return Assignment{}, fmt.Errorf("jobId is required")
	}
	if strings.TrimSpace(assignment.Type) == "" {
		return Assignment{}, fmt.Errorf("type is required")
	}
	assignment.Type = strings.TrimSpace(assignment.Type)
	if strings.TrimSpace(assignment.InputURI) == "" {
		return Assignment{}, fmt.Errorf("inputUri is required")
	}
	if assignment.SchemaVersion == 2 {
		assignment.WorkerID = strings.TrimSpace(assignment.WorkerID)
		if assignment.WorkerID == "" {
			return Assignment{}, fmt.Errorf("workerId is required")
		}
		assignment.Policy = strings.TrimSpace(assignment.Policy)
		if assignment.Policy == "" {
			return Assignment{}, fmt.Errorf("policy is required")
		}
	}
	return assignment, nil
}

func (a Assignment) Claimed() *model.ClaimedOperation {
	return &model.ClaimedOperation{
		OperationID: a.OperationID,
		JobID:       a.JobID,
		Type:        a.Type,
		InputURI:    a.InputURI,
	}
}
