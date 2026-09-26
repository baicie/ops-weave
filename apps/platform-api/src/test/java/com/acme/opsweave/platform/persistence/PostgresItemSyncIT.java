package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresItemSyncIT extends OwnedInventoryTest {
    @Test
    void itemScanWritesOneDefinitionAndRetiresOnlyMissingBindings() {
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
        InventoryWiring wiring = openInventory(properties);
        var principal = new Principal(
            new SubjectId("user-demo"),
            new TenantId(tenant),
            Set.of(Permission.SOURCE_SYNC, Permission.METRIC_READ),
            ResourceScope.tenantWide()
        );
        var tenantId = principal.tenantId();
        wiring.metrics().upsert(new MetricDefinition(
            tenantId, "stale.metric", "Stale", "1", MetricValueType.DOUBLE, MetricType.GAUGE, List.of("mode"), 1
        ));
        wiring.metrics().upsert(binding(tenantId, "29999"));
        wiring.metrics().upsert(binding(tenantId, "20002"));
        var mappings = ClasspathMappingCatalog.load(PostgresItemSyncIT.class.getClassLoader());
        var outcome = new IngestZabbixItemsUseCase(
            new AuthorizeUseCase(),
            new FixtureZabbixItemConnector(),
            mappings,
            wiring.writer(),
            wiring.itemWrites(),
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
        var definitions = wiring.metrics().list(tenantId);
        assertEquals(1, definitions.stream().filter(item -> "host.cpu.usage.user".equals(item.metricKey())).count());
        assertTrue(definitions.stream().anyMatch(item -> "stale.metric".equals(item.metricKey())));
        var bindings = wiring.metrics().listBindings(tenantId);
        assertEquals(MetricLifecycle.INACTIVE, lifecycle(bindings, "29999"));
        assertEquals(MetricLifecycle.ACTIVE, lifecycle(bindings, "20002"));
        var cpu = bindings.stream().filter(item -> "20001".equals(item.externalItemId())).findFirst().orElseThrow();
        assertEquals("host.cpu.usage.user", cpu.metricKey());
        assertEquals(MetricLifecycle.ACTIVE, cpu.lifecycle());
        assertEquals("10084", cpu.hostExternalId());
    }

    private static MetricBinding binding(TenantId tenant, String itemId) {
        return new MetricBinding(
            tenant, "zabbix", "zabbix-1", itemId,
            EntityIds.fromExternal(new ExternalObjectKey(tenant, "zabbix-1", "host", "10084", "1")),
            "10084", "stale.metric", Map.of("mode", "idle"), "%", "identity", 1, MetricLifecycle.ACTIVE, 1
        );
    }

    private static MetricLifecycle lifecycle(java.util.List<MetricBinding> bindings, String itemId) {
        return bindings.stream().filter(item -> itemId.equals(item.externalItemId())).findFirst().orElseThrow().lifecycle();
    }
}
