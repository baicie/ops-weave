-- Owned and migrated by agent-runtime, never by a shared universal repository.
CREATE SCHEMA IF NOT EXISTS ai_runtime;
CREATE TABLE ai_runtime.run (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    idempotency_key varchar(256) NOT NULL,
    skill_release_ref varchar(256) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    state_version bigint NOT NULL DEFAULT 1,
    checkpoint_ref text,
    context_ref text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key)
);
