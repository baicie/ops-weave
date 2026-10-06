-- Keep legacy result bodies immutable; they cannot establish that a write was never attempted.
ALTER TABLE integration.workflow_log_stream_batch ADD COLUMN rejection_known boolean NOT NULL DEFAULT false;
ALTER TABLE integration.workflow_log_stream_batch ADD CONSTRAINT workflow_log_rejection_known
    CHECK(NOT rejection_known OR (body->>'state'='FAILED' AND body->>'error'='OUTPUT_REJECTED'));
