-- No backfill: merged occurrence snapshots cannot reconstruct source inputs.
CREATE TABLE IF NOT EXISTS alerting.problem_observation (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    problem_event_id varchar(20) NOT NULL,
    observed_epoch_nanos numeric(30,0) NOT NULL CHECK (observed_epoch_nanos >= 0),
    first_received_epoch_nanos numeric(30,0) NOT NULL CHECK (first_received_epoch_nanos >= observed_epoch_nanos),
    entity_ids uuid[] NOT NULL CHECK (cardinality(entity_ids) <= 20),
    has_unmapped boolean NOT NULL,
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 20000),
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,source_instance_id,problem_event_id,observed_epoch_nanos),
    FOREIGN KEY (tenant_id,source_instance_id,problem_event_id)
        REFERENCES alerting.external_problem(tenant_id,source_instance_id,problem_event_id)
);
CREATE INDEX IF NOT EXISTS problem_observation_occurrence_cursor
    ON alerting.problem_observation(tenant_id,source_instance_id,problem_event_id,id);
