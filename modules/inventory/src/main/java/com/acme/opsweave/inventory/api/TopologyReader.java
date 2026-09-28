package com.acme.opsweave.inventory.api;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.inventory.domain.*;
import java.time.Instant;
import java.util.Optional;
public interface TopologyReader {
    /** Filter both endpoints by trusted tenant/object scope before LIMIT, within one snapshot. */
    Optional<EntityTopology> read(TenantId tenant, EntityId center, EntityVisibility visibility, Instant asOf);
}
