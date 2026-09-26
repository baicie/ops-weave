package com.acme.opsweave.integration.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record PipelineReplaySpec(UUID syncRunId, PipelineVersion.Ref targetVersion, int limit, String purpose) {
    public PipelineReplaySpec {
        Objects.requireNonNull(syncRunId); Objects.requireNonNull(targetVersion);
        if (limit < 1 || limit > 100 || purpose == null || !Set.of("VALIDATE_MAPPING", "COMPARE_VERSION").contains(purpose)) {
            throw new IllegalArgumentException("Invalid replay specification");
        }
    }
}
