package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transaction boundary for relation instance writes and bounded reads. */
public interface RelationStore {
    WriteResult write(TenantId tenant, UUID requestId, EntityRelation relation, long expectedFromVersion, long expectedToVersion);
    List<EntityRelation> page(TenantId tenant, EntityId endpoint, UUID after, Instant asOf, int limit);

    record WriteResult(EntityRelation relation, boolean replayed) {}
    final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
    final class Access extends RuntimeException {
        public Access(String message) { super(message); }
    }
    final class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }
    final class ScanBudgetExceeded extends RuntimeException {
        public ScanBudgetExceeded() { super("Relation visibility scan budget exhausted"); }
    }
}
