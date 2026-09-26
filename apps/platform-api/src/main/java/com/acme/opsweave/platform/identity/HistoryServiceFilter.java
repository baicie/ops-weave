package com.acme.opsweave.platform.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before both OIDC and dev authentication. Service routes never accept a browser or dev identity. */
public final class HistoryServiceFilter extends OncePerRequestFilter {
    public static final String AUTHENTICATED = "opsweave.history.service.authenticated";
    private final ObjectProvider<HistoryServiceAccess> access;
    public HistoryServiceFilter(ObjectProvider<HistoryServiceAccess> access) { this.access = access; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/v1/service/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var boundary = access.getIfAvailable();
        if (boundary == null) { TrustedPrincipalFilter.write(response, 401, "unauthenticated"); return; }
        try (var permit = boundary.authorize(request)) {
            ClientIdentityOverride.attach(request, permit.principal); request.setAttribute(AUTHENTICATED, Boolean.TRUE);
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthenticationToken(permit.principal)); chain.doFilter(request, response);
        } catch (HistoryServiceAccess.Rejected rejected) {
            String code = switch (rejected.status) { case 400 -> "invalid_request"; case 401 -> "unauthenticated"; case 403 -> "forbidden"; case 429 -> "SERVICE_BUDGET_EXHAUSTED"; default -> "SERVICE_UNAVAILABLE"; };
            TrustedPrincipalFilter.write(response, rejected.status, code);
        }
    }
}
