import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.application.EntityRelationService;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.RelationCardinality;
import com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Explicit relation correction: kept history, CAS, idempotency and conflict visibility. */
public final class RelationCorrectionSmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("relation-correction-smoke");
    static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
    static final Instant NOW = AT.plusSeconds(30);
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Relation correction check " + checks); }
    static void fails(Class<? extends Throwable> type, Runnable call) {
        checks++;
        try { call.run(); } catch (Throwable expected) { if (type.isInstance(expected)) return; throw new AssertionError("Unexpected correction failure", expected); }
        throw new AssertionError("Expected correction failure " + type.getSimpleName());
    }
    static UUID uuid(long value) { return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value)); }
    static EntityId entityId(long value) { return new EntityId(uuid(value)); }
    static AuthorizationService authorization() {
        return (principal, resource, permission) -> principal.has(permission) && principal.resourceScope().includes(resource)
            ? AuthorizationDecision.allow() : AuthorizationDecision.deny("SCOPE_DENIED");
    }
    static Principal principal(Set<Permission> permissions, ResourceScope scope) {
        return new Principal(new SubjectId("relation-corrector"), TENANT, permissions, scope);
    }
    static InventoryQuery query(Map<EntityId, String> types) {
        return new InventoryQuery() {
            @Override public Optional<InventoryQuery.EntityView> find(TenantId tenant, EntityId id) {
                String type = types.get(id);
                return type == null ? Optional.empty() : Optional.of(new InventoryQuery.EntityView(id, tenant, type, "entity", "ACTIVE", 1, Map.of()));
            }
            @Override public List<InventoryQuery.EntityView> list(TenantId tenant) { return List.of(); }
        };
    }
    static EntityRelationService service(Map<EntityId, String> types, RelationStore store, RelationModelReader.Definition model) {
        return new EntityRelationService(authorization(), query(types), store,
            (tenant, id, revision) -> model.id().equals(id) && model.revision() == revision ? Optional.of(model) : Optional.empty(),
            java.time.Clock.fixed(NOW, ZoneOffset.UTC));
    }

    public static void main(String[] args) {
        var hub = entityId(1);
        var first = entityId(2);
        var second = entityId(3);
        var host = entityId(4);
        var model = new RelationModelReader.Definition("builtin.has_interface", 1, "builtin.host", 1, "builtin.network_interface", 1, "ONE_TO_MANY");
        var store = new InMemoryRelationStore();
        var service = service(Map.of(hub, "Host", first, "NetworkInterface", second, "NetworkInterface", host, "Host"), store, model);
        var operator = principal(Set.of(Permission.ENTITY_MANAGE), ResourceScope.tenantWide());
        var reader = principal(Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide());

        var created = service.write(operator, uuid(1), model.id(), model.revision(), hub, first, AT, null, "operator", "unknown", 1, 1);
        var original = created.relation();

        // The correction establishes a new record and keeps the original row, its endpoints and reason history.
        var corrected = service.correct(operator, uuid(2), original.id(), "接口录入错误", 1, hub, second, model.id(), model.revision());
        check(!corrected.replayed() && !corrected.relation().id().equals(original.id()));
        check(corrected.relation().fromEntityId().equals(hub) && corrected.relation().toEntityId().equals(second));
        check(corrected.relation().validFrom().equals(NOW) && corrected.relation().validTo() == null && corrected.relation().version() == 1);
        var kept = store.find(TENANT, original.id()).orElseThrow();
        check(kept.validTo() != null && kept.validTo().equals(NOW) && "接口录入错误".equals(kept.closeReason()));
        check(kept.version() == 2 && kept.fromEntityId().equals(hub) && kept.toEntityId().equals(first) && "operator".equals(kept.sourceRef()));
        check(store.history(TENANT, hub, null, 25).size() == 2);

        // Idempotent replay returns the original receipt; changed content under the same key conflicts.
        var replay = service.correct(operator, uuid(2), original.id(), "接口录入错误", 1, hub, second, model.id(), model.revision());
        check(replay.replayed() && replay.relation().id().equals(corrected.relation().id()));
        fails(RelationStore.CorrectionConflict.class, () ->
            service.correct(operator, uuid(2), original.id(), "改动后的理由", 1, hub, second, model.id(), model.revision()));

        // CAS, no revival, and no silent no-op correction.
        fails(RelationStore.CorrectionConflict.class, () ->
            service.correct(operator, uuid(3), original.id(), "终止后不复活", 1, hub, host, model.id(), model.revision()));
        fails(IllegalArgumentException.class, () ->
            service.correct(operator, uuid(3), corrected.relation().id(), "无变化", 1, hub, second, model.id(), model.revision()));

        // The corrected-away row keeps its uniqueness slot: re-creating it still conflicts.
        var resurrect = uuid(4);
        fails(RelationStore.Conflict.class, () ->
            service.write(operator, resurrect, model.id(), model.revision(), hub, first, NOW, null, "operator", "unknown", 1, 1));

        // Model, endpoint type, cardinality, reason and authorization boundaries fail closed.
        fails(RelationStore.CorrectionConflict.class, () ->
            service.correct(operator, uuid(5), corrected.relation().id(), "未发布模型", 1, hub, first, "custom.missing", 1));
        fails(RelationStore.CorrectionConflict.class, () ->
            service.correct(operator, uuid(5), corrected.relation().id(), "端点类型不符", 1, hub, host, model.id(), model.revision()));
        fails(RelationStore.NotFound.class, () ->
            service.correct(operator, uuid(5), uuid(999), "不存在", 1, hub, first, model.id(), model.revision()));
        fails(RelationStore.Access.class, () ->
            service.correct(reader, uuid(6), corrected.relation().id(), "缺少管理授权", 1, hub, first, model.id(), model.revision()));
        fails(IllegalArgumentException.class, () ->
            service.correct(operator, uuid(6), corrected.relation().id(), " ", 1, hub, first, model.id(), model.revision()));
        fails(IllegalArgumentException.class, () ->
            service.correct(operator, uuid(6), corrected.relation().id(), "x".repeat(257), 1, hub, first, model.id(), model.revision()));
        fails(IllegalArgumentException.class, () ->
            service.correct(operator, uuid(6), corrected.relation().id(), "bad\nreason", 1, hub, first, model.id(), model.revision()));

        // The corrected value may not collide with another recorded slot.
        var otherHub = entityId(6);
        var third = entityId(5);
        var freeModel = new RelationModelReader.Definition("builtin.has_interface", 1, "builtin.host", 1, "builtin.network_interface", 1, "MANY_TO_MANY");
        var freeStore = new InMemoryRelationStore();
        var free = service(Map.of(otherHub, "Host", first, "NetworkInterface", second, "NetworkInterface", third, "NetworkInterface"), freeStore, freeModel);
        var pair = free.write(operator, uuid(7), freeModel.id(), freeModel.revision(), otherHub, first, AT, null, "operator", "unknown", 1, 1);
        free.write(operator, uuid(8), freeModel.id(), freeModel.revision(), otherHub, third, AT, null, "operator", "unknown", 1, 1);
        fails(RelationStore.CorrectionConflict.class, () ->
            free.correct(operator, uuid(9), pair.relation().id(), "版本失配", 7, otherHub, second, freeModel.id(), freeModel.revision()));
        fails(RelationStore.CorrectionConflict.class, () ->
            free.correct(operator, uuid(9), pair.relation().id(), "撞已有记录", 1, otherHub, third, freeModel.id(), freeModel.revision()));

        // Cardinality: the corrected-away row releases its slot inside the same write.
        var oneToOne = new RelationModelReader.Definition("builtin.deployed_on", 1, "builtin.host", 1, "builtin.network_interface", 1, "ONE_TO_ONE");
        var strictStore = new InMemoryRelationStore();
        var strict = service(Map.of(hub, "Host", first, "NetworkInterface", second, "NetworkInterface"), strictStore, oneToOne);
        var deployed = strict.write(operator, uuid(10), oneToOne.id(), oneToOne.revision(), hub, first, AT, null, "operator", "unknown", 1, 1);
        var moved = strict.correct(operator, uuid(11), deployed.relation().id(), "端点更正", 1, hub, second, oneToOne.id(), oneToOne.revision());
        check(moved.relation().toEntityId().equals(second) && strictStore.find(TENANT, deployed.relation().id()).orElseThrow().validTo() != null);

        // Correction records are readable and scoped to the involved endpoints.
        var records = service.corrections(reader, hub, null, 25);
        check(records.size() == 1);
        check(records.get(0).previousRelationId().equals(original.id()) && records.get(0).relationId().equals(corrected.relation().id()));
        check("接口录入错误".equals(records.get(0).reason()) && records.get(0).expectedVersion() == 1);
        check(service.corrections(reader, host, null, 25).isEmpty());
        fails(RelationStore.NotFound.class, () -> service.corrections(reader, entityId(999), null, 25));
        System.out.println("RelationCorrectionSmoke: " + checks + " checks passed");
    }
}
