import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cursor contract for host.get: the walk is bounded by a hostid watermark and the row count captured
 * before the first page, and it is complete only when the observed rows match that captured bound.
 */
public final class ZabbixHostPageContractSmoke {
    public static void main(String[] args) {
        var transport = new ScriptedTransport();
        var connector = new ZabbixJsonRpcConnector(
            URI.create("http://127.0.0.1/api_jsonrpc.php"),
            transport,
            secretRef -> "stub-token"
        );
        var source = new Connector.SourceContext(new TenantId("tenant-demo"), "zabbix-1", "env:OPSWEAVE_ZABBIX_TOKEN");

        transport.watermark = "10085";
        transport.count = 2;
        transport.pages.add(List.of(host("10084"), host("10085")));
        int bound = transport.bodies.size();
        Connector.Page first = connector.fetch(source, null, 2);
        require(first.records().size() == 2, "full page keeps both hosts");
        require(first.snapshotComplete(), "reaching the watermark with the captured row count completes the snapshot");
        require(first.nextCursor() == null, "a verified snapshot has no next cursor");
        require("hostid-watermark-snapshot".equals(first.scanConsistency()), "the walk reports how it was bounded");
        require(transport.bodies.get(bound).contains("\"output\":[\"hostid\"]"), "the watermark request only reads hostids");
        require(transport.bodies.get(bound).contains("\"sortorder\":\"DESC\""), "the watermark is the highest hostid");
        require(transport.bodies.get(bound + 1).contains("\"countOutput\":true"), "the expected row count is captured before the first page");
        require(transport.bodies.get(bound + 2).contains("\"offset\":0"), "first page offset is zero");
        require(transport.bodies.get(bound + 2).contains("\"sortorder\":\"ASC\""), "pages are ordered by hostid ascending");
        require(transport.bodies.get(bound + 2).contains("\"limit\":2"), "requested page size is sent");

        transport.watermark = "10086";
        transport.count = 3;
        transport.pages.add(List.of(host("10084"), host("10085")));
        bound = transport.bodies.size();
        Connector.Page page1 = connector.fetch(source, null, 2);
        require(!page1.snapshotComplete(), "a full page inside the watermark is not a snapshot yet");
        require(page1.nextCursor() != null, "an unfinished bounded walk keeps a cursor");
        transport.pages.add(List.of(host("10086")));
        Connector.Page page2 = connector.fetch(source, page1.nextCursor(), 2);
        require(page2.snapshotComplete(), "the page that reaches the watermark completes the bounded walk");
        require(page2.nextCursor() == null, "complete walk has no next cursor");
        require(transport.bodies.get(bound + 3).contains("\"offset\":2"), "the second page resumes at the API page size");
        require(!transport.bodies.get(bound + 3).contains("countOutput"), "a resumed page does not re-read the bound");

        transport.watermark = "10085";
        transport.count = 2;
        transport.pages.add(List.of(host("10084"), host("10085"), host("10090")));
        Connector.Page outside = connector.fetch(source, null, 3);
        require(outside.records().size() == 2, "a host created after the watermark stays outside this snapshot");
        require(outside.snapshotComplete(), "excluding a newer host still completes the bounded walk");

        transport.watermark = "10087";
        transport.count = 4;
        transport.pages.add(List.of(host("10084"), host("10085")));
        Connector.Page shifted1 = connector.fetch(source, null, 2);
        transport.pages.add(List.of(host("10087")));
        Connector.Page shifted2 = connector.fetch(source, shifted1.nextCursor(), 2);
        require(shifted2.records().size() == 1, "a deleted row shifts the offset so a row is skipped");
        require(!shifted2.snapshotComplete(), "a walk that observed fewer rows than captured is never a snapshot");
        require(shifted2.nextCursor() == null, "a refused walk ends without pretending to continue");

        transport.watermark = null;
        transport.count = 0;
        bound = transport.bodies.size();
        Connector.Page empty = connector.fetch(source, null, 2);
        require(empty.records().isEmpty(), "an empty source has no records");
        require(empty.snapshotComplete(), "an empty source is a verified empty snapshot");
        require(transport.bodies.size() == bound + 2, "an empty snapshot needs only the two bound requests");

        transport.watermark = "10085";
        transport.count = 1;
        transport.pages.add(List.of(new LinkedHashMap<>()));
        Connector.Page dropped = connector.fetch(source, null, 1);
        require(dropped.records().isEmpty(), "host without hostid is not a record");
        require(!dropped.snapshotComplete(), "a page that never saw the watermark is not a snapshot");
        require(dropped.nextCursor() != null, "the walk continues while the watermark row is missing");
        System.out.println("Zabbix host page contract smoke: 24 checks passed");
    }

    private static Map<String, Object> host(String id) {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("hostid", id);
        host.put("name", "host-" + id);
        return host;
    }

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new AssertionError(reason);
        }
    }

    private static final class ScriptedTransport implements ZabbixJsonRpcConnector.Transport {
        private final List<String> bodies = new ArrayList<>();
        private final List<List<Map<String, Object>>> pages = new ArrayList<>();
        private String watermark;
        private long count;
        private int reads;

        @Override
        public String exchange(URI endpoint, String jsonBody, String bearerToken) {
            bodies.add(jsonBody);
            return "scripted";
        }

        @Override
        public List<Map<String, Object>> readHostArray(String responseJson) {
            if (last().contains("countOutput")) {
                throw new IllegalStateException("countOutput is not a host array");
            }
            if (last().contains("\"sortorder\":\"DESC\"")) {
                return watermark == null ? List.of() : List.of(host(watermark));
            }
            return pages.get(reads++);
        }

        @Override
        public long readCount(String responseJson) {
            return count;
        }

        private String last() {
            return bodies.get(bodies.size() - 1);
        }
    }
}
