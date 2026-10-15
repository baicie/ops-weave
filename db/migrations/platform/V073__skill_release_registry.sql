-- Platform Skill registry (M6, ADR-105). A draft is the authoring record of one Skill coordinate
-- (skill_id@version): closed configuration content, a state machine and a content digest. A
-- published version is an immutable row anchored by its content digest and its review receipt; the
-- release pointer only moves through the explicit publish and rollback commands, so history is
-- never rewritten and an AIRun that pinned a version keeps that pin. Commands are idempotent on the
-- request id: a replay answers from the receipt table, a reused request id with different content
-- is an explicit conflict and no second draft or version is ever created. Nothing in this registry
-- executes a Skill, calls a model or accepts identity from command content.
-- Text columns keep the exact instants (like the AIInsight receipt); timestamptz columns order rows.
CREATE TABLE IF NOT EXISTS ai_control.skill_draft (
    tenant_id varchar(128) NOT NULL,
    draft_id uuid NOT NULL,
    skill_id varchar(64) NOT NULL,
    version varchar(16) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('DRAFT','IN_REVIEW','PUBLISHED')),
    edit_version bigint NOT NULL CHECK (edit_version >= 1),
    content_digest varchar(71) NOT NULL CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 131072),
    author_subject varchar(128) NOT NULL,
    created_at_text varchar(40) NOT NULL,
    updated_at_text varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, draft_id),
    UNIQUE (tenant_id, skill_id, version)
);
CREATE INDEX IF NOT EXISTS skill_draft_recent ON ai_control.skill_draft (tenant_id, created_at DESC, draft_id DESC);

-- One immutable published version per coordinate: content, digest and the review receipt are frozen
-- at publication and no update or delete path exists in the store.
CREATE TABLE IF NOT EXISTS ai_control.skill_version (
    tenant_id varchar(128) NOT NULL,
    skill_id varchar(64) NOT NULL,
    version varchar(16) NOT NULL,
    content_digest varchar(71) NOT NULL CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 131072),
    reviewer_subject varchar(128) NOT NULL,
    published_at_text varchar(40) NOT NULL,
    published_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, skill_id, version)
);
CREATE INDEX IF NOT EXISTS skill_version_recent ON ai_control.skill_version (tenant_id, skill_id, published_at DESC, version DESC);

-- The release pointer of one Skill: which published version new runs pin. Rollback moves it back
-- to an earlier published version and records why; version rows are never rewritten.
CREATE TABLE IF NOT EXISTS ai_control.skill_release (
    tenant_id varchar(128) NOT NULL,
    skill_id varchar(64) NOT NULL,
    current_version varchar(16) NOT NULL,
    updated_at_text varchar(40) NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, skill_id)
);

-- The immutable command receipt. The request id is the idempotency key of one admitted command
-- (save / submit / publish / rollback share one namespace) and the digest covers content only, so a
-- replay answers from here and a reused request id with different content fails closed.
CREATE TABLE IF NOT EXISTS ai_control.skill_command (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('SAVE','SUBMIT','PUBLISH','ROLLBACK')),
    command_digest varchar(71) NOT NULL CHECK (command_digest ~ '^sha256:[0-9a-f]{64}$'),
    receipt jsonb NOT NULL CHECK (jsonb_typeof(receipt) = 'object' AND octet_length(receipt::text) <= 32768),
    created_at_text varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id)
);
CREATE INDEX IF NOT EXISTS skill_command_recent ON ai_control.skill_command (tenant_id, created_at DESC, request_id DESC);
