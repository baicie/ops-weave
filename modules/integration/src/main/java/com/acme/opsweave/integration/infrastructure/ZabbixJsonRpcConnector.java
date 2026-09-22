package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Zabbix JSON-RPC host.get client. Transport is injected so this module stays free of HTTP JSON libraries.
 * Pages use limit/offset sorted by hostid, because an unsorted offset is not a stable cursor.
 * The cursor advances by the API result size. A shorter page, including an empty page, is a complete snapshot.
 * Transport failures propagate and are not a complete snapshot.
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
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"host.get\",\"params\":{"
            + "\"output\":[\"hostid\",\"host\",\"name\",\"status\"],"
            + "\"selectInterfaces\":[\"ip\",\"main\",\"type\"],"
            + "\"sortfield\":\"hostid\","
            + "\"sortorder\":\"ASC\","
            + "\"limit\":" + bounded + ","
            + "\"offset\":" + offset
            + "},\"id\":1}";
        String response = transport.exchange(endpoint, body, token);
        List<Map<String, Object>> hosts = transport.readHostArray(response);
        Instant observedAt = Instant.now();
        List<RawRecord> records = new ArrayList<>();
        for (Map<String, Object> host : hosts) {
            Object hostId = host.get("hostid");
            if (hostId == null) {
                continue;
            }
            records.add(new RawRecord(String.valueOf(hostId), observedAt, host));
        }
        boolean complete = hosts.size() < bounded;
        String next = complete ? null : Integer.toString(offset + hosts.size());
        return new Page(List.copyOf(records), next, complete);
    }

    public interface Transport {
        String exchange(URI endpoint, String jsonBody, String bearerToken);

        List<Map<String, Object>> readHostArray(String responseJson);
    }

    public interface SecretSource {
        String resolve(String secretRef);
    }
}
