-- Low-volume control acknowledgements share the existing tenant transaction with task changes.
CREATE TABLE IF NOT EXISTS integration.workflow_control_command (
    tenant_id text NOT NULL,
    owner_subject text NOT NULL,
    request_id uuid NOT NULL,
    workflow_id text NOT NULL,
    body jsonb NOT NULL CHECK (octet_length(body::text) <= 8192),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_subject, request_id),
    FOREIGN KEY (tenant_id, owner_subject, workflow_id)
        REFERENCES integration.workflow_task (tenant_id, owner_subject, workflow_id)
);
