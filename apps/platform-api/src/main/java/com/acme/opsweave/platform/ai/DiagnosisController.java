package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.application.AiInsightService;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.platform.incident.IncidentController;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Same-origin entry point. OIDC uses a bounded internal delegation; Java re-reads its saved result. */
@RestController
@RequestMapping("/api/v1/ai/diagnoses")
public final class DiagnosisController {
    private final PrincipalContext principals;
    private final AuthorizationService authorization;
    private final IncidentService incidents;
    private final AiInsightService insights;
    private final RuntimeDispatcher runtime;
    private final ToolExecutor reads;
    private final String storage;
    private final org.springframework.beans.factory.ObjectProvider<com.acme.opsweave.platform.identity.RuntimeDelegations> delegations;
    public DiagnosisController(PrincipalContext principals, AuthorizationService authorization, IncidentService incidents,
            AiInsightService insights, RuntimeDispatcher runtime, ToolExecutor reads, InventoryWiring wiring,
            org.springframework.beans.factory.ObjectProvider<com.acme.opsweave.platform.identity.RuntimeDelegations> delegations) {
        this.principals = principals; this.authorization = authorization; this.incidents = incidents; this.insights = insights;
        this.runtime = runtime; this.reads = reads; storage = wiring.label(); this.delegations = delegations;
    }
    @PostMapping(consumes="application/json")
    public Object diagnose(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        final tools.jackson.databind.JsonNode body;
        try {
            byte[] bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length == 0 || bytes.length > 16384) throw new IllegalArgumentException();
            body = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);
        } catch (java.io.IOException | tools.jackson.core.JacksonException invalid) { throw new IllegalArgumentException(); }
        var fields = Set.of("runId", "incidentId", "question", "timeRange", "knowledgeMode");
        if (!body.isObject() || body.size() != fields.size() || !fields.stream().allMatch(body::has)) throw new IllegalArgumentException();
        UUID run = IncidentController.uuid(text(body, "runId")), incident = IncidentController.uuid(text(body, "incidentId"));
        String question = text(body, "question");
        if (!"current".equals(text(body, "knowledgeMode")) || question.isBlank() || question.codePointCount(0, question.length()) > 2000) throw new IllegalArgumentException();
        var times = body.get("timeRange"); if (!times.isObject() || times.size() != 2) throw new IllegalArgumentException();
        var window = new ToolWindow(Instant.parse(text(times, "from")), Instant.parse(text(times, "to")));
        if (window.to().isAfter(Instant.now())) throw new IllegalArgumentException();
        var p = principals.requirePrincipal();
        for (var permission : Set.of(Permission.AI_DIAGNOSE, Permission.INCIDENT_READ, Permission.ENTITY_READ, Permission.METRIC_READ, Permission.EVIDENCE_READ, Permission.AI_INSIGHT_READ)) {
            if (!p.has(permission)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        }
        if (authorization.authorize(p, ResourceRef.incident(p.tenantId(), incident), Permission.AI_DIAGNOSE).denied()
            || authorization.authorize(p, new ResourceRef(p.tenantId(), "ai-insight", run.toString()), Permission.AI_INSIGHT_READ).denied()) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        reads.run(() -> incidents.get(p, incident));
        var boundary = delegations.getIfAvailable();
        if (boundary != null) {
            try (var lease = boundary.issue(request, body)) { runtime.dispatch(lease.authorization(), body.toString()); lease.checkActive(); }
            catch (com.acme.opsweave.platform.identity.RuntimeDelegations.Denied denied) { throw new ToolFailure(ToolFailure.Code.FORBIDDEN); }
        } else {
            var headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.size() != 1 || !headers.getFirst().startsWith("Bearer ")) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
            runtime.dispatch(headers.getFirst(), body.toString());
        }
        return reads.run(() -> {
            var saved = insights.get(p, run);
            if (!saved.incidentId().equals(incident) || !saved.subjectId().equals(p.subjectId()) || !saved.window().equals(window)
                || !saved.input().question().equals(question)) throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED);
            return Map.of("storage", storage, "record", InsightJson.body(saved));
        });
    }
    private static String text(tools.jackson.databind.JsonNode node, String name) {
        if (node == null || node.get(name) == null || !node.get(name).isString()) throw new IllegalArgumentException();
        return node.get(name).asString();
    }
}
