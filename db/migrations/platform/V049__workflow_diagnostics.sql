-- Bounded validation facts only. No raw input, normalized values or authorization identity.
CREATE TABLE integration.workflow_diagnostic (
    tenant_id text NOT NULL, owner_subject text NOT NULL, observation_id uuid NOT NULL,
    workflow_id text NOT NULL, revision integer NOT NULL,
    body jsonb NOT NULL CHECK (octet_length(body::text)<=32768), completed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,owner_subject,observation_id),
    FOREIGN KEY (tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
CREATE INDEX workflow_diagnostic_version ON integration.workflow_diagnostic(tenant_id,owner_subject,workflow_id,revision,completed_at DESC,observation_id);
