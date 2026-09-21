package com.acme.opsweave.integration.api;

import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Cursor is advanced only after durable acceptance, never after an in-memory fetch. */
public interface Connector {
    String type();
    ProbeResult probe(SourceContext source);
    Page fetch(SourceContext source, String cursor, int limit);

    record SourceContext(TenantId tenantId, String sourceInstanceId, String secretRef) {}
    record ProbeResult(boolean reachable, String statusCode) {}
    record RawRecord(String externalId, Instant observedAt, Map<String, Object> payload) {}
    record Page(List<RawRecord> records, String nextCursor, boolean snapshotComplete) {}
}
