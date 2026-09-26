-- Preserve exact Java Instants from this version onward; legacy rows retain their actual PG precision.
ALTER TABLE inventory.entity_observation ADD COLUMN observed_epoch_nanos numeric(30,0);
ALTER TABLE inventory.entity_observation ADD COLUMN ingested_epoch_nanos numeric(30,0);
ALTER TABLE inventory.entity_observation ADD COLUMN exact_time boolean NOT NULL DEFAULT false;
UPDATE inventory.entity_observation SET observed_epoch_nanos = extract(epoch FROM observed_at) * 1000000000,
    ingested_epoch_nanos = extract(epoch FROM ingested_at) * 1000000000;
ALTER TABLE inventory.entity_observation ALTER COLUMN observed_epoch_nanos SET NOT NULL;
ALTER TABLE inventory.entity_observation ALTER COLUMN ingested_epoch_nanos SET NOT NULL;
ALTER TABLE inventory.entity ADD COLUMN last_seen_epoch_nanos numeric(30,0);
UPDATE inventory.entity SET last_seen_epoch_nanos = extract(epoch FROM last_seen_at) * 1000000000;
ALTER TABLE inventory.entity ALTER COLUMN last_seen_epoch_nanos SET NOT NULL;
CREATE INDEX entity_observation_history ON inventory.entity_observation (tenant_id, entity_id, id COLLATE "C");
