ALTER TABLE integration.source_sync_run
    ADD COLUMN source_id uuid,
    ADD COLUMN source_configuration_revision smallint,
    ADD COLUMN source_connection_digest varchar(71),
    ADD COLUMN source_scope_digest varchar(71),
    ADD COLUMN retired integer NOT NULL DEFAULT 0 CHECK (retired >= 0),
    ADD CONSTRAINT source_sync_run_registered_scope_check CHECK (
        (source_id IS NULL AND source_configuration_revision IS NULL
            AND source_connection_digest IS NULL AND source_scope_digest IS NULL)
        OR
        (source_id IS NOT NULL AND source_configuration_revision BETWEEN 1 AND 100
            AND source_connection_digest ~ '^sha256:[a-f0-9]{64}$'
            AND source_scope_digest ~ '^sha256:[a-f0-9]{64}$')
    );

CREATE INDEX source_sync_run_registered_scope
    ON integration.source_sync_run (tenant_id, source_id, source_configuration_revision, started_at DESC, id DESC)
    WHERE source_id IS NOT NULL;
