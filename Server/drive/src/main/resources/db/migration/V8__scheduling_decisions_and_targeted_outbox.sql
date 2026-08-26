-- Phase 4A: durable scheduling decisions and worker-targeted outbox routing.
-- Go scheduler chooses placement; the control service commits this row
-- together with Operation QUEUED -> ASSIGNED and a targeted outbox message.
-- No scores, runtime estimates, or adaptive fields.

CREATE TABLE scheduling_decisions (
    id            UUID PRIMARY KEY,
    operation_id  UUID NOT NULL REFERENCES operations (id) ON DELETE CASCADE,
    worker_id     VARCHAR(64) NOT NULL,
    policy        VARCHAR(32) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_scheduling_decisions_operation_created
    ON scheduling_decisions (operation_id, created_at);

CREATE INDEX idx_scheduling_decisions_created_at
    ON scheduling_decisions (created_at);

-- Legacy Java enqueue rows keep the shared-queue routing key.
-- Phase 4A assignments store worker.{workerId} and the selected worker_id.
ALTER TABLE dispatch_outbox
    ADD COLUMN routing_key VARCHAR(128) NOT NULL DEFAULT 'operation.execute',
    ADD COLUMN worker_id VARCHAR(64);
