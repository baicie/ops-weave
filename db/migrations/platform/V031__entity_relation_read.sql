-- Current relation projection; application exposes bounded reads only.
-- No collector, model, or UI writes are enabled by this migration.
CREATE TABLE IF NOT EXISTS inventory.entity_relation (
 tenant_id varchar(128) NOT NULL, id uuid NOT NULL,
 from_entity_id uuid NOT NULL, relation_type varchar(64) NOT NULL,
 to_entity_id uuid NOT NULL, valid_from timestamptz NOT NULL, valid_to timestamptz,
 source_ref text NOT NULL,
 PRIMARY KEY (tenant_id,id),
 FOREIGN KEY (tenant_id,from_entity_id) REFERENCES inventory.entity(tenant_id,id),
 FOREIGN KEY (tenant_id,to_entity_id) REFERENCES inventory.entity(tenant_id,id),
 CHECK (valid_to IS NULL OR valid_to > valid_from)
);
ALTER TABLE inventory.entity_relation ADD COLUMN IF NOT EXISTS data_mode varchar(32) NOT NULL DEFAULT 'unknown'
 CHECK (data_mode IN ('fixture','zabbix-jsonrpc','import','unknown'));
CREATE INDEX IF NOT EXISTS relation_current_forward ON inventory.entity_relation(tenant_id,from_entity_id,id);
CREATE INDEX IF NOT EXISTS relation_current_reverse ON inventory.entity_relation(tenant_id,to_entity_id,id);
