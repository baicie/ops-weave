-- Stable insertion boundary for immutable recorded checks. Original bodies and times remain unchanged.
ALTER TABLE integration.workflow_diagnostic
    ADD COLUMN history_sequence bigint GENERATED ALWAYS AS IDENTITY,
    ADD CONSTRAINT workflow_diagnostic_history_sequence UNIQUE(history_sequence);
