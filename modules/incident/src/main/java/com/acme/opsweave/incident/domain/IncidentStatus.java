package com.acme.opsweave.incident.domain;

public enum IncidentStatus {
    OPEN, INVESTIGATING, MITIGATED, RESOLVED, CLOSED;

    /** Skeleton business rule. Caller authorization and optimistic locking remain separate. */
    public boolean canTransitionTo(IncidentStatus target) {
        if (target == null || target == this) return false;
        return switch (this) {
            case OPEN -> target == INVESTIGATING;
            case INVESTIGATING -> target == MITIGATED;
            case MITIGATED -> target == RESOLVED || target == INVESTIGATING;
            case RESOLVED -> target == CLOSED || target == INVESTIGATING;
            case CLOSED -> false;
        };
    }
}
