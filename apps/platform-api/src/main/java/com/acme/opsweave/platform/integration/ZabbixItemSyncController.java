package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase.SyncOutcome;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.platform.OpsweaveProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/zabbix/items")
public class ZabbixItemSyncController {
    private final PrincipalContext principalContext;
    private final IngestZabbixItemsUseCase ingest;
    private final OpsweaveProperties properties;

    public ZabbixItemSyncController(PrincipalContext principalContext, IngestZabbixItemsUseCase ingest,
                                    OpsweaveProperties properties) {
        this.principalContext = principalContext;
        this.ingest = ingest;
        this.properties = properties;
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync() {
        var principal = principalContext.requirePrincipal();
        if (!LegacyZabbixCompatibility.fixtureOnly(properties)) {
            return LegacyZabbixCompatibility.unavailable();
        }
        SyncOutcome outcome = ingest.execute(principal, null);
        return switch (outcome.kind()) {
            case DENIED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(unavailable(outcome));
            case COMPLETED -> ResponseEntity.ok(body(outcome));
        };
    }

    private static Map<String, Object> body(SyncOutcome outcome) {
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
