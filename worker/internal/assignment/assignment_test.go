package assignment

import "testing"

func TestParseValidMetadata(t *testing.T) {
	body := []byte(`{
		"schemaVersion": 1,
		"operationId": "11111111-1111-1111-1111-111111111111",
		"jobId": "22222222-2222-2222-2222-222222222222",
		"type": "METADATA",
		"inputUri": "s3://media-input/sample.mp4",
		"dispatchedAt": "2026-08-25T02:00:00Z",
		"attemptId": "future-field-ignored"
	}`)
	got, err := Parse(body)
	if err != nil {
		t.Fatal(err)
	}
	if got.OperationID != "11111111-1111-1111-1111-111111111111" {
		t.Fatalf("operationId=%s", got.OperationID)
	}
	if got.Type != "METADATA" || got.InputURI != "s3://media-input/sample.mp4" {
		t.Fatalf("got %+v", got)
	}
}

func TestParseMalformedJSON(t *testing.T) {
	if _, err := Parse([]byte(`{not json`)); err == nil {
		t.Fatal("expected error")
	}
}

func TestParseRejectsBadVersionAndType(t *testing.T) {
	if _, err := Parse([]byte(`{
		"schemaVersion": 2,
		"operationId": "11111111-1111-1111-1111-111111111111",
		"jobId": "22222222-2222-2222-2222-222222222222",
		"type": "METADATA",
		"inputUri": "s3://media-input/sample.mp4",
		"dispatchedAt": "2026-08-25T02:00:00Z"
	}`)); err == nil {
		t.Fatal("expected schemaVersion error")
	}
	if _, err := Parse([]byte(`{
		"schemaVersion": 1,
		"operationId": "11111111-1111-1111-1111-111111111111",
		"jobId": "22222222-2222-2222-2222-222222222222",
		"type": "TRANSCODE_1080P",
		"inputUri": "s3://media-input/sample.mp4",
		"dispatchedAt": "2026-08-25T02:00:00Z"
	}`)); err == nil {
		t.Fatal("expected unsupported type error")
	}
}

func TestParseRequiresFields(t *testing.T) {
	if _, err := Parse([]byte(`{"schemaVersion":1,"type":"METADATA","inputUri":"s3://x/y","jobId":"j","dispatchedAt":"2026-08-25T02:00:00Z"}`)); err == nil {
		t.Fatal("expected missing operationId")
	}
}
