package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricReplay.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowMetricReplayJson {
    private WorkflowMetricReplayJson() {}
    private static void shape(JsonNode n, String... keys) { fields(n, Set.of(keys)); if (n.size() != keys.length) throw new IllegalArgumentException(); }
    private static boolean bool(JsonNode n, String key) { if (!n.get(key).isBoolean()) throw new IllegalArgumentException(); return n.get(key).asBoolean(); }
    private static String nullable(JsonNode n, String key) { return n.get(key).isNull() ? null : text(n, key); }
    private static java.time.Instant time(JsonNode n, String key) { return WorkflowSampleRecoveryJson.instant(text(n, key)); }
    private static UUID uuid(JsonNode n, String key) { return WorkflowRecoveryJson.canonical(text(n, key)); }
    private static WorkflowQuality.Reference reference(JsonNode n) { shape(n, "id", "revision", "digest"); return new WorkflowQuality.Reference(text(n, "id"), integer(n, "revision", null), text(n, "digest")); }
    public static Command command(String json) {
        var n = CatalogJson.JSON.readTree(json); shape(n, "requestId", "id", "revision", "digest", "from", "till");
        return new Command(uuid(n, "requestId"), text(n, "id"), integer(n, "revision", null), text(n, "digest"), time(n, "from"), time(n, "till"));
    }
    public static Execute execute(String json) {
        var n = CatalogJson.JSON.readTree(json); shape(n, "requestId", "planId", "inputDigest", "batchDigest");
        return new Execute(uuid(n, "requestId"), uuid(n, "planId"), text(n, "inputDigest"), text(n, "batchDigest"));
    }
    private static Proof proof(JsonNode n) {
        if (n.isNull()) return null; shape(n, "inputDigest", "batchDigest", "inputCount", "filtered", "collapsed", "labels", "timestamps");
        var labels = new HashMap<String,String>(); var l = n.get("labels"); if (!l.isObject() || l.size() > 24) throw new IllegalArgumentException();
        for (var e : l.properties()) { if (!e.getValue().isString()) throw new IllegalArgumentException(); labels.put(e.getKey(), e.getValue().asString()); }
        var timestamps = new ArrayList<Long>(); var t = n.get("timestamps"); if (!t.isArray() || t.size() > 60) throw new IllegalArgumentException();
        for (var v : t) { if (!v.isIntegralNumber() || !v.canConvertToLong()) throw new IllegalArgumentException(); timestamps.add(v.asLong()); }
        return new Proof(text(n, "inputDigest"), text(n, "batchDigest"), integer(n, "inputCount", null), integer(n, "filtered", null), integer(n, "collapsed", null), labels, timestamps);
    }
    public static Plan plan(String json) {
        var n = CatalogJson.JSON.readTree(json); shape(n, "schemaVersion", "requestId", "reference", "commandDigest", "from", "till", "createdAt", "updatedAt", "expiresAt", "purpose", "outputPolicy", "notifications", "actions", "state", "proof", "error");
        return new Plan(text(n, "schemaVersion"), uuid(n, "requestId"), reference(n.get("reference")), text(n, "commandDigest"), time(n, "from"), time(n, "till"), time(n, "createdAt"), time(n, "updatedAt"), time(n, "expiresAt"), text(n, "purpose"), text(n, "outputPolicy"), bool(n, "notifications"), bool(n, "actions"), text(n, "state"), proof(n.get("proof")), nullable(n, "error"));
    }
    public static Receipt receipt(String json) {
        var n = CatalogJson.JSON.readTree(json); shape(n, "schemaVersion", "requestId", "planId", "reference", "commandDigest", "acceptedAt", "updatedAt", "state", "error");
        return new Receipt(text(n, "schemaVersion"), uuid(n, "requestId"), uuid(n, "planId"), reference(n.get("reference")), text(n, "commandDigest"), time(n, "acceptedAt"), time(n, "updatedAt"), text(n, "state"), nullable(n, "error"));
    }
}
