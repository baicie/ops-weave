CREATE TABLE IF NOT EXISTS ai_control.ai_insight (
    tenant_id varchar(128) NOT NULL,
    id uuid NOT NULL,
    subject_id varchar(128) NOT NULL,
    session_id uuid NOT NULL,
    incident_id uuid NOT NULL,
    incident_version bigint NOT NULL CHECK (incident_version > 0),
    request_digest varchar(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    saved_at_text varchar(40) NOT NULL,
    expires_at_text varchar(40) NOT NULL,
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object' AND octet_length(snapshot::text) <= 131072),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, session_id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES ai_control.tool_read_session (tenant_id, id),
    FOREIGN KEY (tenant_id, incident_id) REFERENCES incident.incident (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS ai_insight_incident ON ai_control.ai_insight (tenant_id, incident_id, id);
CREATE TABLE IF NOT EXISTS ai_control.ai_insight_evidence (
    tenant_id varchar(128) NOT NULL,
    insight_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, insight_id, evidence_id),
    FOREIGN KEY (tenant_id, insight_id) REFERENCES ai_control.ai_insight (tenant_id, id),
    FOREIGN KEY (tenant_id, evidence_id) REFERENCES ai_control.evidence_snapshot (tenant_id, id)
);
