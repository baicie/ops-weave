-- Explicit operator rollback only. This refuses to discard receipts that exceed the old budget.
-- Check/export those receipts first; do not truncate or delete them automatically.
BEGIN;
ALTER TABLE integration.workflow_run DROP CONSTRAINT workflow_run_receipt_check;
ALTER TABLE integration.workflow_run ADD CONSTRAINT workflow_run_receipt_check CHECK (octet_length(receipt::text)<=4096);
DELETE FROM integration.schema_migration WHERE id='V033__workflow_graph_trace_budget';
COMMIT;
