package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Zabbix item.get client. Pages are an offset scan attempt sorted by itemid.
 * The result array is item metadata only. History values are not requested.
 */
public final class ZabbixJsonRpcItemConnector implements Connector {
    private final URI endpoint;
    private final ZabbixJsonRpcConnector.Transport transport;
    private final ZabbixJsonRpcConnector.SecretSource secrets;

    public ZabbixJsonRpcItemConnector(
        URI endpoint,
        ZabbixJsonRpcConnector.Transport transport,
        ZabbixJsonRpcConnector.SecretSource secrets
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @Override
    public String type() {
        return "zabbix";
    }

    @Override
    public ProbeResult probe(SourceContext source) {
        try {
            String token = secrets.resolve(source.secretRef());
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"apiinfo.version\",\"params\":{},\"id\":1}";
            transport.exchange(endpoint, body, token);
            return new ProbeResult(true, "ok");
        } catch (RuntimeException failed) {
            return new ProbeResult(false, "unreachable");
        }
    }

    @Override
    public Page fetch(SourceContext source, String cursor, int limit) {
        String token = secrets.resolve(source.secretRef());
        int bounded = Math.min(Math.max(limit, 1), 500);
        int offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            offset = Integer.parseInt(cursor);
        }
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"item.get\",\"params\":{"
            + "\"output\":[\"itemid\",\"key_\",\"name\",\"value_type\",\"units\",\"hostid\"],"
            + "\"sortfield\":\"itemid\","
            + "\"sortorder\":\"ASC\","
            + "\"limit\":" + bounded + ","
            + "\"offset\":" + offset
            + "},\"id\":1}";
        String response = transport.exchange(endpoint, body, token);
        List<Map<String, Object>> items = transport.readHostArray(response);
        Instant observedAt = Instant.now();
        List<RawRecord> records = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object itemId = item.get("itemid");
            if (itemId == null || String.valueOf(itemId).isBlank()) {
                continue;
            }
            records.add(new RawRecord(String.valueOf(itemId), observedAt, item));
        }
        boolean complete = items.size() < bounded;
        String next = complete ? null : Integer.toString(offset + items.size());
        return new Page(List.copyOf(records), next, complete);
    }
}
