-- Bounded read-only source connection self-checks. A receipt records what one source reported at one
-- moment; it never replaces a real acceptance, never authorizes a scan and never stores vendor text.
CREATE TABLE integration.source_connection_check (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    check_id uuid NOT NULL,
    actor varchar(128) NOT NULL,
    checked_at timestamptz NOT NULL,
    data_mode varchar(64) NOT NULL,
    reachable boolean NOT NULL,
    status_code varchar(64) NOT NULL,
    reported_version varchar(32),
    PRIMARY KEY (tenant_id, source_instance_id, check_id)
);
CREATE INDEX source_connection_check_recent
    ON integration.source_connection_check (tenant_id, source_instance_id, checked_at DESC, check_id DESC);
