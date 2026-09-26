package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit contract/storage codec; no request polymorphism or framework types in the domain. */
public final class SourceReviewJson {
    public static final JsonMapper JSON = JsonMapper.builder().build();
    private SourceReviewJson() {}
    public static Map<String,String> strings(JsonNode node) {
        PipelineJson.fields(node, SourceReview.FIELDS); var result = new TreeMap<String,String>();
        node.properties().forEach(e -> result.put(e.getKey(), PipelineJson.text(node, e.getKey()))); return result;
    }
    public static Map<String,SourceReview.Choice> choices(JsonNode node) {
        var result = new TreeMap<String,SourceReview.Choice>(); strings(node).forEach((k,v) -> result.put(k, SourceReview.Choice.valueOf(v))); return result;
    }
    public static long positiveLong(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1 || value.asLong() > 9_007_199_254_740_991L) throw new IllegalArgumentException("Invalid version");
        return value.asLong();
    }
    public static UUID uuid(String text) {
        if (text == null || !text.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid UUID");
        return UUID.fromString(text);
    }
    public static Instant instant(String text) {
        try { return Instant.parse(text); } catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid timestamp"); }
    }
    public static Map<String,Object> wire(SourceReview r) {
        var m = new LinkedHashMap<String,Object>(); m.put("schemaVersion", "1.0"); m.put("id", r.id().toString()); m.put("tenantId", r.tenantId().value()); m.put("entityId", r.entityId().value().toString());
        m.put("baseVersion", r.baseVersion()); m.put("version", r.version()); m.put("status", r.status().name());
        m.put("sourceInstanceId", r.source().sourceInstanceId()); m.put("externalId", r.source().externalId());
        m.put("observedAt", r.observedAt().toString()); m.put("ingestedAt", r.ingestedAt().toString()); m.put("expiresAt", r.expiresAt().toString());
        m.put("values", r.values()); m.put("primaryAtImport", r.primaryAtImport()); m.put("mappingDigest", r.mappingDigest()); m.put("mappingId", "cmdb-host-import"); m.put("mappingRevision", 1);
        m.put("actor", r.actor()); m.put("dataMode", "import"); m.put("rawRecordRef", "source-review:" + r.id());
        if(r.identity()!=null) m.put("identity",AssetIdentityJson.pin(r.identity()));
        m.put("decisions", r.decisions().stream().map(d -> Map.of("requestId", d.requestId().toString(), "action", d.action().name(), "choices", d.choices(),
            "reason", d.reason(), "actor", d.actor(), "at", d.at().toString(), "entityVersion", d.entityVersion())).toList()); return m;
    }
    public static String encode(SourceReview r) { return JSON.writeValueAsString(wire(r)); }
    public static SourceReview decode(String value) {
        var n = JSON.readTree(value); var tenant = new TenantId(PipelineJson.text(n, "tenantId"));
        var decisions = new ArrayList<SourceReview.Decision>();
        for (var d : n.get("decisions")) decisions.add(new SourceReview.Decision(uuid(PipelineJson.text(d,"requestId")), SourceReview.Action.valueOf(PipelineJson.text(d,"action")), choices(d.get("choices")),
            PipelineJson.text(d,"reason"), PipelineJson.text(d,"actor"), instant(PipelineJson.text(d,"at")), positiveLong(d,"entityVersion")));
        var r = new SourceReview(uuid(PipelineJson.text(n,"id")), tenant, EntityId.parse(PipelineJson.text(n,"entityId")), positiveLong(n,"baseVersion"),
            new ExternalObjectKey(tenant, PipelineJson.text(n,"sourceInstanceId"), "cmdb-host", PipelineJson.text(n,"externalId"), "1"),
            instant(PipelineJson.text(n,"observedAt")), instant(PipelineJson.text(n,"ingestedAt")), strings(n.get("values")), strings(n.get("primaryAtImport")), PipelineJson.text(n,"mappingDigest"), PipelineJson.text(n,"actor"), decisions, AssetIdentityJson.pin(n.get("identity")));
        if (!JSON.readTree(encode(r)).equals(n)) throw new IllegalStateException("Invalid persisted source review");
        return r;
    }
    public static String encode(SourceReview.Command c) { return JSON.writeValueAsString(Map.of("requestId", c.requestId().toString(), "action", c.action().name(),
        "expectedEntityVersion", c.expectedEntityVersion(), "expectedReviewVersion", c.expectedReviewVersion(), "choices", c.choices(), "reason", c.reason(), "actor", c.actor())); }
    public static SourceReview.Command command(String encoded) {
        var n = JSON.readTree(encoded); return new SourceReview.Command(uuid(PipelineJson.text(n,"requestId")), SourceReview.Action.valueOf(PipelineJson.text(n,"action")),
            positiveLong(n,"expectedEntityVersion"), PipelineJson.integer(n,"expectedReviewVersion", null), choices(n.get("choices")), PipelineJson.text(n,"reason"), PipelineJson.text(n,"actor"));
    }
    public static String snapshot(Entity e) { return JSON.writeValueAsString(Map.of("id", e.id().value().toString(), "tenantId", e.tenantId().value(), "entityType", e.entityType(),
        "name", e.name(), "lifecycle", e.lifecycle().name(), "version", e.version(), "lastSeen", e.lastSeen().toString(), "attributes", e.attributes())); }
    public static Entity snapshot(String encoded) {
        var n = JSON.readTree(encoded); Map<String,Object> attributes = JSON.convertValue(n.get("attributes"), new tools.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});
        EntityReadLimits.check(attributes);
        return new Entity(EntityId.parse(PipelineJson.text(n,"id")), new TenantId(PipelineJson.text(n,"tenantId")), PipelineJson.text(n,"entityType"), PipelineJson.text(n,"name"),
            Lifecycle.valueOf(PipelineJson.text(n,"lifecycle")), positiveLong(n,"version"), instant(PipelineJson.text(n,"lastSeen")), attributes);
    }
}
