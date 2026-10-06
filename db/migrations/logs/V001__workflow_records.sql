CREATE DATABASE IF NOT EXISTS opsweave_logs;
CREATE TABLE IF NOT EXISTS opsweave_logs.workflow_records (
 tenant_id String,
 owner_scope String,
 request_id UUID,
 workflow_id String,
 workflow_revision UInt32,
 workflow_digest String,
 row_index UInt8,
 event_time String,
 body String,
 severity_text Nullable(String),
 service_name Nullable(String),
 trace_id Nullable(String),
 span_id Nullable(String)
) ENGINE = ReplacingMergeTree
ORDER BY (tenant_id,owner_scope,workflow_id,workflow_revision,request_id,row_index);
