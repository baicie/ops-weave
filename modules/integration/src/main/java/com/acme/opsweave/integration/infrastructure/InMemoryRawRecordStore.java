package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded in-memory raw retention. Labeled non-production. */
public final class InMemoryRawRecordStore implements IngestZabbixHostsUseCase.RawRecordCollector {
    private static final int MAX_RECORDS = 1000;
    private final ConcurrentHashMap<String, Connector.RawRecord> records = new ConcurrentHashMap<>();
    private final List<String> order = new ArrayList<>();

    @Override
    public synchronized String retain(TenantId tenantId, String sourceInstanceId, Connector.RawRecord record) {
        while (order.size() >= MAX_RECORDS) {
            String oldest = order.remove(0);
            records.remove(oldest);
        }
        String ref = IngestZabbixHostsUseCase.newRawRef();
        records.put(ref, record);
        order.add(ref);
        return ref;
    }

    public Connector.RawRecord get(String ref) {
        return records.get(ref);
    }
}
