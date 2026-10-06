-- Low-frequency normalized asset inputs support recovery; no telemetry values or credentials.
CREATE TABLE integration.workflow_host_checkpoint (
    tenant_id text NOT NULL, owner_subject text NOT NULL, workflow_id text NOT NULL,
    revision integer NOT NULL, body jsonb NOT NULL CHECK (octet_length(body::text)<=4096),
    PRIMARY KEY (tenant_id,owner_subject,workflow_id),
    FOREIGN KEY (tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
CREATE TABLE integration.workflow_host_batch (
    tenant_id text NOT NULL, owner_subject text NOT NULL, batch_id uuid NOT NULL,
    workflow_id text NOT NULL, revision integer NOT NULL,
    body jsonb NOT NULL CHECK (octet_length(body::text)<=16384), updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,owner_subject,batch_id),
    FOREIGN KEY (tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
