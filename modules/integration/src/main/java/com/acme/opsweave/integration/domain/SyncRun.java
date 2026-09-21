package com.acme.opsweave.integration.domain;

import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One source scan. A failed run must not be treated as a complete snapshot. */
public record SyncRun(
    UUID id,
    TenantId tenantId,
    String sourceInstanceId,
    String objectType,
    SyncStatus status,
    Instant startedAt,
    Instant completedAt,
    String cursor,
    int pages,
    int fetched,
    int accepted,
    int rejected,
    boolean snapshotComplete,
    String dataMode,
    String failureReason
) {
    public SyncRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(dataMode, "dataMode");
        if (pages < 0 || fetched < 0 || accepted < 0 || rejected < 0) {
            throw new IllegalArgumentException("Sync counters cannot be negative");
        }
    }
}
