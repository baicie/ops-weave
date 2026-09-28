package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.SyncScan;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Zabbix item.get client using a bounded identity manifest and explicit ID batches.
 * Membership must remain unchanged through the final page; failures never authorize reconciliation.
 * Mutable fields can change during this walk. No unsupported offset parameter is sent.
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
            secrets.resolve(source.secretRef());
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"apiinfo.version\",\"params\":{},\"id\":1}";
            return new ProbeResult(true, "ok", transport.readText(transport.exchange(endpoint, body, null)));
        } catch (RuntimeException failed) {
            return new ProbeResult(false, "unreachable");
        }
    }

    @Override
    public Page fetch(SourceContext source, String cursor, int limit) {
        var position = JsonRpcWatermarkBounds.decode(cursor);
        String token = secrets.resolve(source.secretRef());
        var manifest = JsonRpcWatermarkBounds.capture(transport, endpoint, token, "item.get", "itemid");
        if (position != null && !position.matches(manifest))
            return new Page(List.of(), null, false, SyncScan.ITEMID_WATERMARK);
        int start = position == null ? 0 : position.position();
        int end = Math.min(start + Math.min(Math.max(limit, 1), 500), manifest.ids().size());
        var selected = manifest.ids().subList(start, end);
        List<Map<String, Object>> rows = List.of();
        if (!selected.isEmpty()) {
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"item.get\",\"params\":{"
                + "\"output\":[\"itemid\",\"key_\",\"name\",\"value_type\",\"units\",\"hostid\"],"
                + "\"itemids\":" + selected + ",\"sortfield\":\"itemid\",\"sortorder\":\"ASC\","
                + "\"limit\":" + selected.size() + "},\"id\":1}";
            rows = transport.readHostArray(transport.exchange(endpoint, body, token));
            var returned = rows.stream().map(row -> JsonRpcWatermarkBounds.id(row, "itemid")).toList();
            if (!selected.equals(returned)) return new Page(List.of(), null, false, SyncScan.ITEMID_WATERMARK);
        }
        boolean complete = end == manifest.ids().size();
        if (complete && !manifest.equals(JsonRpcWatermarkBounds.capture(transport, endpoint, token, "item.get", "itemid")))
            return new Page(List.of(), null, false, SyncScan.ITEMID_WATERMARK);
        Instant observedAt = Instant.now();
        List<RawRecord> records = new ArrayList<>();
        for (Map<String, Object> row : rows)
            records.add(new RawRecord(String.valueOf(row.get("itemid")), observedAt, row));
        return new Page(List.copyOf(records), complete ? null : manifest.cursor(end), complete, SyncScan.ITEMID_WATERMARK);
    }

}
