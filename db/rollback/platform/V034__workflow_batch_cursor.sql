BEGIN;
DROP INDEX IF EXISTS integration.sync_run_workflow_completion;
DELETE FROM integration.schema_migration WHERE id='V034__workflow_batch_cursor';
COMMIT;
