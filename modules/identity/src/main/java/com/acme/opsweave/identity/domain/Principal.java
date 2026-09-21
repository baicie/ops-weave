package com.acme.opsweave.identity.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Trusted subject constructed at the authentication boundary.
 * Callers must not assemble this from request body or query fields.
 */
public record Principal(
    SubjectId subjectId,
    TenantId tenantId,
    Set<Permission> permissions,
    ResourceScope resourceScope
) {
    public Principal {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(resourceScope, "resourceScope");
        permissions = Set.copyOf(permissions);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public Set<String> permissionWireValues() {
        return permissions.stream().map(Permission::wireValue).collect(Collectors.toUnmodifiableSet());
    }
}
