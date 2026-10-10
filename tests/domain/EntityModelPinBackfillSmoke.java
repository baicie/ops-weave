import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.catalog.domain.ModelPreview;
import com.acme.opsweave.inventory.api.EntityModelPinBackfillStore;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryEntityModelPinBackfillStore;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

/** Explicit legacy model-pin backfill: verifiable matches only, no guessing, idempotent receipts. */
public final class EntityModelPinBackfillSmoke {
    static int checks;
    static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    static final ModelDefinition MODEL = new ModelDefinition("custom.widget", 1, ModelDefinition.Kind.ENTITY, "Widget", "explicit fixture",
        List.of(new ModelDefinition.Field("name", "Name", ModelDefinition.Type.TEXT, true, 255, null, null, List.of())), null);
    static final EntityModelPin PIN = new EntityModelPin(MODEL.id(), MODEL.revision(), MODEL.digest());

    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Backfill check " + checks); }
    static void rejects(Class<? extends RuntimeException> type, Runnable call) {
        checks++;
        try { call.run(); } catch (RuntimeException expected) { if (type.isInstance(expected)) return; throw new AssertionError("Wrong rejection " + checks, expected); }
        throw new AssertionError("Expected rejection " + checks);
    }
    static EntityId id(int n) { return EntityId.parse(String.format("%08x-0000-0000-0000-000000000001", n)); }
    static Entity entity(TenantId tenant, int n, String type, Map<String, Object> attributes) {
        return new Entity(id(n), tenant, type, "Row " + n, Lifecycle.ACTIVE, 1, NOW, attributes);
    }
    static EntityModelPinBackfillStore.Conformance conformance() {
        return attributes -> {
            try { return ModelPreview.evaluate(MODEL, attributes).valid(); }
            catch (RuntimeException indeterminable) { return false; }
        };
    }
    static void counts(EntityModelPinBackfillStore.Receipt receipt, int scanned, int updated, int kept, int pinned, int source) {
        check(receipt.counts().equals(new EntityModelPinBackfillStore.Counts(scanned, updated, kept, pinned, source)));
    }

    public static void main(String[] args) {
        var store = new InMemoryInventoryStore();
        var backfill = new InMemoryEntityModelPinBackfillStore(store);

        // Scenario A: verifiable matches only; pinned and source-managed rows stay untouched.
        var a = new TenantId("backfill-a");
        store.write(a, UUID.randomUUID(), entity(a, 1, "Widget", Map.of("name", "W1")), (Long) null);
        store.write(a, UUID.randomUUID(), entity(a, 2, "Widget", Map.of("name", "W2")), (Long) null);
        store.write(a, UUID.randomUUID(), entity(a, 3, "Widget", Map.of("name", "W3", "extra", "unlisted")), (Long) null);
        store.write(a, UUID.randomUUID(), entity(a, 4, "Widget", Map.of("name", "W4")), (Long) null, PIN.id(), PIN.revision(), PIN.digest());
        var key = new ExternalObjectKey(a, "fixture-source", "widget", "ext-5", "1");
        store.upsert(entity(a, 5, "Widget", Map.of("name", "W5")),
            new Observation("seed-5", key, id(5), NOW, NOW, Map.of("name", "W5"), "raw-5", 1), new ExternalLink(id(5), key));

        var request = UUID.randomUUID();
        var receipt = backfill.backfill(a, request, "Widget", PIN, 50, conformance(), NOW);
        check(!receipt.replayed());
        counts(receipt, 3, 2, 1, 1, 1);
        check(receipt.requestId().equals(request) && receipt.entityType().equals("Widget") && receipt.model().equals(PIN));
        check(store.find(a, id(1)).orElseThrow().model().equals(PIN));
        check(store.find(a, id(2)).orElseThrow().model().equals(PIN));
        check(store.find(a, id(3)).orElseThrow().model() == null);
        check(store.find(a, id(4)).orElseThrow().model().equals(PIN));
        check(store.find(a, id(5)).orElseThrow().model() == null);
        // The pin is validation metadata: object versions are untouched by the backfill.
        check(store.find(a, id(1)).orElseThrow().version() == 1);

        // requestId replays the stored receipt; a later row is not silently rewritten by the replay.
        store.write(a, UUID.randomUUID(), entity(a, 6, "Widget", Map.of("name", "W6")), (Long) null);
        var replay = backfill.backfill(a, request, "Widget", PIN, 50, conformance(), NOW.plusSeconds(60));
        check(replay.replayed());
        counts(replay, 3, 2, 1, 1, 1);
        check(replay.completedAt().equals(receipt.completedAt()));
        check(store.find(a, id(6)).orElseThrow().model() == null);
        var stored = backfill.receipt(a, request).orElseThrow();
        check(!stored.replayed() && stored.counts().equals(receipt.counts()));
        check(backfill.receipt(a, UUID.randomUUID()).isEmpty());
        check(backfill.receipt(new TenantId("backfill-b"), request).isEmpty());

        // The same requestId with changed content conflicts instead of re-running.
        rejects(EntityModelPinBackfillStore.Conflict.class, () -> backfill.backfill(a, request, "Widget", PIN, 25, conformance(), NOW));
        rejects(EntityModelPinBackfillStore.Conflict.class, () -> backfill.backfill(a, request, "Other", PIN, 50, conformance(), NOW));
        rejects(EntityModelPinBackfillStore.Conflict.class, () -> backfill.backfill(a, request, "Widget",
            new EntityModelPin(PIN.id(), 2, "sha256:" + "b".repeat(64)), 50, conformance(), NOW));
        check(store.find(a, id(3)).orElseThrow().model() == null && store.find(a, id(6)).orElseThrow().model() == null);

        // maxUpdates is a hard budget over unpinned non-source rows in deterministic id order.
        var bounded = backfill.backfill(a, UUID.randomUUID(), "Widget", PIN, 1, conformance(), NOW);
        counts(bounded, 1, 0, 1, 3, 1);
        check(store.find(a, id(3)).orElseThrow().model() == null);
        check(store.find(a, id(6)).orElseThrow().model() == null);
        var remainder = backfill.backfill(a, UUID.randomUUID(), "Widget", PIN, 2, conformance(), NOW);
        counts(remainder, 2, 1, 1, 3, 1);
        check(store.find(a, id(6)).orElseThrow().model().equals(PIN));

        // Indeterminable conformance never guesses: throwing checks keep rows without a pin.
        store.write(a, UUID.randomUUID(), entity(a, 7, "Widget", Map.of("name", "W7")), (Long) null);
        var throwing = backfill.backfill(a, UUID.randomUUID(), "Widget", PIN, 50, attributes -> { throw new IllegalStateException("unreadable"); }, NOW);
        counts(throwing, 2, 0, 2, 4, 1);
        check(store.find(a, id(3)).orElseThrow().model() == null && store.find(a, id(7)).orElseThrow().model() == null);

        // Command bounds are server-side too.
        rejects(IllegalArgumentException.class, () -> backfill.backfill(a, UUID.randomUUID(), "Widget", PIN, 0, conformance(), NOW));
        rejects(IllegalArgumentException.class, () -> backfill.backfill(a, UUID.randomUUID(), "Widget", PIN, 501, conformance(), NOW));
        rejects(IllegalArgumentException.class, () -> backfill.backfill(a, UUID.randomUUID(), "", PIN, 50, conformance(), NOW));
        rejects(IllegalArgumentException.class, () -> backfill.backfill(a, UUID.randomUUID(), "widget!", PIN, 50, conformance(), NOW));

        // Entity type filtering and tenant isolation: only the named type in the trusted tenant moves.
        var b = new TenantId("backfill-b");
        store.write(b, UUID.randomUUID(), entity(b, 1, "Widget", Map.of("name", "B1")), (Long) null);
        store.write(b, UUID.randomUUID(), entity(b, 2, "Other", Map.of("name", "B2")), (Long) null);
        var scoped = backfill.backfill(b, UUID.randomUUID(), "Widget", PIN, 50, conformance(), NOW);
        counts(scoped, 1, 1, 0, 0, 0);
        check(store.find(b, id(1)).orElseThrow().model().equals(PIN));
        check(store.find(b, id(2)).orElseThrow().model() == null);
        check(store.find(a, id(7)).orElseThrow().model() == null);
        check(receipt.counts().equals(new EntityModelPinBackfillStore.Counts(3, 2, 1, 1, 1)));

        System.out.println("EntityModelPinBackfillSmoke: " + checks + " checks passed");
    }
}
