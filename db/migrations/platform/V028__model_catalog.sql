CREATE SCHEMA IF NOT EXISTS catalog;
CREATE TABLE catalog.model_draft (
    tenant_id text NOT NULL,
    owner_subject text NOT NULL,
    model_id text NOT NULL CHECK (model_id ~ '^custom\.[a-z][a-z0-9_]{0,47}$'),
    revision integer NOT NULL CHECK (revision BETWEEN 1 AND 10000),
    edit_version integer NOT NULL CHECK (edit_version BETWEEN 1 AND 1000000),
    definition jsonb NOT NULL CHECK (octet_length(definition::text) <= 65536),
    digest text NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_subject, model_id, revision)
);
CREATE TABLE catalog.model_version (
    tenant_id text NOT NULL,
    model_id text NOT NULL CHECK (model_id ~ '^custom\.[a-z][a-z0-9_]{0,47}$'),
    revision integer NOT NULL CHECK (revision BETWEEN 1 AND 10000),
    definition jsonb NOT NULL CHECK (octet_length(definition::text) <= 65536),
    digest text NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    published_by text NOT NULL,
    published_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, model_id, revision)
);
