package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acme.opsweave.integration.infrastructure.InMemorySourceItemWrites;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceItemWriteCohortTest {
    private final TenantId tenant = new TenantId("cohort-test");
    private final InMemoryInventoryStore leases = new InMemoryInventoryStore();
    private final InMemoryMetricDefinitionStore definitions = new InMemoryMetricDefinitionStore();
    private final InMemorySourceItemWrites writes = new InMemorySourceItemWrites(leases, definitions);
    private final SourceScan.Scope scope = new SourceScan.Scope(tenant, "source-1", "item");

    @Test
    void retirementIsLimitedToEachCapturedHostCohort() {
        seed("item-a-kept", "host-a");
        seed("item-a-missing", "host-a");
        seed("item-b-missing", "host-b");
        var scan = leases.beginScan(scope, UUID.randomUUID());

        assertEquals(1, writes.retireMissing(scan, Set.of("host-a"), Set.of("item-a-kept")));
        assertEquals(MetricLifecycle.ACTIVE, binding("item-a-kept").lifecycle());
        assertEquals(MetricLifecycle.INACTIVE, binding("item-a-missing").lifecycle());
        assertEquals(MetricLifecycle.ACTIVE, binding("item-b-missing").lifecycle());

        assertEquals(1, writes.retireMissing(scan, Set.of("host-b"), Set.of()));
        assertEquals(MetricLifecycle.INACTIVE, binding("item-b-missing").lifecycle());
        assertEquals(MetricLifecycle.INACTIVE, binding("item-a-missing").lifecycle());
    }

    @Test
    void rejectsEmptyOrMalformedCohortWithoutChangingBindings() {
        seed("item-a", "host-a");
        var scan = leases.beginScan(scope, UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () -> writes.retireMissing(scan, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> writes.retireMissing(scan, Set.of(" "), Set.of()));
        var nullItemIds = new HashSet<String>();
        nullItemIds.add(null);
        assertThrows(IllegalArgumentException.class, () -> writes.retireMissing(scan, Set.of("host-a"), nullItemIds));
        assertEquals(MetricLifecycle.ACTIVE, binding("item-a").lifecycle());
        assertEquals(1, binding("item-a").version());
    }

    @Test
    void lostLeaseRejectsCohortRetirementWithoutChangingBindings() {
        seed("item-a", "host-a");
        var scan = leases.beginScan(scope, UUID.randomUUID());
        leases.releaseScan(scan);

        var failure = assertThrows(SourceScan.Failure.class,
            () -> writes.retireMissing(scan, Set.of("host-a"), Set.of()));
        assertEquals(SourceScan.Code.LOST, failure.code());
        assertEquals(MetricLifecycle.ACTIVE, binding("item-a").lifecycle());
        assertEquals(1, binding("item-a").version());
    }

    private void seed(String itemId, String hostId) {
        definitions.upsert(new MetricBinding(
            tenant, "zabbix", "source-1", itemId,
            EntityIds.fromExternal(new ExternalObjectKey(tenant, "source-1", "host", hostId, "1")),
            hostId, "host.cpu.usage", Map.of(), "1", "identity", 1, MetricLifecycle.ACTIVE, 1
        ));
    }

    private MetricBinding binding(String itemId) {
        return definitions.findBinding(tenant, "source-1", itemId).orElseThrow();
    }
}
