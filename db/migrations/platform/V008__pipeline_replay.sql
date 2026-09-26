-- Metadata and bounded results only. No Raw payload duplication or action execution.
CREATE TABLE IF NOT EXISTS integration.pipeline_replay_run (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    owner_subject varchar(128) NOT NULL,
    request_key uuid NOT NULL,
    id uuid NOT NULL,
    spec jsonb NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    attempt integer NOT NULL CHECK (attempt BETWEEN 1 AND 3),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    lease_until timestamptz,
    failure_code varchar(64),
    report jsonb,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, source_instance_id, owner_subject, request_key),
    CHECK ((state = 'RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((state = 'FAILED') = (failure_code IS NOT NULL)),
    CHECK ((state = 'SUCCEEDED') = (report IS NOT NULL)),
    CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z_]{1,64}$'),
    CHECK (report IS NULL OR octet_length(report::text) <= 1100000)
);

CREATE INDEX IF NOT EXISTS pipeline_replay_history
    ON integration.pipeline_replay_run (tenant_id, source_instance_id, owner_subject, created_at DESC, id DESC);
