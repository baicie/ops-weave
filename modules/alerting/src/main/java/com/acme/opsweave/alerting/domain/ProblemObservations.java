package com.acme.opsweave.alerting.domain;

/** Deterministic occurrence projection. Persistence must compare/update atomically. */
public final class ProblemObservations {
    private ProblemObservations() {}
    public static ExternalProblem merge(ExternalProblem previous, ExternalProblem incoming) {
        if (!previous.tenantId().equals(incoming.tenantId()) || !previous.sourceInstanceId().equals(incoming.sourceInstanceId())
            || !previous.problemEventId().equals(incoming.problemEventId()) || !previous.triggerId().equals(incoming.triggerId())
            || !previous.occurredAt().equals(incoming.occurredAt())) throw new IllegalArgumentException("Occurrence identity conflict");
        if (incoming.observedAt().isBefore(previous.observedAt())) return previous;
        if (previous.recoveryEventId() != null && incoming.recoveryEventId() != null && !previous.recoveryEventId().equals(incoming.recoveryEventId())) throw new IllegalArgumentException("Recovery identity conflict");
        if (previous.recoveredAt() != null && incoming.recoveredAt() != null && !previous.recoveredAt().equals(incoming.recoveredAt())) throw new IllegalArgumentException("Recovery time conflict");
        String recovery = incoming.recoveryEventId() == null ? previous.recoveryEventId() : incoming.recoveryEventId();
        var recoveredAt = incoming.recoveredAt() == null ? previous.recoveredAt() : incoming.recoveredAt();
        var merged = new ExternalProblem(previous.tenantId(), previous.sourceInstanceId(), previous.problemEventId(),
            previous.triggerId(), incoming.title(), incoming.severity(), previous.occurredAt(), incoming.observedAt(),
            incoming.hostIds(), incoming.suppressed(), recovery, recoveredAt);
        if (incoming.observedAt().equals(previous.observedAt()) && !merged.equals(previous)) throw new IllegalArgumentException("Conflicting observations at the same time");
        return merged;
    }
}
