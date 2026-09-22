-- Databases that already applied the mixed V003 table are rewritten once.
-- The migrator records this id and does not run the replacement again.
DROP TABLE IF EXISTS telemetry.metric_binding;
DROP TABLE IF EXISTS telemetry.metric_definition;

CREATE SCHEMA IF NOT EXISTS telemetry;

CREATE TABLE telemetry.metric_definition (
    tenant_id varchar(128) NOT NULL,
    metric_key varchar(255) NOT NULL,
    display_name varchar(255) NOT NULL,
    unit varchar(64) NOT NULL,
    value_type varchar(32) NOT NULL,
    metric_type varchar(32) NOT NULL,
    dimension_schema jsonb NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    PRIMARY KEY (tenant_id, metric_key)
);

CREATE TABLE telemetry.metric_binding (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_item_id varchar(512) NOT NULL,
    source_type varchar(64) NOT NULL,
    entity_id varchar(64) NOT NULL,
    host_external_id varchar(512) NOT NULL,
    metric_key varchar(255) NOT NULL,
    fixed_dimensions jsonb NOT NULL,
    source_unit varchar(64) NOT NULL,
    value_transform varchar(64) NOT NULL,
    mapping_revision integer NOT NULL,
    lifecycle varchar(16) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    PRIMARY KEY (tenant_id, source_instance_id, external_item_id),
    FOREIGN KEY (tenant_id, metric_key) REFERENCES telemetry.metric_definition (tenant_id, metric_key)
);
