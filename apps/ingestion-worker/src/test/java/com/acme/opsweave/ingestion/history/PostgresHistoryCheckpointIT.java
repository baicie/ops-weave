package com.acme.opsweave.ingestion.history;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.*;
import com.acme.opsweave.integration.domain.HistoryStream;
import com.acme.opsweave.sharedkernel.TenantId;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresHistoryCheckpointIT {
    @Test
    void commitsOnlyAfterWorkAndSurvivesStoreRecreation() {
        try (var dataSource = dataSource()) {
            var store = new PostgresHistoryCheckpointStore(dataSource);
            store.initialize();
            var stream = stream();
            assertThrows(Failure.class, () -> store.withLock(stream, 100, before -> {
                assertEquals(99, before.completedThrough());
                throw new Failure(Failure.Code.SINK_FAILED);
            }));
            store.withLock(stream, 100, before -> {
                assertEquals(99, before.completedThrough());
                return new Update(before.accepted(159, "a".repeat(64)), new Result(true, 100, 159, 1, 1, 0));
            });
            var reopened = new PostgresHistoryCheckpointStore(dataSource);
            reopened.withLock(stream, 100, before -> {
                assertEquals(159, before.completedThrough()); assertEquals(1, before.revision());
                return new Update(before, Result.idle(before.completedThrough()));
            });
            assertEquals(Failure.Code.CONFIGURATION_INVALID,
                assertThrows(Failure.class, () -> reopened.withLock(stream, 101, before -> new Update(before, Result.idle(0)))).code());
        }
    }

    @Test
    void tenantSourceAndStreamKeysStayIsolated() {
        try (var dataSource = dataSource()) {
            var store = new PostgresHistoryCheckpointStore(dataSource); store.initialize();
            var first = stream();
            store.withLock(first, 100, before -> new Update(before.accepted(159, "a".repeat(64)), Result.idle(159)));
            for (var other : new HistoryStream[] {
                new HistoryStream(new TenantId("other-tenant"), first.sourceInstanceId(), first.itemId(), first.streamName()),
                new HistoryStream(first.tenantId(), "another-source", first.itemId(), first.streamName()),
                new HistoryStream(first.tenantId(), first.sourceInstanceId(), "20002", first.streamName()),
                new HistoryStream(first.tenantId(), first.sourceInstanceId(), first.itemId(), "another-stream")}) {
                store.withLock(other, 100, before -> {
                    assertEquals(99, before.completedThrough()); return new Update(before, Result.idle(99));
                });
            }
        }
    }

    @Test
    void concurrentWorkerCannotEnterWhileAStreamIsLocked() throws Exception {
        try (var dataSource = dataSource(); var executor = Executors.newSingleThreadExecutor()) {
            var store = new PostgresHistoryCheckpointStore(dataSource); store.initialize();
            var stream = stream();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var owner = executor.submit(() -> store.withLock(stream, 100, before -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                return new Update(before.accepted(159, "b".repeat(64)), Result.idle(159));
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var busy = assertThrows(Failure.class, () -> store.withLock(stream, 100, before -> { fail("Second worker entered"); return null; }));
                assertEquals(Failure.Code.CHECKPOINT_BUSY, busy.code());
            } finally { release.countDown(); }
            owner.get(3, TimeUnit.SECONDS);
        }
    }

    static HistoryStream stream() { return new HistoryStream(new TenantId("tenant-demo"), "zabbix-test", "20001", UUID.randomUUID().toString()); }
    static HikariDataSource dataSource() {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(System.getenv("OPSWEAVE_TEST_JDBC_URL"));
        dataSource.setUsername(System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_USER", "opsweave_dev"));
        dataSource.setPassword(System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_PASSWORD", ""));
        dataSource.setMaximumPoolSize(3); dataSource.setMinimumIdle(0); dataSource.setConnectionTimeout(3000);
        return dataSource;
    }
}
