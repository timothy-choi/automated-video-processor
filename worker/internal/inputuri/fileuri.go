package inputuri

import (
	"fmt"
	"net/url"
	"strings"
)

func PathFromFileURI(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", fmt.Errorf("input URI is empty")
	}

	parsed, err := url.Parse(trimmed)
	if err != nil {
		return "", fmt.Errorf("input URI is not valid: %w", err)
	}
	if !strings.EqualFold(parsed.Scheme, "file") {
		scheme := parsed.Scheme
		if scheme == "" {
			scheme = "(none)"
		}
		return "", fmt.Errorf("unsupported URI scheme %s; Phase 2B supports file:// only", scheme)
	}
	if parsed.Host != "" && parsed.Host != "localhost" {
		return "", fmt.Errorf("file URI host %q is not supported", parsed.Host)
	}

	path := parsed.Path
	if path == "" {
		return "", fmt.Errorf("file URI is missing a path")
	}
	return path, nil
}
