-- Operator decisions are separate from uncertain sample output proofs.
CREATE TABLE integration.workflow_sample_recovery (
    tenant_id text NOT NULL, owner_subject text NOT NULL, request_id uuid NOT NULL,
    workflow_id text NOT NULL, revision integer NOT NULL, kind text NOT NULL,
    batch_id uuid NOT NULL, metric_request_id uuid, log_request_id uuid,
    body jsonb NOT NULL CHECK(octet_length(body::text)<=4096), accepted_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,owner_subject,request_id),
    UNIQUE(tenant_id,owner_subject,kind,batch_id),
    CHECK((kind='METRIC_SAMPLE' AND metric_request_id IS NOT NULL AND metric_request_id=batch_id AND log_request_id IS NULL)
       OR (kind='LOG_SAMPLE' AND log_request_id IS NOT NULL AND log_request_id=batch_id AND metric_request_id IS NULL)),
    FOREIGN KEY(tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision),
    FOREIGN KEY(tenant_id,owner_subject,metric_request_id) REFERENCES integration.workflow_metric_output(tenant_id,owner_subject,request_id),
    FOREIGN KEY(tenant_id,owner_subject,log_request_id) REFERENCES integration.workflow_log_output(tenant_id,owner_subject,request_id)
);
