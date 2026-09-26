-- Bounded explicit snapshots; absence is source-scoped and never a physical entity deletion.
CREATE TABLE inventory.source_snapshot (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    actor varchar(128) NOT NULL,
    namespace varchar(64) NOT NULL,
    observed_at timestamptz NOT NULL,
    observed_epoch_nanos numeric(30,0) NOT NULL,
    body jsonb NOT NULL CHECK(octet_length(body::text)<=262144),
    PRIMARY KEY(tenant_id,source_instance_id,request_id)
);
CREATE INDEX source_snapshot_latest ON inventory.source_snapshot(tenant_id,source_instance_id,observed_epoch_nanos DESC);
CREATE TABLE inventory.entity_source_presence (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_id varchar(256) NOT NULL,
    entity_id uuid NOT NULL,
    identity_id uuid NOT NULL,
    expires_epoch_nanos numeric(30,0) NOT NULL,
    present boolean NOT NULL,
    body jsonb NOT NULL CHECK(octet_length(body::text)<=4096),
    PRIMARY KEY(tenant_id,source_instance_id,external_id),
    UNIQUE(tenant_id,source_instance_id,entity_id),
    FOREIGN KEY(tenant_id,entity_id) REFERENCES inventory.entity(tenant_id,id),
    FOREIGN KEY(tenant_id,identity_id) REFERENCES inventory.asset_identity(tenant_id,id)
);
CREATE INDEX source_presence_entity ON inventory.entity_source_presence(tenant_id,entity_id);
