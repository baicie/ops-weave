-- Business-event ownership moves atomically with human-reviewed association revisions.
ALTER TABLE incident.incident ADD COLUMN IF NOT EXISTS merged_into uuid;
ALTER TABLE incident.incident ADD CONSTRAINT incident_merge_target_fk FOREIGN KEY (tenant_id, merged_into) REFERENCES incident.incident (tenant_id,id);
ALTER TABLE incident.incident ADD CONSTRAINT incident_merge_not_self CHECK (merged_into IS NULL OR merged_into <> id);
CREATE TABLE IF NOT EXISTS incident.reorganization_request (
    tenant_id varchar(128) NOT NULL,
    request_key uuid NOT NULL,
    source_id uuid NOT NULL,
    target_id uuid NOT NULL,
    actor varchar(128) NOT NULL,
    receipt jsonb NOT NULL CHECK (jsonb_typeof(receipt) = 'object' AND octet_length(receipt::text) <= 32768),
    PRIMARY KEY (tenant_id,request_key),
    FOREIGN KEY (tenant_id,source_id) REFERENCES incident.incident (tenant_id,id),
    FOREIGN KEY (tenant_id,target_id) REFERENCES incident.incident (tenant_id,id),
    CHECK (source_id <> target_id)
);
CREATE INDEX IF NOT EXISTS reorganization_source ON incident.reorganization_request (tenant_id,source_id,request_key);
CREATE INDEX IF NOT EXISTS reorganization_target ON incident.reorganization_request (tenant_id,target_id,request_key);
