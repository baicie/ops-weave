-- Explicit collection binding between one source instance and one published workflow version, plus
-- durable receipts of the idempotent commands that changed it. Publishing a version never writes a
-- binding; only an authorized enable/disable command does. The binding is what gates controlled
-- batch collection, so it is stored apart from the task lifecycle rows.
CREATE TABLE IF NOT EXISTS integration.workflow_collection_binding (
    tenant_id text NOT NULL,
    owner_subject text NOT NULL,
    workflow_id text NOT NULL CHECK (workflow_id ~ '^[a-z][a-z0-9_-]{0,47}$'),
    revision integer NOT NULL CHECK (revision BETWEEN 1 AND 10000),
    body jsonb NOT NULL CHECK (octet_length(body::text) <= 16384),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_subject, workflow_id, revision)
);
CREATE TABLE IF NOT EXISTS integration.workflow_collection_command (
    tenant_id text NOT NULL,
    owner_subject text NOT NULL,
    request_id uuid NOT NULL,
    workflow_id text NOT NULL CHECK (workflow_id ~ '^[a-z][a-z0-9_-]{0,47}$'),
    body jsonb NOT NULL CHECK (octet_length(body::text) <= 16384),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_subject, request_id)
);
CREATE INDEX IF NOT EXISTS workflow_collection_command_owner_recent
    ON integration.workflow_collection_command (tenant_id, owner_subject, created_at DESC, request_id DESC);

-- Explicit receipt-retention cleanup receipts. The cleanup itself is never scheduled: one row is
-- written only after an operator confirms a preview, and the row keeps the counts so a replayed
-- command returns the original answer instead of deleting again.
CREATE TABLE IF NOT EXISTS integration.receipt_retention_command (
    tenant_id text NOT NULL,
    request_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('onboarding','source-command','external-link')),
    scope_key text NOT NULL CHECK (scope_key ~ '^[A-Za-z0-9_.:@-]{1,128}$'),
    body jsonb NOT NULL CHECK (octet_length(body::text) <= 16384),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id)
);
CREATE INDEX IF NOT EXISTS receipt_retention_command_scope
    ON integration.receipt_retention_command (tenant_id, kind, scope_key, created_at DESC, request_id DESC);

-- Operational delete receipts. A delete is an explicit, reasoned, idempotent command and its
-- receipt keeps the audit counts; it never removes protected or unfinished records and an entity
-- soft delete keeps the row so old Incident/Evidence references still resolve.
CREATE TABLE IF NOT EXISTS inventory.entity_deletion_command (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    entity_id uuid NOT NULL,
    body jsonb NOT NULL CHECK (octet_length(body::text) <= 16384),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id)
);
CREATE INDEX IF NOT EXISTS entity_deletion_command_entity
    ON inventory.entity_deletion_command (tenant_id, entity_id, created_at DESC, request_id DESC);
