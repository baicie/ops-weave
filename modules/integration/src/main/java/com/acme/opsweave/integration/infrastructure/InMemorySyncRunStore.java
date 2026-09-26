package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.domain.ScanRunRetention;
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
    private final java.util.concurrent.atomic.AtomicReference<Instant> lastStartedAt =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
    private final ScanRunRetention.Policy policy;

    public InMemorySyncRunStore() {
        this(ScanRunRetention.Policy.defaults());
    }

    public InMemorySyncRunStore(ScanRunRetention.Policy policy) {
        this.policy = policy;
    }

    /**
     * Wall-clock start time, nudged forward when two runs would otherwise share an instant. The
     * stored order is then a total order on the instant alone, so a retention sweep and a cursor
     * page always agree on which run is newer.
     */
    private Instant nextStartedAt() {
        Instant now = Instant.now();
        while (true) {
            Instant previous = lastStartedAt.get();
            Instant next = now.isAfter(previous) ? now : previous.plusNanos(1_000);
            if (lastStartedAt.compareAndSet(previous, next)) {
                return next;
            }
        }
    }

    @Override
    public SyncRun start(TenantId tenantId, String sourceInstanceId, String objectType, String dataMode) {
        UUID opening = UUID.randomUUID();
        SyncRun run = new SyncRun(
            opening,
            tenantId,
            sourceInstanceId,
            objectType,
            SyncStatus.RUNNING,
            nextStartedAt(),
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
        sweep(tenantId, sourceInstanceId, objectType, opening);
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
        return newestFirst(runs.values().stream()
            .filter(run -> run.tenantId().equals(tenantId)
                && run.sourceInstanceId().equals(sourceInstanceId)
                && run.objectType().equals(objectType))
            .filter(run -> after == null || precedes(run, after)))
            .limit(limit + 1L)
            .toList();
    }

    /** True when the run sorts after the cursor in the newest-first order. */
    private static boolean precedes(SyncRun run, SyncRunCursor after) {
        int byStart = run.startedAt().compareTo(after.startedAt());
        return byStart < 0 || (byStart == 0 && run.id().compareTo(after.id()) < 0);
    }

    /**
     * Applies the retention budget in the same operation that opens a run, after the run is
     * stored. The in-memory store has no pipeline pins, so a live {@code RUNNING} run and the run
     * being opened now are the protected rows; the newest rows of the scope and of the tenant
     * survive, which bounds the scope at the budget instead of the budget plus the live run.
     */
    private void sweep(TenantId tenantId, String sourceInstanceId, String objectType, UUID opening) {
        prune(of(tenantId, sourceInstanceId, objectType), policy.maxRunsPerScope(), opening);
        prune(ofTenant(tenantId), policy.maxRunsPerTenant(), opening);
    }

    /**
     * Deletes the runs that fall outside the budget. The run being opened now counts against the
     * budget — it is a stored row like any other — so the surviving rows are always at most
     * {@code budget}. Rows are handed over newest-first, so the newest survivors win and the
     * oldest evictable run goes first. Explicit loop rather than a stream suffix: the cut index is
     * the whole point and must be trivially auditable.
     */
    private void prune(List<SyncRun> newestFirst, int budget, UUID opening) {
        boolean openingCounted = false;
        int kept = 0;
        List<UUID> doomed = new java.util.ArrayList<>();
        for (SyncRun run : newestFirst) {
            if (run.id().equals(opening)) {
                openingCounted = true;
                continue;
            }
            if (!prunable(run, opening)) {
                continue;
            }
            if (kept + (openingCounted ? 1 : 0) < budget) {
                kept++;
                continue;
            }
            doomed.add(run.id());
        }
        if (!doomed.isEmpty()) {
            runs.keySet().removeAll(doomed);
        }
    }

    @Override
    public int retained(TenantId tenantId, String sourceInstanceId, String objectType) {
        return (int) of(tenantId, sourceInstanceId, objectType).stream()
            .filter(run -> run.status() != SyncStatus.RUNNING)
            .count();
    }

    private List<SyncRun> of(TenantId tenantId, String sourceInstanceId, String objectType) {
        return newestFirst(runs.values().stream()
            .filter(run -> run.tenantId().equals(tenantId)
                && run.sourceInstanceId().equals(sourceInstanceId)
                && run.objectType().equals(objectType)))
            .toList();
    }

    private List<SyncRun> ofTenant(TenantId tenantId) {
        return newestFirst(runs.values().stream().filter(run -> run.tenantId().equals(tenantId))).toList();
    }

    private java.util.stream.Stream<SyncRun> newestFirst(java.util.stream.Stream<SyncRun> stream) {
        // Order by the stored instant, then by run id — the same total order the cursor pages on,
        // so a page boundary can never skip or repeat a row that shares a millisecond.
        return stream.sorted(Comparator.comparing(SyncRun::startedAt).reversed()
            .thenComparing(SyncRun::id, Comparator.reverseOrder()));
    }

    private static boolean prunable(SyncRun run, UUID opening) {
        return run.status() != SyncStatus.RUNNING && !run.id().equals(opening);
    }

    private SyncRun required(TenantId tenantId, UUID id) {
        return find(tenantId, id).orElseThrow(() -> new IllegalArgumentException("Unknown sync run"));
    }
}
