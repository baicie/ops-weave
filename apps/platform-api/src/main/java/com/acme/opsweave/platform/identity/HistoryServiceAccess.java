package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;

/** Dedicated machine-to-machine endpoint; JWT claims cannot supply platform tenant, permissions or resource scope. */
public final class HistoryServiceAccess {
    static final class Rejected extends RuntimeException { final int status; Rejected(int status) { super("History service access denied"); this.status = status; } }
    public final class Permit implements AutoCloseable {
        final Principal principal; private boolean closed;
        Permit(Principal principal) { this.principal = principal; }
        @Override public void close() { if (!closed) { closed = true; concurrent.release(); } }
    }
    private record Counter(Instant start, int calls) {}
    private final HistoryServiceSettings settings;
    private final FileHistoryServiceGrants grants;
    private final String source;
    private final JwtDecoder decoder;
    private final Semaphore concurrent = new Semaphore(4);
    private final Map<String,Counter> rates = new HashMap<>();
    public HistoryServiceAccess(HistoryServiceSettings settings, OpsweaveProperties platform) {
        this.settings = settings; this.grants = new FileHistoryServiceGrants(settings); this.source = platform.zabbix().sourceInstanceId();
        var rest = new RestTemplate(OidcHttp.factory()); rest.setInterceptors(List.of(OidcHttp.boundary(Set.of(URI.create(settings.jwkSetUri())))));
        var jwt = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri()).jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(rest)
            .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt"), new JOSEObjectType("application/at+jwt")))).build();
        jwt.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO), token -> {
            var now = Instant.now(); var iat = token.getIssuedAt(); var exp = token.getExpiresAt();
            boolean valid = settings.issuer().equals(token.getClaimAsString("iss")) && token.getAudience().equals(List.of(HistoryServiceSettings.AUDIENCE))
                && token.getClaims().get("sub") instanceof String sub && !sub.isBlank() && sub.length() <= 255
                && token.getClaims().get("client_id") instanceof String client && client.matches("[A-Za-z0-9._-]{1,128}")
                && token.getClaims().get("jti") instanceof String jti && !jti.isBlank() && jti.length() <= 128 && jti.chars().noneMatch(Character::isISOControl)
                && HistoryServiceSettings.SCOPE.equals(token.getClaims().get("scope"))
                && iat != null && exp != null && !iat.isAfter(now) && now.isBefore(exp) && iat.isBefore(exp) && Duration.between(iat, exp).compareTo(Duration.ofSeconds(900)) <= 0;
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }));
        decoder = jwt;
    }
    public Permit authorize(HttpServletRequest request) {
        if (!concurrent.tryAcquire()) throw new Rejected(429);
        try {
            boolean transport = settings.loopbackTest() ? Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()) : request.isSecure();
            var auth = Collections.list(request.getHeaders("Authorization"));
            if (!transport || request.getHeader("Cookie") != null || request.getHeader("Origin") != null || request.getHeader("Forwarded") != null
                || request.getHeader("X-Forwarded-Proto") != null || auth.size() != 1 || auth.getFirst().length() > 8199
                || !auth.getFirst().matches("Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw new Rejected(401);
            if (ClientIdentityOverride.present(request)) throw new Rejected(400);
            if (!request.getMethod().equals("GET") || !request.getRequestURI().matches("/api/v1/service/ingestion/items/[1-9][0-9]{0,19}/history")
                || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw new Rejected(403);
            var query = request.getParameterMap();
            if (!Set.of("from", "till", "limit", "afterClock", "afterNs").containsAll(query.keySet()) || !query.keySet().containsAll(Set.of("from", "till", "limit"))
                || query.values().stream().anyMatch(v -> v.length != 1 || !v[0].matches("[0-9]{1,10}")) || query.containsKey("afterClock") != query.containsKey("afterNs")) throw new Rejected(400);
            long from = Long.parseLong(request.getParameter("from")), till = Long.parseLong(request.getParameter("till")); int limit = Math.toIntExact(Long.parseLong(request.getParameter("limit")));
            if (query.containsKey("afterClock")) new com.acme.opsweave.integration.domain.HistoryWindow(from, till,
                new com.acme.opsweave.integration.domain.HistoryCursor(Long.parseLong(request.getParameter("afterClock")), Math.toIntExact(Long.parseLong(request.getParameter("afterNs")))), limit);
            else new com.acme.opsweave.integration.domain.HistoryWindow(from, till, null, limit);
            final Jwt token;
            try { token = decoder.decode(auth.getFirst().substring(7)); } catch (RuntimeException invalid) { throw new Rejected(401); }
            String client = token.getClaimAsString("client_id"); final com.acme.opsweave.integration.domain.HistoryServiceGrant grant;
            try { grant = grants.find(client); } catch (IllegalStateException unavailable) { throw new Rejected(503); } catch (IllegalArgumentException missing) { throw new Rejected(403); }
            String item = request.getRequestURI().split("/")[6]; var now = Instant.now(); final Principal principal;
            try { principal = grant.authorize(token.getClaimAsString("iss"), client, token.getSubject(), token.getExpiresAt(), now, source, item, from, till, limit); }
            catch (IllegalArgumentException denied) { throw new Rejected(403); }
            synchronized (rates) {
                rates.values().removeIf(counter -> !now.isBefore(counter.start().plusSeconds(60)));
                var previous = rates.get(client);
                if (previous == null) { if (rates.size() >= 500) throw new Rejected(429); rates.put(client, new Counter(now, 1)); }
                else { if (previous.calls() >= grant.requestsPerMinute()) throw new Rejected(429); rates.put(client, new Counter(previous.start(), previous.calls() + 1)); }
            }
            return new Permit(principal);
        } catch (RuntimeException failure) { concurrent.release(); if (failure instanceof Rejected rejected) throw rejected; throw new Rejected(400); }
    }
}
