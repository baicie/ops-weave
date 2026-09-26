package com.acme.opsweave.alerting.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One external problem occurrence. Trigger identity alone is not an occurrence key. */
public record ExternalProblem(TenantId tenantId, String sourceInstanceId, String problemEventId,
        String triggerId, String title, int severity, Instant occurredAt, Instant observedAt,
        List<String> hostIds, boolean suppressed, String recoveryEventId, Instant recoveredAt) {
    public ExternalProblem {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(occurredAt); Objects.requireNonNull(observedAt);
        if (sourceInstanceId == null || !sourceInstanceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid source instance");
        positiveId(problemEventId); positiveId(triggerId);
        if (title == null || title.isBlank() || title.length() > 300 || title.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid problem title");
        if (severity < 0 || severity > 5 || occurredAt.isAfter(observedAt)) throw new IllegalArgumentException("Invalid problem severity or time");
        hostIds = List.copyOf(hostIds);
        if (hostIds.size() > 20 || hostIds.stream().distinct().count() != hostIds.size()) throw new IllegalArgumentException("Invalid problem hosts");
        hostIds.forEach(ExternalProblem::positiveId);
        if (recoveryEventId != null) {
            positiveId(recoveryEventId);
            if (recoveryEventId.equals(problemEventId)) throw new IllegalArgumentException("Recovery cannot refer to problem event");
        }
        if (recoveredAt != null && (recoveryEventId == null || recoveredAt.isBefore(occurredAt) || recoveredAt.isAfter(observedAt))) throw new IllegalArgumentException("Invalid recovery time");
    }
    public enum State { ACTIVE, RECOVERED, RECOVERY_UNKNOWN }
    public State state() { return recoveryEventId == null ? State.ACTIVE : recoveredAt == null ? State.RECOVERY_UNKNOWN : State.RECOVERED; }
    public static String positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,19}") || new BigInteger(value).compareTo(new BigInteger("18446744073709551615")) > 0) throw new IllegalArgumentException("Invalid external event ID");
        return value;
    }
}
