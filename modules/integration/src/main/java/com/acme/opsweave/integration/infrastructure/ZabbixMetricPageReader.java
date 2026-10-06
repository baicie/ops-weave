package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.api.Connector;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Six bounded requests at most. Metadata pages share a fixed, private item membership. */
public final class ZabbixMetricPageReader {
    private final ZabbixMetricMetadataReader metadata;
    public ZabbixMetricPageReader(MappingRegistry mappings) { metadata = new ZabbixMetricMetadataReader(mappings); }
    private static String request(String params) { return request(params,List.of()); }
    private static String request(String params,List<String> hostGroupIds) { return "{\"jsonrpc\":\"2.0\",\"method\":\"item.get\",\"params\":{" + params + ",\"templated\":false"+ZabbixJsonRpcConnector.groupIdsParameter(hostGroupIds)+"},\"id\":1}"; }
    public static final String COUNT = request("\"countOutput\":true");
    public static final String IDS = request("\"output\":[\"itemid\"],\"sortfield\":\"itemid\",\"sortorder\":\"ASC\",\"limit\":1001");
    private List<String> membership(URI uri, ZabbixJsonRpcConnector.Transport t, String token,List<String> hostGroupIds) {
        long count = t.readCount(t.exchange(uri, request("\"countOutput\":true",hostGroupIds), token));
        if (count > SourceMetricPage.CAPACITY) throw new Capacity();
        if (count < 0) throw new IllegalArgumentException("Invalid source membership count");
        var rows = t.readHostArray(t.exchange(uri, request("\"output\":[\"itemid\"],\"sortfield\":\"itemid\",\"sortorder\":\"ASC\",\"limit\":1001",hostGroupIds), token));
        if (rows.size() != count || rows.size() > SourceMetricPage.CAPACITY) throw new IllegalArgumentException("Metric membership changed or exceeded budget");
        var ids = new ArrayList<String>(); BigInteger previous = BigInteger.ZERO;
        for (var row : rows) {
            if (!(row.get("itemid") instanceof String id)) throw new IllegalArgumentException("Missing source item ID");
            SourceMetricDiscovery.numericId(id); var n = new BigInteger(id);
            if (n.compareTo(previous) <= 0) throw new IllegalArgumentException("Invalid source membership order");
            previous = n; ids.add(id);
        }
        return List.copyOf(ids);
    }
    public SourceMetricPage read(URI uri, ZabbixJsonRpcConnector.Transport t, String token, UUID root, Instant asOf, SourceMetricPage previous) {
        return read(uri,t,token,root,asOf,previous,List.of());
    }
    public SourceMetricPage read(URI uri, ZabbixJsonRpcConnector.Transport t, String token, UUID root, Instant asOf, SourceMetricPage previous,List<String> hostGroupIds) {
        hostGroupIds=com.acme.opsweave.integration.domain.SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        var m = previous == null ? null : previous.manifest(); int offset = previous == null ? 0 : Objects.requireNonNull(previous.nextOffset());
        try {
            var ids = membership(uri,t,token,hostGroupIds);
            if (m == null) m = SourceMetricPage.Manifest.capture(root,asOf,ids);
            else if (!m.itemIds().equals(ids)) return SourceMetricPage.failed(m,offset,"MEMBERSHIP_CHANGED");
            var selected = ids.subList(offset,Math.min(offset+SourceMetricPage.LIMIT,ids.size()));
            List<SourceMetricDiscovery.Item> items = List.of();
            if (!selected.isEmpty()) {
                String body = request("\"output\":[\"itemid\",\"hostid\",\"key_\",\"name\",\"units\",\"value_type\"],\"itemids\":[\"" + String.join("\",\"",selected) + "\"],\"sortfield\":\"itemid\",\"sortorder\":\"ASC\",\"limit\":"+selected.size(),hostGroupIds);
                items = metadata.project(t.readHostArray(t.exchange(uri,body,token)));
                if (!items.stream().map(SourceMetricDiscovery.Item::itemId).toList().equals(selected)) throw new IllegalArgumentException("Metric metadata membership mismatch");
                if (!hostGroupIds.isEmpty()) ZabbixHostGroupVerifier.requireMembership(uri,t,token,
                    items.stream().map(SourceMetricDiscovery.Item::hostId).distinct().toList(),hostGroupIds);
            }
            if (!ids.equals(membership(uri,t,token,hostGroupIds))) return SourceMetricPage.failed(m,offset,"MEMBERSHIP_CHANGED");
            return SourceMetricPage.verified(m,offset,items,"ITEMID_WATERMARK");
        } catch (Capacity exceeded) { return SourceMetricPage.failed(m,offset,"CAPACITY"); }
    }
    public SourceMetricPage fixture(Connector.SourceContext context, UUID root, Instant asOf, SourceMetricPage previous) {
        var items = metadata.fixture(context).items();
        var m = SourceMetricPage.Manifest.capture(root,asOf,items.stream().map(SourceMetricDiscovery.Item::itemId).toList());
        if (previous != null) throw new IllegalArgumentException("Fixture inventory has no next page");
        return SourceMetricPage.verified(m,0,items,"LABELED_FIXTURE");
    }
    private static final class Capacity extends RuntimeException {}
}
