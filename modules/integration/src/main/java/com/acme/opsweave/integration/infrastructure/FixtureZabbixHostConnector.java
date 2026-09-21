package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Explicitly labeled fixture. Must be selected by configuration; live JSON-RPC never falls back here.
 */
public final class FixtureZabbixHostConnector implements Connector {
    @Override
    public String type() {
        return "zabbix";
    }

    @Override
    public ProbeResult probe(SourceContext source) {
        return new ProbeResult(true, "labeled-fixture");
    }

    @Override
    public Page fetch(SourceContext source, String cursor, int limit) {
        Instant observedAt = Instant.parse("2026-09-21T12:00:00Z");
        List<RawRecord> records = List.of(
            new RawRecord("10084", observedAt, host("10084", "zabbix-server", "Zabbix server", "0", "10.0.0.10")),
            new RawRecord("10085", observedAt, host("10085", "app-01", "app-01", "0", "10.0.0.11"))
        );
        int size = Math.min(Math.max(limit, 1), records.size());
        return new Page(records.subList(0, size), null, true);
    }

    private static Map<String, Object> host(String hostId, String host, String name, String status, String ip) {
        return Map.of(
            "hostid", hostId,
            "host", host,
            "name", name,
            "status", status,
            "interfaces", List.of(Map.of("ip", ip, "main", "1", "type", "1"))
        );
    }
}
