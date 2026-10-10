package com.acme.opsweave.inventory.infrastructure;

import com.acme.opsweave.inventory.api.RelationStore;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.RelationCardinality;
import com.acme.opsweave.inventory.domain.RelationConfirmation;
import com.acme.opsweave.inventory.domain.RelationCorrection;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** Explicit development store; it never stands in for a configured PostgreSQL store. */
public final class InMemoryRelationStore implements RelationStore {
    private final Map<String, EntityRelation> rows = new HashMap<>();
    private final Map<String, EntityRelation> requests = new HashMap<>();
    private final Map<String, CloseRequest> closeRequests = new HashMap<>();
    private final Map<String, RelationCorrection> correctionRequests = new HashMap<>();
    private final Map<String, StoredConfirmation> confirmations = new HashMap<>();
    private final Map<String, String> confirmRequests = new HashMap<>();
    private final Map<String, String> withdrawRequests = new HashMap<>();

    private record CloseRequest(UUID relationId, Instant validTo, String reason, long expectedVersion) {}
    private record StoredConfirmation(RelationConfirmation entry, UUID confirmRequestId, UUID withdrawRequestId) {}

    @Override public synchronized WriteResult write(TenantId tenant, UUID requestId, EntityRelation relation, long expectedFromVersion, long expectedToVersion, RelationCardinality cardinality) {
        if (!relation.tenantId().equals(tenant)) throw new Conflict("Relation tenant mismatch");
        String key = tenant.value() + '\0' + requestId;
        EntityRelation previous = requests.get(key);
        if (previous != null) {
            if (!sameCommand(previous, relation)) throw new Conflict("Relation request reused");
            return new WriteResult(rows.get(tenant.value() + '\0' + relation.id()), true);
        }
        String relationKey = tenant.value() + '\0' + relation.id();
        if (rows.containsKey(relationKey)) throw new Conflict("Relation id already exists");
        boolean duplicate = rows.values().stream().anyMatch(r -> r.tenantId().equals(tenant) && r.relationType().equals(relation.relationType())
            && r.fromEntityId().equals(relation.fromEntityId()) && r.toEntityId().equals(relation.toEntityId()));
        if (duplicate) throw new Conflict("Relation already recorded");
        if (cardinality.maxActiveFrom() > 0 && openEnded(tenant, relation.fromEntityId(), relation.relationType(), true) >= cardinality.maxActiveFrom())
            throw new Conflict("Relation from endpoint cardinality exceeded");
        if (cardinality.maxActiveTo() > 0 && openEnded(tenant, relation.toEntityId(), relation.relationType(), false) >= cardinality.maxActiveTo())
            throw new Conflict("Relation to endpoint cardinality exceeded");
        rows.put(relationKey, relation); requests.put(key, relation); return new WriteResult(relation, false);
    }

    @Override public synchronized WriteResult close(TenantId tenant, UUID requestId, UUID relationId, Instant validTo, String reason, long expectedVersion) {
        String requestKey = tenant.value() + '\0' + requestId;
        CloseRequest previous = closeRequests.get(requestKey);
        if (previous != null) {
            if (!previous.relationId().equals(relationId) || !previous.validTo().equals(validTo) || !previous.reason().equals(reason) || previous.expectedVersion() != expectedVersion)
                throw new Conflict("Relation close request reused");
            return new WriteResult(rows.get(tenant.value() + '\0' + relationId), true);
        }
        String relationKey = tenant.value() + '\0' + relationId;
        EntityRelation current = rows.get(relationKey);
        if (current == null) throw new NotFound("Relation missing");
        if (current.validTo() != null) throw new Conflict("Relation already closed");
        if (current.version() != expectedVersion) throw new Conflict("Relation version changed");
        var closed = new EntityRelation(current.id(), current.tenantId(), current.fromEntityId(), current.relationType(), current.relationRevision(),
            current.toEntityId(), current.validFrom(), validTo, current.sourceRef(), current.dataMode(), current.version() + 1, reason);
        rows.put(relationKey, closed);
        closeRequests.put(requestKey, new CloseRequest(relationId, validTo, reason, expectedVersion));
        return new WriteResult(closed, false);
    }

    @Override public synchronized WriteResult correct(TenantId tenant, UUID requestId, UUID previousRelationId, String reason, long expectedVersion, EntityRelation replacement, RelationCardinality cardinality) {
        if (!replacement.tenantId().equals(tenant)) throw new CorrectionConflict("Relation tenant mismatch");
        String requestKey = tenant.value() + '\0' + requestId;
        RelationCorrection recorded = correctionRequests.get(requestKey);
        if (recorded != null) {
            EntityRelation previousRow = rows.get(tenant.value() + '\0' + recorded.relationId());
            if (!recorded.previousRelationId().equals(previousRelationId) || !recorded.reason().equals(reason)
                || recorded.expectedVersion() != expectedVersion || previousRow == null || !sameCorrectionValue(previousRow, replacement))
                throw new CorrectionConflict("Relation correction request reused");
            return new WriteResult(previousRow, true);
        }
        String previousKey = tenant.value() + '\0' + previousRelationId;
        EntityRelation current = rows.get(previousKey);
        if (current == null) throw new NotFound("Relation missing");
        if (current.validTo() != null) throw new CorrectionConflict("Relation already closed");
        if (current.version() != expectedVersion) throw new CorrectionConflict("Relation version changed");
        if (!replacement.validFrom().isAfter(current.validFrom())) throw new CorrectionConflict("Relation closes before it starts");
        String replacementKey = tenant.value() + '\0' + replacement.id();
        if (rows.containsKey(replacementKey)) throw new CorrectionConflict("Relation id already exists");
        boolean duplicate = rows.values().stream().anyMatch(r -> r.tenantId().equals(tenant) && r.relationType().equals(replacement.relationType())
            && r.fromEntityId().equals(replacement.fromEntityId()) && r.toEntityId().equals(replacement.toEntityId()));
        if (duplicate) throw new CorrectionConflict("Relation already recorded");
        if (cardinality.maxActiveFrom() > 0 && openEnded(tenant, replacement.fromEntityId(), replacement.relationType(), true, previousRelationId) >= cardinality.maxActiveFrom())
            throw new CorrectionConflict("Relation from endpoint cardinality exceeded");
        if (cardinality.maxActiveTo() > 0 && openEnded(tenant, replacement.toEntityId(), replacement.relationType(), false, previousRelationId) >= cardinality.maxActiveTo())
            throw new CorrectionConflict("Relation to endpoint cardinality exceeded");
        var terminated = new EntityRelation(current.id(), current.tenantId(), current.fromEntityId(), current.relationType(), current.relationRevision(),
            current.toEntityId(), current.validFrom(), replacement.validFrom(), current.sourceRef(), current.dataMode(), current.version() + 1, reason);
        rows.put(previousKey, terminated);
        rows.put(replacementKey, replacement);
        correctionRequests.put(requestKey, new RelationCorrection(requestId, previousRelationId, replacement.id(), reason, expectedVersion, replacement.validFrom()));
        return new WriteResult(replacement, false);
    }

    @Override public synchronized ConfirmationResult confirm(TenantId tenant, UUID requestId, UUID relationId, RelationConfirmation entry) {
        String relationKey = tenant.value() + '\0' + relationId;
        EntityRelation current = rows.get(relationKey);
        if (current == null) throw new NotFound("Relation missing");
        String entryKey = relationKey + '\0' + entry.key().sourceKind() + '\0' + entry.key().sourceKey() + '\0' + entry.key().batchRef();
        String requestKey = tenant.value() + '\0' + requestId;
        String recorded = confirmRequests.get(requestKey);
        if (recorded != null) {
            StoredConfirmation previous = confirmations.get(recorded);
            if (previous == null || !recorded.equals(entryKey) || !previous.entry().dataMode().equals(entry.dataMode()))
                throw new ConfirmationConflict("Relation confirmation request reused");
            return new ConfirmationResult(previous.entry(), true);
        }
        StoredConfirmation existing = confirmations.get(entryKey);
        if (existing != null) {
            if (existing.entry().withdrawn()) throw new ConfirmationConflict("Relation confirmation already withdrawn");
            if (!existing.entry().dataMode().equals(entry.dataMode())) throw new ConfirmationConflict("Relation confirmation conflicts");
            return new ConfirmationResult(existing.entry(), true);
        }
        if (current.validTo() != null) throw new ConfirmationConflict("Relation already closed");
        if (entriesOf(relationKey).size() >= RelationConfirmation.MAX_ENTRIES_PER_RELATION) throw new ConfirmationConflict("Relation confirmation capacity reached");
        confirmations.put(entryKey, new StoredConfirmation(entry, requestId, null));
        confirmRequests.put(requestKey, entryKey);
        return new ConfirmationResult(entry, false);
    }

    @Override public synchronized ConfirmationResult withdraw(TenantId tenant, UUID requestId, UUID relationId, RelationConfirmation.Key key, Instant at, String reason) {
        String relationKey = tenant.value() + '\0' + relationId;
        EntityRelation current = rows.get(relationKey);
        if (current == null) throw new NotFound("Relation missing");
        String entryKey = relationKey + '\0' + key.sourceKind() + '\0' + key.sourceKey() + '\0' + key.batchRef();
        String requestKey = tenant.value() + '\0' + requestId;
        String recorded = withdrawRequests.get(requestKey);
        if (recorded != null) {
            StoredConfirmation previous = confirmations.get(recorded);
            if (previous == null || !recorded.equals(entryKey) || !previous.entry().withdrawn() || !reason.equals(previous.entry().withdrawReason()))
                throw new ConfirmationConflict("Relation confirmation withdrawal request reused");
            return new ConfirmationResult(previous.entry(), true);
        }
        StoredConfirmation existing = confirmations.get(entryKey);
        if (existing == null) throw new NotFound("Relation confirmation missing");
        if (existing.entry().withdrawn()) return new ConfirmationResult(existing.entry(), true);
        var withdrawn = existing.entry().withdraw(at, reason);
        boolean anyConfirmed = entriesOf(relationKey).stream().anyMatch(entry -> !entry.withdrawn() && !entry.key().equals(key));
        if (!anyConfirmed && current.validTo() == null && !at.isAfter(current.validFrom()))
            throw new ConfirmationConflict("Relation closes before it starts");
        confirmations.put(entryKey, new StoredConfirmation(withdrawn, existing.confirmRequestId(), requestId));
        withdrawRequests.put(requestKey, entryKey);
        if (!anyConfirmed && current.validTo() == null) {
            var closed = new EntityRelation(current.id(), current.tenantId(), current.fromEntityId(), current.relationType(), current.relationRevision(),
                current.toEntityId(), current.validFrom(), at, current.sourceRef(), current.dataMode(), current.version() + 1, reason);
            rows.put(relationKey, closed);
        }
        return new ConfirmationResult(withdrawn, false);
    }

    @Override public synchronized Optional<EntityRelation> find(TenantId tenant, UUID relationId) {
        return Optional.ofNullable(rows.get(tenant.value() + '\0' + relationId));
    }

    @Override public synchronized List<EntityRelation> page(TenantId tenant, EntityId endpoint, UUID after, Instant asOf, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid relation limit");
        return rows.values().stream().filter(r -> r.tenantId().equals(tenant) && (r.fromEntityId().equals(endpoint) || r.toEntityId().equals(endpoint)))
            .filter(r -> !r.validFrom().isAfter(asOf) && (r.validTo() == null || r.validTo().isAfter(asOf)))
            .filter(r -> after == null || r.id().toString().compareTo(after.toString()) > 0)
            .sorted(Comparator.comparing(r -> r.id().toString())).limit(limit + 1L).toList();
    }

    @Override public synchronized List<EntityRelation> history(TenantId tenant, EntityId endpoint, UUID after, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid relation limit");
        return rows.values().stream().filter(r -> r.tenantId().equals(tenant) && (r.fromEntityId().equals(endpoint) || r.toEntityId().equals(endpoint)))
            .filter(r -> after == null || r.id().toString().compareTo(after.toString()) > 0)
            .sorted(Comparator.comparing(r -> r.id().toString())).limit(limit + 1L).toList();
    }

    @Override public synchronized List<RelationConfirmation> confirmations(TenantId tenant, UUID relationId) {
        return entriesOf(tenant.value() + '\0' + relationId);
    }

    @Override public synchronized List<RelationCorrection> corrections(TenantId tenant, EntityId endpoint, UUID after, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid relation limit");
        return correctionRequests.entrySet().stream().filter(e -> e.getKey().startsWith(tenant.value() + '\0'))
            .map(Map.Entry::getValue)
            .filter(record -> touches(tenant, record.previousRelationId(), endpoint) || touches(tenant, record.relationId(), endpoint))
            .filter(record -> after == null || record.requestId().toString().compareTo(after.toString()) > 0)
            .sorted(Comparator.comparing(record -> record.requestId().toString())).limit(limit + 1L).toList();
    }

    private boolean touches(TenantId tenant, UUID relationId, EntityId endpoint) {
        EntityRelation row = rows.get(tenant.value() + '\0' + relationId);
        return row != null && (row.fromEntityId().equals(endpoint) || row.toEntityId().equals(endpoint));
    }

    private List<RelationConfirmation> entriesOf(String relationKey) {
        return confirmations.entrySet().stream().filter(e -> e.getKey().startsWith(relationKey + '\0'))
            .map(e -> e.getValue().entry())
            .sorted(Comparator.comparing((RelationConfirmation e) -> e.key().sourceKind()).thenComparing(e -> e.key().sourceKey()).thenComparing(e -> e.key().batchRef()))
            .toList();
    }

    private long openEnded(TenantId tenant, EntityId endpoint, String relationType, boolean fromSide) {
        return openEnded(tenant, endpoint, relationType, fromSide, null);
    }

    /** The corrected-away row is terminated inside the same write and must not consume a cardinality slot. */
    private long openEnded(TenantId tenant, EntityId endpoint, String relationType, boolean fromSide, UUID excluded) {
        return rows.values().stream().filter(r -> r.tenantId().equals(tenant) && r.relationType().equals(relationType))
            .filter(r -> excluded == null || !r.id().equals(excluded))
            .filter(r -> r.validTo() == null && (fromSide ? r.fromEntityId().equals(endpoint) : r.toEntityId().equals(endpoint))).count();
    }

    private static boolean sameCommand(EntityRelation a, EntityRelation b) {
        return a.tenantId().equals(b.tenantId()) && a.fromEntityId().equals(b.fromEntityId()) && a.relationType().equals(b.relationType())
            && a.relationRevision()==b.relationRevision() && a.toEntityId().equals(b.toEntityId()) && a.validFrom().equals(b.validFrom())
            && (a.closeReason() != null || Objects.equals(a.validTo(),b.validTo())) && a.sourceRef().equals(b.sourceRef()) && a.dataMode().equals(b.dataMode());
    }

    private static boolean sameCorrectionValue(EntityRelation recorded, EntityRelation replacement) {
        return recorded.fromEntityId().equals(replacement.fromEntityId()) && recorded.toEntityId().equals(replacement.toEntityId())
            && recorded.relationType().equals(replacement.relationType()) && recorded.relationRevision() == replacement.relationRevision();
    }
}
