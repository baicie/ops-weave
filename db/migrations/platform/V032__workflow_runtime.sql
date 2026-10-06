-- Bounded control and metadata. Input values remain in the existing observation store only.
CREATE TABLE IF NOT EXISTS integration.workflow_task (
 tenant_id text NOT NULL,
 owner_subject text NOT NULL,
 workflow_id text NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text)<=4096),
 updated_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,workflow_id)
);
CREATE TABLE IF NOT EXISTS integration.workflow_execution (
 tenant_id text NOT NULL,
 owner_subject text NOT NULL,
 execution_id uuid NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text)<=4096),
 created_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,execution_id)
);
CREATE INDEX IF NOT EXISTS workflow_execution_owner_time ON integration.workflow_execution (tenant_id,owner_subject,created_at DESC);
