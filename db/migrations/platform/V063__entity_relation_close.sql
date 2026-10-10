-- Relation maintenance closure: terminating keeps the row, records the operator reason
-- and keys the close by its own idempotent request. Rows are never deleted.
ALTER TABLE inventory.entity_relation ADD COLUMN IF NOT EXISTS close_request_id uuid;
ALTER TABLE inventory.entity_relation ADD COLUMN IF NOT EXISTS close_reason text;
ALTER TABLE inventory.entity_relation ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 1;
ALTER TABLE inventory.entity_relation DROP CONSTRAINT IF EXISTS relation_close_shape;
ALTER TABLE inventory.entity_relation ADD CONSTRAINT relation_close_shape
 CHECK (close_reason IS NULL OR (valid_to IS NOT NULL AND char_length(close_reason) BETWEEN 1 AND 256));
CREATE UNIQUE INDEX IF NOT EXISTS relation_close_request_unique ON inventory.entity_relation(tenant_id, close_request_id) WHERE close_request_id IS NOT NULL;
