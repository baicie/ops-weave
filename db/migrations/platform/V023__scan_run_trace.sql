-- Bounded newest-first read of stored scans for one source scope. No product data is added and no
-- existing row is rewritten; the trace only reads what a previous scan already stored.
CREATE INDEX source_sync_run_recent
    ON integration.source_sync_run (tenant_id, source_instance_id, object_type, started_at DESC, id DESC);
