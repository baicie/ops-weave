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
        require(
            failedRuns.find(tenant, failed.syncRunId()).orElseThrow().failureReason().startsWith("SOURCE_FETCH_FAILED:"),
            "stored failure reason is the stable code"
        );

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
        System.out.println("Zabbix host mapping smoke: 32 checks passed");
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

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new AssertionError(reason);
        }
    }
}
