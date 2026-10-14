-- Roll back the M5 5b executor seam of the run registry. Run rows and submission receipts stay
-- intact: only the lease/fencing columns, the recorded cancellation request and the control
-- receipts are removed.
DROP TABLE IF EXISTS ai_control.ai_run_control_command;
DROP INDEX IF EXISTS ai_control.ai_run_claimable;
ALTER TABLE ai_control.ai_run
    DROP CONSTRAINT IF EXISTS ai_run_lease_check,
    DROP CONSTRAINT IF EXISTS ai_run_cancel_pair_check,
    DROP CONSTRAINT IF EXISTS ai_run_landing_check,
    DROP CONSTRAINT IF EXISTS ai_run_reason_check,
    DROP CONSTRAINT IF EXISTS ai_run_fence_check,
    DROP CONSTRAINT IF EXISTS ai_run_attempt_no_check;
ALTER TABLE ai_control.ai_run
    DROP COLUMN IF EXISTS fence,
    DROP COLUMN IF EXISTS attempt_deadline_at,
    DROP COLUMN IF EXISTS lease_until,
    DROP COLUMN IF EXISTS lease_owner,
    DROP COLUMN IF EXISTS reason,
    DROP COLUMN IF EXISTS cancel_requested_at_text,
    DROP COLUMN IF EXISTS cancel_requested_at,
    DROP COLUMN IF EXISTS attempt_no;
