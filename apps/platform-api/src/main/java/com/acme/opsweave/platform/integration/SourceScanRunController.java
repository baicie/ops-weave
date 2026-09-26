package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.SourceScanRunQueryService;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only trace of scans the platform already stored. Nothing here contacts a source, acquires a
 * lease, retries a scan, repairs metadata or reconciles missing hosts; a failed scan stays failed.
 */
@RestController
public final class SourceScanRunController {
    private static final Set<String> QUERY = Set.of("limit", "after");

    private final PrincipalContext principals;
    private final SourceScanRunQueryService runs;
    private final InventoryWiring wiring;
    private final String source;

    public SourceScanRunController(
        PrincipalContext principals,
        SourceScanRunQueryService runs,
        InventoryWiring wiring,
        OpsweaveProperties properties
    ) {
        this.principals = principals;
        this.runs = runs;
        this.wiring = wiring;
        this.source = properties.zabbix().sourceInstanceId();
    }

    @GetMapping("/api/v1/integrations/zabbix/hosts/runs")
    public Map<String, Object> hostRuns(HttpServletRequest request) {
        return page("host", request);
    }

    @GetMapping("/api/v1/integrations/zabbix/hosts/runs/{id}")
    public Map<String, Object> hostRun(@PathVariable String id, HttpServletRequest request) {
        return entry("host", id, request);
    }

    @GetMapping("/api/v1/integrations/zabbix/items/runs")
    public Map<String, Object> itemRuns(HttpServletRequest request) {
        return page("item", request);
    }

    @GetMapping("/api/v1/integrations/zabbix/items/runs/{id}")
    public Map<String, Object> itemRun(@PathVariable String id, HttpServletRequest request) {
        return entry("item", id, request);
    }

    private Map<String, Object> page(String objectType, HttpServletRequest request) {
        query(request, QUERY);
        String after = request.getParameter("after");
        int limit = request.getParameter("limit") == null
            ? SourceScanRunQueryService.DEFAULT_LIMIT
            : Integer.parseInt(request.getParameter("limit"));
        Principal principal = principals.requirePrincipal();
        SourceScanRunQueryService.Page page = runs.recent(principal, objectType, after, limit);
        Map<String, Object> body = envelope(principal, objectType);
        body.put("limit", limit);
        body.put("after", after);
        body.put("hasMore", page.hasMore());
        body.put("nextCursor", page.nextCursor());
        body.put("items", page.items().stream().map(this::item).toList());
        return body;
    }

    private Map<String, Object> entry(String objectType, String id, HttpServletRequest request) {
        query(request, Set.of());
        Principal principal = principals.requirePrincipal();
        Map<String, Object> body = envelope(principal, objectType);
        body.put("run", item(runs.find(principal, objectType, UUID.fromString(id))));
        return body;
    }

    private Map<String, Object> envelope(Principal principal, String objectType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "1.0");
        body.put("storage", wiring.label());
        body.put("dataMode", "scan-log");
        body.put("tenantId", principal.tenantId().value());
        body.put("sourceInstanceId", source);
        body.put("objectType", objectType);
        return body;
    }

    private Map<String, Object> item(SourceScanRunQueryService.Entry entry) {
        SyncRun run = entry.run();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("syncRunId", run.id().toString());
        item.put("objectType", run.objectType());
        item.put("status", run.status().name());
        item.put("startedAt", run.startedAt().toString());
        item.put("completedAt", run.completedAt() == null ? null : run.completedAt().toString());
        item.put("cursor", run.cursor());
        item.put("pages", run.pages());
        item.put("fetched", run.fetched());
        item.put("accepted", run.accepted());
        item.put("rejected", run.rejected());
        item.put("snapshotComplete", run.snapshotComplete());
        item.put("dataMode", run.dataMode());
        item.put("scanConsistency", run.scanConsistency());
        SyncFailureCode.fromStoredReason(run.failureReason()).ifPresent(code -> {
            item.put("failureCode", code.name());
            item.put("failureSummary", code.safeSummary());
        });
        if (entry.pipelineVersion() != null) {
            item.put("pipelineVersion", Map.of(
                "id", entry.pipelineVersion().id(),
                "revision", entry.pipelineVersion().revision(),
                "digest", entry.pipelineVersion().digest()
            ));
        }
        return item;
    }

    /** Only the documented read parameters are accepted; anything else is a client error. */
    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((name, values) -> {
            if (!allowed.contains(name) || values.length != 1) {
                throw new IllegalArgumentException("Unexpected query");
            }
        });
    }
}
