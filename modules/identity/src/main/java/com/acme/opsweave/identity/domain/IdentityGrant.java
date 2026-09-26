package com.acme.opsweave.identity.domain;

import java.time.Instant;
import java.util.Objects;

/** Operator-owned mapping. OIDC verifies issuer/subject, never supplies platform authorization. */
public record IdentityGrant(String issuer, String externalSubject, long revision, boolean enabled, Principal principal) {
    public IdentityGrant {
        Objects.requireNonNull(issuer); Objects.requireNonNull(externalSubject); Objects.requireNonNull(principal);
        if (issuer.isBlank() || issuer.length() > 2048 || externalSubject.isBlank() || externalSubject.length() > 255
            || externalSubject.codePoints().anyMatch(Character::isISOControl) || revision < 1) throw new IllegalArgumentException("Invalid identity grant");
    }
    public Principal bind(String verifiedIssuer, String verifiedSubject, Instant expiresAt, Instant now) {
        if (!enabled || !issuer.equals(verifiedIssuer) || !externalSubject.equals(verifiedSubject)
            || expiresAt == null || !now.isBefore(expiresAt)) throw new IllegalArgumentException("Identity is not authorized");
        return principal;
    }
}
