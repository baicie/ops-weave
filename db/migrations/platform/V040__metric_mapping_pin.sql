-- Existing bindings remain unpinned; only an explicit authorized command may adopt a definition.
ALTER TABLE telemetry.metric_binding ADD COLUMN mapping_pin jsonb;
ALTER TABLE telemetry.metric_binding ADD CONSTRAINT metric_binding_mapping_pin_shape CHECK (
    mapping_pin IS NULL OR (jsonb_typeof(mapping_pin)='object' AND mapping_pin ?& ARRAY['id','revision','digest']
    AND mapping_pin - ARRAY['id','revision','digest']='{}'::jsonb
    AND jsonb_typeof(mapping_pin->'id')='string' AND (mapping_pin->>'id') ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$'
    AND jsonb_typeof(mapping_pin->'revision')='number' AND mapping_pin->>'revision'=mapping_revision::text
    AND jsonb_typeof(mapping_pin->'digest')='string' AND (mapping_pin->>'digest') ~ '^sha256:[a-f0-9]{64}$'));
CREATE TABLE telemetry.metric_mapping_command (
    tenant_id varchar(128) NOT NULL,
    owner_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    source_instance_id varchar(64) NOT NULL,
    external_item_id varchar(20) NOT NULL,
    body jsonb NOT NULL CHECK(jsonb_typeof(body)='object' AND octet_length(body::text)<=32768),
    PRIMARY KEY(tenant_id,owner_id,request_id)
);
