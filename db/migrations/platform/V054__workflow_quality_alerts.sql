-- Explicit fixed-version thresholds and immutable operator command receipts; no business data.
CREATE TABLE integration.workflow_quality_alert_configuration (
    tenant_id text NOT NULL, owner_subject text NOT NULL, workflow_id text NOT NULL,
    revision integer NOT NULL, edit_version integer NOT NULL CHECK(edit_version BETWEEN 1 AND 1000000),
    body jsonb NOT NULL CHECK(octet_length(body::text)<=4096), updated_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,owner_subject,workflow_id,revision),
    FOREIGN KEY(tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
CREATE TABLE integration.workflow_quality_alert_command (
    tenant_id text NOT NULL, owner_subject text NOT NULL, request_id uuid NOT NULL,
    workflow_id text NOT NULL, revision integer NOT NULL,
    body jsonb NOT NULL CHECK(octet_length(body::text)<=4096), accepted_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,owner_subject,request_id),
    FOREIGN KEY(tenant_id,owner_subject,workflow_id,revision) REFERENCES integration.workflow_quality_alert_configuration(tenant_id,owner_subject,workflow_id,revision)
);
