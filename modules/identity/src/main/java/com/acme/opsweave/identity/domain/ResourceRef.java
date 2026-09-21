package com.acme.opsweave.identity.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Objects;

public record ResourceRef(TenantId tenantId, String type, String id) {
    public ResourceRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (!type.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Invalid resource type");
        }
        if (id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Invalid resource id");
        }
    }

    public static ResourceRef entity(TenantId tenantId, EntityId entityId) {
        return new ResourceRef(tenantId, "entity", entityId.value().toString());
    }

    public static ResourceRef anyEntity(TenantId tenantId) {
        return new ResourceRef(tenantId, "entity", "*");
    }

    public static ResourceRef source(TenantId tenantId, String sourceInstanceId) {
        return new ResourceRef(tenantId, "source", sourceInstanceId);
    }
}
