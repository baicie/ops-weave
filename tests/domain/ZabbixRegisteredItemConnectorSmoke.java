import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.RegisteredItemConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixRegisteredItemConnector;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Protocol checks for group-scoped registered item discovery and drift rejection. */
public final class ZabbixRegisteredItemConnectorSmoke {
    private static int checks;

    public static void main(String[] args) {
        fails(() -> new ZabbixRegisteredItemConnector(endpoint(), new ScriptedTransport(), ref -> "fixture", List.of()),
            IllegalArgumentException.class, "empty host group scope is rejected");

        var transport = new ScriptedTransport();
        var connector = connector(transport);
        var source = new Connector.SourceContext(new TenantId("tenant-demo"), "registered-zabbix", "fixture-ref");
        var first = connector.fetchScoped(source, null, 1);
        require(!first.page().snapshotComplete() && first.verifiedHostExternalIds().isEmpty(),
            "partial page does not expose a retirement scope");
        require(first.page().records().size() == 1, "first page reads one item");
        require(first.page().nextCursor() != null, "cursor pins both manifests");
        var second = connector.fetchScoped(source, first.page().nextCursor(), 1);
        require(second.page().snapshotComplete(), "stable final page completes");
        require(second.page().records().getFirst().externalId().equals("8102"), "second page advances by item identity");
        require(second.verifiedHostExternalIds().equals(Set.of("8001", "8002")), "final page exposes exact verified host scope");
        require(transport.bodies.stream().anyMatch(body -> body.contains("\"groupids\":[\"71\"]")),
            "host manifest is filtered by configured groups");
        require(transport.bodies.stream().filter(body -> body.contains("\"output\":[\"itemid\",\"hostid\"]"))
            .allMatch(body -> body.replace(" ", "").contains("\"hostids\":[8001,8002]")),
            "item manifest uses only explicit host ids");
        require(transport.bodies.stream().filter(body -> body.contains("\"itemids\":"))
            .allMatch(body -> body.replace(" ", "").contains("\"hostids\":[8001,8002]")),
            "item metadata pages require both item and host ids");
        require(transport.bodies.stream().noneMatch(body -> body.contains("\"offset\"")), "protocol does not use offset pagination");

        var cursorDrift = new ScriptedTransport();
        var cursorConnector = connector(cursorDrift);
        var cursorPage = cursorConnector.fetchScoped(source, null, 1);
        cursorDrift.hosts = List.of(new Host(8001, true), new Host(8003, true));
        var driftedCursor = cursorConnector.fetchScoped(source, cursorPage.page().nextCursor(), 1);
        require(!driftedCursor.page().snapshotComplete() && driftedCursor.page().records().isEmpty(),
            "cursor cannot continue against changed host membership");
        require(driftedCursor.verifiedHostExternalIds().isEmpty(), "cursor manifest drift exposes no host scope");

        var outsideManifest = new ScriptedTransport();
        outsideManifest.ignoreItemHostFilter = true;
        outsideManifest.items = List.of(new Item(8101, 9999), new Item(8102, 8002));
        fails(() -> connector(outsideManifest).fetchScoped(source, null, 1), IllegalStateException.class,
            "manifest item outside host scope is rejected");

        var outsidePage = new ScriptedTransport();
        outsidePage.pageHostOverride = 9999L;
        var rejectedPage = connector(outsidePage).fetchScoped(source, null, 1);
        require(!rejectedPage.page().snapshotComplete() && rejectedPage.page().records().isEmpty(),
            "metadata row outside host scope cannot be written");
        require(rejectedPage.verifiedHostExternalIds().isEmpty(), "rejected metadata page exposes no retirement scope");

        var wrongItem = new ScriptedTransport();
        wrongItem.pageItemOverride = 9998L;
        var wrongItemPage = connector(wrongItem).fetchScoped(source, null, 1);
        require(!wrongItemPage.page().snapshotComplete() && wrongItemPage.page().records().isEmpty(),
            "metadata response outside requested item ids is rejected");

        var outsideGroup = new ScriptedTransport();
        outsideGroup.hosts = List.of(new Host(8001, false), new Host(8002, true));
        fails(() -> connector(outsideGroup).fetchScoped(source, null, 1), IllegalStateException.class,
            "host manifest row outside selected groups is rejected");

        var hostDrift = new ScriptedTransport();
        hostDrift.addHostAfterMetadataPage = true;
        var changedHosts = connector(hostDrift).fetchScoped(source, null, 1);
        require(!changedHosts.page().snapshotComplete() && changedHosts.page().records().isEmpty(),
            "host membership drift during a page returns incomplete");
        require(changedHosts.verifiedHostExternalIds().isEmpty(), "host drift cannot authorize retirement");

        var itemDrift = new ScriptedTransport();
        itemDrift.replaceItemAfterMetadataPage = true;
        var changedItems = connector(itemDrift).fetchScoped(source, null, 1);
        require(!changedItems.page().snapshotComplete() && changedItems.page().records().isEmpty(),
            "item membership or owner drift during a page returns incomplete");

        var ownerDrift = new ScriptedTransport();
        ownerDrift.changeItemOwnerAfterMetadataPage = true;
        var changedOwner = connector(ownerDrift).fetchScoped(source, null, 1);
        require(!changedOwner.page().snapshotComplete() && changedOwner.page().records().isEmpty(),
            "item reassignment within the host scope changes the pinned manifest");

        var emptyHosts = new ScriptedTransport();
        emptyHosts.hosts = List.of();
        var emptyHostResult = connector(emptyHosts).fetchScoped(source, null, 1);
        require(!emptyHostResult.page().snapshotComplete() && emptyHostResult.verifiedHostExternalIds().isEmpty(),
            "empty host manifest is never an empty retirement snapshot");
        require(emptyHosts.bodies.stream().noneMatch(body -> body.contains("\"method\":\"item.get\"")),
            "empty host scope stops before item discovery");

        var emptyItems = new ScriptedTransport();
        emptyItems.items = List.of();
        var emptyItemResult = connector(emptyItems).fetchScoped(source, null, 1);
        require(!emptyItemResult.page().snapshotComplete(), "empty item manifest cannot complete a scoped scan");
        require(emptyItemResult.verifiedHostExternalIds().isEmpty(),
            "empty item scan cannot authorize retirement of the host cohort");

        int requests = transport.bodies.size();
        fails(() -> connector(transport).fetchScoped(source, "bad-cursor", 1), IllegalArgumentException.class,
            "invalid cursor is rejected");
        require(transport.bodies.size() == requests, "invalid cursor is rejected before source I/O");

        System.out.println("Zabbix registered item connector smoke: " + checks + " checks passed");
    }

    private static ZabbixRegisteredItemConnector connector(ScriptedTransport transport) {
        return new ZabbixRegisteredItemConnector(endpoint(), transport, ref -> "fixture-token", List.of("71"));
    }

    private static URI endpoint() {
        return URI.create("http://127.0.0.1/api_jsonrpc.php");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }

    private static void fails(Runnable action, Class<? extends RuntimeException> type, String reason) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            require(type.isInstance(failure), reason);
            return;
        }
        throw new AssertionError(reason);
    }

    private record Host(long id, boolean inGroup) { }
    private record Item(long id, long hostId) { }

    private static final class ScriptedTransport implements ZabbixJsonRpcConnector.Transport {
        final List<String> bodies = new ArrayList<>();
        List<Host> hosts = List.of(new Host(8001, true), new Host(8002, true));
        List<Item> items = List.of(new Item(8101, 8001), new Item(8102, 8002));
        Long pageHostOverride;
        Long pageItemOverride;
        boolean addHostAfterMetadataPage;
        boolean replaceItemAfterMetadataPage;
        boolean changeItemOwnerAfterMetadataPage;
        boolean ignoreItemHostFilter;

        @Override
        public String exchange(URI endpoint, String jsonBody, String bearerToken) {
            bodies.add(jsonBody);
            return jsonBody;
        }

        @Override
        public long readCount(String request) {
            if (request.contains("\"method\":\"host.get\"")) {
                return hosts.stream().filter(Host::inGroup).count();
            }
            Set<Long> hostIds = ids(request, "hostids");
            return items.stream().filter(item -> ignoreItemHostFilter || hostIds.contains(item.hostId())).count();
        }

        @Override
        public List<Map<String, Object>> readHostArray(String request) {
            if (request.contains("\"method\":\"host.get\"")) {
                return hosts.stream().map(host -> Map.<String, Object>of(
                    "hostid", Long.toString(host.id()),
                    "groups", host.inGroup() ? List.of(Map.of("groupid", "71")) : List.of()
                )).toList();
            }
            Set<Long> hostIds = ids(request, "hostids");
            if (request.contains("\"output\":[\"itemid\",\"hostid\"]")) {
                return items.stream().filter(item -> ignoreItemHostFilter || hostIds.contains(item.hostId()))
                    .sorted((a, b) -> Long.compare(a.id(), b.id()))
                    .map(item -> Map.<String, Object>of("itemid", Long.toString(item.id()), "hostid", Long.toString(item.hostId())))
                    .toList();
            }
            Set<Long> itemIds = ids(request, "itemids");
            List<Map<String, Object>> rows = items.stream().filter(item -> itemIds.contains(item.id()))
                .sorted((a, b) -> Long.compare(a.id(), b.id())).map(item -> Map.<String, Object>of(
                    "itemid", Long.toString(pageItemOverride == null ? item.id() : pageItemOverride),
                    "key_", "system.cpu.util[,user]",
                    "name", "CPU user time",
                    "value_type", "0",
                    "units", "%",
                    "hostid", Long.toString(pageHostOverride == null ? item.hostId() : pageHostOverride)
                )).toList();
            if (request.contains("\"itemids\":[") && addHostAfterMetadataPage) {
                hosts = List.of(new Host(8001, true), new Host(8002, true), new Host(8003, true));
                addHostAfterMetadataPage = false;
            }
            if (request.contains("\"itemids\":[") && replaceItemAfterMetadataPage) {
                items = List.of(new Item(8101, 8001), new Item(8103, 8002));
                replaceItemAfterMetadataPage = false;
            }
            if (request.contains("\"itemids\":[") && changeItemOwnerAfterMetadataPage) {
                items = List.of(new Item(8101, 8002), new Item(8102, 8002));
                changeItemOwnerAfterMetadataPage = false;
            }
            return rows;
        }

        private static Set<Long> ids(String request, String key) {
            String marker = "\"" + key + "\":[";
            int start = request.indexOf(marker);
            if (start < 0) return Set.of();
            start += marker.length();
            int end = request.indexOf(']', start);
            if (end < 0 || end == start) return Set.of();
            var result = new java.util.LinkedHashSet<Long>();
            for (String value : request.substring(start, end).split(",")) {
                result.add(Long.parseLong(value.replace("\"", "").trim()));
            }
            return Set.copyOf(result);
        }
    }
}
