package com.acme.opsweave.integration.domain;

import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One executable mapping document. The item key and metric key come from configuration, not from mapper code. */
public record MappingDefinition(
    String id,
    String connector,
    String itemKeyExact,
    String metricKey,
    String displayName,
    MetricType metricType,
    String unit,
    MetricValueType valueType,
    List<String> dimensionSchema,
    Map<String, String> fixedDimensions,
    String valueTransform,
    int mappingRevision
) {
    public MappingDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(itemKeyExact, "itemKeyExact");
        Objects.requireNonNull(metricKey, "metricKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(metricType, "metricType");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(dimensionSchema, "dimensionSchema");
        Objects.requireNonNull(fixedDimensions, "fixedDimensions");
        Objects.requireNonNull(valueTransform, "valueTransform");
        if (id.isBlank() || connector.isBlank() || itemKeyExact.isBlank() || metricKey.isBlank()
            || displayName.isBlank() || unit.isBlank() || valueTransform.isBlank()) {
            throw new IllegalArgumentException("Mapping definition fields must be present");
        }
        if (mappingRevision < 1) {
            throw new IllegalArgumentException("Mapping revision must start at 1");
        }
        dimensionSchema = List.copyOf(dimensionSchema);
        for (String name : fixedDimensions.keySet()) {
            if (!dimensionSchema.contains(name)) {
                throw new IllegalArgumentException("Fixed dimension is outside the schema");
            }
        }
        fixedDimensions = Map.copyOf(new LinkedHashMap<>(fixedDimensions));
    }
}
