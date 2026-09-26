package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresPipelineDraftIT extends OwnedInventoryTest {
    private OpsweaveProperties properties() { return new OpsweaveProperties(null, null, new OpsweaveProperties.Inventory("postgres",
        System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))); }
    @Test
    void persistsPrivateDraftAcrossAdaptersAndRejectsCorruptedContent() throws Exception {
        var props = properties(); var wiring = openInventory(props); var tenant = new TenantId("draft-" + UUID.randomUUID()); var owner = new SubjectId("alice");
        var version = PipelineVersion.of(PipelineDefinition.zabbixHostV1());
        var first = wiring.drafts().save(tenant, "source", owner, version, 0, Instant.now());
        var restarted = openInventory(props);
        assertEquals(first, restarted.drafts().find(tenant, "source", owner, version.definition().id(), 1).orElseThrow());
        assertTrue(restarted.pipelines().find(tenant, "source", version.definition().id(), 1).isEmpty());
        assertTrue(restarted.drafts().find(tenant, "source", new SubjectId("bob"), version.definition().id(), 1).isEmpty());
        assertTrue(restarted.drafts().list(tenant, "other", owner, 20).isEmpty());
        assertTrue(restarted.drafts().list(new TenantId("other"), "source", owner, 20).isEmpty());
        assertEquals(first.content().ref(), restarted.drafts().list(tenant, "source", owner, 20).getFirst().target());
        try (var db = java.sql.DriverManager.getConnection(props.inventory().jdbcUrl(), props.inventory().jdbcUser(), props.inventory().jdbcPassword());
            var tamper = db.prepareStatement("UPDATE integration.pipeline_draft SET digest = ? WHERE tenant_id = ?")) {
            tamper.setString(1, "sha256:" + "0".repeat(64)); tamper.setString(2, tenant.value()); assertEquals(1, tamper.executeUpdate());
        }
        assertThrows(IllegalStateException.class, () -> restarted.drafts().find(tenant, "source", owner, version.definition().id(), 1));
    }
    @Test
    void concurrentUpdatesCannotOverwriteTheWinnerOrPublishedVersion() throws Exception {
        var props = properties(); openInventory(props);
        try (var pool = new HikariDataSource(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.setJdbcUrl(props.inventory().jdbcUrl()); pool.setUsername(props.inventory().jdbcUser()); pool.setPassword(props.inventory().jdbcPassword());
            var drafts = new PostgresPipelineDraftStore(pool); var versions = new PostgresPipelineVersionStore(pool);
            var tenant = new TenantId("draft-" + UUID.randomUUID()); var owner = new SubjectId("alice");
            var base = PipelineDefinition.zabbixHostV1(); var original = PipelineVersion.of(base);
            versions.publish(tenant, "source", original); drafts.save(tenant, "source", owner, original, 0, Instant.EPOCH);
            var changed = PipelineVersion.of(new PipelineDefinition(base.id(), 1, "zabbix", "host", base.nodes(), base.edges(), ErrorPolicy.FAIL_FAST));
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
            for (var content : List.of(original, changed)) futures.add(executor.submit(() -> { start.await();
                try { drafts.save(tenant, "source", owner, content, 1, Instant.now()); return true; }
                catch (PipelineException conflict) { assertEquals(PipelineException.Code.DRAFT_CONFLICT, conflict.code()); return false; }
            }));
            start.countDown(); int winners = 0; for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners); assertEquals(2, drafts.find(tenant, "source", owner, base.id(), 1).orElseThrow().editVersion());
            assertEquals(original, versions.find(tenant, "source", base.id(), 1).orElseThrow());
            assertThrows(PipelineException.class, () -> drafts.save(tenant, "source", owner, original, 0, Instant.now()));
        }
    }
}
