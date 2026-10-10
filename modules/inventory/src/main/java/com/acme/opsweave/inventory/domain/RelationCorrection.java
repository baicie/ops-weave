package com.acme.opsweave.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One explicit relation correction record: the terminated previous relation, its corrected
 *  replacement and the operator reason. The record is append-only traceability; the previous
 *  relation row keeps its own endpoints, type, source annotation and history. */
public record RelationCorrection(UUID requestId, UUID previousRelationId, UUID relationId, String reason,
        long expectedVersion, Instant correctedAt) {
    public RelationCorrection {
        Objects.requireNonNull(requestId); Objects.requireNonNull(previousRelationId); Objects.requireNonNull(relationId);
        Objects.requireNonNull(correctedAt);
        if (previousRelationId.equals(relationId)) throw new IllegalArgumentException("Correction must link distinct relations");
        if (reason == null || reason.isBlank() || reason.length() > 256 || reason.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid relation correction reason");
        if (expectedVersion < 1) throw new IllegalArgumentException("Invalid relation version");
    }
}
