-- Operator-only rollback after stopping all history workers and exporting checkpoints.
-- This removes checkpoint metadata, causing a replay from configured initial-from on re-enable.
DROP TABLE IF EXISTS ingestion.history_checkpoint;
