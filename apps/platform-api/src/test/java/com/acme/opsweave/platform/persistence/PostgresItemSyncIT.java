package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.domain.ZabbixItemMapper;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresItemSyncIT {
    @Test
    void itemScanWritesDefinitionAndRetiresOnlyMissingItems() {
        String tenant = "tenant-metric-" + UUID.randomUUID().toString().substring(0, 8);
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
            Set.of(Permission.SOURCE_SYNC, Permission.METRIC_READ),
            ResourceScope.tenantWide()
        );
        var stale = new ZabbixItemMapper().map(principal.tenantId(), "zabbix-1", Map.of(
            "itemid", "29999",
            "key_", "system.cpu.util[,user]",
            "name", "stale",
            "value_type", "0",
            "units", "%",
            "hostid", "10084"
        ));
        wiring.metrics().upsert(new com.acme.opsweave.telemetry.domain.MetricDefinition(
            stale.id(), stale.tenantId(), "stale.metric", "stale", "host", "1",
            stale.valueType(), stale.metricType(), Map.of(), stale.origin(),
            new com.acme.opsweave.telemetry.domain.ExternalMetricMapping(
                "zabbix", "zabbix-1", "29999", "stale.key", "10084", "%", "identity", 1
            ),
            MetricLifecycle.ACTIVE, 1
        ));
        var outcome = new IngestZabbixItemsUseCase(
            new AuthorizeUseCase(),
            new FixtureZabbixItemConnector(),
            wiring.metrics(),
            wiring.rawRecords(),
            wiring.syncRuns(),
            "labeled-fixture",
            wiring.label(),
            "zabbix-1",
            "env:OPSWEAVE_ZABBIX_TOKEN",
            1
        ).execute(principal, null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED, outcome.kind());
        assertEquals(1, outcome.accepted());
        assertEquals(1, outcome.rejected());
        assertEquals(1, outcome.retired());
        assertEquals("postgres", outcome.inventoryStore());
        var stored = wiring.metrics().list(principal.tenantId());
        assertEquals(MetricLifecycle.INACTIVE, stored.stream()
            .filter(item -> "29999".equals(item.externalMapping().externalId())).findFirst().orElseThrow().lifecycle());
        var cpu = stored.stream().filter(item -> "20001".equals(item.externalMapping().externalId())).findFirst().orElseThrow();
        assertEquals("host.cpu.usage.user", cpu.name());
        assertEquals(MetricLifecycle.ACTIVE, cpu.lifecycle());
    }
}
