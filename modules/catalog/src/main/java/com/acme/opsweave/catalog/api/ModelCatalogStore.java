package com.acme.opsweave.catalog.api;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

public interface ModelCatalogStore {
    record Entry(ModelDefinition definition, String digest, String state, int editVersion, Instant updatedAt) {}
    Entry save(TenantId tenant, String owner, ModelDefinition definition, int expectedEditVersion, Instant now);
    Entry publish(TenantId tenant, String owner, ModelDefinition.Ref ref, int expectedEditVersion, String digest, List<ModelDefinition> builtins, Instant now);
    Optional<Entry> find(TenantId tenant, ModelDefinition.Ref ref);
    List<Entry> published(TenantId tenant, int limit);
    List<Entry> drafts(TenantId tenant, String owner, int limit);
}
