import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cursor contract for host.get: sorted offset pages, complete only when the API page is short. */
public final class ZabbixHostPageContractSmoke {
    public static void main(String[] args) {
        var transport = new ScriptedTransport();
        var connector = new ZabbixJsonRpcConnector(
            URI.create("http://127.0.0.1/api_jsonrpc.php"),
            transport,
            secretRef -> "stub-token"
        );
        var source = new Connector.SourceContext(new TenantId("tenant-demo"), "zabbix-1", "env:OPSWEAVE_ZABBIX_TOKEN");
        transport.pages.add(List.of(host("10084"), host("10085")));
        Connector.Page first = connector.fetch(source, null, 2);
        require(first.records().size() == 2, "full page keeps both hosts");
        require(!first.snapshotComplete(), "full page is not a complete snapshot");
        require("2".equals(first.nextCursor()), "cursor advances by the API page size");
        require(transport.bodies.get(0).contains("\"sortfield\":\"hostid\""), "pages are ordered by hostid");
        require(transport.bodies.get(0).contains("\"sortorder\":\"ASC\""), "hostid order is ascending");
        require(transport.bodies.get(0).contains("\"offset\":0"), "first page offset is zero");
        require(transport.bodies.get(0).contains("\"limit\":2"), "requested page size is sent");

        transport.pages.add(List.of(host("10086")));
        Connector.Page second = connector.fetch(source, first.nextCursor(), 2);
        require(second.snapshotComplete(), "short page completes the snapshot");
        require(second.nextCursor() == null, "complete snapshot has no next cursor");
        require(transport.bodies.get(1).contains("\"offset\":2"), "second page uses the previous cursor");

        transport.pages.add(List.of());
        Connector.Page empty = connector.fetch(source, null, 2);
        require(empty.snapshotComplete(), "empty page is a complete snapshot");
        require(empty.records().isEmpty(), "empty page has no records");

        transport.pages.add(List.of(new LinkedHashMap<>()));
        Connector.Page dropped = connector.fetch(source, null, 1);
        require(dropped.records().isEmpty(), "host without hostid is not a record");
        require(!dropped.snapshotComplete(), "a full API page is incomplete even if every hostid is missing");
        require("1".equals(dropped.nextCursor()), "cursor still advances by the API result size");
        System.out.println("Zabbix host page contract smoke: 15 checks passed");
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
        private int reads;

        @Override
        public String exchange(URI endpoint, String jsonBody, String bearerToken) {
            bodies.add(jsonBody);
            return "scripted";
        }

        @Override
        public List<Map<String, Object>> readHostArray(String responseJson) {
            return pages.get(reads++);
        }
    }
}
