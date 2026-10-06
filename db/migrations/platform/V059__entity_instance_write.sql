CREATE TABLE IF NOT EXISTS inventory.entity_instance_request (
 tenant_id varchar(128) NOT NULL, request_id uuid NOT NULL, entity_id uuid NOT NULL,
 body jsonb NOT NULL CHECK (octet_length(body::text) <= 16384), created_at timestamptz NOT NULL,
 PRIMARY KEY (tenant_id, request_id),
 FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity (tenant_id, id)
);
