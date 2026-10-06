package com.acme.opsweave.inventory.infrastructure;

import com.acme.opsweave.inventory.api.RelationStore;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** Explicit development store; it never stands in for a configured PostgreSQL store. */
public final class InMemoryRelationStore implements RelationStore {
    private final Map<String, EntityRelation> rows = new HashMap<>();
    private final Map<String, EntityRelation> requests = new HashMap<>();
    @Override public synchronized WriteResult write(TenantId tenant, UUID requestId, EntityRelation relation, long expectedFromVersion, long expectedToVersion) {
        if (!relation.tenantId().equals(tenant)) throw new Conflict("Relation tenant mismatch");
        String key = tenant.value() + '\0' + requestId;
        EntityRelation previous = requests.get(key);
        if (previous != null) {
            if (!sameCommand(previous, relation)) throw new Conflict("Relation request reused");
            return new WriteResult(previous, true);
        }
        String relationKey = tenant.value() + '\0' + relation.id();
        if (rows.containsKey(relationKey)) throw new Conflict("Relation id already exists");
        rows.put(relationKey, relation); requests.put(key, relation); return new WriteResult(relation, false);
    }
    @Override public synchronized List<EntityRelation> page(TenantId tenant, EntityId endpoint, UUID after, Instant asOf, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid relation limit");
        return rows.values().stream().filter(r -> r.tenantId().equals(tenant) && (r.fromEntityId().equals(endpoint) || r.toEntityId().equals(endpoint)))
            .filter(r -> !r.validFrom().isAfter(asOf) && (r.validTo() == null || r.validTo().isAfter(asOf)))
            .filter(r -> after == null || r.id().toString().compareTo(after.toString()) > 0)
            .sorted(Comparator.comparing(r -> r.id().toString())).limit(limit + 1L).toList();
    }
    private static boolean sameCommand(EntityRelation a, EntityRelation b) {
        return a.tenantId().equals(b.tenantId()) && a.fromEntityId().equals(b.fromEntityId()) && a.relationType().equals(b.relationType())
            && a.relationRevision()==b.relationRevision() && a.toEntityId().equals(b.toEntityId()) && a.validFrom().equals(b.validFrom())
            && Objects.equals(a.validTo(),b.validTo()) && a.sourceRef().equals(b.sourceRef()) && a.dataMode().equals(b.dataMode());
    }
}
