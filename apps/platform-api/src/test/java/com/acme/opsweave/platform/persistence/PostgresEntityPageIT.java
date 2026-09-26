package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.application.BrowseEntitiesUseCase;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresEntityPageIT extends OwnedInventoryTest {
    private final TenantId tenant = new TenantId("entity-page-" + UUID.randomUUID());
    private final InventoryWiring wiring = openInventory(new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "zabbix-1", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"),
            System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_USER", "opsweave_dev"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
    private static EntityId id(int n) { return EntityId.parse(String.format("%08x-0000-0000-0000-000000000001", n)); }
    private Principal principal(ResourceScope scope) { return new Principal(new SubjectId("reader"), tenant, Set.of(Permission.ENTITY_READ), scope); }
    private void put(TenantId target, EntityId id, String name, String type, Lifecycle lifecycle, Map<String,Object> attrs) {
        Instant now = Instant.now(); var key = new ExternalObjectKey(target, "zabbix-1", type, id.value().toString(), "1");
        wiring.writer().upsert(new Entity(id, target, type, name, lifecycle, 1, now, attrs),
            new Observation(UUID.randomUUID().toString(), key, id, now, now, attrs, "raw-test", 1), new ExternalLink(id, key));
    }
    @Test void paginatesAfterTenantAndObjectScopeAndTreatsSearchLiterally() {
        for (int i = 1; i <= 60; i++) put(tenant, id(i), "Host " + i, "host", Lifecycle.ACTIVE, Map.of("ip", "10.0.0." + i));
        put(tenant, id(61), "literal_% Service", "service", Lifecycle.INACTIVE, Map.of());
        put(new TenantId("foreign-" + UUID.randomUUID()), id(62), "foreign", "host", Lifecycle.ACTIVE, Map.of());
        put(tenant, id(Integer.MIN_VALUE), "unsigned uuid", "host", Lifecycle.ACTIVE, Map.of());
        var browse = new BrowseEntitiesUseCase(new AuthorizeUseCase(), wiring.query());
        var all = principal(ResourceScope.tenantWide());
        var seen = new HashSet<EntityId>(); EntityId after = null;
        do {
            var page = browse.execute(all, new EntityPageQuery("", null, "", after, 25));
            assertTrue(page.items().size() <= 25);
            for (var item : page.items()) assertTrue(seen.add(item.id()));
            after = page.nextCursor();
        } while (after != null);
        assertEquals(62, seen.size()); assertFalse(seen.contains(id(62)));
        var scoped = principal(ResourceScope.of(Set.of(ResourceRef.entity(tenant, id(59)), ResourceRef.entity(tenant, id(60)))));
        var scopedFirst = browse.execute(scoped, new EntityPageQuery("", null, "host", null, 1));
        assertEquals(id(59), scopedFirst.items().getFirst().id()); assertEquals(id(59), scopedFirst.nextCursor());
        var scopedNext = browse.execute(scoped, new EntityPageQuery("", null, "host", scopedFirst.nextCursor(), 1));
        assertEquals(id(60), scopedNext.items().getFirst().id()); assertNull(scopedNext.nextCursor());
        assertEquals(id(61), browse.execute(all, new EntityPageQuery("_%", Lifecycle.INACTIVE, "service", null, 25)).items().getFirst().id());
        assertEquals(id(23), browse.execute(all, new EntityPageQuery("hOsT 23", null, "", null, 25)).items().getFirst().id());
        assertEquals(id(23), browse.execute(all, new EntityPageQuery("10.0.0.23", null, "", null, 25)).items().getFirst().id());
        assertTrue(browse.execute(all, new EntityPageQuery("' OR 1=1 --", null, "", null, 25)).items().isEmpty());
    }
    @Test void oversizedAttributesFailClosedAndDoNotLeakThroughOtherScopes() {
        put(tenant, id(1), "oversized", "host", Lifecycle.ACTIVE, Map.of("large", "x".repeat(20000)));
        put(tenant, id(2), "visible", "host", Lifecycle.ACTIVE, Map.of());
        var browse = new BrowseEntitiesUseCase(new AuthorizeUseCase(), wiring.query());
        assertThrows(IllegalStateException.class, () -> browse.execute(principal(ResourceScope.tenantWide()), new EntityPageQuery("", null, "", null, 25)));
        assertThrows(IllegalStateException.class, () -> wiring.query().find(tenant, id(1)));
        var scoped = principal(ResourceScope.of(Set.of(ResourceRef.entity(tenant, id(2)))));
        assertEquals(id(2), browse.execute(scoped, new EntityPageQuery("", null, "", null, 25)).items().getFirst().id());
    }
}
