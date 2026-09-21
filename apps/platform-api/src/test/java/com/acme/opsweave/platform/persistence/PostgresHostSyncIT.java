package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.domain.PipelineDefinition;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresHostSyncIT {
    @Test
    void pagesUntilSnapshotThenRetiresOnlyAfterSuccess() {
        String tenant = "tenant-pg-" + UUID.randomUUID().toString().substring(0, 8);
        var properties = new OpsweaveProperties(
            new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant, "", "")),
            new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "zabbix-1", 1),
            new OpsweaveProperties.Inventory(
                "postgres",
                System.getenv("OPSWEAVE_TEST_JDBC_URL"),
                System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_USER", "opsweave_dev"),
                System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_PASSWORD", "")
            )
        );
        InventoryWiring wiring = InventoryWiring.open(properties);
        var principal = new Principal(
            new SubjectId("user-demo"),
            new TenantId(tenant),
            Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ),
            ResourceScope.tenantWide()
        );
        var staleKey = new ExternalObjectKey(principal.tenantId(), "zabbix-1", "host", "999", "1");
        var staleId = EntityIds.fromExternal(staleKey);
        wiring.writer().upsert(
            new Entity(staleId, principal.tenantId(), "host", "gone-host", Lifecycle.ACTIVE, 1, Instant.parse("2026-09-20T00:00:00Z"), Map.of("hostId", "999")),
            new Observation("obs-stale", staleKey, staleId, Instant.parse("2026-09-20T00:00:00Z"), Instant.parse("2026-09-20T00:00:01Z"), Map.of("hostId", "999"), "raw-stale", 1),
            new ExternalLink(staleId, staleKey)
        );
        var ingest = new IngestZabbixHostsUseCase(
            new AuthorizeUseCase(),
            new FixtureZabbixHostConnector(),
            wiring.writer(),
            wiring.rawRecords(),
            wiring.syncRuns(),
            PipelineDefinition.zabbixHostV1(),
            "labeled-fixture",
            wiring.label(),
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN",
            1
        );
        var outcome = ingest.execute(principal, null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, outcome.kind());
        assertEquals(2, outcome.pages());
        assertEquals(2, outcome.accepted());
        assertEquals(1, outcome.retired());
        assertEquals("postgres", outcome.inventoryStore());
        assertTrue(outcome.snapshotComplete());
        assertEquals(SyncStatus.SUCCEEDED, wiring.syncRuns().find(principal.tenantId(), outcome.syncRunId()).orElseThrow().status());
        assertEquals("INACTIVE", wiring.query().find(principal.tenantId(), staleId).orElseThrow().lifecycle());

        var failed = new IngestZabbixHostsUseCase(
            new AuthorizeUseCase(),
            new Connector() {
                private int calls;

                @Override
                public String type() {
                    return "zabbix";
                }

                @Override
                public ProbeResult probe(SourceContext source) {
                    return new ProbeResult(false, "test");
                }

                @Override
                public Page fetch(SourceContext source, String cursor, int limit) {
                    if (++calls > 1) {
                        throw new IllegalStateException("page failed");
                    }
                    return new FixtureZabbixHostConnector().fetch(source, cursor, 1);
                }
            },
            wiring.writer(),
            wiring.rawRecords(),
            wiring.syncRuns(),
            PipelineDefinition.zabbixHostV1(),
            "zabbix-jsonrpc",
            wiring.label(),
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN",
            1
        );
        var broken = failed.execute(principal, null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE, broken.kind());
        assertEquals("INACTIVE", wiring.query().find(principal.tenantId(), staleId).orElseThrow().lifecycle());
        var stillThere = wiring.query().list(principal.tenantId()).stream()
            .filter(view -> "10085".equals(view.attributes().get("hostId")))
            .findFirst()
            .orElseThrow();
        assertEquals("ACTIVE", stillThere.lifecycle());
    }
}
