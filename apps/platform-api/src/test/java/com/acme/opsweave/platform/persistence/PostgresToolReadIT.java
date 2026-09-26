package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.aicontrol.api.ToolReadStore;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.incident.domain.IncidentRecord;
import com.acme.opsweave.platform.ai.ToolJson;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL", matches=".+")
class PostgresToolReadIT {
    static DriverManagerDataSource source;
    static final Instant NOW = Instant.parse("2026-09-25T00:00:00.123456789Z");
    final TenantId tenant = new TenantId("tools-pg-" + UUID.randomUUID());
    final SubjectId subject = new SubjectId("tool-reader");
    @BeforeAll static void migrate() {
        source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        for (String version : List.of("V002__host_sync", "V003__metric_definition", "V004__metric_catalog", "V007__pipeline_version", "V008__pipeline_replay", "V009__pipeline_draft", "V010__external_problem_incident", "V013__incident_reorganization", "V011__tool_read_evidence"))
            new SchemaMigrator(source).apply("db/migration/" + version + ".sql", version);
        new SchemaMigrator(source).apply("db/migration/V016__problem_observation.sql","V016__problem_observation");
        new SchemaMigrator(source).apply("db/migration/V012__ai_insight.sql","V012__ai_insight");
        new SchemaMigrator(source).apply("db/migration/V019__ai_content_retention.sql","V019__ai_content_retention");
    }
    ToolReadSession session() {
        var problem = new ExternalProblem(tenant, "zabbix-1", "101", "201", "Fixture problem", 3, NOW.minusSeconds(600), NOW, List.of(), false, null, null);
        new PostgresIncidentStore(source).ingest(tenant, "zabbix-1", "labeled-fixture", List.of(problem), NOW);
        return new ToolReadSession(UUID.randomUUID(), tenant, subject, IncidentRecord.initialId(problem), 1, Set.of(),
            new ToolWindow(NOW.minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS), NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)), NOW, NOW.plusSeconds(60), 0);
    }
    PlatformEvidence evidence(ToolReadSession s) {
        return new PlatformEvidence(UUID.randomUUID(), s.id(), tenant, s.incidentId(), 1, Set.of(), "incident", "Fixture summary", s.window(), NOW, NOW,
            NOW.plusSeconds(86400), List.of("labeled-fixture"), List.of("CURRENT_KNOWLEDGE_ONLY"), Map.of("title", "Fixture", "version", 1));
    }
    @Test void concurrentCallsShareOnePersistedBudgetAndCheckOwner() throws Exception {
        var store = new PostgresToolReadStore(source); var s = session(); store.create(s);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1); var jobs = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) jobs.add(workers.submit(() -> { gate.await(); try { store.begin(tenant, subject, s.id(), "incident.get@2.0.0", NOW); return "OK"; } catch (ToolFailure failure) { return failure.code().name(); } }));
            gate.countDown(); var outcomes = new ArrayList<String>(); for (var job : jobs) outcomes.add(job.get(15, TimeUnit.SECONDS));
            assertEquals(4, outcomes.stream().filter("OK"::equals).count()); assertEquals(8, outcomes.stream().filter("BUDGET_EXHAUSTED"::equals).count());
        }
        var restarted = new PostgresToolReadStore(source);
        assertEquals(ToolFailure.Code.BUDGET_EXHAUSTED, assertThrows(ToolFailure.class, () -> restarted.begin(tenant, subject, s.id(), "incident.get@2.0.0", NOW)).code());
        assertEquals(ToolFailure.Code.NOT_FOUND, assertThrows(ToolFailure.class, () -> restarted.begin(tenant, new SubjectId("other"), s.id(), "incident.get@2.0.0", NOW)).code());
        assertEquals(ToolFailure.Code.NOT_FOUND, assertThrows(ToolFailure.class, () -> restarted.begin(new TenantId("other"), subject, s.id(), "incident.get@2.0.0", NOW)).code());
        try (var c = source.getConnection(); var q = c.prepareStatement("SELECT count(*) FROM audit.tool_read_call WHERE tenant_id=? AND session_id=? AND outcome='STARTED'")) {
            q.setString(1, tenant.value()); q.setObject(2, s.id()); try (var rows = q.executeQuery()) { rows.next(); assertEquals(4, rows.getInt(1)); }
        }
    }
    @Test void evidenceAndAuditCommitTogetherAndSurviveReopening() throws Exception {
        var store = new PostgresToolReadStore(source); var s = session(); store.create(s);
        var call = store.begin(tenant, subject, s.id(), "incident.get@2.0.0", NOW); var snapshot = evidence(s);
        var invalidCall = new ToolReadStore.Call(UUID.randomUUID(), call.session(), call.tool(), call.startedAt());
        assertThrows(ToolFailure.class, () -> store.complete(invalidCall, snapshot, ToolJson.bytes(snapshot), NOW));
        assertTrue(store.find(tenant, snapshot.id()).isEmpty());
        store.complete(call, snapshot, ToolJson.bytes(snapshot), NOW);
        assertEquals(snapshot, new PostgresToolReadStore(source).find(tenant, snapshot.id()).orElseThrow());
        assertTrue(store.find(new TenantId("other"), snapshot.id()).isEmpty());
        assertEquals(NOW, store.find(tenant, snapshot.id()).orElseThrow().availableAt());
        var failed = store.begin(tenant, subject, s.id(), "metric.summary@2.0.0", NOW);
        store.fail(failed, ToolFailure.Code.UNAVAILABLE, NOW);
        try (var c = source.getConnection(); var q = c.prepareStatement("SELECT outcome,result_bytes FROM audit.tool_read_call WHERE id=?")) {
            q.setObject(1, call.id()); try (var rows = q.executeQuery()) { assertTrue(rows.next()); assertEquals("PARTIAL", rows.getString(1)); assertEquals(ToolJson.bytes(snapshot), rows.getInt(2)); }
            q.setObject(1, failed.id()); try (var rows = q.executeQuery()) { assertTrue(rows.next()); assertEquals("UNAVAILABLE", rows.getString(1)); assertEquals(0, rows.getInt(2)); }
        }
    }
    @Test void expiredSessionsAndLateResultsFailAndActiveOwnerSessionsAreBounded() {
        var store = new PostgresToolReadStore(source); var s = session(); store.create(s);
        var call = store.begin(tenant, subject, s.id(), "incident.get@2.0.0", NOW); var snapshot = evidence(s);
        assertEquals(ToolFailure.Code.DEADLINE, assertThrows(ToolFailure.class, () -> store.complete(call, snapshot, ToolJson.bytes(snapshot), NOW.plusSeconds(15))).code());
        assertTrue(store.find(tenant, snapshot.id()).isEmpty());
        assertEquals(ToolFailure.Code.EXPIRED, assertThrows(ToolFailure.class, () -> store.begin(tenant, subject, s.id(), "incident.get@2.0.0", NOW.plusSeconds(60))).code());
        for (int i = 0; i < 3; i++) store.create(session());
        assertEquals(ToolFailure.Code.BUSY, assertThrows(ToolFailure.class, () -> store.create(session())).code());
    }
}
