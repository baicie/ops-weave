package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.WorkflowCollectionBindingService;
import com.acme.opsweave.integration.domain.WorkflowCollectionBinding;
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
import java.util.Map;
import java.util.UUID;

/**
 * Explicit enable/disable of the collection binding between one source instance and one published
 * workflow version. Publishing never lands here: these commands are separate, reasoned, idempotent
 * and only they can make a version eligible for controlled batch collection. Nothing on this
 * controller starts a task, reads a source, writes an entity or calls a model.
 */
@RestController
@RequestMapping("/api/v1/integrations/workflows/collection-bindings")
public class WorkflowCollectionBindingController {
    private final PrincipalContext principals;
    private final WorkflowCollectionBindingService service;

    public WorkflowCollectionBindingController(PrincipalContext principals, InventoryWiring wiring) {
        this.principals = principals;
        this.service = new WorkflowCollectionBindingService(wiring.workflows(), Clock.systemUTC());
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
    }

    private static UUID uuid(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }

    @PostMapping(value = "/enable", consumes = "application/json")
    public Object enable(HttpServletRequest request) throws IOException {
        noQuery(request);
        return submit(request, WorkflowCollectionBinding.Operation.ENABLE);
    }

    @PostMapping(value = "/disable", consumes = "application/json")
    public Object disable(HttpServletRequest request) throws IOException {
        noQuery(request);
        return submit(request, WorkflowCollectionBinding.Operation.DISABLE);
    }

    private Object submit(HttpServletRequest request, WorkflowCollectionBinding.Operation operation) throws IOException {
        var body = CatalogJson.read(request);
        var command = WorkflowCollectionBindingJson.command(body);
        if (command.operation() != operation) throw new IllegalArgumentException();
        var result = service.command(principals.requirePrincipal(), command);
        return WorkflowCollectionBindingJson.wire(result.receipt());
    }

    @GetMapping
    public Object list(HttpServletRequest request) {
        noQuery(request);
        return WorkflowCollectionBindingJson.page(service.bindings(principals.requirePrincipal()));
    }

    @GetMapping("/commands/{requestId}")
    public Object command(@PathVariable String requestId, HttpServletRequest request) {
        noQuery(request);
        return WorkflowCollectionBindingJson.wire(service.receipt(principals.requirePrincipal(), uuid(requestId)));
    }
}
