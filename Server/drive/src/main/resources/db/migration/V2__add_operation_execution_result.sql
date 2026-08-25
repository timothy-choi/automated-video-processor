ALTER TABLE operations
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN actual_runtime_ms BIGINT,
    ADD COLUMN failure_reason TEXT,
    ADD COLUMN result_json JSONB;

ALTER TABLE operations
    ADD CONSTRAINT operations_runtime_non_negative
        CHECK (actual_runtime_ms IS NULL OR actual_runtime_ms >= 0);

-- Claim path: oldest queued METADATA operation.
CREATE INDEX idx_operations_claim_metadata
    ON operations (created_at, operation_order)
    WHERE status = 'QUEUED' AND operation_type = 'METADATA';
