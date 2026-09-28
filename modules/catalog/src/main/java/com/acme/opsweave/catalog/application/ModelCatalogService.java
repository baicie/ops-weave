package com.acme.opsweave.catalog.application;

import com.acme.opsweave.catalog.api.ModelCatalogStore;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.identity.domain.*;
import java.time.Clock;
import java.util.*;

public final class ModelCatalogService {
    private final ModelCatalogStore store;
    private final List<ModelDefinition> builtins;
    private final Clock clock;
    public ModelCatalogService(ModelCatalogStore store, List<ModelDefinition> builtins, Clock clock) { this.store = store; this.builtins = List.copyOf(builtins); this.clock = clock; }
    public record Page(List<ModelCatalogStore.Entry> items, boolean truncated) {}
    public void authorize(Principal principal, boolean write) {
        Permission permission = write ? Permission.ENTITY_MANAGE : Permission.ENTITY_READ;
        if (new Authorizer().decide(principal, new ResourceRef(principal.tenantId(), "catalog", "*"), permission).denied()) throw new CatalogFailure(CatalogFailure.Code.FORBIDDEN);
    }
    public Page published(Principal p) { authorize(p, false); return page(store.published(p.tenantId(), 51)); }
    public Page drafts(Principal p) { authorize(p, false); return page(store.drafts(p.tenantId(), p.subjectId().value(), 51)); }
    private Page page(List<ModelCatalogStore.Entry> items) { return new Page(items.stream().limit(50).toList(), items.size() > 50); }
    public ModelCatalogStore.Entry save(Principal p, ModelDefinition definition, int expected) {
        authorize(p, true); CatalogRules.editable(definition, expected);
        return store.save(p.tenantId(), p.subjectId().value(), definition, expected, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    public ModelCatalogStore.Entry publish(Principal p, ModelDefinition.Ref ref, int expected, String digest) {
        authorize(p, true);
        if (!ref.id().startsWith("custom.") || expected < 1 || expected > 1000000 || digest == null || !digest.matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid publication");
        return store.publish(p.tenantId(), p.subjectId().value(), ref, expected, digest, builtins, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    public ModelCatalogStore.Entry find(Principal p, ModelDefinition.Ref ref) {
        authorize(p, false);
        return store.find(p.tenantId(), ref).orElseThrow(() -> new CatalogFailure(CatalogFailure.Code.NOT_FOUND));
    }
    public ModelPreview preview(Principal p, ModelDefinition definition, Map<String, Object> sample) {
        authorize(p, false); return ModelPreview.evaluate(definition, sample);
    }
}
