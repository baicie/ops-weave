-- Durable diagnosis run registry (M5, ADR-104). A run is the platform record of one admitted
-- diagnosis request and its current state. Submitting is idempotent on the request id: a replay
-- returns the original run and the same request id with different content is an explicit conflict,
-- so no second run is ever created. The registry executes nothing -- no model call and no tool call
-- happens because a row exists -- and state only moves through the compare-and-set executor seam.
-- Text columns keep the exact instants (like the AIInsight receipt); timestamptz columns order rows.
CREATE TABLE IF NOT EXISTS ai_control.ai_run (
    tenant_id varchar(128) NOT NULL,
    run_id uuid NOT NULL,
    subject_id varchar(128) NOT NULL,
    incident_id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','WAITING_TOOL','SUCCEEDED','FAILED','CANCELLED')),
    status_version bigint NOT NULL CHECK (status_version >= 0),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 16384),
    created_at_text varchar(40) NOT NULL,
    updated_at_text varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    FOREIGN KEY (tenant_id, incident_id) REFERENCES incident.incident (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS ai_run_owner_recent ON ai_control.ai_run (tenant_id, subject_id, created_at DESC, run_id DESC);
CREATE INDEX IF NOT EXISTS ai_run_incident ON ai_control.ai_run (tenant_id, incident_id, run_id);

-- The immutable submission receipt. The request id is the idempotency key of one admitted
-- submission and the digest covers content only, so a replay answers from here instead of
-- creating a run, and a reused request id with different content fails closed.
CREATE TABLE IF NOT EXISTS ai_control.ai_run_command (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    run_id uuid NOT NULL,
    subject_id varchar(128) NOT NULL,
    command_digest varchar(71) NOT NULL CHECK (command_digest ~ '^sha256:[0-9a-f]{64}$'),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 16384),
    created_at_text varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES ai_control.ai_run (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS ai_run_command_recent ON ai_control.ai_run_command (tenant_id, created_at DESC, request_id DESC);
