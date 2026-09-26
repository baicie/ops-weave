package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncRunCursor;
import com.acme.opsweave.integration.domain.SyncScan;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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
            null,
            SyncScan.OFFSET_ATTEMPT
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
            null,
            current.scanConsistency()
        ));
    }

    @Override
    public void succeed(TenantId tenantId, UUID id, String scanConsistency) {
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
            null,
            scanConsistency
        ));
    }

    @Override
    public void fail(TenantId tenantId, UUID id, String reason, String scanConsistency) {
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
            text,
            scanConsistency
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

    @Override
    public List<SyncRun> recent(
        TenantId tenantId,
        String sourceInstanceId,
        String objectType,
        SyncRunCursor after,
        int limit
    ) {
        if (limit < 1 || limit > MAX_RECENT) {
            throw new IllegalArgumentException("Invalid run limit");
        }
        return runs.values().stream()
            .filter(run -> run.tenantId().equals(tenantId)
                && run.sourceInstanceId().equals(sourceInstanceId)
                && run.objectType().equals(objectType))
            .filter(run -> after == null || precedes(run, after))
            .sorted(Comparator.comparing(SyncRun::startedAt).reversed()
                .thenComparing(SyncRun::id, Comparator.reverseOrder()))
            .limit(limit + 1L)
            .toList();
    }

    /** True when the run sorts after the cursor in the newest-first order. */
    private static boolean precedes(SyncRun run, SyncRunCursor after) {
        int byStart = run.startedAt().compareTo(after.startedAt());
        return byStart < 0 || (byStart == 0 && run.id().compareTo(after.id()) < 0);
    }

    private SyncRun required(TenantId tenantId, UUID id) {
        return find(tenantId, id).orElseThrow(() -> new IllegalArgumentException("Unknown sync run"));
    }
}
