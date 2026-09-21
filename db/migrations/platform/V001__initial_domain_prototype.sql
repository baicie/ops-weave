-- Prototype only. Not automatically run by this starter.
-- A migration role may create schemas; production runtime roles must NOT own tables.
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS incident;
CREATE SCHEMA IF NOT EXISTS integration;
CREATE SCHEMA IF NOT EXISTS delivery;
CREATE SCHEMA IF NOT EXISTS ai_control;

CREATE TABLE inventory.entity (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    lifecycle varchar(16) NOT NULL CHECK (lifecycle IN ('DISCOVERED','ACTIVE','INACTIVE','DELETED','ARCHIVED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(attributes) = 'object'),
    last_seen_at timestamptz,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX entity_type_name ON inventory.entity (tenant_id, entity_type, name);

CREATE TABLE inventory.external_link (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_type varchar(64) NOT NULL,
    external_id varchar(512) NOT NULL,
    generation varchar(128) NOT NULL,
    entity_id uuid NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    PRIMARY KEY (tenant_id, source_instance_id, external_type, external_id, generation),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity (tenant_id, id),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE TABLE inventory.entity_observation (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    entity_id uuid,
    source_instance_id varchar(128) NOT NULL,
    external_ref jsonb NOT NULL,
    field_name varchar(128) NOT NULL,
    observed_value jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    mapping_version varchar(128) NOT NULL,
    raw_ref text,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity (tenant_id, id)
);
CREATE INDEX observation_lookup ON inventory.entity_observation (tenant_id, entity_id, field_name, observed_at DESC);

CREATE TABLE inventory.entity_relation (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    from_entity_id uuid NOT NULL,
    relation_type varchar(64) NOT NULL,
    to_entity_id uuid NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    source_ref text NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, from_entity_id) REFERENCES inventory.entity (tenant_id, id),
    FOREIGN KEY (tenant_id, to_entity_id) REFERENCES inventory.entity (tenant_id, id),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX relation_forward ON inventory.entity_relation (tenant_id, from_entity_id, relation_type);
CREATE INDEX relation_reverse ON inventory.entity_relation (tenant_id, to_entity_id, relation_type);

CREATE TABLE integration.data_source (
    tenant_id varchar(128) NOT NULL,
    id varchar(128) NOT NULL,
    connector_type varchar(64) NOT NULL,
    endpoint text NOT NULL,
    secret_ref text NOT NULL,
    config_version bigint NOT NULL DEFAULT 1,
    enabled boolean NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE incident.incident (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    title varchar(300) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('OPEN','INVESTIGATING','MITIGATED','RESOLVED','CLOSED')),
    severity smallint NOT NULL CHECK (severity BETWEEN 0 AND 5),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    confirmed_root_cause text,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE incident.incident_entity (
    tenant_id varchar(128) NOT NULL,
    incident_id uuid NOT NULL,
    entity_id uuid NOT NULL,
    role varchar(32) NOT NULL,
    PRIMARY KEY (tenant_id, incident_id, entity_id, role),
    FOREIGN KEY (tenant_id, incident_id) REFERENCES incident.incident (tenant_id, id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity (tenant_id, id)
);

CREATE TABLE incident.change_record (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    entity_id uuid,
    change_type varchar(64) NOT NULL,
    before_ref text,
    after_ref text,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    source_ref text NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES inventory.entity (tenant_id, id),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);

CREATE TABLE delivery.outbox_event (
    tenant_id varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    schema_version varchar(32) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','LEASED','SENT','FAILED')),
    attempts integer NOT NULL DEFAULT 0,
    lease_owner varchar(128),
    lease_until timestamptz,
    fencing_token bigint NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX outbox_pending ON delivery.outbox_event (available_at, created_at) WHERE status IN ('PENDING','LEASED');

CREATE TABLE delivery.consumer_inbox (
    consumer_name varchar(128) NOT NULL,
    tenant_id varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, tenant_id, event_id)
);

CREATE TABLE ai_control.skill_release (
    tenant_id varchar(128) NOT NULL,
    skill_id varchar(128) NOT NULL,
    version varchar(64) NOT NULL,
    definition jsonb NOT NULL,
    content_digest varchar(128) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, skill_id, version)
);
-- Published releases must be immutable at application/role level; the table alone does not enforce the workflow.
