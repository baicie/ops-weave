-- Roll back the M5 5c run event log. Run rows, submission receipts, execution metadata and control
-- receipts stay intact: only the event records are removed. The run registry remains readable and
-- the cursor readback and push channel become unavailable, never silently empty.
DROP TABLE IF EXISTS ai_control.ai_run_event;
