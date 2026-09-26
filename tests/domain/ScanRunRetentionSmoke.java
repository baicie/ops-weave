import com.acme.opsweave.integration.domain.ScanRunRetention;
import com.acme.opsweave.integration.domain.SourceScanRunException;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded trace storage: opening a scan applies the retention budget, so the stored run log stops
 * growing with the lifetime of the deployment. Retention is storage hygiene only — it never
 * rewrites a stored result, never retires an object and never reopens a finished walk.
 *
 * <p>A budget is the most rows one scope or one tenant keeps. The run being opened counts against
 * it, and only rows retention may delete are evicted: a run that is still open and (in the
 * persistent store) any run a pipeline version is pinned to are never victims. The sweep runs with
 * every new scan, so a scope converges to the budget as scans continue.
 */
public final class ScanRunRetentionSmoke {
    private static final TenantId TENANT = new TenantId("tenant-retention");
    private static final TenantId OTHER = new TenantId("tenant-retention-other");
    private static final String SOURCE = "zabbix-1";
    private static int checks = 0;

    public static void main(String[] args) {
        require(ScanRunRetention.DEFAULT_MAX_RUNS_PER_SCOPE == 1000
                && ScanRunRetention.DEFAULT_MAX_RUNS_PER_TENANT == 5000,
            "published defaults stay at 1000 per scope and 5000 per tenant");
        var defaults = ScanRunRetention.Policy.defaults();
        require(defaults.maxRunsPerScope() == ScanRunRetention.DEFAULT_MAX_RUNS_PER_SCOPE
                && defaults.maxRunsPerTenant() == ScanRunRetention.DEFAULT_MAX_RUNS_PER_TENANT,
            "defaults() returns the published policy");

        // Configuration may tighten a budget but never widen it past the published ceiling.
        require(new ScanRunRetention.Policy(3, 9).maxRunsPerScope() == 3, "a tighter budget is accepted");
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> new ScanRunRetention.Policy(0, 10));
        fails(SourceScanRunException.Code.INVALID_REQUEST,
            () -> new ScanRunRetention.Policy(ScanRunRetention.DEFAULT_MAX_RUNS_PER_SCOPE + 1, 5000));
        fails(SourceScanRunException.Code.INVALID_REQUEST,
            () -> new ScanRunRetention.Policy(10, ScanRunRetention.DEFAULT_MAX_RUNS_PER_TENANT + 1));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> new ScanRunRetention.Policy(20, 10));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> new ScanRunRetention.Sweep(-1, 0));

        // A scope budget of three: six scans leave three rows, and the run that just closed is never
        // the victim. Which older row a tied instant evicts is not part of the contract, so the
        // assertions count rows and name only the rows the contract protects.
        var runs = new InMemorySyncRunStore(new ScanRunRetention.Policy(3, 100));
        List<SyncRun> opened = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            SyncRun closed = finish(runs, TENANT, SOURCE, "host");
            opened.add(closed);
            require(runs.recent(TENANT, SOURCE, "host", null, 50).size() <= 3,
                "a scope never holds more than its budget of rows");
            require(runs.find(TENANT, closed.id()).isPresent(),
                "the run that just closed is never the victim of its own sweep");
        }
        require(runs.recent(TENANT, SOURCE, "host", null, 50).size() == 3, "six scans leave the budget of three");
        require(runs.retained(TENANT, SOURCE, "host") == 3, "the reported retained count matches the stored rows");
        long survived = opened.stream().filter(run -> runs.find(TENANT, run.id()).isPresent()).count();
        require(survived == 3, "exactly the budget survives, whatever the tie-break between equal instants");
        require(runs.find(TENANT, opened.get(0).id()).isEmpty(),
            "a run older than the newest three cannot still be stored");
        require(runs.find(TENANT, opened.get(4).id()).isPresent() && runs.find(TENANT, opened.get(5).id()).isPresent(),
            "the two newest runs are never evicted by a later sweep");

        // Opening a scan evicts an older finished run, because the new row counts against the budget.
        SyncRun extra = runs.start(TENANT, SOURCE, "host", "labeled-fixture");
        require(runs.find(TENANT, extra.id()).isPresent(), "the run being opened is never pruned by its own sweep");
        require(runs.find(TENANT, opened.get(5).id()).isPresent(), "the newest finished run survives the next sweep");
        require(runs.recent(TENANT, SOURCE, "host", null, 50).size() == 3, "the scope is back at its budget");

        // A run that never closed is protected: retention never drops a scan that is still open, even
        // when a scope holds more rows than its budget because nobody closed the earlier ones.
        var live = new InMemorySyncRunStore(new ScanRunRetention.Policy(1, 100));
        SyncRun open = live.start(TENANT, SOURCE, "host", "labeled-fixture");
        require(live.find(TENANT, open.id()).isPresent(), "the run being opened is never pruned by its own sweep");
        require(live.retained(TENANT, SOURCE, "host") == 0, "an open run is not a finished row yet");
        pause();
        SyncRun second = live.start(TENANT, SOURCE, "host", "labeled-fixture");
        require(live.find(TENANT, second.id()).isPresent() && live.find(TENANT, open.id()).isPresent(),
            "an open run is never evicted under another scan, whatever the budget says");
        live.fail(TENANT, open.id(), "SOURCE_SCAN_UNVERIFIED", "offset-scan-attempt");
        live.succeed(TENANT, second.id(), "hostid-watermark-snapshot");
        require(live.retained(TENANT, SOURCE, "host") == 2,
            "closing a run never prunes it; the budget applies when the next scan opens");
        pause();
        SyncRun third = live.start(TENANT, SOURCE, "host", "labeled-fixture");
        require(live.find(TENANT, open.id()).isEmpty(),
            "the next scan evicts the finished run that no longer fits the budget");
        require(live.find(TENANT, second.id()).isEmpty(),
            "at a budget of one the new scan is the only row left");
        require(live.find(TENANT, third.id()).isPresent() && live.recent(TENANT, SOURCE, "host", null, 50).size() == 1,
            "the open run is the whole scope until it closes");

        // A read never prunes: the trace can report an old scope without destroying what it reads.
        var quiet = new InMemorySyncRunStore(new ScanRunRetention.Policy(1, 100));
        SyncRun kept = finish(quiet, TENANT, SOURCE, "host");
        require(quiet.recent(TENANT, SOURCE, "host", null, 50).size() == 1, "the finished run is stored");
        require(quiet.recent(TENANT, SOURCE, "host", null, 50).size() == 1, "recent() prunes nothing");
        require(quiet.find(TENANT, kept.id()).isPresent(), "reading the trace never deletes the run it reports");
        require(quiet.retained(TENANT, SOURCE, "host") == 1, "retained() prunes nothing either");

        // The budget is per object type and per source: one busy scope cannot evict another's trace.
        var budgeted = new InMemorySyncRunStore(new ScanRunRetention.Policy(5, 100));
        SyncRun hostRun = finish(budgeted, TENANT, SOURCE, "host");
        SyncRun itemRun = finish(budgeted, TENANT, SOURCE, "item");
        SyncRun otherSource = finish(budgeted, TENANT, "zabbix-2", "host");
        require(budgeted.retained(TENANT, SOURCE, "host") == 1
                && budgeted.retained(TENANT, SOURCE, "item") == 1
                && budgeted.retained(TENANT, "zabbix-2", "host") == 1,
            "each scope counts only its own rows");
        require(budgeted.find(TENANT, hostRun.id()).isPresent()
                && budgeted.find(TENANT, itemRun.id()).isPresent()
                && budgeted.find(TENANT, otherSource.id()).isPresent(),
            "a scope sweep never touches another object type or source");

        // The tenant budget spans scopes: a crowded tenant keeps its newest rows and evicts the rest,
        // wherever their scope is. Which of two same-instant runs goes is not part of the contract.
        var tenantWide = new InMemorySyncRunStore(new ScanRunRetention.Policy(2, 2));
        SyncRun firstScope = finish(tenantWide, TENANT, SOURCE, "host");
        SyncRun secondScope = finish(tenantWide, TENANT, "zabbix-2", "host");
        require(tenantRows(tenantWide, firstScope, secondScope) == 2,
            "two finished runs in two scopes fit the tenant budget");
        SyncRun thirdScope = finish(tenantWide, TENANT, SOURCE, "item");
        require(tenantWide.find(TENANT, thirdScope.id()).isPresent(),
            "the run that just closed is never the tenant budget's victim");
        require(tenantRows(tenantWide, firstScope, secondScope, thirdScope) == 2,
            "the tenant sweep brings the whole tenant back to its budget");
        require(tenantWide.find(TENANT, firstScope.id()).isEmpty()
                || tenantWide.find(TENANT, secondScope.id()).isEmpty(),
            "the tenant budget evicts an older run from another scope");
        SyncRun fourthScope = finish(tenantWide, TENANT, "zabbix-2", "item");
        require(tenantRows(tenantWide, firstScope, secondScope, thirdScope, fourthScope) == 2,
            "the tenant stays bounded as more scopes scan");
        require(tenantWide.find(TENANT, fourthScope.id()).isPresent(),
            "each sweep keeps the newest rows of the tenant");

        // Another tenant's runs are neither counted nor pruned by this tenant's budget.
        var isolated = new InMemorySyncRunStore(new ScanRunRetention.Policy(1, 100));
        SyncRun foreign = finish(isolated, OTHER, SOURCE, "host");
        SyncRun mine = finish(isolated, TENANT, SOURCE, "host");
        require(isolated.find(OTHER, foreign.id()).isPresent(), "one tenant's budget never prunes another tenant");
        require(isolated.find(TENANT, mine.id()).isPresent(), "this tenant's own row is kept");
        require(isolated.retained(OTHER, SOURCE, "host") == 1
                && isolated.retained(TENANT, SOURCE, "host") == 1,
            "counting stays inside the tenant even after the other tenant's scan swept");

        System.out.println("ScanRunRetentionSmoke: " + checks + " checks passed");
    }

    private static SyncRun finish(InMemorySyncRunStore runs, TenantId tenant, String source, String objectType) {
        SyncRun run = runs.start(tenant, source, objectType, "labeled-fixture");
        runs.succeed(tenant, run.id(), "hostid-watermark-snapshot");
        return run;
    }

    /**
     * Makes the next run's stored instant strictly later, so the assertion is about the retention
     * contract rather than about which of two runs sharing a millisecond sorts first.
     */
    private static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** How many of the given runs are still stored. Reads every scope, so no scope filter hides one. */
    private static long tenantRows(InMemorySyncRunStore runs, SyncRun... candidates) {
        return List.of(candidates).stream().filter(run -> runs.find(TENANT, run.id()).isPresent()).count();
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
}
