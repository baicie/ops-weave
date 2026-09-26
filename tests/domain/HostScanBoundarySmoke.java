import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.integration.domain.ZabbixHostMapper;
import com.acme.opsweave.integration.infrastructure.InMemoryPipelineVersionStore;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A host walk may retire an absent host only when its captured watermark and row count both hold. */
public final class HostScanBoundarySmoke {
    private static final TenantId TENANT = new TenantId("tenant-host-boundary");
    private static final String SOURCE = "zabbix-1";
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final Principal PRINCIPAL = new Principal(
        new SubjectId("operator"), TENANT, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide()
    );
    private static int checks = 0;

    public static void main(String[] args) {
        // 1. A walk that reaches the captured watermark with the captured row count is a verified snapshot.
        var transport = new ScriptedTransport();
        var inventory = seeded("999");
        var runs = new InMemorySyncRunStore();
        transport.script("10085", 2, List.of(host("10084"), host("10085")));
        var verified = ingest(transport, inventory, runs, 2).execute(PRINCIPAL, null);
        require(verified.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "a verified walk completes");
        require(verified.snapshotComplete(), "a verified walk reports a complete snapshot");
        require("hostid-watermark-snapshot".equals(verified.scanConsistency()), "the response names the bound method");
        require(verified.accepted() == 2 && verified.retired() == 1, "both hosts are stored and the absent host retires");
        require("INACTIVE".equals(lifecycle(inventory, "999")), "the absent host is inactive after a verified snapshot");
        require("ACTIVE".equals(lifecycle(inventory, "10084")), "an observed host stays active");

        // 2. A row removed during the walk shifts the offsets: the count no longer matches, so nothing retires.
        var shiftedTransport = new ScriptedTransport();
        var shiftedInventory = seeded("999");
        var shiftedRuns = new InMemorySyncRunStore();
        shiftedTransport.script("10087", 4, List.of(host("10084"), host("10085")), List.of(host("10087")));
        var shifted = ingest(shiftedTransport, shiftedInventory, shiftedRuns, 2).execute(PRINCIPAL, null);
        require(shifted.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE, "an unverified walk is unavailable");
        require(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.name().equals(shifted.reasonCode()), "the failure code names the unverified walk");
        require(!shifted.snapshotComplete(), "an unverified walk never claims a snapshot");
        require("ACTIVE".equals(lifecycle(shiftedInventory, "999")), "an unverified walk never retires");
        require(shifted.pages() == 2 && shifted.fetched() == 3 && shifted.accepted() == 3,
            "the rows the walk actually read stay stored without a partial rollback");
        require(shiftedInventory.find(TENANT, hostId("10086")).isEmpty(), "the row the shift skipped was never seen");
        var shiftedRun = shiftedRuns.find(TENANT, shifted.syncRunId()).orElseThrow();
        require(shiftedRun.status() == SyncStatus.FAILED, "the unverified walk is recorded as failed");
        require(shiftedRun.failureReason().equals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.storedReason()),
            "the stored reason is the fixed summary");

        // 3. A host created after the watermark stays outside the snapshot and is never stored.
        var newerTransport = new ScriptedTransport();
        var newerInventory = seeded("999");
        newerTransport.script("10085", 2, List.of(host("10084"), host("10085"), host("10090")));
        var bounded = ingest(newerTransport, newerInventory, new InMemorySyncRunStore(), 3).execute(PRINCIPAL, null);
        require(bounded.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "a newer host does not break the walk");
        require(bounded.accepted() == 2, "only the hosts inside the watermark are stored");
        require(newerInventory.find(TENANT, hostId("10090")).isEmpty(), "a host created after the watermark is not written");
        require("INACTIVE".equals(lifecycle(newerInventory, "999")), "the verified snapshot still retires the absent host");

        // 4. A page request that fails is never an empty snapshot and never retires anything.
        var missingPage = new ScriptedTransport();
        var missingPageInventory = seeded("999");
        missingPage.script("10085", 2);
        var unavailable = ingest(missingPage, missingPageInventory, new InMemorySyncRunStore(), 1).execute(PRINCIPAL, null);
        require(unavailable.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE, "a failed page request is unavailable");
        require(SyncFailureCode.SOURCE_FETCH_FAILED.name().equals(unavailable.reasonCode()), "a failed page request is a fetch failure");
        require(!unavailable.snapshotComplete(), "a failed page request never claims a snapshot");
        require("ACTIVE".equals(lifecycle(missingPageInventory, "999")), "a failed page request retires nothing");

        // 5. A cursor that does not carry the captured bound is refused instead of guessed.
        var connector = new ZabbixJsonRpcConnector(
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
        var emptyInventory = seeded("999");
        emptyTransport.script(null, 0);
        var empty = ingest(emptyTransport, emptyInventory, new InMemorySyncRunStore(), 1).execute(PRINCIPAL, null);
        require(empty.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, "a bounded empty source completes");
        require("hostid-watermark-snapshot".equals(empty.scanConsistency()), "an empty source keeps the bounded label");
        require(empty.retired() == 1, "a verified empty snapshot retires the absent host");
        require("INACTIVE".equals(lifecycle(emptyInventory, "999")), "the absent host is inactive after a verified empty snapshot");

        // 7. A connector that claims completion without a proven bound may not retire anything.
        var unprovenInventory = seeded("999");
        var unproven = ingest(UnboundedConnector::new, unprovenInventory, new InMemorySyncRunStore(), 1).execute(PRINCIPAL, null);
        require(unproven.kind() == IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE,
            "an unbounded complete walk is not accepted");
        require(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.name().equals(unproven.reasonCode()),
            "an unbounded complete walk reports UNVERIFIED");
        require(unproven.retired() == 0, "an unbounded complete walk retires nothing");
        require("ACTIVE".equals(lifecycle(unprovenInventory, "999")), "the absent host stays active");

        System.out.println("HostScanBoundarySmoke: " + checks + " checks passed");
    }

    private static IngestZabbixHostsUseCase ingest(
        ScriptedTransport transport, InMemoryInventoryStore inventory, InMemorySyncRunStore runs, int pageSize
    ) {
        return ingest(() -> new ZabbixJsonRpcConnector(
            URI.create("http://127.0.0.1/api_jsonrpc.php"), transport, secretRef -> "stub-token"
        ), inventory, runs, pageSize);
    }

    private static IngestZabbixHostsUseCase ingest(
        java.util.function.Supplier<Connector> connector, InMemoryInventoryStore inventory, InMemorySyncRunStore runs, int pageSize
    ) {
        return new IngestZabbixHostsUseCase(
            new AuthorizeUseCase(), connector.get(), inventory, new InMemoryRawRecordStore(), runs,
            new InMemoryPipelineVersionStore(), "labeled-fixture", "memory", SOURCE, "env:OPSWEAVE_ZABBIX_TOKEN", pageSize
        );
    }

    /** A connector that reports a complete walk it never bounded. */
    private static final class UnboundedConnector implements Connector {
        @Override public String type() { return "zabbix"; }
        @Override public ProbeResult probe(SourceContext source) { return new ProbeResult(false, "test"); }
        @Override public Page fetch(SourceContext source, String cursor, int limit) {
            return new Page(List.of(), null, true);
        }
    }

    /** One absent host plus the host the walk is expected to observe. */
    private static InMemoryInventoryStore seeded(String absentHostId) {
        var inventory = new InMemoryInventoryStore();
        var key = new ExternalObjectKey(TENANT, SOURCE, "host", absentHostId, "1");
        var id = EntityIds.fromExternal(key);
        inventory.upsert(
            new com.acme.opsweave.inventory.domain.Entity(id, TENANT, "host", "gone-" + absentHostId, Lifecycle.ACTIVE, 1, NOW, Map.of("hostId", absentHostId)),
            new com.acme.opsweave.inventory.domain.Observation("obs-" + absentHostId, key, id, NOW, NOW, Map.of("hostId", absentHostId), "raw-" + absentHostId, 1),
            new ExternalLink(id, key)
        );
        return inventory;
    }

    private static EntityId hostId(String externalId) {
        return EntityIds.fromExternal(new ExternalObjectKey(TENANT, SOURCE, "host", externalId, "1"));
    }

    private static String lifecycle(InMemoryInventoryStore inventory, String externalId) {
        return inventory.find(TENANT, hostId(externalId)).orElseThrow().lifecycle();
    }

    private static Map<String, Object> host(String id) {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("hostid", id);
        host.put("host", "host-" + id);
        host.put("name", "host-" + id);
        host.put("status", "0");
        host.put("interfaces", List.of(Map.of("ip", "10.0.0.1", "main", "1", "type", "1")));
        return host;
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
                throw new IllegalStateException("countOutput is not a host array");
            }
            if (responseJson.contains("\"sortorder\":\"DESC\"")) {
                return watermark == null ? List.of() : List.of(host(watermark));
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
