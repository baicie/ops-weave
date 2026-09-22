package com.acme.opsweave.integration.domain;

import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import java.util.Objects;

/** Catalog entry plus the source binding produced from one mapped item. */
public record MappedMetric(MetricDefinition definition, MetricBinding binding) {
    public MappedMetric {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(binding, "binding");
        if (!definition.tenantId().equals(binding.tenantId()) || !definition.metricKey().equals(binding.metricKey())) {
            throw new IllegalArgumentException("Binding must reference its definition");
        }
    }
}
