package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Handwritten adapter for the canonical platform-evidence and tool-read-session contracts. */
public final class ToolJson {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ToolJson() {}
    public static Map<String,Object> session(ToolReadSession s, String storage, Set<String> permissions) {
        return Map.ofEntries(Map.entry("schemaVersion", "1.0"), Map.entry("id", s.id().toString()), Map.entry("tenantId", s.tenantId().value()),
            Map.entry("subjectId", s.subjectId().value()), Map.entry("incidentId", s.incidentId().toString()), Map.entry("incidentVersion", s.incidentVersion()),
            Map.entry("entityIds", s.entityIds().stream().map(v -> v.value().toString()).sorted().toList()), Map.entry("queryWindow", window(s.window())),
            Map.entry("knowledgeMode", "current"), Map.entry("createdAt", s.createdAt().toString()), Map.entry("deadlineAt", s.deadlineAt().toString()),
            Map.entry("maxToolCalls", ToolReadSession.MAX_CALLS), Map.entry("usedCalls", s.usedCalls()), Map.entry("allowedTools", ToolReadSession.TOOLS.stream().sorted().toList()), Map.entry("storage", storage),
            Map.entry("policyVersion", "readonly-diagnosis-v1"), Map.entry("permissions", permissions.stream().filter(Set.of("ai.diagnose", "ai.insight.read", "incident.read", "entity.read", "metric.read", "evidence.read")::contains).sorted().toList()));
    }
    public static Map<String,Object> window(ToolWindow w) { return Map.of("from", w.from().toString(), "to", w.to().toString()); }
    public static Map<String,Object> document(PlatformEvidence e) {
        var evidence = Map.ofEntries(Map.entry("id", e.id().toString()), Map.entry("tenantId", e.tenantId().value()), Map.entry("incidentId", e.incidentId().toString()),
            Map.entry("kind", e.kind()), Map.entry("summary", e.summary()), Map.entry("observedAt", e.observedAt().toString()), Map.entry("availableAt", e.availableAt().toString()),
            Map.entry("expiresAt", e.expiresAt().toString()), Map.entry("sourceRef", "/api/v1/ai/evidence/" + e.id()), Map.entry("trust", "untrusted_data"));
        return Map.ofEntries(Map.entry("schemaVersion", "1.0"), Map.entry("knowledgeMode", "current"), Map.entry("evidence", evidence), Map.entry("sessionId", e.sessionId().toString()),
            Map.entry("incidentVersion", e.incidentVersion()), Map.entry("entityIds", e.entityIds().stream().map(v -> v.value().toString()).sorted().toList()),
            Map.entry("queryWindow", window(e.queryWindow())), Map.entry("dataModes", e.dataModes()), Map.entry("warnings", e.warnings()), Map.entry("data", e.data()),
            Map.entry("policyVersion", "readonly-diagnosis-v1"), Map.entry("producerTool", e.kind().equals("incident") ? "incident.get@2.0.0" : "metric.summary@2.0.0"));
    }
    public static Map<String,Object> result(PlatformEvidence e) {
        return Map.of("status", e.warnings().isEmpty() ? "ok" : "partial", "data", document(e), "evidenceRefs", List.of(e.id().toString()), "warnings", e.warnings(), "truncated", false);
    }
    public static int bytes(PlatformEvidence e) { return encode(e).getBytes(StandardCharsets.UTF_8).length; }
    public static String encode(PlatformEvidence e) { return JSON.writeValueAsString(document(e)); }
    public static PlatformEvidence decode(String text) {
        try {
            if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 32768) throw new IllegalArgumentException();
            var root = JSON.readTree(text); var e = root.get("evidence"); var w = root.get("queryWindow");
            var entities = new HashSet<EntityId>(); for (var id : root.get("entityIds")) if (!entities.add(EntityId.parse(id.asString()))) throw new IllegalArgumentException();
            var value = new PlatformEvidence(UUID.fromString(string(e, "id")), UUID.fromString(string(root, "sessionId")), new TenantId(string(e, "tenantId")),
                UUID.fromString(string(e, "incidentId")), number(root, "incidentVersion"), entities, string(e, "kind"), string(e, "summary"),
                new ToolWindow(Instant.parse(string(w, "from")), Instant.parse(string(w, "to"))), Instant.parse(string(e, "observedAt")), Instant.parse(string(e, "availableAt")),
                Instant.parse(string(e, "expiresAt")), strings(root.get("dataModes")), strings(root.get("warnings")), map(root.get("data")));
            if (!JSON.readTree(encode(value)).equals(root)) throw new IllegalArgumentException(); return value;
        } catch (RuntimeException invalid) { throw new ToolFailure(ToolFailure.Code.UNAVAILABLE); }
    }
    private static String string(JsonNode n, String key) { if (n == null || n.get(key) == null || !n.get(key).isString()) throw new IllegalArgumentException(); return n.get(key).asString(); }
    private static long number(JsonNode n, String key) { if (n.get(key) == null || !n.get(key).isIntegralNumber() || !n.get(key).canConvertToLong()) throw new IllegalArgumentException(); return n.get(key).asLong(); }
    private static List<String> strings(JsonNode n) { if (n == null || !n.isArray()) throw new IllegalArgumentException(); var result = new ArrayList<String>(); for (var v : n) { if (!v.isString()) throw new IllegalArgumentException(); result.add(v.asString()); } return result; }
    private static Map<String,Object> map(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException();
        var result = new LinkedHashMap<String,Object>(); for (var p : n.properties()) result.put(p.getKey(), data(p.getValue())); return result;
    }
    private static Object data(JsonNode n) {
        if (n.isString()) return n.asString(); if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber() && n.canConvertToLong()) return n.asLong();
        if (n.isObject()) return map(n);
        if (n.isArray()) { var list = new ArrayList<Object>(); for (var value : n) list.add(data(value)); return list; }
        throw new IllegalArgumentException();
    }
}
