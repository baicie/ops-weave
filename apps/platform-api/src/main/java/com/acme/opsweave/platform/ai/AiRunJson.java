package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunControl;
import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import com.acme.opsweave.aicontrol.domain.InsightSubmission;
import com.acme.opsweave.aicontrol.domain.ToolWindow;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.TenantId;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.acme.opsweave.platform.integration.PipelineJson.fields;
import static com.acme.opsweave.platform.integration.PipelineJson.text;

/**
 * Closed wire adapter for the durable run registry (contracts/schemas/v2/ai-run-*): the submit
 * command, the run record and the run page, plus the 5b control commands and their idempotent
 * receipts (cancel/retry). Tenant, subject and permissions never appear on the wire, and a stored
 * body or receipt is accepted only when it round-trips to the exact same record.
 */
public final class AiRunJson {
    private AiRunJson() {}

    /** The command is a closed set: no tenant, actor, permission, status or approval flag. */
    public static AiRun.Command command(JsonNode n) {
        fields(n, Set.of("requestId", "incidentId", "question", "timeRange", "knowledgeMode", "skill"));
        var range = n.get("timeRange");
        fields(range, Set.of("from", "to"));
        var skill = n.get("skill");
        fields(skill, Set.of("id", "version", "digest"));
        return new AiRun.Command(uuid(n, "requestId"), uuid(n, "incidentId"), text(n, "question"),
            new ToolWindow(Instant.parse(text(range, "from")), Instant.parse(text(range, "to"))),
            text(n, "knowledgeMode"),
            new InsightSubmission.SkillRef(text(skill, "id"), text(skill, "version"), text(skill, "digest")));
    }

    /** The control command is one request id; the target run comes from the path, never the body. */
    public static AiRunControl.Command controlCommand(JsonNode n, AiRunControl.Kind kind, UUID runId) {
        fields(n, Set.of("requestId"));
        return new AiRunControl.Command(kind, uuid(n, "requestId"), runId);
    }

    /** Immutable submission parts plus the current state; identity is never on the wire. */
    public static Map<String, Object> wire(AiRun.Run run) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("runId", run.runId().toString());
        body.put("requestId", run.requestId().toString());
        body.put("commandDigest", run.commandDigest());
        body.put("status", run.status().name());
        body.put("statusVersion", run.statusVersion());
        body.put("incidentId", run.incidentId().toString());
        body.put("question", run.question());
        body.put("timeRange", Map.of("from", run.window().from().toString(), "to", run.window().to().toString()));
        body.put("knowledgeMode", run.knowledgeMode());
        body.put("skill", Map.of("id", run.skill().id(), "version", run.skill().version(), "digest", run.skill().digest()));
        body.put("attempt", run.attempt());
        if (run.cancelRequestedAt() != null) body.put("cancelRequestedAt", run.cancelRequestedAt().toString());
        if (run.reason() != null) body.put("reason", run.reason().name());
        if (run.disclosure() != null) body.put("disclosure", reconciliation(run.disclosure()));
        body.put("createdAt", run.createdAt().toString());
        body.put("updatedAt", run.updatedAt().toString());
        return body;
    }

    /** The closed duplicate-fee / adopted-result disclosure shared by the run and the retry receipt. */
    public static Map<String, Object> reconciliation(AiRunControl.Reconciliation value) {
        var body = new LinkedHashMap<String, Object>();
        body.put("adoptedPriorResult", value.adoptedPriorResult());
        body.put("rerun", value.rerun());
        body.put("priorModelCalls", value.priorModelCalls());
        body.put("uncertainPriorFees", value.uncertainPriorFees());
        body.put("duplicateFeeRisk", value.duplicateFeeRisk());
        return body;
    }

    public static Map<String, Object> wire(AiRunControl.CancelReceipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("requestId", receipt.requestId().toString());
        body.put("runId", receipt.runId().toString());
        body.put("requestedAt", receipt.requestedAt().toString());
        body.put("recorded", receipt.recorded());
        body.put("status", receipt.status().name());
        return body;
    }

    public static Map<String, Object> wire(AiRunControl.RetryReceipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("requestId", receipt.requestId().toString());
        body.put("runId", receipt.runId().toString());
        body.put("requestedAt", receipt.requestedAt().toString());
        body.put("attempt", receipt.attempt());
        body.put("status", receipt.status().name());
        body.put("reconciliation", reconciliation(receipt.reconciliation()));
        return body;
    }

    public static String storage(AiRun.Run run) {
        return CatalogJson.JSON.writeValueAsString(wire(run));
    }

    public static String storage(AiRunControl.CancelReceipt receipt) {
        return CatalogJson.JSON.writeValueAsString(wire(receipt));
    }

    public static String storage(AiRunControl.RetryReceipt receipt) {
        return CatalogJson.JSON.writeValueAsString(wire(receipt));
    }

    public static AiRun.Run decode(String json, String tenantId, String subjectId) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("schemaVersion", "runId", "requestId", "commandDigest", "status", "statusVersion", "incidentId",
            "question", "timeRange", "knowledgeMode", "skill", "attempt", "cancelRequestedAt", "reason", "disclosure",
            "createdAt", "updatedAt"));
        if (!"2.0".equals(text(n, "schemaVersion"))) throw new IllegalArgumentException("Invalid run schema version");
        var range = n.get("timeRange");
        fields(range, Set.of("from", "to"));
        var skill = n.get("skill");
        fields(skill, Set.of("id", "version", "digest"));
        var run = new AiRun.Run(uuid(n, "runId"), new TenantId(tenantId), new SubjectId(subjectId), uuid(n, "requestId"),
            text(n, "commandDigest"), uuid(n, "incidentId"), text(n, "question"),
            new ToolWindow(Instant.parse(text(range, "from")), Instant.parse(text(range, "to"))),
            text(n, "knowledgeMode"),
            new InsightSubmission.SkillRef(text(skill, "id"), text(skill, "version"), text(skill, "digest")),
            status(n), longValue(n, "statusVersion"), intValue(n, "attempt"),
            n.has("cancelRequestedAt") ? Instant.parse(text(n, "cancelRequestedAt")) : null,
            n.has("reason") ? reason(n) : null,
            n.has("disclosure") ? reconciliation(n.get("disclosure")) : null,
            Instant.parse(text(n, "createdAt")), Instant.parse(text(n, "updatedAt")));
        if (!CatalogJson.JSON.readTree(storage(run)).equals(n)) throw new IllegalArgumentException("Invalid stored run");
        return run;
    }

    public static AiRunControl.CancelReceipt cancelReceipt(String json) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("schemaVersion", "requestId", "runId", "requestedAt", "recorded", "status"));
        if (!"2.0".equals(text(n, "schemaVersion"))) throw new IllegalArgumentException("Invalid receipt schema version");
        var flag = n.get("recorded");
        if (flag == null || !flag.isBoolean()) throw new IllegalArgumentException("Invalid receipt flag");
        var receipt = new AiRunControl.CancelReceipt(uuid(n, "requestId"), uuid(n, "runId"),
            Instant.parse(text(n, "requestedAt")), flag.asBoolean(), status(n));
        if (!CatalogJson.JSON.readTree(storage(receipt)).equals(n)) throw new IllegalArgumentException("Invalid stored receipt");
        return receipt;
    }

    public static AiRunControl.RetryReceipt retryReceipt(String json) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("schemaVersion", "requestId", "runId", "requestedAt", "attempt", "status", "reconciliation"));
        if (!"2.0".equals(text(n, "schemaVersion"))) throw new IllegalArgumentException("Invalid receipt schema version");
        var receipt = new AiRunControl.RetryReceipt(uuid(n, "requestId"), uuid(n, "runId"),
            Instant.parse(text(n, "requestedAt")), intValue(n, "attempt"), status(n), reconciliation(n.get("reconciliation")));
        if (!CatalogJson.JSON.readTree(storage(receipt)).equals(n)) throw new IllegalArgumentException("Invalid stored receipt");
        return receipt;
    }

    /** One bounded page: items, the cursor of the last item and the explicit truncation flag. */
    public static Map<String, Object> page(AiRun.Page page) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("items", page.items().stream().map(AiRunJson::wire).toList());
        body.put("nextCursor", page.nextCursor());
        body.put("hasMore", page.hasMore());
        return body;
    }

    /**
     * One run event record (contracts/schemas/v2/ai-run-event): the six closed fields of the record
     * and nothing else — no reasoning text, no credential and no raw log or evidence body.
     */
    public static Map<String, Object> wire(AiRunEvent.Event event) {
        var body = new LinkedHashMap<String, Object>();
        body.put("runId", event.runId().toString());
        body.put("seq", event.seq());
        body.put("type", event.type().name());
        body.put("occurredAt", event.occurredAt().toString());
        body.put("payloadVersion", event.payloadVersion());
        body.put("payload", payload(event.payload()));
        return body;
    }

    private static Map<String, Object> payload(AiRunEvent.Payload payload) {
        var body = new LinkedHashMap<String, Object>();
        if (payload.status() != null) body.put("status", payload.status());
        if (payload.attempt() != null) body.put("attempt", payload.attempt());
        if (payload.reason() != null) body.put("reason", payload.reason());
        if (payload.disclosure() != null) body.put("disclosure", reconciliation(payload.disclosure()));
        return body;
    }

    public static String storage(AiRunEvent.Event event) {
        return CatalogJson.JSON.writeValueAsString(wire(event));
    }

    public static String storage(AiRunEvent.Page page) {
        return CatalogJson.JSON.writeValueAsString(page(page));
    }

    public static AiRunEvent.Event decodeEvent(String json, UUID runId) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("runId", "seq", "type", "occurredAt", "payloadVersion", "payload"));
        if (!runId.equals(uuid(n, "runId"))) throw new IllegalArgumentException("Invalid event run");
        var type = type(n);
        int payloadVersion = intValue(n, "payloadVersion");
        var event = new AiRunEvent.Event(runId, longValue(n, "seq"), type, Instant.parse(text(n, "occurredAt")),
            payloadVersion, payload(n.get("payload")));
        if (!CatalogJson.JSON.readTree(storage(event)).equals(n)) throw new IllegalArgumentException("Invalid stored event");
        return event;
    }

    private static AiRunEvent.Payload payload(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("Invalid event payload");
        fields(n, Set.of("status", "attempt", "reason", "disclosure"));
        return new AiRunEvent.Payload(n.has("status") ? text(n, "status") : null,
            n.has("attempt") ? intValue(n, "attempt") : null,
            n.has("reason") ? text(n, "reason") : null,
            n.has("disclosure") ? reconciliation(n.get("disclosure")) : null);
    }

    private static AiRunEvent.Type type(JsonNode n) {
        try {
            return AiRunEvent.Type.valueOf(text(n, "type"));
        } catch (IllegalArgumentException closed) {
            throw new IllegalArgumentException("Invalid event type");
        }
    }

    /** One bounded event page: the retained records, the cursor and the explicit gap flag. */
    public static Map<String, Object> page(AiRunEvent.Page page) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("runId", page.runId().toString());
        body.put("items", page.items().stream().map(AiRunJson::wire).toList());
        body.put("nextCursor", page.nextCursor());
        body.put("hasMore", page.hasMore());
        body.put("gap", page.gap());
        body.put("snapshot", snapshot(page.snapshot()));
        return body;
    }

    private static Map<String, Object> snapshot(AiRunEvent.Snapshot snapshot) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", snapshot.status());
        body.put("statusVersion", snapshot.statusVersion());
        body.put("attempt", snapshot.attempt());
        body.put("updatedAt", snapshot.updatedAt().toString());
        return body;
    }

    private static AiRunControl.Reconciliation reconciliation(JsonNode n) {
        fields(n, Set.of("adoptedPriorResult", "rerun", "priorModelCalls", "uncertainPriorFees", "duplicateFeeRisk"));
        return new AiRunControl.Reconciliation(bool(n, "adoptedPriorResult"), bool(n, "rerun"),
            intValue(n, "priorModelCalls"), intValue(n, "uncertainPriorFees"), bool(n, "duplicateFeeRisk"));
    }

    private static boolean bool(JsonNode n, String field) {
        var value = n.get(field);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException("Invalid boolean field");
        return value.asBoolean();
    }

    private static AiRun.Status status(JsonNode n) {
        try {
            return AiRun.Status.valueOf(text(n, "status"));
        } catch (IllegalArgumentException closed) {
            throw new IllegalArgumentException("Invalid run status");
        }
    }

    private static AiRun.Reason reason(JsonNode n) {
        try {
            return AiRun.Reason.valueOf(text(n, "reason"));
        } catch (IllegalArgumentException closed) {
            throw new IllegalArgumentException("Invalid run reason");
        }
    }

    private static long longValue(JsonNode n, String field) {
        var value = n.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException("Invalid integer field");
        }
        return value.asLong();
    }

    private static int intValue(JsonNode n, String field) {
        var value = n.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
            throw new IllegalArgumentException("Invalid integer field");
        }
        return value.asInt();
    }

    private static UUID uuid(JsonNode n, String field) {
        var value = text(n, field);
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }
}
