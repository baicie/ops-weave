package com.acme.opsweave.incident.domain;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.incident.domain.IncidentRecord.*;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

/** Human-directed association change, never an AI action or an upstream recovery fact. */
public final class IncidentReorganization {
    private IncidentReorganization() {}
    public enum Kind { MERGE, SPLIT }
    public record ProblemKey(String sourceInstanceId, String problemEventId) implements Comparable<ProblemKey> {
        public ProblemKey { if (sourceInstanceId == null || !sourceInstanceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException(); ExternalProblem.positiveId(problemEventId); }
        public int compareTo(ProblemKey other) { int first = sourceInstanceId.compareTo(other.sourceInstanceId); return first == 0 ? problemEventId.compareTo(other.problemEventId) : first; }
        public static ProblemKey of(Problem problem) { return new ProblemKey(problem.observation().sourceInstanceId(), problem.observation().problemEventId()); }
    }
    public record Request(UUID requestKey, Kind kind, UUID sourceIncidentId, long expectedSourceVersion, UUID targetIncidentId,
            long expectedTargetVersion, List<ProblemKey> problemKeys, String title, String reason) {
        public Request {
            Objects.requireNonNull(requestKey); Objects.requireNonNull(kind); Objects.requireNonNull(sourceIncidentId); Objects.requireNonNull(targetIncidentId);
            if (sourceIncidentId.equals(targetIncidentId) || expectedSourceVersion < 1 || expectedSourceVersion >= 9007199254740991L || expectedTargetVersion < 0
                || expectedTargetVersion >= 9007199254740991L || reason == null || reason.isBlank() || reason.length() > 500 || problemKeys == null || problemKeys.size() > 49
                || new HashSet<>(problemKeys).size() != problemKeys.size()) throw new IncidentFailure(INVALID_REQUEST);
            problemKeys = problemKeys.stream().sorted().toList();
            if (kind == Kind.MERGE ? expectedTargetVersion < 1 || !problemKeys.isEmpty() || title != null
                : expectedTargetVersion != 0 || problemKeys.isEmpty() || title == null || title.isBlank() || title.length() > 300) throw new IncidentFailure(INVALID_REQUEST);
        }
    }
    public record Receipt(Request request, String actor, long sourceVersion, long targetVersion, List<ProblemKey> movedProblems, Instant occurredAt) {
        public Receipt {
            Objects.requireNonNull(request); Objects.requireNonNull(occurredAt); movedProblems = List.copyOf(movedProblems);
            if (actor == null || actor.isBlank() || actor.length() > 128 || sourceVersion != request.expectedSourceVersion() + 1 || targetVersion != request.expectedTargetVersion() + 1
                || movedProblems.isEmpty() || movedProblems.size() > 50 || new HashSet<>(movedProblems).size() != movedProblems.size()
                || (request.kind() == Kind.SPLIT && !new HashSet<>(movedProblems).equals(new HashSet<>(request.problemKeys())))) throw new IllegalArgumentException("Invalid reorganization receipt");
        }
    }
    public record Applied(IncidentRecord source, IncidentRecord target, Receipt receipt) {}
    public static Applied apply(IncidentRecord source, IncidentRecord target, Request request, String actor, Instant now) {
        if (!source.incident().id().equals(request.sourceIncidentId()) || source.incident().version() != request.expectedSourceVersion()
            || source.merged() || source.incident().status() == IncidentStatus.CLOSED) throw new IncidentFailure(CONFLICT);
        if (now.isBefore(source.incident().createdAt())) throw new IncidentFailure(INVALID_REQUEST);
        var selected = new HashSet<>(request.kind() == Kind.MERGE ? source.problems().stream().map(ProblemKey::of).toList() : request.problemKeys());
        var moved = source.problems().stream().filter(p -> selected.contains(ProblemKey.of(p))).toList();
        if (selected.size() != moved.size()) throw new IncidentFailure(CONFLICT);
        final IncidentRecord newSource, newTarget;
        long sourceVersion = source.incident().version() + 1;
        if (request.kind() == Kind.MERGE) {
            if (target == null || target.merged() || !target.incident().tenantId().equals(source.incident().tenantId()) || !target.incident().id().equals(request.targetIncidentId())
                || target.incident().version() != request.expectedTargetVersion() || (target.incident().status() != IncidentStatus.OPEN && target.incident().status() != IncidentStatus.INVESTIGATING)
                || now.isBefore(target.incident().createdAt())) throw new IncidentFailure(CONFLICT);
            var problems = new ArrayList<>(target.problems());
            if (problems.stream().anyMatch(p -> selected.contains(ProblemKey.of(p)))) throw new IncidentFailure(CONFLICT);
            problems.addAll(moved); var timeline = new ArrayList<>(target.timeline());
            // Source status decisions remain in its archived snapshot; only source facts join the target.
            timeline.addAll(source.timeline().stream().filter(t -> t.kind() != IncidentRecord.Kind.STATUS_CHANGE).toList());
            newSource = new IncidentRecord(header(source, source.problems(), sourceVersion), source.problems(), source.timeline(), new Organization(sourceVersion, request.requestKey(), target.incident().id()));
            long targetVersion = target.incident().version() + 1;
            newTarget = new IncidentRecord(header(target, problems, targetVersion), problems, timeline, new Organization(targetVersion, request.requestKey(), null));
        } else {
            if (target != null || moved.size() == source.problems().size()) throw new IncidentFailure(CONFLICT);
            var remaining = source.problems().stream().filter(p -> !selected.contains(ProblemKey.of(p))).toList();
            var retainedTime = source.timeline().stream().filter(t -> t.kind() == IncidentRecord.Kind.STATUS_CHANGE || !selected.contains(new ProblemKey(t.sourceInstanceId(), t.problemEventId()))).toList();
            var movedTime = source.timeline().stream().filter(t -> t.kind() != IncidentRecord.Kind.STATUS_CHANGE && selected.contains(new ProblemKey(t.sourceInstanceId(), t.problemEventId()))).toList();
            newSource = new IncidentRecord(header(source, remaining, sourceVersion), remaining, retainedTime, new Organization(sourceVersion, request.requestKey(), null));
            newTarget = new IncidentRecord(new Header(source.incident().tenantId(), request.targetIncidentId(), request.title(), IncidentStatus.OPEN, severity(moved), 1, now),
                moved, movedTime, new Organization(1, request.requestKey(), null));
        }
        newSource.entityIds(); newTarget.entityIds();
        return new Applied(newSource, newTarget, new Receipt(request, actor, newSource.incident().version(), newTarget.incident().version(), selected.stream().sorted().toList(), now));
    }
    private static Header header(IncidentRecord original, List<Problem> problems, long version) { var h = original.incident(); return new Header(h.tenantId(), h.id(), h.title(), h.status(), severity(problems), version, h.createdAt()); }
    private static int severity(List<Problem> problems) { return problems.stream().mapToInt(p -> p.observation().severity()).max().orElseThrow(); }
}
