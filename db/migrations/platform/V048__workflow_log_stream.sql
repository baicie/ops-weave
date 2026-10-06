-- Per-window proofs only. Raw and normalized telemetry values stay in the log store.
CREATE TABLE integration.workflow_log_stream_task (
    tenant_id text NOT NULL, owner_subject text NOT NULL, workflow_id text NOT NULL,
    revision integer NOT NULL, body jsonb NOT NULL CHECK (octet_length(body::text)<=4096),
    PRIMARY KEY (tenant_id,owner_subject,workflow_id),
    FOREIGN KEY (tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
CREATE TABLE integration.workflow_log_stream_batch (
    tenant_id text NOT NULL, owner_subject text NOT NULL, batch_id uuid NOT NULL,
    workflow_id text NOT NULL, revision integer NOT NULL,
    body jsonb NOT NULL CHECK (octet_length(body::text)<=65536), created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,owner_subject,batch_id),
    FOREIGN KEY (tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
CREATE TABLE integration.workflow_log_stream_control (
    tenant_id text NOT NULL, owner_subject text NOT NULL, request_id uuid NOT NULL,
    workflow_id text NOT NULL, body jsonb NOT NULL CHECK (octet_length(body::text)<=8192),
    PRIMARY KEY (tenant_id,owner_subject,request_id)
);
