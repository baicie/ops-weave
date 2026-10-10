package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.inventory.api.ExternalLinkRegistrationStore;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalLinkRegistration;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** PostgreSQL external-link registrations: idempotent receipts, no silent rebinding, resolvable links. */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresExternalLinkRegistrationIT extends OwnedInventoryTest {
    private static final Instant AT = Instant.parse("2026-10-10T00:00:00Z");
    private final TenantId tenant = new TenantId("extlink-pg-" + UUID.randomUUID());
    private final InventoryWiring wiring = openInventory(new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "extlink-fixture", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"),
            System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_USER", "opsweave_dev"),
            System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));

    @Test void registrationIsIdempotentAndNeverSilentlyRebinds() {
        var target = seed("target");
        var other = seed("other");
        var requestId = UUID.randomUUID();
        var registration = registration(requestId, "host-1", target);

        var first = wiring.externalLinkRegistrations().register(registration, AT);
        assertFalse(first.replayed());
        assertEquals(target, wiring.query().linkOf(key("host-1")).orElseThrow().entityId());

        var replay = wiring.externalLinkRegistrations().register(registration, AT);
        assertTrue(replay.replayed());

        var changed = registration(requestId, "host-1", other);
        assertThrows(ExternalLinkRegistrationStore.Conflict.class, () -> wiring.externalLinkRegistrations().register(changed, AT));

        var rebind = registration(UUID.randomUUID(), "host-1", other);
        assertThrows(ExternalLinkRegistrationStore.Conflict.class, () -> wiring.externalLinkRegistrations().register(rebind, AT));
        assertEquals(target, wiring.query().linkOf(key("host-1")).orElseThrow().entityId(), "binding is not silently rebound");
    }

    @Test void registrationsSurviveReopenAndRespectTenantScope() {
        var target = seed("persist");
        var request = registration(UUID.randomUUID(), "host-persist", target);
        wiring.externalLinkRegistrations().register(request, AT);

        var reopened = openInventory(properties());
        var stored = reopened.externalLinkRegistrations().find(tenant, request.requestId()).orElseThrow();
        assertEquals(target, stored.registration().targetId());
        assertEquals(target, reopened.query().linkOf(key("host-persist")).orElseThrow().entityId());

        var otherTenant = new TenantId("extlink-other-" + UUID.randomUUID());
        assertTrue(reopened.externalLinkRegistrations().find(otherTenant, request.requestId()).isEmpty());
        assertTrue(reopened.externalLinkRegistrations().page(otherTenant, null, null, 25).isEmpty());
    }

    @Test void pageIsBoundedAndOrderedByRequest() {
        var target = seed("page");
        for (int i = 0; i < 3; i++) {
            wiring.externalLinkRegistrations().register(registration(UUID.randomUUID(), "host-page-" + i, target), AT);
        }
        var page = wiring.externalLinkRegistrations().page(tenant, null, null, 2);
        assertEquals(3, page.size(), "store returns limit + 1 for a bounded page");
        var next = wiring.externalLinkRegistrations().page(tenant, null, page.getFirst().registration().requestId(), 2);
        assertEquals(2, next.size());
        assertEquals(page.get(1).registration().requestId(), next.getFirst().registration().requestId());
    }

    private OpsweaveProperties properties() {
        return new OpsweaveProperties(
            new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
            new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "extlink-fixture", 1),
            new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"),
                System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_USER", "opsweave_dev"),
                System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    }

    private ExternalObjectKey key(String externalId) {
        return new ExternalObjectKey(tenant, "workflow.demo", "entity", externalId, "builtin.host");
    }

    private ExternalLinkRegistration registration(UUID requestId, String externalId, EntityId target) {
        return ExternalLinkRegistration.of(requestId, tenant, "workflow.demo", "entity", externalId, "builtin.host", target, "来源身份确认");
    }

    private EntityId seed(String externalId) {
        var id = new EntityId(UUID.randomUUID());
        var entity = new Entity(id, tenant, "Host", externalId, Lifecycle.ACTIVE, 1, AT, Map.of("name", externalId));
        var seedKey = new ExternalObjectKey(tenant, "extlink-pg", "host", externalId, "1");
        var observation = new Observation("extlink-" + externalId + "-" + id.value(), seedKey, id, AT, AT,
            Map.of("name", externalId), "extlink-raw-" + id, 1);
        wiring.writer().upsert(entity, observation, new ExternalLink(id, seedKey));
        return id;
    }
}
