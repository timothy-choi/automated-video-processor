-- Phase 6D: Account-owned input media. Artifacts remain outputs.
-- jobs.media_asset_id is nullable so legacy/raw inputUri Jobs stay valid.
-- Existing Jobs are backfilled as NULL. Do not cascade-delete assets.

CREATE TABLE media_assets (
    id                 UUID PRIMARY KEY,
    account_id         UUID NOT NULL REFERENCES accounts (id),
    status             VARCHAR(32) NOT NULL,
    original_filename  VARCHAR(255) NOT NULL,
    content_type       VARCHAR(255),
    size_bytes         BIGINT,
    bucket             VARCHAR(255) NOT NULL,
    object_key         VARCHAR(1024) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT media_assets_status_check
        CHECK (status IN ('PENDING_UPLOAD', 'READY', 'FAILED')),
    CONSTRAINT media_assets_original_filename_not_blank
        CHECK (btrim(original_filename) <> ''),
    CONSTRAINT media_assets_bucket_not_blank
        CHECK (btrim(bucket) <> ''),
    CONSTRAINT media_assets_object_key_not_blank
        CHECK (btrim(object_key) <> ''),
    CONSTRAINT media_assets_size_non_negative
        CHECK (size_bytes IS NULL OR size_bytes >= 0)
);

CREATE UNIQUE INDEX idx_media_assets_bucket_object_key
    ON media_assets (bucket, object_key);

CREATE INDEX idx_media_assets_account_created_at_id
    ON media_assets (account_id, created_at DESC, id DESC);

CREATE INDEX idx_media_assets_account_status_created_at_id
    ON media_assets (account_id, status, created_at DESC, id DESC);

ALTER TABLE jobs
    ADD COLUMN media_asset_id UUID REFERENCES media_assets (id);

CREATE INDEX idx_jobs_media_asset_id ON jobs (media_asset_id);
