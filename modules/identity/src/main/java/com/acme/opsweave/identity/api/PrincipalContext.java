package com.acme.opsweave.identity.api;

import com.acme.opsweave.identity.domain.Principal;

/** Request-scoped view of the principal constructed by the authentication boundary. */
public interface PrincipalContext {
    Principal requirePrincipal();
}
