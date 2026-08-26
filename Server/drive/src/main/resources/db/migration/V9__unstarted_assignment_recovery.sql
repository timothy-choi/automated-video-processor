-- Phase 4A stabilization: unstarted ASSIGNED recovery.
-- assigned_at is the assignment clock (not updated_at).
-- current_assignment_id is SchedulingDecision.id for the live placement.
-- assigned_worker_id is the worker that placement targeted.

ALTER TABLE operations
    ADD COLUMN assigned_at TIMESTAMPTZ,
    ADD COLUMN assigned_worker_id VARCHAR(64),
    ADD COLUMN current_assignment_id UUID;

CREATE INDEX idx_operations_assigned_unstarted
    ON operations (assigned_at)
    WHERE status = 'ASSIGNED' AND current_attempt_id IS NULL;
