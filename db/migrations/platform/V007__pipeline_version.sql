-- Published rows are insert-only through the application; syncs pin before fetching.
CREATE TABLE IF NOT EXISTS integration.pipeline_version (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    pipeline_id varchar(64) NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    digest varchar(71) NOT NULL CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
    definition jsonb NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_instance_id, pipeline_id, revision),
    UNIQUE (tenant_id, source_instance_id, pipeline_id, revision, digest)
);

CREATE TABLE IF NOT EXISTS integration.sync_pipeline_pin (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    sync_run_id uuid NOT NULL,
    pipeline_id varchar(64) NOT NULL,
    revision integer NOT NULL,
    digest varchar(71) NOT NULL,
    PRIMARY KEY (tenant_id, sync_run_id),
    FOREIGN KEY (tenant_id, sync_run_id) REFERENCES integration.source_sync_run (tenant_id, id),
    FOREIGN KEY (tenant_id, source_instance_id, pipeline_id, revision, digest)
        REFERENCES integration.pipeline_version (tenant_id, source_instance_id, pipeline_id, revision, digest)
);

CREATE INDEX IF NOT EXISTS raw_record_by_run
    ON integration.raw_record_metadata (tenant_id, source_instance_id, sync_run_id, id);
