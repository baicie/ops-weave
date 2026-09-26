package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase.SyncOutcome;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.OpsweaveProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/zabbix/hosts")
public class ZabbixHostSyncController {
    private final PrincipalContext principalContext;
    private final IngestZabbixHostsUseCase ingest;
    private final InventoryWiring wiring;
    private final String source;

    public ZabbixHostSyncController(PrincipalContext principalContext, IngestZabbixHostsUseCase ingest, InventoryWiring wiring, OpsweaveProperties properties) {
        this.principalContext = principalContext;
        this.ingest = ingest;
        this.wiring = wiring;
        this.source = properties.zabbix().sourceInstanceId();
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) throws IOException {
        var input = PipelineJson.read(request, true);
        if (input != null) PipelineJson.fields(input, Set.of("pipelineVersion"));
        SyncOutcome outcome = ingest.execute(principalContext.requirePrincipal(), null,
            input == null ? null : PipelineJson.ref(input.get("pipelineVersion")));
        return switch (outcome.kind()) {
            case DENIED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(unavailable(outcome));
            case COMPLETED -> ResponseEntity.ok(body(outcome));
        };
    }

    private Map<String, Object> body(SyncOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fetched", outcome.fetched());
        body.put("accepted", outcome.accepted());
        body.put("rejected", outcome.rejected());
        body.put("retired", outcome.retired());
        body.put("pages", outcome.pages());
        body.put("snapshotComplete", outcome.snapshotComplete());
        body.put("dataMode", outcome.dataMode());
        body.put("inventoryStore", outcome.inventoryStore());
        body.put("scanConsistency", outcome.scanConsistency());
        if (outcome.syncRunId() != null) {
            body.put("syncRunId", outcome.syncRunId().toString());
            wiring.pipelines().pinned(principalContext.requirePrincipal().tenantId(), source, outcome.syncRunId())
                .ifPresent(version -> body.put("pipelineVersion", version));
        }
        return body;
    }

    private static Map<String, Object> unavailable(SyncOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "source_unavailable");
        body.put("pages", outcome.pages());
        body.put("fetched", outcome.fetched());
        body.put("accepted", outcome.accepted());
        body.put("rejected", outcome.rejected());
        body.put("snapshotComplete", false);
        if (!"none".equals(outcome.scanConsistency())) {
            body.put("scanConsistency", outcome.scanConsistency());
        }
        SyncFailureCode code = known(outcome.reasonCode());
        if (code != null) {
            body.put("failureCode", code.name());
            body.put("summary", code.safeSummary());
        }
        if (outcome.syncRunId() != null) {
            body.put("syncRunId", outcome.syncRunId().toString());
        }
        return body;
    }

    private static SyncFailureCode known(String code) {
        if (code == null) {
            return null;
        }
        for (SyncFailureCode item : SyncFailureCode.values()) {
            if (item.name().equals(code)) {
                return item;
            }
        }
        return null;
    }
}
