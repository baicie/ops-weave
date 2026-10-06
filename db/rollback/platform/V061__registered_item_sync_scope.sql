DROP INDEX IF EXISTS integration.source_sync_run_registered_scope;

ALTER TABLE integration.source_sync_run
    DROP CONSTRAINT IF EXISTS source_sync_run_registered_scope_check,
    DROP COLUMN IF EXISTS retired,
    DROP COLUMN IF EXISTS source_scope_digest,
    DROP COLUMN IF EXISTS source_connection_digest,
    DROP COLUMN IF EXISTS source_configuration_revision,
    DROP COLUMN IF EXISTS source_id;
