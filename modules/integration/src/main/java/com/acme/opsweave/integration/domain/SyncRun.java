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
    String failureReason,
    String scanConsistency,
    int retired,
    SourceScope sourceScope
) {
    public SyncRun(UUID id,TenantId tenantId,String sourceInstanceId,String objectType,SyncStatus status,Instant startedAt,
        Instant completedAt,String cursor,int pages,int fetched,int accepted,int rejected,boolean snapshotComplete,
        String dataMode,String failureReason,String scanConsistency) {
        this(id,tenantId,sourceInstanceId,objectType,status,startedAt,completedAt,cursor,pages,fetched,accepted,rejected,
            snapshotComplete,dataMode,failureReason,scanConsistency,0,null);
    }

    /** Immutable connection revision and normalized host-group scope used by a registered item scan. */
    public record SourceScope(UUID sourceId,int configurationRevision,String connectionDigest,String scopeDigest) {
        public SourceScope {
            Objects.requireNonNull(sourceId);
            if(configurationRevision<1||configurationRevision>100)throw new IllegalArgumentException("Invalid source configuration revision");
            WorkflowDefinition.checkDigest(connectionDigest);
            WorkflowDefinition.checkDigest(scopeDigest);
        }
    }

    public SyncRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(dataMode, "dataMode");
        if (scanConsistency == null || !scanConsistency.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid scan consistency");
        }
        if (pages < 0 || fetched < 0 || accepted < 0 || rejected < 0 || retired < 0) {
            throw new IllegalArgumentException("Sync counters cannot be negative");
        }
    }
}
