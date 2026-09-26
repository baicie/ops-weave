package com.acme.opsweave.platform.incident;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.incident.domain.IncidentRecord.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Hand-authored boundary adapter for the canonical contracts, never a generated client. */
public final class IncidentJson {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private IncidentJson() {}
    public static Map<String,Object> header(Header h) {
        return Map.of("schemaVersion", "1.0", "tenantId", h.tenantId().value(), "id", h.id().toString(), "title", h.title(),
            "status", h.status().name(), "severity", h.severity(), "version", h.version(), "createdAt", h.createdAt().toString());
    }
    public static Map<String,Object> problem(ExternalProblem p) {
        var row = new LinkedHashMap<String,Object>(); row.put("schemaVersion", "1.0"); row.put("tenantId", p.tenantId().value());
        row.put("sourceInstanceId", p.sourceInstanceId()); row.put("problemEventId", p.problemEventId()); row.put("triggerId", p.triggerId());
        row.put("title", p.title()); row.put("severity", p.severity()); row.put("occurredAt", p.occurredAt().toString()); row.put("observedAt", p.observedAt().toString());
        row.put("hostIds", p.hostIds()); row.put("suppressed", p.suppressed()); row.put("recoveryEventId", p.recoveryEventId());
        row.put("recoveredAt", p.recoveredAt() == null ? null : p.recoveredAt().toString()); row.put("state", p.state().name());
        row.put("gaps", p.state() == ExternalProblem.State.RECOVERY_UNKNOWN ? List.of("RECOVERY_EVENT_UNAVAILABLE") : List.of()); return row;
    }
    public static Map<String,Object> body(IncidentRecord record) {
        var gaps = new ArrayList<>(List.of("LOGS_NOT_CONNECTED", "CHANGES_NOT_CONNECTED"));
        if (record.hasUnmappedHosts()) gaps.add("ENTITY_MAPPING_MISSING");
        if (record.problems().stream().anyMatch(p -> p.observation().state() == ExternalProblem.State.RECOVERY_UNKNOWN)) gaps.add("RECOVERY_EVENT_UNAVAILABLE");
        var problems = record.problems().stream().map(p -> Map.of("observation", problem(p.observation()), "dataMode", p.dataMode(),
            "sourceContract", "zabbix-7.0-event-v1",
            "firstReceivedAt", p.firstReceivedAt().toString(), "lastReceivedAt", p.lastReceivedAt().toString(),
            "entities", p.entities().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> Map.of("hostId", e.getKey(), "entityId", e.getValue().value().toString())).toList())).toList();
        var result = new LinkedHashMap<String,Object>(); result.put("schemaVersion", "1.0"); result.put("incident", header(record.incident())); result.put("problems", problems);
        result.put("timeline", record.timeline().stream().map(IncidentJson::timeline).toList()); result.put("gaps", gaps);
        if (record.organization().version() > 0) {
            var organization = new LinkedHashMap<String,Object>(); organization.put("version",record.organization().version()); organization.put("changeId",record.organization().changeId().toString());
            organization.put("mergedInto",record.merged() ? record.organization().mergedInto().toString() : null); result.put("organization",organization);
        }
        return result;
    }
    public static String encode(IncidentRecord record) {
        String value = JSON.writeValueAsString(body(record));
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240000) throw new IncidentFailure(IncidentFailure.Code.READ_LIMIT);
        return value;
    }
    public static IncidentRecord decode(String value) {
        try {
            if (value == null || value.length() > 262144) throw new IllegalArgumentException();
            var root = JSON.readTree(value); var h = root.get("incident");
            var header = new Header(new TenantId(text(h, "tenantId")), UUID.fromString(text(h, "id")), text(h, "title"),
                IncidentStatus.valueOf(text(h, "status")), Math.toIntExact(number(h, "severity")), number(h, "version"), Instant.parse(text(h, "createdAt")));
            var problems = new ArrayList<Problem>(); var timeline = new ArrayList<Timeline>();
            if (!root.get("problems").isArray() || root.get("problems").size() > 50 || !root.get("timeline").isArray() || root.get("timeline").size() > 100) throw new IllegalArgumentException();
            for (var p : root.get("problems")) {
                var observation = p.get("observation"); var hosts = new ArrayList<String>();
                if (!observation.get("hostIds").isArray() || observation.get("hostIds").size() > 20 || !p.get("entities").isArray() || p.get("entities").size() > 20) throw new IllegalArgumentException();
                for (var host : observation.get("hostIds")) { if (!host.isString()) throw new IllegalArgumentException(); hosts.add(host.asString()); }
                if (!observation.get("suppressed").isBoolean()) throw new IllegalArgumentException();
                var external = new ExternalProblem(new TenantId(text(observation, "tenantId")), text(observation, "sourceInstanceId"), text(observation, "problemEventId"),
                    text(observation, "triggerId"), text(observation, "title"), Math.toIntExact(number(observation, "severity")), Instant.parse(text(observation, "occurredAt")),
                    Instant.parse(text(observation, "observedAt")), hosts, observation.get("suppressed").asBoolean(), nullable(observation, "recoveryEventId"),
                    nullable(observation, "recoveredAt") == null ? null : Instant.parse(text(observation, "recoveredAt")));
                var entities = new LinkedHashMap<String,EntityId>();
                for (var entity : p.get("entities")) if (entities.putIfAbsent(text(entity, "hostId"), EntityId.parse(text(entity, "entityId"))) != null) throw new IllegalArgumentException();
                problems.add(new Problem(external, text(p, "dataMode"), entities, Instant.parse(text(p, "firstReceivedAt")), Instant.parse(text(p, "lastReceivedAt"))));
            }
            for (var t : root.get("timeline")) timeline.add(new Timeline(UUID.fromString(text(t, "id")), Kind.valueOf(text(t, "kind")), Instant.parse(text(t, "occurredAt")),
                Instant.parse(text(t, "availableAt")), nullable(t, "sourceInstanceId"), nullable(t, "problemEventId"), nullable(t, "recoveryEventId"),
                status(t, "fromStatus"), status(t, "toStatus"), nullable(t, "actor")));
            var organization = root.get("organization");
            var record = organization == null ? new IncidentRecord(header, problems, timeline) : new IncidentRecord(header, problems, timeline,
                new Organization(number(organization,"version"), UUID.fromString(text(organization,"changeId")), nullable(organization,"mergedInto") == null ? null : UUID.fromString(text(organization,"mergedInto")))); record.entityIds();
            // Reject unknown fields, schema/status/gap mismatches and noncanonical identity encodings.
            if (!JSON.readTree(encode(record)).equals(root)) throw new IllegalArgumentException();
            return record;
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid stored incident"); }
    }
    private static Map<String,Object> timeline(Timeline t) {
        var row = new LinkedHashMap<String,Object>(); row.put("id", t.id().toString()); row.put("kind", t.kind().name());
        row.put("occurredAt", t.occurredAt().toString()); row.put("availableAt", t.availableAt().toString()); row.put("sourceInstanceId", t.sourceInstanceId());
        row.put("problemEventId", t.problemEventId()); row.put("recoveryEventId", t.recoveryEventId()); row.put("fromStatus", t.fromStatus() == null ? null : t.fromStatus().name());
        row.put("toStatus", t.toStatus() == null ? null : t.toStatus().name()); row.put("actor", t.actor()); return row;
    }
    private static IncidentStatus status(JsonNode n, String field) { return nullable(n, field) == null ? null : IncidentStatus.valueOf(text(n, field)); }
    private static String text(JsonNode n, String field) { if (n.get(field) == null || !n.get(field).isString()) throw new IllegalArgumentException(); return n.get(field).asString(); }
    private static String nullable(JsonNode n, String field) { if (n.get(field) == null) throw new IllegalArgumentException(); return n.get(field).isNull() ? null : text(n, field); }
    private static long number(JsonNode n, String field) { if (n.get(field) == null || !n.get(field).isIntegralNumber() || !n.get(field).canConvertToLong()) throw new IllegalArgumentException(); return n.get(field).asLong(); }
}
