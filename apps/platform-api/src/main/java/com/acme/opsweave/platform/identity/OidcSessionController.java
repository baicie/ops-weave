package com.acme.opsweave.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="opsweave.auth.mode", havingValue="oidc")
public final class OidcSessionController {
    private final OidcSessions sessions;
    private final OidcSettings settings;
    public OidcSessionController(OidcSessions sessions, OidcSettings settings) { this.sessions = sessions; this.settings = settings; }
    @GetMapping("/api/v1/auth/session")
    public Object current(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        var identity = sessions.identity(request.getSession(false));
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        var result = new LinkedHashMap<String,Object>(); result.put("schemaVersion", "1.0"); result.put("mode", "oidc");
        result.put("dataMode", settings.loopbackTest() ? "oidc-protocol-test" : "oidc"); result.put("authenticated", identity != null);
        result.put("csrfToken", csrf.getToken()); result.put("loginPath", "/api/v1/auth/login/opsweave");
        result.put("sessionId", identity == null ? null : identity.id()); result.put("expiresAt", identity == null ? null : identity.expiresAt().toString());
        if (identity == null) result.put("principal", null);
        else { var p = sessions.resolve(identity); result.put("principal", Map.of("subjectId", p.subjectId().value(), "tenantId", p.tenantId().value(), "permissions", p.permissionWireValues().stream().sorted().toList())); }
        return result;
    }
}
