-- Batch metadata and immutable fingerprints only; metric values belong to the time-series sink.
CREATE TABLE IF NOT EXISTS integration.workflow_metric_output (
    tenant_id text NOT NULL,
    owner_subject text NOT NULL,
    request_id uuid NOT NULL,
    workflow_id text NOT NULL,
    revision integer NOT NULL,
    body jsonb NOT NULL CHECK (octet_length(body::text) <= 16384),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_subject, request_id),
    FOREIGN KEY (tenant_id, workflow_id, revision)
        REFERENCES integration.workflow_version (tenant_id, workflow_id, revision)
);
