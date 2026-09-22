import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.integration.domain.ZabbixHostMapper;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class ZabbixHostMappingSmoke {
    public static void main(String[] args) {
        var pipeline = PipelineDefinition.zabbixHostV1();
        require(pipeline.executionOrder().size() == 6, "linear host pipeline");
        var tenant = new TenantId("tenant-demo");
        var mapper = new ZabbixHostMapper();
        var payload = Map.<String, Object>of(
            "hostid", "10084",
            "host", "zabbix-server",
            "name", "Zabbix server",
            "status", "0",
            "interfaces", java.util.List.of(Map.of("ip", "10.0.0.10", "main", "1", "type", "1"))
        );
        var first = mapper.map(
            pipeline, tenant, "zabbix-1", payload,
            Instant.parse("2026-09-21T12:00:00Z"),
            Instant.parse("2026-09-21T12:00:01Z"),
            "raw-1"
        );
        var second = mapper.map(
            pipeline, tenant, "zabbix-1", payload,
            Instant.parse("2026-09-21T13:00:00Z"),
            Instant.parse("2026-09-21T13:00:01Z"),
            "raw-2"
        );
        require(first.entity().id().equals(second.entity().id()), "repeat sync keeps entity id");
        require("Zabbix server".equals(first.entity().name()), "host name");
        require("10.0.0.10".equals(first.entity().attributes().get("ip")), "host ip");
        require("zabbix".equals(first.entity().attributes().get("source")), "source label");
        require("10084".equals(first.entity().attributes().get("hostId")), "host id");
        require(mapper.rejectReason(Map.of("name", "x")).isPresent(), "missing hostid rejected");
        var otherTenant = mapper.map(
            pipeline, new TenantId("tenant-other"), "zabbix-1", payload,
            Instant.parse("2026-09-21T12:00:00Z"),
            Instant.parse("2026-09-21T12:00:01Z"),
            "raw-3"
        );
        require(!first.entity().id().equals(otherTenant.entity().id()), "cross-tenant host identity");

        var inventory = new InMemoryInventoryStore();
        var runs = new InMemorySyncRunStore();
        var staleKey = new ExternalObjectKey(tenant, "zabbix-1", "host", "999", "1");
        var staleId = EntityIds.fromExternal(staleKey);
        inventory.upsert(
            new com.acme.opsweave.inventory.domain.Entity(
                staleId, tenant, "host", "gone-host", Lifecycle.ACTIVE, 1,
                Instant.parse("2026-09-20T00:00:00Z"), Map.of("hostId", "999")
            ),
            new com.acme.opsweave.inventory.domain.Observation(
                "obs-stale", staleKey, staleId,
                Instant.parse("2026-09-20T00:00:00Z"), Instant.parse("2026-09-20T00:00:01Z"),
                Map.of("hostId", "999"), "raw-stale", 1
            ),
            new ExternalLink(staleId, staleKey)
        );
        var principal = new Principal(
            new SubjectId("user-demo"),
            tenant,
            Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ),
            ResourceScope.tenantWide()
        );
        var ingest = ingest(new FixtureZabbixHostConnector(), inventory, runs, 1);
        var outcome = ingest.execute(principal, null);
        require(outcome.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "fixture ingest completed");
        require(outcome.pages() == 2, "two pages");
        require(outcome.accepted() == 2, "two hosts accepted");
        require(outcome.retired() == 1, "absent host retired only after a complete snapshot");
        require(outcome.snapshotComplete(), "snapshot complete");
        require("offset-scan-attempt".equals(outcome.scanConsistency()), "completed scan is an offset attempt");
        require("labeled-fixture".equals(outcome.dataMode()), "fixture remains labeled");
        require(runs.find(tenant, outcome.syncRunId()).orElseThrow().status() == SyncStatus.SUCCEEDED, "sync run succeeded");
        require("INACTIVE".equals(inventory.find(tenant, staleId).orElseThrow().lifecycle()), "stale host inactive");
        var denied = ingest.execute(
            new Principal(new SubjectId("user-demo"), tenant, Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide()),
            null
        );
        require(denied.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.DENIED, "sync without permission denied");

        var kept = new InMemoryInventoryStore();
        var failedRuns = new InMemorySyncRunStore();
        kept.upsert(
            new com.acme.opsweave.inventory.domain.Entity(
                staleId, tenant, "host", "gone-host", Lifecycle.ACTIVE, 1,
                Instant.parse("2026-09-20T00:00:00Z"), Map.of("hostId", "999")
            ),
            new com.acme.opsweave.inventory.domain.Observation(
                "obs-stale-2", staleKey, staleId,
                Instant.parse("2026-09-20T00:00:00Z"), Instant.parse("2026-09-20T00:00:01Z"),
                Map.of("hostId", "999"), "raw-stale-2", 1
            ),
            new ExternalLink(staleId, staleKey)
        );
        var failed = ingest(new FailingSecondPageConnector(), kept, failedRuns, 1).execute(principal, null);
        require(failed.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE, "mid-scan failure is unavailable");
        require("SOURCE_FETCH_FAILED".equals(failed.reasonCode()), "fetch failure code");
        require(failed.syncRunId() != null, "failed scan keeps its sync run");
        require(!failed.snapshotComplete(), "failed scan is not a complete snapshot");
        require("ACTIVE".equals(kept.find(tenant, staleId).orElseThrow().lifecycle()), "failed scan does not retire");
        require(failed.pages() == 1, "failed scan keeps the completed page count");
        require(failed.fetched() == 1, "failed scan keeps the fetched count");
        require(failed.accepted() == 1, "failed scan keeps the accepted count");
        require("offset-scan-attempt".equals(failed.scanConsistency()), "failed scan remains an offset attempt");

        var emptyStore = seeded(tenant, staleKey, staleId, "obs-empty", "raw-empty");
        var emptied = ingest(new StaticPageConnector(true), emptyStore, new InMemorySyncRunStore(), 1).execute(principal, null);
        require(emptied.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "empty snapshot completes");
        require(emptied.retired() == 1, "empty complete snapshot retires previously seen hosts");
        require("INACTIVE".equals(emptyStore.find(tenant, staleId).orElseThrow().lifecycle()), "empty snapshot host inactive");

        var writeStore = seeded(tenant, staleKey, staleId, "obs-write", "raw-write");
        var writeFailed = new IngestZabbixHostsUseCase(
            new AuthorizeUseCase(),
            new FixtureZabbixHostConnector(),
            new InventoryWritePort() {
                @Override
                public void upsert(
                    com.acme.opsweave.inventory.domain.Entity entity,
                    com.acme.opsweave.inventory.domain.Observation observation,
                    ExternalLink link
                ) {
                    throw new IllegalStateException("inventory unavailable");
                }

                @Override
                public int retireMissing(TenantId tenantId, String sourceInstanceId, String externalType, java.util.Set<String> seenExternalIds) {
                    throw new IllegalStateException("retire must not run");
                }
            },
            new InMemoryRawRecordStore(),
            new InMemorySyncRunStore(),
            PipelineDefinition.zabbixHostV1(),
            "labeled-fixture",
            "memory",
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN",
            1
        ).execute(principal, null);
        require("INVENTORY_WRITE_FAILED".equals(writeFailed.reasonCode()), "inventory write failure code");
        require("ACTIVE".equals(writeStore.find(tenant, staleId).orElseThrow().lifecycle()), "write failure does not retire");

        var stalledStore = seeded(tenant, staleKey, staleId, "obs-stall", "raw-stall");
        var stalled = ingest(new StaticPageConnector(false), stalledStore, new InMemorySyncRunStore(), 1).execute(principal, null);
        require("PAGE_NOT_ADVANCED".equals(stalled.reasonCode()), "stalled cursor failure code");
        require("ACTIVE".equals(stalledStore.find(tenant, staleId).orElseThrow().lifecycle()), "stalled cursor does not retire");

        var rawStore = seeded(tenant, staleKey, staleId, "obs-raw", "raw-raw");
        var rawRuns = new InMemorySyncRunStore();
        var rawFailed = new IngestZabbixHostsUseCase(
            new AuthorizeUseCase(),
            new FixtureZabbixHostConnector(),
            rawStore,
            (tenantId, sourceInstanceId, syncRunId, record) -> {
                throw new IllegalStateException("raw store unavailable");
            },
            rawRuns,
            PipelineDefinition.zabbixHostV1(),
            "labeled-fixture",
            "memory",
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN",
            1
        ).execute(principal, null);
        require("RAW_PERSIST_FAILED".equals(rawFailed.reasonCode()), "raw persist failure code");
        require("ACTIVE".equals(rawStore.find(tenant, staleId).orElseThrow().lifecycle()), "raw failure does not retire");

        var presentKey = new ExternalObjectKey(tenant, "zabbix-1", "host", "10084", "1");
        var presentId = EntityIds.fromExternal(presentKey);
        var presentStore = seeded(tenant, staleKey, staleId, "obs-present-stale", "raw-present-stale");
        presentStore.upsert(
            new Entity(presentId, tenant, "host", "still-there", Lifecycle.ACTIVE, 1, Instant.parse("2026-09-20T00:00:00Z"), Map.of("hostId", "10084")),
            new com.acme.opsweave.inventory.domain.Observation(
                "obs-present", presentKey, presentId, Instant.parse("2026-09-20T00:00:00Z"), Instant.parse("2026-09-20T00:00:01Z"),
                Map.of("hostId", "10084"), "raw-present", 1
            ),
            new ExternalLink(presentId, presentKey)
        );
        var rejected = ingest(new RejectedButPresentConnector(), presentStore, new InMemorySyncRunStore(), 1).execute(principal, null);
        require(rejected.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "rejected mapping can still finish the scan");
        require(rejected.accepted() == 0, "rejected host is not accepted");
        require(rejected.rejected() == 1, "rejected host is counted separately");
        require("ACTIVE".equals(presentStore.find(tenant, presentId).orElseThrow().lifecycle()), "present rejected host stays active");
        require("INACTIVE".equals(presentStore.find(tenant, staleId).orElseThrow().lifecycle()), "absent host still retires");

        var drift = new InMemoryInventoryStore();
        for (String hostId : java.util.List.of("1", "2", "3")) {
            var key = new ExternalObjectKey(tenant, "zabbix-1", "host", hostId, "1");
            var id = EntityIds.fromExternal(key);
            drift.upsert(
                new Entity(id, tenant, "host", "host-" + hostId, Lifecycle.ACTIVE, 1, Instant.parse("2026-09-20T00:00:00Z"), Map.of("hostId", hostId)),
                new com.acme.opsweave.inventory.domain.Observation(
                    "obs-drift-" + hostId, key, id, Instant.parse("2026-09-20T00:00:00Z"), Instant.parse("2026-09-20T00:00:01Z"),
                    Map.of("hostId", hostId), "raw-drift-" + hostId, 1
                ),
                new ExternalLink(id, key)
            );
        }
        var drifted = ingest(new DriftingHostConnector(), drift, new InMemorySyncRunStore(), 1).execute(principal, null);
        require(drifted.snapshotComplete(), "offset drift still ends the scan attempt");
        require("offset-scan-attempt".equals(drifted.scanConsistency()), "drift is not a consistent snapshot");
        require("ACTIVE".equals(lifecycle(drift, tenant, "1")), "host returned on page 1 stays active");
        require("INACTIVE".equals(lifecycle(drift, tenant, "2")), "host skipped by an offset shift is not protected");
        require("ACTIVE".equals(lifecycle(drift, tenant, "3")), "host returned after the shift stays active");
        System.out.println("Zabbix host mapping smoke: 46 checks passed");
    }

    private static IngestZabbixHostsUseCase ingest(
        Connector connector,
        InMemoryInventoryStore inventory,
        InMemorySyncRunStore runs,
        int pageSize
    ) {
        return new IngestZabbixHostsUseCase(
            new AuthorizeUseCase(),
            connector,
            inventory,
            new InMemoryRawRecordStore(),
            runs,
            PipelineDefinition.zabbixHostV1(),
            "labeled-fixture",
            "memory",
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN",
            pageSize
        );
    }

    private static final class FailingSecondPageConnector implements Connector {
        private int calls;

        @Override
        public String type() {
            return "zabbix";
        }

        @Override
        public ProbeResult probe(SourceContext source) {
            return new ProbeResult(false, "test");
        }

        @Override
        public Page fetch(SourceContext source, String cursor, int limit) {
            calls++;
            if (calls > 1) {
                throw new IllegalStateException("page failed");
            }
            return new FixtureZabbixHostConnector().fetch(source, cursor, limit);
        }
    }

    private static InMemoryInventoryStore seeded(
        TenantId tenant,
        ExternalObjectKey staleKey,
        com.acme.opsweave.sharedkernel.EntityId staleId,
        String observationId,
        String rawRef
    ) {
        var store = new InMemoryInventoryStore();
        store.upsert(
            new Entity(
                staleId, tenant, "host", "gone-host", Lifecycle.ACTIVE, 1,
                Instant.parse("2026-09-20T00:00:00Z"), Map.of("hostId", "999")
            ),
            new com.acme.opsweave.inventory.domain.Observation(
                observationId, staleKey, staleId,
                Instant.parse("2026-09-20T00:00:00Z"), Instant.parse("2026-09-20T00:00:01Z"),
                Map.of("hostId", "999"), rawRef, 1
            ),
            new ExternalLink(staleId, staleKey)
        );
        return store;
    }

    private static final class StaticPageConnector implements Connector {
        private final boolean complete;

        private StaticPageConnector(boolean complete) {
            this.complete = complete;
        }

        @Override
        public String type() {
            return "zabbix";
        }

        @Override
        public ProbeResult probe(SourceContext source) {
            return new ProbeResult(false, "test");
        }

        @Override
        public Page fetch(SourceContext source, String cursor, int limit) {
            return new Page(java.util.List.of(), null, complete);
        }
    }

    private static final class RejectedButPresentConnector implements Connector {
        @Override
        public String type() {
            return "zabbix";
        }

        @Override
        public ProbeResult probe(SourceContext source) {
            return new ProbeResult(false, "test");
        }

        @Override
        public Page fetch(SourceContext source, String cursor, int limit) {
            return new Page(
                java.util.List.of(new RawRecord("10084", Instant.parse("2026-09-21T12:00:00Z"), Map.of("name", "still-there"))),
                null,
                true
            );
        }
    }

    private static final class DriftingHostConnector implements Connector {
        private int calls;

        @Override
        public String type() {
            return "zabbix";
        }

        @Override
        public ProbeResult probe(SourceContext source) {
            return new ProbeResult(false, "test");
        }

        @Override
        public Page fetch(SourceContext source, String cursor, int limit) {
            calls++;
            if (calls == 1) {
                return new Page(java.util.List.of(hostRecord("1")), "1", false);
            }
            if (calls == 2) {
                return new Page(java.util.List.of(hostRecord("3")), "2", false);
            }
            return new Page(java.util.List.of(), null, true);
        }

        private static RawRecord hostRecord(String hostId) {
            return new RawRecord(hostId, Instant.parse("2026-09-21T12:00:00Z"), Map.of(
                "hostid", hostId,
                "host", "host-" + hostId,
                "name", "host-" + hostId,
                "status", "0"
            ));
        }
    }

    private static String lifecycle(InMemoryInventoryStore store, TenantId tenant, String hostId) {
        var key = new ExternalObjectKey(tenant, "zabbix-1", "host", hostId, "1");
        return store.find(tenant, EntityIds.fromExternal(key)).orElseThrow().lifecycle();
    }

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new AssertionError(reason);
        }
    }
}
