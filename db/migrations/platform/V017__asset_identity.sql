-- Operator verified, scoped identifiers. No entity id rewriting or untrusted source authority.
CREATE TABLE IF NOT EXISTS inventory.asset_identity (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    entity_id uuid NOT NULL,
    namespace varchar(64) NOT NULL,
    value varchar(36) NOT NULL,
    active boolean NOT NULL,
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 16384),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity(tenant_id, id)
);
CREATE UNIQUE INDEX IF NOT EXISTS asset_identity_active_key ON inventory.asset_identity(tenant_id, namespace, value) WHERE active;
CREATE INDEX IF NOT EXISTS asset_identity_entity_page ON inventory.asset_identity(tenant_id, entity_id, namespace, id);
CREATE TABLE IF NOT EXISTS inventory.asset_identity_receipt (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    entity_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    entity_version bigint NOT NULL CHECK (entity_version > 1),
    command jsonb NOT NULL CHECK (octet_length(command::text) <= 16384),
    result jsonb NOT NULL CHECK (octet_length(result::text) <= 16384),
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, identity_id) REFERENCES inventory.asset_identity(tenant_id, id)
);
