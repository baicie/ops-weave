package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ServletPrincipalContext implements PrincipalContext {
    private final HttpServletRequest request;

    public ServletPrincipalContext(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public Principal requirePrincipal() {
        return ClientIdentityOverride.read(request);
    }
}
