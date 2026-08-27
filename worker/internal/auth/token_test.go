package auth

import "testing"

func TestRequireWorkerToken(t *testing.T) {
	if err := RequireWorkerToken("worker-a", ""); err == nil || err.Error() != "WORKER_SERVICE_TOKEN is required" {
		t.Fatalf("err=%v", err)
	}
	token := Issue("test-worker-pepper", "worker-a")
	if err := RequireWorkerToken("worker-a", token); err != nil {
		t.Fatal(err)
	}
	if err := RequireWorkerToken("worker-b", token); err == nil {
		t.Fatal("expected mismatch")
	}
	if err := RequireWorkerToken("worker-a", "not-a-worker-token"); err == nil {
		t.Fatal("expected invalid credential")
	}
}

func TestSubject(t *testing.T) {
	token := Issue("pepper", "worker-a")
	subject, ok := Subject(token)
	if !ok || subject != "worker-a" {
		t.Fatalf("subject=%s ok=%t", subject, ok)
	}
}
