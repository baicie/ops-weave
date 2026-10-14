package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.ReceiptRetention;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.acme.opsweave.platform.integration.PipelineJson.fields;
import static com.acme.opsweave.platform.integration.PipelineJson.integer;
import static com.acme.opsweave.platform.integration.PipelineJson.text;

/** Closed wire adapter for the receipt retention budget, preview and cleanup receipt. */
public final class ReceiptRetentionJson {
    private ReceiptRetentionJson() {}

    /** The confirm command is a closed set: no tenant, actor, permission or schedule. */
    public static ReceiptRetention.Command command(JsonNode n) {
        fields(n, Set.of("requestId", "kind", "scopeKey", "previewDigest", "reason", "maxRemove"));
        return new ReceiptRetention.Command(UUID.fromString(text(n, "requestId")), ReceiptRetention.Kind.of(text(n, "kind")),
            text(n, "scopeKey"), text(n, "previewDigest"), text(n, "reason"), integer(n, "maxRemove", null));
    }

    public static String storage(ReceiptRetention.Receipt receipt) {
        return CatalogJson.JSON.writeValueAsString(wire(receipt));
    }

    public static ReceiptRetention.Receipt receipt(String json) {
        var n = CatalogJson.JSON.readTree(json);
        fields(n, Set.of("schemaVersion", "requestId", "commandDigest", "createdAt", "kind", "scopeKey",
            "removed", "keptProtected", "keptUnfinished", "reason"));
        if (n.has("schemaVersion") && !"1.0".equals(text(n, "schemaVersion"))) throw new IllegalArgumentException();
        return new ReceiptRetention.Receipt(UUID.fromString(text(n, "requestId")), text(n, "commandDigest"),
            Instant.parse(text(n, "createdAt")), ReceiptRetention.Kind.of(text(n, "kind")), text(n, "scopeKey"),
            integer(n, "removed", null), integer(n, "keptProtected", null), integer(n, "keptUnfinished", null), text(n, "reason"));
    }

    /** The receipt is immutable: a replay and a later read return this exact same body. */
    public static Map<String, Object> wire(ReceiptRetention.Receipt receipt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.0");
        body.put("requestId", receipt.requestId());
        body.put("commandDigest", receipt.commandDigest());
        body.put("createdAt", receipt.createdAt());
        body.put("kind", receipt.kind().label());
        body.put("scopeKey", receipt.scopeKey());
        body.put("removed", receipt.removed());
        body.put("keptProtected", receipt.keptProtected());
        body.put("keptUnfinished", receipt.keptUnfinished());
        body.put("reason", receipt.reason());
        return body;
    }

    public static Map<String, Object> wire(ReceiptRetention.Report report) {
        var body = new LinkedHashMap<String, Object>();
        body.put("kind", report.kind().label());
        body.put("scopeKey", report.scopeKey());
        body.put("kept", report.kept());
        body.put("keptProtected", report.protectedRows());
        body.put("maxPerScope", report.maxPerScope());
        body.put("maxPerTenant", report.maxPerTenant());
        body.put("status", report.status().name());
        body.put("summary", report.summary());
        return body;
    }

    /** The preview names exactly the rows a confirm would remove and keeps both retention counts. */
    public static Map<String, Object> wire(ReceiptRetention.Preview preview) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.0");
        body.put("kind", preview.kind().label());
        body.put("scopeKey", preview.scopeKey());
        body.put("previewDigest", preview.previewDigest());
        body.put("candidates", new ArrayList<>(preview.candidates()));
        body.put("removable", preview.removable());
        body.put("keptProtected", preview.keptProtected());
        body.put("keptUnfinished", preview.keptUnfinished());
        body.put("maxRemove", preview.maxRemove());
        return body;
    }

    public static Map<String, Object> envelope(List<ReceiptRetention.Report> reports) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.0");
        body.put("dataMode", "receipt-log");
        body.put("items", reports.stream().map(ReceiptRetentionJson::wire).toList());
        body.put("count", reports.size());
        return body;
    }
}
