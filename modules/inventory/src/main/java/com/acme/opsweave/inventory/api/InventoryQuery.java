package com.acme.opsweave.inventory.api;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lookup is always scoped by the trusted tenant, never by a client-supplied tenant field. */
public interface InventoryQuery {
    Optional<EntityView> find(TenantId tenantId, EntityId entityId);

    List<EntityView> list(TenantId tenantId);

    record EntityView(
        EntityId id,
        TenantId tenantId,
        String type,
        String name,
        String lifecycle,
        long version,
        Map<String, Object> attributes
    ) {}
}
