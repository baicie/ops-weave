import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.application.EntityRelationService;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

public final class EntityRelationSmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("relation-smoke");
    static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Relation check " + checks); }
    static void fails(Class<? extends Throwable> type, Runnable call) {
        checks++;
        try { call.run(); } catch (Throwable expected) { if (type.isInstance(expected)) return; throw new AssertionError("Unexpected relation failure", expected); }
        throw new AssertionError("Expected relation failure " + type.getSimpleName());
    }
    static UUID uuid(long value) { return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value)); }
    static EntityId entityId(long value) { return new EntityId(uuid(value)); }
    static EntityRelation relation(long id, EntityId from, EntityId to) {
        return new EntityRelation(uuid(id), TENANT, from, "builtin.has_interface", 1, to, AT, null, "operator", "unknown", 1);
    }
    static AuthorizationService authorization() {
        return (principal, resource, permission) -> principal.has(permission) && principal.resourceScope().includes(resource)
            ? AuthorizationDecision.allow() : AuthorizationDecision.deny("SCOPE_DENIED");
    }
    static Principal principal(Set<Permission> permissions, ResourceScope scope) {
        return new Principal(new SubjectId("relation-operator"), TENANT, permissions, scope);
    }
    static InventoryQuery query(Map<EntityId, String> types) {
        return new InventoryQuery() {
            @Override public Optional<EntityView> find(TenantId tenant, EntityId id) {
                String type = types.get(id);
                return type == null ? Optional.empty() : Optional.of(new EntityView(id, tenant, type, "entity", "ACTIVE", 1, Map.of()));
            }
            @Override public List<EntityView> list(TenantId tenant) { return List.of(); }
        };
    }
    static final class RelationRows implements RelationStore {
        private final List<EntityRelation> rows;
        int pages;
        RelationRows(List<EntityRelation> rows) { this.rows = List.copyOf(rows); }
        @Override public WriteResult write(TenantId tenant, UUID requestId, EntityRelation relation, long expectedFromVersion, long expectedToVersion) {
            throw new UnsupportedOperationException();
        }
        @Override public List<EntityRelation> page(TenantId tenant, EntityId endpoint, UUID after, Instant asOf, int limit) {
            pages++;
            return rows.stream().filter(r -> r.tenantId().equals(tenant) && (r.fromEntityId().equals(endpoint) || r.toEntityId().equals(endpoint)))
                .filter(r -> !r.validFrom().isAfter(asOf) && (r.validTo() == null || r.validTo().isAfter(asOf)))
                .filter(r -> after == null || r.id().toString().compareTo(after.toString()) > 0)
                .sorted(Comparator.comparing(r -> r.id().toString())).limit(limit + 1L).toList();
        }
    }
    static EntityRelationService readService(EntityId endpoint, RelationRows store) {
        return new EntityRelationService(authorization(), query(Map.of(endpoint, "Host")), store,
            (tenant, id, revision) -> Optional.empty(), java.time.Clock.fixed(AT, ZoneOffset.UTC));
    }
    static ResourceScope visibleScope(EntityId endpoint, List<EntityRelation> rows, Set<UUID> visibleRelations) {
        var refs = new HashSet<ResourceRef>(); refs.add(ResourceRef.entity(TENANT, endpoint));
        for (var row : rows) if (visibleRelations.contains(row.id())) refs.add(ResourceRef.entity(TENANT, row.toEntityId()));
        return ResourceScope.of(refs);
    }

    public static void main(String[] args) {
        var tenant = TENANT;
        var from = entityId(9001);
        var to = entityId(9002);
        var request = uuid(1);
        var relation = new EntityRelation(request, tenant, from, "builtin.depends_on", 1, to, AT, null, "operator", "unknown", 1);
        var store = new InMemoryRelationStore();
        var first = store.write(tenant, request, relation, 1, 1);
        check(!first.replayed() && first.relation().id().equals(request));
        var replay = store.write(tenant, request, relation, 1, 1);
        check(replay.replayed() && replay.relation().equals(relation));
        var changed = new EntityRelation(request, tenant, to, "builtin.depends_on", 1, from, AT, null, "operator", "unknown", 1);
        fails(RelationStore.Conflict.class, () -> store.write(tenant, request, changed, 1, 1));
        check(store.page(tenant, from, null, AT, 25).size() == 1);
        fails(IllegalArgumentException.class, () -> new EntityRelation(UUID.randomUUID(), tenant, from, "builtin.depends_on", 1, from, AT, null, "operator", "unknown", 1));
        fails(IllegalArgumentException.class, () -> new EntityRelation(UUID.randomUUID(), tenant, from, "builtin.depends_on", 1, to, AT, AT, "operator", "unknown", 1));

        verifyBuiltinEndpointType("NetworkInterface");
        verifyBuiltinEndpointType("network_interface");
        verifyVisiblePagination();
        verifyScanBudgetFailure();
        System.out.println("EntityRelationSmoke: " + checks + " checks passed");
    }

    static void verifyBuiltinEndpointType(String actualType) {
        var from = entityId(9101); var to = entityId(actualType.equals("NetworkInterface") ? 9102 : 9103);
        var query = query(Map.of(from, "Host", to, actualType)); var writes = new InMemoryRelationStore();
        var model = new RelationModelReader.Definition("builtin.has_interface", 1, "builtin.host", 1, "builtin.network_interface", 1, "ONE_TO_MANY");
        var service = new EntityRelationService(authorization(), query, writes, (tenant, id, revision) -> Optional.of(model), java.time.Clock.fixed(AT, ZoneOffset.UTC));
        var operator = principal(Set.of(Permission.ENTITY_MANAGE), ResourceScope.tenantWide());
        var result = service.write(operator, UUID.randomUUID(), model.id(), model.revision(), from, to, AT, null, "operator", "unknown", 1, 1);
        check(result.relation().relationType().equals("builtin.has_interface"));
        check(model.toType().equals("builtin.network_interface"));
    }

    static void verifyVisiblePagination() {
        var endpoint = entityId(9200); var rows = new ArrayList<EntityRelation>();
        for (int i = 1; i <= 12; i++) rows.add(relation(i, endpoint, entityId(10000 + i)));
        var visibleIds = Set.of(uuid(2), uuid(4), uuid(6), uuid(8), uuid(10));
        var reader = principal(Set.of(Permission.ENTITY_READ), visibleScope(endpoint, rows, visibleIds));
        var store = new RelationRows(rows); var service = readService(endpoint, store);
        var firstPage = service.page(reader, endpoint, null, AT, 2);
        check(firstPage.size() == 3);
        check(firstPage.get(0).id().equals(uuid(2)) && firstPage.get(1).id().equals(uuid(4)) && firstPage.get(2).id().equals(uuid(6)));
        var nextCursor = firstPage.get(1).id();
        check(nextCursor.equals(uuid(4)) && visibleIds.contains(nextCursor));
        var secondPage = service.page(reader, endpoint, nextCursor, AT, 2);
        check(secondPage.size() == 3 && secondPage.get(0).id().equals(uuid(6)) && secondPage.get(1).id().equals(uuid(8)) && secondPage.get(2).id().equals(uuid(10)));

        var endingRows = new ArrayList<EntityRelation>();
        for (int i = 1; i <= 9; i++) endingRows.add(relation(i, endpoint, entityId(11000 + i)));
        var endingVisible = Set.of(uuid(2), uuid(4));
        var endingReader = principal(Set.of(Permission.ENTITY_READ), visibleScope(endpoint, endingRows, endingVisible));
        var endingPage = readService(endpoint, new RelationRows(endingRows)).page(endingReader, endpoint, null, AT, 2);
        check(endingPage.size() == 2 && endingPage.stream().allMatch(row -> endingVisible.contains(row.id())));
        var noRead = principal(Set.of(), ResourceScope.tenantWide());
        fails(RelationStore.Access.class, () -> service.page(noRead, endpoint, null, AT, 2));
    }

    static void verifyScanBudgetFailure() {
        var endpoint = entityId(9300); var rows = new ArrayList<EntityRelation>();
        for (int i = 1; i <= 1001; i++) rows.add(relation(i, endpoint, entityId(20000 + i)));
        var reader = principal(Set.of(Permission.ENTITY_READ), ResourceScope.of(Set.of(ResourceRef.entity(TENANT, endpoint))));
        var store = new RelationRows(rows); var service = readService(endpoint, store);
        fails(RelationStore.ScanBudgetExceeded.class, () -> service.page(reader, endpoint, null, AT, 25));
        check(store.pages == 20);
    }
}
