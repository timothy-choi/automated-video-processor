package auth

import "testing"

func TestRequireToken(t *testing.T) {
	if _, err := RequireToken(""); err == nil {
		t.Fatal("expected missing token error")
	}
	if _, err := RequireToken("   "); err == nil {
		t.Fatal("expected missing token error")
	}
	got, err := RequireToken(" test-scheduler-token ")
	if err != nil {
		t.Fatal(err)
	}
	if got != "test-scheduler-token" {
		t.Fatalf("got=%q", got)
	}
}
