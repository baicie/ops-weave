package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.ReadZabbixProblemsUseCase;
import com.acme.opsweave.integration.application.SourceConnectionService;
import com.acme.opsweave.integration.domain.ProblemReadWindow;
import com.acme.opsweave.integration.domain.SourceConnectionConfiguration;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import com.acme.opsweave.integration.domain.WorkflowFailure;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Read a bounded problem page from one immutable registered connection revision. */
@RestController
public final class RegisteredProblemController {
    private static final Set<String> QUERY = Set.of("from", "till", "afterEventId", "limit");
    private final PrincipalContext principals;
    private final SourceConnectionService connections;
    private final RegisteredHostSourceReader reader;
    private final InventoryWiring wiring;

    public RegisteredProblemController(PrincipalContext principals, SourceConnectionWiring connections,
            RegisteredHostSourceReader reader, InventoryWiring wiring) {
        this.principals = principals;
        this.connections = connections.service();
        this.reader = reader;
        this.wiring = wiring;
    }

    @GetMapping("/api/v2/data-sources/{id}/connection/{revision}/problems")
    public Map<String, Object> read(@PathVariable UUID id, @PathVariable int revision, HttpServletRequest request) {
        query(request);
        var principal = principals.requirePrincipal();
        var instance = connections.read(principal, id).instance();
        SourceConnectionConfiguration fixed = connections.configuration(principal, id, revision)
            .orElseThrow(() -> new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
        if (fixed.hostGroupIds().isEmpty()) {
            throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        }
        var source = new WorkflowDefinition.Source("ZABBIX_HOST", instance.source().instanceId(),
            new WorkflowDefinition.ConfigurationPin(id, revision, fixed.connectionDigest()));
        fixed = connections.workflowConfiguration(principal, source);
        long from = number(request, "from");
        long till = number(request, "till");
        String after = request.getParameter("afterEventId");
        int limit = request.getParameter("limit") == null ? 25 : integer(request, "limit");
        var window = new ProblemReadWindow(from, till, after, limit);
        var result = reader.readProblems(principal, instance.source().instanceId(), fixed.endpoint().pin(),
            fixed.credentialPin(), fixed.hostGroupIds(), window);
        var scopeDigest = scopeDigest(id, revision, fixed);
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("storage", wiring.label());
        body.put("dataMode", result.dataMode());
        body.put("sourceId", id.toString());
        body.put("sourceInstanceId", result.sourceInstanceId());
        body.put("configurationRevision", revision);
        body.put("connectionDigest", fixed.connectionDigest());
        body.put("hostGroupIds", fixed.hostGroupIds());
        body.put("scopeDigest", scopeDigest);
        var echo = new LinkedHashMap<String, Object>();
        echo.put("from", from);
        echo.put("till", till);
        echo.put("afterEventId", after);
        echo.put("limit", limit);
        body.put("query", echo);
        body.put("items", result.page().items().stream().map(RegisteredProblemController::wire).toList());
        body.put("nextAfterEventId", result.page().nextAfterEventId());
        return body;
    }

    private static Map<String, Object> wire(ExternalProblem problem) {
        var row = new LinkedHashMap<String, Object>();
        row.put("schemaVersion", "2.0");
        row.put("tenantId", problem.tenantId().value());
        row.put("sourceInstanceId", problem.sourceInstanceId());
        row.put("problemEventId", problem.problemEventId());
        row.put("triggerId", problem.triggerId());
        row.put("title", problem.title());
        row.put("severity", problem.severity());
        row.put("occurredAt", problem.occurredAt().toString());
        row.put("observedAt", problem.observedAt().toString());
        row.put("hostIds", problem.hostIds());
        row.put("suppressed", problem.suppressed());
        row.put("recoveryEventId", problem.recoveryEventId());
        row.put("recoveredAt", problem.recoveredAt() == null ? null : problem.recoveredAt().toString());
        row.put("state", problem.state().name());
        row.put("gaps", problem.state() == ExternalProblem.State.RECOVERY_UNKNOWN
            ? java.util.List.of("RECOVERY_EVENT_UNAVAILABLE") : java.util.List.of());
        return row;
    }

    private static String scopeDigest(UUID sourceId, int revision, SourceConnectionConfiguration fixed) {
        var parts = new ArrayList<String>();
        parts.add("registered-problem-scope-v1");
        parts.add(sourceId.toString());
        parts.add(Integer.toString(revision));
        parts.add(fixed.connectionDigest());
        parts.addAll(fixed.hostGroupIds());
        return WorkflowDefinition.hash(parts);
    }

    private static void query(HttpServletRequest request) {
        request.getParameterMap().forEach((name, values) -> {
            if (!QUERY.contains(name) || values.length != 1) throw new IllegalArgumentException();
        });
        if (request.getParameter("from") == null || request.getParameter("till") == null) {
            throw new IllegalArgumentException();
        }
    }

    private static long number(HttpServletRequest request, String name) {
        try { return Long.parseLong(request.getParameter(name)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(); }
    }

    private static int integer(HttpServletRequest request, String name) {
        try { return Integer.parseInt(request.getParameter(name)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(); }
    }
}
