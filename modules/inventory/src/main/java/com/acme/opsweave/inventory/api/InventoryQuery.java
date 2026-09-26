package com.acme.opsweave.inventory.api;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.inventory.domain.EntityPageQuery;
import com.acme.opsweave.inventory.domain.EntityVisibility;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lookup is always scoped by the trusted tenant, never by a client-supplied tenant field. */
public interface InventoryQuery {
    Optional<EntityView> find(TenantId tenantId, EntityId entityId);

    List<EntityView> list(TenantId tenantId);

    /** Apply trusted object visibility and filters before pagination; return at most limit+1. */
    default List<EntityView> page(TenantId tenantId, EntityVisibility visibility, EntityPageQuery query) {
        throw new IllegalStateException("Inventory paging unavailable");
    }

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
