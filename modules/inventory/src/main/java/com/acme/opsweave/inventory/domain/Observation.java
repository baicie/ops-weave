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
        if (id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Invalid observation id");
        }
        if (rawRecordRef.isBlank() || rawRecordRef.length() > 256) {
            throw new IllegalArgumentException("Invalid rawRecordRef");
        }
        if (mappingRevision < 1) {
            throw new IllegalArgumentException("Invalid mappingRevision");
        }
        fields = Map.copyOf(fields);
    }
}
