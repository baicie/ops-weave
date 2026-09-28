-- v2 read-only transformation workflows. Published versions and run receipts are append-only.
CREATE TABLE IF NOT EXISTS integration.workflow_draft (
 tenant_id varchar(128) NOT NULL, owner_subject varchar(128) NOT NULL,
 workflow_id varchar(48) NOT NULL, revision integer NOT NULL CHECK (revision BETWEEN 1 AND 10000),
 entry jsonb NOT NULL CHECK (octet_length(entry::text)<=49152), updated_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,owner_subject,workflow_id,revision)
);
CREATE TABLE IF NOT EXISTS integration.workflow_version (
 tenant_id varchar(128) NOT NULL, workflow_id varchar(48) NOT NULL,
 revision integer NOT NULL CHECK(revision BETWEEN 1 AND 10000),
 entry jsonb NOT NULL CHECK(octet_length(entry::text)<=49152), published_by varchar(128) NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,workflow_id,revision)
);
CREATE TABLE IF NOT EXISTS integration.workflow_run (
 tenant_id varchar(128) NOT NULL, owner_subject varchar(128) NOT NULL, run_id uuid NOT NULL,
 receipt jsonb NOT NULL CHECK(octet_length(receipt::text)<=4096), created_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,run_id)
);
CREATE INDEX IF NOT EXISTS workflow_draft_recent ON integration.workflow_draft(tenant_id,owner_subject,updated_at DESC);
CREATE INDEX IF NOT EXISTS workflow_version_recent ON integration.workflow_version(tenant_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS workflow_run_recent ON integration.workflow_run(tenant_id,owner_subject,created_at DESC);
