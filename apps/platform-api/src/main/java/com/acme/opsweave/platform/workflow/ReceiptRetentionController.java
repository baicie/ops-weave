package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.ReceiptRetentionService;
import com.acme.opsweave.integration.domain.ReceiptRetention;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

/**
 * The receipt retention budget and its explicit cleanup. The budget is a read, the preview is a read
 * and only the confirm changes anything; nothing here is scheduled and reading never deletes.
 */
@RestController
@RequestMapping("/api/v1/integrations/receipts/retention")
public class ReceiptRetentionController {
    private final PrincipalContext principals;
    private final InventoryWiring wiring;

    public ReceiptRetentionController(PrincipalContext principals, InventoryWiring wiring) {
        this.principals = principals;
        this.wiring = wiring;
    }

    /**
     * Resolved per request: the store accessor refuses the memory build explicitly, so the refusal is
     * the request's failure — never a startup failure of the whole application context.
     */
    private ReceiptRetentionService service() {
        return new ReceiptRetentionService(wiring.receiptRetention(),
            ReceiptRetention.Policy.defaults(), Clock.systemUTC());
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
    }

    private static UUID uuid(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }

    @GetMapping
    public Object budgets(HttpServletRequest request) {
        noQuery(request);
        return ReceiptRetentionJson.envelope(service().reports(principals.requirePrincipal()));
    }

    /** Read-only: which rows a confirm would remove and how many stay protected. */
    @PostMapping(value = "/preview", consumes = "application/json")
    public Object preview(HttpServletRequest request) throws IOException {
        noQuery(request);
        var n = CatalogJson.read(request);
        com.acme.opsweave.platform.integration.PipelineJson.fields(n, java.util.Set.of("kind", "scopeKey", "maxRemove"));
        return ReceiptRetentionJson.wire(service().preview(principals.requirePrincipal(),
            ReceiptRetention.Kind.of(com.acme.opsweave.platform.integration.PipelineJson.text(n, "kind")),
            com.acme.opsweave.platform.integration.PipelineJson.text(n, "scopeKey"),
            com.acme.opsweave.platform.integration.PipelineJson.integer(n, "maxRemove", null)));
    }

    @PostMapping(value = "/confirm", consumes = "application/json")
    public Object confirm(HttpServletRequest request) throws IOException {
        noQuery(request);
        var command = ReceiptRetentionJson.command(CatalogJson.read(request));
        return ReceiptRetentionJson.wire(service().confirm(principals.requirePrincipal(), command).receipt());
    }

    @GetMapping("/commands/{requestId}")
    public Object command(@PathVariable String requestId, HttpServletRequest request) {
        noQuery(request);
        return ReceiptRetentionJson.wire(service().receipt(principals.requirePrincipal(), uuid(requestId)));
    }
}
