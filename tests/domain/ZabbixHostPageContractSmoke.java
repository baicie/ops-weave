import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcItemConnector;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Protocol fixture: explicit-ID pages, membership changes, boundedness and malformed cursors. */
public final class ZabbixHostPageContractSmoke {
    private static int checks;
    public static void main(String[] args) {
        for (String kind : List.of("host", "item")) {
            var transport = new ScriptedTransport(kind);
            Connector connector = kind.equals("host")
                ? new ZabbixJsonRpcConnector(URI.create("http://127.0.0.1/api_jsonrpc.php"), transport, ref -> "fixture")
                : new ZabbixJsonRpcItemConnector(URI.create("http://127.0.0.1/api_jsonrpc.php"), transport, ref -> "fixture");
            var source = new Connector.SourceContext(new TenantId("tenant-demo"), "zabbix-1", "fixture-secret");
            transport.ids = List.of(84L, 85L, 90L);
            var first = connector.fetch(source, null, 2);
            require(first.records().size() == 2 && !first.snapshotComplete(), "first page is incomplete");
            require(first.nextCursor() != null, "first page carries the manifest digest");
            var second = connector.fetch(source, first.nextCursor(), 2);
            require(second.snapshotComplete() && second.records().size() == 1, "last page completes");
            require(second.records().get(0).externalId().equals("90"), "last page does not repeat first row");
            require(transport.bodies.stream().noneMatch(body -> body.contains("\"offset\"")), "never sends unsupported offset");
            require(transport.bodies.stream().anyMatch(body -> body.contains("\""+kind+"ids\":[90]")), "explicit IDs select next page");

            // Same count and maximum ID are not sufficient: changed membership rejects the cursor.
            transport.ids = List.of(84L, 86L, 90L);
            var changed = connector.fetch(source, first.nextCursor(), 2);
            require(!changed.snapshotComplete() && changed.records().isEmpty(), "same-count replacement is not a snapshot");
            transport.ids = List.of(84L, 85L, 90L);
            transport.wrongPage = true;
            var duplicate = connector.fetch(source, first.nextCursor(), 2);
            require(!duplicate.snapshotComplete() && duplicate.records().isEmpty(), "repeated page rejected before writes");
            transport.wrongPage = false;
            transport.changeAfterPage = true;
            require(!connector.fetch(source, null, 500).snapshotComplete(), "final membership change cannot retire objects");
            transport.changeAfterPage = false;

            transport.ids = List.of();
            require(connector.fetch(source, null, 2).snapshotComplete(), "stable empty membership is verified twice");
            transport.ids = List.of(84L, 84L);
            fails(() -> connector.fetch(source, null, 2), IllegalStateException.class, "duplicate manifest");
            transport.ids = List.of(85L, 84L);
            fails(() -> connector.fetch(source, null, 2), IllegalStateException.class, "unsorted manifest");
            transport.ids = java.util.stream.LongStream.rangeClosed(1, 1001).boxed().toList();
            int requests = transport.bodies.size();
            fails(() -> connector.fetch(source, null, 2), IllegalStateException.class, "manifest over limit");
            require(transport.bodies.size() == requests + 1, "over-limit count rejects before payload read");
            for (String cursor : List.of("999", "1|2|3|4|5", "ids-v1|"+"a".repeat(64)+"|3|3",
                    "ids-v1|"+"a".repeat(64)+"|3|-1", "ids-v1|"+"a".repeat(64)+"|3|0")) {
                requests = transport.bodies.size();
                fails(() -> connector.fetch(source, cursor, 2), IllegalArgumentException.class, "invalid cursor");
                require(transport.bodies.size() == requests, "invalid cursor rejected before I/O");
            }
            transport.ids = List.of(84L);
            transport.fail = true;
            fails(() -> connector.fetch(source, null, 2), IllegalStateException.class, "upstream failure is not empty");
        }
        System.out.println("Zabbix host/item page contract smoke: " + checks + " checks passed");
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
        checks++;
    }
    private static void fails(Runnable action, Class<? extends RuntimeException> type, String reason) {
        try { action.run(); } catch (RuntimeException failure) {
            require(type.isInstance(failure), reason); return;
        }
        throw new AssertionError(reason);
    }
    private static final class ScriptedTransport implements ZabbixJsonRpcConnector.Transport {
        final String kind;
        final List<String> bodies = new ArrayList<>();
        List<Long> ids = List.of();
        boolean wrongPage, changeAfterPage, fail;
        ScriptedTransport(String kind) { this.kind = kind; }
        @Override public String exchange(URI endpoint, String body, String token) {
            bodies.add(body);
            if (fail) throw new IllegalStateException("fixture upstream unavailable");
            return body;
        }
        @Override public long readCount(String body) { return ids.size(); }
        @Override public List<Map<String,Object>> readHostArray(String body) {
            if (body.contains("\"output\":[\""+kind+"id\"]"))
                return ids.stream().map(id -> Map.<String,Object>of(kind+"id", id.toString())).toList();
            String marker = "\""+kind+"ids\":[";
            int start = body.indexOf(marker) + marker.length();
            String selected = body.substring(start, body.indexOf(']', start));
            var rows = new ArrayList<Map<String,Object>>();
            for (String id : selected.split(","))
                rows.add(Map.of(kind+"id", wrongPage ? "84" : id.trim(), "name", "fixture-object"));
            if (changeAfterPage) ids = List.of(84L, 86L, 90L);
            return rows;
        }
    }
}
