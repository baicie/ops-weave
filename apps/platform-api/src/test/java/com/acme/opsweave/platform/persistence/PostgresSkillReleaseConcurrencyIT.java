package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.domain.SkillCheck;
import com.acme.opsweave.aicontrol.domain.SkillContent;
import com.acme.opsweave.aicontrol.domain.SkillDraft;
import com.acme.opsweave.aicontrol.domain.SkillRelease;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real transactions race on explicit synthetic rows; no model, tool or external source is used. */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresSkillReleaseConcurrencyIT {
    private static final String SKILL = "fixture.concurrent";
    private static final SubjectId AUTHOR = new SubjectId("fixture-author");
    private static final SubjectId REVIEWER = new SubjectId("fixture-reviewer");
    private static final Instant T0 = Instant.parse("2026-10-15T02:00:00Z");
    private static DriverManagerDataSource control;
    private final TenantId tenant = new TenantId("skill-race-" + UUID.randomUUID());

    @BeforeAll
    static void migrate() {
        control = source("skill-race-control");
        for (var migration : List.of("V002__host_sync", "V010__external_problem_incident", "V011__tool_read_evidence",
            "V073__skill_release_registry")) {
            new SchemaMigrator(control).apply("db/migration/" + migration + ".sql", migration);
        }
    }

    @Test
    void distinctRequestsCompetingForOneEditVersionHaveOneWinner() throws Exception {
        var store = new PostgresSkillReleases(control);
        var draft = save(store, "1.0.0");
        var first = new SkillDraft.SaveCommand(UUID.randomUUID(), SKILL, "1.0.0", 1, content("First fixture edit"));
        var second = new SkillDraft.SaveCommand(UUID.randomUUID(), SKILL, "1.0.0", 1, content("Second fixture edit"));
        var results = race("skill_draft", concurrent -> List.of(
            () -> concurrent.save(first, tenant, AUTHOR, T0.plusSeconds(1)),
            () -> concurrent.save(second, tenant, AUTHOR, T0.plusSeconds(1))));
        assertEquals(1, results.stream().filter(Outcome::succeeded).count());
        assertEquals(1, results.stream().filter(r -> "SKILL_STATE_CONFLICT".equals(r.conflict())).count());
        var winner = (SkillDraft.SaveReceipt) results.stream().filter(Outcome::succeeded).findFirst().orElseThrow().receipt();
        var current = store.findDraft(tenant, draft.draftId()).orElseThrow();
        assertEquals(2, current.editVersion());
        assertEquals(winner.contentDigest(), current.contentDigest());
        var command = winner.requestId().equals(first.requestId()) ? first : second;
        assertEquals(winner, store.save(command, tenant, AUTHOR, T0.plusSeconds(3)));
        assertEquals(current, store.findDraft(tenant, draft.draftId()).orElseThrow());
    }

    @Test
    void anEditRacingPublicationCannotRewritePublishedContentOrPublishAnEditedDraft() throws Exception {
        var store = new PostgresSkillReleases(control);
        var draft = save(store, "1.0.0");
        store.submit(new SkillDraft.ReviewCommand(UUID.randomUUID(), draft.draftId()), tenant, T0);
        var edit = new SkillDraft.SaveCommand(UUID.randomUUID(), SKILL, "1.0.0", 1, content("Changed fixture content"));
        var publish = new SkillDraft.PublishCommand(UUID.randomUUID(), draft.draftId());
        var check = SkillCheck.run(SKILL, draft.version(), draft.content());
        var results = race("skill_draft", concurrent -> List.of(
            () -> concurrent.save(edit, tenant, AUTHOR, T0.plusSeconds(1)),
            () -> concurrent.publish(publish, tenant, REVIEWER, check, T0.plusSeconds(1))));
        assertEquals(1, results.stream().filter(Outcome::succeeded).count());
        var current = store.findDraft(tenant, draft.draftId()).orElseThrow();
        var published = store.findVersion(tenant, SKILL, "1.0.0");
        if (published.isPresent()) {
            assertEquals(SkillDraft.State.PUBLISHED, current.state());
            assertEquals(draft.contentDigest(), published.orElseThrow().contentDigest());
            assertEquals(current.contentDigest(), published.orElseThrow().contentDigest());
            assertEquals("1.0.0", store.pointer(tenant, SKILL).orElseThrow().currentVersion());
        } else {
            assertEquals(SkillDraft.State.DRAFT, current.state());
            assertEquals(edit.content().contentDigest(), current.contentDigest());
            assertEquals(2, current.editVersion());
            assertTrue(store.pointer(tenant, SKILL).isEmpty());
        }
    }

    @Test
    void distinctPublicationsOfTheSameCoordinateProduceOneImmutableVersionAndAttributableReplay() throws Exception {
        var store = new PostgresSkillReleases(control);
        var draft = save(store, "1.0.0");
        store.submit(new SkillDraft.ReviewCommand(UUID.randomUUID(), draft.draftId()), tenant, T0);
        var first = new SkillDraft.PublishCommand(UUID.randomUUID(), draft.draftId());
        var second = new SkillDraft.PublishCommand(UUID.randomUUID(), draft.draftId());
        var check = SkillCheck.run(SKILL, draft.version(), draft.content());
        var results = race("skill_draft", concurrent -> List.of(
            () -> concurrent.publish(first, tenant, REVIEWER, check, T0.plusSeconds(1)),
            () -> concurrent.publish(second, tenant, REVIEWER, check, T0.plusSeconds(1))));
        assertEquals(1, results.stream().filter(Outcome::succeeded).count());
        assertEquals(1, results.stream().filter(r -> "SKILL_STATE_CONFLICT".equals(r.conflict())).count());
        var winner = (SkillDraft.PublishReceipt) results.stream().filter(Outcome::succeeded).findFirst().orElseThrow().receipt();
        var command = winner.requestId().equals(first.requestId()) ? first : second;
        assertEquals(REVIEWER, winner.review().reviewer());
        assertEquals(winner, store.publish(command, tenant, new SubjectId("another-current-reviewer"), check, T0.plusSeconds(3)));
        assertEquals(1, store.pageVersions(tenant, SKILL, null, 10).size());
        assertEquals(draft.contentDigest(), store.findVersion(tenant, SKILL, "1.0.0").orElseThrow().contentDigest());
        assertEquals(SkillDraft.State.PUBLISHED, store.findDraft(tenant, draft.draftId()).orElseThrow().state());
    }

    @Test
    void concurrentPointerMovesRecordAContiguousHistoryInsteadOfTwoStaleFromVersions() throws Exception {
        var store = new PostgresSkillReleases(control);
        publish(store, "1.0.1");
        publish(store, "1.0.2");
        publish(store, "1.0.3");
        var first = new SkillRelease.RollbackCommand(UUID.randomUUID(), SKILL, "1.0.1", "Fixture rollback one");
        var second = new SkillRelease.RollbackCommand(UUID.randomUUID(), SKILL, "1.0.2", "Fixture rollback two");
        var results = race("skill_release", concurrent -> List.of(
            () -> concurrent.rollback(first, tenant, REVIEWER, T0.plusSeconds(1)),
            () -> concurrent.rollback(second, tenant, REVIEWER, T0.plusSeconds(1))));
        assertTrue(results.stream().allMatch(Outcome::succeeded));
        var receipts = results.stream().map(r -> (SkillRelease.RollbackReceipt) r.receipt()).toList();
        assertEquals(1, receipts.stream().filter(r -> "1.0.3".equals(r.fromVersion())).count());
        var initial = receipts.stream().filter(r -> "1.0.3".equals(r.fromVersion())).findFirst().orElseThrow();
        var last = receipts.stream().filter(r -> !"1.0.3".equals(r.fromVersion())).findFirst().orElseThrow();
        assertEquals(initial.toVersion(), last.fromVersion());
        assertEquals(last.toVersion(), store.pointer(tenant, SKILL).orElseThrow().currentVersion());
        assertEquals(3, store.pageVersions(tenant, SKILL, null, 10).size());
    }

    private record Outcome(Object receipt, String conflict) {
        boolean succeeded() { return conflict == null; }
    }

    /**
     * Hold a real row lock until both transactions are demonstrably waiting on a database lock.
     * Before serialization this lets both requests read stale state; after serialization one waits
     * for the Skill transaction while the other waits for this test's row, without timing guesses.
     */
    private List<Outcome> race(String table, java.util.function.Function<PostgresSkillReleases, List<Supplier<Object>>> commands)
        throws Exception {
        var application = "skill-race-" + UUID.randomUUID();
        var store = new PostgresSkillReleases(source(application));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var held = control.getConnection()) {
            held.setAutoCommit(false);
            try (var lock = held.prepareStatement("SELECT 1 FROM ai_control." + table
                + " WHERE tenant_id=? AND skill_id=? FOR UPDATE")) {
                lock.setString(1, tenant.value());
                lock.setString(2, SKILL);
                try (var rows = lock.executeQuery()) { assertTrue(rows.next(), "Fixture row must exist"); }
            }
            var start = new CountDownLatch(1);
            var pending = new ArrayList<Future<Outcome>>();
            for (var command : commands.apply(store)) {
                pending.add(workers.submit(() -> {
                    start.await();
                    try { return new Outcome(command.get(), null); }
                    catch (SkillRelease.Conflict conflict) { return new Outcome(null, conflict.code()); }
                }));
            }
            start.countDown();
            try { awaitBlocked(application, pending.size()); }
            finally { held.commit(); }
            var results = new ArrayList<Outcome>();
            for (var request : pending) results.add(request.get(20, TimeUnit.SECONDS));
            return results;
        }
    }

    private static void awaitBlocked(String application, int expected) throws SQLException, InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int blocked = 0;
        while (System.nanoTime() < deadline) {
            try (var c = control.getConnection(); var read = c.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND application_name=? AND wait_event_type='Lock'")) {
                read.setString(1, application);
                try (var rows = read.executeQuery()) { rows.next(); blocked = rows.getInt(1); }
            }
            if (blocked == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, blocked, "Both concurrent test transactions must reach a database lock");
    }

    private SkillDraft save(PostgresSkillReleases store, String version) {
        var receipt = store.save(new SkillDraft.SaveCommand(UUID.randomUUID(), SKILL, version, 1, content("Fixture initial content")),
            tenant, AUTHOR, T0);
        return store.findDraft(tenant, receipt.draftId()).orElseThrow();
    }

    private void publish(PostgresSkillReleases store, String version) {
        var draft = save(store, version);
        store.submit(new SkillDraft.ReviewCommand(UUID.randomUUID(), draft.draftId()), tenant, T0);
        store.publish(new SkillDraft.PublishCommand(UUID.randomUUID(), draft.draftId()), tenant, REVIEWER,
            SkillCheck.run(SKILL, version, draft.content()), T0);
    }

    private static SkillContent content(String prompt) {
        return new SkillContent("readonly-current-incident-diagnosis-v1", prompt,
            "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"],\"additionalProperties\":false}",
            List.of("incident.get@2.0.0"), "current", "reasoning-structured-readonly", "forbidden", 1, 1, 1024, 1000,
            List.of(new SkillContent.TestCase("fixture", "Fixture question", "{\"summary\":\"Fixture output\"}")));
    }

    private static DriverManagerDataSource source(String application) {
        var source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),
            System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        var properties = new Properties();
        properties.setProperty("ApplicationName", application);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        source.setConnectionProperties(properties);
        return source;
    }
}
