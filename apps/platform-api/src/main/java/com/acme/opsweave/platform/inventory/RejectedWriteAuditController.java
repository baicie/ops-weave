package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.inventory.application.RejectedWriteAuditService;
import com.acme.opsweave.inventory.application.SourceReviewService;
import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of refused supplemental-source writes. It replays nothing: a refused request stays
 * refused, no write is retried, and the response carries the stable code and the attempted field
 * names only — never a field value or a vendor message.
 */
@RestController
public final class RejectedWriteAuditController {
    private static final Set<String> QUERY = Set.of("limit");

    private final PrincipalContext principals;
    private final AuthorizationService authorization;
    private final InventoryWiring wiring;
    private final String source;

    public RejectedWriteAuditController(
        PrincipalContext principals,
        AuthorizationService authorization,
        InventoryWiring wiring,
        @Value("${opsweave.inventory.cmdb-import-source:}") String source
    ) {
        this.principals = principals;
        this.authorization = authorization;
        this.wiring = wiring;
        this.source = source;
    }

    @GetMapping("/api/v1/integrations/cmdb/rejected-writes")
    public Map<String, Object> recent(HttpServletRequest request) {
        request.getParameterMap().forEach((name, values) -> {
            if (!QUERY.contains(name) || values.length != 1) {
                throw new IllegalArgumentException("Unexpected query");
            }
        });
        Principal principal = principals.requirePrincipal();
        if (source == null || source.isBlank()) {
            throw new SourceReviewService.Access(503);
        }
        int limit = request.getParameter("limit") == null
            ? RejectedWriteAuditService.DEFAULT_LIMIT
            : Integer.parseInt(request.getParameter("limit"));
        var service = new RejectedWriteAuditService(authorization, wiring.rejectedWrites(), source);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "1.0");
        body.put("storage", wiring.label());
        body.put("dataMode", "import");
        body.put("tenantId", principal.tenantId().value());
        body.put("sourceInstanceId", source);
        body.put("limit", limit);
        body.put("items", service.recent(principal, limit).stream().map(this::item).toList());
        return body;
    }

    private Map<String, Object> item(RejectedWriteAttempt attempt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("attemptId", attempt.id().toString());
        item.put("kind", attempt.kind().name());
        item.put("method", attempt.method().label());
        item.put("reasonCode", attempt.code().name());
        item.put("reasonSummary", attempt.code().summary());
        item.put("actor", attempt.actor());
        item.put("fieldNames", attempt.fieldNames());
        item.put("attemptedAt", attempt.attemptedAt().toString());
        return item;
    }
}
