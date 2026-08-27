-- Phase 5E: Account principals, hashed API keys, and Job ownership.
-- Existing Jobs are assigned to a durable legacy-system Account so historical
-- work remains queryable. jobs.account_id is then NOT NULL.

CREATE TABLE accounts (
    id          UUID PRIMARY KEY,
    name        TEXT NOT NULL,
    status      VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT accounts_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE'))
);

CREATE TABLE api_keys (
    id          UUID PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES accounts (id),
    key_prefix  VARCHAR(32) NOT NULL,
    key_hash    VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    CONSTRAINT api_keys_key_hash_unique UNIQUE (key_hash),
    CONSTRAINT api_keys_prefix_not_blank CHECK (btrim(key_prefix) <> ''),
    CONSTRAINT api_keys_hash_sha256 CHECK (key_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_api_keys_account_id ON api_keys (account_id);

-- Stable well-known principal for Jobs that existed before ownership.
INSERT INTO accounts (id, name, status, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'legacy-system',
    'ACTIVE',
    TIMESTAMPTZ '2020-01-01 00:00:00+00'
);

ALTER TABLE jobs
    ADD COLUMN account_id UUID REFERENCES accounts (id);

UPDATE jobs
SET account_id = '00000000-0000-0000-0000-000000000001'
WHERE account_id IS NULL;

ALTER TABLE jobs
    ALTER COLUMN account_id SET NOT NULL;

CREATE INDEX idx_jobs_account_id ON jobs (account_id);

-- Owner-scoped GET /jobs: WHERE account_id = ? ORDER BY created_at DESC, id DESC
CREATE INDEX idx_jobs_account_id_created_at_id ON jobs (account_id, created_at DESC, id DESC);

-- Owner-scoped status filter + newest-first sort
CREATE INDEX idx_jobs_account_id_status_created_at_id ON jobs (account_id, status, created_at DESC, id DESC);
