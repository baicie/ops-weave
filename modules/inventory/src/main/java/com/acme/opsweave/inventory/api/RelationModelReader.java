package com.acme.opsweave.inventory.api;

import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Optional;

/** Read-only adapter from the model catalog into the inventory boundary. */
public interface RelationModelReader {
    Optional<Definition> find(TenantId tenant, String id, int revision);
    record Definition(String id, int revision, String fromType, int fromRevision, String toType, int toRevision, String cardinality) {}
}
