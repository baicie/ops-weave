package com.acme.opsweave.platform.incident;

import com.acme.opsweave.incident.domain.IncidentReorganization.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Canonical incident-reorganization request and receipt boundary. */
public final class ReorganizationJson {
    private static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private ReorganizationJson() {}
    public static Request request(String value) { return request(JSON.readTree(value)); }
    public static Request request(byte[] value) { return request(JSON.readTree(value)); }
    public static Request request(JsonNode n) {
        exact(n, "requestKey", "kind", "sourceIncidentId", "expectedSourceVersion", "targetIncidentId", "expectedTargetVersion", "problemKeys", "title", "reason");
        return new Request(id(n,"requestKey"), Kind.valueOf(text(n,"kind")), id(n,"sourceIncidentId"), number(n,"expectedSourceVersion"),
            id(n,"targetIncidentId"), number(n,"expectedTargetVersion"), keys(n.get("problemKeys")), n.get("title").isNull() ? null : text(n,"title"), text(n,"reason"));
    }
    public static Map<String,Object> request(Request r) {
        var result = new LinkedHashMap<String,Object>(); result.put("requestKey",r.requestKey().toString()); result.put("kind",r.kind().name());
        result.put("sourceIncidentId",r.sourceIncidentId().toString()); result.put("expectedSourceVersion",r.expectedSourceVersion());
        result.put("targetIncidentId",r.targetIncidentId().toString()); result.put("expectedTargetVersion",r.expectedTargetVersion());
        result.put("problemKeys",keys(r.problemKeys())); result.put("title",r.title()); result.put("reason",r.reason()); return result;
    }
    public static Map<String,Object> body(Receipt r) {
        return Map.of("schemaVersion","1.0", "request",request(r.request()), "actor",r.actor(), "sourceVersion",r.sourceVersion(), "targetVersion",r.targetVersion(),
            "movedProblems",keys(r.movedProblems()), "occurredAt",r.occurredAt().toString());
    }
    public static String encode(Receipt r) { return JSON.writeValueAsString(body(r)); }
    public static Receipt decode(String text) {
        try {
            if (text == null || text.length() > 32768) throw new IllegalArgumentException(); var n = JSON.readTree(text);
            var r = new Receipt(request(n.get("request")), text(n,"actor"), number(n,"sourceVersion"), number(n,"targetVersion"), keys(n.get("movedProblems")), Instant.parse(text(n,"occurredAt")));
            if (!JSON.readTree(encode(r)).equals(n)) throw new IllegalArgumentException(); return r;
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid reorganization receipt"); }
    }
    private static List<Map<String,String>> keys(List<ProblemKey> keys) { return keys.stream().map(k -> Map.of("sourceInstanceId",k.sourceInstanceId(),"problemEventId",k.problemEventId())).toList(); }
    private static List<ProblemKey> keys(JsonNode n) {
        if (n == null || !n.isArray() || n.size() > 50) throw new IllegalArgumentException(); var result = new ArrayList<ProblemKey>();
        for (var key : n) { exact(key,"sourceInstanceId","problemEventId"); result.add(new ProblemKey(text(key,"sourceInstanceId"),text(key,"problemEventId"))); } return result;
    }
    private static UUID id(JsonNode n,String f) { return IncidentController.uuid(text(n,f)); }
    private static String text(JsonNode n,String f) { if (n == null || n.get(f) == null || !n.get(f).isString()) throw new IllegalArgumentException(); return n.get(f).asString(); }
    private static long number(JsonNode n,String f) { if (n == null || n.get(f) == null || !n.get(f).isIntegralNumber() || !n.get(f).canConvertToLong()) throw new IllegalArgumentException(); return n.get(f).asLong(); }
    private static void exact(JsonNode n,String... fields) { if (n == null || !n.isObject() || n.size() != fields.length || !Arrays.stream(fields).allMatch(n::has)) throw new IllegalArgumentException(); }
}
