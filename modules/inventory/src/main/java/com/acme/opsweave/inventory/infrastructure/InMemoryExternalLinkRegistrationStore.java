package com.acme.opsweave.inventory.infrastructure;

import com.acme.opsweave.inventory.api.ExternalLinkRegistrationStore;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalLinkRegistration;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Labeled in-memory external-link registrations. Not a production store and not a silent fixture. */
public final class InMemoryExternalLinkRegistrationStore implements ExternalLinkRegistrationStore {
    private final InMemoryInventoryStore inventory;
    private final Map<String, Entry> receipts = new HashMap<>();

    public InMemoryExternalLinkRegistrationStore(InMemoryInventoryStore inventory) {
        this.inventory = inventory;
    }

    @Override
    public synchronized Result register(ExternalLinkRegistration registration, Instant registeredAt) {
        String requestKey = requestKey(registration.key().tenantId(), registration.requestId());
        var existing = receipts.get(requestKey);
        if (existing != null) {
            if (existing.registration().sameContent(registration)) return new Result(existing, true);
            throw new Conflict("External link request reused");
        }
        var bound = inventory.linkOf(registration.key());
        if (bound.isPresent() && !bound.get().entityId().equals(registration.targetId())) {
            throw new Conflict("External key already bound to another entity");
        }
        try {
            inventory.registerLink(new ExternalLink(registration.targetId(), registration.key()));
        } catch (IllegalStateException rebind) {
            throw new Conflict("External key already bound to another entity");
        }
        var entry = new Entry(registration, registeredAt);
        receipts.put(requestKey, entry);
        return new Result(entry, false);
    }

    @Override
    public synchronized Optional<Entry> find(TenantId tenant, UUID requestId) {
        return Optional.ofNullable(receipts.get(requestKey(tenant, requestId)));
    }

    @Override
    public synchronized List<Entry> page(TenantId tenant, String namespace, UUID after, int limit) {
        var cursor = after == null ? null : after.toString();
        return receipts.entrySet().stream()
            .filter(e -> e.getValue().registration().key().tenantId().equals(tenant))
            .filter(e -> namespace == null || namespace.isBlank() || e.getValue().registration().key().sourceInstanceId().equals(namespace))
            .filter(e -> cursor == null || e.getValue().registration().requestId().toString().compareTo(cursor) > 0)
            .sorted(Map.Entry.comparingByKey())
            .limit(limit + 1L)
            .map(Map.Entry::getValue)
            .toList();
    }

    private static String requestKey(TenantId tenant, UUID requestId) {
        return tenant.value() + '\0' + requestId;
    }
}
