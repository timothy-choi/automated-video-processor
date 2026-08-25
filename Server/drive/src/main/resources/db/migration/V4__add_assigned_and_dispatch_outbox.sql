ALTER TABLE operations DROP CONSTRAINT operations_status_check;
ALTER TABLE operations
    ADD CONSTRAINT operations_status_check CHECK (status IN (
        'QUEUED', 'ASSIGNED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'
    ));

CREATE TABLE dispatch_outbox (
    id               UUID PRIMARY KEY,
    operation_id     UUID NOT NULL REFERENCES operations (id) ON DELETE CASCADE,
    payload_json     JSONB NOT NULL,
    status           VARCHAR(16) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    sent_at          TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT dispatch_outbox_operation_unique UNIQUE (operation_id),
    CONSTRAINT dispatch_outbox_status_check CHECK (status IN ('PENDING', 'SENT')),
    CONSTRAINT dispatch_outbox_attempts_non_negative CHECK (publish_attempts >= 0)
);

CREATE INDEX idx_dispatch_outbox_pending
    ON dispatch_outbox (created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_operations_dispatch_queued
    ON operations (created_at, operation_order)
    WHERE status = 'QUEUED' AND operation_type IN ('METADATA', 'THUMBNAIL');
