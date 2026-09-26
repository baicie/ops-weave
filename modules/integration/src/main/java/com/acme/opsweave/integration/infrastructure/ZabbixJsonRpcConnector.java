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
 * Zabbix JSON-RPC host.get client. Transport is injected so this module stays free of HTTP JSON libraries.
 * The walk is bounded before the first request: the highest hostid and the row count are captured, pages
 * are read ascending inside that watermark, and the walk only completes when the observed rows match the
 * captured count and the highest hostid was seen. Hosts created afterwards stay outside this snapshot;
 * a removed row shifts later offsets, which the count check reports instead of reconciling a partial view.
 * The cursor carries the bound so a resumed page cannot silently drop it. Transport failures propagate
 * and are never a complete scan.
 */
public final class ZabbixJsonRpcConnector implements Connector {
    private final URI endpoint;
    private final Transport transport;
    private final SecretSource secrets;

    public ZabbixJsonRpcConnector(URI endpoint, Transport transport, SecretSource secrets) {
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
            ? JsonRpcWatermarkBounds.capture(transport, endpoint, token, "host.get", "hostid")
            : JsonRpcWatermarkBounds.decode(cursor);
        if (bounds.expected() == 0) {
            // Nothing existed when the watermark was captured: a verified empty snapshot needs no page request.
            return new Page(List.of(), null, true, SyncScan.HOSTID_WATERMARK);
        }
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"host.get\",\"params\":{"
            + "\"output\":[\"hostid\",\"host\",\"name\",\"status\"],"
            + "\"selectInterfaces\":[\"ip\",\"main\",\"type\"],"
            + "\"sortfield\":\"hostid\","
            + "\"sortorder\":\"ASC\","
            + "\"limit\":" + bounded + ","
            + "\"offset\":" + bounds.offset()
            + "},\"id\":1}";
        String response = transport.exchange(endpoint, body, token);
        List<Map<String, Object>> hosts = transport.readHostArray(response);
        Instant observedAt = Instant.now();
        List<RawRecord> records = new ArrayList<>();
        long last = 0;
        int inside = 0;
        boolean ordered = true;
        boolean beyond = false;
        for (Map<String, Object> host : hosts) {
            Object hostId = host.get("hostid");
            if (hostId == null) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(String.valueOf(hostId).trim());
            } catch (NumberFormatException invalid) {
                ordered = false;
                continue;
            }
            if (id > bounds.watermark()) {
                // Created after the watermark was captured: outside this snapshot.
                beyond = true;
                continue;
            }
            if (id <= bounds.previous() || (last != 0 && id <= last)) {
                ordered = false;
            }
            last = id;
            inside++;
            records.add(new RawRecord(String.valueOf(hostId), observedAt, host));
        }
        long observed = bounds.observed() + inside;
        boolean atEnd = beyond || hosts.size() < bounded || (last != 0 && last >= bounds.watermark());
        boolean complete = bounds.verified(atEnd, ordered, observed, last);
        String next = atEnd ? null : bounds.encode(bounds.offset() + hosts.size(), last, observed);
        return new Page(List.copyOf(records), next, complete, SyncScan.HOSTID_WATERMARK);
    }

    public interface Transport {
        String exchange(URI endpoint, String jsonBody, String bearerToken);

        List<Map<String, Object>> readHostArray(String responseJson);

        /** Reads a JSON-RPC {@code countOutput} result. Transports that never count may not support it. */
        default long readCount(String responseJson) {
            throw new IllegalStateException("Zabbix JSON-RPC count is not supported by this transport");
        }

        /** Reads a bounded JSON-RPC string result such as {@code apiinfo.version}. */
        default String readText(String responseJson) {
            throw new IllegalStateException("Zabbix JSON-RPC text result is not supported by this transport");
        }
    }

    public interface SecretSource {
        String resolve(String secretRef);
    }
}
