package com.acme.opsweave.integration.api;

import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;
import java.util.UUID;

public interface RawRecordReader {
    int MAX_RECORDS = 100;
    int MAX_PAYLOAD_BYTES = com.acme.opsweave.integration.domain.RawRetention.MAX_PAYLOAD_BYTES;
    Batch read(TenantId tenant, String source, UUID run, int limit);
    /** Null record explicitly denotes an oversized payload, never an empty source object. */
    record Retained(String ref, Connector.RawRecord record) {}
    record Batch(List<Retained> records, long retainedCount) {
        public Batch { records = List.copyOf(records); }
    }
}
