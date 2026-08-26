package model

import "time"

type Operation struct {
	OperationID    string    `json:"operationId"`
	JobID          string    `json:"jobId"`
	Type           string    `json:"type"`
	InputURI       string    `json:"inputUri"`
	CreatedAt      time.Time `json:"createdAt"`
	OperationOrder int       `json:"operationOrder"`
}

type Worker struct {
	ID                  string   `json:"id"`
	Status              string   `json:"status"`
	SupportedOperations []string `json:"supportedOperations"`
	SupportedCodecs     []string `json:"supportedCodecs"`
	CPUArchitecture     string   `json:"cpuArchitecture"`
	CPUCores            int      `json:"cpuCores"`
	MemoryBytes         int64    `json:"memoryBytes"`
}

type Snapshot struct {
	Operations []Operation `json:"operations"`
	Workers    []Worker    `json:"workers"`
}

type AssignRequest struct {
	OperationID string `json:"operationId"`
	WorkerID    string `json:"workerId"`
	Policy      string `json:"policy"`
}

type AssignResponse struct {
	DecisionID  string `json:"decisionId"`
	OperationID string `json:"operationId"`
	JobID       string `json:"jobId"`
	WorkerID    string `json:"workerId"`
	Policy      string `json:"policy"`
	RoutingKey  string `json:"routingKey"`
}

type Placement struct {
	OperationID string
	WorkerID    string
	Policy      string
}
