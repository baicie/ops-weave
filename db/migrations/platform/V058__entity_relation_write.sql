-- Controlled relation instance writes are keyed by the original request.
ALTER TABLE inventory.entity_relation ADD COLUMN IF NOT EXISTS request_id uuid;
UPDATE inventory.entity_relation SET request_id=id WHERE request_id IS NULL;
ALTER TABLE inventory.entity_relation ADD COLUMN IF NOT EXISTS relation_revision integer NOT NULL DEFAULT 1;
ALTER TABLE inventory.entity_relation ADD CONSTRAINT entity_relation_request_unique UNIQUE (tenant_id, request_id);
CREATE INDEX IF NOT EXISTS relation_request_lookup ON inventory.entity_relation(tenant_id,request_id);
