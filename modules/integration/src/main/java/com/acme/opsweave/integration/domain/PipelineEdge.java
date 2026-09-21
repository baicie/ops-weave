package com.acme.opsweave.integration.domain;

import java.util.Objects;

public record PipelineEdge(String from, String to) {
    public PipelineEdge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isBlank() || to.isBlank()) {
            throw new IllegalArgumentException("Pipeline edge endpoints are required");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("Pipeline edge cannot be a self-loop");
        }
    }
}
