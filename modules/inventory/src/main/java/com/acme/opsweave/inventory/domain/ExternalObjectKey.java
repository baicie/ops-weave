package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Objects;

/** Identity is scoped to source instance and lifecycle, not merely to hostname/IP. */
public record ExternalObjectKey(
    TenantId tenantId, String sourceInstanceId, String externalType,
    String externalId, String generation
) {
    public ExternalObjectKey {
        Objects.requireNonNull(tenantId, "tenantId");
        required(sourceInstanceId, "sourceInstanceId");
        required(externalType, "externalType");
        required(externalId, "externalId");
        required(generation, "generation");
    }
    private static void required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }
}
