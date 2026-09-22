-- Metric definitions only. Point samples and history are not stored here.
CREATE SCHEMA IF NOT EXISTS telemetry;

CREATE TABLE IF NOT EXISTS telemetry.metric_definition (
    tenant_id varchar(128) NOT NULL,
    id varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    display_name varchar(255) NOT NULL,
    entity_type varchar(64) NOT NULL,
    unit varchar(64) NOT NULL,
    value_type varchar(32) NOT NULL,
    metric_type varchar(32) NOT NULL,
    dimensions jsonb NOT NULL,
    origin varchar(32) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_id varchar(512) NOT NULL,
    item_key varchar(512) NOT NULL,
    host_external_id varchar(512) NOT NULL,
    source_unit varchar(64) NOT NULL,
    value_transform varchar(64) NOT NULL,
    mapping_revision integer NOT NULL,
    lifecycle varchar(16) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, source_instance_id, external_id)
);
