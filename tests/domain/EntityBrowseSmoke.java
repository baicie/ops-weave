import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.application.BrowseEntitiesUseCase;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

public class EntityBrowseSmoke {
    private static int checks;
    private static final TenantId TENANT = new TenantId("browse-tenant");
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static EntityId id(int n) { return EntityId.parse(String.format("%08x-0000-0000-0000-000000000001", n)); }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void fail(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + type.getSimpleName()); }
        catch (RuntimeException e) { check(type.isInstance(e)); }
    }
    private static Principal principal(ResourceScope scope) {
        return new Principal(new SubjectId("reader"), TENANT, Set.of(Permission.ENTITY_READ), scope);
    }
    private static EntityPageQuery query(String search, EntityId after, int limit) { return new EntityPageQuery(search, null, "", after, limit); }
    private static void put(InMemoryInventoryStore store, TenantId tenant, int n, String name, String type, Lifecycle lifecycle, Map<String,Object> attrs) {
        var key = new ExternalObjectKey(tenant, "source-1", type, "" + n, "1");
        store.upsert(new Entity(id(n), tenant, type, name, lifecycle, 1, NOW, attrs),
            new Observation(tenant.value() + n, key, id(n), NOW, NOW, attrs, "raw-" + n, 1), new ExternalLink(id(n), key));
    }
    public static void main(String[] args) {
        var store = new InMemoryInventoryStore(); var auth = new AuthorizeUseCase();
        for (int n = 1; n <= 30; n++) put(store, TENANT, n, "Host " + n, "host", Lifecycle.ACTIVE, Map.of("ip", "10.0.0." + n));
        put(store, TENANT, 31, "Host_% literal", "service", Lifecycle.INACTIVE, Map.of());
        put(store, new TenantId("foreign"), 32, "Foreign", "host", Lifecycle.ACTIVE, Map.of());
        var browse = new BrowseEntitiesUseCase(auth, store); var all = principal(ResourceScope.tenantWide());
        var first = browse.execute(all, query("", null, 25));
        check(first.items().size() == 25); check(first.nextCursor().equals(id(25)));
        var second = browse.execute(all, query("", first.nextCursor(), 25));
        check(second.items().size() == 6); check(second.nextCursor() == null); check(second.items().getFirst().id().equals(id(26)));
        check(browse.execute(all, query("HOST 12", null, 25)).items().size() == 1);
        check(browse.execute(all, query("10.0.0.12", null, 25)).items().getFirst().id().equals(id(12)));
        check(browse.execute(all, query("_%", null, 25)).items().getFirst().id().equals(id(31)));
        check(browse.execute(all, query("' OR 1=1 --", null, 25)).items().isEmpty());
        check(browse.execute(all, new EntityPageQuery("", Lifecycle.INACTIVE, "service", null, 25)).items().size() == 1);
        check(browse.execute(all, new EntityPageQuery("", Lifecycle.ACTIVE, "service", null, 25)).items().isEmpty());
        var scoped = principal(ResourceScope.of(Set.of(ResourceRef.entity(TENANT, id(29)), ResourceRef.entity(TENANT, id(30)), ResourceRef.entity(new TenantId("foreign"), id(32)))));
        var scopedFirst = browse.execute(scoped, query("", null, 1));
        check(scopedFirst.items().getFirst().id().equals(id(29))); check(scopedFirst.nextCursor().equals(id(29)));
        check(browse.execute(scoped, query("", id(1), 25)).items().size() == 2);
        check(browse.execute(principal(ResourceScope.of(Set.of(ResourceRef.source(TENANT, "source-1")))), query("", null, 25)).items().isEmpty());
        var denied = new Principal(all.subjectId(), TENANT, Set.of(), all.resourceScope());
        check(new BrowseEntitiesUseCase(auth, null).execute(denied, query("", null, 25)).forbidden());
        fail(IllegalArgumentException.class, () -> query("", null, 0));
        fail(IllegalArgumentException.class, () -> query("", null, 101));
        fail(IllegalArgumentException.class, () -> query("x".repeat(101), null, 25));
        fail(IllegalArgumentException.class, () -> query("x\ny", null, 25));
        fail(IllegalArgumentException.class, () -> new EntityPageQuery("", null, "../host", null, 25));
        InventoryQuery broken = new InventoryQuery() {
            public Optional<EntityView> find(TenantId tenant, EntityId entity) { throw new AssertionError(); }
            public List<EntityView> list(TenantId tenant) { throw new AssertionError(); }
            public List<EntityView> page(TenantId tenant, EntityVisibility visibility, EntityPageQuery query) {
                return store.list(new TenantId("foreign"));
            }
        };
        fail(IllegalStateException.class, () -> new BrowseEntitiesUseCase(auth, broken).execute(all, query("", null, 25)));
        put(store, TENANT, 33, "Oversized", "host", Lifecycle.ACTIVE, Map.of("payload", "x".repeat(17000)));
        fail(IllegalStateException.class, () -> browse.execute(all, query("Oversized", null, 25)));
        fail(IllegalStateException.class, () -> EntityReadLimits.check(Map.of("number", Double.NaN)));
        fail(IllegalStateException.class, () -> EntityReadLimits.check(Map.of("number", new java.math.BigInteger("9".repeat(100)))));
        check(browse.execute(scoped, query("", null, 25)).items().size() == 2);
        System.out.println("Entity browse smoke: " + checks + " checks passed");
    }
}
