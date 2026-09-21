package com.acme.opsweave.identity.domain;

import java.util.Objects;
import java.util.Set;

/** Tenant-wide or an explicit allow-list. Not an ABAC language. */
public final class ResourceScope {
    private final boolean tenantWide;
    private final Set<ResourceRef> allowed;

    private ResourceScope(boolean tenantWide, Set<ResourceRef> allowed) {
        this.tenantWide = tenantWide;
        this.allowed = allowed;
    }

    public static ResourceScope tenantWide() {
        return new ResourceScope(true, Set.of());
    }

    public static ResourceScope of(Set<ResourceRef> refs) {
        Objects.requireNonNull(refs, "refs");
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("Explicit resource scope must name at least one resource");
        }
        return new ResourceScope(false, Set.copyOf(refs));
    }

    public boolean isTenantWide() {
        return tenantWide;
    }

    public boolean includes(ResourceRef resource) {
        Objects.requireNonNull(resource, "resource");
        if (tenantWide) {
            return true;
        }
        if (allowed.contains(resource)) {
            return true;
        }
        ResourceRef wildcard = new ResourceRef(resource.tenantId(), resource.type(), "*");
        return allowed.contains(wildcard);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResourceScope that)) {
            return false;
        }
        return tenantWide == that.tenantWide && allowed.equals(that.allowed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantWide, allowed);
    }
}
