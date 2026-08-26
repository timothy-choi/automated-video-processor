-- Phase 4B: record operation ordering vs worker placement separately.
-- Existing rows were FIFO operation order + lexicographic worker placement.
-- Do not edit V8/V9.

ALTER TABLE scheduling_decisions
    ADD COLUMN operation_policy VARCHAR(32),
    ADD COLUMN worker_policy VARCHAR(32);

UPDATE scheduling_decisions
SET operation_policy = 'FIFO',
    worker_policy = 'LEXICOGRAPHIC'
WHERE operation_policy IS NULL;

ALTER TABLE scheduling_decisions
    ALTER COLUMN operation_policy SET NOT NULL,
    ALTER COLUMN worker_policy SET NOT NULL;

CREATE INDEX idx_scheduling_decisions_rr_history
    ON scheduling_decisions (worker_policy, created_at DESC, id DESC);
