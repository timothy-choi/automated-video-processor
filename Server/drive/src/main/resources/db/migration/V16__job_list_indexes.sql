-- Phase 5C: indexes for GET /jobs listing (filter + deterministic sort).
-- idx_jobs_status and idx_operations_job_id already exist from V1.
-- idx_artifacts_job_id already exists from V3.

-- Unfiltered list: ORDER BY created_at DESC, id DESC
CREATE INDEX idx_jobs_created_at_id ON jobs (created_at DESC, id DESC);

-- Common filter: WHERE status = ? ORDER BY created_at DESC, id DESC
CREATE INDEX idx_jobs_status_created_at_id ON jobs (status, created_at DESC, id DESC);

-- EXISTS (SELECT 1 FROM operations WHERE job_id = jobs.id AND operation_type = ?)
CREATE INDEX idx_operations_job_id_type ON operations (job_id, operation_type);
