package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.application.ToolGateway;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.platform.incident.IncidentController;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public final class ToolController {
    private final PrincipalContext principals;
    private final ToolGateway gateway;
    private final ToolExecutor executor;
    private final String storage;
    public ToolController(PrincipalContext principals, ToolGateway gateway, ToolExecutor executor, InventoryWiring wiring) { this.principals = principals; this.gateway = gateway; this.executor = executor; storage = wiring.label(); }
    @PostMapping(path="/ai/read-sessions", consumes="application/json")
    public Object create(HttpServletRequest request) {
        var body = IncidentController.object(request, Set.of("incidentId", "timeRange", "knowledgeMode"));
        if (!"current".equals(text(body, "knowledgeMode"))) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        UUID id = IncidentController.uuid(text(body, "incidentId")); ToolWindow window = window(body.get("timeRange"));
        var principal = principals.requirePrincipal(); // Capture trusted identity before switching threads.
        var permissions = principal.permissions().stream().map(com.acme.opsweave.identity.domain.Permission::wireValue).collect(java.util.stream.Collectors.toSet());
        var created = executor.run(() -> gateway.create(principal, id, window));
        com.acme.opsweave.platform.identity.RuntimeDelegations.bindReadSession(request, created.id());
        return ToolJson.session(created, storage, permissions);
    }
    @PostMapping(path="/tools/incident.get/2.0.0", consumes="application/json")
    public Object incident(HttpServletRequest request) {
        var body = IncidentController.object(request, Set.of("incidentId"));
        UUID id = IncidentController.uuid(text(body, "incidentId")), session = session(request); var principal = principals.requirePrincipal();
        return executor.run(() -> ToolJson.result(gateway.incident(principal, session, id)));
    }
    @PostMapping(path="/tools/metric.summary/2.0.0", consumes="application/json")
    public Object metric(HttpServletRequest request) {
        var body = IncidentController.object(request, Set.of("incidentId", "metric", "timeRange", "maxPoints"));
        UUID id = IncidentController.uuid(text(body, "incidentId")), session = session(request); var principal = principals.requirePrincipal();
        var window = window(body.get("timeRange")); String metric = text(body, "metric");
        if (!body.get("maxPoints").isIntegralNumber() || !body.get("maxPoints").canConvertToInt()) throw new IllegalArgumentException();
        int points = body.get("maxPoints").asInt();
        return executor.run(() -> ToolJson.result(gateway.metric(principal, session, id, metric, window, points)));
    }
    @PostMapping(path="/tools/evidence.get/2.0.0", consumes="application/json")
    public Object recheck(HttpServletRequest request) {
        var body = IncidentController.object(request, Set.of("evidenceId"));
        UUID id = IncidentController.uuid(text(body, "evidenceId")), session = session(request); var principal = principals.requirePrincipal();
        return executor.run(() -> ToolJson.result(gateway.recheck(principal, session, id)));
    }
    @GetMapping("/ai/evidence/{evidenceId}")
    public Object evidence(@PathVariable String evidenceId, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        UUID id = IncidentController.uuid(evidenceId); var principal = principals.requirePrincipal();
        return executor.run(() -> ToolJson.document(gateway.getEvidence(principal, id)));
    }
    private static UUID session(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("X-OpsWeave-Read-Session")); if (headers.size() != 1) throw new IllegalArgumentException();
        return IncidentController.uuid(headers.getFirst());
    }
    private static String text(JsonNode n, String field) { if (n == null || n.get(field) == null || !n.get(field).isString()) throw new IllegalArgumentException(); return n.get(field).asString(); }
    private static ToolWindow window(JsonNode n) { if (n == null || !n.isObject() || n.size() != 2) throw new IllegalArgumentException(); return new ToolWindow(Instant.parse(text(n, "from")), Instant.parse(text(n, "to"))); }
}
