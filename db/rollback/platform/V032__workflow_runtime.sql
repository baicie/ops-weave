-- Only after operators have explicitly stopped all workflow tasks and exported needed receipts.
BEGIN;
DROP TABLE IF EXISTS integration.workflow_execution;
DROP TABLE IF EXISTS integration.workflow_task;
DELETE FROM integration.schema_migration WHERE id='V032__workflow_runtime';
COMMIT;
