package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase.SyncOutcome;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.application.SourceConnectionService;
import com.acme.opsweave.integration.domain.SourceConnectionConfiguration;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit metadata synchronization for one immutable registered connection revision. */
@RestController
public final class RegisteredItemSyncController {
    private final PrincipalContext principals;
    private final SourceConnectionService connections;
    private final RegisteredHostSourceReader reader;
    private final IngestZabbixItemsUseCase ingest;
    private final InventoryWiring wiring;

    public RegisteredItemSyncController(
        PrincipalContext principals,
        SourceConnectionWiring connections,
        RegisteredHostSourceReader reader,
        IngestZabbixItemsUseCase ingest,
        InventoryWiring wiring
    ) {
        this.principals = principals;
        this.connections = connections.service();
        this.reader = reader;
        this.ingest = ingest;
        this.wiring = wiring;
    }

    @PostMapping("/api/v2/data-sources/{id}/connection/{revision}/items/sync")
    public ResponseEntity<?> sync(
        @PathVariable UUID id,
        @PathVariable int revision,
        HttpServletRequest request
    ) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException("Unexpected query");
        var principal = principals.requirePrincipal();
        var instance = connections.read(principal, id).instance();
        SourceConnectionConfiguration fixed = connections.configuration(principal, id, revision)
            .orElseThrow(() -> new com.acme.opsweave.integration.domain.WorkflowFailure(
                com.acme.opsweave.integration.domain.WorkflowFailure.Code.NOT_FOUND));
        var source = new WorkflowDefinition.Source(
            "ZABBIX_HOST",
            instance.source().instanceId(),
            new WorkflowDefinition.ConfigurationPin(id, revision, fixed.connectionDigest())
        );
        fixed = connections.workflowConfiguration(principal, source);
        String scopeDigest=scopeDigest(id,revision,fixed);
        var sourceScope=new SyncRun.SourceScope(id,revision,fixed.connectionDigest(),scopeDigest);
        SyncOutcome outcome = reader.syncItems(
            principal,
            instance.source().instanceId(),
            fixed.endpoint().pin(),
            fixed.credentialPin(),
            fixed.hostGroupIds(),
            sourceScope,
            ingest
        );
        HttpStatus status = switch (outcome.kind()) {
            case COMPLETED -> HttpStatus.OK;
            case DENIED -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(wire(id,instance.source().instanceId(),fixed,sourceScope,outcome));
    }

    private Map<String, Object> wire(UUID sourceId,String sourceInstanceId,SourceConnectionConfiguration fixed,SyncRun.SourceScope sourceScope,SyncOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2.0");
        body.put("storage", wiring.label());
        body.put("dataMode", outcome.dataMode());
        body.put("sourceId", sourceId.toString());
        body.put("sourceInstanceId", sourceInstanceId);
        body.put("configurationRevision", fixed.revision());
        body.put("connectionDigest", fixed.connectionDigest());
        body.put("hostGroupIds", fixed.hostGroupIds());
        body.put("scopeDigest", sourceScope.scopeDigest());
        body.put("pages", outcome.pages());
        body.put("fetched", outcome.fetched());
        body.put("accepted", outcome.accepted());
        body.put("rejected", outcome.rejected());
        body.put("retired", outcome.retired());
        body.put("snapshotComplete", outcome.snapshotComplete());
        body.put("scanConsistency", outcome.scanConsistency());
        body.put("syncRunId", outcome.syncRunId() == null ? null : outcome.syncRunId().toString());
        if (outcome.kind() != SyncOutcome.Kind.COMPLETED) {
            body.put("error", outcome.kind() == SyncOutcome.Kind.DENIED ? "forbidden" : "source_unavailable");
            var failure = SyncFailureCode.fromStoredReason(outcome.reasonCode())
                .or(() -> java.util.Arrays.stream(SyncFailureCode.values())
                    .filter(code -> code.name().equals(outcome.reasonCode())).findFirst());
            failure.ifPresent(code -> {
                body.put("failureCode", code.name());
                body.put("summary", code.safeSummary());
            });
        }
        return body;
    }

    private static String scopeDigest(UUID sourceId,int revision,SourceConnectionConfiguration fixed) {
        var parts = new ArrayList<String>();
        parts.add("registered-item-scan-scope-v1");
        parts.add(sourceId.toString());
        parts.add(Integer.toString(revision));
        parts.add(fixed.connectionDigest());
        parts.addAll(fixed.hostGroupIds());
        return WorkflowDefinition.hash(parts);
    }
}
