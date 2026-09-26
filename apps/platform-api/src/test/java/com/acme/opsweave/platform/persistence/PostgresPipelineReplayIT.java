package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresPipelineReplayIT extends OwnedInventoryTest {
    private OpsweaveProperties properties() {
        return new OpsweaveProperties(null, null, new OpsweaveProperties.Inventory("postgres",
            System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    }
    private HikariDataSource pool() {
        var pool = new HikariDataSource(); var config = properties().inventory();
        pool.setJdbcUrl(config.jdbcUrl()); pool.setUsername(config.jdbcUser()); pool.setPassword(config.jdbcPassword()); return pool;
    }
    @Test
    void concurrentClaimsAndExpiredLeaseUseFencingAndBoundedExplicitRecovery() throws Exception {
        openInventory(properties());
        var tenant = new TenantId("replay-" + UUID.randomUUID()); var owner = new SubjectId("owner");
        var spec = new PipelineReplaySpec(UUID.randomUUID(), PipelineVersion.of(PipelineDefinition.zabbixHostV1()).ref(), 10, "COMPARE_VERSION");
        var key = UUID.randomUUID(); var now = Instant.parse("2026-09-25T00:00:00Z");
        try (var pool = pool(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var store = new PostgresPipelineReplayStore(pool); var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<com.acme.opsweave.integration.api.PipelineReplayStore.Claim>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> { start.await(); return store.claim(tenant, "source", owner, key, spec, now); }));
            start.countDown(); int winners = 0; var ids = new HashSet<UUID>(); PipelineReplayRun original = null;
            for (var future : futures) { var claim = future.get(15, TimeUnit.SECONDS); if (claim.acquired()) winners++; ids.add(claim.run().id()); original = claim.run(); }
            assertEquals(1, winners); assertEquals(1, ids.size());
            var second = store.claim(tenant, "source", owner, key, spec, now.plusSeconds(121));
            assertTrue(second.acquired()); assertEquals(2, second.run().attempt()); assertEquals(original.id(), second.run().id());
            assertFalse(store.finish(original, now.plusSeconds(122), null, "STALE"));
            assertTrue(store.finish(second.run(), now.plusSeconds(122), null, "SAFE_FAILURE"));
            var third = store.claim(tenant, "source", owner, key, spec, now.plusSeconds(123)); assertTrue(third.acquired());
            var exhausted = store.claim(tenant, "source", owner, key, spec, now.plusSeconds(244));
            assertFalse(exhausted.acquired()); assertEquals(3, exhausted.run().attempt());
            assertEquals("REPLAY_ATTEMPTS_EXHAUSTED", exhausted.run().failureCode());
            assertFalse(store.finish(third.run(), now.plusSeconds(245), null, "STALE"));
            assertTrue(store.find(tenant, "source", new SubjectId("other"), original.id()).isEmpty());
            assertTrue(store.find(new TenantId("other"), "source", owner, original.id()).isEmpty());
            var conflict = assertThrows(PipelineException.class, () -> store.claim(tenant, "source", owner, key,
                new PipelineReplaySpec(spec.syncRunId(), spec.targetVersion(), 1, spec.purpose()), now));
            assertEquals(PipelineException.Code.REPLAY_KEY_CONFLICT, conflict.code());
        }
    }

    @Test
    void persistedReportsSurviveAdapterRestartAndRecheckPermissions() {
        var wiring = openInventory(properties()); var tenant = new TenantId("replay-" + UUID.randomUUID());
        var principal = new Principal(new SubjectId("owner"), tenant, Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ), ResourceScope.tenantWide());
        var sync = new IngestZabbixHostsUseCase(new AuthorizeUseCase(), new FixtureZabbixHostConnector(), wiring.writer(),
            wiring.rawRecords(), wiring.syncRuns(), wiring.pipelines(), "labeled-fixture", "postgres", "zabbix-1", "unused", 1).execute(principal, null);
        var spec = new PipelineReplaySpec(sync.syncRunId(), wiring.pipelines().pinned(tenant, "zabbix-1", sync.syncRunId()).orElseThrow(), 100, "COMPARE_VERSION");
        var evaluator = new HostPipelineService(new AuthorizeUseCase(), wiring.pipelines(), wiring.rawReader(), wiring.syncRuns(), "zabbix-1");
        var service = new PipelineReplayService(new AuthorizeUseCase(), wiring.replays(), evaluator, "zabbix-1", Clock.systemUTC());
        var first = service.execute(principal, UUID.randomUUID(), spec);
        var restarted = openInventory(properties());
        var restored = new PipelineReplayService(new AuthorizeUseCase(), restarted.replays(), evaluator, "zabbix-1", Clock.systemUTC());
        assertEquals(first, restored.get(principal, first.id()));
        assertEquals(first, restored.execute(principal, first.requestKey(), spec));
        assertEquals(2, restarted.query().list(tenant).size());
        var revoked = new Principal(principal.subjectId(), tenant, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide());
        assertEquals(PipelineException.Code.FORBIDDEN, assertThrows(PipelineException.class, () -> restored.get(revoked, first.id())).code());
        assertEquals(1, restored.list(revoked, null, 20).items().size());
    }

    @Test
    void historyCursorIsScopedAndStableForEqualTimestamps() {
        openInventory(properties());
        try (var pool = pool()) {
            var store = new PostgresPipelineReplayStore(pool); var tenant = new TenantId("replay-" + UUID.randomUUID()); var owner = new SubjectId("owner");
            var spec = new PipelineReplaySpec(UUID.randomUUID(), PipelineVersion.of(PipelineDefinition.zabbixHostV1()).ref(), 10, "COMPARE_VERSION");
            for (int i = 0; i < 4; i++) store.claim(tenant, "source", owner, UUID.randomUUID(), spec, Instant.EPOCH);
            var first = store.list(tenant, "source", owner, null, 2); var second = store.list(tenant, "source", owner, first.getLast().id(), 2);
            var ids = new HashSet<UUID>(); first.forEach(r -> ids.add(r.id())); second.forEach(r -> ids.add(r.id())); assertEquals(4, ids.size());
            assertEquals(PipelineException.Code.NOT_FOUND, assertThrows(PipelineException.class,
                () -> store.list(tenant, "other", owner, first.getFirst().id(), 2)).code());
        }
    }
}
