package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record Entity(
    EntityId id,
    TenantId tenantId,
    String entityType,
    String name,
    Lifecycle lifecycle,
    long version,
    Instant lastSeen,
    Map<String, Object> attributes
) {
    public Entity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(lastSeen, "lastSeen");
        Objects.requireNonNull(attributes, "attributes");
        if (entityType.isBlank() || entityType.length() > 64) {
            throw new IllegalArgumentException("Invalid entityType");
        }
        if (name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException("Invalid name");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Invalid version");
        }
        attributes = Map.copyOf(attributes);
    }
}
