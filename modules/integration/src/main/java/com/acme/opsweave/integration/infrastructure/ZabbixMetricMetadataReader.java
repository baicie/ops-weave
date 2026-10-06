package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.domain.*;
import java.math.BigInteger;
import java.net.URI;
import java.util.*;

/** Two fixed metadata reads plus one bounded host membership check when scoped. */
public final class ZabbixMetricMetadataReader {
    public static final String REQUEST = "{\"jsonrpc\":\"2.0\",\"method\":\"item.get\",\"params\":{\"output\":[\"itemid\",\"hostid\",\"key_\",\"name\",\"units\",\"value_type\"],\"templated\":false,\"sortfield\":\"itemid\",\"sortorder\":\"ASC\",\"limit\":21},\"id\":1}";
    private final MappingRegistry mappings;
    public ZabbixMetricMetadataReader(MappingRegistry mappings) { this.mappings = Objects.requireNonNull(mappings); }
    public SourceMetricDiscovery fixture(com.acme.opsweave.integration.api.Connector.SourceContext context) {
        var page = new FixtureZabbixItemConnector().fetch(context, null, 21);
        var items = project(page.records().stream().map(com.acme.opsweave.integration.api.Connector.RawRecord::payload).toList());
        return new SourceMetricDiscovery(items, true, "LABELED_FIXTURE", "READ_VERIFIED", SourceMetricDiscovery.digest(items));
    }
    public SourceMetricDiscovery read(URI endpoint, ZabbixJsonRpcConnector.Transport transport, String token) {
        return read(endpoint,transport,token,List.of());
    }
    public SourceMetricDiscovery read(URI endpoint, ZabbixJsonRpcConnector.Transport transport, String token,List<String> hostGroupIds) {
        hostGroupIds=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        String request=hostGroupIds.isEmpty()?REQUEST:request(hostGroupIds);
        var first = project(transport.readHostArray(transport.exchange(endpoint, request, token)));
        var second = project(transport.readHostArray(transport.exchange(endpoint, request, token)));
        boolean match = first.equals(second), complete = match && first.size() <= SourceMetricDiscovery.LIMIT;
        var items = first.subList(0, Math.min(first.size(), SourceMetricDiscovery.LIMIT));
        if (!hostGroupIds.isEmpty()) ZabbixHostGroupVerifier.requireMembership(endpoint,transport,token,
            items.stream().map(SourceMetricDiscovery.Item::hostId).distinct().toList(),hostGroupIds);
        return new SourceMetricDiscovery(items, complete, match ? "FIRST_PAGE_MATCH" : "UNVERIFIED",
            complete ? "READ_VERIFIED" : "INCOMPLETE", SourceMetricDiscovery.digest(items));
    }
    private static String request(List<String> hostGroupIds){
        String groups=ZabbixJsonRpcConnector.groupIdsParameter(hostGroupIds);
        return REQUEST.substring(0,REQUEST.length()-"},\"id\":1}".length())+groups+"},\"id\":1}";
    }
    List<SourceMetricDiscovery.Item> project(List<Map<String,Object>> rows) {
        if (rows.size() > 21) throw new IllegalStateException("Metric discovery exceeded page budget");
        var items = new ArrayList<SourceMetricDiscovery.Item>(); BigInteger previous = BigInteger.ZERO;
        for (var row : rows) {
            // Only the requested metadata is projected. Additional upstream content cannot enter the receipt.
            String id = text(row,"itemid"), host = text(row,"hostid"), key = text(row,"key_"), rawType = text(row,"value_type");
            SourceMetricDiscovery.numericId(id); SourceMetricDiscovery.numericId(host);
            if (new BigInteger(id).compareTo(previous) <= 0) throw new IllegalStateException("Invalid metric metadata order");
            previous = new BigInteger(id);
            String type = switch(rawType) { case "0" -> "FLOAT"; case "1" -> "CHARACTER"; case "2" -> "LOG"; case "3" -> "UNSIGNED"; case "4" -> "TEXT"; case "5" -> "BINARY"; default -> "UNKNOWN"; };
            var mapping = mappings.find("zabbix", key).map(SourceMetricDiscovery.Mapping::from).orElse(null);
            items.add(new SourceMetricDiscovery.Item(id, host, key, text(row,"name"), text(row,"units"), type,
                mapping == null ? "NO_MAPPING" : SourceMetricDiscovery.compatible(type, mapping.valueType()) ? "MAPPED" : "TYPE_MISMATCH", mapping));
        }
        return List.copyOf(items);
    }
    private static String text(Map<String,Object> row, String key) {
        Object v = row.get(key);
        if (!(v instanceof String)) throw new IllegalArgumentException("Missing source metadata");
        return (String)v;
    }
}
