-- Immutable operator decisions; uncertain output and confirmed progress remain separate.
CREATE TABLE integration.workflow_recovery_closure (
    tenant_id text NOT NULL, owner_subject text NOT NULL, request_id uuid NOT NULL,
    workflow_id text NOT NULL, revision integer NOT NULL, kind text NOT NULL,
    batch_id uuid NOT NULL, body jsonb NOT NULL CHECK (octet_length(body::text)<=16384), accepted_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,owner_subject,request_id),
    UNIQUE (tenant_id,owner_subject,kind,batch_id),
    FOREIGN KEY (tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
