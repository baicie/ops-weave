package com.acme.opsweave.identity.infrastructure;

import com.acme.opsweave.identity.api.BearerCredentials;
import com.acme.opsweave.identity.api.PrincipalResolver;
import com.acme.opsweave.identity.domain.Principal;
import java.util.Objects;
import java.util.Optional;

/** Default fail-closed resolver. Health may still be public; business APIs stay unauthenticated. */
public final class ClosedPrincipalResolver implements PrincipalResolver {
    @Override
    public Optional<Principal> resolve(BearerCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        return Optional.empty();
    }
}
