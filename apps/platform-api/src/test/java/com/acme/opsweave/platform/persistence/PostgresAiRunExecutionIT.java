package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.aicontrol.api.*;
import com.acme.opsweave.aicontrol.application.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.platform.ai.AiRunJson;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Real PostgreSQL behaviour of the AIRun executor seam (ADR-104 5b): lease/fencing claims that never
 * double-run after a worker crash, crash recovery that reconciles unknown completion states (model
 * fee receipt + saved AIInsight) before claiming or re-running, cooperative cancellation at safe
 * points, re-authorization before recovery, and the explicit duplicate-fee disclosure.
 *
 * <p>The insight store and the diagnosis dispatch are <b>explicit test fixtures</b> (the real chain
 * is the M4 synchronous diagnosis; its own persistence is covered elsewhere). Model fee receipts are
 * real {@code ai_control.model_spend} rows through the real store, so the fee accounting and the
 * "one model call per diagnosis" invariant are asserted in SQL.
 */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresAiRunExecutionIT {
    static DriverManagerDataSource source;
    static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    final TenantId tenant = new TenantId("ai-run-exec-pg-" + UUID.randomUUID());

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"),
            System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        for (String version : List.of("V002__host_sync", "V003__metric_definition", "V004__metric_catalog", "V007__pipeline_version",
            "V008__pipeline_replay", "V009__pipeline_draft", "V010__external_problem_incident", "V013__incident_reorganization",
            "V011__tool_read_evidence", "V012__ai_insight")) {
            new SchemaMigrator(source).apply("db/migration/" + version + ".sql", version);
        }
        new SchemaMigrator(source).apply("db/migration/V016__problem_observation.sql", "V016__problem_observation");
        new SchemaMigrator(source).apply("db/migration/V019__ai_content_retention.sql", "V019__ai_content_retention");
        new SchemaMigrator(source).apply("db/migration/V069__ai_run_registry.sql", "V069__ai_run_registry");
        new SchemaMigrator(source).apply("db/migration/V070__ai_run_execution.sql", "V070__ai_run_execution");
    }

    /** Explicit test fixture: saved complete results keyed by run id. */
    static final class FixtureInsightStore implements AiInsightStore {
        final Map<String, AiInsight> saved = new ConcurrentHashMap<>();
        public Optional<AiInsight> find(TenantId t, UUID id) { return Optional.ofNullable(saved.get(t.value() + "|" + id)); }
        public AiInsight save(AiInsight insight) { saved.put(insight.tenantId().value() + "|" + insight.input().runId(), insight); return insight; }
    }

    /** Explicit test fixture: the diagnosis chain stand-in with injectable outcomes and races. */
    static final class FixtureDispatch implements AiRunDispatch {
        volatile String mode = "ok";
        volatile int calls;
        volatile Runnable during;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        final FixtureInsightStore insights;
        final TenantId tenant;
        final SubjectId subject;
        final UUID incident;
        final ToolWindow window;
        FixtureDispatch(FixtureInsightStore insights, TenantId tenant, SubjectId subject, UUID incident, ToolWindow window) {
            this.insights = insights; this.tenant = tenant; this.subject = subject; this.incident = incident; this.window = window;
        }
        public void dispatch(AiRun.Run run, Principal as) {
            calls++;
            if (entered != null) entered.countDown();
            if (release != null) {
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new ToolFailure(ToolFailure.Code.DEADLINE);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ToolFailure(ToolFailure.Code.DEADLINE);
                }
            }
            if (during != null) during.run();
            if ("fail".equals(mode)) throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);
            if ("deadline".equals(mode)) throw new ToolFailure(ToolFailure.Code.DEADLINE);
            if ("save-then-fail".equals(mode)) {
                insights.save(fixtureInsight(run.runId()));
                throw new ToolFailure(ToolFailure.Code.DEADLINE);
            }
            if (!"none".equals(mode)) insights.save(fixtureInsight(run.runId()));
        }
        AiInsight fixtureInsight(UUID runId) {
            var evidence = List.of(UUID.randomUUID(), UUID.randomUUID());
            var draft = new InsightDraft("Fixture summary",
                List.of(new InsightDraft.Finding("observation", "Fixture observed", List.of(evidence.get(0)))), List.of(), List.of());
            var at = Instant.parse("2026-10-13T01:00:30Z");
            var input = new InsightSubmission(runId, UUID.randomUUID(), "Summarize the incident", at.minusSeconds(2),
                at.minusSeconds(1), at, new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64)),
                new InsightSubmission.ModelRef("mock-deterministic", "mock-current-v1"), evidence, draft);
            return new AiInsight(tenant, subject, incident, 1, Set.of(), window, input, "sha256:" + "a".repeat(64), at,
                at.plusSeconds(3600), List.of("labeled-fixture"), List.of("CURRENT_KNOWLEDGE_ONLY"));
        }
    }

    record Fixture(Principal p, UUID incident, PostgresAiRuns store, ModelSpendStore spend, FixtureInsightStore insights,
                   FixtureDispatch dispatch, AiRunService service, AiRunExecutionService execution,
                   AiRunControlService control, InsightSubmission.SkillRef skill, ToolWindow window) {}

    Fixture fixture() {
        return fixture(new Principal(new SubjectId("reader"), tenant, Set.of(Permission.AI_DIAGNOSE, Permission.AI_INSIGHT_READ,
            Permission.INCIDENT_READ, Permission.ENTITY_READ, Permission.METRIC_READ, Permission.EVIDENCE_READ),
            ResourceScope.tenantWide()));
    }

    Fixture fixture(Principal p) {
        var clock = Clock.systemUTC();
        var incidents = new PostgresIncidentStore(source);
        var observation = new ExternalProblem(tenant, "zabbix-1", "101", "201", "Fixture incident", 3,
            NOW.minusSeconds(30), NOW, List.of(), false, null, null);
        incidents.ingest(tenant, "zabbix-1", "labeled-fixture", List.of(observation), NOW);
        var incident = IncidentRecord.initialId(observation);
        var incidentService = new IncidentService(new AuthorizeUseCase(), incidents, clock);
        var store = new PostgresAiRuns(source);
        var spend = new PostgresModelSpendStore(source);
        var skill = new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64));
        var window = new ToolWindow(NOW.minusSeconds(600).truncatedTo(ChronoUnit.SECONDS),
            NOW.minusSeconds(300).truncatedTo(ChronoUnit.SECONDS));
        var insights = new FixtureInsightStore();
        var dispatch = new FixtureDispatch(insights, tenant, p.subjectId(), incident, window);
        var execution = new AiRunExecutionService(store, insights, spend, dispatch, new AuthorizeUseCase(), incidentService,
            clock, "it-worker");
        var control = new AiRunControlService(store, insights, spend, execution, new AuthorizeUseCase(), incidentService, clock);
        return new Fixture(p, incident, store, spend, insights, dispatch,
            new AiRunService(new AuthorizeUseCase(), incidentService, store, clock), execution, control, skill, window);
    }

    AiRun.Command command(Fixture f, String question) {
        return new AiRun.Command(UUID.randomUUID(), f.incident(), question,
            new ToolWindow(Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS),
                Instant.now().minusSeconds(3000).truncatedTo(ChronoUnit.SECONDS)),
            "current", f.skill());
    }

    ModelSpend.Call fee(Fixture f, UUID runId, Instant reservedAt) {
        return new ModelSpend.Call(tenant, f.p().subjectId(), runId, UUID.randomUUID(), f.incident(),
            "sha256:" + "b".repeat(64), 32, ModelSpend.Policy.mock(), reservedAt, reservedAt.plusSeconds(60), null, null);
    }

    void expireLease(UUID runId) throws Exception {
        try (var c = source.getConnection(); var update = c.prepareStatement(
            "UPDATE ai_control.ai_run SET lease_until=clock_timestamp()-interval '1 second' WHERE tenant_id=? AND run_id=?")) {
            update.setString(1, tenant.value());
            update.setObject(2, runId);
            assertEquals(1, update.executeUpdate());
        }
    }

    Map<String, Object> row(UUID runId) throws Exception {
        try (var c = source.getConnection(); var read = c.prepareStatement(
            "SELECT status,status_version,attempt_no,fence,lease_until,cancel_requested_at_text,reason,body::text AS body"
                + " FROM ai_control.ai_run WHERE tenant_id=? AND run_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                assertTrue(rows.next());
                var row = new LinkedHashMap<String, Object>();
                row.put("status", rows.getString("status"));
                row.put("statusVersion", rows.getLong("status_version"));
                row.put("attempt", rows.getInt("attempt_no"));
                row.put("fence", rows.getLong("fence"));
                row.put("leaseUntil", rows.getTimestamp("lease_until"));
                row.put("cancelRequestedAt", rows.getString("cancel_requested_at_text"));
                row.put("reason", rows.getString("reason"));
                row.put("body", rows.getString("body"));
                return row;
            }
        }
    }

    long count(String table) throws Exception {
        try (var c = source.getConnection(); var read = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id=?")) {
            read.setString(1, tenant.value());
            try (var rows = read.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    @Test
    void concurrentClaimsTakeExactlyOneAttempt() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), command(f, "Concurrent claim"));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var jobs = new ArrayList<Future<Optional<AiRunStore.Claim>>>();
            for (int i = 0; i < 6; i++) {
                jobs.add(workers.submit(() -> {
                    gate.await();
                    return f.store().claim(tenant, run.runId(), "worker-" + Thread.currentThread().getId(), Instant.now(),
                        AiRunExecutionService.LEASE, AiRunExecutionService.ATTEMPT_DEADLINE);
                }));
            }
            gate.countDown();
            int winners = 0;
            long fence = 0;
            for (var job : jobs) {
                var claim = job.get(20, TimeUnit.SECONDS);
                if (claim.isPresent()) { winners++; fence = claim.get().fence(); }
            }
            assertEquals(1, winners);
            assertEquals(1, fence);
        }
        var stored = row(run.runId());
        assertEquals("RUNNING", stored.get("status"));
        assertEquals(1, stored.get("attempt"));
        assertEquals(1L, stored.get("fence"));
        assertNotNull(stored.get("leaseUntil"));
    }

    @Test
    void aLostLeaseRejectsTheStaleWorkersCommit() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), command(f, "Fenced worker"));
        var stale = f.store().claim(tenant, run.runId(), "worker-a", Instant.now(), AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        expireLease(run.runId());
        var fresh = f.store().claim(tenant, run.runId(), "worker-b", Instant.now(), AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        assertEquals(2, fresh.attempt());
        assertTrue(fresh.fence() > stale.fence());
        // The crashed worker's late result is rejected: its fencing token no longer matches.
        assertTrue(f.store().advance(tenant, run.runId(), stale.run().statusVersion(), stale.fence(),
            new AiRun.Transition(AiRun.Status.FAILED, AiRun.Reason.EXECUTION_FAILED, null, Instant.now())).isEmpty());
        assertEquals("RUNNING", row(run.runId()).get("status"));
        assertTrue(f.store().advance(tenant, run.runId(), fresh.run().statusVersion(), fresh.fence(),
            AiRun.Transition.to(AiRun.Status.SUCCEEDED, Instant.now())).isPresent());
        var stored = row(run.runId());
        assertEquals("SUCCEEDED", stored.get("status"));
        assertNull(stored.get("leaseUntil"));
    }

    @Test
    void recoveryClaimsASavedResultInsteadOfRunningAgain() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), command(f, "Crashed after save"));
        f.store().claim(tenant, run.runId(), "worker-a", Instant.now(), AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        f.insights().save(f.dispatch().fixtureInsight(run.runId()));
        expireLease(run.runId());
        int calls = f.dispatch().calls;
        var done = f.execution().execute(tenant, run.runId(), f.p()).orElseThrow();
        assertEquals(AiRun.Status.SUCCEEDED, done.status());
        assertEquals(2, done.attempt());
        assertTrue(done.disclosure().adoptedPriorResult());
        assertEquals(AiRunControl.Decision.ADOPT, done.disclosure().decision());
        assertEquals(calls, f.dispatch().calls);
        assertEquals(1, f.insights().find(tenant, run.runId()).stream().count());
    }

    @Test
    void recoveryDisclosesSpentFeesAndNeverRerunsTheModel() throws Exception {
        var f = fixture();
        var lost = new ArrayList<UUID>();
        for (String question : List.of("Crashed after fee one", "Crashed after fee two")) {
            var run = f.service().submit(f.p(), command(f, question));
            f.store().claim(tenant, run.runId(), "worker-a", Instant.now(), AiRunExecutionService.LEASE,
                AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
            f.spend().reserve(fee(f, run.runId(), Instant.now().minusSeconds(300)));
            expireLease(run.runId());
            int calls = f.dispatch().calls;
            var done = f.execution().execute(tenant, run.runId(), f.p()).orElseThrow();
            assertEquals(AiRun.Status.FAILED, done.status());
            assertEquals(AiRun.Reason.RESULT_LOST, done.reason());
            assertEquals(1, done.disclosure().priorModelCalls());
            assertEquals(1, done.disclosure().uncertainPriorFees());
            assertTrue(done.disclosure().duplicateFeeRisk());
            assertEquals(calls, f.dispatch().calls); // the one-call-per-diagnosis invariant: no second provider call
            lost.add(run.runId());
        }
        // The fees are retained (never refunded) and each loss was disclosed with its count.
        assertEquals(2, count("ai_control.model_spend"));
        assertEquals(2, f.spend().metrics(tenant,
            new ModelSpend.MetricsQuery(NOW.minusSeconds(3600), Instant.now(), null, 10), Instant.now())
            .stream().mapToLong(ModelSpend.Metric::uncertainCalls).sum());
        // A recovery command of the same unknown state discloses the same counts in its receipt.
        var run = f.service().get(f.p(), lost.get(0));
        assertEquals(AiRun.Status.FAILED, run.status());
        assertEquals(1, run.disclosure().uncertainPriorFees());
    }

    @Test
    void cooperativeCancellationLandsAtSafePointsAndRacesCompletion() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), command(f, "Cancel me"));
        var command = new AiRunControl.Command(AiRunControl.Kind.CANCEL, UUID.randomUUID(), run.runId());
        var receipt = f.control().cancel(f.p(), run.runId(), command);
        assertTrue(receipt.recorded());
        assertEquals(AiRun.Status.PENDING, receipt.status());
        var recorded = row(run.runId());
        assertEquals(0L, recorded.get("statusVersion"));
        assertEquals("PENDING", recorded.get("status"));
        assertNotNull(recorded.get("cancelRequestedAt"));
        int calls = f.dispatch().calls;
        var cancelled = f.execution().execute(tenant, run.runId(), f.p()).orElseThrow();
        assertEquals(AiRun.Status.CANCELLED, cancelled.status());
        assertEquals(AiRun.Reason.CANCELLED_BY_REQUEST, cancelled.reason());
        assertEquals(calls, f.dispatch().calls);
        assertFalse(f.insights().find(tenant, run.runId()).isPresent());
        // The replayed command answers with the original receipt; a terminal run changes nothing.
        assertEquals(receipt, f.control().cancel(f.p(), run.runId(), command));
        var late = f.control().cancel(f.p(), run.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, UUID.randomUUID(), run.runId()));
        assertFalse(late.recorded());
        assertEquals(AiRun.Status.CANCELLED, late.status());
        assertEquals(2, count("ai_control.ai_run_control_command"));
        assertEquals("CANCELLED", row(run.runId()).get("status"));

        // A cancellation racing the dispatch lands at the post-dispatch safe point.
        var raced = f.service().submit(f.p(), command(f, "Cancel mid-dispatch"));
        f.dispatch().mode = "deadline";
        f.dispatch().entered = new CountDownLatch(1);
        f.dispatch().release = new CountDownLatch(1);
        var executed = new CompletableFuture<AiRun.Run>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = pool.submit(() -> f.execution().execute(tenant, raced.runId(), f.p()).orElseThrow());
            assertTrue(f.dispatch().entered.await(20, TimeUnit.SECONDS));
            assertTrue(f.control().cancel(f.p(), raced.runId(),
                new AiRunControl.Command(AiRunControl.Kind.CANCEL, UUID.randomUUID(), raced.runId())).recorded());
            f.dispatch().release.countDown();
            executed.complete(task.get(30, TimeUnit.SECONDS));
        } finally {
            f.dispatch().release.countDown();
            f.dispatch().entered = null;
            f.dispatch().release = null;
        }
        var racedDone = executed.get();
        assertEquals(AiRun.Status.CANCELLED, racedDone.status());
        assertEquals(AiRun.Reason.CANCELLED_BY_REQUEST, racedDone.reason());
        assertFalse(f.insights().find(tenant, raced.runId()).isPresent());
    }

    @Test
    void recoveryReauthorizesTheCurrentIncidentBeforeRunning() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), command(f, "Revoked owner"));
        f.store().claim(tenant, run.runId(), "worker-a", Instant.now(), AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        expireLease(run.runId());
        // Withdrawn permission: the recovery command is rejected and nothing runs.
        var denied = new Principal(f.p().subjectId(), tenant, Set.of(Permission.AI_INSIGHT_READ), ResourceScope.tenantWide());
        assertThrows(ToolFailure.class, () -> f.control().retry(denied, run.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, UUID.randomUUID(), run.runId())));
        assertEquals(1, row(run.runId()).get("attempt"));
        // Withdrawn object scope: same rejection.
        var narrowed = new Principal(f.p().subjectId(), tenant, f.p().permissions(),
            ResourceScope.of(Set.of(ResourceRef.incident(tenant, UUID.randomUUID()))));
        assertThrows(ToolFailure.class, () -> f.control().retry(narrowed, run.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, UUID.randomUUID(), run.runId())));
        assertEquals(1, row(run.runId()).get("attempt"));
        // An executor attempt with withdrawn authority fails the run closed instead of executing.
        int calls = f.dispatch().calls;
        var failed = f.execution().execute(tenant, run.runId(), narrowed).orElseThrow();
        assertEquals(AiRun.Status.FAILED, failed.status());
        assertEquals(AiRun.Reason.AUTHORIZATION_REVOKED, failed.reason());
        assertEquals(calls, f.dispatch().calls);
        assertEquals(2, row(run.runId()).get("attempt"));
    }

    @Test
    void recoveryOfAnUnfinishedRunTakesANewAttemptAndSucceeds() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), command(f, "Crashed before work"));
        f.store().claim(tenant, run.runId(), "worker-a", Instant.now(), AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        expireLease(run.runId());
        var retry = new AiRunControl.Command(AiRunControl.Kind.RETRY, UUID.randomUUID(), run.runId());
        var receipt = f.control().retry(f.p(), run.runId(), retry);
        assertEquals(2, receipt.attempt());
        assertEquals(AiRun.Status.SUCCEEDED, receipt.status());
        assertTrue(receipt.reconciliation().rerun());
        assertEquals(0, receipt.reconciliation().priorModelCalls());
        assertEquals("SUCCEEDED", row(run.runId()).get("status"));
        assertEquals(2, row(run.runId()).get("attempt"));
        // The replayed command answers with the original receipt and runs nothing again.
        int calls = f.dispatch().calls;
        assertEquals(receipt, f.control().retry(f.p(), run.runId(), retry));
        assertEquals(calls, f.dispatch().calls);
        assertEquals(1, count("ai_control.ai_run_control_command"));
    }

    @Test
    void onlyASucceededRunCarriesAConclusion() throws Exception {
        var f = fixture();
        var failed = f.service().submit(f.p(), command(f, "Failing chain"));
        f.dispatch().mode = "fail";
        var failedRun = f.execution().execute(tenant, failed.runId(), f.p()).orElseThrow();
        assertEquals(AiRun.Status.FAILED, failedRun.status());
        assertEquals("RUNTIME_UNAVAILABLE", row(failed.runId()).get("reason"));
        assertFalse(f.insights().find(tenant, failed.runId()).isPresent());
        // A complete saved result is claimed even when the dispatch reports failure afterwards.
        f.dispatch().mode = "save-then-fail";
        var adopted = f.service().submit(f.p(), command(f, "Saved before failure"));
        var adoptedRun = f.execution().execute(tenant, adopted.runId(), f.p()).orElseThrow();
        assertEquals(AiRun.Status.SUCCEEDED, adoptedRun.status());
        assertTrue(f.insights().find(tenant, adopted.runId()).isPresent());
        // The healthy chain writes exactly one conclusion beside exactly one SUCCEEDED run.
        f.dispatch().mode = "ok";
        var good = f.service().submit(f.p(), command(f, "Healthy chain"));
        var goodRun = f.execution().execute(tenant, good.runId(), f.p()).orElseThrow();
        assertEquals(AiRun.Status.SUCCEEDED, goodRun.status());
        assertTrue(f.insights().find(tenant, good.runId()).isPresent());
        assertEquals(2, f.insights().saved.size());
    }

    @Test
    void controlCommandsAreIdempotentAndShareNoRequestIds() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), command(f, "Command identity"));
        // A control request id may not collide with a submission request id, either way round.
        assertThrows(AiRun.Conflict.class, () -> f.control().cancel(f.p(), run.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, run.requestId(), run.runId())));
        assertThrows(AiRun.Conflict.class, () -> f.control().retry(f.p(), run.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, run.requestId(), run.runId())));
        // Same request id, different target run: an explicit conflict, never a second receipt.
        var cancel = new AiRunControl.Command(AiRunControl.Kind.CANCEL, UUID.randomUUID(), run.runId());
        f.control().cancel(f.p(), run.runId(), cancel);
        var other = f.service().submit(f.p(), command(f, "Another run"));
        assertThrows(AiRun.Conflict.class, () -> f.control().cancel(f.p(), other.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, cancel.requestId(), other.runId())));
        assertEquals(1, count("ai_control.ai_run_control_command"));
        // The stored receipt round-trips to the exact same wire record.
        var stored = new PostgresAiRuns(source).findCancel(tenant, cancel.requestId()).orElseThrow();
        assertEquals(AiRunJson.wire(stored), AiRunJson.wire(AiRunJson.cancelReceipt(AiRunJson.storage(stored))));
    }

    @Test
    void theSweepRecoversLeaseExpiredRuns() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), command(f, "Sweep me"));
        f.store().claim(tenant, run.runId(), "worker-a", Instant.now(), AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        expireLease(run.runId());
        var swept = f.execution().tick(f.p(), 10);
        assertEquals(1, swept.size());
        assertEquals(AiRun.Status.SUCCEEDED, swept.getFirst().status());
        assertEquals(2, row(run.runId()).get("attempt"));
        assertTrue(f.execution().tick(f.p(), 10).isEmpty());
    }
}
