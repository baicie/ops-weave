package com.acme.opsweave.inventory.api;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Optional;

/** Authorization is enforced by the application boundary, not by caller-supplied tenant JSON. */
public interface InventoryQuery {
    Optional<EntityView> find(TenantId tenantId, EntityId entityId);
    record EntityView(EntityId id, String type, String name, long version) {}
}
