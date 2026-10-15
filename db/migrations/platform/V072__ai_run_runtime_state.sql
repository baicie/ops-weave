-- M5 5d (ADR-104 §2.2/§3): the Runtime's state attestations and the cross-store report snapshot.
-- These rows are a bounded receipt projection of the Runtime's internal progress (attempt, internal
-- status and three counters) -- the StepRun/Checkpoint internals stay Runtime-owned and are never
-- stored on the platform. Ingestion records a delivery fact and never moves a run: no status,
-- status_version, attempt or lease changes because a report arrived. Delivery is idempotent on the
-- request id and shares the one idempotency namespace with submissions and control commands; the
-- per-run window is bounded (older receipts dropped, the drop counted in run_seq) so the
-- observability aggregate can disclose truncation instead of hiding it. The cross-store report is a
-- one-row-per-run observation of the last comparison -- a disclosure artifact, not state. Text
-- columns keep the exact instants; timestamptz columns order rows.
CREATE TABLE IF NOT EXISTS ai_control.ai_run_runtime_state (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    run_id uuid NOT NULL,
    run_seq bigint NOT NULL CHECK (run_seq >= 1),
    attempt integer NOT NULL CHECK (attempt >= 1 AND attempt <= 1000000),
    status varchar(24) NOT NULL CHECK (status IN ('PENDING','RUNNING','WAITING_TOOL','SUCCEEDED','FAILED','CANCELLED')),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 2048),
    reported_at_text varchar(40) NOT NULL,
    reported_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id),
    UNIQUE (tenant_id, run_id, run_seq),
    FOREIGN KEY (tenant_id, run_id) REFERENCES ai_control.ai_run (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS ai_run_runtime_state_run ON ai_control.ai_run_runtime_state (tenant_id, run_id, run_seq DESC);

CREATE TABLE IF NOT EXISTS ai_control.ai_run_cross_store_report (
    tenant_id varchar(128) NOT NULL,
    run_id uuid NOT NULL,
    findings integer NOT NULL CHECK (findings >= 0 AND findings <= 8),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 8192),
    compared_at_text varchar(40) NOT NULL,
    compared_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES ai_control.ai_run (tenant_id, run_id)
);
