package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncRunCursor;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SyncRunStore {
    /** Upper bound for one trace page; the store returns at most {@code limit + 1} rows. */
    int MAX_RECENT = 50;

    SyncRun start(TenantId tenantId, String sourceInstanceId, String objectType, String dataMode);

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
}
