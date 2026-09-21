package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.domain.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

public final class ClientIdentityOverride {
    public static final String ATTR = "opsweave.principal";
    private static final Set<String> QUERY = Set.of(
        "tenantid", "tenant_id", "userid", "user_id", "subjectid", "permissions", "principal"
    );
    private static final Set<String> HEADERS = Set.of(
        "x-tenant-id", "x-user-id", "x-subject-id", "x-permissions"
    );

    private ClientIdentityOverride() {}

    public static boolean present(HttpServletRequest request) {
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            if (QUERY.contains(names.nextElement().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        Enumeration<String> headers = request.getHeaderNames();
        while (headers.hasMoreElements()) {
            if (HEADERS.contains(headers.nextElement().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static void attach(HttpServletRequest request, Principal principal) {
        request.setAttribute(ATTR, principal);
    }

    public static Principal read(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR);
        if (value instanceof Principal principal) {
            return principal;
        }
        throw new IllegalStateException("Trusted principal is not attached to the request");
    }
}
