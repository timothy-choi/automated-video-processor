package model

import "time"

type Operation struct {
	OperationID    string    `json:"operationId"`
	JobID          string    `json:"jobId"`
	Type           string    `json:"type"`
	InputURI       string    `json:"inputUri"`
	CreatedAt      time.Time `json:"createdAt"`
	QueuedAt       time.Time `json:"queuedAt"`
	OperationOrder int       `json:"operationOrder"`
	Traceparent    string    `json:"traceparent,omitempty"`
	Tracestate     string    `json:"tracestate,omitempty"`
}

type Worker struct {
	ID                  string   `json:"id"`
	Status              string   `json:"status"`
	SupportedOperations []string `json:"supportedOperations"`
	SupportedCodecs     []string `json:"supportedCodecs"`
	CPUArchitecture     string   `json:"cpuArchitecture"`
	CPUCores            int      `json:"cpuCores"`
	MemoryBytes         int64    `json:"memoryBytes"`
	ActiveOperations    int      `json:"activeOperations"`
}

type Snapshot struct {
	Operations        []Operation       `json:"operations"`
	Workers           []Worker          `json:"workers"`
	RoundRobinCursors map[string]string `json:"roundRobinCursors"`
}

type AssignRequest struct {
	OperationID     string `json:"operationId"`
	WorkerID        string `json:"workerId"`
	OperationPolicy string `json:"operationPolicy"`
	WorkerPolicy    string `json:"workerPolicy"`
}

type AssignResponse struct {
	DecisionID      string `json:"decisionId"`
	OperationID     string `json:"operationId"`
	JobID           string `json:"jobId"`
	WorkerID        string `json:"workerId"`
	Policy          string `json:"policy"`
	OperationPolicy string `json:"operationPolicy"`
	WorkerPolicy    string `json:"workerPolicy"`
	RoutingKey      string `json:"routingKey"`
}

type Placement struct {
	OperationID      string
	WorkerID         string
	OperationPolicy  string
	WorkerPolicy     string
	ActiveOperations int
}
