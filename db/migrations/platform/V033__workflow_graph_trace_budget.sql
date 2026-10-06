-- DAG trace metadata: at most 5 records x 16 nodes with closed field error lists.
-- No input values, credentials, or arbitrary exception bodies are stored in receipts.
ALTER TABLE integration.workflow_run DROP CONSTRAINT workflow_run_receipt_check;
ALTER TABLE integration.workflow_run ADD CONSTRAINT workflow_run_receipt_check CHECK (octet_length(receipt::text)<=524288);
