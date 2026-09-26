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
 * Zabbix item.get client. The walk is bounded by an itemid watermark and the row count captured before
 * the first request, pages are read ascending inside that watermark, and the walk completes only when
 * the observed rows match the captured bound. Items created afterwards stay outside this snapshot; a
 * removed row shifts later offsets, which the count check reports instead of reconciling a partial view.
 * The result array is item metadata only; history values are never requested.
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
            return new ProbeResult(true, "ok", transport.readText(transport.exchange(endpoint, body, token)));
        } catch (RuntimeException failed) {
            return new ProbeResult(false, "unreachable");
        }
    }

    @Override
    public Page fetch(SourceContext source, String cursor, int limit) {
        String token = secrets.resolve(source.secretRef());
        int bounded = Math.min(Math.max(limit, 1), 500);
        JsonRpcWatermarkBounds bounds = cursor == null || cursor.isBlank()
            ? JsonRpcWatermarkBounds.capture(transport, endpoint, token, "item.get", "itemid")
            : JsonRpcWatermarkBounds.decode(cursor);
        if (bounds.expected() == 0) {
            return new Page(List.of(), null, true, SyncScan.ITEMID_WATERMARK);
        }
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"item.get\",\"params\":{"
            + "\"output\":[\"itemid\",\"key_\",\"name\",\"value_type\",\"units\",\"hostid\"],"
            + "\"sortfield\":\"itemid\","
            + "\"sortorder\":\"ASC\","
            + "\"limit\":" + bounded + ","
            + "\"offset\":" + bounds.offset()
            + "},\"id\":1}";
        String response = transport.exchange(endpoint, body, token);
        List<Map<String, Object>> items = transport.readHostArray(response);
        Instant observedAt = Instant.now();
        List<RawRecord> records = new ArrayList<>();
        long last = 0;
        int inside = 0;
        boolean ordered = true;
        boolean beyond = false;
        for (Map<String, Object> item : items) {
            Object itemId = item.get("itemid");
            if (itemId == null || String.valueOf(itemId).isBlank()) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(String.valueOf(itemId).trim());
            } catch (NumberFormatException invalid) {
                ordered = false;
                continue;
            }
            if (id > bounds.watermark()) {
                beyond = true;
                continue;
            }
            if (id <= bounds.previous() || (last != 0 && id <= last)) {
                ordered = false;
            }
            last = id;
            inside++;
            records.add(new RawRecord(String.valueOf(itemId), observedAt, item));
        }
        long observed = bounds.observed() + inside;
        boolean atEnd = beyond || items.size() < bounded || (last != 0 && last >= bounds.watermark());
        boolean complete = bounds.verified(atEnd, ordered, observed, last);
        String next = atEnd ? null : bounds.encode(bounds.offset() + items.size(), last, observed);
        return new Page(List.copyOf(records), next, complete, SyncScan.ITEMID_WATERMARK);
    }
}
