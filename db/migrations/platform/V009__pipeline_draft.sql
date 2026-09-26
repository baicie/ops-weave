-- Private working copies; publication remains in the immutable pipeline_version table.
CREATE TABLE IF NOT EXISTS integration.pipeline_draft (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    owner_subject varchar(128) NOT NULL,
    pipeline_id varchar(64) NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    edit_version integer NOT NULL CHECK (edit_version > 0),
    definition jsonb NOT NULL,
    digest varchar(71) NOT NULL CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, source_instance_id, owner_subject, pipeline_id, revision),
    CHECK (octet_length(definition::text) <= 16384)
);
CREATE INDEX IF NOT EXISTS pipeline_draft_recent
    ON integration.pipeline_draft (tenant_id, source_instance_id, owner_subject, updated_at DESC, pipeline_id DESC, revision DESC);
