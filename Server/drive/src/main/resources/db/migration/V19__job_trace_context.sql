-- Phase 6A: persist W3C trace context on Job so later scheduler
-- placement can continue the original POST /jobs trace.

ALTER TABLE jobs
    ADD COLUMN traceparent VARCHAR(128),
    ADD COLUMN tracestate VARCHAR(512);
