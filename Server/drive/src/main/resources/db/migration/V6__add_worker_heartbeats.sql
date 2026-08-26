-- Phase 3C: control-plane liveness. Existing worker rows are inventory only;
-- they have not proven reachability in this process, so they become UNAVAILABLE
-- with a NULL last_heartbeat until the worker re-registers or heartbeats.
-- NULL last_heartbeat means the worker is not currently considered live.

ALTER TABLE workers DROP CONSTRAINT workers_status_check;

ALTER TABLE workers ADD COLUMN last_heartbeat TIMESTAMPTZ;

UPDATE workers
SET status = 'UNAVAILABLE',
    last_heartbeat = NULL
WHERE status = 'REGISTERED';

ALTER TABLE workers ADD CONSTRAINT workers_status_check
    CHECK (status IN ('AVAILABLE', 'UNAVAILABLE'));
