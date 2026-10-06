-- Stop the matching executor first. Rolling back loses the compatibility gate for legacy rejections.
ALTER TABLE integration.workflow_log_stream_batch DROP CONSTRAINT workflow_log_rejection_known;
ALTER TABLE integration.workflow_log_stream_batch DROP COLUMN rejection_known;
