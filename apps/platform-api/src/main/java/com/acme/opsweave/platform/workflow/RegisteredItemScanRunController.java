package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.RegisteredSourceScanRunQueryService;
import com.acme.opsweave.integration.application.SourceConnectionService;
import com.acme.opsweave.integration.domain.SourceConnectionConfiguration;
import com.acme.opsweave.integration.domain.SourceScanRunException;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
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

/** Read-only history of scans pinned to a registered source connection revision. */
@RestController
public final class RegisteredItemScanRunController {
    private static final Set<String> PAGE_QUERY = Set.of("limit", "after");

    private final PrincipalContext principals;
    private final RegisteredSourceScanRunQueryService runs;
    private final SourceConnectionService connections;
    private final InventoryWiring wiring;

    public RegisteredItemScanRunController(
        PrincipalContext principals,
        RegisteredSourceScanRunQueryService runs,
        SourceConnectionWiring connections,
        InventoryWiring wiring
    ) {
        this.principals = principals;
        this.runs = runs;
        this.connections = connections.service();
        this.wiring = wiring;
    }

    @GetMapping("/api/v2/data-sources/{id}/connection/{revision}/items/runs")
    public Map<String, Object> page(
        @PathVariable UUID id,
        @PathVariable int revision,
        HttpServletRequest request
    ) {
        query(request, PAGE_QUERY);
        Principal principal = principals.requirePrincipal();
        SourceConnectionConfiguration fixed = configuration(principal, id, revision);
        String after = request.getParameter("after");
        int limit = request.getParameter("limit") == null
            ? RegisteredSourceScanRunQueryService.DEFAULT_LIMIT
            : Integer.parseInt(request.getParameter("limit"));
        var page = runs.recent(principal, id, revision, after, limit);

        Map<String, Object> body = envelope(principal, id, revision, fixed);
        body.put("limit", limit);
        body.put("after", after);
        body.put("hasMore", page.hasMore());
        body.put("nextCursor", page.nextCursor());
        body.put("items", page.items().stream().map(entry -> run(entry.run(), id, revision, fixed)).toList());
        return body;
    }

    @GetMapping("/api/v2/data-sources/{id}/connection/{revision}/items/runs/{syncRunId}")
    public Map<String, Object> read(
        @PathVariable UUID id,
        @PathVariable int revision,
        @PathVariable UUID syncRunId,
        HttpServletRequest request
    ) {
        query(request, Set.of());
        Principal principal = principals.requirePrincipal();
        SourceConnectionConfiguration fixed = configuration(principal, id, revision);
        Map<String, Object> body = envelope(principal, id, revision, fixed);
        body.put("run", run(runs.find(principal, id, revision, syncRunId).run(), id, revision, fixed));
        return body;
    }

    private SourceConnectionConfiguration configuration(Principal principal, UUID id, int revision) {
        return connections.configuration(principal, id, revision)
            .orElseThrow(() -> new com.acme.opsweave.integration.domain.WorkflowFailure(
                com.acme.opsweave.integration.domain.WorkflowFailure.Code.NOT_FOUND));
    }

    private Map<String, Object> envelope(
        Principal principal,
        UUID id,
        int revision,
        SourceConnectionConfiguration fixed
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2.0");
        body.put("storage", wiring.label());
        body.put("dataMode", "scan-log");
        body.put("tenantId", principal.tenantId().value());
        body.put("sourceId", id.toString());
        body.put("sourceInstanceId", SourceConnectionConfiguration.physicalId(id));
        body.put("configurationRevision", revision);
        body.put("connectionDigest", fixed.connectionDigest());
        body.put("hostGroupIds", fixed.hostGroupIds());
        return body;
    }

    private Map<String, Object> run(
        SyncRun value,
        UUID sourceId,
        int revision,
        SourceConnectionConfiguration fixed
    ) {
        requireMatchingScope(value, sourceId, revision, fixed);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("syncRunId", value.id().toString());
        item.put("sourceId", sourceId.toString());
        item.put("configurationRevision", revision);
        item.put("connectionDigest", fixed.connectionDigest());
        item.put("scopeDigest", scopeDigest(sourceId, revision, fixed));
        item.put("objectType", value.objectType());
        item.put("status", value.status().name());
        item.put("startedAt", value.startedAt().toString());
        item.put("completedAt", value.completedAt() == null ? null : value.completedAt().toString());
        item.put("cursor", value.cursor());
        item.put("pages", value.pages());
        item.put("fetched", value.fetched());
        item.put("accepted", value.accepted());
        item.put("rejected", value.rejected());
        item.put("retired", value.retired());
        item.put("snapshotComplete", value.snapshotComplete());
        item.put("dataMode", value.dataMode());
        item.put("scanConsistency", value.scanConsistency());
        SyncFailureCode.fromStoredReason(value.failureReason()).ifPresent(code -> {
            item.put("failureCode", code.name());
            item.put("failureSummary", code.safeSummary());
        });
        return item;
    }

    private static void requireMatchingScope(
        SyncRun run,
        UUID sourceId,
        int revision,
        SourceConnectionConfiguration fixed
    ) {
        SyncRun.SourceScope scope = run.sourceScope();
        if (scope == null
            || !scope.sourceId().equals(sourceId)
            || scope.configurationRevision() != revision
            || !scope.connectionDigest().equals(fixed.connectionDigest())
            || !scope.scopeDigest().equals(scopeDigest(sourceId, revision, fixed))) {
            throw new IllegalStateException("Registered item scan scope is inconsistent");
        }
    }

    private static String scopeDigest(UUID id, int revision, SourceConnectionConfiguration fixed) {
        var parts = new ArrayList<String>();
        parts.add("registered-item-scan-scope-v1");
        parts.add(id.toString());
        parts.add(Integer.toString(revision));
        parts.add(fixed.connectionDigest());
        parts.addAll(fixed.hostGroupIds());
        return WorkflowDefinition.hash(parts);
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((name, values) -> {
            if (!allowed.contains(name) || values.length != 1) {
                throw new SourceScanRunException(SourceScanRunException.Code.INVALID_REQUEST);
            }
        });
    }
}
