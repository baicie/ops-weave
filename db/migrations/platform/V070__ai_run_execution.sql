-- M5 5b (ADR-104 §2.3/§2.4): the executor seam of the durable run registry. Execution metadata
-- lives beside the run state, never inside it: the attempt counter and the lease/fencing columns
-- drive crash recovery without double runs (atomic claim, lease deadline, fencing token, CAS
-- commit), the cooperative cancellation request is recorded beside the state machine (it never
-- occupies a state and never moves status_version), and the idempotent receipts of the cancel/retry
-- commands answer replays without executing anything twice.
ALTER TABLE ai_control.ai_run
    ADD COLUMN IF NOT EXISTS attempt_no integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cancel_requested_at timestamptz,
    ADD COLUMN IF NOT EXISTS cancel_requested_at_text varchar(40),
    ADD COLUMN IF NOT EXISTS reason varchar(32),
    ADD COLUMN IF NOT EXISTS lease_owner varchar(128),
    ADD COLUMN IF NOT EXISTS lease_until timestamptz,
    ADD COLUMN IF NOT EXISTS attempt_deadline_at timestamptz,
    ADD COLUMN IF NOT EXISTS fence bigint NOT NULL DEFAULT 0;
ALTER TABLE ai_control.ai_run DROP CONSTRAINT IF EXISTS ai_run_attempt_no_check;
ALTER TABLE ai_control.ai_run ADD CONSTRAINT ai_run_attempt_no_check CHECK (attempt_no >= 0);
ALTER TABLE ai_control.ai_run DROP CONSTRAINT IF EXISTS ai_run_fence_check;
ALTER TABLE ai_control.ai_run ADD CONSTRAINT ai_run_fence_check CHECK (fence >= 0);
ALTER TABLE ai_control.ai_run DROP CONSTRAINT IF EXISTS ai_run_reason_check;
ALTER TABLE ai_control.ai_run ADD CONSTRAINT ai_run_reason_check CHECK (reason IS NULL OR reason IN (
    'RUNTIME_UNAVAILABLE', 'DEADLINE', 'BUDGET_EXHAUSTED', 'AUTHORIZATION_REVOKED',
    'RESULT_LOST', 'EXECUTION_FAILED', 'CANCELLED_BY_REQUEST'));
-- A landing reason exists only on FAILED/CANCELLED, never beside a complete SUCCEEDED result.
ALTER TABLE ai_control.ai_run DROP CONSTRAINT IF EXISTS ai_run_landing_check;
ALTER TABLE ai_control.ai_run ADD CONSTRAINT ai_run_landing_check CHECK (
    (status = 'SUCCEEDED' AND reason IS NULL)
    OR (status IN ('FAILED', 'CANCELLED') AND reason IS NOT NULL)
    OR (status IN ('PENDING', 'RUNNING', 'WAITING_TOOL') AND reason IS NULL));
-- The cancellation request is one instant, stored twice (exact text + sortable timestamp).
ALTER TABLE ai_control.ai_run DROP CONSTRAINT IF EXISTS ai_run_cancel_pair_check;
ALTER TABLE ai_control.ai_run ADD CONSTRAINT ai_run_cancel_pair_check CHECK (
    (cancel_requested_at IS NULL) = (cancel_requested_at_text IS NULL));
-- A live lease implies a claimed non-terminal run; terminal landings release it.
ALTER TABLE ai_control.ai_run DROP CONSTRAINT IF EXISTS ai_run_lease_check;
ALTER TABLE ai_control.ai_run ADD CONSTRAINT ai_run_lease_check CHECK (
    lease_until IS NULL OR (lease_owner IS NOT NULL AND status IN ('RUNNING', 'WAITING_TOOL')));

-- The immutable idempotent receipt of one cancel/retry command. The request id is the idempotency
-- key of exactly one command (never shared with a submission receipt): a replay answers from here
-- instead of executing again, and a reused request id with different content fails closed.
CREATE TABLE IF NOT EXISTS ai_control.ai_run_control_command (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('CANCEL', 'RETRY')),
    run_id uuid NOT NULL,
    subject_id varchar(128) NOT NULL,
    command_digest varchar(71) NOT NULL CHECK (command_digest ~ '^sha256:[0-9a-f]{64}$'),
    receipt jsonb NOT NULL CHECK (jsonb_typeof(receipt) = 'object' AND octet_length(receipt::text) <= 16384),
    created_at_text varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES ai_control.ai_run (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS ai_run_control_command_recent ON ai_control.ai_run_control_command (tenant_id, created_at DESC, request_id DESC);
CREATE INDEX IF NOT EXISTS ai_run_claimable ON ai_control.ai_run (tenant_id, subject_id, status, lease_until);
