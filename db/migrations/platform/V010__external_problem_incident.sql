CREATE SCHEMA IF NOT EXISTS alerting;
CREATE SCHEMA IF NOT EXISTS incident;

-- Bounded aggregate snapshot keeps source timestamps at nanosecond precision.
-- Indexed scope/header fields are updated in the same transaction as the snapshot.
CREATE TABLE IF NOT EXISTS incident.incident (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    title varchar(300) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('OPEN','INVESTIGATING','MITIGATED','RESOLVED','CLOSED')),
    severity smallint NOT NULL CHECK (severity BETWEEN 0 AND 5),
    version bigint NOT NULL CHECK (version > 0),
    created_at_text varchar(40) NOT NULL,
    entity_ids uuid[] NOT NULL DEFAULT '{}',
    has_unmapped boolean NOT NULL,
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object' AND octet_length(snapshot::text) <= 262144),
    PRIMARY KEY (tenant_id, id),
    CHECK (cardinality(entity_ids) <= 100)
);
CREATE INDEX IF NOT EXISTS incident_status_page ON incident.incident (tenant_id, status, id);

CREATE TABLE IF NOT EXISTS alerting.external_problem (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    problem_event_id varchar(20) NOT NULL,
    incident_id uuid NOT NULL,
    data_mode varchar(32) NOT NULL CHECK (data_mode IN ('labeled-fixture','zabbix-jsonrpc')),
    PRIMARY KEY (tenant_id, source_instance_id, problem_event_id),
    FOREIGN KEY (tenant_id, incident_id) REFERENCES incident.incident (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS problem_incident_lookup ON alerting.external_problem (tenant_id, incident_id);

CREATE TABLE IF NOT EXISTS incident.transition_request (
    tenant_id varchar(128) NOT NULL,
    incident_id uuid NOT NULL,
    request_key uuid NOT NULL,
    actor varchar(128) NOT NULL,
    expected_version bigint NOT NULL CHECK (expected_version > 0),
    target_status varchar(24) NOT NULL CHECK (target_status IN ('OPEN','INVESTIGATING','MITIGATED','RESOLVED','CLOSED')),
    result_version bigint NOT NULL CHECK (result_version > expected_version),
    PRIMARY KEY (tenant_id, incident_id, request_key),
    FOREIGN KEY (tenant_id, incident_id) REFERENCES incident.incident (tenant_id, id)
);
