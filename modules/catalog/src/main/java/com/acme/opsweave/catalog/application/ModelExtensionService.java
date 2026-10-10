package com.acme.opsweave.catalog.application;

import com.acme.opsweave.catalog.api.ModelCatalogStore;
import com.acme.opsweave.catalog.api.ModelExtensionStore;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.identity.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Tenant extension overlays share the catalog authorization boundary but never edit the base body. */
public final class ModelExtensionService {
    private final ModelExtensionStore store;
    private final ModelCatalogStore catalog;
    private final List<ModelDefinition> builtins;
    private final Clock clock;

    public ModelExtensionService(ModelExtensionStore store, ModelCatalogStore catalog, List<ModelDefinition> builtins, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.builtins = List.copyOf(builtins);
        this.clock = clock;
    }

    public record Page(List<ModelExtensionStore.Entry> items, boolean truncated) {}

    public void authorize(Principal principal, boolean write) {
        Permission permission = write ? Permission.ENTITY_MANAGE : Permission.ENTITY_READ;
        if (new Authorizer().decide(principal, new ResourceRef(principal.tenantId(), "catalog", "*"), permission).denied()) throw new CatalogFailure(CatalogFailure.Code.FORBIDDEN);
    }

    public Page published(Principal principal) {
        authorize(principal, false);
        return page(store.published(principal.tenantId(), 51));
    }

    public Page drafts(Principal principal) {
        authorize(principal, false);
        return page(store.drafts(principal.tenantId(), principal.subjectId().value(), 51));
    }

    private Page page(List<ModelExtensionStore.Entry> items) {
        return new Page(items.stream().limit(50).toList(), items.size() > 50);
    }

    /** Built-in definitions are immutable; custom bases must be published inside the trusted tenant. */
    public ModelDefinition base(Principal principal, ModelDefinition.Ref ref) {
        authorize(principal, false);
        return builtins.stream().filter(definition -> definition.ref().equals(ref)).findFirst()
            .orElseGet(() -> catalog.find(principal.tenantId(), ref).map(ModelCatalogStore.Entry::definition)
                .orElseThrow(() -> new CatalogFailure(CatalogFailure.Code.NOT_FOUND)));
    }

    public ModelExtensionStore.Entry save(Principal principal, ModelDefinition.Ref base, List<ModelDefinition.Field> fields, int expectedEditVersion) {
        authorize(principal, true);
        if (expectedEditVersion < 0 || expectedEditVersion >= 1000000) throw new IllegalArgumentException("Invalid extension draft");
        ModelDefinition baseDefinition = base(principal, base);
        new ModelExtension(base, 1, fields).requireCompatibleBase(baseDefinition);
        return store.save(principal.tenantId(), principal.subjectId().value(), base, fields, expectedEditVersion, now());
    }

    public ModelExtensionStore.Entry publish(Principal principal, ModelDefinition.Ref base, int expectedEditVersion, String digest) {
        authorize(principal, true);
        if (expectedEditVersion < 1 || expectedEditVersion > 1000000 || digest == null || !digest.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid extension publication");
        }
        base(principal, base);
        return store.publish(principal.tenantId(), principal.subjectId().value(), base, expectedEditVersion, digest, now());
    }

    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
