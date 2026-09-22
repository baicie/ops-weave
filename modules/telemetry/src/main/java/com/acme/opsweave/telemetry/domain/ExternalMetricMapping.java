package com.acme.opsweave.telemetry.domain;

import java.util.Objects;

/** Link from a platform metric definition back to one source item. Not a sample. */
public record ExternalMetricMapping(
    String sourceType,
    String sourceInstanceId,
    String externalId,
    String itemKey,
    String hostExternalId,
    String sourceUnit,
    String valueTransform,
    int mappingRevision
) {
    public ExternalMetricMapping {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(hostExternalId, "hostExternalId");
        Objects.requireNonNull(sourceUnit, "sourceUnit");
        Objects.requireNonNull(valueTransform, "valueTransform");
        if (externalId.isBlank() || itemKey.isBlank() || hostExternalId.isBlank()) {
            throw new IllegalArgumentException("External metric mapping requires item, key, and host");
        }
        if (mappingRevision < 1) {
            throw new IllegalArgumentException("Mapping revision must start at 1");
        }
    }
}
