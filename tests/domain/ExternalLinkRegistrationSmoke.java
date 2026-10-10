import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.WorkflowRelationOutput;
import com.acme.opsweave.inventory.api.ExternalLinkRegistrationStore;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.application.EntityRelationService;
import com.acme.opsweave.inventory.application.ExternalLinkRegistrationService;
import com.acme.opsweave.inventory.domain.EntityModelPin;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore;
import com.acme.opsweave.inventory.infrastructure.InMemoryExternalLinkRegistrationStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

public final class ExternalLinkRegistrationSmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("external-link-smoke");
    static final Instant AT = Instant.parse("2026-10-10T00:00:00Z");
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("External link check " + checks); }
    static void fails(Class<? extends Throwable> type, Runnable call) {
        checks++;
        try { call.run(); } catch (Throwable expected) { if (type.isInstance(expected)) return; throw new AssertionError("Unexpected external link failure", expected); }
        throw new AssertionError("Expected external link failure " + type.getSimpleName());
    }
    static EntityId entityId(long value) { return new EntityId(UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value))); }
    static AuthorizationService authorization() {
        return (principal, resource, permission) -> principal.has(permission) && principal.resourceScope().includes(resource)
            ? AuthorizationDecision.allow() : AuthorizationDecision.deny("SCOPE_DENIED");
    }
    static Principal operator(Set<Permission> permissions) {
        return new Principal(new SubjectId("link-operator"), TENANT, permissions, ResourceScope.tenantWide());
    }
    static final EntityModelPin HOST_MODEL = new EntityModelPin("builtin.host", 1, "sha256:" + "1".repeat(64));
    static InventoryQuery query(Map<EntityId, InventoryQuery.EntityView> entities) {
        return new InventoryQuery() {
            @Override public Optional<InventoryQuery.EntityView> find(TenantId tenant, EntityId id) {
                var v = entities.get(id);
                return v == null ? Optional.empty() : Optional.of(v);
            }
            @Override public List<InventoryQuery.EntityView> list(TenantId tenant) { return List.copyOf(entities.values()); }
        };
    }

    public static void main(String[] args) {
        var host = entityId(21);
        var service = entityId(22);
        var inventory = new InMemoryInventoryStore();
        var store = new InMemoryExternalLinkRegistrationStore(inventory);
        var entities = new HashMap<EntityId, InventoryQuery.EntityView>();
        entities.put(host, new InventoryQuery.EntityView(host, TENANT, "Host", "h1", "ACTIVE", 1, Map.of(), HOST_MODEL));
        entities.put(service, new InventoryQuery.EntityView(service, TENANT, "Service", "s1", "ACTIVE", 1, Map.of(),
            new EntityModelPin("builtin.service", 1, "sha256:" + "2".repeat(64))));
        var query = query(entities);
        var service1 = new ExternalLinkRegistrationService(authorization(), query, store, java.time.Clock.fixed(AT, ZoneOffset.UTC));
        var p = operator(Set.of(Permission.ENTITY_MANAGE, Permission.ENTITY_READ));

        // 1. Deterministic registration binds an explicit key to an existing entity.
        var first = service1.register(p, UUID.randomUUID(), "workflow.demo", "entity", "host-1", "builtin.host", host, "来源身份确认");
        check(!first.replayed());
        check(inventory.linkOf(new ExternalObjectKey(TENANT, "workflow.demo", "entity", "host-1", "builtin.host")).orElseThrow().entityId().equals(host));

        // 2. Same requestId and content replays the original receipt.
        var replayId = UUID.randomUUID();
        var reg = service1.register(p, replayId, "workflow.demo", "entity", "svc-1", "builtin.service", service, null);
        var replay = service1.register(p, replayId, "workflow.demo", "entity", "svc-1", "builtin.service", service, null);
        check(!reg.replayed() && replay.replayed());
        check(replay.entry().registration().requestId().equals(replayId));

        // 3. Same requestId with changed content conflicts.
        fails(ExternalLinkRegistrationStore.Conflict.class, () ->
            service1.register(p, replayId, "workflow.demo", "entity", "svc-1", "builtin.service", host, null));

        // 4. A key already bound to another entity is never silently rebound.
        fails(ExternalLinkRegistrationStore.Conflict.class, () ->
            service1.register(p, UUID.randomUUID(), "workflow.demo", "entity", "host-1", "builtin.host", service, "改绑"));
        check(inventory.linkOf(new ExternalObjectKey(TENANT, "workflow.demo", "entity", "host-1", "builtin.host")).orElseThrow().entityId().equals(host));

        // 5. Unknown target entity is not found; nothing is guessed.
        fails(ExternalLinkRegistrationStore.NotFound.class, () ->
            service1.register(p, UUID.randomUUID(), "workflow.demo", "entity", "ghost", "builtin.host", entityId(99), null));

        // 6. Closed-set namespace/object type and bounded key validation.
        fails(IllegalArgumentException.class, () -> service1.register(p, UUID.randomUUID(), "Workflow.Bogus", "entity", "k", "builtin.host", host, null));
        fails(IllegalArgumentException.class, () -> service1.register(p, UUID.randomUUID(), "workflow.demo", "Entity Type", "k", "builtin.host", host, null));
        fails(IllegalArgumentException.class, () -> service1.register(p, UUID.randomUUID(), "workflow.demo", "entity", " ", "builtin.host", host, null));
        fails(IllegalArgumentException.class, () -> service1.register(p, UUID.randomUUID(), "workflow.demo", "entity", "k", "builtin.host", host, "x".repeat(257)));

        // 7. Generation is derived from the target's pinned model when not asserted.
        var derived = service1.register(p, UUID.randomUUID(), "workflow.demo", "entity", "host-derived", null, host, null);
        check(derived.entry().registration().key().generation().equals(HOST_MODEL.id()));

        // 8. entity.manage is required to bind; entity.read to list.
        var readOnly = operator(Set.of(Permission.ENTITY_READ));
        fails(ExternalLinkRegistrationStore.Access.class, () ->
            service1.register(readOnly, UUID.randomUUID(), "workflow.demo", "entity", "nope", "builtin.host", host, null));
        var noRead = operator(Set.of(Permission.ENTITY_MANAGE));
        fails(ExternalLinkRegistrationStore.Access.class, () -> service1.page(noRead, null, null, 25));

        // 9. Authorized read returns only visible rows, bounded.
        var listed = service1.page(p, null, null, 25);
        check(listed.size() >= 3);
        check(listed.stream().allMatch(e -> e.registration().key().tenantId().equals(TENANT)));

        // 10. A registered link resolves a previously-pending workflow relation endpoint.
        var relationStore = new InMemoryRelationStore();
        var relationModel = new com.acme.opsweave.inventory.api.RelationModelReader.Definition(
            "builtin.contains", 1, "builtin.host", 1, "builtin.service", 1, "MANY_TO_MANY");
        var entityService = new EntityRelationService(authorization(), query, relationStore,
            (tenant, id, revision) -> relationModel.id().equals(id) && relationModel.revision() == revision ? Optional.of(relationModel) : Optional.empty(),
            java.time.Clock.fixed(AT, ZoneOffset.UTC));
        WorkflowRelationOutput.Endpoints endpoints = (tenant, namespace, identity, endpointModelId) ->
            inventory.linkOf(new ExternalObjectKey(tenant, namespace, "entity", identity, endpointModelId))
                .flatMap(link -> query.find(tenant, link.entityId()).map(v -> new WorkflowRelationOutput.Endpoint(v.id(), v.version())));
        var output = new WorkflowRelationOutput(entityService, endpoints, java.time.Clock.fixed(AT, ZoneOffset.UTC));
        var rows = List.of(Map.<String, Object>of("from", "host-1", "to", "svc-1"),
            Map.<String, Object>of("from", "host-unbound", "to", "svc-1"));
        var result = output.write(p, "workflow.demo", relationModel.id(), relationModel.revision(),
            "from", "to", relationModel.fromType(), relationModel.toType(), rows, "fixture");
        check(result.relationIds().size() == 1 && result.pending().size() == 1);
        check(result.pending().get(0).code().equals("FROM_UNRESOLVED"));
        check(relationStore.history(TENANT, host, null, 50).size() == 1);

        System.out.println("ExternalLinkRegistrationSmoke: " + checks + " checks passed");
    }
}
