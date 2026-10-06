-- Complete windows use a separate table; existing sample keys and data are unchanged.
CREATE TABLE IF NOT EXISTS opsweave_logs.workflow_log_windows (
 tenant_id String,
 owner_scope String,
 request_id UUID,
 workflow_id String,
 workflow_revision UInt32,
 workflow_digest String,
 row_index UInt32,
 source_position String,
 event_time String,
 body String,
 severity_text Nullable(String),
 service_name Nullable(String),
 trace_id Nullable(String),
 span_id Nullable(String)
) ENGINE = ReplacingMergeTree
ORDER BY (tenant_id,owner_scope,workflow_id,workflow_revision,request_id,row_index);
