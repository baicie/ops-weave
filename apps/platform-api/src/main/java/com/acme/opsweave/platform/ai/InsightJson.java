package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.api.InsightEncoding;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class InsightJson implements InsightEncoding {
    private static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public static InsightSubmission request(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        try {
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length == 0 || bytes.length > 65536) throw new IllegalArgumentException();
            return input(JSON.readTree(bytes));
        } catch (IOException | tools.jackson.core.JacksonException error) { throw new IllegalArgumentException("Invalid insight submission"); }
    }
    public static InsightSubmission input(JsonNode node) {
        exact(node, "runId", "sessionId", "question", "asOf", "builtAt", "completedAt", "skill", "model", "evidenceIds", "insight");
        var skill = node.get("skill"); exact(skill, "id", "version", "digest");
        var model = node.get("model"); exact(model, "provider", "name");
        return new InsightSubmission(uuid(node, "runId"), uuid(node, "sessionId"), text(node, "question"), time(node, "asOf"), time(node, "builtAt"), time(node, "completedAt"),
            new InsightSubmission.SkillRef(text(skill, "id"), text(skill, "version"), text(skill, "digest")),
            new InsightSubmission.ModelRef(text(model, "provider"), text(model, "name")), ids(node.get("evidenceIds")), draft(node.get("insight")));
    }
    private static InsightDraft draft(JsonNode node) {
        exact(node, "summary", "findings", "missingData", "limitations"); var findings = new ArrayList<InsightDraft.Finding>();
        if (!node.get("findings").isArray()) throw new IllegalArgumentException();
        for (var finding : node.get("findings")) { exact(finding, "kind", "statement", "evidenceRefs"); findings.add(new InsightDraft.Finding(text(finding, "kind"), text(finding, "statement"), ids(finding.get("evidenceRefs")))); }
        return new InsightDraft(text(node, "summary"), findings, strings(node.get("missingData")), strings(node.get("limitations")));
    }
    public static Map<String,Object> input(InsightSubmission s) {
        var result = new LinkedHashMap<String,Object>(); result.put("runId", s.runId().toString()); result.put("sessionId", s.sessionId().toString());
        result.put("question", s.question()); result.put("asOf", s.asOf().toString()); result.put("builtAt", s.builtAt().toString()); result.put("completedAt", s.completedAt().toString());
        var skill = new LinkedHashMap<String,Object>(); skill.put("id", s.skill().id()); skill.put("version", s.skill().version()); skill.put("digest", s.skill().digest()); result.put("skill", skill);
        var model = new LinkedHashMap<String,Object>(); model.put("provider", s.model().provider()); model.put("name", s.model().name()); result.put("model", model);
        result.put("evidenceIds", s.evidenceIds().stream().map(UUID::toString).toList()); result.put("insight", draft(s.insight())); return result;
    }
    private static Map<String,Object> draft(InsightDraft draft) {
        var result = new LinkedHashMap<String,Object>(); result.put("summary", draft.summary());
        result.put("findings", draft.findings().stream().map(f -> { var row = new LinkedHashMap<String,Object>(); row.put("kind", f.kind()); row.put("statement", f.statement()); row.put("evidenceRefs", f.evidenceRefs().stream().map(UUID::toString).toList()); return row; }).toList());
        result.put("missingData", draft.missingData()); result.put("limitations", draft.limitations()); return result;
    }
    public static Map<String,Object> body(AiInsight value) {
        var result = new LinkedHashMap<>(input(value.input())); result.remove("runId"); result.put("id", value.input().runId().toString());
        result.put("schemaVersion", "1.0"); result.put("knowledgeMode", "current"); result.put("tenantId", value.tenantId().value()); result.put("subjectId", value.subjectId().value());
        result.put("incidentId", value.incidentId().toString()); result.put("incidentVersion", value.incidentVersion()); result.put("entityIds", value.entityIds().stream().map(e -> e.value().toString()).sorted().toList());
        result.put("queryWindow", ToolJson.window(value.window())); result.put("savedAt", value.savedAt().toString()); result.put("expiresAt", value.expiresAt().toString());
        result.put("dataModes", value.dataModes()); result.put("warnings", value.warnings()); result.put("verification", "reference_integrity_only"); return result;
    }
    public static String encode(AiInsight value) { return JSON.writeValueAsString(body(value)); }
    public static AiInsight decode(String text, String digest) {
        try {
            if (text.getBytes(StandardCharsets.UTF_8).length > 131072) throw new IllegalArgumentException();
            var root = JSON.readTree(text); var submission = new LinkedHashMap<String,Object>();
            for (String field : List.of("sessionId", "question", "asOf", "builtAt", "completedAt", "skill", "model", "evidenceIds", "insight")) submission.put(field, root.get(field));
            submission.put("runId", root.get("id")); var input = input(JSON.valueToTree(submission));
            var window = root.get("queryWindow"); var entities = new HashSet<EntityId>(); for (UUID id : ids(root.get("entityIds"))) if (!entities.add(new EntityId(id))) throw new IllegalArgumentException();
            if (!root.get("incidentVersion").isIntegralNumber() || !root.get("incidentVersion").canConvertToLong()) throw new IllegalArgumentException();
            var result = new AiInsight(new TenantId(text(root,"tenantId")), new SubjectId(text(root,"subjectId")), uuid(root,"incidentId"), root.get("incidentVersion").asLong(), entities,
                new ToolWindow(time(window,"from"), time(window,"to")), input, digest, time(root,"savedAt"), time(root,"expiresAt"), strings(root.get("dataModes")), strings(root.get("warnings")));
            if (!JSON.readTree(encode(result)).equals(root)) throw new IllegalArgumentException(); return result;
        } catch (RuntimeException invalid) { throw new ToolFailure(ToolFailure.Code.UNAVAILABLE); }
    }
    public String digest(InsightSubmission value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(input(value)).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public int bytes(AiInsight value) { return encode(value).getBytes(StandardCharsets.UTF_8).length; }
    private static void exact(JsonNode n, String... fields) { if (n == null || !n.isObject() || n.size() != fields.length || Arrays.stream(fields).anyMatch(f -> !n.has(f))) throw new IllegalArgumentException(); }
    private static String text(JsonNode n, String field) { if (n == null || n.get(field) == null || !n.get(field).isString()) throw new IllegalArgumentException(); return n.get(field).asString(); }
    private static Instant time(JsonNode n, String field) { return Instant.parse(text(n, field)); }
    private static UUID uuid(JsonNode n, String field) { return com.acme.opsweave.platform.incident.IncidentController.uuid(text(n, field)); }
    private static List<String> strings(JsonNode n) { if (n == null || !n.isArray()) throw new IllegalArgumentException(); var values = new ArrayList<String>(); for (var v : n) { if (!v.isString()) throw new IllegalArgumentException(); values.add(v.asString()); } return values; }
    private static List<UUID> ids(JsonNode n) { return strings(n).stream().map(com.acme.opsweave.platform.incident.IncidentController::uuid).toList(); }
}
