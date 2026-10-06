package com.acme.opsweave.integration.domain;

import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import java.math.BigDecimal;
import java.time.Instant;
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
    int mappingRevision,
    BigDecimal minimum,
    BigDecimal maximum
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
        if (minimum != null) new MetricPoint(Instant.EPOCH, minimum);
        if (maximum != null) new MetricPoint(Instant.EPOCH, maximum);
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Mapping numeric range is inverted");
        }
        if(connector.length()>64||itemKeyExact.length()>1024||metricKey.length()>255||displayName.length()>255||unit.length()>64||valueTransform.length()>64
            ||dimensionSchema.size()>32||dimensionSchema.stream().distinct().count()!=dimensionSchema.size()||dimensionSchema.stream().anyMatch(n->n==null||n.isBlank()||n.length()>64)
            ||fixedDimensions.size()>32||fixedDimensions.values().stream().anyMatch(v->v==null||v.length()>256))throw new IllegalArgumentException("Mapping definition exceeds bounds");
        new com.acme.opsweave.telemetry.domain.MetricMappingPin(id,mappingRevision,"sha256:"+"0".repeat(64));
        dimensionSchema = List.copyOf(dimensionSchema);
        for (String name : fixedDimensions.keySet()) {
            if (!dimensionSchema.contains(name)) {
                throw new IllegalArgumentException("Fixed dimension is outside the schema");
            }
        }
        fixedDimensions = Map.copyOf(new LinkedHashMap<>(fixedDimensions));
    }

    public MetricPoint normalize(Instant timestamp, BigDecimal sourceValue) {
        MetricPoint point = MetricPoint.normalize(timestamp, sourceValue, valueTransform);
        if ((minimum != null && point.value().compareTo(minimum) < 0)
            || (maximum != null && point.value().compareTo(maximum) > 0)) {
            throw new IllegalArgumentException("Metric value outside configured range");
        }
        return point;
    }

    public com.acme.opsweave.telemetry.domain.MetricMappingPin pin() {
        var parts = new java.util.ArrayList<>(List.of("metric-mapping-v1",id,connector,itemKeyExact,metricKey,
            displayName,metricType.name(),unit,valueType.name(),valueTransform,Integer.toString(mappingRevision),
            Objects.toString(minimum,""),Objects.toString(maximum,"")));
        parts.add(Integer.toString(dimensionSchema.size()));parts.addAll(dimensionSchema);
        parts.add(Integer.toString(fixedDimensions.size()));
        new java.util.TreeMap<>(fixedDimensions).forEach((k,v)->{parts.add(k);parts.add(v);});
        return new com.acme.opsweave.telemetry.domain.MetricMappingPin(id,mappingRevision,WorkflowDefinition.hash(parts));
    }
}
