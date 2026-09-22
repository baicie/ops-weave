package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.AuthorizationDecision;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase.RawRecordCollector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase.SyncOutcome;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.integration.domain.ZabbixItemMapper;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pages Zabbix items into metric definitions. History points are not requested or stored.
 * Presence is separate from mapping success. A finished walk is an offset scan attempt.
 */
public final class IngestZabbixItemsUseCase {
    private static final Logger LOG = Logger.getLogger(IngestZabbixItemsUseCase.class.getName());
    private static final int MAX_PAGES = 10_000;
    private final AuthorizationService authorization;
    private final Connector connector;
    private final MetricDefinitionStore definitions;
    private final RawRecordCollector rawRecords;
    private final SyncRunStore syncRuns;
    private final ZabbixItemMapper mapper = new ZabbixItemMapper();
    private final String dataMode;
    private final String inventoryStore;
    private final String configuredSourceInstanceId;
    private final String secretRef;
    private final int pageSize;

    public IngestZabbixItemsUseCase(
        AuthorizationService authorization,
        Connector connector,
        MetricDefinitionStore definitions,
        RawRecordCollector rawRecords,
        SyncRunStore syncRuns,
        String dataMode,
        String inventoryStore,
        String configuredSourceInstanceId,
        String secretRef,
        int pageSize
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.rawRecords = Objects.requireNonNull(rawRecords, "rawRecords");
        this.syncRuns = Objects.requireNonNull(syncRuns, "syncRuns");
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
        SyncRun run = syncRuns.start(principal.tenantId(), sourceInstanceId, "item", dataMode);
        String cursor = null;
        int pages = 0;
        int fetched = 0;
        int accepted = 0;
        int rejected = 0;
        Set<String> observedExternalIds = new LinkedHashSet<>();
        List<String> rejectionReasons = new ArrayList<>();
        SyncFailureCode failure = SyncFailureCode.SOURCE_FETCH_FAILED;
        try {
            while (pages < MAX_PAGES) {
                failure = SyncFailureCode.SOURCE_FETCH_FAILED;
                Connector.Page page = connector.fetch(context, cursor, pageSize);
                pages++;
                for (Connector.RawRecord record : page.records()) {
                    fetched++;
                    if (record.externalId() != null && !record.externalId().isBlank()) {
                        observedExternalIds.add(record.externalId());
                    }
                    failure = SyncFailureCode.RAW_PERSIST_FAILED;
                    rawRecords.retain(principal.tenantId(), sourceInstanceId, run.id(), record);
                    failure = SyncFailureCode.MAPPING_FAILED;
                    var reason = mapper.rejectReason(record.payload());
                    if (reason.isPresent()) {
                        rejected++;
                        rejectionReasons.add(reason.get());
                        continue;
                    }
                    var mapped = mapper.map(principal.tenantId(), sourceInstanceId, record.payload());
                    failure = SyncFailureCode.INVENTORY_WRITE_FAILED;
                    definitions.upsert(mapped);
                    accepted++;
                }
                String next = page.nextCursor();
                failure = SyncFailureCode.CHECKPOINT_FAILED;
                syncRuns.checkpoint(principal.tenantId(), run.id(), next, pages, fetched, accepted, rejected);
                if (page.snapshotComplete()) {
                    failure = SyncFailureCode.INVENTORY_WRITE_FAILED;
                    int retired = definitions.retireMissing(principal.tenantId(), sourceInstanceId, observedExternalIds);
                    failure = SyncFailureCode.CHECKPOINT_FAILED;
                    syncRuns.succeed(principal.tenantId(), run.id());
                    return SyncOutcome.completed(
                        run.id(), pages, fetched, accepted, rejected, retired, dataMode, inventoryStore, List.copyOf(rejectionReasons)
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
            LOG.log(Level.WARNING, "Zabbix item sync failed: " + failure.name(), failed);
            failQuietly(principal.tenantId(), run.id(), failure);
            return SyncOutcome.failed(failure, run.id(), pages, fetched, accepted, rejected);
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
}
