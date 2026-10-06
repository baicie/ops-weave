-- Only scheduling metadata; fixed source pages use the existing bounded scan journal.
CREATE TABLE integration.workflow_host_schedule (
    tenant_id varchar(64) NOT NULL,
    owner_subject varchar(255) NOT NULL,
    workflow_id varchar(48) NOT NULL,
    revision integer NOT NULL,
    body jsonb NOT NULL CHECK (jsonb_typeof(body)='object' AND octet_length(body::text)<=4096),
    PRIMARY KEY (tenant_id,owner_subject,workflow_id),
    FOREIGN KEY (tenant_id,workflow_id,revision) REFERENCES integration.workflow_version(tenant_id,workflow_id,revision)
);
CREATE TABLE integration.workflow_host_schedule_control (
    tenant_id varchar(64) NOT NULL,
    owner_subject varchar(255) NOT NULL,
    request_id uuid NOT NULL,
    workflow_id varchar(48) NOT NULL,
    body jsonb NOT NULL CHECK (jsonb_typeof(body)='object' AND octet_length(body::text)<=8192),
    PRIMARY KEY (tenant_id,owner_subject,request_id),
    FOREIGN KEY (tenant_id,owner_subject,workflow_id) REFERENCES integration.workflow_host_schedule(tenant_id,owner_subject,workflow_id)
);
