package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.RawRecordReader;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded in-memory raw retention. Labeled non-production. */
public final class InMemoryRawRecordStore implements IngestZabbixHostsUseCase.RawRecordCollector, RawRecordReader {
    private static final int MAX_RECORDS = 1000;
    private record Stored(TenantId tenant, String source, UUID run, Connector.RawRecord record) {}
    private final ConcurrentHashMap<String, Stored> records = new ConcurrentHashMap<>();
    private final List<String> order = new ArrayList<>();

    @Override
    public synchronized String retain(
        TenantId tenantId,
        String sourceInstanceId,
        UUID syncRunId,
        Connector.RawRecord record
    ) {
        while (order.size() >= MAX_RECORDS) {
            String oldest = order.remove(0);
            records.remove(oldest);
        }
        String ref = IngestZabbixHostsUseCase.newRawRef();
        records.put(ref, new Stored(tenantId, sourceInstanceId, syncRunId,
            new Connector.RawRecord(record.externalId(), record.observedAt(), copyMap(record.payload()))));
        order.add(ref);
        return ref;
    }

    @Override
    public synchronized Batch read(TenantId tenant, String source, UUID run, int limit) {
        if (limit < 1 || limit > MAX_RECORDS) throw new IllegalArgumentException("Invalid raw limit");
        var matched = order.stream().filter(ref -> {
            var stored = records.get(ref);
            return stored.tenant().equals(tenant) && stored.source().equals(source) && stored.run().equals(run);
        }).toList();
        return new Batch(matched.stream().limit(limit).map(ref -> {
            var raw = records.get(ref).record();
            // Conservative bound for this development store; PostgreSQL measures UTF-8 JSON bytes.
            return new Retained(ref, raw.payload().toString().length() > MAX_PAYLOAD_BYTES / 6 ? null : raw);
        }).toList(), matched.size());
    }

    private static java.util.Map<String, Object> copyMap(java.util.Map<String, Object> value) {
        var copy = new java.util.LinkedHashMap<String, Object>();
        value.forEach((key, item) -> copy.put(key, copyValue(item)));
        return java.util.Collections.unmodifiableMap(copy);
    }
    private static Object copyValue(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            var copy = new java.util.LinkedHashMap<String, Object>();
            map.forEach((key, item) -> copy.put((String) key, copyValue(item)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(InMemoryRawRecordStore::copyValue).toList();
        return value;
    }
}
