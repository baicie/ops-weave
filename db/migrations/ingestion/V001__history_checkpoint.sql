-- Worker-owned metadata only. Samples remain in VictoriaMetrics.
CREATE SCHEMA IF NOT EXISTS ingestion;
CREATE TABLE IF NOT EXISTS ingestion.history_checkpoint (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(64) NOT NULL,
    item_id varchar(20) NOT NULL,
    stream_name varchar(64) NOT NULL,
    initial_from bigint NOT NULL CHECK (initial_from >= 0),
    completed_through bigint NOT NULL CHECK (completed_through >= initial_from - 1),
    series_hash varchar(64) NOT NULL DEFAULT '',
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_instance_id, item_id, stream_name)
);
