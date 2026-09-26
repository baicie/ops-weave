package com.acme.opsweave.platform.integration;

import com.acme.opsweave.integration.domain.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit wire adapter for the schemas in contracts; no polymorphic deserialization. */
public final class PipelineJson {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private PipelineJson() {}
    public static JsonNode read(HttpServletRequest request, boolean optional) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(16_385);
        if (bytes.length == 0 && optional) return null;
        if (bytes.length == 0 || bytes.length > 16_384) throw new IllegalArgumentException("Invalid pipeline body size");
        try { return JSON.readTree(bytes); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid pipeline JSON"); }
    }
    public static PipelineDefinition definition(JsonNode node) {
        fields(node, Set.of("id", "revision", "source", "nodes", "edges", "errorPolicy"));
        var source = node.get("source");
        fields(source, Set.of("type", "objectType"));
        var nodes = node.get("nodes");
        var edges = node.get("edges");
        if (nodes == null || !nodes.isArray() || nodes.size() != 6 || edges == null || !edges.isArray() || edges.size() != 5) {
            throw new IllegalArgumentException("Invalid pipeline graph");
        }
        var parsedNodes = new ArrayList<PipelineNode>();
        for (var item : nodes) {
            fields(item, Set.of("id", "type", "config"));
            Map<String, String> config = new LinkedHashMap<>();
            if (item.has("config")) {
                fields(item.get("config"), Set.of("displayNameField"));
                item.get("config").properties().forEach(entry -> config.put(entry.getKey(), text(item.get("config"), entry.getKey())));
            }
            String typeName = text(item, "type");
            NodeType type = NodeType.fromWire(typeName);
            if (!type.wireName().equals(typeName)) throw new IllegalArgumentException("Invalid node type");
            parsedNodes.add(new PipelineNode(text(item, "id"), type, config));
        }
        var parsedEdges = new ArrayList<PipelineEdge>();
        for (var item : edges) {
            fields(item, Set.of("from", "to"));
            parsedEdges.add(new PipelineEdge(text(item, "from"), text(item, "to")));
        }
        String policy = text(node, "errorPolicy");
        if (!Set.of("failFast", "skipRecord").contains(policy)) throw new IllegalArgumentException("Invalid error policy");
        return new PipelineDefinition(text(node, "id"), integer(node, "revision", null), text(source, "type"),
            text(source, "objectType"), parsedNodes, parsedEdges, ErrorPolicy.fromWire(policy));
    }
    public static PipelineDefinition decode(String json) { return definition(JSON.readTree(json)); }
    public static String encode(PipelineDefinition definition) { return JSON.writeValueAsString(wire(definition)); }
    public static Map<String, Object> wire(PipelineDefinition definition) {
        return Map.of("id", definition.id(), "revision", definition.revision(),
            "source", Map.of("type", definition.sourceType(), "objectType", definition.objectType()),
            "nodes", definition.executionOrder().stream().map(node -> Map.of("id", node.id(), "type", node.type().wireName(), "config", node.config())).toList(),
            "edges", definition.edges(), "errorPolicy", definition.errorPolicy().wireName());
    }
    public static Map<String, Object> wire(PipelineVersion version) {
        return Map.of("definition", wire(version.definition()), "digest", version.digest(), "engine", PipelineVersion.ENGINE, "state", "PUBLISHED");
    }
    public static PipelineVersion.Ref ref(JsonNode node) {
        fields(node, Set.of("id", "revision", "digest"));
        return new PipelineVersion.Ref(text(node, "id"), integer(node, "revision", null), text(node, "digest"));
    }
    public static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected JSON object");
        if (node.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) throw new IllegalArgumentException("Unknown field");
    }
    public static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) throw new IllegalArgumentException("Invalid text field");
        return value.asString();
    }
    public static int integer(JsonNode node, String field, Integer fallback) {
        var value = node.get(field);
        if (value == null && fallback != null) return fallback;
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("Invalid integer field");
        return value.asInt();
    }
}
