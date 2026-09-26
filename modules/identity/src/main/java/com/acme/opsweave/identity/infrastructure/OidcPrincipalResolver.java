package com.acme.opsweave.identity.infrastructure;

import com.acme.opsweave.identity.api.BearerCredentials;
import com.acme.opsweave.identity.api.PrincipalResolver;
import com.acme.opsweave.identity.domain.Principal;
import java.util.Objects;
import java.util.Optional;

/**
 * Raw bearer OIDC boundary stays closed. Authorization-code verification and
 * operator grant mapping live in the platform BFF adapter; no mock fallback.
 */
public final class OidcPrincipalResolver implements PrincipalResolver {
    @Override
    public Optional<Principal> resolve(BearerCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        throw new UnsupportedOperationException(
            "Raw OIDC bearer resolution is disabled; use the verified platform BFF session"
        );
    }
}
