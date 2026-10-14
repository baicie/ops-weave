package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.aicontrol.application.AiRunService;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.platform.ai.AiRunJson;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Real PostgreSQL behaviour of the durable run registry (ADR-104 5a): idempotent creation under
 * concurrency, tenant-scoped readback, bounded cursor pages and the compare-and-set state advance.
 * Submitting a run performs zero model and zero tool calls, which the last test asserts in SQL.
 */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresAiRunIT {
    static DriverManagerDataSource source;
    static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    final TenantId tenant = new TenantId("ai-run-pg-" + UUID.randomUUID());

    /** A movable clock so consecutive submissions keep their own creation instant. */
    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

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

    record Fixture(Principal p, UUID incident, PostgresAiRuns store, AiRunService service, AiRun.Command command, MutableClock clock) {}

    Fixture fixture() {
        var clock = new MutableClock(NOW);
        var p = new Principal(new SubjectId("reader"), tenant, Set.of(Permission.AI_DIAGNOSE, Permission.AI_INSIGHT_READ,
            Permission.INCIDENT_READ, Permission.ENTITY_READ, Permission.METRIC_READ, Permission.EVIDENCE_READ),
            ResourceScope.tenantWide());
        var incidents = new PostgresIncidentStore(source);
        var observation = new ExternalProblem(tenant, "zabbix-1", "101", "201", "Fixture incident", 3,
            NOW.minusSeconds(30), NOW, List.of(), false, null, null);
        incidents.ingest(tenant, "zabbix-1", "labeled-fixture", List.of(observation), NOW);
        var incident = IncidentRecord.initialId(observation);
        var incidentService = new IncidentService(new AuthorizeUseCase(), incidents, clock);
        var store = new PostgresAiRuns(source);
        var skill = new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64));
        var window = new ToolWindow(NOW.minusSeconds(600).truncatedTo(ChronoUnit.SECONDS),
            NOW.minusSeconds(300).truncatedTo(ChronoUnit.SECONDS));
        var command = new AiRun.Command(UUID.randomUUID(), incident, "Summarize the incident", window, "current", skill);
        return new Fixture(p, incident, store, new AiRunService(new AuthorizeUseCase(), incidentService, store, clock), command, clock);
    }

    @Test
    void concurrentIdenticalSubmissionsCreateExactlyOneRun() throws Exception {
        var f = fixture();
        var results = new ArrayList<AiRun.Run>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var jobs = new ArrayList<Future<AiRun.Run>>();
            for (int i = 0; i < 6; i++) jobs.add(workers.submit(() -> { gate.await(); return f.service().submit(f.p(), f.command()); }));
            gate.countDown();
            for (var job : jobs) results.add(job.get(20, TimeUnit.SECONDS));
        }
        assertTrue(results.stream().allMatch(results.getFirst()::equals));
        assertEquals(1, count("ai_control.ai_run"));
        assertEquals(1, count("ai_control.ai_run_command"));
        var reopened = new PostgresAiRuns(source).find(tenant, results.getFirst().runId()).orElseThrow();
        assertEquals(results.getFirst(), reopened);
        // A replay answers with the same run and never creates a second one.
        assertEquals(reopened, f.service().submit(f.p(), f.command()));
        assertEquals(1, count("ai_control.ai_run"));
        // The same request id with different content, or from another subject, is an explicit conflict.
        var changed = new AiRun.Command(f.command().requestId(), f.incident(), "A different question",
            f.command().window(), "current", f.command().skill());
        assertThrows(AiRun.Conflict.class, () -> f.service().submit(f.p(), changed));
        var other = new Principal(new SubjectId("other"), tenant, f.p().permissions(), f.p().resourceScope());
        assertThrows(AiRun.Conflict.class, () -> f.service().submit(other, f.command()));
        assertEquals(1, count("ai_control.ai_run"));
        assertEquals(1, count("ai_control.ai_run_command"));
    }

    @Test
    void readbackIsDurableAndTenantScoped() {
        var f = fixture();
        var submitted = f.service().submit(f.p(), f.command());
        var reopened = new PostgresAiRuns(source).find(tenant, submitted.runId()).orElseThrow();
        assertEquals(submitted, reopened);
        assertEquals(submitted.runId(), new PostgresAiRuns(source).byRequest(tenant, f.command().requestId()).orElseThrow().runId());
        assertTrue(new PostgresAiRuns(source).byRequest(tenant, UUID.randomUUID()).isEmpty());
        assertTrue(new PostgresAiRuns(source).find(new TenantId("other"), submitted.runId()).isEmpty());
        assertEquals(submitted, f.service().get(f.p(), submitted.runId()));
        // The stored body round-trips to the exact same wire record.
        assertEquals(AiRunJson.wire(submitted), AiRunJson.wire(AiRunJson.decode(AiRunJson.storage(submitted), tenant.value(), "reader")));
    }

    @Test
    void pagesCoverEveryRunOnceInOneRetainedOrdering() {
        var f = fixture();
        var submitted = f.service().submit(f.p(), f.command());
        f.clock().now = f.clock().now.plusSeconds(1);
        var second = f.service().submit(f.p(), new AiRun.Command(UUID.randomUUID(), f.incident(), "Second question",
            f.command().window(), "current", f.command().skill()));
        f.clock().now = f.clock().now.plusSeconds(1);
        var third = f.service().submit(f.p(), new AiRun.Command(UUID.randomUUID(), f.incident(), "Third question",
            f.command().window(), "current", f.command().skill()));
        var seen = new LinkedHashSet<UUID>();
        String after = null;
        boolean more = true;
        while (more) {
            var page = f.service().list(f.p(), after, 2);
            for (var item : page.items()) assertTrue(seen.add(item.runId()));
            more = page.hasMore();
            after = page.nextCursor();
        }
        assertEquals(Set.of(submitted.runId(), second.runId(), third.runId()), seen);
        var first = f.service().list(f.p(), null, 2);
        assertTrue(first.hasMore());
        assertEquals(third.runId(), first.items().get(0).runId());
        assertEquals(second.runId(), first.items().get(1).runId());
        assertEquals(second.runId().toString(), first.nextCursor());
        var last = f.service().list(f.p(), first.nextCursor(), 2);
        assertFalse(last.hasMore());
        assertNull(last.nextCursor());
        assertEquals(submitted.runId(), last.items().get(0).runId());
        // A cursor of another owner is not visible and never a guessed position.
        var stranger = new Principal(new SubjectId("stranger"), tenant, f.p().permissions(), f.p().resourceScope());
        assertThrows(ToolFailure.class, () -> f.service().list(stranger, first.nextCursor(), 2));
        assertThrows(ToolFailure.class, () -> f.service().list(f.p(), UUID.randomUUID().toString(), 2));
    }

    @Test
    void stateAdvanceIsACompareAndSetAndTerminalsNeverMove() {
        var f = fixture();
        var submitted = f.service().submit(f.p(), f.command());
        var running = f.store().advance(tenant, submitted.runId(), 0, 0, AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(1))).orElseThrow();
        assertEquals(AiRun.Status.RUNNING, running.status());
        assertEquals(1, running.statusVersion());
        // A stale writer loses the compare-and-set instead of overwriting the newer state.
        assertTrue(f.store().advance(tenant, submitted.runId(), 0, 0, AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(2))).isEmpty());
        assertEquals(1, new PostgresAiRuns(source).find(tenant, submitted.runId()).orElseThrow().statusVersion());
        var waiting = f.store().advance(tenant, submitted.runId(), 1, 0, AiRun.Transition.to(AiRun.Status.WAITING_TOOL, NOW.plusSeconds(2))).orElseThrow();
        assertEquals(AiRun.Status.WAITING_TOOL, waiting.status());
        // A waiting run comes back through RUNNING before it may finish; the machine says so.
        assertThrows(AiRun.Conflict.class, () -> f.store().advance(tenant, submitted.runId(), 2, 0, AiRun.Transition.to(AiRun.Status.SUCCEEDED, NOW.plusSeconds(3))));
        var resumed = f.store().advance(tenant, submitted.runId(), 2, 0, AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(3))).orElseThrow();
        assertEquals(3, resumed.statusVersion());
        var done = f.store().advance(tenant, submitted.runId(), 3, 0, AiRun.Transition.to(AiRun.Status.SUCCEEDED, NOW.plusSeconds(4))).orElseThrow();
        assertEquals(AiRun.Status.SUCCEEDED, done.status());
        assertEquals(done, new PostgresAiRuns(source).find(tenant, submitted.runId()).orElseThrow());
        assertThrows(AiRun.Conflict.class, () -> f.store().advance(tenant, submitted.runId(), 4, 0, AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(5))));
        assertTrue(f.store().advance(tenant, submitted.runId(), 3, 0, AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(5))).isEmpty());
        assertTrue(f.store().advance(tenant, UUID.randomUUID(), 0, 0, AiRun.Transition.to(AiRun.Status.RUNNING, NOW)).isEmpty());
    }

    @Test
    void submittingExecutesNothing() throws Exception {
        var f = fixture();
        f.service().submit(f.p(), f.command());
        assertEquals(1, count("ai_control.ai_run"));
        for (String table : List.of("ai_control.ai_insight", "ai_control.model_spend", "ai_control.tool_read_session")) {
            assertEquals(0, count(table));
        }
    }

    long count(String table) throws Exception {
        try (var c = source.getConnection(); var read = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id=?")) {
            read.setString(1, tenant.value());
            try (var rows = read.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
}
