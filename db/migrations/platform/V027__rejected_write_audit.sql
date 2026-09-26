-- Bounded audit of refused supplemental-source writes. A refusal is evidence about a write the
-- platform did not perform: it records the stable reason code, the refused operation and the names of
-- the fields that were attempted, never a field value, vendor payload or exception message.
CREATE TABLE integration.rejected_write_attempt (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    kind varchar(32) NOT NULL CHECK (kind IN ('FIELD_REVIEW', 'BINDING_CORRECTION')),
    method varchar(32) NOT NULL CHECK (method IN ('STAGE_REVIEW', 'DECIDE_REVIEW', 'CORRECT_BINDING', 'INGEST_SNAPSHOT')),
    reason_code varchar(64) NOT NULL CHECK (reason_code IN (
        'ENTITY_VERSION_CHANGED', 'REVIEW_VERSION_CHANGED', 'REVIEW_STATE_CHANGED', 'REVIEW_STALE',
        'BINDING_CHANGED', 'BINDING_UNCHANGED', 'BINDING_FIELDS_ACTIVE', 'BINDING_CONFLICT',
        'SNAPSHOT_OUTDATED', 'REQUEST_CONFLICT', 'LIMIT_REACHED', 'IDENTITY_UNRESOLVED')),
    actor varchar(128) NOT NULL,
    field_names jsonb NOT NULL,
    attempted_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX rejected_write_attempt_recent
    ON integration.rejected_write_attempt (tenant_id, source_instance_id, attempted_at DESC, id DESC);
