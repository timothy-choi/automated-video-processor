package model

import "time"

type ClaimedOperation struct {
	OperationID string    `json:"operationId"`
	JobID       string    `json:"jobId"`
	Type        string    `json:"type"`
	InputURI    string    `json:"inputUri"`
	ClaimedAt   time.Time `json:"claimedAt"`
	Status      string    `json:"status"`
}

type RegisterWorkerRequest struct {
	WorkerID            string   `json:"workerId"`
	Hostname            string   `json:"hostname"`
	SupportedOperations []string `json:"supportedOperations"`
	SupportedCodecs     []string `json:"supportedCodecs"`
	CPUArchitecture     string   `json:"cpuArchitecture"`
	CPUCores            int      `json:"cpuCores"`
	MemoryBytes         int64    `json:"memoryBytes"`
	FFmpegVersion       string   `json:"ffmpegVersion,omitempty"`
}

type RegisterWorkerResponse struct {
	WorkerID      string    `json:"workerId"`
	Status        string    `json:"status"`
	LastHeartbeat time.Time `json:"lastHeartbeat"`
	RegisteredAt  time.Time `json:"registeredAt"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

type HeartbeatResponse struct {
	WorkerID      string    `json:"workerId"`
	Status        string    `json:"status"`
	LastHeartbeat time.Time `json:"lastHeartbeat"`
}

type MetadataResult struct {
	DurationSeconds *float64 `json:"durationSeconds,omitempty"`
	FormatName      *string  `json:"formatName,omitempty"`
	SizeBytes       *int64   `json:"sizeBytes,omitempty"`
	VideoCodec      *string  `json:"videoCodec,omitempty"`
	AudioCodec      *string  `json:"audioCodec,omitempty"`
	Width           *int     `json:"width,omitempty"`
	Height          *int     `json:"height,omitempty"`
	FrameRate       *float64 `json:"frameRate,omitempty"`
}

type ArtifactResult struct {
	ObjectURI   string `json:"objectUri"`
	ContentType string `json:"contentType"`
	SizeBytes   int64  `json:"sizeBytes"`
	Checksum    string `json:"checksum"`
}

type CompleteRequest struct {
	AttemptID       string          `json:"attemptId"`
	ActualRuntimeMs int64           `json:"actualRuntimeMs"`
	Metadata        *MetadataResult `json:"metadata,omitempty"`
	Artifact        *ArtifactResult `json:"artifact,omitempty"`
}

type FailRequest struct {
	AttemptID       string `json:"attemptId"`
	ActualRuntimeMs *int64 `json:"actualRuntimeMs,omitempty"`
	Reason          string `json:"reason"`
}

type CancelledRequest struct {
	WorkerID        string `json:"workerId"`
	ActualRuntimeMs int64  `json:"actualRuntimeMs"`
}

type StartRequest struct {
	WorkerID     string `json:"workerId"`
	AssignmentID string `json:"assignmentId,omitempty"`
}

type StartResponse struct {
	Outcome        string     `json:"outcome"`
	OperationID    string     `json:"operationId"`
	JobID          string     `json:"jobId"`
	Type           string     `json:"type"`
	InputURI       string     `json:"inputUri"`
	Status         string     `json:"status"`
	AttemptID      string     `json:"attemptId"`
	WorkerID       string     `json:"workerId"`
	LeaseExpiresAt *time.Time `json:"leaseExpiresAt"`
}

type RenewRequest struct {
	WorkerID string `json:"workerId"`
}

type RenewResponse struct {
	AttemptID        string     `json:"attemptId"`
	WorkerID         string     `json:"workerId"`
	Status           string     `json:"status"`
	LeaseExpiresAt   *time.Time `json:"leaseExpiresAt"`
	CancelRequested  bool       `json:"cancelRequested"`
}

const (
	StartStarted         = "STARTED"
	StartAlreadyRunning  = "ALREADY_RUNNING"
	StartAlreadyTerminal = "ALREADY_TERMINAL"
	StartInvalidState    = "INVALID_STATE"
)
