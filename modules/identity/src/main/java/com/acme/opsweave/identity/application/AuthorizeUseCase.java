package com.acme.opsweave.identity.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.AuthorizationDecision;
import com.acme.opsweave.identity.domain.Authorizer;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;

public final class AuthorizeUseCase implements AuthorizationService {
    private final Authorizer authorizer = new Authorizer();

    @Override
    public AuthorizationDecision authorize(Principal principal, ResourceRef resource, Permission action) {
        return authorizer.decide(principal, resource, action);
    }
}
