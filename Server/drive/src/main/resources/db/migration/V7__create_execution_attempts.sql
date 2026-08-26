-- Execution attempts: one concrete worker execution of a logical Operation.
-- Attempt history is append-only. Operation.current_attempt_id is the active owner
-- while RUNNING; it is cleared when the operation is requeued after interruption.
-- No FK from operations.current_attempt_id to execution_attempts to avoid a
-- circular insert when the attempt row is created.

CREATE TABLE execution_attempts (
    id                 UUID PRIMARY KEY,
    operation_id       UUID NOT NULL REFERENCES operations (id) ON DELETE CASCADE,
    worker_id          VARCHAR(64) NOT NULL REFERENCES workers (id),
    status             VARCHAR(32) NOT NULL,
    attempt_number     INTEGER NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    started_at         TIMESTAMPTZ NOT NULL,
    ended_at           TIMESTAMPTZ,
    lease_expires_at   TIMESTAMPTZ NOT NULL,
    actual_runtime_ms  BIGINT,
    failure_reason     TEXT,
    CONSTRAINT execution_attempts_status_check CHECK (status IN (
        'ASSIGNED',
        'RUNNING',
        'COMPLETED',
        'FAILED',
        'INTERRUPTED'
    )),
    CONSTRAINT execution_attempts_number_positive CHECK (attempt_number > 0),
    CONSTRAINT execution_attempts_operation_number_unique UNIQUE (operation_id, attempt_number)
);

CREATE INDEX idx_execution_attempts_operation_id ON execution_attempts (operation_id);
CREATE INDEX idx_execution_attempts_worker_id ON execution_attempts (worker_id);
CREATE INDEX idx_execution_attempts_running_lease
    ON execution_attempts (lease_expires_at)
    WHERE status = 'RUNNING';

CREATE UNIQUE INDEX execution_attempts_one_running_per_operation
    ON execution_attempts (operation_id)
    WHERE status = 'RUNNING';

ALTER TABLE operations ADD COLUMN current_attempt_id UUID;
