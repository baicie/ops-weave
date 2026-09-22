package com.acme.opsweave.telemetry.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared metric semantics. One definition covers every source item that means the same measurement.
 * Source item identity lives on {@link MetricBinding}. Point samples are not part of this object.
 */
public record MetricDefinition(
    TenantId tenantId,
    String metricKey,
    String displayName,
    String unit,
    MetricValueType valueType,
    MetricType metricType,
    List<String> dimensionSchema,
    long version
) {
    public MetricDefinition {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(metricKey, "metricKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(metricType, "metricType");
        Objects.requireNonNull(dimensionSchema, "dimensionSchema");
        if (metricKey.isBlank() || displayName.isBlank() || unit.isBlank()) {
            throw new IllegalArgumentException("Metric definition fields must be present");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Metric definition version must start at 1");
        }
        List<String> names = new ArrayList<>();
        for (String name : dimensionSchema) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Dimension schema names must be present");
            }
            if (names.contains(name)) {
                throw new IllegalArgumentException("Dimension schema repeats " + name);
            }
            names.add(name);
        }
        dimensionSchema = List.copyOf(names);
    }
}
