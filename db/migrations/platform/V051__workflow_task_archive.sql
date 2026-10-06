-- Terminal metadata only; input/output journals and private checkpoints stay in their existing tables.
CREATE TABLE integration.workflow_task_archive (
    tenant_id text NOT NULL, owner_subject text NOT NULL,
    workflow_id text NOT NULL, revision integer NOT NULL, next_revision integer NOT NULL CHECK(next_revision>revision),
    kind text NOT NULL CHECK(kind IN ('HOST_SCAN','METRIC_STREAM','LOG_STREAM')),
    body jsonb NOT NULL CHECK(octet_length(body::text)<=4096), replaced_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,owner_subject,workflow_id,revision),
    FOREIGN KEY(tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision),
    FOREIGN KEY(tenant_id,workflow_id,next_revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
