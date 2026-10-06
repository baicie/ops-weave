-- Historical selection and one output attempt per selection. Values stay in the metric engine.
CREATE TABLE integration.workflow_metric_replay_plan (
    tenant_id text NOT NULL, owner_subject text NOT NULL, request_id uuid NOT NULL,
    workflow_id text NOT NULL, revision integer NOT NULL,
    body jsonb NOT NULL, created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_subject, request_id),
    FOREIGN KEY (tenant_id, workflow_id, revision) REFERENCES integration.workflow_version(tenant_id, workflow_id, revision)
);
CREATE INDEX workflow_metric_replay_plan_selection ON integration.workflow_metric_replay_plan(tenant_id, owner_subject, workflow_id, revision, created_at DESC, request_id);
CREATE TABLE integration.workflow_metric_replay_receipt (
    tenant_id text NOT NULL, owner_subject text NOT NULL, request_id uuid NOT NULL, plan_id uuid NOT NULL,
    body jsonb NOT NULL, accepted_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_subject, request_id),
    UNIQUE (tenant_id, owner_subject, plan_id),
    FOREIGN KEY (tenant_id, owner_subject, plan_id) REFERENCES integration.workflow_metric_replay_plan(tenant_id, owner_subject, request_id)
);
