-- Tenant extension overlays for one fixed base model version, and durable receipts of the
-- explicit legacy entity model-pin backfill command. The immutable base model body is never
-- modified here: extensions only add optional bounded scalar fields in the tenant namespace.
CREATE TABLE IF NOT EXISTS catalog.model_extension_draft (
    tenant_id text NOT NULL,
    owner_subject text NOT NULL,
    base_id text NOT NULL CHECK (base_id ~ '^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$'),
    base_revision integer NOT NULL CHECK (base_revision BETWEEN 1 AND 10000),
    revision integer NOT NULL CHECK (revision BETWEEN 1 AND 10000),
    edit_version integer NOT NULL CHECK (edit_version BETWEEN 1 AND 1000000),
    definition jsonb NOT NULL CHECK (octet_length(definition::text) <= 65536),
    digest text NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_subject, base_id, base_revision)
);
CREATE TABLE IF NOT EXISTS catalog.model_extension_version (
    tenant_id text NOT NULL,
    base_id text NOT NULL CHECK (base_id ~ '^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$'),
    base_revision integer NOT NULL CHECK (base_revision BETWEEN 1 AND 10000),
    revision integer NOT NULL CHECK (revision BETWEEN 1 AND 10000),
    definition jsonb NOT NULL CHECK (octet_length(definition::text) <= 65536),
    digest text NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    published_by text NOT NULL,
    published_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, base_id, base_revision, revision)
);
CREATE TABLE IF NOT EXISTS inventory.entity_model_pin_backfill_request (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,
    request jsonb NOT NULL CHECK (octet_length(request::text) <= 8192),
    receipt jsonb NOT NULL CHECK (octet_length(receipt::text) <= 8192),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id)
);
