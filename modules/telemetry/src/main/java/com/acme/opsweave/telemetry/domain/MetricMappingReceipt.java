package com.acme.opsweave.telemetry.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable metadata maintenance result; it does not certify previously stored metric points. */
public record MetricMappingReceipt(UUID requestId, long expectedBindingVersion, String commandDigest,
        MetricMappingPin previousPin, MetricBinding binding, Instant createdAt) {
    public MetricMappingReceipt {
        Objects.requireNonNull(requestId); Objects.requireNonNull(binding); Objects.requireNonNull(createdAt);
        if (expectedBindingVersion < 1 || expectedBindingVersion > 1_000_000_000L || commandDigest == null
                || !commandDigest.matches("sha256:[a-f0-9]{64}") || binding.mappingPin() == null
                || binding.lifecycle() != MetricLifecycle.ACTIVE
                || binding.version() != expectedBindingVersion + (binding.mappingPin().equals(previousPin) ? 0 : 1))
            throw new IllegalArgumentException("Invalid metric mapping receipt");
    }
}
