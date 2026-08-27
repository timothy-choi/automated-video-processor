-- Phase 5B: FIFO uses current queue-entry time, not logical Operation age.
-- queued_at is set on initial submit, explicit user retry, assignment-timeout
-- recovery, and lease-interruption requeue.

ALTER TABLE operations
    ADD COLUMN queued_at TIMESTAMPTZ;

UPDATE operations
SET queued_at = created_at
WHERE queued_at IS NULL;

ALTER TABLE operations
    ALTER COLUMN queued_at SET NOT NULL;

CREATE INDEX idx_operations_status_queued_at ON operations (status, queued_at);
