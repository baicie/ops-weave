-- Explicit relation corrections and multi-source confirmation entries (ADR-102).
-- Corrections never rewrite the previous relation row: it only receives its effective end time and
-- the operator reason, while this table links it to its corrected replacement. Confirmation entries
-- hang on the single relation instance record; one entry is one independent source (or manual)
-- assertion with its deterministic identity (source kind + source key + batch reference). A
-- withdrawal only marks its own entry; rows and other entries are never deleted.
CREATE TABLE IF NOT EXISTS inventory.entity_relation_correction (
    tenant_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    relation_id uuid NOT NULL,
    corrected_relation_id uuid NOT NULL,
    reason varchar(256) NOT NULL,
    expected_version bigint NOT NULL,
    corrected_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, relation_id) REFERENCES inventory.entity_relation (tenant_id, id),
    FOREIGN KEY (tenant_id, corrected_relation_id) REFERENCES inventory.entity_relation (tenant_id, id),
    CHECK (char_length(reason) BETWEEN 1 AND 256),
    CHECK (expected_version >= 1)
);
-- A relation row is corrected at most once; a corrected-away row is terminated and never revived.
CREATE UNIQUE INDEX IF NOT EXISTS relation_correction_previous_unique
    ON inventory.entity_relation_correction(tenant_id, relation_id);
CREATE INDEX IF NOT EXISTS relation_correction_endpoint_lookup
    ON inventory.entity_relation_correction(tenant_id, corrected_relation_id);
CREATE TABLE IF NOT EXISTS inventory.entity_relation_confirmation (
    tenant_id varchar(128) NOT NULL,
    relation_id uuid NOT NULL,
    source_kind varchar(16) NOT NULL,
    source_key varchar(128) NOT NULL,
    batch_ref varchar(128) NOT NULL,
    data_mode varchar(32) NOT NULL,
    confirmed_at timestamptz NOT NULL,
    confirm_request_id uuid NOT NULL,
    withdrawn_at timestamptz,
    withdraw_request_id uuid,
    withdraw_reason varchar(256),
    PRIMARY KEY (tenant_id, relation_id, source_kind, source_key, batch_ref),
    FOREIGN KEY (tenant_id, relation_id) REFERENCES inventory.entity_relation (tenant_id, id),
    CHECK (source_kind IN ('manual', 'source')),
    CHECK (data_mode IN ('fixture', 'zabbix-jsonrpc', 'import', 'unknown')),
    CHECK (withdrawn_at IS NULL OR (withdrawn_at >= confirmed_at AND withdraw_request_id IS NOT NULL
        AND withdraw_reason IS NOT NULL AND char_length(withdraw_reason) BETWEEN 1 AND 256))
);
CREATE UNIQUE INDEX IF NOT EXISTS relation_confirmation_request_unique
    ON inventory.entity_relation_confirmation(tenant_id, confirm_request_id);
CREATE UNIQUE INDEX IF NOT EXISTS relation_confirmation_withdraw_request_unique
    ON inventory.entity_relation_confirmation(tenant_id, withdraw_request_id) WHERE withdraw_request_id IS NOT NULL;
