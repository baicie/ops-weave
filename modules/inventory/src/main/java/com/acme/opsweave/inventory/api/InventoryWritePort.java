package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Set;

public interface InventoryWritePort {
    /** Unsupported stores fail closed; the configured production adapter must fence every scan write. */
    default SourceScan.Token beginScan(SourceScan.Scope scope, java.util.UUID runId) { throw new UnsupportedOperationException("Source scan fencing is required"); }
    default void renewScan(SourceScan.Token scan) { throw new UnsupportedOperationException("Source scan fencing is required"); }
    default void upsert(SourceScan.Token scan, Entity entity, Observation observation, ExternalLink link) { throw new UnsupportedOperationException("Source scan fencing is required"); }
    default int finishScan(SourceScan.Token scan, Set<String> observedExternalIds) { throw new UnsupportedOperationException("Source scan fencing is required"); }
    default void releaseScan(SourceScan.Token scan) { throw new UnsupportedOperationException("Source scan fencing is required"); }

    /** Internal standalone writes must reject an unreleased managed scan; HTTP ingestion uses the fenced overload. */
    void upsert(Entity entity, Observation observation, ExternalLink link);

    /**
     * Marks linked entities whose external id was absent from a finished offset scan.
     * {@code observedExternalIds} is source presence, including objects whose mapping was rejected.
     * Callers must not invoke this when the scan failed or the page walk did not finish.
     * An offset walk can miss an object that still exists if the source changes mid-scan.
     */
    int retireMissing(TenantId tenantId, String sourceInstanceId, String externalType, Set<String> observedExternalIds);
}
