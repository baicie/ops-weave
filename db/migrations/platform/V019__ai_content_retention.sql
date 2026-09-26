-- Scrub expired payloads without releasing primary keys, session uniqueness or reference scopes.
ALTER TABLE ai_control.evidence_snapshot ALTER COLUMN snapshot DROP NOT NULL;
ALTER TABLE ai_control.evidence_snapshot ADD COLUMN retired_metadata jsonb;
ALTER TABLE ai_control.evidence_snapshot ADD COLUMN retention_created_at timestamptz;
ALTER TABLE ai_control.evidence_snapshot ADD COLUMN retention_expires_at timestamptz;
UPDATE ai_control.evidence_snapshot SET retention_created_at=(snapshot->'evidence'->>'availableAt')::timestamptz,
    retention_expires_at=(snapshot->'evidence'->>'expiresAt')::timestamptz;
ALTER TABLE ai_control.evidence_snapshot ALTER COLUMN retention_created_at SET NOT NULL;
ALTER TABLE ai_control.evidence_snapshot ALTER COLUMN retention_expires_at SET NOT NULL;
ALTER TABLE ai_control.evidence_snapshot ADD CONSTRAINT evidence_retention_state CHECK (
    (snapshot IS NOT NULL AND retired_metadata IS NULL) OR
    (snapshot IS NULL AND retired_metadata IS NOT NULL AND jsonb_typeof(retired_metadata)='object' AND octet_length(retired_metadata::text)<=8192));
CREATE INDEX evidence_retention_candidates ON ai_control.evidence_snapshot(tenant_id,retention_created_at,id) WHERE snapshot IS NOT NULL;

ALTER TABLE ai_control.ai_insight ALTER COLUMN snapshot DROP NOT NULL;
ALTER TABLE ai_control.ai_insight ADD COLUMN retired_metadata jsonb;
ALTER TABLE ai_control.ai_insight ADD COLUMN retention_created_at timestamptz;
ALTER TABLE ai_control.ai_insight ADD COLUMN retention_expires_at timestamptz;
UPDATE ai_control.ai_insight SET retention_created_at=saved_at_text::timestamptz,retention_expires_at=expires_at_text::timestamptz;
ALTER TABLE ai_control.ai_insight ALTER COLUMN retention_created_at SET NOT NULL;
ALTER TABLE ai_control.ai_insight ALTER COLUMN retention_expires_at SET NOT NULL;
ALTER TABLE ai_control.ai_insight ADD CONSTRAINT insight_retention_state CHECK (
    (snapshot IS NOT NULL AND retired_metadata IS NULL) OR
    (snapshot IS NULL AND retired_metadata IS NOT NULL AND jsonb_typeof(retired_metadata)='object' AND octet_length(retired_metadata::text)<=8192));
CREATE INDEX insight_retention_candidates ON ai_control.ai_insight(tenant_id,retention_created_at,id) WHERE snapshot IS NOT NULL;
CREATE INDEX tool_audit_retention_candidates ON audit.tool_read_call(tenant_id,started_at,id);

CREATE TABLE ai_control.retention_receipt (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    body jsonb NOT NULL CHECK(jsonb_typeof(body)='object' AND octet_length(body::text)<=32768),
    PRIMARY KEY(tenant_id,request_id)
);
