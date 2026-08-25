CREATE TABLE jobs (
    id              UUID PRIMARY KEY,
    input_uri       TEXT NOT NULL,
    status          VARCHAR(32) NOT NULL,
    priority        VARCHAR(16) NOT NULL,
    deadline        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT jobs_input_uri_not_blank CHECK (btrim(input_uri) <> ''),
    CONSTRAINT jobs_status_check CHECK (status IN (
        'QUEUED', 'ASSIGNED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'
    )),
    CONSTRAINT jobs_priority_check CHECK (priority IN ('LOW', 'NORMAL', 'HIGH'))
);

CREATE TABLE operations (
    id               UUID PRIMARY KEY,
    job_id           UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    operation_type   VARCHAR(64) NOT NULL,
    status           VARCHAR(32) NOT NULL,
    operation_order  INTEGER NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT operations_job_order_unique UNIQUE (job_id, operation_order),
    CONSTRAINT operations_order_non_negative CHECK (operation_order >= 0),
    CONSTRAINT operations_type_check CHECK (operation_type IN (
        'METADATA',
        'THUMBNAIL',
        'AUDIO_EXTRACTION',
        'TRANSCODE_1080P',
        'TRANSCODE_4K_TO_1080P',
        'H264_TO_AV1'
    )),
    CONSTRAINT operations_status_check CHECK (status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'
    ))
);

-- Future scheduler queries will filter queued work by status.
CREATE INDEX idx_jobs_status ON jobs (status);

-- Lookups of operations by job (GET /jobs/{id}/operations).
CREATE INDEX idx_operations_job_id ON operations (job_id);
