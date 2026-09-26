import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.domain.MappingDocumentParser;
import com.acme.opsweave.integration.domain.MappingRegistry;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.InMemorySourceItemWrites;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** An item scan owns its source scope: a stale, superseded or blocked scan may never retire bindings. */
public final class ItemScanOwnershipSmoke {
    private static final TenantId TENANT = new TenantId("tenant-item-scan");
    private static final String SOURCE = "zabbix-1";
    private static final SourceScan.Scope SCOPE = new SourceScan.Scope(TENANT, SOURCE, "item");
    private static final Principal PRINCIPAL = new Principal(
        new SubjectId("operator"), TENANT, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide()
    );
    private static int checks = 0;
    private static MappingRegistry mappings;

    public static void main(String[] args) {
        var clock = new MutableClock(Instant.parse("2026-09-26T05:00:00Z"));
        var inventory = new InMemoryInventoryStore(clock);
        var definitions = new InMemoryMetricDefinitionStore();
        seed(definitions, "29999");

        // Another writer holds the scope: this scan must not fetch, write or retire anything.
        var holder = inventory.beginScan(SCOPE, UUID.randomUUID());
        var blocked = ingest(inventory, definitions, new FixtureZabbixItemConnector(), 1).execute(PRINCIPAL, null);
        require(blocked.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE, "a foreign lease blocks the item scan");
        require("SOURCE_SCAN_BUSY".equals(blocked.reasonCode()), "a blocked item scan reports SOURCE_SCAN_BUSY");
        require(blocked.pages() == 0 && blocked.accepted() == 0, "a blocked item scan writes nothing");
        require(lifecycle(definitions, "29999") == MetricLifecycle.ACTIVE, "a blocked item scan never retires");
        require(definitions.findBinding(TENANT, SOURCE, "20001").isEmpty(), "a blocked item scan stores no binding");
        inventory.renewScan(holder);
        require(SourceScan.Code.BUSY == fails(() -> inventory.beginScan(SCOPE, UUID.randomUUID())), "the holder keeps its scope");
        inventory.releaseScan(holder);

        // The lease expires while this scan is paging: it must stop instead of retiring from a stale walk.
        var expiring = new InMemoryInventoryStore(clock);
        var expiringDefinitions = new InMemoryMetricDefinitionStore();
        seed(expiringDefinitions, "29999");
        var lost = ingest(expiring, expiringDefinitions, new ExpiringConnector(clock), 1).execute(PRINCIPAL, null);
        require("SOURCE_SCAN_LOST".equals(lost.reasonCode()), "an expired lease stops the item scan");
        require(lost.pages() == 1 && lost.accepted() == 0, "the expired scan stores nothing after the lease is gone");
        require(expiringDefinitions.findBinding(TENANT, SOURCE, "20001").isEmpty(), "a fenced write refuses an expired lease");
        require(lifecycle(expiringDefinitions, "29999") == MetricLifecycle.ACTIVE, "an expired scan never retires");
        var afterLoss = expiring.beginScan(SCOPE, UUID.randomUUID());
        require(afterLoss.fence() >= 2, "a failed scan releases its scope for the next fence");
        expiring.releaseScan(afterLoss);

        // A newer owner takes the scope mid-walk: the old scan must not write, retire or clear the new lease.
        var takeoverInventory = new InMemoryInventoryStore(clock);
        var takeoverDefinitions = new InMemoryMetricDefinitionStore();
        seed(takeoverDefinitions, "29999");
        SourceScan.Token[] newOwner = new SourceScan.Token[1];
        var superseded = ingest(takeoverInventory, takeoverDefinitions, new TakeoverConnector(clock, takeoverInventory, newOwner), 1)
            .execute(PRINCIPAL, null);
        require("SOURCE_SCAN_LOST".equals(superseded.reasonCode()), "a superseded item scan stops");
        require(lifecycle(takeoverDefinitions, "29999") == MetricLifecycle.ACTIVE, "a superseded scan never retires");
        require(newOwner[0] != null, "the newer owner acquired the scope");
        takeoverInventory.renewScan(newOwner[0]);
        require(SourceScan.Code.BUSY == fails(() -> takeoverInventory.beginScan(SCOPE, UUID.randomUUID())),
            "the superseded scan never clears the new lease");
        require(new InMemorySourceItemWrites(takeoverInventory, takeoverDefinitions).retireMissing(newOwner[0], Set.of("20001")) == 1,
            "the newer owner can still retire from its own lease");
        takeoverInventory.releaseScan(newOwner[0]);

        // A healthy scan retires only what is missing and leaves the scope ready for the next fence.
        var healthyInventory = new InMemoryInventoryStore(clock);
        var healthyDefinitions = new InMemoryMetricDefinitionStore();
        seed(healthyDefinitions, "29999");
        var healthy = ingest(healthyInventory, healthyDefinitions, new FixtureZabbixItemConnector(), 1).execute(PRINCIPAL, null);
        require(healthy.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "a healthy item scan completes");
        require(healthy.retired() == 1, "only the missing item is retired");
        require(lifecycle(healthyDefinitions, "29999") == MetricLifecycle.INACTIVE, "the missing item is retired");
        require(lifecycle(healthyDefinitions, "20001") == MetricLifecycle.ACTIVE, "the observed item stays active");
        var nextFence = healthyInventory.beginScan(SCOPE, UUID.randomUUID());
        require(nextFence.fence() == 2, "a finished scan releases its scope");
        healthyInventory.releaseScan(nextFence);

        // An expired token cannot write or retire even when nobody took the scope over.
        var staleInventory = new InMemoryInventoryStore(clock);
        var staleDefinitions = new InMemoryMetricDefinitionStore();
        seed(staleDefinitions, "29999");
        var stale = staleInventory.beginScan(SCOPE, UUID.randomUUID());
        clock.advance(Duration.ofSeconds(31));
        var writes = new InMemorySourceItemWrites(staleInventory, staleDefinitions);
        require(SourceScan.Code.LOST == fails(() -> writes.retireMissing(stale, Set.of())), "an expired token cannot retire");
        require(SourceScan.Code.LOST == fails(() -> writes.upsert(stale, definition(), binding("20001"))), "an expired token cannot write");
        require(lifecycle(staleDefinitions, "29999") == MetricLifecycle.ACTIVE, "an expired token changes no lifecycle");
        require(staleDefinitions.findBinding(TENANT, SOURCE, "20001").isEmpty(), "an expired token stores no binding");

        System.out.println("ItemScanOwnershipSmoke: " + checks + " checks passed");
    }

    private static IngestZabbixItemsUseCase ingest(
        InMemoryInventoryStore inventory, InMemoryMetricDefinitionStore definitions, Connector connector, int pageSize
    ) {
        return new IngestZabbixItemsUseCase(
            new AuthorizeUseCase(),
            connector,
            mappings(),
            inventory,
            new InMemorySourceItemWrites(inventory, definitions),
            new InMemoryRawRecordStore(),
            new InMemorySyncRunStore(),
            "labeled-fixture",
            "memory",
            SOURCE,
            "env:OPSWEAVE_ZABBIX_TOKEN",
            pageSize
        );
    }

    private static MappingRegistry mappings() {
        if (mappings == null) {
            try {
                mappings = new MappingRegistry(List.of(
                    MappingDocumentParser.parse(Files.readString(Path.of("extensions/mappings/zabbix-cpu-user.yaml")))
                ));
            } catch (java.io.IOException failed) {
                throw new IllegalStateException("Mapping fixture is missing", failed);
            }
        }
        return mappings;
    }

    private static void seed(InMemoryMetricDefinitionStore store, String itemId) {
        store.upsert(definition());
        store.upsert(binding(itemId));
    }

    private static MetricDefinition definition() {
        return new MetricDefinition(TENANT, "stale.metric", "Stale", "1", MetricValueType.DOUBLE, MetricType.GAUGE, List.of("mode"), 1);
    }

    private static MetricBinding binding(String itemId) {
        return new MetricBinding(
            TENANT, "zabbix", SOURCE, itemId,
            EntityIds.fromExternal(new ExternalObjectKey(TENANT, SOURCE, "host", "10084", "1")),
            "10084", "stale.metric", Map.of("mode", "idle"), "%", "identity", 1, MetricLifecycle.ACTIVE, 1
        );
    }

    private static MetricLifecycle lifecycle(InMemoryMetricDefinitionStore store, String itemId) {
        return store.findBinding(TENANT, SOURCE, itemId).orElseThrow().lifecycle();
    }

    private static SourceScan.Code fails(Runnable action) {
        try {
            action.run();
        } catch (SourceScan.Failure expected) {
            checks++;
            return expected.code();
        }
        throw new IllegalStateException("Expected a source scan failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
        checks++;
    }

    /** A lease that expires while the walk is still paging. */
    private static final class ExpiringConnector implements Connector {
        private final Connector delegate = new FixtureZabbixItemConnector();
        private final MutableClock clock;
        private int calls;

        private ExpiringConnector(MutableClock clock) {
            this.clock = clock;
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
            Page page = delegate.fetch(source, cursor, limit);
            if (++calls == 1) {
                clock.advance(Duration.ofSeconds(31));
            }
            return page;
        }
    }

    /** Another writer takes the scope over while this walk is still paging. */
    private static final class TakeoverConnector implements Connector {
        private final Connector delegate = new FixtureZabbixItemConnector();
        private final MutableClock clock;
        private final InMemoryInventoryStore inventory;
        private final SourceScan.Token[] newOwner;
        private int calls;

        private TakeoverConnector(MutableClock clock, InMemoryInventoryStore inventory, SourceScan.Token[] newOwner) {
            this.clock = clock;
            this.inventory = inventory;
            this.newOwner = newOwner;
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
            Page page = delegate.fetch(source, cursor, limit);
            if (++calls == 1) {
                clock.advance(Duration.ofSeconds(31));
                newOwner[0] = inventory.beginScan(SCOPE, UUID.randomUUID());
            }
            return page;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
