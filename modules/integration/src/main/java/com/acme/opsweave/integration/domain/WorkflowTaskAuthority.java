package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Private server-issued policy reference. No credential or serialized Principal is stored. */
public record WorkflowTaskAuthority(UUID id, String issuer, String externalSubject, String grantDigest,
                                    Instant issuedAt, Instant expiresAt, int maxBatches, int consumedBatches) {
    public WorkflowTaskAuthority {
        Objects.requireNonNull(id); Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
        if (issuer == null || issuer.isBlank() || issuer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 || issuer.codePoints().anyMatch(Character::isISOControl)
            || externalSubject == null || externalSubject.isBlank() || externalSubject.length() > 255
            || externalSubject.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255
            || externalSubject.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        WorkflowDefinition.checkDigest(grantDigest);
        if (!expiresAt.isAfter(issuedAt) || expiresAt.isAfter(issuedAt.plusSeconds(900))
            || maxBatches < 1 || maxBatches > 20 || consumedBatches < 0 || consumedBatches > maxBatches)
            throw new IllegalArgumentException();
    }

    public void requireBudget(Instant now) {
        if (now.isBefore(issuedAt) || !now.isBefore(expiresAt))
            throw new WorkflowFailure(WorkflowFailure.Code.AUTHORIZATION_EXPIRED);
        if (consumedBatches >= maxBatches) throw new WorkflowFailure(WorkflowFailure.Code.EXECUTION_LIMIT);
    }

    public WorkflowTaskAuthority consume() {
        if (consumedBatches >= maxBatches) throw new WorkflowFailure(WorkflowFailure.Code.EXECUTION_LIMIT);
        return new WorkflowTaskAuthority(id, issuer, externalSubject, grantDigest, issuedAt, expiresAt, maxBatches, consumedBatches + 1);
    }
}
