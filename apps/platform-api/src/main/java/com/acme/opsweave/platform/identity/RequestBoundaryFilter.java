package com.acme.opsweave.platform.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlation metadata only; never a principal, permission or idempotency key. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestBoundaryFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-OpsWeave-Request-Id";
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        var values = Collections.list(request.getHeaders(HEADER));
        if (values.size() > 1 || (!values.isEmpty() && !values.getFirst().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) {
            response.setHeader(HEADER, UUID.randomUUID().toString());
            TrustedPrincipalFilter.write(response, 400, "invalid_request_id"); return;
        }
        String id = values.isEmpty() ? UUID.randomUUID().toString() : UUID.fromString(values.getFirst()).toString();
        response.setHeader(HEADER, id); request.setAttribute(HEADER, id);
        String previous = MDC.get("requestId"); MDC.put("requestId", id);
        try { chain.doFilter(request, response); }
        finally { if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous); }
    }
}
