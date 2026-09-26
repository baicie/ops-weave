-- One supplemental binding per asset. Import values never determine canonical identity.
CREATE TABLE inventory.source_review (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    entity_id uuid NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_id varchar(256) NOT NULL,
    active boolean NOT NULL DEFAULT false,
    body jsonb NOT NULL CHECK (octet_length(body::text) <= 16384),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity(tenant_id, id)
);
CREATE INDEX source_review_page ON inventory.source_review(tenant_id, entity_id, source_instance_id, id);
CREATE UNIQUE INDEX source_review_active_entity ON inventory.source_review(tenant_id, entity_id) WHERE active;
CREATE UNIQUE INDEX source_review_active_object ON inventory.source_review(tenant_id, source_instance_id, external_id) WHERE active;
CREATE TABLE inventory.entity_source_authority (
    tenant_id varchar(128) NOT NULL,
    entity_id uuid NOT NULL,
    primary_snapshot jsonb NOT NULL CHECK (octet_length(primary_snapshot::text) <= 20000),
    PRIMARY KEY (tenant_id, entity_id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity(tenant_id, id)
);
CREATE TABLE inventory.source_review_receipt (
    tenant_id varchar(128) NOT NULL,
    entity_id uuid NOT NULL,
    request_id uuid NOT NULL,
    review_id uuid NOT NULL,
    command jsonb NOT NULL CHECK (octet_length(command::text) <= 4096),
    result jsonb NOT NULL CHECK (octet_length(result::text) <= 16384),
    PRIMARY KEY (tenant_id, entity_id, request_id),
    FOREIGN KEY (tenant_id, review_id) REFERENCES inventory.source_review(tenant_id, id)
);
