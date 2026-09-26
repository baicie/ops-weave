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
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresPipelineIT extends OwnedInventoryTest {
    private OpsweaveProperties properties() {
        return new OpsweaveProperties(null, null, new OpsweaveProperties.Inventory("postgres",
            System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    }
    @Test
    void persistsVersionsPinsAndRawAcrossRecreatedAdaptersAndReportsRetentionGaps() throws Exception {
        var props = properties(); var wiring = openInventory(props);
        var tenant = new TenantId("pipeline-" + UUID.randomUUID());
        var principal = new Principal(new SubjectId("test-user"), tenant,
            Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ), ResourceScope.tenantWide());
        var sync = new IngestZabbixHostsUseCase(new AuthorizeUseCase(), new FixtureZabbixHostConnector(), wiring.writer(),
            wiring.rawRecords(), wiring.syncRuns(), wiring.pipelines(), "labeled-fixture", "postgres", "zabbix-1", "unused", 1);
        var result = sync.execute(principal, null);
        assertTrue(result.snapshotComplete());
        var restarted = openInventory(props);
        var service = new HostPipelineService(new AuthorizeUseCase(), restarted.pipelines(), restarted.rawReader(), restarted.syncRuns(), "zabbix-1");
        var ref = restarted.pipelines().pinned(tenant, "zabbix-1", result.syncRunId()).orElseThrow();
        var report = service.replay(principal, result.syncRunId(), ref, 100, true, "VALIDATE_MAPPING");
        assertEquals(2, report.accepted()); assertEquals(0, report.changed()); assertEquals(0, report.missingRaw());
        assertEquals(1, restarted.rawReader().read(tenant, "zabbix-1", result.syncRunId(), 1).records().size());
        assertEquals(2, restarted.rawReader().read(tenant, "zabbix-1", result.syncRunId(), 1).retainedCount());
        assertTrue(restarted.rawReader().read(new TenantId("other"), "zabbix-1", result.syncRunId(), 100).records().isEmpty());
        assertTrue(restarted.pipelines().pinned(tenant, "other", result.syncRunId()).isEmpty());
        try (var db = DriverManager.getConnection(props.inventory().jdbcUrl(), props.inventory().jdbcUser(), props.inventory().jdbcPassword())) {
            try (var delete = db.prepareStatement("DELETE FROM integration.raw_record_metadata WHERE tenant_id = ? AND id = ?")) {
                delete.setString(1, tenant.value()); delete.setString(2, report.rows().getFirst().rawRef()); assertEquals(1, delete.executeUpdate());
            }
            var missing = service.replay(principal, result.syncRunId(), ref, 100, true, "VALIDATE_MAPPING");
            assertEquals(1, missing.missingRaw()); assertEquals(1, missing.accepted());
            try (var update = db.prepareStatement("UPDATE integration.raw_record_metadata SET payload = jsonb_build_object('padding', repeat('x', 70000)) WHERE tenant_id = ?")) {
                update.setString(1, tenant.value()); assertEquals(1, update.executeUpdate());
            }
            var large = service.replay(principal, result.syncRunId(), ref, 100, true, "VALIDATE_MAPPING");
            assertEquals(1, large.oversized()); assertEquals("RAW_TOO_LARGE", large.rows().getFirst().status());
        }
        assertEquals(2, restarted.query().list(tenant).size());
    }

    @Test
    void concurrentPublicationCannotOverwriteSameRevision() throws Exception {
        var props = properties(); openInventory(props);
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(props.inventory().jdbcUrl()); pool.setUsername(props.inventory().jdbcUser()); pool.setPassword(props.inventory().jdbcPassword());
            var store = new PostgresPipelineVersionStore(pool);
            var tenant = new TenantId("pipeline-" + UUID.randomUUID());
            var base = PipelineDefinition.zabbixHostV1();
            var a = PipelineVersion.of(base);
            var b = PipelineVersion.of(new PipelineDefinition(base.id(), 1, "zabbix", "host", base.nodes(), base.edges(), ErrorPolicy.FAIL_FAST));
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = List.of(a, b).stream().map(v -> executor.submit(() -> {
                    start.await();
                    try { store.publish(tenant, "zabbix-1", v); return true; }
                    catch (PipelineException conflict) { assertEquals(PipelineException.Code.VERSION_CONFLICT, conflict.code()); return false; }
                })).toList();
                start.countDown(); int winners = 0;
                for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
                assertEquals(1, winners);
                var stored = store.find(tenant, "zabbix-1", base.id(), 1).orElseThrow();
                assertEquals(stored, store.publish(tenant, "zabbix-1", stored));
                assertTrue(store.find(new TenantId("other"), "zabbix-1", base.id(), 1).isEmpty());
            }
        }
    }
}
