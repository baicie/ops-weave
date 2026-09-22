package com.acme.opsweave.telemetry.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Map;
import java.util.Objects;

/**
 * What a metric is. Point values and history stay out of this object.
 */
public record MetricDefinition(
    String id,
    TenantId tenantId,
    String name,
    String displayName,
    String entityType,
    String unit,
    MetricValueType valueType,
    MetricType metricType,
    Map<String, String> dimensions,
    MetricOrigin origin,
    ExternalMetricMapping externalMapping,
    MetricLifecycle lifecycle,
    long version
) {
    public MetricDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(metricType, "metricType");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(externalMapping, "externalMapping");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (id.isBlank() || name.isBlank() || displayName.isBlank() || entityType.isBlank()) {
            throw new IllegalArgumentException("Metric definition identity fields must be present");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Metric definition version must start at 1");
        }
        dimensions = Map.copyOf(dimensions);
    }
}
