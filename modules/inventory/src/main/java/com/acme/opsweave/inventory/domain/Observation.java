package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record Observation(
    String id,
    ExternalObjectKey key,
    EntityId entityId,
    Instant observedAt,
    Instant ingestedAt,
    Map<String, Object> fields,
    String rawRecordRef,
    int mappingRevision
) {
    public Observation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(rawRecordRef, "rawRecordRef");
        if (!id.matches("[a-zA-Z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid observation id");
        }
        if (rawRecordRef.isBlank() || rawRecordRef.length() > 256) {
            throw new IllegalArgumentException("Invalid rawRecordRef");
        }
        if (mappingRevision < 1) {
            throw new IllegalArgumentException("Invalid mappingRevision");
        }
        fields = freezeMap(fields, 0);
    }
    private static Map<String, Object> freezeMap(Map<?, ?> value, int depth) {
        if (depth > 8 || value.size() > 256) throw new IllegalArgumentException("Observation fields exceed structure budget");
        var result = new java.util.LinkedHashMap<String, Object>();
        for (var entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Invalid field key");
            result.put(key, freeze(entry.getValue(), depth + 1));
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value, int depth) {
        if (depth > 8) throw new IllegalArgumentException("Observation fields exceed depth budget");
        if (value instanceof Map<?, ?> map) return freezeMap(map, depth);
        if (value instanceof java.util.List<?> list) {
            if (list.size() > 256) throw new IllegalArgumentException("Observation field list exceeds budget");
            return java.util.Collections.unmodifiableList(list.stream().map(item -> freeze(item, depth + 1)).toList());
        }
        if (value instanceof Number number) return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Unsupported observation value");
    }
    public static void checkWrite(Entity entity, Observation observation, ExternalLink link) {
        if (!entity.tenantId().equals(observation.key().tenantId()) || !entity.id().equals(observation.entityId())
            || !link.key().equals(observation.key()) || !link.entityId().equals(entity.id())
            || !entity.lastSeen().equals(observation.observedAt())) throw new IllegalArgumentException("Observation write scope mismatch");
    }
}
