package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.api.BearerCredentials;
import com.acme.opsweave.identity.api.PrincipalResolver;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.platform.OpsweaveProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class TrustedPrincipalFilter extends OncePerRequestFilter {
    private final PrincipalResolver resolver;
    private final OpsweaveProperties properties;

    public TrustedPrincipalFilter(PrincipalResolver resolver, OpsweaveProperties properties) {
        this.resolver = resolver;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (ClientIdentityOverride.present(request)) {
            write(response, HttpServletResponse.SC_BAD_REQUEST, "identity_override_rejected");
            return;
        }
        Optional<String> bearer = bearerToken(request);
        if (bearer.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (properties.auth().bindLoopbackOnly() && !loopback(request)) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated");
            return;
        }
        Optional<Principal> principal;
        try {
            principal = resolver.resolve(new BearerCredentials(bearer.get()));
        } catch (RuntimeException failed) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated");
            return;
        }
        if (principal.isEmpty()) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated");
            return;
        }
        ClientIdentityOverride.attach(request, principal.get());
        var authentication = new PrincipalAuthenticationToken(principal.get());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    private static boolean loopback(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        if (addr == null) {
            return false;
        }
        try {
            return InetAddress.getByName(addr).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void write(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
