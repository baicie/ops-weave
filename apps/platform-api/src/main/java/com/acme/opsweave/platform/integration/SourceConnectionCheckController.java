package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.SourceConnectionCheckService;
import com.acme.opsweave.integration.domain.SourceConnectionCheck;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only source self-check. It never starts a scan, never acquires a lease and never repairs or
 * reconciles anything: it records what one bounded probe reported, including an unreachable source.
 */
@RestController
public final class SourceConnectionCheckController {
    private static final Set<String> QUERY = Set.of("limit");

    private final PrincipalContext principals;
    private final SourceConnectionCheckService checks;
    private final InventoryWiring wiring;
    private final String source;

    public SourceConnectionCheckController(
        PrincipalContext principals,
        SourceConnectionCheckService checks,
        InventoryWiring wiring,
        OpsweaveProperties properties
    ) {
        this.principals = principals;
        this.checks = checks;
        this.wiring = wiring;
        this.source = properties.zabbix().sourceInstanceId();
    }

    @PostMapping("/api/v1/integrations/zabbix/connection-checks")
    public Map<String, Object> check(HttpServletRequest request) {
        query(request, Set.of());
        Principal principal = principals.requirePrincipal();
        Map<String, Object> body = envelope(principal);
        body.put("check", item(checks.check(principal)));
        return body;
    }

    @GetMapping("/api/v1/integrations/zabbix/connection-checks")
    public Map<String, Object> recent(HttpServletRequest request) {
        query(request, QUERY);
        Principal principal = principals.requirePrincipal();
        int limit = request.getParameter("limit") == null
            ? SourceConnectionCheckService.DEFAULT_LIMIT
            : Integer.parseInt(request.getParameter("limit"));
        Map<String, Object> body = envelope(principal);
        body.put("limit", limit);
        body.put("items", checks.recent(principal, limit).stream().map(this::item).toList());
        return body;
    }

    private Map<String, Object> envelope(Principal principal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "1.0");
        body.put("storage", wiring.label());
        body.put("dataMode", "connection-check");
        body.put("tenantId", principal.tenantId().value());
        body.put("sourceInstanceId", source);
        return body;
    }

    private Map<String, Object> item(SourceConnectionCheck check) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("checkId", check.id().toString());
        item.put("sourceInstanceId", check.sourceInstanceId());
        item.put("actor", check.actor());
        item.put("checkedAt", check.checkedAt().toString());
        item.put("dataMode", check.dataMode());
        item.put("reachable", check.reachable());
        item.put("statusCode", check.statusCode());
        item.put("reportedVersion", check.reportedVersion());
        return item;
    }

    /** Only the documented read parameters are accepted; anything else is a client error. */
    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((name, values) -> {
            if (!allowed.contains(name) || values.length != 1) {
                throw new IllegalArgumentException("Unexpected query");
            }
        });
    }
}
