package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
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

func New(baseURL string, timeout time.Duration, serviceToken string) *Client {
	transport := http.DefaultTransport
	if strings.TrimSpace(serviceToken) != "" {
		transport = &bearerTransport{base: http.DefaultTransport, token: strings.TrimSpace(serviceToken)}
	}
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		httpClient: &http.Client{
			Timeout:   timeout,
			Transport: transport,
		},
	}
}

func IsUnauthorized(err error) bool {
	var statusErr *StatusError
	return errors.As(err, &statusErr) && (statusErr.Status == http.StatusUnauthorized || statusErr.Status == http.StatusForbidden)
}

type bearerTransport struct {
	base  http.RoundTripper
	token string
}

func (t *bearerTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	cloned := req.Clone(req.Context())
	cloned.Header.Set("Authorization", "Bearer "+t.token)
	base := t.base
	if base == nil {
		base = http.DefaultTransport
	}
	return base.RoundTrip(cloned)
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

func (c *Client) Start(ctx context.Context, operationID, workerID, assignmentID string) (model.StartResponse, error) {
	payload, err := json.Marshal(model.StartRequest{WorkerID: workerID, AssignmentID: assignmentID})
	if err != nil {
		return model.StartResponse{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/internal/operations/"+operationID+"/start", bytes.NewReader(payload))
	if err != nil {
		return model.StartResponse{}, err
	}
	req.Header.Set("Content-Type", "application/json")
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

func (c *Client) Renew(ctx context.Context, operationID, attemptID, workerID string) (model.RenewResponse, error) {
	payload, err := json.Marshal(model.RenewRequest{WorkerID: workerID})
	if err != nil {
		return model.RenewResponse{}, err
	}
	path := "/internal/operations/" + operationID + "/attempts/" + attemptID + "/renew"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(payload))
	if err != nil {
		return model.RenewResponse{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return model.RenewResponse{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return model.RenewResponse{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return model.RenewResponse{}, &StatusError{Status: resp.StatusCode, Body: string(body)}
	}
	var renewed model.RenewResponse
	if err := json.Unmarshal(body, &renewed); err != nil {
		return model.RenewResponse{}, fmt.Errorf("renew response is invalid JSON: %w", err)
	}
	if renewed.AttemptID == "" {
		return model.RenewResponse{}, fmt.Errorf("renew response is missing attemptId")
	}
	return renewed, nil
}

func (c *Client) Complete(ctx context.Context, operationID string, request model.CompleteRequest) error {
	payload, err := json.Marshal(request)
	if err != nil {
		return err
	}
	return c.postJSON(ctx, "/internal/operations/"+operationID+"/complete", payload)
}

func (c *Client) Fail(ctx context.Context, operationID string, runtimeMs *int64, reason, attemptID string) error {
	payload, err := json.Marshal(model.FailRequest{
		AttemptID:       attemptID,
		ActualRuntimeMs: runtimeMs,
		Reason:          reason,
	})
	if err != nil {
		return err
	}
	return c.postJSON(ctx, "/internal/operations/"+operationID+"/fail", payload)
}

func (c *Client) Cancelled(ctx context.Context, operationID, attemptID, workerID string, runtimeMs int64) error {
	payload, err := json.Marshal(model.CancelledRequest{
		WorkerID:        workerID,
		ActualRuntimeMs: runtimeMs,
	})
	if err != nil {
		return err
	}
	path := "/internal/operations/" + operationID + "/attempts/" + attemptID + "/cancelled"
	return c.postJSON(ctx, path, payload)
}

func (c *Client) RegisterWorker(ctx context.Context, request model.RegisterWorkerRequest) (model.RegisterWorkerResponse, error) {
	payload, err := json.Marshal(request)
	if err != nil {
		return model.RegisterWorkerResponse{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/internal/workers/register", bytes.NewReader(payload))
	if err != nil {
		return model.RegisterWorkerResponse{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return model.RegisterWorkerResponse{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return model.RegisterWorkerResponse{}, err
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return model.RegisterWorkerResponse{}, &StatusError{Status: resp.StatusCode, Body: string(body)}
	}
	var registered model.RegisterWorkerResponse
	if err := json.Unmarshal(body, &registered); err != nil {
		return model.RegisterWorkerResponse{}, fmt.Errorf("register response is invalid JSON: %w", err)
	}
	if registered.WorkerID == "" {
		return model.RegisterWorkerResponse{}, fmt.Errorf("register response is missing workerId")
	}
	return registered, nil
}

func (c *Client) Heartbeat(ctx context.Context, workerID string) (model.HeartbeatResponse, error) {
	path := "/internal/workers/" + url.PathEscape(workerID) + "/heartbeat"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, http.NoBody)
	if err != nil {
		return model.HeartbeatResponse{}, err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return model.HeartbeatResponse{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return model.HeartbeatResponse{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return model.HeartbeatResponse{}, &StatusError{Status: resp.StatusCode, Body: string(body)}
	}
	var heartbeat model.HeartbeatResponse
	if err := json.Unmarshal(body, &heartbeat); err != nil {
		return model.HeartbeatResponse{}, fmt.Errorf("heartbeat response is invalid JSON: %w", err)
	}
	if heartbeat.WorkerID == "" {
		return model.HeartbeatResponse{}, fmt.Errorf("heartbeat response is missing workerId")
	}
	return heartbeat, nil
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
