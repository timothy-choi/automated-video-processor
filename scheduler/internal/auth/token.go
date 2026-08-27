package auth

import (
	"fmt"
	"strings"
)

func RequireToken(raw string) (string, error) {
	token := strings.TrimSpace(raw)
	if token == "" {
		return "", fmt.Errorf("SCHEDULER_SERVICE_TOKEN is required")
	}
	return token, nil
}
