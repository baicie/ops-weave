package com.acme.opsweave.platform.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Browser cookie boundary, before CSRF and OAuth filters. Never trusts forwarding/identity headers. */
public final class OidcBoundaryFilter extends OncePerRequestFilter {
    private final OidcSettings settings;
    private final OidcSessions sessions;
    private final RuntimeDelegations delegations;
    public OidcBoundaryFilter(OidcSettings settings, OidcSessions sessions, RuntimeDelegations delegations) { this.settings = settings; this.sessions = sessions; this.delegations = delegations; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (Boolean.TRUE.equals(request.getAttribute(HistoryServiceFilter.AUTHENTICATED))) { chain.doFilter(request, response); return; }
        if (!path.startsWith("/api/")) { chain.doFilter(request, response); return; }
        if (ClientIdentityOverride.present(request)) { TrustedPrincipalFilter.write(response, 400, "identity_override_rejected"); return; }
        if (request.getHeader("Authorization") != null) {
            try { chain.doFilter(delegations.authorize(request), response); }
            catch (RuntimeDelegations.Denied denied) { TrustedPrincipalFilter.write(response, 401, "unauthenticated"); }
            return;
        }
        boolean callback = path.equals("/api/v1/auth/callback");
        if (Collections.list(request.getHeaders("Origin")).size() > 1 || (!callback && ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))
            || (request.getHeader("Origin") != null && !settings.publicOrigin().equals(request.getHeader("Origin")))
            || (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()) && !settings.publicOrigin().equals(request.getHeader("Origin")))))) {
            TrustedPrincipalFilter.write(response, 403, "forbidden"); return;
        }
        if (callback) {
            var session = request.getSession(false); var started = session == null ? null : session.getAttribute("opsweave.login.started");
            if (started == null) { TrustedPrincipalFilter.write(response, 401, "unauthenticated"); return; }
            if (!"GET".equals(request.getMethod()) || !(started instanceof Instant time) || !Instant.now().isBefore(time.plusSeconds(180))
                || request.getParameterMap().entrySet().stream().anyMatch(e -> !Set.of("state", "code", "error", "error_description", "iss", "session_state").contains(e.getKey()) || e.getValue().length != 1 || e.getValue()[0].length() > 4096)
                || (request.getParameter("iss") != null && !settings.issuer().equals(request.getParameter("iss")))) {
                sessions.clear(request); TrustedPrincipalFilter.write(response, 401, "unauthenticated"); return;
            }
            session.removeAttribute("opsweave.login.started");
        }
        if (path.equals("/api/v1/auth/login/opsweave")) {
            if (!request.getParameterMap().isEmpty() || !request.getMethod().equals("GET")) { TrustedPrincipalFilter.write(response, 400, "invalid_request"); return; }
            request.getSession().removeAttribute(OidcSessions.ATTR);
            request.getSession().setAttribute("opsweave.login.started", Instant.now()); request.getSession().setMaxInactiveInterval(180);
        }
        if (path.equals("/api/v1/auth/session") && !request.getParameterMap().isEmpty()) { TrustedPrincipalFilter.write(response, 400, "invalid_request"); return; }
        var session = request.getSession(false);
        if (session != null && sessions.identity(session) != null) {
            try {
                var principal = sessions.resolve(session); ClientIdentityOverride.attach(request, principal);
                SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthenticationToken(principal));
            } catch (RuntimeException denied) {
                sessions.clear(request); SecurityContextHolder.clearContext();
                TrustedPrincipalFilter.write(response, 401, "unauthenticated"); return;
            }
        }
        chain.doFilter(request, response);
    }
}
