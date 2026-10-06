-- Metadata and ciphertext only. Versions, revocations and original command receipts are append-only.
CREATE TABLE IF NOT EXISTS integration.source_credential (
 tenant_id text NOT NULL, owner_subject text NOT NULL, credential_id uuid NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text)<=4096), updated_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,credential_id)
);
CREATE TABLE IF NOT EXISTS integration.source_credential_version (
 tenant_id text NOT NULL, owner_subject text NOT NULL, credential_id uuid NOT NULL,
 revision integer NOT NULL CHECK (revision BETWEEN 1 AND 100), version_id uuid NOT NULL,
 key_id text NOT NULL CHECK (key_id ~ '^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$'), nonce text NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text)<=8192), created_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,credential_id,revision),
 UNIQUE (tenant_id,owner_subject,credential_id,version_id), UNIQUE (key_id,nonce),
 FOREIGN KEY (tenant_id,owner_subject,credential_id) REFERENCES integration.source_credential (tenant_id,owner_subject,credential_id)
);
CREATE TABLE IF NOT EXISTS integration.source_credential_revocation (
 tenant_id text NOT NULL, owner_subject text NOT NULL, credential_id uuid NOT NULL,
 revision integer NOT NULL, request_id uuid NOT NULL, created_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,credential_id,revision),
 FOREIGN KEY (tenant_id,owner_subject,credential_id,revision) REFERENCES integration.source_credential_version (tenant_id,owner_subject,credential_id,revision)
);
CREATE TABLE IF NOT EXISTS integration.source_credential_command (
 tenant_id text NOT NULL, owner_subject text NOT NULL, request_id uuid NOT NULL, credential_id uuid NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text)<=8192),
 PRIMARY KEY (tenant_id,owner_subject,request_id),
 FOREIGN KEY (tenant_id,owner_subject,credential_id) REFERENCES integration.source_credential (tenant_id,owner_subject,credential_id)
);
CREATE INDEX IF NOT EXISTS source_credential_owner_recent ON integration.source_credential (tenant_id,owner_subject,updated_at DESC,credential_id);
