package com.acme.opsweave.platform.catalog;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.catalog.api.ModelCatalogStore;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/** Closed, bounded wire adapter for contracts/schemas/v1/model-*. */
public final class CatalogJson {
    public static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private CatalogJson() {}
    public static JsonNode read(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(65537);
        if (bytes.length == 0 || bytes.length > 65536) throw new IllegalArgumentException("Invalid catalog body size");
        try { return JSON.readTree(bytes); } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid catalog JSON"); }
    }
    public static ModelDefinition definition(JsonNode n) {
        fields(n, Set.of("schemaVersion", "id", "revision", "kind", "label", "description", "cleaningProfile", "fields", "endpoints"));
        if (!"1.0".equals(text(n, "schemaVersion")) || !ModelDefinition.PROFILE.equals(text(n, "cleaningProfile")) || !n.has("description") || !n.get("description").isString()) throw new IllegalArgumentException();
        var items = n.get("fields");
        if (items == null || !items.isArray() || items.size() > 32) throw new IllegalArgumentException();
        var parsed = new ArrayList<ModelDefinition.Field>();
        for (var f : items) {
            fields(f, Set.of("id", "label", "type", "required", "maxLength", "min", "max", "choices"));
            if (!f.has("required") || !f.get("required").isBoolean()) throw new IllegalArgumentException();
            var choices = new ArrayList<String>();
            if (f.has("choices")) {
                if (!f.get("choices").isArray() || f.get("choices").size() == 0 || f.get("choices").size() > 32) throw new IllegalArgumentException();
                for (var choice : f.get("choices")) { if (!choice.isString()) throw new IllegalArgumentException(); choices.add(choice.asString()); }
            }
            parsed.add(new ModelDefinition.Field(text(f, "id"), text(f, "label"), ModelDefinition.Type.valueOf(text(f, "type")), f.get("required").asBoolean(),
                f.has("maxLength") ? integer(f, "maxLength", null) : null, number(f, "min"), number(f, "max"), choices));
        }
        ModelDefinition.Endpoints endpoints = null;
        if (n.has("endpoints")) { var e = n.get("endpoints"); fields(e, Set.of("from", "to", "cardinality")); endpoints = new ModelDefinition.Endpoints(ref(e.get("from")), ref(e.get("to")), ModelDefinition.Cardinality.valueOf(text(e, "cardinality"))); }
        var definition = new ModelDefinition(text(n, "id"), integer(n, "revision", null), ModelDefinition.Kind.valueOf(text(n, "kind")), text(n, "label"), n.get("description").asString(), parsed, endpoints);
        // 2 x 50 entries still fit the shared 2 MiB response budget, including UTF-8/escaping.
        if (encode(definition).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) throw new IllegalArgumentException("Model definition too large");
        return definition;
    }
    public static ModelDefinition.Ref ref(JsonNode n) { fields(n, Set.of("id", "revision")); return new ModelDefinition.Ref(text(n, "id"), integer(n, "revision", null)); }
    private static BigDecimal number(JsonNode n, String key) { if (!n.has(key)) return null; if (!n.get(key).isNumber()) throw new IllegalArgumentException(); return new BigDecimal(n.get(key).toString()); }
    public static Map<String, Object> sample(JsonNode n) {
        if (n == null || !n.isObject() || n.size() > 32) throw new IllegalArgumentException();
        var result = new LinkedHashMap<String, Object>();
        for (var entry : n.properties()) { var v = entry.getValue(); Object value;
            if (v.isNull()) value = null; else if (v.isString()) value = v.asString(); else if (v.isNumber()) value = new BigDecimal(v.toString()); else if (v.isBoolean()) value = v.asBoolean(); else throw new IllegalArgumentException();
            result.put(entry.getKey(), value);
        }
        return result;
    }
    public static Map<String, Object> wire(ModelDefinition d) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "1.0"); result.put("id", d.id()); result.put("revision", d.revision()); result.put("kind", d.kind().name());
        result.put("label", d.label()); result.put("description", d.description()); result.put("cleaningProfile", ModelDefinition.PROFILE);
        result.put("fields", d.fields().stream().map(f -> {
            var m = new LinkedHashMap<String, Object>(); m.put("id", f.id()); m.put("label", f.label()); m.put("type", f.type().name()); m.put("required", f.required());
            if (f.maxLength() != null) m.put("maxLength", f.maxLength()); if (f.min() != null) m.put("min", f.min()); if (f.max() != null) m.put("max", f.max()); if (!f.choices().isEmpty()) m.put("choices", f.choices()); return m;
        }).toList());
        if (d.endpoints() != null) result.put("endpoints", d.endpoints()); return result;
    }
    public static Map<String, Object> wire(ModelCatalogStore.Entry e) { return Map.of("definition", wire(e.definition()), "digest", e.digest(), "state", e.state(), "editVersion", e.editVersion(), "updatedAt", e.updatedAt().toString()); }
    public static String encode(ModelDefinition d) { return JSON.writeValueAsString(wire(d)); }
    public static ModelDefinition decode(String json) { return definition(JSON.readTree(json)); }
}
