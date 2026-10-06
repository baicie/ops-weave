package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.WorkflowMetricReplayService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Clock;

@RestController
@RequestMapping("/api/v1/integrations/workflows/metric-replays")
public final class WorkflowMetricReplayController {
    private final PrincipalContext principals;
    private final WorkflowMetricReplayService service;
    private final WorkflowMetricOutputController output;
    public WorkflowMetricReplayController(PrincipalContext principals, InventoryWiring wiring, WorkflowController workflows,
        SourceConnectionWiring connections, RegisteredHostSourceReader registered, WorkflowMetricOutputController output) {
        this.principals = principals; this.output = output;
        service = new WorkflowMetricReplayService(wiring.workflows(), workflows.service(), (p, source, from, till) -> {
            var c = connections.service().workflowConfiguration(p, source);
            return registered.metricWindow(p, source, c.endpoint().pin(), c.credentialPin(), c.hostGroupIds(), from, till, WorkflowMetricReplay.MAX_POINTS);
        }, output.streamSink(), output.budget(), Clock.systemUTC());
    }
    private Principal principal(HttpServletRequest request) { if (request.getQueryString() != null) throw new IllegalArgumentException(); return principals.requirePrincipal(); }
    private void available() { if (!output.available()) throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE); }
    @PostMapping(value="/plans", consumes="application/json") public Object create(HttpServletRequest request) throws IOException {
        var p = principal(request); available(); return service.create(p, WorkflowMetricReplayJson.command(CatalogJson.read(request).toString()));
    }
    @GetMapping("/plans/{id}") public Object plan(@PathVariable String id, HttpServletRequest request) { return service.plan(principal(request), WorkflowRecoveryJson.canonical(id)); }
    @GetMapping("/plans/{id}/execution") public Object executionForPlan(@PathVariable String id, HttpServletRequest request) {
        var result = service.executionForPlan(principal(request), WorkflowRecoveryJson.canonical(id));
        return java.util.Collections.singletonMap("receipt", result);
    }
    @GetMapping("/workflows/{id}/versions/{revision}/{digest}/plans") public Object plans(@PathVariable String id, @PathVariable int revision, @PathVariable String digest, HttpServletRequest request) {
        return service.plans(principal(request), new WorkflowQuality.Reference(id, revision, digest));
    }
    @PostMapping(value="/execute", consumes="application/json") public Object execute(HttpServletRequest request) throws IOException {
        var p = principal(request); available(); return service.execute(p, WorkflowMetricReplayJson.execute(CatalogJson.read(request).toString()));
    }
    @GetMapping("/commands/{id}") public Object receipt(@PathVariable String id, HttpServletRequest request) { return service.receipt(principal(request), WorkflowRecoveryJson.canonical(id)); }
    @PostMapping("/commands/{id}/verification") public Object verify(@PathVariable String id, HttpServletRequest request) {
        var p = principal(request); available(); if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw new IllegalArgumentException(); return service.verify(p, WorkflowRecoveryJson.canonical(id));
    }
}
