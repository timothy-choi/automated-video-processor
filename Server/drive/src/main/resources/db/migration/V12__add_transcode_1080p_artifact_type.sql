ALTER TABLE artifacts DROP CONSTRAINT artifacts_type_check;
ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_type_check CHECK (artifact_type IN ('THUMBNAIL', 'AUDIO', 'TRANSCODE_1080P'));

DROP INDEX IF EXISTS idx_operations_claim_executable;
CREATE INDEX idx_operations_claim_executable
    ON operations (created_at, operation_order)
    WHERE status = 'QUEUED' AND operation_type IN ('METADATA', 'THUMBNAIL', 'AUDIO_EXTRACTION', 'TRANSCODE_1080P');

DROP INDEX IF EXISTS idx_operations_dispatch_queued;
CREATE INDEX idx_operations_dispatch_queued
    ON operations (created_at, operation_order)
    WHERE status = 'QUEUED' AND operation_type IN ('METADATA', 'THUMBNAIL', 'AUDIO_EXTRACTION', 'TRANSCODE_1080P');
