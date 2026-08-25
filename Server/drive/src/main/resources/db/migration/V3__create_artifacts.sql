CREATE TABLE artifacts (
    id              UUID PRIMARY KEY,
    job_id          UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    operation_id    UUID NOT NULL REFERENCES operations (id) ON DELETE CASCADE,
    artifact_type   VARCHAR(32) NOT NULL,
    object_uri      TEXT NOT NULL,
    content_type    VARCHAR(128) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    checksum        VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT artifacts_operation_unique UNIQUE (operation_id),
    CONSTRAINT artifacts_type_check CHECK (artifact_type IN ('THUMBNAIL')),
    CONSTRAINT artifacts_size_non_negative CHECK (size_bytes >= 0),
    CONSTRAINT artifacts_object_uri_not_blank CHECK (btrim(object_uri) <> ''),
    CONSTRAINT artifacts_content_type_not_blank CHECK (btrim(content_type) <> ''),
    CONSTRAINT artifacts_checksum_not_blank CHECK (btrim(checksum) <> '')
);

CREATE INDEX idx_artifacts_job_id ON artifacts (job_id);

-- Claim path: oldest queued METADATA or THUMBNAIL operation.
CREATE INDEX idx_operations_claim_executable
    ON operations (created_at, operation_order)
    WHERE status = 'QUEUED' AND operation_type IN ('METADATA', 'THUMBNAIL');
