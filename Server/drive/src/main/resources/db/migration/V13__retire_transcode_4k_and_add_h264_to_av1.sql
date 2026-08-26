-- Phase 4D.3: retire TRANSCODE_4K_TO_1080P and make H264_TO_AV1 an executable artifact type.
--
-- TRANSCODE_4K_TO_1080P was never executable. Leftover QUEUED/ASSIGNED/RUNNING rows
-- would stay queued forever after the public API stops accepting the type.
-- Do not delete jobs. Do not rewrite leftover work into a runnable TRANSCODE_1080P
-- encode (that would change product behavior of already-submitted requests).
-- Mark non-terminal rows FAILED with a retirement reason, recompute job status,
-- then change the type column so the CHECK and Java enum can drop the retired value.

DELETE FROM dispatch_outbox
WHERE operation_id IN (
    SELECT id FROM operations WHERE operation_type = 'TRANSCODE_4K_TO_1080P'
);

DELETE FROM worker_supported_operations
WHERE operation_type = 'TRANSCODE_4K_TO_1080P';

UPDATE operations
SET
    status = 'FAILED',
    completed_at = COALESCE(completed_at, NOW()),
    failure_reason = 'operation type retired; submit TRANSCODE_1080P for 1080p H.264 output',
    assigned_worker_id = NULL,
    current_assignment_id = NULL,
    assigned_at = NULL,
    current_attempt_id = NULL,
    updated_at = NOW()
WHERE operation_type = 'TRANSCODE_4K_TO_1080P'
  AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');

UPDATE jobs j
SET
    status = sub.next_status,
    updated_at = NOW()
FROM (
    SELECT
        o.job_id,
        CASE
            WHEN bool_or(o.status = 'FAILED') THEN 'FAILED'
            WHEN NOT bool_or(o.status IN ('QUEUED', 'ASSIGNED', 'RUNNING'))
                 AND bool_or(o.status = 'COMPLETED') THEN 'COMPLETED'
            WHEN bool_or(o.status IN ('RUNNING', 'COMPLETED')) THEN 'RUNNING'
            WHEN bool_or(o.status = 'ASSIGNED') THEN 'ASSIGNED'
            ELSE 'QUEUED'
        END AS next_status
    FROM operations o
    WHERE o.job_id IN (
        SELECT DISTINCT job_id
        FROM operations
        WHERE operation_type = 'TRANSCODE_4K_TO_1080P'
    )
    GROUP BY o.job_id
) sub
WHERE j.id = sub.job_id
  AND j.status <> 'CANCELLED';

-- Persistence compatibility only: leftover rows are already terminal. They are not
-- executed as TRANSCODE_1080P. The type is rewritten so Hibernate and CHECKs can
-- drop TRANSCODE_4K_TO_1080P.
UPDATE operations
SET operation_type = 'TRANSCODE_1080P',
    updated_at = NOW()
WHERE operation_type = 'TRANSCODE_4K_TO_1080P';

ALTER TABLE operations DROP CONSTRAINT operations_type_check;
ALTER TABLE operations
    ADD CONSTRAINT operations_type_check CHECK (operation_type IN (
        'METADATA',
        'THUMBNAIL',
        'AUDIO_EXTRACTION',
        'TRANSCODE_1080P',
        'H264_TO_AV1'
    ));

ALTER TABLE worker_supported_operations DROP CONSTRAINT worker_supported_operations_type_check;
ALTER TABLE worker_supported_operations
    ADD CONSTRAINT worker_supported_operations_type_check CHECK (operation_type IN (
        'METADATA',
        'THUMBNAIL',
        'AUDIO_EXTRACTION',
        'TRANSCODE_1080P',
        'H264_TO_AV1'
    ));

ALTER TABLE artifacts DROP CONSTRAINT artifacts_type_check;
ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_type_check CHECK (artifact_type IN (
        'THUMBNAIL',
        'AUDIO',
        'TRANSCODE_1080P',
        'H264_TO_AV1'
    ));

DROP INDEX IF EXISTS idx_operations_claim_executable;
CREATE INDEX idx_operations_claim_executable
    ON operations (created_at, operation_order)
    WHERE status = 'QUEUED' AND operation_type IN (
        'METADATA', 'THUMBNAIL', 'AUDIO_EXTRACTION', 'TRANSCODE_1080P', 'H264_TO_AV1'
    );

DROP INDEX IF EXISTS idx_operations_dispatch_queued;
CREATE INDEX idx_operations_dispatch_queued
    ON operations (created_at, operation_order)
    WHERE status = 'QUEUED' AND operation_type IN (
        'METADATA', 'THUMBNAIL', 'AUDIO_EXTRACTION', 'TRANSCODE_1080P', 'H264_TO_AV1'
    );
