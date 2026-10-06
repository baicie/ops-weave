-- Operator must verify no retained or pending replay receipt before destructive rollback.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM integration.workflow_log_replay_plan) OR EXISTS (SELECT 1 FROM integration.workflow_log_replay_receipt) THEN
        RAISE EXCEPTION 'Replay records must be preserved before rollback';
    END IF;
END $$;
DROP TABLE integration.workflow_log_replay_receipt;
DROP TABLE integration.workflow_log_replay_plan;
