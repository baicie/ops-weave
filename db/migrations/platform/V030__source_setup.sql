-- Private immutable onboarding receipts. Draft + receipt commit atomically in the workflow transaction.
CREATE TABLE IF NOT EXISTS integration.source_setup (
 tenant_id text NOT NULL,
 owner_subject text NOT NULL,
 setup_id uuid NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text)<=16384),
 created_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,setup_id)
);
CREATE INDEX IF NOT EXISTS source_setup_owner_recent ON integration.source_setup (tenant_id,owner_subject,created_at DESC,setup_id DESC);
