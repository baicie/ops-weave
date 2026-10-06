package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.ScanRunRetention;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncRunCursor;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SyncRunStore {
    /** Upper bound for one trace page; the store returns at most {@code limit + 1} rows. */
    int MAX_RECENT = 50;

    /**
     * Opens one scan run and, first, applies the retention budget so the stored trace stays
     * bounded. The newest rows of the scope and of the tenant survive; a {@code RUNNING} run and a
     * run that a pipeline version is pinned to are never deleted. Pruning is storage hygiene: it
     * never rewrites a stored result, retires an object or reconciles a missing one.
     */
    SyncRun start(TenantId tenantId, String sourceInstanceId, String objectType, String dataMode);

    /** Opens a run pinned to an immutable registered-connection revision and scope digest. */
    default SyncRun start(TenantId tenantId,String sourceInstanceId,String objectType,String dataMode,SyncRun.SourceScope sourceScope) {
        return start(tenantId,sourceInstanceId,objectType,dataMode);
    }

    void checkpoint(
        TenantId tenantId,
        UUID id,
        String cursor,
        int pages,
        int fetched,
        int accepted,
        int rejected
    );

    /** Marks the walk succeeded and records how it was bounded. */
    void succeed(TenantId tenantId, UUID id, String scanConsistency);

    /** Marks a successful reconciliation together with the number of bindings retired in its fenced transaction. */
    default void succeed(TenantId tenantId,UUID id,String scanConsistency,int retired) {
        if(retired<0)throw new IllegalArgumentException("Invalid retired count");
        succeed(tenantId,id,scanConsistency);
    }

    /** Marks the walk failed and records how it was bounded; a failure never claims a snapshot. */
    void fail(TenantId tenantId, UUID id, String reason, String scanConsistency);

    Optional<SyncRun> find(TenantId tenantId, UUID id);

    /**
     * Newest-first stored scans for one source scope, ordered by started_at DESC then id DESC.
     * An {@code after} cursor is exclusive. At most {@code limit + 1} rows come back so the caller
     * can tell whether another page exists; a read never mutates a stored run.
     */
    List<SyncRun> recent(
        TenantId tenantId,
        String sourceInstanceId,
        String objectType,
        SyncRunCursor after,
        int limit
    );

    /** Newest-first runs for one registered connection revision, including at most limit + 1 rows. */
    List<SyncRun> registeredRecent(TenantId tenantId,UUID sourceId,int configurationRevision,SyncRunCursor after,int limit);

    /** Oldest completed batches after an exclusive completion cursor, including failures.
     * At most limit+1 rows; old history must not consume a workflow's pending-batch budget. */
    List<SyncRun> completedAfter(TenantId tenantId, String sourceInstanceId, String objectType,
        java.time.Instant after, UUID afterId, int limit);

    /**
     * Rows the retention policy keeps for one scope right now. Read-only: it prunes nothing, so a
     * trace never mutates the log it is reporting on. It counts the same rows a sweep would keep.
     */
    int retained(TenantId tenantId, String sourceInstanceId, String objectType);
}
