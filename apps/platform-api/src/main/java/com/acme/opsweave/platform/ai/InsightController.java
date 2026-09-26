package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.application.AiInsightService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.platform.incident.IncidentController;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/insights")
public final class InsightController {
    private final AiInsightService service;
    private final PrincipalContext principal;
    private final RuntimeOrigin origin;
    private final ToolExecutor executor;
    private final String storage;
    private final com.acme.opsweave.aicontrol.application.ModelSpendService spend;
    public InsightController(AiInsightService service, PrincipalContext principal, RuntimeOrigin origin, ToolExecutor executor, InventoryWiring wiring, com.acme.opsweave.aicontrol.application.ModelSpendService spend) {
        this.service = service; this.principal = principal; this.origin = origin; this.executor = executor; storage = wiring.label(); this.spend = spend;
    }
    @PostMapping(consumes="application/json")
    public Object submit(HttpServletRequest request) {
        origin.require(request); var input = InsightJson.request(request); origin.validate(input); var p = principal.requirePrincipal();
        spend.requireReport(p,input);
        return executor.run(() -> Map.of("storage", storage, "record", InsightJson.body(service.submit(p, input))));
    }
    @GetMapping("/{runId}")
    public Object get(@PathVariable String runId, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        var id = IncidentController.uuid(runId); var p = principal.requirePrincipal();
        return executor.run(() -> Map.of("storage", storage, "record", InsightJson.body(service.get(p, id))));
    }
}
