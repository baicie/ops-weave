package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Labeled in-memory sync log. Not a production checkpoint store. */
public final class InMemorySyncRunStore implements SyncRunStore {
    private final ConcurrentHashMap<UUID, SyncRun> runs = new ConcurrentHashMap<>();

    @Override
    public SyncRun start(TenantId tenantId, String sourceInstanceId, String objectType, String dataMode) {
        SyncRun run = new SyncRun(
            UUID.randomUUID(),
            tenantId,
            sourceInstanceId,
            objectType,
            SyncStatus.RUNNING,
            Instant.now(),
            null,
            null,
            0,
            0,
            0,
            0,
            false,
            dataMode,
            null
        );
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public void checkpoint(
        TenantId tenantId,
        UUID id,
        String cursor,
        int pages,
        int fetched,
        int accepted,
        int rejected
    ) {
        SyncRun current = required(tenantId, id);
        runs.put(id, new SyncRun(
            current.id(),
            current.tenantId(),
            current.sourceInstanceId(),
            current.objectType(),
            SyncStatus.RUNNING,
            current.startedAt(),
            null,
            cursor,
            pages,
            fetched,
            accepted,
            rejected,
            false,
            current.dataMode(),
            null
        ));
    }

    @Override
    public void succeed(TenantId tenantId, UUID id) {
        SyncRun current = required(tenantId, id);
        runs.put(id, new SyncRun(
            current.id(),
            current.tenantId(),
            current.sourceInstanceId(),
            current.objectType(),
            SyncStatus.SUCCEEDED,
            current.startedAt(),
            Instant.now(),
            current.cursor(),
            current.pages(),
            current.fetched(),
            current.accepted(),
            current.rejected(),
            true,
            current.dataMode(),
            null
        ));
    }

    @Override
    public void fail(TenantId tenantId, UUID id, String reason) {
        SyncRun current = required(tenantId, id);
        String text = reason == null || reason.isBlank() ? "failed" : reason;
        if (text.length() > 200) {
            text = text.substring(0, 200);
        }
        runs.put(id, new SyncRun(
            current.id(),
            current.tenantId(),
            current.sourceInstanceId(),
            current.objectType(),
            SyncStatus.FAILED,
            current.startedAt(),
            Instant.now(),
            current.cursor(),
            current.pages(),
            current.fetched(),
            current.accepted(),
            current.rejected(),
            false,
            current.dataMode(),
            text
        ));
    }

    @Override
    public Optional<SyncRun> find(TenantId tenantId, UUID id) {
        SyncRun run = runs.get(id);
        if (run == null || !run.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(run);
    }

    private SyncRun required(TenantId tenantId, UUID id) {
        return find(tenantId, id).orElseThrow(() -> new IllegalArgumentException("Unknown sync run"));
    }
}
