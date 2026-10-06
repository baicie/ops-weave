-- Rollback refuses any existing proof exceeding the original bound. Never discard proofs.
BEGIN;
ALTER TABLE integration.workflow_metric_stream_batch
    DROP CONSTRAINT workflow_metric_stream_batch_body_check;
ALTER TABLE integration.workflow_metric_stream_batch
    ADD CONSTRAINT workflow_metric_stream_batch_body_check
    CHECK (octet_length(body::text)<=8192);
COMMIT;
