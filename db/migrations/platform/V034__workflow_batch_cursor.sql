-- Bounded oldest-first pending batches; completed history before the task cursor is excluded.
CREATE INDEX IF NOT EXISTS sync_run_workflow_completion ON integration.source_sync_run (tenant_id,source_instance_id,object_type,completed_at,id) WHERE completed_at IS NOT NULL;
