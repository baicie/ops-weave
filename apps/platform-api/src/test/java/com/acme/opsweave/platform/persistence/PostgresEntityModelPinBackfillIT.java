package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.inventory.api.EntityModelPinBackfillStore;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL", matches=".+")
class PostgresEntityModelPinBackfillIT extends OwnedInventoryTest {
    final TenantId tenant = new TenantId("pin-backfill-" + UUID.randomUUID());
    final DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    final InventoryWiring wiring = openInventory(new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "pin-backfill-fixture", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
    static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    final ModelDefinition model = new BuiltinCatalog().definitions().stream()
        .filter(definition -> definition.id().equals("builtin.host")).findFirst().orElseThrow();
    final EntityModelPin pin = new EntityModelPin(model.id(), model.revision(), model.digest());
    final EntityModelPinBackfillStore.Conformance conformance = attributes -> {
        try { return ModelPreview.evaluate(model, attributes).valid(); }
        catch (RuntimeException indeterminable) { return false; }
    };

    Entity entity(int n, Map<String, Object> attributes) {
        return new Entity(EntityId.parse(String.format("%08x-0000-0000-0000-000000000001", n)), tenant, "Host", "Row " + n, Lifecycle.ACTIVE, 1, NOW, attributes);
    }

    @Test void backfillPinsOnlyVerifiableRowsAndReplaysDurableReceipts() {
        wiring.entityInstances().write(tenant, UUID.randomUUID(), entity(1, Map.of("hostname", "edge-01", "ip", "10.0.0.1")), (Long) null);
        wiring.entityInstances().write(tenant, UUID.randomUUID(), entity(2, Map.of("hostname", "edge-02", "unknown", "x")), (Long) null);
        wiring.entityInstances().write(tenant, UUID.randomUUID(), entity(3, Map.of("hostname", "edge-03")), (Long) null,
            pin.id(), pin.revision(), pin.digest());
        var key = new ExternalObjectKey(tenant, "pin-backfill-source", "host", "ext-4", "1");
        wiring.writer().upsert(entity(4, Map.of("hostname", "edge-04")),
            new Observation("seed-4", key, entity(4, Map.of()).id(), NOW, NOW, Map.of("hostname", "edge-04"), "raw-4", 1), new ExternalLink(entity(4, Map.of()).id(), key));

        var request = UUID.randomUUID();
        var receipt = new PostgresEntityModelPinBackfillStore(source).backfill(tenant, request, "Host", pin, 50, conformance, NOW);
        assertFalse(receipt.replayed());
        assertEquals(new EntityModelPinBackfillStore.Counts(2, 1, 1, 1, 1), receipt.counts());
        assertEquals(pin, wiring.query().find(tenant, entity(1, Map.of()).id()).orElseThrow().model());
        assertNull(wiring.query().find(tenant, entity(2, Map.of()).id()).orElseThrow().model());
        assertEquals(pin, wiring.query().find(tenant, entity(3, Map.of()).id()).orElseThrow().model());
        assertNull(wiring.query().find(tenant, entity(4, Map.of()).id()).orElseThrow().model());

        // A later row is not silently rewritten by the idempotent replay of the earlier command.
        wiring.entityInstances().write(tenant, UUID.randomUUID(), entity(5, Map.of("hostname", "edge-05")), (Long) null);
        var replay = new PostgresEntityModelPinBackfillStore(source).backfill(tenant, request, "Host", pin, 50, conformance, NOW.plusSeconds(60));
        assertTrue(replay.replayed());
        assertEquals(receipt.counts(), replay.counts());
        assertNull(wiring.query().find(tenant, entity(5, Map.of()).id()).orElseThrow().model());
        var stored = new PostgresEntityModelPinBackfillStore(source).receipt(tenant, request).orElseThrow();
        assertFalse(stored.replayed());
        assertEquals(receipt.counts(), stored.counts());
        assertEquals(request, stored.requestId());
        assertTrue(new PostgresEntityModelPinBackfillStore(source).receipt(tenant, UUID.randomUUID()).isEmpty());

        // The same requestId with changed content conflicts and rewrites nothing.
        assertThrows(EntityModelPinBackfillStore.Conflict.class,
            () -> new PostgresEntityModelPinBackfillStore(source).backfill(tenant, request, "Host", pin, 25, conformance, NOW));
        assertThrows(EntityModelPinBackfillStore.Conflict.class,
            () -> new PostgresEntityModelPinBackfillStore(source).backfill(tenant, request, "Application", pin, 50, conformance, NOW));
        assertNull(wiring.query().find(tenant, entity(2, Map.of()).id()).orElseThrow().model());
        assertNull(wiring.query().find(tenant, entity(5, Map.of()).id()).orElseThrow().model());
    }

    @Test void maxUpdatesIsAHardBudgetOverUnpinnedRows() {
        for (int n = 1; n <= 3; n++) wiring.entityInstances().write(tenant, UUID.randomUUID(), entity(n, Map.of("hostname", "edge-0" + n)), (Long) null);
        var first = new PostgresEntityModelPinBackfillStore(source).backfill(tenant, UUID.randomUUID(), "Host", pin, 1, conformance, NOW);
        assertEquals(new EntityModelPinBackfillStore.Counts(1, 1, 0, 0, 0), first.counts());
        int pinned = 0;
        for (int n = 1; n <= 3; n++) if (wiring.query().find(tenant, entity(n, Map.of()).id()).orElseThrow().model() != null) pinned++;
        assertEquals(1, pinned);
        var second = new PostgresEntityModelPinBackfillStore(source).backfill(tenant, UUID.randomUUID(), "Host", pin, 5, conformance, NOW);
        assertEquals(new EntityModelPinBackfillStore.Counts(2, 2, 0, 1, 0), second.counts());
        assertThrows(IllegalArgumentException.class, () -> new PostgresEntityModelPinBackfillStore(source).backfill(tenant, UUID.randomUUID(), "Host", pin, 0, conformance, NOW));
        assertThrows(IllegalArgumentException.class, () -> new PostgresEntityModelPinBackfillStore(source).backfill(tenant, UUID.randomUUID(), "Host", pin, 501, conformance, NOW));
    }

    @Test void receiptsAndRowsAreTenantScoped() {
        wiring.entityInstances().write(tenant, UUID.randomUUID(), entity(1, Map.of("hostname", "edge-01")), (Long) null);
        var request = UUID.randomUUID();
        new PostgresEntityModelPinBackfillStore(source).backfill(tenant, request, "Host", pin, 50, conformance, NOW);
        var other = new TenantId(tenant.value() + "-other");
        assertTrue(new PostgresEntityModelPinBackfillStore(source).receipt(other, request).isEmpty());
        // The same requestId in another tenant is an independent command over that tenant's own rows.
        var scoped = new PostgresEntityModelPinBackfillStore(source).backfill(other, request, "Host", pin, 50, conformance, NOW);
        assertFalse(scoped.replayed());
        assertEquals(new EntityModelPinBackfillStore.Counts(0, 0, 0, 0, 0), scoped.counts());
        assertEquals(pin, wiring.query().find(tenant, entity(1, Map.of()).id()).orElseThrow().model());
        assertTrue(wiring.query().find(other, entity(1, Map.of()).id()).isEmpty());
    }
}
