package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.integration.domain.WorkflowHostScan;
import com.acme.opsweave.integration.domain.WorkflowRuntime;
import com.acme.opsweave.integration.domain.WorkflowQuality;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** RELATION output receipts survive PostgreSQL round-trips and the V064 backfill closes old bodies. */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresWorkflowRelationReceiptIT extends OwnedInventoryTest {
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private final TenantId tenant = new TenantId("relation-receipt-pg-" + UUID.randomUUID());
    private final String owner = "relation-receipt-author";
    private final DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    private final InventoryWiring wiring = openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")), new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "relation-receipt-fixture", 1), new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));

    private WorkflowRuntime.Execution execution(List<String> relationIds, List<WorkflowRuntime.Pending> pending, int accepted, String state, String error) {
        return new WorkflowRuntime.Execution(UUID.randomUUID(), "relation-receipt", 1, DIGEST, new WorkflowRuntime.Settings("source_id", "name"), "fixture", null, NOW,
            state, accepted, 0, 0, List.of(), error, null, relationIds, pending);
    }

    @Test void executionReceiptKeepsRelationIdsAndPendingAcrossReopen() {
        var relationId = UUID.randomUUID().toString();
        var original = execution(List.of(relationId), List.of(new WorkflowRuntime.Pending(1, "FROM_UNRESOLVED")), 2, "SUCCEEDED", null);
        assertEquals(1, original.written());
        assertEquals(2, original.written() + original.pending().size());
        wiring.workflows().transaction(tenant, s -> { s.addExecution(owner, original); return null; });
        var reopened = new PostgresWorkflowStore(source);
        assertEquals(original, reopened.transaction(tenant, s -> s.execution(owner, original.id()).orElseThrow()));
        assertEquals(List.of(original), reopened.transaction(tenant, s -> s.executions(owner)));
        assertThrows(IllegalArgumentException.class, () -> execution(List.of(relationId), List.of(), 2, "SUCCEEDED", null));
    }

    @Test void legacyExecutionBodiesBackfillWithEmptyRelationOutcomes() {
        var id = UUID.randomUUID();
        var legacy = "{\"id\":\"" + id + "\",\"workflowId\":\"relation-receipt\",\"revision\":1,\"digest\":\"" + DIGEST + "\",\"settings\":{\"identityField\":\"source_id\",\"nameField\":\"name\"},\"origin\":\"fixture\",\"syncRunId\":null,\"createdAt\":\"" + NOW + "\",\"state\":\"SUCCEEDED\",\"accepted\":1,\"rejected\":0,\"filtered\":0,\"entityIds\":[\"" + UUID.randomUUID() + "\"],\"error\":null,\"authorizationId\":null}";
        try (var connection = source.getConnection(); var insert = connection.prepareStatement("INSERT INTO integration.workflow_execution (tenant_id,owner_subject,execution_id,body,created_at) VALUES (?,?,?,?::jsonb,?)")) {
            insert.setString(1, tenant.value());
            insert.setString(2, owner);
            insert.setObject(3, id);
            insert.setString(4, legacy);
            insert.setTimestamp(5, java.sql.Timestamp.from(NOW));
            assertEquals(1, insert.executeUpdate());
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException(failed);
        }
        // Same predicate as the V064 statements (jsonb "?" is jsonb_exists), scoped to this fixture's tenant.
        try (var connection = source.getConnection(); var migrate = connection.prepareStatement("UPDATE integration.workflow_execution SET body = body || '{\"relationIds\":[],\"pending\":[]}'::jsonb WHERE NOT jsonb_exists(body,'relationIds') AND tenant_id = ?")) {
            migrate.setString(1, tenant.value());
            assertEquals(1, migrate.executeUpdate());
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException(failed);
        }
        var reopened = new PostgresWorkflowStore(source);
        var backfilled = reopened.transaction(tenant, s -> s.execution(owner, id).orElseThrow());
        assertTrue(backfilled.relationIds().isEmpty() && backfilled.pending().isEmpty() && backfilled.written() == 1);
    }

    @Test void hostBatchReceiptCarriesRelationIdsAndPendingAcrossReopen() {
        var d = new com.acme.opsweave.integration.domain.WorkflowDefinition("relation-receipt", 1, "Fixture relation receipt",
            new com.acme.opsweave.integration.domain.WorkflowDefinition.Source("ZABBIX_HOST", "fixture-source"),
            new com.acme.opsweave.integration.domain.WorkflowDefinition.Target("builtin.host", 1, "sha256:" + "c".repeat(64)),
            List.of(new com.acme.opsweave.integration.domain.WorkflowDefinition.Node("source", com.acme.opsweave.integration.domain.WorkflowDefinition.Type.SOURCE, "1", Map.of()),
                new com.acme.opsweave.integration.domain.WorkflowDefinition.Node("map", com.acme.opsweave.integration.domain.WorkflowDefinition.Type.MAP, "1", Map.of("name", "name")),
                new com.acme.opsweave.integration.domain.WorkflowDefinition.Node("validate", com.acme.opsweave.integration.domain.WorkflowDefinition.Type.VALIDATE, "1", Map.of()),
                new com.acme.opsweave.integration.domain.WorkflowDefinition.Node("output", com.acme.opsweave.integration.domain.WorkflowDefinition.Type.OUTPUT, "1", Map.of())),
            List.of(new com.acme.opsweave.integration.domain.WorkflowDefinition.Edge("source", "map"), new com.acme.opsweave.integration.domain.WorkflowDefinition.Edge("map", "validate"), new com.acme.opsweave.integration.domain.WorkflowDefinition.Edge("validate", "output")));
        var layout = new java.util.HashMap<String, com.acme.opsweave.integration.api.WorkflowStore.Position>();
        for (var n : d.nodes()) layout.put(n.id(), new com.acme.opsweave.integration.api.WorkflowStore.Position(0, 0));
        wiring.workflows().transaction(tenant, s -> { s.publish(new com.acme.opsweave.integration.api.WorkflowStore.Entry(d, d.digest(), "PUBLISHED", 0, layout, Instant.now(), null), owner); return null; });
        var relationId = UUID.randomUUID().toString();
        var first = Map.<String, Object>of("name", "Synthetic Fixture private", "ip", "127.0.0.1", "lifecycle", "ACTIVE", "entity_id", UUID.randomUUID().toString());
        var second = Map.<String, Object>of("name", "Synthetic Fixture pending", "ip", "127.0.0.2", "lifecycle", "ACTIVE", "entity_id", UUID.randomUUID().toString());
        var batch = new WorkflowHostScan.Batch(UUID.randomUUID(), UUID.randomUUID(), "relation-receipt", 1, DIGEST, new WorkflowRuntime.Settings("entity_id", "name"), 1, null, null, true, NOW, List.of(first, second),
            "READY", List.of(), null, NOW, List.of(relationId), List.of(new WorkflowRuntime.Pending(1, "TO_UNRESOLVED")));
        assertEquals(2, batch.relationIds().size() + batch.pending().size());
        assertEquals(1, WorkflowQuality.host(batch).counts().confirmed());
        wiring.workflows().transaction(tenant, s -> { s.addHostBatch(owner, batch); return null; });
        var inFlight = batch.state("IN_FLIGHT", List.of(), batch.relationIds(), batch.pending(), null, NOW.plusSeconds(1));
        var confirmed = inFlight.state("CONFIRMED", List.of(), batch.relationIds(), batch.pending(), null, NOW.plusSeconds(2));
        wiring.workflows().transaction(tenant, s -> { s.finishHostBatch(owner, inFlight); s.finishHostBatch(owner, confirmed); return null; });
        var reopened = new PostgresWorkflowStore(source);
        assertEquals(confirmed, reopened.transaction(tenant, s -> s.hostBatch(owner, batch.id()).orElseThrow()));
        assertThrows(IllegalArgumentException.class, () -> batch.state("IN_FLIGHT", List.of(UUID.randomUUID().toString()), List.of(), List.of(), null, NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowHostScan.Batch(batch.id(), batch.scanId(), batch.workflowId(), 1, DIGEST, batch.settings(), 1, null, null, true, NOW, List.of(first, second), "READY", List.of(UUID.randomUUID().toString()), null, NOW, List.of(relationId), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowHostScan.Batch(batch.id(), batch.scanId(), batch.workflowId(), 1, DIGEST, batch.settings(), 1, null, null, true, NOW, List.of(first, second), "READY", List.of(), null, NOW, List.of(relationId, relationId), List.of()));
    }

    @Test void storeKeepsExecutionsScopedByOwnerAndTenant() {
        var relationId = UUID.randomUUID().toString();
        var original = execution(List.of(relationId), List.of(), 1, "SUCCEEDED", null);
        wiring.workflows().transaction(tenant, s -> { s.addExecution(owner, original); return null; });
        var reopened = new PostgresWorkflowStore(source);
        assertTrue(reopened.transaction(tenant, s -> s.executions("someone-else")).isEmpty());
        assertTrue(reopened.transaction(tenant, s -> s.execution("someone-else", original.id())).isEmpty());
        assertTrue(reopened.transaction(new TenantId(tenant.value() + "-other"), s -> s.executions(owner)).isEmpty());
    }
}
