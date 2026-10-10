package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.catalog.domain.ModelDefinition.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL", matches=".+")
class PostgresModelExtensionIT extends OwnedInventoryTest {
    final TenantId tenant = new TenantId("model-extension-" + UUID.randomUUID());
    final DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    final InventoryWiring wiring = openInventory(new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "zabbix-1", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
    final Ref base = new Ref("builtin.host", 1);
    static Field field(String id) { return new Field(id, id, Type.TEXT, false, 64, null, null, List.of()); }
    PostgresModelExtensionStore store() { return new PostgresModelExtensionStore(source); }

    @Test void extensionDraftsAndVersionsSurviveReopenWithTenantAndOwnerIsolation() {
        var store = store();
        var draft = store.save(tenant, "one", base, List.of(field("business_code")), 0, Instant.now());
        assertEquals(1, draft.editVersion());
        assertEquals(1, draft.extension().revision());
        assertEquals(draft.extension().digest(), store.drafts(tenant, "one", 51).getFirst().extension().digest());
        assertTrue(store.drafts(tenant, "two", 51).isEmpty());
        assertThrows(CatalogFailure.class, () -> store.save(tenant, "one", base, List.of(field("business_code")), 0, Instant.now()));
        var published = store.publish(tenant, "one", base, 1, draft.extension().digest(), Instant.now());
        assertEquals("PUBLISHED", published.state());
        // The base model body is untouched: only the tenant overlay row exists.
        assertEquals(1, store.find(tenant, base, 1).orElseThrow().extension().revision());
        assertEquals(1, store.latest(tenant, base).orElseThrow().extension().revision());
        assertTrue(store.latest(new TenantId(tenant.value() + "-other"), base).isEmpty());
        assertTrue(store.find(new TenantId(tenant.value() + "-other"), base, 1).isEmpty());
        assertEquals(published.extension().digest(), store.publish(tenant, "one", base, 1, draft.extension().digest(), Instant.now()).extension().digest());
        var reopened = store();
        assertEquals(published.extension().digest(), reopened.find(tenant, base, 1).orElseThrow().extension().digest());
        assertEquals(1, reopened.published(tenant, 51).size());
    }

    @Test void publishedRevisionsStayAdditiveAndConsecutive() {
        var store = store();
        var first = store.save(tenant, "one", base, List.of(field("business_code")), 0, Instant.now());
        store.publish(tenant, "one", base, 1, first.extension().digest(), Instant.now());
        var second = store.save(tenant, "one", base, List.of(field("business_code"), field("owner")), 1, Instant.now());
        assertEquals(2, second.extension().revision());
        assertEquals(2, store.publish(tenant, "one", base, 2, second.extension().digest(), Instant.now()).extension().revision());
        var dropping = store.save(tenant, "one", base, List.of(field("serial")), 2, Instant.now());
        assertEquals(3, dropping.extension().revision());
        assertThrows(CatalogFailure.class, () -> store.publish(tenant, "one", base, 3, dropping.extension().digest(), Instant.now()));
        assertEquals(2, store.latest(tenant, base).orElseThrow().extension().revision());
        assertTrue(store.find(tenant, base, 3).isEmpty());
    }

    @Test void compareAndSetAcrossIndependentConnectionsHasExactlyOneWinner() throws Exception {
        var store = store();
        store.save(tenant, "one", base, List.of(field("business_code")), 0, Instant.now());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try { store.save(tenant, "one", base, List.of(field("business_code"), field("owner_" + n)), 1, Instant.now()); return true; }
                    catch (CatalogFailure conflict) { assertEquals(CatalogFailure.Code.CONFLICT, conflict.code()); return false; }
                }));
            }
            start.countDown();
            int success = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) success++;
            assertEquals(1, success);
            assertEquals(2, store.drafts(tenant, "one", 51).getFirst().editVersion());
        }
    }

    @Test void corruptedStoredExtensionNeverReturnsAsTrustedVersion() throws Exception {
        var store = store();
        var draft = store.save(tenant, "one", base, List.of(field("business_code")), 0, Instant.now());
        store.publish(tenant, "one", base, 1, draft.extension().digest(), Instant.now());
        try (var c = source.getConnection(); var s = c.prepareStatement("UPDATE catalog.model_extension_version SET digest=? WHERE tenant_id=?")) {
            s.setString(1, "sha256:" + "0".repeat(64)); s.setString(2, tenant.value()); s.executeUpdate();
        }
        assertThrows(IllegalStateException.class, () -> store.find(tenant, base, 1));
    }
}
