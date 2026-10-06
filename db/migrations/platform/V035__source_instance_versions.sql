-- Optional maintained metadata over private immutable onboarding receipts; legacy rows require no rewrite.
CREATE TABLE IF NOT EXISTS integration.source_instance (
 tenant_id text NOT NULL,
 owner_subject text NOT NULL,
 source_id uuid NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text)<=16384),
 updated_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,source_id),
 FOREIGN KEY (tenant_id,owner_subject,source_id) REFERENCES integration.source_setup (tenant_id,owner_subject,setup_id)
);
CREATE TABLE IF NOT EXISTS integration.source_configuration (
 tenant_id text NOT NULL,
 owner_subject text NOT NULL,
 source_id uuid NOT NULL,
 revision integer NOT NULL CHECK (revision BETWEEN 1 AND 100),
 body jsonb NOT NULL CHECK (octet_length(body::text)<=4096),
 PRIMARY KEY (tenant_id,owner_subject,source_id,revision),
 FOREIGN KEY (tenant_id,owner_subject,source_id) REFERENCES integration.source_setup (tenant_id,owner_subject,setup_id)
);
CREATE TABLE IF NOT EXISTS integration.source_command (
 tenant_id text NOT NULL,
 owner_subject text NOT NULL,
 request_id uuid NOT NULL,
 source_id uuid NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text)<=32768),
 PRIMARY KEY (tenant_id,owner_subject,request_id),
 FOREIGN KEY (tenant_id,owner_subject,source_id) REFERENCES integration.source_setup (tenant_id,owner_subject,setup_id)
);
CREATE INDEX IF NOT EXISTS source_instance_owner_recent ON integration.source_instance (tenant_id,owner_subject,updated_at DESC,source_id);
