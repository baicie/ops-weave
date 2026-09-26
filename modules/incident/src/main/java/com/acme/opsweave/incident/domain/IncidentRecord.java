package com.acme.opsweave.incident.domain;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.sharedkernel.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Bounded business view. Vendor transport/JSON and framework classes stay outside this module. */
public record IncidentRecord(Header incident, List<Problem> problems, List<Timeline> timeline, Organization organization) {
    public IncidentRecord(Header incident, List<Problem> problems, List<Timeline> timeline) { this(incident, problems, timeline, new Organization(0, null, null)); }
    public IncidentRecord {
        Objects.requireNonNull(incident); Objects.requireNonNull(organization); problems = List.copyOf(problems); timeline = List.copyOf(timeline);
        if (organization.version() > incident.version() || incident.id().equals(organization.mergedInto())) throw new IllegalArgumentException("Invalid organization version");
        if (problems.isEmpty() || problems.size() > 50 || timeline.size() > 100) throw new IncidentFailure(IncidentFailure.Code.READ_LIMIT);
        if (problems.stream().anyMatch(p -> !p.observation().tenantId().equals(incident.tenantId()))) throw new IllegalArgumentException("Incident tenant conflict");
        if (problems.stream().map(p -> p.observation().sourceInstanceId() + ":" + p.observation().problemEventId()).distinct().count() != problems.size()
            || timeline.stream().map(Timeline::id).distinct().count() != timeline.size()) throw new IllegalArgumentException("Duplicate incident data");
    }
    public Set<EntityId> entityIds() {
        var ids = new HashSet<EntityId>(); problems.forEach(p -> ids.addAll(p.entities().values()));
        if (ids.size() > 100) throw new IncidentFailure(IncidentFailure.Code.READ_LIMIT);
        return Set.copyOf(ids);
    }
    public boolean merged() { return organization.mergedInto() != null; }
    public record Organization(long version, UUID changeId, UUID mergedInto) {
        public Organization { if (version < 0 || (version == 0) != (changeId == null) || (version == 0 && mergedInto != null)) throw new IllegalArgumentException("Invalid organization metadata"); }
    }
    public boolean hasUnmappedHosts() { return problems.stream().anyMatch(p -> p.observation().hostIds().isEmpty() || !p.entities().keySet().containsAll(p.observation().hostIds())); }
    public static UUID initialId(ExternalProblem problem) {
        String tenant = problem.tenantId().value(), source = problem.sourceInstanceId();
        return stableId("incident:" + tenant.length() + ":" + tenant + source.length() + ":" + source + ":" + problem.problemEventId());
    }
    public static UUID stableId(String material) { return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)); }
    public record Header(TenantId tenantId, UUID id, String title, IncidentStatus status, int severity, long version, Instant createdAt) {
        public Header {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(id); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
            if (title == null || title.isBlank() || title.length() > 300 || severity < 0 || severity > 5 || version < 1) throw new IllegalArgumentException("Invalid incident header");
        }
    }
    public record Problem(ExternalProblem observation, String dataMode, Map<String,EntityId> entities, Instant firstReceivedAt, Instant lastReceivedAt) {
        public Problem {
            Objects.requireNonNull(observation); entities = Map.copyOf(entities); Objects.requireNonNull(firstReceivedAt); Objects.requireNonNull(lastReceivedAt);
            if (!Set.of("labeled-fixture", "zabbix-jsonrpc").contains(dataMode) || entities.size() > 20 || lastReceivedAt.isBefore(firstReceivedAt)
                || observation.observedAt().isAfter(lastReceivedAt)) throw new IllegalArgumentException("Invalid incident problem");
            entities.keySet().forEach(ExternalProblem::positiveId);
        }
    }
    public enum Kind { PROBLEM, RECOVERY, STATUS_CHANGE }
    public record Timeline(UUID id, Kind kind, Instant occurredAt, Instant availableAt, String sourceInstanceId,
            String problemEventId, String recoveryEventId, IncidentStatus fromStatus, IncidentStatus toStatus, String actor) {
        public Timeline {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(occurredAt); Objects.requireNonNull(availableAt);
            if (occurredAt.isAfter(availableAt)) throw new IllegalArgumentException("Future timeline entry");
            if (kind == Kind.STATUS_CHANGE) {
                if (fromStatus == null || toStatus == null || actor == null || actor.isBlank() || actor.length() > 128 || sourceInstanceId != null
                    || problemEventId != null || recoveryEventId != null) throw new IllegalArgumentException("Invalid status entry");
            } else {
                if (sourceInstanceId == null || fromStatus != null || toStatus != null || actor != null) throw new IllegalArgumentException("Invalid source entry");
                ExternalProblem.positiveId(problemEventId);
                if (kind == Kind.RECOVERY) ExternalProblem.positiveId(recoveryEventId);
                else if (recoveryEventId != null) throw new IllegalArgumentException("Unexpected recovery ID");
            }
        }
    }
}
