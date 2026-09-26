CREATE SCHEMA IF NOT EXISTS ai_control;
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE IF NOT EXISTS ai_control.tool_read_session (
  tenant_id varchar(128) NOT NULL,
  id uuid NOT NULL,
  subject_id varchar(128) NOT NULL,
  incident_id uuid NOT NULL,
  incident_version bigint NOT NULL CHECK (incident_version > 0),
  entity_ids uuid[] NOT NULL CHECK (cardinality(entity_ids) <= 5),
  from_text varchar(40) NOT NULL,
  to_text varchar(40) NOT NULL,
  created_at_text varchar(40) NOT NULL,
  deadline_at timestamptz NOT NULL,
  used_calls integer NOT NULL DEFAULT 0 CHECK (used_calls BETWEEN 0 AND 4),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, incident_id) REFERENCES incident.incident(tenant_id, id)
);
CREATE INDEX IF NOT EXISTS tool_read_session_owner ON ai_control.tool_read_session (tenant_id, subject_id, deadline_at);

CREATE TABLE IF NOT EXISTS ai_control.evidence_snapshot (
  tenant_id varchar(128) NOT NULL,
  id uuid NOT NULL,
  session_id uuid NOT NULL,
  incident_id uuid NOT NULL,
  snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object' AND octet_length(snapshot::text) <= 32768),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, session_id) REFERENCES ai_control.tool_read_session(tenant_id, id),
  FOREIGN KEY (tenant_id, incident_id) REFERENCES incident.incident(tenant_id, id)
);

CREATE TABLE IF NOT EXISTS audit.tool_read_call (
  id uuid PRIMARY KEY,
  tenant_id varchar(128) NOT NULL,
  subject_id varchar(128) NOT NULL,
  session_id uuid,
  evidence_id uuid,
  tool varchar(64) NOT NULL,
  outcome varchar(64) NOT NULL,
  started_at timestamptz NOT NULL,
  completed_at timestamptz,
  result_bytes integer NOT NULL DEFAULT 0 CHECK (result_bytes BETWEEN 0 AND 32768)
);
CREATE INDEX IF NOT EXISTS tool_read_call_session ON audit.tool_read_call (tenant_id, session_id, started_at);
