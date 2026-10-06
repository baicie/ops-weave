package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.WorkflowMetricSourceService;
import com.acme.opsweave.integration.domain.SourceConnectionConfiguration;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import com.acme.opsweave.integration.domain.WorkflowFailure;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Read one bounded metric window through a registered connection revision. */
@RestController
public final class RegisteredMetricHistoryController {
    private static final Set<String> QUERY = Set.of("from", "till", "limit");
    private static final int MAX_POINTS = 600;
    private final PrincipalContext principals;
    private final SourceConnectionWiring connections;
    private final RegisteredHostSourceReader registered;
    private final WorkflowMetricSourceService metrics;
    private final InventoryWiring wiring;

    public RegisteredMetricHistoryController(PrincipalContext principals, SourceConnectionWiring connections,
            RegisteredHostSourceReader registered, InventoryWiring wiring) {
        this.principals = principals;
        this.connections = connections;
        this.registered = registered;
        this.metrics = new WorkflowMetricSourceService(wiring.workflows(), connections.service(), java.time.Clock.systemUTC());
        this.wiring = wiring;
    }

    @GetMapping("/api/v2/data-sources/{id}/connection/{revision}/metrics/{itemId}/history")
    public Map<String, Object> read(@PathVariable UUID id, @PathVariable int revision, @PathVariable String itemId,
            HttpServletRequest request) {
        query(request);
        var principal = principals.requirePrincipal();
        SourceConnectionConfiguration configuration = connections.service().configuration(principal, id, revision)
                .orElseThrow(() -> new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
        var instance = connections.service().read(principal, id).instance();
        var configuredHost = new WorkflowDefinition.Source("ZABBIX_HOST", instance.source().instanceId(),
                new WorkflowDefinition.ConfigurationPin(id, revision, configuration.connectionDigest()));
        var selection = metrics.find(principal, configuredHost, itemId)
                .orElseThrow(() -> new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE))
                .source();
        long from = number(request, "from");
        long till = number(request, "till");
        int limit = request.getParameter("limit") == null ? MAX_POINTS : integer(request, "limit");
        if (from < 0 || till != from + 60 || limit < 1 || limit > MAX_POINTS) throw new IllegalArgumentException();
        var points = registered.metricWindow(principal, selection, configuration.endpoint().pin(),
                configuration.credentialPin(), configuration.hostGroupIds(), Instant.ofEpochSecond(from), Instant.ofEpochSecond(till), limit);
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "2.0");
        body.put("storage", wiring.label());
        body.put("dataMode", "zabbix-jsonrpc");
        body.put("source", WorkflowJson.wire(selection));
        body.put("sourceId", id.toString());
        body.put("configurationRevision", revision);
        body.put("connectionDigest", configuration.connectionDigest());
        body.put("scopeDigest", scopeDigest(id, revision, configuration));
        body.put("from", from);
        body.put("till", till);
        body.put("limit", limit);
        body.put("windowComplete", true);
        body.put("points", points);
        return body;
    }

    private static void query(HttpServletRequest request) {
        request.getParameterMap().forEach((name, values) -> {
            if (!QUERY.contains(name) || values.length != 1) throw new IllegalArgumentException();
        });
        if (request.getParameter("from") == null || request.getParameter("till") == null) throw new IllegalArgumentException();
    }
    private static long number(HttpServletRequest request, String name) {
        try { return Long.parseLong(request.getParameter(name)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(); }
    }
    private static int integer(HttpServletRequest request, String name) {
        try { return Integer.parseInt(request.getParameter(name)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(); }
    }
    private static String scopeDigest(UUID sourceId, int revision, SourceConnectionConfiguration fixed) {
        var parts = new ArrayList<String>();
        parts.add("registered-metric-history-scope-v1");
        parts.add(sourceId.toString());
        parts.add(Integer.toString(revision));
        parts.add(fixed.connectionDigest());
        parts.addAll(fixed.hostGroupIds());
        return WorkflowDefinition.hash(parts);
    }
}
