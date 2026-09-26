package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.PipelineVersionStore;
import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.domain.PipelineVersion;
import com.acme.opsweave.integration.domain.SourceScanRunException;
import com.acme.opsweave.integration.domain.SourceScanRunException.Code;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncRunCursor;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only trace for already stored source scans. There is no connector, lease, inventory writer
 * or action port here: the service can only return what a previous scan already wrote, and it
 * never repairs, retries or reconciles anything.
 */
public final class SourceScanRunQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    private static final Set<String> OBJECT_TYPES = Set.of("host", "item");

    private final AuthorizationService authorization;
    private final SyncRunStore runs;
    private final PipelineVersionStore versions;
    private final String source;

    public SourceScanRunQueryService(
        AuthorizationService authorization,
        SyncRunStore runs,
        PipelineVersionStore versions,
        String source
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.source = source;
    }

    public Page recent(Principal principal, String objectType, String after, int limit) {
        authorize(principal);
        String type = type(objectType);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new SourceScanRunException(Code.INVALID_REQUEST);
        }
        SyncRunCursor cursor;
        try {
            cursor = after == null || after.isBlank() ? null : SyncRunCursor.decode(after);
        } catch (IllegalArgumentException failed) {
            throw new SourceScanRunException(Code.INVALID_REQUEST);
        }
        List<SyncRun> rows = runs.recent(principal.tenantId(), source, type, cursor, limit);
        boolean hasMore = rows.size() > limit;
        List<SyncRun> page = hasMore ? rows.subList(0, limit) : rows;
        List<Entry> items = page.stream().map(run -> entry(principal, run)).toList();
        String nextCursor = hasMore && !page.isEmpty()
            ? new SyncRunCursor(page.get(page.size() - 1).startedAt(), page.get(page.size() - 1).id()).encode()
            : null;
        return new Page(items, hasMore, nextCursor);
    }

    public Entry find(Principal principal, String objectType, UUID syncRunId) {
        authorize(principal);
        String type = type(objectType);
        if (syncRunId == null) {
            throw new SourceScanRunException(Code.INVALID_REQUEST);
        }
        SyncRun run = runs.find(principal.tenantId(), syncRunId)
            .filter(stored -> type.equals(stored.objectType()) && source.equals(stored.sourceInstanceId()))
            .orElseThrow(() -> new SourceScanRunException(Code.NOT_FOUND));
        return entry(principal, run);
    }

    private Entry entry(Principal principal, SyncRun run) {
        PipelineVersion.Ref pinned = versions.pinned(principal.tenantId(), source, run.id()).orElse(null);
        return new Entry(run, pinned);
    }

    private void authorize(Principal principal) {
        if (principal == null) {
            throw new SourceScanRunException(Code.FORBIDDEN);
        }
        if (source == null || source.isBlank()) {
            throw new SourceScanRunException(Code.UNCONFIGURED);
        }
        if (authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.SOURCE_SYNC).denied()) {
            throw new SourceScanRunException(Code.FORBIDDEN);
        }
    }

    private static String type(String objectType) {
        if (objectType == null || !OBJECT_TYPES.contains(objectType)) {
            throw new SourceScanRunException(Code.INVALID_REQUEST);
        }
        return objectType;
    }

    /** One stored scan plus the mapping version that was pinned to it, when one was pinned. */
    public record Entry(SyncRun run, PipelineVersion.Ref pipelineVersion) {}

    public record Page(List<Entry> items, boolean hasMore, String nextCursor) {}
}
