-- Phase 5A: job/operation cancellation.
-- CANCEL_REQUESTED means a RUNNING operation has user cancellation intent but
-- the worker has not yet terminated FFmpeg/ffprobe. Attempt CANCELLED is the
-- terminal attempt status after coordinated (or lease-finalized) cancellation.

ALTER TABLE operations DROP CONSTRAINT operations_status_check;
ALTER TABLE operations
    ADD CONSTRAINT operations_status_check CHECK (status IN (
        'QUEUED',
        'ASSIGNED',
        'RUNNING',
        'CANCEL_REQUESTED',
        'COMPLETED',
        'FAILED',
        'CANCELLED'
    ));

ALTER TABLE jobs DROP CONSTRAINT jobs_status_check;
ALTER TABLE jobs
    ADD CONSTRAINT jobs_status_check CHECK (status IN (
        'QUEUED',
        'ASSIGNED',
        'RUNNING',
        'CANCEL_REQUESTED',
        'COMPLETED',
        'FAILED',
        'CANCELLED',
        'INTERRUPTED'
    ));

ALTER TABLE execution_attempts DROP CONSTRAINT execution_attempts_status_check;
ALTER TABLE execution_attempts
    ADD CONSTRAINT execution_attempts_status_check CHECK (status IN (
        'ASSIGNED',
        'RUNNING',
        'COMPLETED',
        'FAILED',
        'INTERRUPTED',
        'CANCELLED'
    ));
