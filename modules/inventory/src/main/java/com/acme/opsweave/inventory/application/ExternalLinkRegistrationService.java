package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.inventory.api.ExternalLinkRegistrationStore;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.domain.ExternalLinkRegistration;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controlled external-link registration. Identity and tenant always come from the trusted principal.
 * The registration binds an explicit external object key to an existing entity so that an unresolved
 * workflow relation endpoint becomes resolvable; it never resolves names or addresses and never
 * rewrites an existing entity binding.
 */
public final class ExternalLinkRegistrationService {
    private static final int PAGE_SCAN_BUDGET = 1000;
    private static final int PAGE_SCAN_CHUNK = 50;
    private final AuthorizationService authorization;
    private final InventoryQuery entities;
    private final ExternalLinkRegistrationStore store;
    private final Clock clock;

    public ExternalLinkRegistrationService(AuthorizationService authorization, InventoryQuery entities,
            ExternalLinkRegistrationStore store, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization);
        this.entities = Objects.requireNonNull(entities);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public ExternalLinkRegistrationStore.Result register(Principal p, UUID requestId, String namespace, String objectType,
            String externalKey, String generation, EntityId targetId, String reason) {
        if (requestId == null) throw new IllegalArgumentException("Invalid external link request id");
        if (targetId == null) throw new IllegalArgumentException("Invalid external link target");
        authorize(p, targetId);
        var entity = entities.find(p.tenantId(), targetId)
            .orElseThrow(() -> new ExternalLinkRegistrationStore.NotFound("Target entity missing"));
        String resolved = (generation == null || generation.isBlank()) ? derive(entity) : generation;
        var registration = ExternalLinkRegistration.of(requestId, p.tenantId(), namespace, objectType, externalKey, resolved, targetId, reason);
        return store.register(registration, clock.instant());
    }

    public List<ExternalLinkRegistrationStore.Entry> page(Principal p, String namespace, UUID after, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid external link limit");
        if (!p.has(Permission.ENTITY_READ)) throw new ExternalLinkRegistrationStore.Access("External link read denied");
        var visible = new ArrayList<ExternalLinkRegistrationStore.Entry>(limit + 1);
        UUID cursor = after;
        int scanned = 0;
        boolean more = false;
        while (scanned < PAGE_SCAN_BUDGET) {
            int chunk = Math.min(PAGE_SCAN_CHUNK, PAGE_SCAN_BUDGET - scanned);
            var rows = store.page(p.tenantId(), namespace, cursor, chunk);
            if (rows.size() > chunk + 1) throw new IllegalStateException("External link store exceeded page bound");
            int count = Math.min(rows.size(), chunk);
            for (int i = 0; i < count; i++) {
                var row = rows.get(i);
                cursor = row.registration().requestId();
                scanned++;
                if (visible(p, row.registration().targetId())) visible.add(row);
                if (visible.size() > limit) return List.copyOf(visible);
            }
            more = rows.size() > chunk;
            if (!more) return List.copyOf(visible);
        }
        if (more) throw new IllegalStateException("External link visibility scan budget exhausted");
        return List.copyOf(visible);
    }

    /** When the generation is not asserted, take it from the target's pinned model; never guess. */
    private static String derive(InventoryQuery.EntityView entity) {
        var model = entity.model();
        if (model == null) throw new IllegalArgumentException("Target entity has no pinned model for generation");
        return model.id();
    }

    private void authorize(Principal p, EntityId id) {
        if (!p.has(Permission.ENTITY_MANAGE)
            || authorization.authorize(p, ResourceRef.entity(p.tenantId(), id), Permission.ENTITY_MANAGE).denied()) {
            throw new ExternalLinkRegistrationStore.Access("External link management denied");
        }
    }

    private boolean visible(Principal p, EntityId id) {
        return authorization.authorize(p, ResourceRef.entity(p.tenantId(), id), Permission.ENTITY_READ).allowed();
    }
}
