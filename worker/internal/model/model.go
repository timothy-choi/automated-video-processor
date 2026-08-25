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

type CompleteRequest struct {
	ActualRuntimeMs int64          `json:"actualRuntimeMs"`
	Result          MetadataResult `json:"result"`
}

type FailRequest struct {
	ActualRuntimeMs *int64 `json:"actualRuntimeMs,omitempty"`
	Reason          string `json:"reason"`
}
