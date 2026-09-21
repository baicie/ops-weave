package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.domain.Principal;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class PrincipalAuthenticationToken extends AbstractAuthenticationToken {
    private final Principal principal;

    public PrincipalAuthenticationToken(Principal principal) {
        super(authorities(principal));
        this.principal = principal;
        super.setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Principal getPrincipal() {
        return principal;
    }

    private static Collection<? extends GrantedAuthority> authorities(Principal principal) {
        return principal.permissions().stream()
            .map(permission -> new SimpleGrantedAuthority("PERM_" + permission.wireValue()))
            .toList();
    }
}
