package com.acme.opsweave.inventory.infrastructure;

import com.acme.opsweave.inventory.api.EntityModelPinBackfillStore;
import com.acme.opsweave.inventory.domain.EntityModelPin;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Explicit ephemeral store only when inventory.store=memory; never a PostgreSQL fallback. */
public final class InMemoryEntityModelPinBackfillStore implements EntityModelPinBackfillStore {
    private record Request(String entityType, EntityModelPin model, int maxUpdates, Counts counts, Instant completedAt) {}

    private final InMemoryInventoryStore inventory;
    private final Map<String, Request> requests = new HashMap<>();

    public InMemoryEntityModelPinBackfillStore(InMemoryInventoryStore inventory) { this.inventory = inventory; }

    @Override
    public synchronized Receipt backfill(TenantId tenant, UUID requestId, String entityType, EntityModelPin model,
                                        int maxUpdates, Conformance conformance, Instant now) {
        if (tenant == null || requestId == null || model == null || conformance == null || now == null) throw new IllegalArgumentException("Invalid backfill command");
        if (entityType == null || !entityType.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid backfill command");
        if (maxUpdates < 1 || maxUpdates > 500) throw new IllegalArgumentException("Invalid backfill command");
        String key = tenant.value() + '\0' + requestId;
        Request prior = requests.get(key);
        if (prior != null) {
            if (!prior.entityType().equals(entityType) || !prior.model().equals(model) || prior.maxUpdates() != maxUpdates) {
                throw new Conflict("Backfill request reused");
            }
            return new Receipt(requestId, prior.entityType(), prior.model(), prior.counts(), prior.completedAt(), true);
        }
        int scanned = 0, updated = 0, kept = 0, skippedPinned = 0, skippedSource = 0;
        for (var row : inventory.backfillRows(tenant, entityType)) {
            if (row.model() != null) { skippedPinned++; continue; }
            if (inventory.sourceManaged(tenant, row.id())) { skippedSource++; continue; }
            if (scanned >= maxUpdates) continue;
            scanned++;
            boolean accepted;
            try { accepted = conformance.accepts(row.attributes()); } catch (RuntimeException invalid) { accepted = false; }
            if (accepted) { inventory.assignModelPin(tenant, row.id(), model); updated++; } else { kept++; }
        }
        var counts = new Counts(scanned, updated, kept, skippedPinned, skippedSource);
        requests.put(key, new Request(entityType, model, maxUpdates, counts, now));
        return new Receipt(requestId, entityType, model, counts, now, false);
    }

    @Override
    public synchronized Optional<Receipt> receipt(TenantId tenant, UUID requestId) {
        Request stored = requests.get(tenant.value() + '\0' + requestId);
        return Optional.ofNullable(stored)
            .map(value -> new Receipt(requestId, value.entityType(), value.model(), value.counts(), value.completedAt(), false));
    }
}
