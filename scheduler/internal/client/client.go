package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

type StatusError struct {
	Status int
	Body   string
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("control service status %d: %s", e.Status, truncate(e.Body, 500))
}

func IsConflict(err error) bool {
	var statusErr *StatusError
	return errors.As(err, &statusErr) && statusErr.Status == http.StatusConflict
}

type Client struct {
	baseURL    string
	httpClient *http.Client
}

func New(baseURL string, timeout time.Duration) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		httpClient: &http.Client{
			Timeout: timeout,
		},
	}
}

func (c *Client) Snapshot(ctx context.Context) (model.Snapshot, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/internal/scheduler/snapshot", nil)
	if err != nil {
		return model.Snapshot{}, err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return model.Snapshot{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return model.Snapshot{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return model.Snapshot{}, &StatusError{Status: resp.StatusCode, Body: string(body)}
	}
	var snapshot model.Snapshot
	if err := json.Unmarshal(body, &snapshot); err != nil {
		return model.Snapshot{}, fmt.Errorf("snapshot response is invalid JSON: %w", err)
	}
	return snapshot, nil
}

func (c *Client) Assign(ctx context.Context, placement model.Placement) (model.AssignResponse, error) {
	payload, err := json.Marshal(model.AssignRequest{
		OperationID: placement.OperationID,
		WorkerID:    placement.WorkerID,
		Policy:      placement.Policy,
	})
	if err != nil {
		return model.AssignResponse{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/internal/scheduler/assign", bytes.NewReader(payload))
	if err != nil {
		return model.AssignResponse{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return model.AssignResponse{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return model.AssignResponse{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return model.AssignResponse{}, &StatusError{Status: resp.StatusCode, Body: string(body)}
	}
	var assigned model.AssignResponse
	if err := json.Unmarshal(body, &assigned); err != nil {
		return model.AssignResponse{}, fmt.Errorf("assign response is invalid JSON: %w", err)
	}
	return assigned, nil
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max] + "..."
}
