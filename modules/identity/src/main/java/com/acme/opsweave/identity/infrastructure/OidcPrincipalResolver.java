package com.acme.opsweave.identity.infrastructure;

import com.acme.opsweave.identity.api.BearerCredentials;
import com.acme.opsweave.identity.api.PrincipalResolver;
import com.acme.opsweave.identity.domain.Principal;
import java.util.Objects;
import java.util.Optional;

/**
 * Production OIDC adapter placeholder. Deliberately refuses to resolve.
 * A missing IdP must not fall back to a mock or demo principal.
 */
public final class OidcPrincipalResolver implements PrincipalResolver {
    @Override
    public Optional<Principal> resolve(BearerCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        throw new UnsupportedOperationException(
            "OIDC principal adapter is not implemented; refusing mock fallback"
        );
    }
}
