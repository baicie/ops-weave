package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.domain.Principal;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.UUID;

/** Single-platform-process session. No provider access/refresh/ID token is retained here. */
public final class OidcSessions {
    static final String ATTR = "opsweave.oidc.session";
    public record Identity(String id, String issuer, String subject, String grantDigest, Instant expiresAt) {}
    private final FileIdentityGrants grants;
    private final OidcSettings settings;
    public OidcSessions(FileIdentityGrants grants, OidcSettings settings) { this.grants = grants; this.settings = settings; }
    public Identity establish(HttpServletRequest request, String issuer, String subject, Instant idExpires) {
        var now = Instant.now(); var grant = grants.find(subject); grant.grant().bind(issuer, subject, idExpires, now);
        Instant deadline = now.plusSeconds(settings.sessionSeconds()); if (idExpires.isBefore(deadline)) deadline = idExpires;
        var identity = new Identity(UUID.randomUUID().toString(), issuer, subject, grant.digest(), deadline);
        request.getSession().setAttribute(ATTR, identity); request.getSession().setMaxInactiveInterval(settings.sessionSeconds());
        return identity;
    }
    public Identity identity(HttpSession session) { return session == null ? null : (Identity) session.getAttribute(ATTR); }
    public Principal resolve(HttpSession session) {
        var identity = identity(session); if (identity == null) throw new IllegalArgumentException("Session unavailable");
        return resolve(identity);
    }
    public Principal resolve(Identity identity) {
        var grant = grants.find(identity.subject());
        if (!grant.digest().equals(identity.grantDigest())) throw new IllegalArgumentException("Session authorization changed");
        return grant.grant().bind(identity.issuer(), identity.subject(), identity.expiresAt(), Instant.now());
    }
    public void clear(HttpServletRequest request) { var session = request.getSession(false); if (session != null) session.invalidate(); }
}
