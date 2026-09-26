package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.inventory.application.SourceReceiptCapacityService;
import com.acme.opsweave.inventory.application.SourceReviewService;
import com.acme.opsweave.inventory.domain.SourceReceiptCapacity;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How full the stored receipts of the configured import source are. The caps fail closed, so this
 * view exists to make the ceiling visible before a write is refused. It counts only: nothing is
 * pruned, no cap is raised, and no import or correction is replayed.
 */
@RestController
public final class SourceReceiptCapacityController {
    private final PrincipalContext principals;
    private final AuthorizationService authorization;
    private final InventoryWiring wiring;
    private final String source;

    public SourceReceiptCapacityController(
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

    @GetMapping("/api/v1/integrations/cmdb/receipt-capacity")
    public Map<String, Object> capacity(HttpServletRequest request) {
        request.getParameterMap().forEach((name, values) -> {
            throw new IllegalArgumentException("Unexpected query");
        });
        Principal principal = principals.requirePrincipal();
        if (source == null || source.isBlank()) {
            throw new SourceReviewService.Access(503);
        }
        var store = wiring.receiptCapacity();
        var service = new SourceReceiptCapacityService(authorization, store, source);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "1.0");
        body.put("storage", wiring.label());
        body.put("dataMode", "import");
        body.put("tenantId", principal.tenantId().value());
        body.put("sourceInstanceId", source);
        body.put("items", service.capacity(principal).stream().map(this::item).toList());
        return body;
    }

    private Map<String, Object> item(SourceReceiptCapacity capacity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", capacity.kind().label());
        item.put("kept", capacity.kept());
        item.put("max", capacity.max());
        item.put("status", capacity.status().name());
        item.put("summary", capacity.summary());
        return item;
    }
}
