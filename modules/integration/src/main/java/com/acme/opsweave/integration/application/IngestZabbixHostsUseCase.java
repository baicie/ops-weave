package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.AuthorizationDecision;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.domain.ZabbixHostMapper;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class IngestZabbixHostsUseCase {
    private final AuthorizationService authorization;
    private final Connector connector;
    private final InventoryWritePort inventory;
    private final RawRecordCollector rawRecords;
    private final PipelineDefinition pipeline;
    private final ZabbixHostMapper mapper = new ZabbixHostMapper();
    private final String dataMode;
    private final String configuredSourceInstanceId;
    private final String secretRef;

    public IngestZabbixHostsUseCase(
        AuthorizationService authorization,
        Connector connector,
        InventoryWritePort inventory,
        RawRecordCollector rawRecords,
        PipelineDefinition pipeline,
        String dataMode,
        String configuredSourceInstanceId,
        String secretRef
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.rawRecords = Objects.requireNonNull(rawRecords, "rawRecords");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.dataMode = Objects.requireNonNull(dataMode, "dataMode");
        this.configuredSourceInstanceId = Objects.requireNonNull(configuredSourceInstanceId, "configuredSourceInstanceId");
        this.secretRef = Objects.requireNonNull(secretRef, "secretRef");
    }

    public SyncOutcome execute(Principal principal, String requestedSourceInstanceId) {
        Objects.requireNonNull(principal, "principal");
        String sourceInstanceId = requestedSourceInstanceId == null || requestedSourceInstanceId.isBlank()
            ? configuredSourceInstanceId
            : requestedSourceInstanceId;
        if (!configuredSourceInstanceId.equals(sourceInstanceId)) {
            return SyncOutcome.denied("SOURCE_MISMATCH");
        }
        AuthorizationDecision decision = authorization.authorize(
            principal,
            ResourceRef.source(principal.tenantId(), sourceInstanceId),
            Permission.SOURCE_SYNC
        );
        if (decision.denied()) {
            return SyncOutcome.denied(decision.reasonCode());
        }
        if ("closed".equals(dataMode) || "unavailable".equals(dataMode)) {
            return SyncOutcome.unavailable("Zabbix source is not configured");
        }
        Connector.SourceContext context = new Connector.SourceContext(
            principal.tenantId(),
            sourceInstanceId,
            secretRef
        );
        Connector.Page page;
        try {
            page = connector.fetch(context, null, 100);
        } catch (RuntimeException failed) {
            return SyncOutcome.unavailable("Zabbix fetch failed; inventory was not reconciled");
        }
        Instant ingestedAt = Instant.now();
        int accepted = 0;
        int rejected = 0;
        List<String> rejectionReasons = new ArrayList<>();
        for (Connector.RawRecord record : page.records()) {
            String rawRef = rawRecords.retain(principal.tenantId(), sourceInstanceId, record);
            var reason = mapper.rejectReason(record.payload());
            if (reason.isPresent()) {
                rejected++;
                rejectionReasons.add(reason.get());
                continue;
            }
            try {
                var mapped = mapper.map(
                    pipeline,
                    principal.tenantId(),
                    sourceInstanceId,
                    record.payload(),
                    record.observedAt(),
                    ingestedAt,
                    rawRef
                );
                inventory.upsert(mapped.entity(), mapped.observation(), mapped.link());
                accepted++;
            } catch (RuntimeException failed) {
                rejected++;
                rejectionReasons.add("mapping failed");
            }
        }
        return SyncOutcome.completed(
            page.records().size(),
            accepted,
            rejected,
            page.snapshotComplete(),
            dataMode,
            List.copyOf(rejectionReasons)
        );
    }

    public interface RawRecordCollector {
        String retain(TenantId tenantId, String sourceInstanceId, Connector.RawRecord record);
    }

    public record SyncOutcome(
        Kind kind,
        String reasonCode,
        int fetched,
        int accepted,
        int rejected,
        boolean snapshotComplete,
        String dataMode,
        List<String> rejectionReasons
    ) {
        public enum Kind { COMPLETED, DENIED, UNAVAILABLE }

        public static SyncOutcome completed(
            int fetched,
            int accepted,
            int rejected,
            boolean snapshotComplete,
            String dataMode,
            List<String> rejectionReasons
        ) {
            return new SyncOutcome(Kind.COMPLETED, "OK", fetched, accepted, rejected, snapshotComplete, dataMode, rejectionReasons);
        }

        public static SyncOutcome denied(String reasonCode) {
            return new SyncOutcome(Kind.DENIED, reasonCode, 0, 0, 0, false, "none", List.of());
        }

        public static SyncOutcome unavailable(String reasonCode) {
            return new SyncOutcome(Kind.UNAVAILABLE, reasonCode, 0, 0, 0, false, "unavailable", List.of());
        }
    }

    public static String newRawRef() {
        return "raw-" + UUID.randomUUID();
    }
}
