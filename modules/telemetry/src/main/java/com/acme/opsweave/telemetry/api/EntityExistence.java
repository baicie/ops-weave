package com.acme.opsweave.telemetry.api;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;

/** Tenant-scoped existence only. Authorization stays in the query use case. */
public interface EntityExistence {
    boolean exists(TenantId tenantId, EntityId entityId);
}
