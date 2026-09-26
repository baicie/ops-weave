-- Old observations/snapshots keep their original entity. Only the current binding is corrected.
CREATE TABLE inventory.source_binding_correction (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    request_id uuid NOT NULL,
    namespace varchar(64) NOT NULL,
    actor varchar(128) NOT NULL,
    previous_entity_id uuid NOT NULL,
    target_entity_id uuid NOT NULL,
    body jsonb NOT NULL CHECK(octet_length(body::text)<=16384),
    PRIMARY KEY(tenant_id,source_instance_id,request_id),
    FOREIGN KEY(tenant_id,source_instance_id,request_id) REFERENCES inventory.source_snapshot(tenant_id,source_instance_id,request_id),
    FOREIGN KEY(tenant_id,previous_entity_id) REFERENCES inventory.entity(tenant_id,id),
    FOREIGN KEY(tenant_id,target_entity_id) REFERENCES inventory.entity(tenant_id,id)
);
CREATE INDEX source_correction_previous ON inventory.source_binding_correction(tenant_id,source_instance_id,previous_entity_id,request_id);
CREATE INDEX source_correction_target ON inventory.source_binding_correction(tenant_id,source_instance_id,target_entity_id,request_id);
