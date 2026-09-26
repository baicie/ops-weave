import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.application.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

public class ObservationHistorySmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("observation-smoke");
    static final Instant NOW = Instant.parse("2026-09-25T00:00:10.987654321Z");
    static final ExternalObjectKey KEY = new ExternalObjectKey(TENANT, "zabbix-1", "host", "1", "1");
    static final EntityId ID = EntityIds.fromExternal(KEY);
    static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("Check " + checks); }
    static void fails(Runnable action) { try { action.run(); throw new AssertionError("Expected rejection"); } catch (RuntimeException expected) { checks++; } }
    static Observation observation(String id, Instant observed, Instant ingested, Map<String,Object> fields) { return new Observation(id, KEY, ID, observed, ingested, fields, "raw-" + id, 1); }
    static Entity entity(String name, Instant time) { return new Entity(ID, TENANT, "host", name, Lifecycle.ACTIVE, 1, time, Map.of()); }
    static ObservationQuery query(String after, int limit, Instant cutoff) { return new ObservationQuery(NOW.minusSeconds(60).getEpochSecond(), NOW.getEpochSecond(), cutoff, "", after, limit); }
    public static void main(String[] args) {
        var store = new InMemoryInventoryStore(); var link = new ExternalLink(ID, KEY);
        var nested = new ArrayList<Object>(); nested.add("original"); var fields = new HashMap<String,Object>(); fields.put("values", nested); fields.put("revision", 1);
        var a = observation("a", NOW.minusSeconds(2), NOW.minusSeconds(1), fields); nested.set(0, "changed"); fields.put("secret", true);
        check(((List<?>) a.fields().get("values")).getFirst().equals("original")); check(!a.fields().containsKey("secret"));
        fails(() -> ((List<Object>) a.fields().get("values")).add("mutation"));
        store.upsert(entity("new", a.observedAt()), a, link); store.upsert(entity("ignored duplicate", a.observedAt()), a, link);
        check(store.find(TENANT, ID).orElseThrow().version() == 1); check(store.find(TENANT, ID).orElseThrow().name().equals("new"));
        fails(() -> store.upsert(entity("bad", NOW), observation("a", NOW, NOW, Map.of()), link));
        check(store.observation(TENANT, "a").orElseThrow().equals(a));
        var older = observation("b", NOW.minusSeconds(20), NOW, Map.of()); store.upsert(entity("old", older.observedAt()), older, link);
        check(store.find(TENANT, ID).orElseThrow().name().equals("new")); check(store.find(TENANT, ID).orElseThrow().version() == 1);
        var c = observation("c", NOW.minusSeconds(1), NOW.plusSeconds(1), Map.of()); store.upsert(entity("later", c.observedAt()), c, link);
        var auth = new AuthorizeUseCase(); var get = new GetEntityUseCase(auth, store);
        var read = new ReadObservationsUseCase(get, store, Clock.fixed(NOW, ZoneOffset.UTC));
        var principal = new Principal(new SubjectId("reader"), TENANT, Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide());
        var first = read.execute(principal, ID, query(null, 1, NOW)); check(first.items().size() == 1); check(first.nextCursor().equals("a"));
        var second = read.execute(principal, ID, query(first.nextCursor(), 1, NOW)); check(second.items().getFirst().observation().equals(older)); check(second.nextCursor() == null);
        check(read.execute(principal, ID, query(null, 25, NOW.minusNanos(1))).items().size() == 1);
        check(read.execute(principal, ID, new ObservationQuery(NOW.minusSeconds(60).getEpochSecond(), NOW.getEpochSecond(), NOW, "other", null, 25)).items().isEmpty());
        var foreign = new Principal(principal.subjectId(), new TenantId("foreign"), principal.permissions(), ResourceScope.tenantWide());
        check(read.execute(foreign, ID, query(null, 25, NOW)).kind() == EntityAccessKind.MISSING);
        var denied = new Principal(principal.subjectId(), TENANT, Set.of(), ResourceScope.tenantWide());
        var never = new ReadObservationsUseCase(get, (tenant, id, q) -> { throw new AssertionError("Unauthorized storage read"); }, Clock.systemUTC());
        check(never.execute(denied, ID, query(null, 25, NOW)).kind() == EntityAccessKind.FORBIDDEN);
        var outOfScope = new Principal(principal.subjectId(), TENANT, principal.permissions(), ResourceScope.of(Set.of(ResourceRef.source(TENANT, "zabbix-1"))));
        check(never.execute(outOfScope, ID, query(null, 25, NOW)).kind() == EntityAccessKind.FORBIDDEN);
        fails(() -> query(null, 51, NOW)); fails(() -> query("../invalid", 25, NOW)); fails(() -> new ObservationQuery(0, NOW.getEpochSecond(), NOW, "", null, 25));
        fails(() -> read.execute(principal, ID, query(null, 25, NOW.plusSeconds(1))));
        var corrupted = new ReadObservationsUseCase(get, (tenant, id, q) -> List.of(new ObservationReader.Entry(c, true)), Clock.fixed(NOW, ZoneOffset.UTC));
        fails(() -> corrupted.execute(principal, ID, query(null, 25, NOW)));
        var otherKey = new ExternalObjectKey(TENANT, "zabbix-2", "host", "1", "1");
        fails(() -> store.upsert(entity("wrong-source", NOW), new Observation("d", otherKey, ID, NOW, NOW, Map.of(), "raw-d", 1), new ExternalLink(ID, otherKey)));
        check(store.observations().size() == 3);
        var foreignObservation = new Observation("a", new ExternalObjectKey(foreign.tenantId(), "zabbix-1", "host", "1", "1"), ID, NOW, NOW, Map.of(), "raw-a", 1);
        store.upsert(new Entity(ID, foreign.tenantId(), "host", "foreign", Lifecycle.ACTIVE, 1, NOW, Map.of()), foreignObservation, new ExternalLink(ID, foreignObservation.key()));
        check(store.observation(TENANT, "a").orElseThrow().equals(a)); check(store.observation(foreign.tenantId(), "a").orElseThrow().equals(foreignObservation));
        fails(() -> store.upsert(entity("foreign", NOW), foreignObservation, link));
        System.out.println("Observation history smoke: " + checks + " checks passed");
    }
}
