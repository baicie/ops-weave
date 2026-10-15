-- Roll back the M5 5d Runtime state receipts and cross-store report snapshots. Run rows, submission
-- receipts, execution metadata, control receipts and event records stay intact: only the delivery
-- receipts and the comparison snapshots are removed. The run registry remains readable; the
-- observability aggregate's runtime counters become unavailable, never silently zero.
DROP TABLE IF EXISTS ai_control.ai_run_cross_store_report;
DROP TABLE IF EXISTS ai_control.ai_run_runtime_state;
