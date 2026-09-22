package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Set;

public interface InventoryWritePort {
    void upsert(Entity entity, Observation observation, ExternalLink link);

    /**
     * Marks linked entities whose external id was absent from a finished offset scan.
     * {@code observedExternalIds} is source presence, including objects whose mapping was rejected.
     * Callers must not invoke this when the scan failed or the page walk did not finish.
     * An offset walk can miss an object that still exists if the source changes mid-scan.
     */
    int retireMissing(TenantId tenantId, String sourceInstanceId, String externalType, Set<String> observedExternalIds);
}
