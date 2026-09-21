package com.acme.opsweave.identity.api;

import com.acme.opsweave.identity.domain.AuthorizationDecision;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;

public interface AuthorizationService {
    AuthorizationDecision authorize(Principal principal, ResourceRef resource, Permission action);
}
