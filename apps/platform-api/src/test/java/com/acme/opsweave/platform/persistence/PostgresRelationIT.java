package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.inventory.api.RelationStore;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.inventory.domain.RelationCardinality;
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

        var first = wiring.relations().write(tenant, requestId, relation, 1, 1, RelationCardinality.MANY_TO_MANY);
        assertFalse(first.replayed());
        var replay = wiring.relations().write(tenant, requestId, relation, 1, 1, RelationCardinality.MANY_TO_MANY);
        assertTrue(replay.replayed());
        assertEquals(first.relation(), replay.relation());

        var changed = relation(requestId, from, to, START, null, "operator-b");
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().write(tenant, requestId, changed, 1, 1, RelationCardinality.MANY_TO_MANY));

        var second = relation(UUID.randomUUID(), from, to, START.plusSeconds(1), null, "operator-c");
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().write(tenant, second.id(), second, 2, 1, RelationCardinality.MANY_TO_MANY));
    }

    @Test void pageKeepsAsOfCursorAndTenantBoundaries() {
        var from = seed("page-from");
        var to = seed("page-to");
        var other = seed("page-other");
        var first = relation(UUID.randomUUID(), from, to, START, null, "first");
        var second = relation(UUID.randomUUID(), from, other, START.plusSeconds(1), null, "second");
        wiring.relations().write(tenant, first.id(), first, 1, 1, RelationCardinality.MANY_TO_MANY);
        wiring.relations().write(tenant, second.id(), second, 1, 1, RelationCardinality.MANY_TO_MANY);

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

    @Test void closeIsIdempotentAndHistoryKeepsRows() {
        var from = seed("close-from");
        var to = seed("close-to");
        var requestId = UUID.randomUUID();
        var relation = relation(requestId, from, to, START, null, "operator");
        wiring.relations().write(tenant, requestId, relation, 1, 1, RelationCardinality.MANY_TO_MANY);

        var closeRequest = UUID.randomUUID();
        var end = START.plusSeconds(30);
        var closed = wiring.relations().close(tenant, closeRequest, requestId, end, "业务确认解绑", 1);
        assertFalse(closed.replayed());
        assertEquals(2, closed.relation().version());
        assertEquals("业务确认解绑", closed.relation().closeReason());
        var replay = wiring.relations().close(tenant, closeRequest, requestId, end, "业务确认解绑", 1);
        assertTrue(replay.replayed());
        assertEquals(closed.relation(), replay.relation());

        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().close(tenant, closeRequest, requestId, START.plusSeconds(60), "改动后的理由", 1));
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().close(tenant, UUID.randomUUID(), requestId, end, "重复终止", 2));
        assertThrows(RelationStore.NotFound.class, () -> wiring.relations().close(tenant, UUID.randomUUID(), UUID.randomUUID(), end, "missing", 1));

        assertTrue(wiring.relations().page(tenant, from, null, START.plusSeconds(60), 50).isEmpty());
        var history = wiring.relations().history(tenant, from, null, 50);
        assertEquals(1, history.size());
        assertEquals("业务确认解绑", history.getFirst().closeReason());
        var createReplay = wiring.relations().write(tenant, requestId, relation, 1, 1, RelationCardinality.MANY_TO_MANY);
        assertTrue(createReplay.replayed());
        assertEquals(2, createReplay.relation().version());
        assertEquals("业务确认解绑", createReplay.relation().closeReason());
        var found = wiring.relations().find(tenant, requestId);
        assertTrue(found.isPresent());
        assertEquals(2, found.get().version());
    }

    @Test void duplicateRelationsAndResurrectionAreRejected() {
        var from = seed("dup-from");
        var to = seed("dup-to");
        var first = UUID.randomUUID();
        wiring.relations().write(tenant, first, relation(first, from, to, START, null, "source-a"), 1, 1, RelationCardinality.MANY_TO_MANY);
        var duplicate = UUID.randomUUID();
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().write(tenant, duplicate, relation(duplicate, from, to, START, null, "source-b"), 1, 1, RelationCardinality.MANY_TO_MANY));

        wiring.relations().close(tenant, UUID.randomUUID(), first, START.plusSeconds(10), "来源失效确认", 1);
        var resurrect = UUID.randomUUID();
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().write(tenant, resurrect, relation(resurrect, from, to, START.plusSeconds(5), null, "source-b"), 1, 1, RelationCardinality.MANY_TO_MANY));
        assertEquals(1, wiring.relations().history(tenant, from, null, 50).size());
    }

    @Test void cardinalityIsEnforcedInsideTheWriteTransaction() {
        var hub = seed("card-hub");
        var a = seed("card-a");
        var b = seed("card-b");
        var first = UUID.randomUUID();
        wiring.relations().write(tenant, first, relation(first, hub, a, START, null, "one"), 1, 1, RelationCardinality.ONE_TO_MANY);
        var second = UUID.randomUUID();
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().write(tenant, second, relation(second, hub, b, START, null, "two"), 1, 1, RelationCardinality.ONE_TO_MANY));

        wiring.relations().close(tenant, UUID.randomUUID(), first, START.plusSeconds(10), "接口回收", 1);
        wiring.relations().write(tenant, second, relation(second, hub, b, START, null, "two"), 1, 1, RelationCardinality.ONE_TO_MANY);
        assertEquals(2, wiring.relations().history(tenant, hub, null, 50).size());
    }

    @Test void correctionsKeepBothRowsAndReplayIdempotently() {
        var from = seed("correct-from");
        var to = seed("correct-to");
        var other = seed("correct-other");
        var requestId = UUID.randomUUID();
        wiring.relations().write(tenant, requestId, relation(requestId, from, to, START, null, "operator"), 1, 1, RelationCardinality.ONE_TO_MANY);
        var correctionRequest = UUID.randomUUID();
        var replacement = relation(UUID.randomUUID(), from, other, START.plusSeconds(30), null, "operator");
        var corrected = wiring.relations().correct(tenant, correctionRequest, requestId, "端点录入错误", 1, replacement, RelationCardinality.ONE_TO_MANY);
        assertFalse(corrected.replayed());
        var kept = wiring.relations().find(tenant, requestId).orElseThrow();
        assertEquals(START.plusSeconds(30), kept.validTo());
        assertEquals("端点录入错误", kept.closeReason());
        assertEquals(2, kept.version());
        assertEquals(to, kept.toEntityId());
        var replacementRow = wiring.relations().find(tenant, corrected.relation().id()).orElseThrow();
        assertEquals(other, replacementRow.toEntityId());
        assertEquals(1, replacementRow.version());
        var replay = wiring.relations().correct(tenant, correctionRequest, requestId, "端点录入错误", 1,
            relation(UUID.randomUUID(), from, other, START.plusSeconds(60), null, "operator"), RelationCardinality.ONE_TO_MANY);
        assertTrue(replay.replayed());
        assertEquals(corrected.relation().id(), replay.relation().id());
        assertThrows(RelationStore.CorrectionConflict.class, () -> wiring.relations().correct(tenant, correctionRequest, requestId,
            "改动后的理由", 1, replacement, RelationCardinality.ONE_TO_MANY));
        assertThrows(RelationStore.CorrectionConflict.class, () -> wiring.relations().correct(tenant, UUID.randomUUID(), requestId,
            "终止后不复活", 1, relation(UUID.randomUUID(), from, to, START.plusSeconds(40), null, "operator"), RelationCardinality.ONE_TO_MANY));
        assertThrows(RelationStore.CorrectionConflict.class, () -> wiring.relations().correct(tenant, UUID.randomUUID(), corrected.relation().id(),
            "版本失配", 7, relation(UUID.randomUUID(), from, other, START.plusSeconds(40), null, "operator"), RelationCardinality.ONE_TO_MANY));
        assertThrows(RelationStore.Conflict.class, () -> wiring.relations().write(tenant, UUID.randomUUID(),
            relation(UUID.randomUUID(), from, to, START.plusSeconds(40), null, "operator"), 1, 1, RelationCardinality.ONE_TO_MANY));
        var records = wiring.relations().corrections(tenant, from, null, 50);
        assertEquals(1, records.size());
        assertEquals(requestId, records.getFirst().previousRelationId());
        assertEquals(corrected.relation().id(), records.getFirst().relationId());
        assertEquals("端点录入错误", records.getFirst().reason());
    }

    @Test void confirmationsMergeSourcesAndDeriveTermination() {
        var from = seed("confirm-from");
        var to = seed("confirm-to");
        var requestId = UUID.randomUUID();
        wiring.relations().write(tenant, requestId, relation(requestId, from, to, START, null, "operator"), 1, 1, RelationCardinality.MANY_TO_MANY);
        var first = wiring.relations().confirm(tenant, UUID.randomUUID(), requestId,
            new com.acme.opsweave.inventory.domain.RelationConfirmation("source", "workflow.a", "batch-1", "fixture", START.plusSeconds(1), null, null));
        assertFalse(first.replayed());
        wiring.relations().confirm(tenant, UUID.randomUUID(), requestId,
            new com.acme.opsweave.inventory.domain.RelationConfirmation("source", "workflow.b", "batch-1", "import", START.plusSeconds(1), null, null));
        var replay = wiring.relations().confirm(tenant, UUID.randomUUID(), requestId,
            new com.acme.opsweave.inventory.domain.RelationConfirmation("source", "workflow.a", "batch-1", "fixture", START.plusSeconds(2), null, null));
        assertTrue(replay.replayed());
        assertEquals(2, wiring.relations().confirmations(tenant, requestId).size());
        assertThrows(RelationStore.ConfirmationConflict.class, () -> wiring.relations().confirm(tenant, UUID.randomUUID(), requestId,
            new com.acme.opsweave.inventory.domain.RelationConfirmation("source", "workflow.a", "batch-1", "unknown", START.plusSeconds(1), null, null)));
        var key = new com.acme.opsweave.inventory.domain.RelationConfirmation.Key("source", "workflow.a", "batch-1");
        var withdrawn = wiring.relations().withdraw(tenant, UUID.randomUUID(), requestId, key, START.plusSeconds(3), "来源批次撤销");
        assertTrue(withdrawn.entry().withdrawn());
        assertTrue(wiring.relations().find(tenant, requestId).orElseThrow().validTo() == null, "other source still confirms");
        var again = wiring.relations().withdraw(tenant, UUID.randomUUID(), requestId, key, START.plusSeconds(4), "另一个理由");
        assertTrue(again.replayed());
        assertEquals("来源批次撤销", again.entry().withdrawReason());
        var last = wiring.relations().withdraw(tenant, UUID.randomUUID(), requestId,
            new com.acme.opsweave.inventory.domain.RelationConfirmation.Key("source", "workflow.b", "batch-1"), START.plusSeconds(5), "来源下线");
        assertFalse(last.replayed());
        var terminated = wiring.relations().find(tenant, requestId).orElseThrow();
        assertEquals(START.plusSeconds(5), terminated.validTo());
        assertEquals("来源下线", terminated.closeReason());
        assertEquals(2, terminated.version());
        assertEquals(2, wiring.relations().confirmations(tenant, requestId).size());
        assertThrows(RelationStore.ConfirmationConflict.class, () -> wiring.relations().confirm(tenant, UUID.randomUUID(), requestId,
            new com.acme.opsweave.inventory.domain.RelationConfirmation("manual", "operator", "assert-1", "unknown", START.plusSeconds(6), null, null)));
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
