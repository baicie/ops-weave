-- Non-secret fixed address and credential pins; existing configuration/creation receipts remain immutable.
CREATE TABLE IF NOT EXISTS integration.source_connection_configuration (
 tenant_id text NOT NULL,
 owner_subject text NOT NULL,
 source_id uuid NOT NULL,
 revision integer NOT NULL CHECK (revision BETWEEN 1 AND 100),
 body jsonb NOT NULL CHECK (octet_length(body::text)<=16384),
 created_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id,owner_subject,source_id,revision),
 FOREIGN KEY (tenant_id,owner_subject,source_id,revision) REFERENCES integration.source_configuration (tenant_id,owner_subject,source_id,revision)
);
