-- Durable worker identity and static capabilities.
-- updated_at is the latest registration upsert, not a liveness heartbeat.
CREATE TABLE workers (
    id                VARCHAR(64) PRIMARY KEY,
    hostname          VARCHAR(255) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    cpu_architecture  VARCHAR(32) NOT NULL,
    cpu_cores         INTEGER NOT NULL,
    memory_bytes      BIGINT NOT NULL,
    ffmpeg_version    VARCHAR(64),
    registered_at     TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT workers_id_format CHECK (id ~ '^[A-Za-z0-9._-]+$'),
    CONSTRAINT workers_hostname_not_blank CHECK (btrim(hostname) <> ''),
    CONSTRAINT workers_status_check CHECK (status IN ('REGISTERED')),
    CONSTRAINT workers_cpu_architecture_not_blank CHECK (btrim(cpu_architecture) <> ''),
    CONSTRAINT workers_cpu_cores_positive CHECK (cpu_cores > 0),
    CONSTRAINT workers_memory_bytes_non_negative CHECK (memory_bytes >= 0)
);

CREATE TABLE worker_supported_operations (
    worker_id        VARCHAR(64) NOT NULL REFERENCES workers (id) ON DELETE CASCADE,
    operation_type   VARCHAR(64) NOT NULL,
    PRIMARY KEY (worker_id, operation_type),
    CONSTRAINT worker_supported_operations_type_check CHECK (operation_type IN (
        'METADATA',
        'THUMBNAIL',
        'AUDIO_EXTRACTION',
        'TRANSCODE_1080P',
        'TRANSCODE_4K_TO_1080P',
        'H264_TO_AV1'
    ))
);

CREATE TABLE worker_supported_codecs (
    worker_id  VARCHAR(64) NOT NULL REFERENCES workers (id) ON DELETE CASCADE,
    codec      VARCHAR(32) NOT NULL,
    PRIMARY KEY (worker_id, codec),
    CONSTRAINT worker_supported_codecs_codec_check CHECK (codec IN (
        'h264',
        'hevc',
        'av1',
        'vp9'
    ))
);
