package com.acme.opsweave.incident.domain;

import com.acme.opsweave.alerting.domain.*;
import com.acme.opsweave.incident.domain.IncidentRecord.*;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

public final class IncidentProjection {
    private IncidentProjection() {}
    public record Applied(IncidentRecord record, boolean created, boolean changed) {}
    public static Applied observe(IncidentRecord previous, ExternalProblem incoming, String dataMode, Map<String,EntityId> mapped, Instant receivedAt) {
        if (receivedAt.isBefore(incoming.observedAt()) || !incoming.hostIds().containsAll(mapped.keySet())) throw new IllegalArgumentException("Invalid observation metadata");
        var problems = new ArrayList<Problem>(); var timeline = new ArrayList<Timeline>();
        Problem old = null;
        if (previous != null) {
            if (previous.merged()) throw new IncidentFailure(CONFLICT);
            if (!previous.incident().tenantId().equals(incoming.tenantId())) throw new IncidentFailure(CONFLICT);
            problems.addAll(previous.problems()); timeline.addAll(previous.timeline());
            old = problems.stream().filter(p -> p.observation().sourceInstanceId().equals(incoming.sourceInstanceId())
                && p.observation().problemEventId().equals(incoming.problemEventId())).findFirst().orElseThrow(() -> new IncidentFailure(CONFLICT));
            if (!old.dataMode().equals(dataMode)) throw new IncidentFailure(CONFLICT);
        }
        ExternalProblem merged;
        try { merged = old == null ? incoming : ProblemObservations.merge(old.observation(), incoming); }
        catch (IllegalArgumentException conflict) { throw new IncidentFailure(CONFLICT); }
        var entities = new HashMap<String,EntityId>(); if (old != null) entities.putAll(old.entities());
        // Retain historical links; changed external bindings require an explicit mapping resolution.
        for (var entry : mapped.entrySet()) {
            if (entities.containsKey(entry.getKey()) && !entities.get(entry.getKey()).equals(entry.getValue())) throw new IncidentFailure(CONFLICT);
            entities.put(entry.getKey(), entry.getValue());
        }
        var problem = new Problem(merged, dataMode, entities, old == null ? receivedAt : old.firstReceivedAt(),
            old == null || receivedAt.isAfter(old.lastReceivedAt()) ? receivedAt : old.lastReceivedAt());
        boolean changed = old == null || !sameContent(old.observation(), merged) || !old.entities().equals(entities);
        if (old != null) problems.remove(old);
        problems.add(problem);
        if (old == null) timeline.add(sourceEntry(merged, Kind.PROBLEM, merged.occurredAt(), receivedAt));
        if (merged.recoveredAt() != null && (old == null || old.observation().recoveredAt() == null)) timeline.add(sourceEntry(merged, Kind.RECOVERY, merged.recoveredAt(), receivedAt));
        int severity = problems.stream().mapToInt(p -> p.observation().severity()).max().orElseThrow();
        Header header = previous == null ? new Header(incoming.tenantId(), IncidentRecord.initialId(incoming), incoming.title(), IncidentStatus.OPEN, severity, 1, receivedAt)
            : new Header(previous.incident().tenantId(), previous.incident().id(), previous.incident().title(), previous.incident().status(), severity,
                Math.addExact(previous.incident().version(), changed ? 1 : 0), previous.incident().createdAt());
        timeline.sort(Comparator.comparing(Timeline::occurredAt).thenComparing(e -> e.id().toString()));
        var record = previous == null ? new IncidentRecord(header, problems, timeline) : new IncidentRecord(header, problems, timeline, previous.organization()); record.entityIds();
        return new Applied(record, previous == null, changed);
    }
    public static IncidentRecord transition(IncidentRecord previous, long expectedVersion, IncidentStatus target, UUID requestKey, String actor, Instant now) {
        if (previous.merged() || previous.incident().version() != expectedVersion) throw new IncidentFailure(CONFLICT);
        if (!previous.incident().status().canTransitionTo(target)) throw new IncidentFailure(INVALID_TRANSITION);
        if (target == IncidentStatus.RESOLVED && previous.problems().stream().anyMatch(p -> p.observation().state() != ExternalProblem.State.RECOVERED)) throw new IncidentFailure(ACTIVE_PROBLEMS);
        var before = previous.incident(); var header = new Header(before.tenantId(), before.id(), before.title(), target, before.severity(), Math.addExact(before.version(), 1), before.createdAt());
        var timeline = new ArrayList<>(previous.timeline());
        timeline.add(new Timeline(requestKey, Kind.STATUS_CHANGE, now, now, null, null, null, before.status(), target, actor));
        return new IncidentRecord(header, previous.problems(), timeline, previous.organization());
    }
    private static Timeline sourceEntry(ExternalProblem p, Kind kind, Instant when, Instant availableAt) {
        UUID id = IncidentRecord.stableId(IncidentRecord.initialId(p) + ":" + kind + ":" + (kind == Kind.RECOVERY ? p.recoveryEventId() : ""));
        return new Timeline(id, kind, when, availableAt, p.sourceInstanceId(), p.problemEventId(), kind == Kind.RECOVERY ? p.recoveryEventId() : null, null, null, null);
    }
    private static boolean sameContent(ExternalProblem a, ExternalProblem b) {
        return a.tenantId().equals(b.tenantId()) && a.sourceInstanceId().equals(b.sourceInstanceId()) && a.problemEventId().equals(b.problemEventId())
            && a.triggerId().equals(b.triggerId()) && a.title().equals(b.title()) && a.severity() == b.severity() && a.occurredAt().equals(b.occurredAt())
            && a.hostIds().equals(b.hostIds()) && a.suppressed() == b.suppressed() && Objects.equals(a.recoveryEventId(), b.recoveryEventId()) && Objects.equals(a.recoveredAt(), b.recoveredAt());
    }
}
