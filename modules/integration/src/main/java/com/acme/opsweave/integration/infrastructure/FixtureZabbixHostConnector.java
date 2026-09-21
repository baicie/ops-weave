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
        int offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            offset = Integer.parseInt(cursor);
        }
        int bounded = Math.min(Math.max(limit, 1), 500);
        if (offset >= records.size()) {
            return new Page(List.of(), null, true);
        }
        int end = Math.min(offset + bounded, records.size());
        boolean complete = end >= records.size();
        return new Page(List.copyOf(records.subList(offset, end)), complete ? null : Integer.toString(end), complete);
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
