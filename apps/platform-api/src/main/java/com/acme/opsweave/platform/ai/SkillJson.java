package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.SkillCheck;
import com.acme.opsweave.aicontrol.domain.SkillContent;
import com.acme.opsweave.aicontrol.domain.SkillDraft;
import com.acme.opsweave.aicontrol.domain.SkillRelease;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.TenantId;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.acme.opsweave.platform.integration.PipelineJson.fields;
import static com.acme.opsweave.platform.integration.PipelineJson.text;

/**
 * Closed wire adapter for the platform Skill registry (contracts/schemas/v2/skill-*): the draft save
 * command, the review/publish/rollback commands and their idempotent receipts, the draft record, the
 * immutable published version, the release pointer, the deterministic check report and the bounded
 * pages. Tenant, subject and permissions never appear on the wire — requests carry no identity
 * fields at all — and a stored body is accepted only when it round-trips to the exact same record.
 */
public final class SkillJson {
    private SkillJson() {}

    /** The content is a closed set: no tenant, actor, permission, path or URL field. */
    public static SkillContent content(JsonNode n) {
        fields(n, Set.of("executionTemplate", "prompt", "outputSchema", "allowedTools", "knowledgeScope",
            "modelPolicyRef", "sideEffects", "maxToolCalls", "maxEvidence", "maxContextBytes", "deadlineMs", "tests"));
        var allowed = n.get("allowedTools");
        if (allowed == null || !allowed.isArray()) throw new IllegalArgumentException("Invalid tool list");
        var tools = new ArrayList<String>();
        for (var item : allowed) tools.add(stringItem(item));
        var tests = new ArrayList<SkillContent.TestCase>();
        if (n.has("tests")) {
            var cases = n.get("tests");
            if (!cases.isArray()) throw new IllegalArgumentException("Invalid test list");
            for (var item : cases) {
                fields(item, Set.of("id", "question", "expectedOutput"));
                tests.add(new SkillContent.TestCase(text(item, "id"), text(item, "question"), text(item, "expectedOutput")));
            }
        }
        return new SkillContent(text(n, "executionTemplate"), text(n, "prompt"), text(n, "outputSchema"),
            List.copyOf(tools), text(n, "knowledgeScope"), text(n, "modelPolicyRef"), text(n, "sideEffects"),
            intValue(n, "maxToolCalls"), intValue(n, "maxEvidence"), intValue(n, "maxContextBytes"),
            longValue(n, "deadlineMs"), List.copyOf(tests));
    }

    /** The save command is a closed set; identity comes from the trusted principal only. */
    public static SkillDraft.SaveCommand saveCommand(JsonNode n) {
        fields(n, Set.of("requestId", "skillId", "version", "editVersion", "content"));
        return new SkillDraft.SaveCommand(uuid(n, "requestId"), text(n, "skillId"), text(n, "version"),
            longValue(n, "editVersion"), content(n.get("content")));
    }

    /** The review submission is one request id; the draft comes from the path, never the body. */
    public static SkillDraft.ReviewCommand reviewCommand(JsonNode n, UUID draftId) {
        fields(n, Set.of("requestId"));
        return new SkillDraft.ReviewCommand(uuid(n, "requestId"), draftId);
    }

    /** The publish command is one request id; the publisher comes from the trusted principal. */
    public static SkillDraft.PublishCommand publishCommand(JsonNode n, UUID draftId) {
        fields(n, Set.of("requestId"));
        return new SkillDraft.PublishCommand(uuid(n, "requestId"), draftId);
    }

    /** The rollback command names its target version and reason; never identity. */
    public static SkillRelease.RollbackCommand rollbackCommand(JsonNode n, String skillId) {
        fields(n, Set.of("requestId", "toVersion", "reason"));
        return new SkillRelease.RollbackCommand(uuid(n, "requestId"), skillId, text(n, "toVersion"), text(n, "reason"));
    }

    /** The closed content wire form; the digest is recomputed and compared on every round trip. */
    public static Map<String, Object> wire(SkillContent content) {
        var body = new LinkedHashMap<String, Object>();
        body.put("executionTemplate", content.executionTemplate());
        body.put("prompt", content.prompt());
        body.put("outputSchema", content.outputSchema());
        body.put("allowedTools", content.allowedTools());
        body.put("knowledgeScope", content.knowledgeScope());
        body.put("modelPolicyRef", content.modelPolicyRef());
        body.put("sideEffects", content.sideEffects());
        body.put("maxToolCalls", content.maxToolCalls());
        body.put("maxEvidence", content.maxEvidence());
        body.put("maxContextBytes", content.maxContextBytes());
        body.put("deadlineMs", content.deadlineMs());
        var tests = new ArrayList<Object>();
        for (var test : content.tests()) {
            tests.add(Map.of("id", test.id(), "question", test.question(), "expectedOutput", test.expectedOutput()));
        }
        body.put("tests", tests);
        return body;
    }

    /** The draft record: identity is server-derived and never accepted from a request. */
    public static Map<String, Object> wire(SkillDraft draft) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("draftId", draft.draftId().toString());
        body.put("skillId", draft.skillId());
        body.put("version", draft.version());
        body.put("state", draft.state().name());
        body.put("editVersion", draft.editVersion());
        body.put("contentDigest", draft.contentDigest());
        body.put("content", wire(draft.content()));
        body.put("createdAt", draft.createdAt().toString());
        body.put("updatedAt", draft.updatedAt().toString());
        return body;
    }

    /** The immutable published version with its review receipt; nothing here ever changes. */
    public static Map<String, Object> wire(SkillRelease.Version version) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("skillId", version.skillId());
        body.put("version", version.version());
        body.put("contentDigest", version.contentDigest());
        body.put("content", wire(version.content()));
        body.put("review", review(version.review()));
        body.put("publishedAt", version.publishedAt().toString());
        return body;
    }

    public static Map<String, Object> review(SkillRelease.Review review) {
        return Map.of("approvedAt", review.approvedAt().toString());
    }

    /** The release pointer: which published version new runs pin right now. */
    public static Map<String, Object> wire(SkillRelease.Pointer pointer) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("skillId", pointer.skillId());
        body.put("currentVersion", pointer.currentVersion());
        body.put("updatedAt", pointer.updatedAt().toString());
        return body;
    }

    /** The deterministic check report; {@code modelComparison} is always the constant "not-run". */
    public static Map<String, Object> wire(SkillCheck check) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("skillId", check.skillId());
        body.put("version", check.version());
        body.put("contentDigest", check.contentDigest());
        body.put("result", check.result().name());
        var cases = new ArrayList<Object>();
        for (var item : check.cases()) {
            cases.add(Map.of("id", item.id(), "result", item.result().name(), "detail", item.detail()));
        }
        body.put("cases", cases);
        body.put("modelComparison", check.modelComparison());
        return body;
    }

    public static Map<String, Object> wire(SkillDraft.SaveReceipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("requestId", receipt.requestId().toString());
        body.put("draftId", receipt.draftId().toString());
        body.put("skillId", receipt.skillId());
        body.put("version", receipt.version());
        body.put("state", receipt.state().name());
        body.put("editVersion", receipt.editVersion());
        body.put("contentDigest", receipt.contentDigest());
        body.put("savedAt", receipt.savedAt().toString());
        return body;
    }

    public static Map<String, Object> wire(SkillDraft.ReviewReceipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("requestId", receipt.requestId().toString());
        body.put("draftId", receipt.draftId().toString());
        body.put("skillId", receipt.skillId());
        body.put("version", receipt.version());
        body.put("state", receipt.state().name());
        body.put("submittedAt", receipt.submittedAt().toString());
        return body;
    }

    public static Map<String, Object> wire(SkillDraft.PublishReceipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("requestId", receipt.requestId().toString());
        body.put("draftId", receipt.draftId().toString());
        body.put("skillId", receipt.skillId());
        body.put("version", receipt.version());
        body.put("contentDigest", receipt.contentDigest());
        body.put("review", review(receipt.review()));
        body.put("publishedAt", receipt.publishedAt().toString());
        return body;
    }

    public static Map<String, Object> wire(SkillRelease.RollbackReceipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("requestId", receipt.requestId().toString());
        body.put("skillId", receipt.skillId());
        body.put("fromVersion", receipt.fromVersion());
        body.put("toVersion", receipt.toVersion());
        body.put("reason", receipt.reason());
        body.put("rolledBackAt", receipt.rolledBackAt().toString());
        return body;
    }

    public static Map<String, Object> draftPage(List<SkillDraft> items, String nextCursor, boolean hasMore) {
        var rows = new ArrayList<Object>();
        for (var draft : items) rows.add(wire(draft));
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("items", rows);
        body.put("nextCursor", nextCursor);
        body.put("hasMore", hasMore);
        return body;
    }

    public static Map<String, Object> versionPage(List<SkillRelease.Version> items, String nextCursor, boolean hasMore) {
        var rows = new ArrayList<Object>();
        for (var version : items) rows.add(wire(version));
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("items", rows);
        body.put("nextCursor", nextCursor);
        body.put("hasMore", hasMore);
        return body;
    }

    // ---- storage round trip: a stored body is accepted only when it matches the record exactly ----

    public static String storage(SkillDraft draft) {
        return CatalogJson.JSON.writeValueAsString(wire(draft));
    }

    public static String storage(SkillRelease.Version version) {
        return CatalogJson.JSON.writeValueAsString(wire(version));
    }

    public static SkillDraft decodeDraft(String json, TenantId tenant, SubjectId author) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("schemaVersion", "draftId", "skillId", "version", "state", "editVersion",
            "contentDigest", "content", "createdAt", "updatedAt"));
        if (!"2.0".equals(text(n, "schemaVersion"))) throw new IllegalArgumentException("Invalid stored draft");
        var content = content(n.get("content"));
        var draft = new SkillDraft(tenant, uuid(n, "draftId"), text(n, "skillId"), text(n, "version"), content,
            text(n, "contentDigest"), state(n), longValue(n, "editVersion"), author,
            Instant.parse(text(n, "createdAt")), Instant.parse(text(n, "updatedAt")));
        if (!CatalogJson.JSON.readTree(storage(draft)).equals(n)) throw new IllegalArgumentException("Invalid stored draft");
        return draft;
    }

    public static SkillRelease.Version decodeVersion(String json, TenantId tenant, SubjectId reviewer) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("schemaVersion", "skillId", "version", "contentDigest", "content", "review", "publishedAt"));
        if (!"2.0".equals(text(n, "schemaVersion"))) throw new IllegalArgumentException("Invalid stored version");
        fields(n.get("review"), Set.of("approvedAt"));
        var version = new SkillRelease.Version(tenant, text(n, "skillId"), text(n, "version"),
            content(n.get("content")), text(n, "contentDigest"),
            new SkillRelease.Review(reviewer, Instant.parse(text(n.get("review"), "approvedAt"))),
            Instant.parse(text(n, "publishedAt")));
        if (!CatalogJson.JSON.readTree(storage(version)).equals(n)) throw new IllegalArgumentException("Invalid stored version");
        return version;
    }

    private static String stringItem(JsonNode item) {
        if (item == null || !item.isString() || item.asString().isBlank()) {
            throw new IllegalArgumentException("Invalid string item");
        }
        return item.asString();
    }

    private static SkillDraft.State state(JsonNode n) {
        String value = text(n, "state");
        try {
            return SkillDraft.State.valueOf(value);
        } catch (IllegalArgumentException closed) {
            throw new IllegalArgumentException("Invalid stored state");
        }
    }

    private static long longValue(JsonNode n, String field) {
        var value = n.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Invalid long field");
        }
        return value.asLong();
    }

    private static int intValue(JsonNode n, String field) {
        var value = n.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid integer field");
        }
        return value.asInt();
    }

    private static UUID uuid(JsonNode n, String field) {
        try {
            return UUID.fromString(text(n, field));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid uuid field");
        }
    }
}
