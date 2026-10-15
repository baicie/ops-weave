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
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Real PostgreSQL behaviour of the M5 5d cross-store seams: the Runtime's idempotent state delivery
 * (a bounded receipt projection that never moves a run), the disclose-only reconciliation between
 * the run registry and the delivered Runtime state, and the bounded observability aggregate with
 * honest event-truncation counters.
 *
 * <p>Divergence is asserted from real rows: after a reconciliation the {@code ai_control.ai_run}
 * row is byte-for-byte unchanged, the event log gained nothing and no model fee row appeared —
 * reconciliation discloses, it never repairs, never re-runs and never double-runs. The insight store
 * is an <b>explicit test fixture</b>; model fee receipts are real {@code ai_control.model_spend}
 * rows through the real store.
 */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresAiRunRuntimeStateIT {
    static DriverManagerDataSource source;
    static final Instant NOW = Instant.parse("2026-10-14T01:00:00Z");
    final TenantId tenant = new TenantId("ai-run-runtime-pg-" + UUID.randomUUID());

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"),
            System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        for (String version : List.of("V002__host_sync", "V003__metric_definition", "V004__metric_catalog", "V007__pipeline_version",
            "V008__pipeline_replay", "V009__pipeline_draft", "V010__external_problem_incident", "V013__incident_reorganization",
            "V011__tool_read_evidence", "V012__ai_insight", "V018__model_spend")) {
            new SchemaMigrator(source).apply("db/migration/" + version + ".sql", version);
        }
        new SchemaMigrator(source).apply("db/migration/V016__problem_observation.sql", "V016__problem_observation");
        new SchemaMigrator(source).apply("db/migration/V019__ai_content_retention.sql", "V019__ai_content_retention");
        new SchemaMigrator(source).apply("db/migration/V069__ai_run_registry.sql", "V069__ai_run_registry");
        new SchemaMigrator(source).apply("db/migration/V070__ai_run_execution.sql", "V070__ai_run_execution");
        new SchemaMigrator(source).apply("db/migration/V071__ai_run_events.sql", "V071__ai_run_events");
        new SchemaMigrator(source).apply("db/migration/V072__ai_run_runtime_state.sql", "V072__ai_run_runtime_state");
    }

    /** Explicit test fixture: saved complete results keyed by run id, nothing else. */
    static final class FixtureInsightStore implements AiInsightStore {
        final Map<String, AiInsight> saved = new HashMap<>();
        public Optional<AiInsight> find(TenantId t, UUID id) { return Optional.ofNullable(saved.get(t.value() + "|" + id)); }
        public AiInsight save(AiInsight insight) { saved.put(insight.tenantId().value() + "|" + insight.input().runId(), insight); return insight; }
    }

    record Fixture(Principal p, UUID incident, PostgresAiRuns store, PostgresAiRunRuntimeStates states,
                   ModelSpendStore spend, AiRunService service, AiRunRuntimeStateService runtimeStates,
                   AiRun.Command command, Instant created) {}

    Fixture fixture() {
        return fixture(new PostgresAiRuns(source));
    }

    Fixture fixture(PostgresAiRuns store) {
        var p = new Principal(new SubjectId("reader"), tenant, Set.of(Permission.AI_DIAGNOSE, Permission.AI_INSIGHT_READ,
            Permission.INCIDENT_READ, Permission.ENTITY_READ, Permission.METRIC_READ, Permission.EVIDENCE_READ),
            ResourceScope.tenantWide());
        var incidents = new PostgresIncidentStore(source);
        var observation = new ExternalProblem(tenant, "zabbix-1", "101", "201", "Fixture incident", 3,
            NOW.minusSeconds(30), NOW, List.of(), false, null, null);
        incidents.ingest(tenant, "zabbix-1", "labeled-fixture", List.of(observation), NOW);
        var incident = IncidentRecord.initialId(observation);
        var incidentService = new IncidentService(new AuthorizeUseCase(), incidents, Clock.fixed(NOW, ZoneOffset.UTC));
        var skill = new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64));
        var window = new ToolWindow(NOW.minusSeconds(600).truncatedTo(ChronoUnit.SECONDS),
            NOW.minusSeconds(300).truncatedTo(ChronoUnit.SECONDS));
        var command = new AiRun.Command(UUID.randomUUID(), incident, "Summarize the incident", window, "current", skill);
        var service = new AiRunService(new AuthorizeUseCase(), incidentService, store, Clock.fixed(NOW, ZoneOffset.UTC));
        service.submit(p, command);
        var states = new PostgresAiRunRuntimeStates(source);
        var spend = new PostgresModelSpendStore(source);
        var runtimeStates = new AiRunRuntimeStateService(store, states, new FixtureInsightStore(), spend, Clock.systemUTC());
        return new Fixture(p, incident, store, states, spend, service, runtimeStates, command, NOW);
    }

    static AiRunRuntimeState.Report attestation(UUID runId, String requestId, int attempt, String status, Instant at) {
        return new AiRunRuntimeState.Report(UUID.fromString(requestId), runId, attempt, status, 2, 1, 1, at);
    }

    private String runRow(UUID runId) throws Exception {
        try (var c = source.getConnection();
             var read = c.prepareStatement("SELECT status,status_version,attempt_no,body::text AS body"
                 + " FROM ai_control.ai_run WHERE tenant_id=? AND run_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString("status") + "|" + rows.getLong("status_version") + "|"
                    + rows.getInt("attempt_no") + "|" + rows.getString("body");
            }
        }
    }

    private long count(String table, UUID runId) throws Exception {
        try (var c = source.getConnection();
             var read = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND run_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    @Test
    void runtimeStateDeliveryIsIdempotentBoundedAndNeverMovesTheRun() throws Exception {
        var f = fixture();
        var run = f.service().list(f.p(), null, 50).items().get(0);
        var before = runRow(run.runId());
        long eventsBefore = count("ai_control.ai_run_event", run.runId());
        // One delivery: the receipt echoes the attestation and the run row does not move at all.
        var report = attestation(run.runId(), "10000000-0000-4000-8000-0000000000b1", 1, "RUNNING", NOW);
        var receipt = f.runtimeStates().report(f.p(), run.runId(), report);
        assertEquals(report.requestId(), receipt.requestId());
        assertEquals("RUNNING", receipt.status());
        // A replay answers with the same receipt; changed content under the same request id is 409.
        assertEquals(receipt, f.runtimeStates().report(f.p(), run.runId(), report));
        assertThrows(AiRun.Conflict.class, () -> f.runtimeStates().report(f.p(), run.runId(),
            attestation(run.runId(), "10000000-0000-4000-8000-0000000000b1", 2, "RUNNING", NOW)));
        // The request id shares the one idempotency namespace with submissions and control commands.
        assertThrows(AiRun.Conflict.class, () -> f.runtimeStates().report(f.p(), run.runId(),
            attestation(run.runId(), f.command().requestId().toString(), 1, "RUNNING", NOW)));
        // Unknown run in the path and another owner's run both fail closed.
        assertThrows(ToolFailure.class, () -> f.runtimeStates().report(f.p(), UUID.randomUUID(),
            attestation(UUID.randomUUID(), "10000000-0000-4000-8000-0000000000b2", 1, "RUNNING", NOW)));
        assertEquals(before, runRow(run.runId()));
        assertEquals(eventsBefore, count("ai_control.ai_run_event", run.runId()));
        // The receipt window is bounded: older attestations drop and the drop is counted.
        for (int i = 0; i < AiRunRuntimeState.RETAINED_REPORTS + 5; i++) {
            f.runtimeStates().report(f.p(), run.runId(), attestation(run.runId(),
                String.format("10000000-0000-4000-8000-00000000%04x", 0x0200 + i), 1, "RUNNING", NOW));
        }
        var counts = f.states().counts(tenant, run.runId());
        assertEquals(AiRunRuntimeState.RETAINED_REPORTS, counts.retained());
        assertTrue(counts.dropped() >= 5, "dropped=" + counts.dropped());
        assertTrue(f.states().find(tenant, UUID.fromString("10000000-0000-4000-8000-000000000200")).isEmpty());
        assertEquals(1, f.states().latest(tenant, run.runId()).orElseThrow().attempt());
        assertEquals(before, runRow(run.runId()));
    }

    @Test
    void theCrossStoreReconciliationDisclosesDivergenceAndRepairsNothing() throws Exception {
        var f = fixture();
        var run = f.service().list(f.p(), null, 50).items().get(0);
        // Without any delivered Runtime state the run is disclosed as MISSING_RUNTIME_STATE.
        var missing = f.runtimeStates().reconcile(run);
        assertEquals(List.of(AiRunCrossStore.Finding.MISSING_RUNTIME_STATE), missing.findings());
        assertEquals("none", missing.repair());
        // Deliver a Runtime attestation that still runs while the platform lands the run terminal.
        f.runtimeStates().report(f.p(), run.runId(),
            attestation(run.runId(), "10000000-0000-4000-8000-0000000000b3", 1, "RUNNING", NOW));
        var claim = f.store().claim(tenant, run.runId(), "worker", NOW.plusSeconds(1),
            Duration.ofSeconds(90), Duration.ofSeconds(65)).orElseThrow();
        var landed = f.store().advance(tenant, run.runId(), claim.run().statusVersion(), claim.fence(),
            new AiRun.Transition(AiRun.Status.SUCCEEDED, null, null, NOW.plusSeconds(5))).orElseThrow();
        var before = runRow(run.runId());
        long eventsBefore = count("ai_control.ai_run_event", run.runId());
        long feesBefore = count("ai_control.model_spend", run.runId());
        var diverging = f.runtimeStates().reconcile(landed);
        assertTrue(diverging.findings().contains(AiRunCrossStore.Finding.PLATFORM_TERMINAL_RUNTIME_ACTIVE),
            diverging.findings().toString());
        assertTrue(diverging.findings().contains(AiRunCrossStore.Finding.STALE_RUNTIME_REPORT),
            diverging.findings().toString());
        assertEquals("none", diverging.repair());
        // Disclose-only: the registry row, the event log and the fee receipts are all untouched.
        assertEquals(before, runRow(run.runId()));
        assertEquals(eventsBefore, count("ai_control.ai_run_event", run.runId()));
        assertEquals(feesBefore, count("ai_control.model_spend", run.runId()));
        // The periodic pass records the report snapshot and still repairs nothing.
        var recorded = f.runtimeStates().reconcileAndRecord(landed);
        assertEquals(recorded.findings(), f.states().findReport(tenant, run.runId()).orElseThrow().findings());
        assertEquals(before, runRow(run.runId()));
        assertEquals(eventsBefore, count("ai_control.ai_run_event", run.runId()));
        // A consistent pair (terminal on both sides) discloses nothing.
        f.runtimeStates().report(f.p(), run.runId(),
            attestation(run.runId(), "10000000-0000-4000-8000-0000000000b4", 1, "SUCCEEDED", NOW.plusSeconds(5)));
        var consistent = f.runtimeStates().reconcile(landed);
        assertTrue(consistent.consistent(), consistent.findings().toString());
        assertEquals(before, runRow(run.runId()));
    }

    @Test
    void observabilityDisclosesTruncatedEventWindowsFeesAndDeliveries() throws Exception {
        var f = fixture(new PostgresAiRuns(source, 3));
        var run = f.service().list(f.p(), null, 50).items().get(0);
        var claim = f.store().claim(tenant, run.runId(), "worker", NOW.plusSeconds(1),
            Duration.ofSeconds(90), Duration.ofSeconds(65)).orElseThrow();
        f.store().advance(tenant, run.runId(), claim.run().statusVersion(), claim.fence(),
            new AiRun.Transition(AiRun.Status.WAITING_TOOL, null, null, NOW.plusSeconds(2)));
        f.store().advance(tenant, run.runId(), claim.run().statusVersion() + 1, claim.fence(),
            AiRun.Transition.to(AiRun.Status.RUNNING, NOW.plusSeconds(3)));
        var landed = f.store().advance(tenant, run.runId(), claim.run().statusVersion() + 2, claim.fence(),
            new AiRun.Transition(AiRun.Status.SUCCEEDED, null, null, NOW.plusSeconds(4))).orElseThrow();
        // Two delivered attestations and one retained fee without a result.
        f.runtimeStates().report(f.p(), run.runId(),
            attestation(run.runId(), "10000000-0000-4000-8000-0000000000b5", 1, "RUNNING", NOW.plusSeconds(2)));
        f.runtimeStates().report(f.p(), run.runId(),
            attestation(run.runId(), "10000000-0000-4000-8000-0000000000b6", 1, "SUCCEEDED", NOW.plusSeconds(4)));
        f.spend().reserve(new ModelSpend.Call(tenant, f.p().subjectId(), run.runId(), UUID.randomUUID(),
            landed.incidentId(), "sha256:" + "a".repeat(64), 10, ModelSpend.Policy.mock(),
            NOW.plusSeconds(2), NOW.plusSeconds(22), null, null));
        var observability = f.runtimeStates().observability(landed);
        assertEquals(3, observability.events().window());
        assertTrue(observability.events().truncated());
        assertTrue(observability.events().dropped() > 0);
        assertTrue(observability.events().retained() <= 3);
        assertEquals(1, observability.spend().priorModelCalls());
        assertEquals(1, observability.spend().uncertainPriorFees());
        assertEquals(2, observability.runtime().deliveredReports());
        assertEquals(0, observability.runtime().droppedReports());
        assertEquals(Integer.valueOf(1), observability.runtime().latestAttempt());
        assertNotNull(observability.runtime().lastReportedAt());
        // Reading the aggregate executes nothing: no state move, no event, no new fee row.
        assertEquals(3, observability.events().retained());
        assertEquals(2, observability.runtime().deliveredReports());
    }
}
