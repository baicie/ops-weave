-- Only after deployment review. Original immutable observations are preserved.
ALTER TABLE integration.workflow_diagnostic DROP COLUMN history_sequence;
