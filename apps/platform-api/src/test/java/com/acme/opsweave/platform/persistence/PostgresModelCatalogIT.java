package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.catalog.domain.ModelDefinition.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL", matches=".+")
class PostgresModelCatalogIT extends OwnedInventoryTest {
    final TenantId tenant = new TenantId("model-catalog-" + UUID.randomUUID());
    final DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    final InventoryWiring wiring = openInventory(new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "zabbix-1", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
    ModelDefinition model(String label) { return new ModelDefinition("custom.pg_model", 1, Kind.ENTITY, label, "PG fixture only", List.of(new Field("name", "Name", Type.TEXT, true, 255, null, null, List.of())), null); }
    PostgresModelCatalogStore store() { return new PostgresModelCatalogStore(source); }
    @Test void draftsAndPublishedModelsSurviveReopenWithTenantAndOwnerIsolation() {
        var definition = model("Persistent model"); var draft = store().save(tenant, "one", definition, 0, Instant.now());
        assertEquals(draft.digest(), store().drafts(tenant, "one", 51).getFirst().digest()); assertTrue(store().drafts(tenant, "two", 51).isEmpty());
        assertThrows(CatalogFailure.class, () -> store().publish(tenant, "two", definition.ref(), 1, draft.digest(), List.of(), Instant.now()));
        var published = store().publish(tenant, "one", definition.ref(), 1, draft.digest(), List.of(), Instant.now());
        assertEquals(published.digest(), store().find(tenant, definition.ref()).orElseThrow().digest());
        assertTrue(store().find(new TenantId(tenant.value() + "-other"), definition.ref()).isEmpty());
        assertThrows(CatalogFailure.class, () -> store().save(tenant, "two", definition, 0, Instant.now()));
        assertEquals(published.digest(), store().publish(tenant, "one", definition.ref(), 1, draft.digest(), List.of(), Instant.now()).digest());
    }
    @Test void compareAndSetAcrossIndependentConnectionsHasExactlyOneWinner() throws Exception {
        store().save(tenant, "one", model("initial"), 0, Instant.now()); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++) { final int n = i; futures.add(pool.submit(() -> { start.await(); try { store().save(tenant, "one", model("writer " + n), 1, Instant.now()); return true; } catch (CatalogFailure conflict) { assertEquals(CatalogFailure.Code.CONFLICT, conflict.code()); return false; } })); }
            start.countDown(); int success = 0; for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) success++;
            assertEquals(1, success); assertEquals(2, store().drafts(tenant, "one", 51).getFirst().editVersion());
        }
    }
    @Test void twoOwnersCannotReplacePublishedContentAndMissingEndpointsRollback() throws Exception {
        var one = model("one"); var two = model("two"); store().save(tenant, "one", one, 0, Instant.now()); store().save(tenant, "two", two, 0, Instant.now());
        store().publish(tenant, "one", one.ref(), 1, one.digest(), List.of(), Instant.now());
        assertThrows(CatalogFailure.class, () -> store().publish(tenant, "two", two.ref(), 1, two.digest(), List.of(), Instant.now()));
        assertEquals("one", store().find(tenant, one.ref()).orElseThrow().definition().label());
        var relation = new ModelDefinition("custom.pg_relation", 1, Kind.RELATION, "Relation", "", List.of(), new Endpoints(new Ref("builtin.host", 1), new Ref("custom.missing", 1), Cardinality.MANY_TO_MANY));
        store().save(tenant, "one", relation, 0, Instant.now());
        assertEquals(CatalogFailure.Code.UNKNOWN_ENTITY_TYPE, assertThrows(CatalogFailure.class, () -> store().publish(tenant, "one", relation.ref(), 1, relation.digest(), new BuiltinCatalog().definitions(), Instant.now())).code());
        assertTrue(store().find(tenant, relation.ref()).isEmpty()); assertEquals(2, store().drafts(tenant, "one", 51).size());
    }
    @Test void corruptedStoredDefinitionNeverReturnsAsTrustedVersion() throws Exception {
        var d = model("Integrity"); store().save(tenant, "one", d, 0, Instant.now()); store().publish(tenant, "one", d.ref(), 1, d.digest(), List.of(), Instant.now());
        try (var c = source.getConnection(); var s = c.prepareStatement("UPDATE catalog.model_version SET digest=? WHERE tenant_id=?")) { s.setString(1, "sha256:" + "0".repeat(64)); s.setString(2, tenant.value()); s.executeUpdate(); }
        assertThrows(IllegalStateException.class, () -> store().find(tenant, d.ref()));
    }
}
