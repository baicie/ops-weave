package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

/** A bounded current one-hop view, never an inferred dependency graph. */
public record EntityTopology(TenantId tenant, EntityId center, Instant asOf, List<Node> nodes, List<Edge> edges, boolean truncated) {
    public static final int LIMIT = 50;
    public EntityTopology {
        Objects.requireNonNull(tenant); Objects.requireNonNull(center); Objects.requireNonNull(asOf);
        nodes = List.copyOf(nodes); edges = List.copyOf(edges);
        if (nodes.isEmpty() || nodes.size() > LIMIT + 1 || edges.size() > LIMIT || truncated && edges.size() != LIMIT) throw new IllegalArgumentException("Topology exceeds bounds");
        var ids = new HashSet<EntityId>();
        for (var node : nodes) if (!ids.add(node.id())) throw new IllegalArgumentException("Duplicate node");
        if (!ids.contains(center)) throw new IllegalArgumentException("Missing center");
        var edgeIds = new HashSet<UUID>(); var reached = new HashSet<EntityId>(); reached.add(center);
        for (var edge : edges) {
            if (!edgeIds.add(edge.id()) || !ids.contains(edge.from()) || !ids.contains(edge.to())
                || !edge.from().equals(center) && !edge.to().equals(center)
                || edge.validFrom().isAfter(asOf) || edge.validTo() != null && !edge.validTo().isAfter(asOf)) throw new IllegalArgumentException("Invalid topology edge");
            reached.add(edge.from()); reached.add(edge.to());
        }
        if (!reached.equals(ids)) throw new IllegalArgumentException("Unrelated node");
    }
    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid topology text");
        return value;
    }
    private static String mode(String value) {
        if (!Set.of("fixture", "zabbix-jsonrpc", "import", "unknown").contains(value)) throw new IllegalArgumentException("Invalid origin");
        return value;
    }
    public record Node(EntityId id, String name, String type, String lifecycle, String dataMode) {
        public Node { Objects.requireNonNull(id); name=text(name,255); type=text(type,64); Lifecycle.valueOf(lifecycle); dataMode=mode(dataMode); }
    }
    public record Edge(UUID id, EntityId from, EntityId to, String type, Instant validFrom, Instant validTo, String dataMode) {
        public Edge { Objects.requireNonNull(id); Objects.requireNonNull(from); Objects.requireNonNull(to); type=text(type,64); Objects.requireNonNull(validFrom); dataMode=mode(dataMode);
            if (validTo != null && !validTo.isAfter(validFrom)) throw new IllegalArgumentException("Invalid relation interval"); }
    }
}
