-- Deterministic external-link registrations. The binding itself lives in inventory.entity_external_link
-- (so InventoryQuery.linkOf resolves it); this table keeps the idempotent receipt and the operator reason.
-- The idempotency key (tenant_id, request_id) is unique; the binding may be re-asserted to the same entity
-- under a new request, but never silently rebound to another entity (guarded in the store and by the
-- entity_external_link primary key).
CREATE TABLE IF NOT EXISTS inventory.external_link_registration (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_type varchar(64) NOT NULL,
    external_id varchar(512) NOT NULL,
    generation varchar(128) NOT NULL,
    entity_id uuid NOT NULL,
    reason varchar(256),
    registered_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS external_link_registration_lookup
    ON inventory.external_link_registration(tenant_id, source_instance_id, external_type, external_id, generation);
