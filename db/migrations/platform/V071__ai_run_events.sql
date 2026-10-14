-- M5 5c (ADR-104 §2.5): the ordered run event log. Events are records, not commands -- one row is
-- one fact that already happened (the submission, one claim, one applied state move, the recorded
-- cancellation request, the explicit unknown-completion disclosure, the terminal landing). Sequence
-- numbers are per run and strictly monotonic; the payload is closed (status, attempt, landing
-- reason, reconciliation disclosure) and carries no hidden reasoning, no credential and no raw log
-- or evidence body. Reading is side-effect free and the retained window is a server constant: rows
-- below it are dropped on append, which is what expires a cursor into an explicit gap instead of a
-- fake continuous history. Text columns keep the exact instants; timestamptz columns order rows.
CREATE TABLE IF NOT EXISTS ai_control.ai_run_event (
    tenant_id varchar(128) NOT NULL,
    run_id uuid NOT NULL,
    seq bigint NOT NULL CHECK (seq >= 1),
    type varchar(24) NOT NULL CHECK (type IN ('SUBMITTED','CLAIMED','STATUS_CHANGED','TOOL_WAIT','CANCEL_REQUESTED','RECONCILED','FINISHED')),
    payload_version smallint NOT NULL CHECK (payload_version = 1),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 2048),
    occurred_at_text varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, run_id, seq),
    FOREIGN KEY (tenant_id, run_id) REFERENCES ai_control.ai_run (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS ai_run_event_cursor ON ai_control.ai_run_event (tenant_id, run_id, seq DESC);
