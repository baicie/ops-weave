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
 * Zabbix host.get client using a bounded identity manifest and explicit ID batches.
 * Membership must remain unchanged through the final page; failures never authorize reconciliation.
 * Mutable fields can change during this walk. No unsupported offset parameter is sent.
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
        var manifest = JsonRpcWatermarkBounds.capture(transport, endpoint, token, "host.get", "hostid");
        if (position != null && !position.matches(manifest))
            return new Page(List.of(), null, false, SyncScan.HOSTID_WATERMARK);
        int start = position == null ? 0 : position.position();
        int end = Math.min(start + Math.min(Math.max(limit, 1), 500), manifest.ids().size());
        var selected = manifest.ids().subList(start, end);
        List<Map<String, Object>> rows = List.of();
        if (!selected.isEmpty()) {
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"host.get\",\"params\":{"
                + "\"output\":[\"hostid\",\"host\",\"name\",\"status\"],\"selectInterfaces\":[\"ip\",\"main\",\"type\"],"
                + "\"hostids\":" + selected + ",\"sortfield\":\"hostid\",\"sortorder\":\"ASC\","
                + "\"limit\":" + selected.size() + "},\"id\":1}";
            rows = transport.readHostArray(transport.exchange(endpoint, body, token));
            var returned = rows.stream().map(row -> JsonRpcWatermarkBounds.id(row, "hostid")).toList();
            if (!selected.equals(returned)) return new Page(List.of(), null, false, SyncScan.HOSTID_WATERMARK);
        }
        boolean complete = end == manifest.ids().size();
        if (complete && !manifest.equals(JsonRpcWatermarkBounds.capture(transport, endpoint, token, "host.get", "hostid")))
            return new Page(List.of(), null, false, SyncScan.HOSTID_WATERMARK);
        Instant observedAt = Instant.now();
        List<RawRecord> records = new ArrayList<>();
        for (Map<String, Object> row : rows)
            records.add(new RawRecord(String.valueOf(row.get("hostid")), observedAt, row));
        return new Page(List.copyOf(records), complete ? null : manifest.cursor(end), complete, SyncScan.HOSTID_WATERMARK);
    }

    public interface Transport {
        /** A null bearer token is reserved for unauthenticated apiinfo.version discovery. */
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
