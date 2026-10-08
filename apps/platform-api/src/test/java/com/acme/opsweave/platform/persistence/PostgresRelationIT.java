package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.inventory.api.RelationStore;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** PostgreSQL relation persistence: idempotency, endpoint CAS, temporal pages and tenant scope. */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresRelationIT extends OwnedInventoryTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private final TenantId tenant = new TenantId("relation-pg-" + UUID.randomUUID());
    private final InventoryWiring wiring = openInventory(new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "relation-fixture", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"),
            System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_USER", "opsweave_dev"),
            System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));

    @Test void writesAreIdempotentAndEndpointVersionsAreChecked() {
        var from = seed("from");
        var to = seed("to");
        var requestId = UUID.randomUUID();
        var relation = relation(requestId, from, to, START, null, "operator-a");

        var first = wiring.relations().write(tenant, requestId, relation, 1, 1);
        assertFalse(first.replayed());
        var replay = wiring.relations().write(tenant, requestId, relation, 1, 1);
        assertTrue(replay.replayed());
        assertEquals(first.relation(), replay.relation());

        var changed = relation(requestId, from, to, START, null, "operator-b");
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().write(tenant, requestId, changed, 1, 1));

        var second = relation(UUID.randomUUID(), from, to, START.plusSeconds(1), null, "operator-c");
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().write(tenant, second.id(), second, 2, 1));
    }

    @Test void pageKeepsAsOfCursorAndTenantBoundaries() {
        var from = seed("page-from");
        var to = seed("page-to");
        var other = seed("page-other");
        var first = relation(UUID.randomUUID(), from, to, START, null, "first");
        var second = relation(UUID.randomUUID(), from, other, START.plusSeconds(1), null, "second");
        wiring.relations().write(tenant, first.id(), first, 1, 1);
        wiring.relations().write(tenant, second.id(), second, 1, 1);

        var beforeStart = wiring.relations().page(tenant, from, null, START.minusSeconds(1), 50);
        assertTrue(beforeStart.isEmpty());

        var page = wiring.relations().page(tenant, from, null, START.plusSeconds(2), 1);
        assertEquals(2, page.size(), "store returns limit + 1 for a bounded page");
        var cursor = page.getFirst().id();
        var next = wiring.relations().page(tenant, from, cursor, START.plusSeconds(2), 1);
        assertEquals(1, next.size());
        assertNotEquals(cursor, next.getFirst().id());
        assertTrue(Set.of(first.id(), second.id()).contains(next.getFirst().id()));

        var tenantMismatch = new TenantId("relation-pg-other-" + UUID.randomUUID());
        assertTrue(wiring.relations().page(tenantMismatch, from, null, START.plusSeconds(2), 50).isEmpty());
    }

    private EntityId seed(String externalId) {
        var id = new EntityId(UUID.randomUUID());
        var entity = new Entity(id, tenant, "Application", externalId, Lifecycle.ACTIVE, 1, START,
            Map.of("name", externalId));
        var key = new ExternalObjectKey(tenant, "relation-pg", "entity", externalId, "1");
        var observation = new Observation("relation-" + externalId + "-" + id.value(), key, id, START, START,
            Map.of("name", externalId), "relation-raw-" + id, 1);
        wiring.writer().upsert(entity, observation, new ExternalLink(id, key));
        return id;
    }

    private EntityRelation relation(UUID requestId, EntityId from, EntityId to, Instant validFrom, Instant validTo, String sourceRef) {
        return new EntityRelation(requestId, tenant, from, "builtin.depends_on", 1, to, validFrom, validTo, sourceRef, "unknown", 1);
    }
}
