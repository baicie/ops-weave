import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.application.EntityRelationService;
import com.acme.opsweave.inventory.domain.RelationConfirmation;
import com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Multi-source confirmation merge: independent entries, isolated withdrawals, no silent overwrite. */
public final class RelationConfirmationSmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("relation-confirmation-smoke");
    static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
    static final Instant NOW = AT.plusSeconds(30);
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Relation confirmation check " + checks); }
    static void fails(Class<? extends Throwable> type, Runnable call) {
        checks++;
        try { call.run(); } catch (Throwable expected) { if (type.isInstance(expected)) return; throw new AssertionError("Unexpected confirmation failure", expected); }
        throw new AssertionError("Expected confirmation failure " + type.getSimpleName());
    }
    static UUID uuid(long value) { return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value)); }
    static EntityId entityId(long value) { return new EntityId(uuid(value)); }
    static AuthorizationService authorization() {
        return (principal, resource, permission) -> principal.has(permission) && principal.resourceScope().includes(resource)
            ? AuthorizationDecision.allow() : AuthorizationDecision.deny("SCOPE_DENIED");
    }
    static Principal principal(Set<Permission> permissions, ResourceScope scope) {
        return new Principal(new SubjectId("relation-confirmer"), TENANT, permissions, scope);
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
        var from = entityId(1);
        var to = entityId(2);
        var model = new RelationModelReader.Definition("builtin.depends_on", 1, "builtin.host", 1, "builtin.host", 1, "MANY_TO_MANY");
        var store = new InMemoryRelationStore();
        var service = service(Map.of(from, "Host", to, "Host"), store, model);
        var operator = principal(Set.of(Permission.ENTITY_MANAGE), ResourceScope.tenantWide());
        var reader = principal(Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide());
        var created = service.write(operator, uuid(1), model.id(), model.revision(), from, to, AT, null, "operator", "unknown", 1, 1);
        var relationId = created.relation().id();

        // Two sources and one manual assertion confirm the single relation record as separate entries.
        var first = service.confirm(operator, uuid(2), relationId, "source", "workflow.source-a", "batch-1", "fixture");
        check(!first.replayed() && "source".equals(first.entry().key().sourceKind()) && !first.entry().withdrawn());
        var second = service.confirm(operator, uuid(3), relationId, "source", "workflow.source-b", "batch-9", "import");
        var manual = service.confirm(operator, uuid(4), relationId, "manual", "operator", "assert-1", "unknown");
        check(service.confirmations(reader, relationId).entries().size() == 3);

        // Deterministic entry identity: cross-execution replays return the recorded entry and never double count.
        var replay = service.confirm(operator, uuid(5), relationId, "source", "workflow.source-a", "batch-1", "fixture");
        check(replay.replayed() && replay.entry().equals(first.entry()) && service.confirmations(reader, relationId).entries().size() == 3);
        var requestReplay = service.confirm(operator, uuid(2), relationId, "source", "workflow.source-a", "batch-1", "fixture");
        check(requestReplay.replayed());
        fails(RelationStore.ConfirmationConflict.class, () ->
            service.confirm(operator, uuid(2), relationId, "source", "workflow.source-b", "batch-9", "import"));
        fails(RelationStore.ConfirmationConflict.class, () ->
            service.confirm(operator, uuid(6), relationId, "source", "workflow.source-a", "batch-1", "import"));

        // Withdrawing one source only withdraws its own entry; the other confirmations and the manual assertion stand.
        var withdraw = service.withdraw(operator, uuid(7), relationId, "source", "workflow.source-a", "batch-1", "来源批次撤销");
        check(!withdraw.replayed() && withdraw.entry().withdrawn() && "来源批次撤销".equals(withdraw.entry().withdrawReason()));
        var view = service.confirmations(reader, relationId);
        check(view.relation().validTo() == null && view.entries().size() == 3);
        check(view.entries().stream().filter(entry -> entry.withdrawn()).count() == 1);
        check(view.entries().stream().anyMatch(entry -> entry.key().sourceKey().equals("workflow.source-b") && !entry.withdrawn()));
        check(view.entries().stream().anyMatch(entry -> entry.key().sourceKind().equals("manual") && !entry.withdrawn()));

        // Withdrawals are idempotent on the entry: the first recorded reason is never rewritten.
        var rewithdraw = service.withdraw(operator, uuid(8), relationId, "source", "workflow.source-a", "batch-1", "另一个理由");
        check(rewithdraw.replayed() && "来源批次撤销".equals(rewithdraw.entry().withdrawReason()));
        var requestReplayWithdraw = service.withdraw(operator, uuid(7), relationId, "source", "workflow.source-a", "batch-1", "来源批次撤销");
        check(requestReplayWithdraw.replayed());
        fails(RelationStore.ConfirmationConflict.class, () ->
            service.withdraw(operator, uuid(7), relationId, "source", "workflow.source-b", "batch-9", "同键异内容"));

        // Source failure never deletes relations or entries; unknown entries are explicit misses.
        check(service.confirmations(reader, relationId).entries().size() == 3);
        check(store.find(TENANT, relationId).orElseThrow().validTo() == null);
        fails(RelationStore.NotFound.class, () ->
            service.withdraw(operator, uuid(9), relationId, "source", "workflow.source-c", "batch-1", "不存在的确认"));

        // When the last still-confirmed entry is withdrawn the relation is terminated through the close
        // semantics with the withdrawal reason and its history kept.
        service.withdraw(operator, uuid(10), relationId, "source", "workflow.source-b", "batch-9", "来源下线");
        check(service.confirmations(reader, relationId).relation().validTo() == null);
        var last = service.withdraw(operator, uuid(11), relationId, "manual", "operator", "assert-1", "人工撤回断言");
        check(!last.replayed());
        var terminated = service.confirmations(reader, relationId);
        check(terminated.relation().validTo() != null && "人工撤回断言".equals(terminated.relation().closeReason()));
        check(terminated.relation().version() == 2 && terminated.entries().size() == 3);
        check(store.history(TENANT, from, null, 25).size() == 1);

        // A terminated relation accepts no new confirmation and stays unrecoverable.
        fails(RelationStore.ConfirmationConflict.class, () ->
            service.confirm(operator, uuid(12), relationId, "source", "workflow.source-a", "batch-2", "fixture"));

        // Authorization and field boundaries fail closed.
        var secondRelation = service.write(operator, uuid(20), model.id(), model.revision(), to, from, AT, null, "operator", "unknown", 1, 1);
        fails(RelationStore.Access.class, () ->
            service.confirm(reader, uuid(21), secondRelation.relation().id(), "source", "workflow.source-a", "batch-1", "fixture"));
        fails(RelationStore.Access.class, () ->
            service.withdraw(reader, uuid(22), secondRelation.relation().id(), "source", "workflow.source-a", "batch-1", "缺少管理授权"));
        fails(IllegalArgumentException.class, () ->
            service.confirm(operator, uuid(23), secondRelation.relation().id(), "vendor", "workflow.source-a", "batch-1", "fixture"));
        fails(IllegalArgumentException.class, () ->
            service.confirm(operator, uuid(23), secondRelation.relation().id(), "source", " ", "batch-1", "fixture"));
        fails(IllegalArgumentException.class, () ->
            service.withdraw(operator, uuid(23), secondRelation.relation().id(), "source", "workflow.source-a", "batch-1", "x".repeat(257)));
        fails(RelationStore.NotFound.class, () ->
            service.confirm(operator, uuid(24), uuid(999), "source", "workflow.source-a", "batch-1", "fixture"));

        // Per-relation entry capacity is enforced in the write path.
        for (int i = 0; i < RelationConfirmation.MAX_ENTRIES_PER_RELATION; i++)
            service.confirm(operator, uuid(100 + i), secondRelation.relation().id(), "source", "workflow.bulk", "batch-" + i, "unknown");
        check(service.confirmations(reader, secondRelation.relation().id()).entries().size() == RelationConfirmation.MAX_ENTRIES_PER_RELATION);
        fails(RelationStore.ConfirmationConflict.class, () ->
            service.confirm(operator, uuid(300), secondRelation.relation().id(), "source", "workflow.bulk", "batch-overflow", "unknown"));
        System.out.println("RelationConfirmationSmoke: " + checks + " checks passed");
    }
}
