package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.RelationCardinality;
import com.acme.opsweave.inventory.domain.RelationConfirmation;
import com.acme.opsweave.inventory.domain.RelationCorrection;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transaction boundary for relation instance writes and bounded reads. */
public interface RelationStore {
    WriteResult write(TenantId tenant, UUID requestId, EntityRelation relation, long expectedFromVersion, long expectedToVersion, RelationCardinality cardinality);
    WriteResult close(TenantId tenant, UUID requestId, UUID relationId, Instant validTo, String reason, long expectedVersion);
    WriteResult correct(TenantId tenant, UUID requestId, UUID previousRelationId, String reason, long expectedVersion, EntityRelation replacement, RelationCardinality cardinality);
    ConfirmationResult confirm(TenantId tenant, UUID requestId, UUID relationId, RelationConfirmation entry);
    ConfirmationResult withdraw(TenantId tenant, UUID requestId, UUID relationId, RelationConfirmation.Key key, Instant at, String reason);
    Optional<EntityRelation> find(TenantId tenant, UUID relationId);
    List<EntityRelation> page(TenantId tenant, EntityId endpoint, UUID after, Instant asOf, int limit);
    List<EntityRelation> history(TenantId tenant, EntityId endpoint, UUID after, int limit);
    List<RelationConfirmation> confirmations(TenantId tenant, UUID relationId);
    List<RelationCorrection> corrections(TenantId tenant, EntityId endpoint, UUID after, int limit);

    record WriteResult(EntityRelation relation, boolean replayed) {}
    record ConfirmationResult(RelationConfirmation entry, boolean replayed) {}
    final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
    final class CorrectionConflict extends RuntimeException {
        public CorrectionConflict(String message) { super(message); }
    }
    final class ConfirmationConflict extends RuntimeException {
        public ConfirmationConflict(String message) { super(message); }
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
