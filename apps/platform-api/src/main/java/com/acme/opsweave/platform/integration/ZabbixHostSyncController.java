package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase.SyncOutcome;
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

    public ZabbixHostSyncController(PrincipalContext principalContext, IngestZabbixHostsUseCase ingest) {
        this.principalContext = principalContext;
        this.ingest = ingest;
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync() {
        SyncOutcome outcome = ingest.execute(principalContext.requirePrincipal(), null);
        return switch (outcome.kind()) {
            case DENIED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "source_unavailable"));
            case COMPLETED -> ResponseEntity.ok(body(outcome));
        };
    }

    private static Map<String, Object> body(SyncOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fetched", outcome.fetched());
        body.put("accepted", outcome.accepted());
        body.put("rejected", outcome.rejected());
        body.put("snapshotComplete", outcome.snapshotComplete());
        body.put("dataMode", outcome.dataMode());
        return body;
    }
}
