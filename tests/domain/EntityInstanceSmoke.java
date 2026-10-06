import com.acme.opsweave.inventory.api.EntityInstanceStore;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.EntityModelPin;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class EntityInstanceSmoke {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Entity instance check " + checks); }
    public static void main(String[] args) {
        var tenant = new TenantId("entity-instance-smoke");
        var foreign = new TenantId("entity-instance-foreign");
        var id = new EntityId(UUID.randomUUID());
        var request = UUID.randomUUID();
        var at = Instant.parse("2026-10-06T00:00:00Z");
        var store = new InMemoryInventoryStore();
        var first = new Entity(id, tenant, "Host", "edge-01", Lifecycle.ACTIVE, 1, at, Map.of("address", "10.0.0.1"));
        check(!store.write(tenant, request, first, null).replayed());
        check(store.write(tenant, request, first, null).replayed());
        var updated = new Entity(id, tenant, "Host", "edge-02", Lifecycle.ACTIVE, 2, at.plusSeconds(1), Map.of("address", "10.0.0.2"));
        check(!store.write(tenant, UUID.randomUUID(), updated, 1L).replayed());
        check(store.find(tenant, id).orElseThrow().version() == 2);
        var pin = new EntityModelPin("custom.host_extension", 3, "sha256:" + "b".repeat(64));
        var pinnedId = new EntityId(UUID.randomUUID()); var pinnedRequest = UUID.randomUUID();
        var pinned = new Entity(pinnedId, tenant, "custom.host_extension", "pinned", Lifecycle.ACTIVE, 1, at, Map.of("code", "x"));
        var pinnedReceipt = store.write(tenant, pinnedRequest, pinned, null, pin.id(), pin.revision(), pin.digest());
        check(pinnedReceipt.entity().model().equals(pin));
        check(store.find(tenant, pinnedId).orElseThrow().model().equals(pin));
        try { store.write(foreign, UUID.randomUUID(), first, null); throw new AssertionError("tenant mismatch accepted"); }
        catch (EntityInstanceStore.Conflict expected) { checks++; }
        try { store.write(tenant, UUID.randomUUID(), updated, 1L); throw new AssertionError("stale version accepted"); }
        catch (EntityInstanceStore.Conflict expected) { checks++; }
        try { com.acme.opsweave.inventory.domain.EntityReadLimits.checkForWrite(Map.of("payload", "x".repeat(3_000))); throw new AssertionError("oversized instance accepted"); }
        catch (IllegalStateException expected) { checks++; }
        System.out.println("EntityInstanceSmoke: " + checks + " checks passed");
    }
}
