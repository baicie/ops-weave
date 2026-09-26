package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.SyncScan;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Explicitly labeled item fixture. Live JSON-RPC never falls back here; the list is immutable. */
public final class FixtureZabbixItemConnector implements Connector {
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
            new RawRecord("20001", observedAt, item("20001", "system.cpu.util[,user]", "CPU user time", "0", "%", "10084")),
            new RawRecord("20002", observedAt, item("20002", "system.cpu.util[,idle]", "CPU idle time", "0", "%", "10084"))
        );
        int offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            offset = Integer.parseInt(cursor);
        }
        int bounded = Math.min(Math.max(limit, 1), 500);
        if (offset >= records.size()) {
            return new Page(List.of(), null, true, SyncScan.ITEMID_WATERMARK);
        }
        int end = Math.min(offset + bounded, records.size());
        boolean complete = end >= records.size();
        return new Page(
            List.copyOf(records.subList(offset, end)),
            complete ? null : Integer.toString(end),
            complete,
            SyncScan.ITEMID_WATERMARK
        );
    }

    private static Map<String, Object> item(
        String itemId,
        String key,
        String name,
        String valueType,
        String units,
        String hostId
    ) {
        return Map.of(
            "itemid", itemId,
            "key_", key,
            "name", name,
            "value_type", valueType,
            "units", units,
            "hostid", hostId
        );
    }
}
