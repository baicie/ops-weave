package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.AuthorizationDecision;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.integration.domain.ZabbixHostMapper;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class IngestZabbixHostsUseCase {
    private static final Logger LOG = Logger.getLogger(IngestZabbixHostsUseCase.class.getName());
    private static final int MAX_PAGES = 10_000;
    private final AuthorizationService authorization;
    private final Connector connector;
    private final InventoryWritePort inventory;
    private final RawRecordCollector rawRecords;
    private final SyncRunStore syncRuns;
    private final PipelineDefinition pipeline;
    private final ZabbixHostMapper mapper = new ZabbixHostMapper();
    private final String dataMode;
    private final String inventoryStore;
    private final String configuredSourceInstanceId;
    private final String secretRef;
    private final int pageSize;

    public IngestZabbixHostsUseCase(
        AuthorizationService authorization,
        Connector connector,
        InventoryWritePort inventory,
        RawRecordCollector rawRecords,
        SyncRunStore syncRuns,
        PipelineDefinition pipeline,
        String dataMode,
        String inventoryStore,
        String configuredSourceInstanceId,
        String secretRef,
        int pageSize
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.rawRecords = Objects.requireNonNull(rawRecords, "rawRecords");
        this.syncRuns = Objects.requireNonNull(syncRuns, "syncRuns");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.dataMode = Objects.requireNonNull(dataMode, "dataMode");
        this.inventoryStore = Objects.requireNonNull(inventoryStore, "inventoryStore");
        this.configuredSourceInstanceId = Objects.requireNonNull(configuredSourceInstanceId, "configuredSourceInstanceId");
        this.secretRef = Objects.requireNonNull(secretRef, "secretRef");
        if (pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException("Zabbix page size must be between 1 and 500");
        }
        this.pageSize = pageSize;
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
        SyncRun run = syncRuns.start(principal.tenantId(), sourceInstanceId, "host", dataMode);
        String cursor = null;
        int pages = 0;
        int fetched = 0;
        int accepted = 0;
        int rejected = 0;
        Set<String> seen = new LinkedHashSet<>();
        List<String> rejectionReasons = new ArrayList<>();
        SyncFailureCode failure = SyncFailureCode.SOURCE_FETCH_FAILED;
        try {
            while (pages < MAX_PAGES) {
                failure = SyncFailureCode.SOURCE_FETCH_FAILED;
                Connector.Page page = connector.fetch(context, cursor, pageSize);
                pages++;
                Instant ingestedAt = Instant.now();
                for (Connector.RawRecord record : page.records()) {
                    fetched++;
                    failure = SyncFailureCode.RAW_PERSIST_FAILED;
                    String rawRef = rawRecords.retain(principal.tenantId(), sourceInstanceId, run.id(), record);
                    failure = SyncFailureCode.MAPPING_FAILED;
                    var reason = mapper.rejectReason(record.payload());
                    if (reason.isPresent()) {
                        rejected++;
                        rejectionReasons.add(reason.get());
                        continue;
                    }
                    var mapped = mapper.map(
                        pipeline,
                        principal.tenantId(),
                        sourceInstanceId,
                        record.payload(),
                        record.observedAt(),
                        ingestedAt,
                        rawRef
                    );
                    failure = SyncFailureCode.INVENTORY_WRITE_FAILED;
                    inventory.upsert(mapped.entity(), mapped.observation(), mapped.link());
                    seen.add(record.externalId());
                    accepted++;
                }
                String next = page.nextCursor();
                failure = SyncFailureCode.CHECKPOINT_FAILED;
                syncRuns.checkpoint(
                    principal.tenantId(),
                    run.id(),
                    next,
                    pages,
                    fetched,
                    accepted,
                    rejected
                );
                if (page.snapshotComplete()) {
                    failure = SyncFailureCode.INVENTORY_WRITE_FAILED;
                    int retired = inventory.retireMissing(
                        principal.tenantId(),
                        sourceInstanceId,
                        "host",
                        seen
                    );
                    failure = SyncFailureCode.CHECKPOINT_FAILED;
                    syncRuns.succeed(principal.tenantId(), run.id());
                    return SyncOutcome.completed(
                        run.id(),
                        pages,
                        fetched,
                        accepted,
                        rejected,
                        retired,
                        dataMode,
                        inventoryStore,
                        List.copyOf(rejectionReasons)
                    );
                }
                if (next == null || next.equals(cursor)) {
                    failure = SyncFailureCode.PAGE_NOT_ADVANCED;
                    throw new IllegalStateException(failure.name());
                }
                cursor = next;
            }
            failure = SyncFailureCode.PAGE_LIMIT_EXCEEDED;
            throw new IllegalStateException(failure.name());
        } catch (RuntimeException failed) {
            LOG.log(Level.WARNING, "Zabbix host sync failed: " + failure.name(), failed);
            failQuietly(principal.tenantId(), run.id(), failure);
            return SyncOutcome.failed(failure, run.id());
        }
    }

    private void failQuietly(TenantId tenantId, UUID id, SyncFailureCode failure) {
        try {
            SyncRun current = syncRuns.find(tenantId, id).orElse(null);
            if (current != null && current.status() == SyncStatus.RUNNING) {
                syncRuns.fail(tenantId, id, failure.storedReason());
            }
        } catch (RuntimeException ignored) {
            // The scan already failed; do not turn a status write into a reconcile.
        }
    }

    public interface RawRecordCollector {
        String retain(TenantId tenantId, String sourceInstanceId, UUID syncRunId, Connector.RawRecord record);
    }

    public record SyncOutcome(
        Kind kind,
        String reasonCode,
        UUID syncRunId,
        int pages,
        int fetched,
        int accepted,
        int rejected,
        int retired,
        boolean snapshotComplete,
        String dataMode,
        String inventoryStore,
        List<String> rejectionReasons
    ) {
        public enum Kind { COMPLETED, DENIED, UNAVAILABLE }

        public static SyncOutcome completed(
            UUID syncRunId,
            int pages,
            int fetched,
            int accepted,
            int rejected,
            int retired,
            String dataMode,
            String inventoryStore,
            List<String> rejectionReasons
        ) {
            return new SyncOutcome(
                Kind.COMPLETED,
                "OK",
                syncRunId,
                pages,
                fetched,
                accepted,
                rejected,
                retired,
                true,
                dataMode,
                inventoryStore,
                rejectionReasons
            );
        }

        public static SyncOutcome denied(String reasonCode) {
            return new SyncOutcome(Kind.DENIED, reasonCode, null, 0, 0, 0, 0, 0, false, "none", "none", List.of());
        }

        public static SyncOutcome unavailable(String reasonCode) {
            return new SyncOutcome(
                Kind.UNAVAILABLE,
                reasonCode,
                null,
                0,
                0,
                0,
                0,
                0,
                false,
                "unavailable",
                "none",
                List.of()
            );
        }

        public static SyncOutcome failed(SyncFailureCode failure, UUID syncRunId) {
            return new SyncOutcome(
                Kind.UNAVAILABLE,
                failure.name(),
                syncRunId,
                0,
                0,
                0,
                0,
                0,
                false,
                "unavailable",
                "none",
                List.of()
            );
        }
    }

    public static String newRawRef() {
        return "raw-" + UUID.randomUUID();
    }
}
