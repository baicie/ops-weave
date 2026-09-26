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
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.InMemorySourceItemWrites;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcItemConnector;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An item walk may retire a binding only when its captured itemid watermark and row count both hold. */
public final class ItemScanBoundarySmoke {
    private static final TenantId TENANT = new TenantId("tenant-item-boundary");
    private static final String SOURCE = "zabbix-1";
    private static final Principal PRINCIPAL = new Principal(
        new SubjectId("operator"), TENANT, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide()
    );
    private static int checks = 0;
    private static MappingRegistry mappings;

    public static void main(String[] args) {
        // 1. A walk that reaches the captured itemid watermark with the captured row count is a verified snapshot.
        var transport = new ScriptedTransport();
        var definitions = seeded("29999");
        var runs = new InMemorySyncRunStore();
        transport.script("20002", 2, List.of(item("20001"), item("20002")));
        var verified = ingest(transport, definitions, runs, 2).execute(PRINCIPAL, null);
        require(verified.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "a verified item walk completes");
        require(verified.snapshotComplete(), "a verified item walk reports a complete snapshot");
        require("itemid-watermark-snapshot".equals(verified.scanConsistency()), "the item response names the bound method");
        require(verified.accepted() == 1 && verified.rejected() == 1 && verified.retired() == 1,
            "the mapped item is stored, the unmapped one counted and only the absent binding retires");
        require(lifecycle(definitions, "29999") == MetricLifecycle.INACTIVE, "the absent binding is inactive");
        require(lifecycle(definitions, "20001") == MetricLifecycle.ACTIVE, "the observed item stays active");

        // 2. A row removed during the walk shifts the offsets: the count no longer matches, so nothing retires.
        var shiftedTransport = new ScriptedTransport();
        var shiftedDefinitions = seeded("29999");
        var shiftedRuns = new InMemorySyncRunStore();
        shiftedTransport.script("20004", 4, List.of(item("20001"), item("20002")), List.of(item("20004")));
        var shifted = ingest(shiftedTransport, shiftedDefinitions, shiftedRuns, 2).execute(PRINCIPAL, null);
        require(shifted.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE, "an unverified item walk is unavailable");
        require(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.name().equals(shifted.reasonCode()), "the item failure code names the unverified walk");
        require(!shifted.snapshotComplete(), "an unverified item walk never claims a snapshot");
        require(lifecycle(shiftedDefinitions, "29999") == MetricLifecycle.ACTIVE, "an unverified item walk never retires");
        require(shiftedDefinitions.findBinding(TENANT, SOURCE, "20003").isEmpty(),
            "the binding the shift skipped was never seen");
        require("itemid-watermark-snapshot".equals(shifted.scanConsistency()), "the failed walk keeps its method label");
        var shiftedRun = shiftedRuns.find(TENANT, shifted.syncRunId()).orElseThrow();
        require(shiftedRun.status() == SyncStatus.FAILED, "the unverified item walk is recorded as failed");
        require(shiftedRun.failureReason().equals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.storedReason()),
            "the stored reason is the fixed summary");
        require(shiftedRun.scanConsistency().equals("itemid-watermark-snapshot"), "the stored run keeps the method label");

        // 3. An item created after the watermark stays outside the snapshot and is never stored.
        var newerTransport = new ScriptedTransport();
        var newerDefinitions = seeded("29999");
        newerTransport.script("20002", 2, List.of(item("20001"), item("20002"), item("20009")));
        var bounded = ingest(newerTransport, newerDefinitions, new InMemorySyncRunStore(), 3).execute(PRINCIPAL, null);
        require(bounded.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "a newer item does not break the walk");
        require(newerDefinitions.findBinding(TENANT, SOURCE, "20009").isEmpty(), "an item created after the watermark is not stored");
        require(lifecycle(newerDefinitions, "29999") == MetricLifecycle.INACTIVE, "the verified snapshot still retires the absent binding");

        // 4. A page request that fails is never an empty snapshot and never retires anything.
        var missingPage = new ScriptedTransport();
        var missingDefinitions = seeded("29999");
        missingPage.script("20002", 2);
        var unavailable = ingest(missingPage, missingDefinitions, new InMemorySyncRunStore(), 1).execute(PRINCIPAL, null);
        require(SyncFailureCode.SOURCE_FETCH_FAILED.name().equals(unavailable.reasonCode()), "a failed item page request is a fetch failure");
        require(!unavailable.snapshotComplete(), "a failed item page request never claims a snapshot");
        require(lifecycle(missingDefinitions, "29999") == MetricLifecycle.ACTIVE, "a failed item page request retires nothing");

        // 5. A cursor that does not carry the captured bound is refused instead of guessed.
        var connector = new ZabbixJsonRpcItemConnector(
            URI.create("http://127.0.0.1/api_jsonrpc.php"), new ScriptedTransport(), secretRef -> "stub-token"
        );
        var source = new Connector.SourceContext(TENANT, SOURCE, "env:OPSWEAVE_ZABBIX_TOKEN");
        for (String cursor : List.of("999", "1|2|3", "1|2|3|4|5|6")) {
            try {
                connector.fetch(source, cursor, 1);
                throw new IllegalStateException("Expected a refused cursor");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }

        // 6. An empty source that was bounded is a verified snapshot and may retire.
        var emptyTransport = new ScriptedTransport();
        var emptyDefinitions = seeded("29999");
        emptyTransport.script(null, 0);
        var empty = ingest(emptyTransport, emptyDefinitions, new InMemorySyncRunStore(), 1).execute(PRINCIPAL, null);
        require(empty.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "a bounded empty item source completes");
        require("itemid-watermark-snapshot".equals(empty.scanConsistency()), "an empty item source keeps the bounded label");
        require(empty.retired() == 1, "a verified empty item snapshot retires the absent binding");
        require(lifecycle(emptyDefinitions, "29999") == MetricLifecycle.INACTIVE, "the absent binding is inactive");

        // 7. A connector that claims completion without a proven bound may not retire anything.
        var unprovenDefinitions = seeded("29999");
        var unproven = ingest(UnboundedConnector::new, unprovenDefinitions, new InMemorySyncRunStore(), 1).execute(PRINCIPAL, null);
        require(unproven.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE,
            "an unbounded complete item walk is not accepted");
        require(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.name().equals(unproven.reasonCode()),
            "an unbounded complete item walk reports UNVERIFIED");
        require(unproven.retired() == 0, "an unbounded complete item walk retires nothing");
        require(lifecycle(unprovenDefinitions, "29999") == MetricLifecycle.ACTIVE, "the absent binding stays active");

        System.out.println("ItemScanBoundarySmoke: " + checks + " checks passed");
    }

    private static IngestZabbixItemsUseCase ingest(
        ScriptedTransport transport, InMemoryMetricDefinitionStore definitions, InMemorySyncRunStore runs, int pageSize
    ) {
        return ingest(() -> new ZabbixJsonRpcItemConnector(
            URI.create("http://127.0.0.1/api_jsonrpc.php"), transport, secretRef -> "stub-token"
        ), definitions, runs, pageSize);
    }

    private static IngestZabbixItemsUseCase ingest(
        java.util.function.Supplier<Connector> connector, InMemoryMetricDefinitionStore definitions,
        InMemorySyncRunStore runs, int pageSize
    ) {
        var inventory = new InMemoryInventoryStore();
        return new IngestZabbixItemsUseCase(
            new AuthorizeUseCase(), connector.get(), mappings(), inventory,
            new InMemorySourceItemWrites(inventory, definitions), new InMemoryRawRecordStore(), runs,
            "labeled-fixture", "memory", SOURCE, "env:OPSWEAVE_ZABBIX_TOKEN", pageSize
        );
    }

    /** A connector that reports a complete walk it never bounded. */
    private static final class UnboundedConnector implements Connector {
        @Override public ProbeResult probe(SourceContext source) { return new ProbeResult(false, "test"); }
        @Override public Page fetch(SourceContext source, String cursor, int limit) {
            return new Page(List.of(), null, true);
        }
        @Override public String type() { return "zabbix"; }
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

    /** One absent binding plus the catalog entry the walk is expected to observe. */
    private static InMemoryMetricDefinitionStore seeded(String absentItemId) {
        var store = new InMemoryMetricDefinitionStore();
        store.upsert(new MetricDefinition(TENANT, "stale.metric", "Stale", "1", MetricValueType.DOUBLE, MetricType.GAUGE, List.of("mode"), 1));
        store.upsert(new MetricBinding(
            TENANT, "zabbix", SOURCE, absentItemId,
            EntityIds.fromExternal(new ExternalObjectKey(TENANT, SOURCE, "host", "10084", "1")),
            "10084", "stale.metric", Map.of("mode", "idle"), "%", "identity", 1, MetricLifecycle.ACTIVE, 1
        ));
        return store;
    }

    private static MetricLifecycle lifecycle(InMemoryMetricDefinitionStore store, String itemId) {
        return store.findBinding(TENANT, SOURCE, itemId).orElseThrow().lifecycle();
    }

    private static Map<String, Object> item(String id) {
        boolean mapped = !"20002".equals(id);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemid", id);
        item.put("key_", mapped ? "system.cpu.util[,user]" : "system.cpu.util[,idle]");
        item.put("name", mapped ? "CPU user time" : "CPU idle time");
        item.put("value_type", "0");
        item.put("units", "%");
        item.put("hostid", "10084");
        return item;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
        checks++;
    }

    /** Answers the bound requests from the script and the page requests in order. */
    private static final class ScriptedTransport implements ZabbixJsonRpcConnector.Transport {
        private final List<List<Map<String, Object>>> pages = new ArrayList<>();
        private String watermark;
        private long count;
        private int reads;

        @SafeVarargs
        private final void script(String watermark, long count, List<Map<String, Object>>... pages) {
            this.watermark = watermark;
            this.count = count;
            this.pages.clear();
            this.pages.addAll(List.of(pages));
            this.reads = 0;
        }

        @Override
        public String exchange(URI endpoint, String jsonBody, String bearerToken) {
            return jsonBody;
        }

        @Override
        public List<Map<String, Object>> readHostArray(String responseJson) {
            if (responseJson.contains("countOutput")) {
                throw new IllegalStateException("countOutput is not an item array");
            }
            if (responseJson.contains("\"sortorder\":\"DESC\"")) {
                return watermark == null ? List.of() : List.of(item(watermark));
            }
            if (reads >= pages.size()) {
                throw new IllegalStateException("No scripted page");
            }
            return pages.get(reads++);
        }

        @Override
        public long readCount(String responseJson) {
            return count;
        }
    }
}
