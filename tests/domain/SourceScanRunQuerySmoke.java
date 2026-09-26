import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.application.SourceScanRunQueryService;
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.domain.PipelineVersion;
import com.acme.opsweave.integration.domain.SourceScanRunException;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncRunCursor;
import com.acme.opsweave.integration.domain.SyncScan;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.integration.infrastructure.InMemoryPipelineVersionStore;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only trace of stored scans: authorization, isolation, bounded paging and failure codes. */
public final class SourceScanRunQuerySmoke {
    private static final TenantId TENANT = new TenantId("tenant-scan-run");
    private static final TenantId OTHER = new TenantId("tenant-scan-run-other");
    private static final String SOURCE = "zabbix-1";
    private static final SubjectId ACTOR = new SubjectId("operator");
    private static int checks = 0;

    public static void main(String[] args) {
        var runs = new InMemorySyncRunStore();
        var versions = new InMemoryPipelineVersionStore();
        var service = new SourceScanRunQueryService(new AuthorizeUseCase(), runs, versions, SOURCE);
        var reader = new Principal(ACTOR, TENANT, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide());

        fails(SourceScanRunException.Code.FORBIDDEN, () -> service.recent(null, "host", null, 10));
        fails(SourceScanRunException.Code.FORBIDDEN, () -> service.recent(
            new Principal(ACTOR, TENANT, Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide()), "host", null, 10));
        fails(SourceScanRunException.Code.UNCONFIGURED, () -> new SourceScanRunQueryService(
            new AuthorizeUseCase(), runs, versions, "  ").recent(reader, "host", null, 10));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(reader, "problem", null, 10));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(reader, null, null, 10));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(reader, "host", null, 0));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(
            reader, "host", null, SourceScanRunQueryService.MAX_LIMIT + 1));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(reader, "host", "***", 10));
        require(SourceScanRunQueryService.DEFAULT_LIMIT == 20 && SourceScanRunQueryService.MAX_LIMIT == 50,
            "trace page stays bounded at 50 rows");
        fails(IllegalArgumentException.class, () -> runs.recent(TENANT, SOURCE, "host", null, 51));

        // Another tenant, another source and another object type are never part of this trace.
        runs.start(OTHER, SOURCE, "host", "labeled-fixture");
        runs.start(TENANT, "zabbix-2", "host", "labeled-fixture");
        runs.start(TENANT, SOURCE, "item", "labeled-fixture");
        require(service.recent(reader, "host", null, 10).items().isEmpty(),
            "only the configured tenant, source and object type are traced");
        require(service.recent(reader, "item", null, 10).items().size() == 1,
            "item scans are traced separately from host scans");

        var oldest = runs.start(TENANT, SOURCE, "host", "labeled-fixture");
        var middle = runs.start(TENANT, SOURCE, "host", "labeled-fixture");
        var newest = runs.start(TENANT, SOURCE, "host", "labeled-fixture");
        runs.checkpoint(TENANT, oldest.id(), "100", 1, 5, 5, 0);
        runs.succeed(TENANT, oldest.id(), SyncScan.HOSTID_WATERMARK);
        runs.checkpoint(TENANT, middle.id(), "200", 2, 10, 9, 1);
        runs.succeed(TENANT, middle.id(), SyncScan.OFFSET_ATTEMPT);
        runs.fail(TENANT, newest.id(), SyncFailureCode.SOURCE_FETCH_FAILED.storedReason(), SyncScan.OFFSET_ATTEMPT);

        List<UUID> expected = List.of(oldest, middle, newest).stream()
            .sorted(Comparator.comparing(SyncRun::startedAt).reversed()
                .thenComparing(SyncRun::id, Comparator.reverseOrder()))
            .map(SyncRun::id)
            .toList();
        var all = service.recent(reader, "host", null, 10);
        require(all.items().stream().map(entry -> entry.run().id()).toList().equals(expected),
            "stored scans come back newest first");
        require(!all.hasMore() && all.nextCursor() == null, "a complete page carries no cursor");

        var firstPage = service.recent(reader, "host", null, 2);
        require(firstPage.items().size() == 2 && firstPage.hasMore() && firstPage.nextCursor() != null,
            "bounded page reports that more rows exist");
        require(firstPage.items().stream().map(entry -> entry.run().id()).toList().equals(expected.subList(0, 2)),
            "first page follows the newest-first order");
        var secondPage = service.recent(reader, "host", firstPage.nextCursor(), 2);
        require(secondPage.items().size() == 1 && !secondPage.hasMore(),
            "cursor resumes after the last returned row");
        require(secondPage.items().getFirst().run().id().equals(expected.get(2)), "paged rows are disjoint and complete");
        require(firstPage.items().stream().noneMatch(entry -> entry.run().id().equals(secondPage.items().getFirst().run().id())),
            "the cursor never repeats a row");

        var failed = service.find(reader, "host", newest.id());
        require(failed.run().status() == SyncStatus.FAILED && !failed.run().snapshotComplete(),
            "a failed scan is never a complete snapshot");
        require(failed.run().pages() == 0 && failed.run().fetched() == 0,
            "a scan that failed before its first page reports no progress");
        require(SyncScan.HOSTID_WATERMARK.equals(service.find(reader, "host", oldest.id()).run().scanConsistency()),
            "the trace keeps how a finished walk was bounded");
        require(SyncScan.OFFSET_ATTEMPT.equals(failed.run().scanConsistency()),
            "a failed walk keeps its own method label");
        require(SyncFailureCode.fromStoredReason(failed.run().failureReason()).orElseThrow()
            == SyncFailureCode.SOURCE_FETCH_FAILED, "stored failure keeps its stable code");
        require(failed.pipelineVersion() == null, "no mapping version was pinned for this run");

        var version = versions.publish(TENANT, SOURCE, PipelineVersion.of(PipelineDefinition.zabbixHostV1()));
        versions.pin(TENANT, SOURCE, middle.id(), version.ref());
        require(version.ref().equals(service.find(reader, "host", middle.id()).pipelineVersion()),
            "trace exposes the mapping version pinned to the scan");
        require(service.find(reader, "host", oldest.id()).pipelineVersion() == null,
            "an unpinned scan reports no mapping version");

        fails(SourceScanRunException.Code.NOT_FOUND, () -> service.find(reader, "item", newest.id()));
        fails(SourceScanRunException.Code.NOT_FOUND, () -> service.find(
            new Principal(ACTOR, OTHER, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide()), "host", newest.id()));
        fails(SourceScanRunException.Code.NOT_FOUND, () -> service.find(reader, "host", UUID.randomUUID()));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.find(reader, "host", null));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.find(reader, "problem", newest.id()));

        require(SyncFailureCode.fromStoredReason(null).isEmpty(), "missing failure text yields no code");
        require(SyncFailureCode.fromStoredReason("CUSTOM: raw vendor text").isEmpty(),
            "unknown stored text yields no code");
        require(SyncFailureCode.fromStoredReason("SOURCE_SCAN_LOST").isEmpty(),
            "a code without its fixed summary is not trusted");
        require(SyncFailureCode.SOURCE_SCAN_LOST.storedReason()
                .equals("SOURCE_SCAN_LOST: Source scan lease was lost. This scan cannot write or reconcile missing objects."),
            "stored reason stays code plus fixed summary");

        var cursor = new SyncRunCursor(Instant.parse("2026-09-26T05:00:08.123456Z"), UUID.randomUUID());
        require(SyncRunCursor.decode(cursor.encode()).equals(cursor), "cursor keeps microsecond precision");
        fails(IllegalArgumentException.class, () -> SyncRunCursor.decode("not-a-cursor"));
        fails(IllegalArgumentException.class, () -> new SyncRunCursor(null, UUID.randomUUID()));

        System.out.println("SourceScanRunQuerySmoke: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
        checks++;
    }

    private static void fails(SourceScanRunException.Code code, Runnable action) {
        try {
            action.run();
        } catch (SourceScanRunException expected) {
            require(expected.code() == code, "expected " + code + " but got " + expected.code());
            return;
        }
        throw new IllegalStateException("Expected " + code);
    }

    private static void fails(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            require(type.isInstance(expected), "expected " + type.getSimpleName() + " but got " + expected.getClass().getSimpleName());
            return;
        }
        throw new IllegalStateException("Expected " + type.getSimpleName());
    }
}
