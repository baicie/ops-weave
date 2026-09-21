package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Set;

public interface InventoryWritePort {
    void upsert(Entity entity, Observation observation, ExternalLink link);

    /**
     * Marks linked entities that were absent from a complete snapshot as inactive.
     * Callers must not invoke this when the scan failed or the snapshot is incomplete.
     */
    int retireMissing(TenantId tenantId, String sourceInstanceId, String externalType, Set<String> seenExternalIds);
}
