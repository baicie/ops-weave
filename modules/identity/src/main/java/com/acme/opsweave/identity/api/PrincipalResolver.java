package com.acme.opsweave.identity.api;

import com.acme.opsweave.identity.domain.Principal;
import java.util.Optional;

public interface PrincipalResolver {
    Optional<Principal> resolve(BearerCredentials credentials);
}
