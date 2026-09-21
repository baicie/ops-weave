package com.acme.opsweave.identity.domain;

import java.util.Objects;

public final class Authorizer {
    public AuthorizationDecision decide(Principal principal, ResourceRef resource, Permission action) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        if (!principal.tenantId().equals(resource.tenantId())) {
            return AuthorizationDecision.deny("TENANT_MISMATCH");
        }
        if (!principal.has(action)) {
            return AuthorizationDecision.deny("PERMISSION_MISSING");
        }
        if (!principal.resourceScope().includes(resource)) {
            return AuthorizationDecision.deny("SCOPE_EXCLUDED");
        }
        return AuthorizationDecision.allow();
    }
}
