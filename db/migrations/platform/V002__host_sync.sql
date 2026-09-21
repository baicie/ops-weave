-- Applied by platform-api when OPSWEAVE_INVENTORY_STORE=postgres.
-- V001 remains an unapplied domain prototype. Payload stays in JSONB; object storage is later.
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE IF NOT EXISTS integration.schema_migration (
    id varchar(128) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS inventory.entity (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    lifecycle varchar(16) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    attributes jsonb NOT NULL,
    last_seen_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS inventory.entity_external_link (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_type varchar(64) NOT NULL,
    external_id varchar(512) NOT NULL,
    generation varchar(128) NOT NULL,
    entity_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, source_instance_id, external_type, external_id, generation),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS inventory.entity_observation (
    tenant_id varchar(128) NOT NULL,
    id varchar(128) NOT NULL,
    entity_id uuid NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_type varchar(64) NOT NULL,
    external_id varchar(512) NOT NULL,
    generation varchar(128) NOT NULL,
    observed_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    fields jsonb NOT NULL,
    raw_record_ref varchar(256) NOT NULL,
    mapping_revision integer NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS integration.raw_record_metadata (
    tenant_id varchar(128) NOT NULL,
    id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_id varchar(512) NOT NULL,
    observed_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    sync_run_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS integration.source_sync_run (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    object_type varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    cursor_text varchar(256),
    pages integer NOT NULL,
    fetched integer NOT NULL,
    accepted integer NOT NULL,
    rejected integer NOT NULL,
    snapshot_complete boolean NOT NULL,
    data_mode varchar(64) NOT NULL,
    failure_reason varchar(200),
    PRIMARY KEY (tenant_id, id)
);
