package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.aicontrol.application.AiRunEventService;
import com.acme.opsweave.aicontrol.application.AiRunService;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Real PostgreSQL behaviour of the ordered run event log (ADR-104 §2.5): records land at the state
 * transition points, sequence numbers are per run and monotonic, the cursor readback is ordered and
 * bounded, replaying a cursor has zero side effects and an expired cursor answers with the retained
 * window plus an explicit gap and the current snapshot.
 */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresAiRunEventIT {
    static DriverManagerDataSource source;
    static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    final TenantId tenant = new TenantId("ai-run-event-pg-" + UUID.randomUUID());

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
        new SchemaMigrator(source).apply("db/migration/V071__ai_run_events.sql", "V071__ai_run_events");
    }

    record Fixture(Principal p, UUID incident, PostgresAiRuns store, AiRunService service, AiRunEventService events,
                   AiRun.Command command, MutableClock clock) {}

    Fixture fixture() {
        return fixture(new PostgresAiRuns(source));
    }

    Fixture fixture(PostgresAiRuns store) {
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
        var skill = new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64));
        var window = new ToolWindow(NOW.minusSeconds(600).truncatedTo(ChronoUnit.SECONDS),
            NOW.minusSeconds(300).truncatedTo(ChronoUnit.SECONDS));
        var command = new AiRun.Command(UUID.randomUUID(), incident, "Summarize the incident", window, "current", skill);
        var service = new AiRunService(new AuthorizeUseCase(), incidentService, store, clock);
        return new Fixture(p, incident, store, service, new AiRunEventService(service, store), command, clock);
    }

    @Test
    void eventsAreRecordedAtStateTransitionPointsAndRejectedMovesRecordNothing() {
        var f = fixture();
        var submitted = f.service().submit(f.p(), f.command());
        var uuid = submitted.runId();
        var claim = f.store().claim(tenant, uuid, "worker", NOW.plusSeconds(1), Duration.ofSeconds(90), Duration.ofSeconds(65)).orElseThrow();
        f.store().advance(tenant, uuid, claim.run().statusVersion(), claim.fence(),
            new AiRun.Transition(AiRun.Status.WAITING_TOOL, null, null, NOW.plusSeconds(2)));
        // A stale compare-and-set is rejected and records nothing: an event describes an applied move.
        assertTrue(f.store().advance(tenant, uuid, 0, claim.fence(),
            AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(3))).isEmpty());
        assertEquals(3, f.store().read(tenant, uuid, 0, 50).size());
        f.store().append(tenant, uuid, AiRunEvent.Type.RECONCILED, NOW.plusSeconds(3),
            AiRunEvent.Payload.reconciliation(new AiRunControl.Reconciliation(false, false, 1, 1, true)));
        f.store().advance(tenant, uuid, 2, claim.fence(),
            AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(4)));
        f.store().requestCancel(tenant, uuid, NOW.plusSeconds(5));
        f.store().requestCancel(tenant, uuid, NOW.plusSeconds(6));
        var landed = f.store().advance(tenant, uuid, 3, claim.fence(),
            new AiRun.Transition(AiRun.Status.CANCELLED, AiRun.Reason.CANCELLED_BY_REQUEST, null, NOW.plusSeconds(7))).orElseThrow();
        var log = f.store().read(tenant, uuid, 0, 50);
        assertEquals(List.of(AiRunEvent.Type.SUBMITTED, AiRunEvent.Type.CLAIMED, AiRunEvent.Type.TOOL_WAIT,
            AiRunEvent.Type.RECONCILED, AiRunEvent.Type.STATUS_CHANGED, AiRunEvent.Type.CANCEL_REQUESTED,
            AiRunEvent.Type.FINISHED), log.stream().map(AiRunEvent.Event::type).toList());
        for (int i = 0; i < log.size(); i++) assertEquals(i + 1, log.get(i).seq());
        assertEquals(Integer.valueOf(0), log.get(0).payload().attempt());
        assertEquals(Integer.valueOf(1), log.get(1).payload().attempt());
        assertEquals("WAITING_TOOL", log.get(2).payload().status());
        assertEquals(1, log.get(3).payload().disclosure().priorModelCalls());
        assertEquals("RUNNING", log.get(4).payload().status());
        assertNull(log.get(5).payload().status());
        assertEquals("CANCELLED", log.get(6).payload().status());
        assertEquals("CANCELLED_BY_REQUEST", log.get(6).payload().reason());
        assertEquals(log.get(6).occurredAt(), landed.updatedAt());
        // A terminal run changes nothing any more: cancelling is idempotent and records no second event.
        assertTrue(f.store().requestCancel(tenant, uuid, NOW.plusSeconds(8)).isEmpty());
        assertEquals(7, f.store().read(tenant, uuid, 0, 50).size());
        assertEquals(new AiRunEvent.Bounds(1, 7), f.store().bounds(tenant, uuid));
    }

    @Test
    void cursorReadbackIsOrderedBoundedAndReplayHasNoSideEffects() {
        var f = fixture();
        var run = f.service().submit(f.p(), f.command());
        var uuid = run.runId();
        var claim = f.store().claim(tenant, uuid, "worker", NOW.plusSeconds(1), Duration.ofSeconds(90), Duration.ofSeconds(65)).orElseThrow();
        f.store().advance(tenant, uuid, claim.run().statusVersion(), claim.fence(),
            new AiRun.Transition(AiRun.Status.WAITING_TOOL, null, null, NOW.plusSeconds(2)));
        f.store().advance(tenant, uuid, 2, claim.fence(),
            AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(3)));
        f.store().advance(tenant, uuid, 3, claim.fence(),
            new AiRun.Transition(AiRun.Status.SUCCEEDED, null, null, NOW.plusSeconds(4)));
        assertEquals(5, f.store().read(tenant, uuid, 0, 50).size());
        // Bounded pages cover every record exactly once in the retained ordering seq ASC.
        var seen = new ArrayList<Long>();
        String after = null;
        boolean more = true;
        while (more) {
            var page = f.events().read(f.p(), uuid, after, 2);
            assertTrue(page.items().size() <= 2);
            for (var event : page.items()) seen.add(event.seq());
            more = page.hasMore();
            after = page.nextCursor();
            assertEquals(more, after != null);
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), seen);
        var tail = f.events().read(f.p(), uuid, "5", 2);
        assertTrue(tail.items().isEmpty() && !tail.hasMore() && tail.nextCursor() == null && !tail.gap());
        // Replaying a cursor re-delivers the same records: reading is a projection, not a command.
        var before = new PostgresAiRuns(source).find(tenant, uuid).orElseThrow();
        var first = f.events().read(f.p(), uuid, "1", 2);
        assertEquals(first, f.events().read(f.p(), uuid, "1", 2));
        assertEquals(first.items(), f.events().read(f.p(), uuid, "1", 2).items());
        assertEquals(before, new PostgresAiRuns(source).find(tenant, uuid).orElseThrow());
        assertEquals(before.statusVersion(), new PostgresAiRuns(source).find(tenant, uuid).orElseThrow().statusVersion());
        assertEquals(5, count("ai_control.ai_run_event"));
        // The page carries the current snapshot and the cursor names the last record exactly.
        assertEquals("SUCCEEDED", first.snapshot().status());
        assertEquals(4, first.snapshot().statusVersion());
        assertEquals("3", first.nextCursor());
        // Closed query bounds: an illegal cursor or page size is an invalid request, never a guess.
        assertThrows(ToolFailure.class, () -> f.events().read(f.p(), uuid, "-1", 2));
        assertThrows(ToolFailure.class, () -> f.events().read(f.p(), uuid, "01", 2));
        assertThrows(ToolFailure.class, () -> f.events().read(f.p(), uuid, "x", 2));
        assertThrows(ToolFailure.class, () -> f.events().read(f.p(), uuid, null, 0));
        assertThrows(ToolFailure.class, () -> f.events().read(f.p(), uuid, null, 51));
        // The run read boundary is the event read boundary: unknown or foreign runs are 404.
        assertThrows(ToolFailure.class, () -> f.events().read(f.p(), UUID.randomUUID(), null, 2));
    }

    @Test
    void anExpiredCursorFallsBackToTheRetainedWindowWithAnExplicitGap() {
        var small = new PostgresAiRuns(source, 3);
        var f = fixture(small);
        var run = f.service().submit(f.p(), f.command());
        var uuid = run.runId();
        for (int i = 0; i < 5; i++) {
            f.store().append(tenant, uuid, AiRunEvent.Type.RECONCILED, NOW.plusSeconds(i),
                AiRunEvent.Payload.reconciliation(AiRunControl.Reconciliation.clean()));
        }
        assertEquals(new AiRunEvent.Bounds(4, 6), f.store().bounds(tenant, uuid));
        assertEquals(3, f.store().read(tenant, uuid, 0, 50).size());
        // The cursor expired: the answer is the retained window plus the current snapshot and gap=true.
        var page = f.events().read(f.p(), uuid, "0", 50);
        assertTrue(page.gap());
        assertEquals(List.of(4L, 5L, 6L), page.items().stream().map(AiRunEvent.Event::seq).toList());
        assertEquals("PENDING", page.snapshot().status());
        assertEquals(0, page.snapshot().statusVersion());
        assertEquals(0, page.snapshot().attempt());
        // A cursor at the edge of the retained window is continuous again and the gap closes.
        assertFalse(f.events().read(f.p(), uuid, "3", 50).gap());
        assertFalse(f.events().read(f.p(), uuid, "6", 50).gap());
        assertFalse(f.events().read(f.p(), uuid, "0", 50).items().isEmpty());
        assertEquals(4, f.events().read(f.p(), uuid, "0", 50).items().get(0).seq());
    }

    @Test
    void readingEventsExecutesNothing() throws Exception {
        var f = fixture();
        var run = f.service().submit(f.p(), f.command());
        var uuid = run.runId();
        for (int i = 0; i < 3; i++) {
            f.events().read(f.p(), uuid, null, 50);
            f.events().read(f.p(), uuid, String.valueOf(i), 50);
        }
        assertEquals(1, count("ai_control.ai_run_event"));
        for (String table : List.of("ai_control.ai_insight", "ai_control.model_spend", "ai_control.tool_read_session")) {
            assertEquals(0, count(table));
        }
        assertEquals(AiRun.Status.PENDING, new PostgresAiRuns(source).find(tenant, uuid).orElseThrow().status());
    }

    long count(String table) {
        try (var c = source.getConnection(); var read = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id=?")) {
            read.setString(1, tenant.value());
            try (var rows = read.executeQuery()) { rows.next(); return rows.getLong(1); }
        } catch (Exception failed) { throw new IllegalStateException(failed); }
    }
}
