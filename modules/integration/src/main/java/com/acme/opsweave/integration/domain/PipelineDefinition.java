package com.acme.opsweave.integration.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PipelineDefinition(
    String id,
    int revision,
    String sourceType,
    String objectType,
    List<PipelineNode> nodes,
    List<PipelineEdge> edges,
    ErrorPolicy errorPolicy
) {
    public PipelineDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(errorPolicy, "errorPolicy");
        if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid pipeline id");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("Pipeline revision must start at 1");
        }
        if (!sourceType.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Invalid source type");
        }
        if (!objectType.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Invalid object type");
        }
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Pipeline must declare nodes");
        }
        validateGraph(nodes, edges);
    }

    public List<PipelineNode> executionOrder() {
        Map<String, PipelineNode> byId = new HashMap<>();
        for (PipelineNode node : nodes) {
            byId.put(node.id(), node);
        }
        Map<String, String> outgoing = new HashMap<>();
        Set<String> targets = new HashSet<>();
        for (PipelineEdge edge : edges) {
            outgoing.put(edge.from(), edge.to());
            targets.add(edge.to());
        }
        PipelineNode start = nodes.stream()
            .filter(node -> node.type() == NodeType.SOURCE)
            .findFirst()
            .orElseThrow();
        if (targets.contains(start.id())) {
            throw new IllegalArgumentException("Source node cannot be an edge target");
        }
        List<PipelineNode> ordered = new ArrayList<>();
        String current = start.id();
        Set<String> seen = new HashSet<>();
        while (current != null) {
            if (!seen.add(current)) {
                throw new IllegalArgumentException("Pipeline graph must be acyclic");
            }
            ordered.add(byId.get(current));
            current = outgoing.get(current);
        }
        if (ordered.size() != nodes.size()) {
            throw new IllegalArgumentException("Pipeline graph must be a single chain covering every node");
        }
        if (ordered.getLast().type() != NodeType.WRITE_OBSERVATION) {
            throw new IllegalArgumentException("Pipeline must end with WriteObservation");
        }
        return List.copyOf(ordered);
    }

    public static PipelineDefinition zabbixHostV1() {
        return new PipelineDefinition(
            "zabbix-host-default",
            1,
            "zabbix",
            "host",
            List.of(
                new PipelineNode("source", NodeType.SOURCE),
                new PipelineNode("parse", NodeType.PARSE),
                new PipelineNode("map", NodeType.MAP),
                new PipelineNode("validate", NodeType.VALIDATE),
                new PipelineNode("resolve", NodeType.ENTITY_RESOLVE),
                new PipelineNode("write", NodeType.WRITE_OBSERVATION)
            ),
            List.of(
                new PipelineEdge("source", "parse"),
                new PipelineEdge("parse", "map"),
                new PipelineEdge("map", "validate"),
                new PipelineEdge("validate", "resolve"),
                new PipelineEdge("resolve", "write")
            ),
            ErrorPolicy.FAIL_FAST
        );
    }

    private static void validateGraph(List<PipelineNode> nodes, List<PipelineEdge> edges) {
        Set<String> ids = new HashSet<>();
        int sources = 0;
        int writes = 0;
        for (PipelineNode node : nodes) {
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("Duplicate pipeline node id");
            }
            if (node.type() == NodeType.SOURCE) {
                sources++;
            }
            if (node.type() == NodeType.WRITE_OBSERVATION) {
                writes++;
            }
        }
        if (sources != 1 || writes != 1) {
            throw new IllegalArgumentException("Pipeline must have exactly one Source and one WriteObservation");
        }
        Set<NodeType> required = Set.of(
            NodeType.SOURCE, NodeType.PARSE, NodeType.MAP,
            NodeType.VALIDATE, NodeType.ENTITY_RESOLVE, NodeType.WRITE_OBSERVATION
        );
        Set<NodeType> present = new HashSet<>();
        for (PipelineNode node : nodes) {
            present.add(node.type());
        }
        if (!present.containsAll(required)) {
            throw new IllegalArgumentException("First pipeline version requires Source/Parse/Map/Validate/EntityResolve/WriteObservation");
        }
        for (PipelineEdge edge : edges) {
            if (!ids.contains(edge.from()) || !ids.contains(edge.to())) {
                throw new IllegalArgumentException("Pipeline edge references an unknown node");
            }
        }
        if (edges.size() != nodes.size() - 1) {
            throw new IllegalArgumentException("First pipeline version must be a linear chain");
        }
    }
}
