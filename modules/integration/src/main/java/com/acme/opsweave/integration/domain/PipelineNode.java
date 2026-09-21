package com.acme.opsweave.integration.domain;

import java.util.Map;
import java.util.Objects;

public record PipelineNode(String id, NodeType type, Map<String, String> config) {
    public PipelineNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(config, "config");
        if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid node id");
        }
        config = Map.copyOf(config);
    }

    public PipelineNode(String id, NodeType type) {
        this(id, type, Map.of());
    }
}
