package com.acme.opsweave.catalog.api;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.catalog.domain.ModelExtension;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** Tenant-namespaced extension overlays, separate from the immutable model catalog body. */
public interface ModelExtensionStore {
    record Entry(ModelExtension extension, String state, int editVersion, Instant updatedAt) {}

    /** Saves the caller's private draft; expectedEditVersion zero creates, the revision is server-assigned. */
    Entry save(TenantId tenant, String owner, ModelDefinition.Ref base, List<ModelDefinition.Field> fields, int expectedEditVersion, Instant now);

    /** Publishes exactly the saved draft as the next immutable additive extension revision. */
    Entry publish(TenantId tenant, String owner, ModelDefinition.Ref base, int expectedEditVersion, String digest, Instant now);

    Optional<Entry> find(TenantId tenant, ModelDefinition.Ref base, int revision);
    Optional<Entry> latest(TenantId tenant, ModelDefinition.Ref base);
    List<Entry> published(TenantId tenant, int limit);
    List<Entry> drafts(TenantId tenant, String owner, int limit);
}
