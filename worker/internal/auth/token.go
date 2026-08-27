package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
)

const Prefix = "mp_wk_"

func Subject(token string) (string, bool) {
	token = strings.TrimSpace(token)
	if !strings.HasPrefix(token, Prefix) {
		return "", false
	}
	rest := strings.TrimPrefix(token, Prefix)
	separator := strings.LastIndex(rest, "_")
	if separator <= 0 || separator >= len(rest)-1 {
		return "", false
	}
	workerID := rest[:separator]
	macHex := rest[separator+1:]
	if len(macHex) != 64 {
		return "", false
	}
	for _, c := range macHex {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return "", false
		}
	}
	if workerID == "" {
		return "", false
	}
	return workerID, true
}

func RequireWorkerToken(workerID, token string) error {
	if strings.TrimSpace(token) == "" {
		return fmt.Errorf("WORKER_SERVICE_TOKEN is required")
	}
	subject, ok := Subject(token)
	if !ok {
		return fmt.Errorf("WORKER_SERVICE_TOKEN is not a worker credential")
	}
	if subject != workerID {
		return fmt.Errorf("WORKER_SERVICE_TOKEN is not bound to WORKER_ID")
	}
	return nil
}

func Issue(pepper, workerID string) string {
	mac := hmac.New(sha256.New, []byte(pepper))
	mac.Write([]byte("WORKER:" + workerID))
	return Prefix + workerID + "_" + hex.EncodeToString(mac.Sum(nil))
}
