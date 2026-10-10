-- Workflow RELATION output receipts: written relation ids and unresolved (pending) rows are part
-- of the runtime execution and host batch bodies. Pre-existing rows are entity-only outputs and
-- backfill with empty relation outcomes so the closed parser keeps reading them unchanged.
UPDATE integration.workflow_execution
   SET body = body || '{"relationIds":[],"pending":[]}'::jsonb
 WHERE NOT body ? 'relationIds';
UPDATE integration.workflow_host_batch
   SET body = body || '{"relationIds":[],"pending":[]}'::jsonb
 WHERE NOT body ? 'relationIds';
