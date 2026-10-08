package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.domain.ScanRunRetention;
import com.acme.opsweave.integration.domain.SourceConnectionConfiguration;
import com.acme.opsweave.integration.domain.SourceScanRunException;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncRunCursor;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only history for item scans pinned to one registered source connection revision. */
public final class RegisteredSourceScanRunQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = SyncRunStore.MAX_RECENT;

    private final AuthorizationService authorization;
    private final SyncRunStore runs;
    private final ScanRunRetention.Policy retention;

    public RegisteredSourceScanRunQueryService(AuthorizationService authorization, SyncRunStore runs) {
        this(authorization, runs, ScanRunRetention.Policy.defaults());
    }

    public RegisteredSourceScanRunQueryService(
        AuthorizationService authorization,
        SyncRunStore runs,
        ScanRunRetention.Policy retention
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    public Page recent(Principal principal, UUID sourceId, int revision, String after, int limit) {
        return recent(principal, sourceId, revision, SourceConnectionConfiguration.physicalId(sourceId), after, limit);
    }

    /** Reads history against the physical source id resolved from the maintained instance. */
    public Page recent(Principal principal, UUID sourceId, int revision, String sourceInstanceId, String after, int limit) {
        authorize(principal, sourceId, revision, sourceInstanceId);
        if (limit < 1 || limit > MAX_LIMIT) throw new SourceScanRunException(SourceScanRunException.Code.INVALID_REQUEST);
        SyncRunCursor cursor;
        try {
            cursor = after == null || after.isBlank() ? null : SyncRunCursor.decode(after);
        } catch (IllegalArgumentException failed) {
            throw new SourceScanRunException(SourceScanRunException.Code.INVALID_REQUEST);
        }

        List<SyncRun> rows = runs.registeredRecent(principal.tenantId(), sourceId, revision, cursor, limit);
        if (rows.size() > limit + 1) throw hidden();
        var ids = new java.util.HashSet<UUID>();
        for (int index = 0; index < rows.size(); index++) {
            SyncRun run = rows.get(index);
            requireScope(principal, sourceId, revision, sourceInstanceId, run);
            if (!ids.add(run.id())) throw hidden();
            if (index > 0 && newerThan(run, rows.get(index - 1))) throw hidden();
        }

        boolean hasMore = rows.size() > limit;
        List<SyncRun> selected = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String nextCursor = hasMore && !selected.isEmpty()
            ? new SyncRunCursor(selected.getLast().startedAt(), selected.getLast().id()).encode()
            : null;
        List<Entry> items = selected.stream().map(Entry::new).toList();
        int retained = runs.retained(principal.tenantId(), sourceInstanceId, "item");
        return new Page(items, hasMore, nextCursor,
            new Retention(retention.maxRunsPerScope(), retention.maxRunsPerTenant(), retained));
    }

    public Entry find(Principal principal, UUID sourceId, int revision, UUID runId) {
        return find(principal, sourceId, revision, SourceConnectionConfiguration.physicalId(sourceId), runId);
    }

    /** Reads one history row against the physical source id resolved from the maintained instance. */
    public Entry find(Principal principal, UUID sourceId, int revision, String sourceInstanceId, UUID runId) {
        authorize(principal, sourceId, revision, sourceInstanceId);
        if (runId == null) throw new SourceScanRunException(SourceScanRunException.Code.INVALID_REQUEST);
        SyncRun run = runs.find(principal.tenantId(), runId)
            .orElseThrow(() -> new SourceScanRunException(SourceScanRunException.Code.NOT_FOUND));
        requireScope(principal, sourceId, revision, sourceInstanceId, runId, run);
        return new Entry(run);
    }

    private void authorize(Principal principal, UUID sourceId, int revision, String sourceInstanceId) {
        if (principal == null) throw new SourceScanRunException(SourceScanRunException.Code.FORBIDDEN);
        if (sourceId == null || revision < 1 || revision > 100 || sourceInstanceId == null
            || !sourceInstanceId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            throw new SourceScanRunException(SourceScanRunException.Code.INVALID_REQUEST);
        if (authorization.authorize(principal, ResourceRef.source(principal.tenantId(), sourceInstanceId), Permission.SOURCE_SYNC).denied())
            throw new SourceScanRunException(SourceScanRunException.Code.FORBIDDEN);
    }

    private static void requireScope(Principal principal, UUID sourceId, int revision, String sourceInstanceId, SyncRun run) {
        requireScope(principal, sourceId, revision, sourceInstanceId, null, run);
    }

    private static void requireScope(Principal principal, UUID sourceId, int revision, String sourceInstanceId, UUID expectedRunId, SyncRun run) {
        SyncRun.SourceScope scope = run.sourceScope();
        if (!run.tenantId().equals(principal.tenantId())
            || expectedRunId != null && !run.id().equals(expectedRunId)
            || !run.sourceInstanceId().equals(sourceInstanceId)
            || !run.objectType().equals("item")
            || !run.dataMode().equals("zabbix-jsonrpc")
            || scope == null
            || !scope.sourceId().equals(sourceId)
            || scope.configurationRevision() != revision
            || scope.sourceInstanceId() != null && !scope.sourceInstanceId().equals(run.sourceInstanceId())) {
            throw hidden();
        }
    }

    private static boolean newerThan(SyncRun candidate, SyncRun previous) {
        int byStart = candidate.startedAt().compareTo(previous.startedAt());
        return byStart > 0 || byStart == 0 && candidate.id().compareTo(previous.id()) > 0;
    }

    private static SourceScanRunException hidden() {
        return new SourceScanRunException(SourceScanRunException.Code.NOT_FOUND);
    }

    public record Entry(SyncRun run) { public Entry { Objects.requireNonNull(run); } }
    public record Retention(int maxRunsPerScope, int maxRunsPerTenant, int retained) {}
    public record Page(List<Entry> items, boolean hasMore, String nextCursor, Retention retention) {
        public Page { items = List.copyOf(items); Objects.requireNonNull(retention); }
    }
}
