-- Explicit bounded metadata operations. Completed results never return to pending or get replaced.
CREATE TABLE IF NOT EXISTS integration.source_inspection (
 tenant_id text NOT NULL,
 owner_subject text NOT NULL,
 request_id uuid NOT NULL,
 source_id uuid NOT NULL,
 state text NOT NULL CHECK (state IN ('PENDING','COMPLETED','UNKNOWN')),
 body jsonb NOT NULL CHECK (octet_length(body::text)<=8192),
 started_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,request_id),
 FOREIGN KEY (tenant_id,owner_subject,source_id) REFERENCES integration.source_setup (tenant_id,owner_subject,setup_id)
);
CREATE INDEX IF NOT EXISTS source_inspection_owner_recent ON integration.source_inspection (tenant_id,owner_subject,source_id,started_at DESC,request_id);
