-- Phase 6A: persist W3C trace context with the transactional outbox so
-- RabbitMQ publish can continue the assign-time trace. Not part of the
-- assignment JSON contract.

ALTER TABLE dispatch_outbox
    ADD COLUMN traceparent VARCHAR(128),
    ADD COLUMN tracestate VARCHAR(512);
