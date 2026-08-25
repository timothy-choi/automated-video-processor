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

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
)

type StatusError struct {
	Status int
	Body   string
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("control service status %d: %s", e.Status, truncate(e.Body, 500))
}

func IsUnavailable(err error) bool {
	if err == nil {
		return false
	}
	var statusErr *StatusError
	if errors.As(err, &statusErr) {
		return statusErr.Status >= 500 || statusErr.Status == 0
	}
	return true
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

func (c *Client) Claim(ctx context.Context) (*model.ClaimedOperation, bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/internal/operations/claim", nil)
	if err != nil {
		return nil, false, err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, false, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, false, err
	}

	if resp.StatusCode == http.StatusNoContent {
		return nil, false, nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil, false, fmt.Errorf("claim failed: status %d: %s", resp.StatusCode, truncate(string(body), 500))
	}

	var claimed model.ClaimedOperation
	if err := json.Unmarshal(body, &claimed); err != nil {
		return nil, false, fmt.Errorf("claim response is invalid JSON: %w", err)
	}
	if claimed.OperationID == "" {
		return nil, false, fmt.Errorf("claim response is missing operationId")
	}
	return &claimed, true, nil
}

func (c *Client) Start(ctx context.Context, operationID string) (model.StartResponse, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/internal/operations/"+operationID+"/start", nil)
	if err != nil {
		return model.StartResponse{}, err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return model.StartResponse{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return model.StartResponse{}, err
	}
	if resp.StatusCode == http.StatusNotFound {
		return model.StartResponse{}, &StatusError{Status: resp.StatusCode, Body: string(body)}
	}
	if resp.StatusCode != http.StatusOK {
		return model.StartResponse{}, &StatusError{Status: resp.StatusCode, Body: string(body)}
	}
	var started model.StartResponse
	if err := json.Unmarshal(body, &started); err != nil {
		return model.StartResponse{}, fmt.Errorf("start response is invalid JSON: %w", err)
	}
	if started.Outcome == "" {
		return model.StartResponse{}, fmt.Errorf("start response is missing outcome")
	}
	return started, nil
}

func (c *Client) Complete(ctx context.Context, operationID string, request model.CompleteRequest) error {
	payload, err := json.Marshal(request)
	if err != nil {
		return err
	}
	return c.postJSON(ctx, "/internal/operations/"+operationID+"/complete", payload)
}

func (c *Client) Fail(ctx context.Context, operationID string, runtimeMs *int64, reason string) error {
	payload, err := json.Marshal(model.FailRequest{
		ActualRuntimeMs: runtimeMs,
		Reason:          reason,
	})
	if err != nil {
		return err
	}
	return c.postJSON(ctx, "/internal/operations/"+operationID+"/fail", payload)
}

func (c *Client) postJSON(ctx context.Context, path string, payload []byte) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &StatusError{Status: resp.StatusCode, Body: string(body)}
	}
	return nil
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
}
