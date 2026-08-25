package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
)

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

func (c *Client) Complete(ctx context.Context, operationID string, runtimeMs int64, result model.MetadataResult) error {
	payload, err := json.Marshal(model.CompleteRequest{
		ActualRuntimeMs: runtimeMs,
		Result:          result,
	})
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
		return fmt.Errorf("request %s failed: status %d: %s", path, resp.StatusCode, truncate(string(body), 500))
	}
	return nil
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
}
