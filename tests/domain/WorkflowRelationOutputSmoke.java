import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.WorkflowRelationOutput;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.application.EntityRelationService;
import com.acme.opsweave.inventory.domain.RelationCardinality;
import com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

public final class WorkflowRelationOutputSmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("relation-output-smoke");
    static final Instant AT = Instant.parse("2026-10-09T00:00:00Z");
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Relation output check " + checks); }
    static void fails(Class<? extends Throwable> type, Runnable call) {
        checks++;
        try { call.run(); } catch (Throwable expected) { if (type.isInstance(expected)) return; throw new AssertionError("Unexpected relation output failure", expected); }
        throw new AssertionError("Expected relation output failure " + type.getSimpleName());
    }
    static EntityId entityId(long value) { return new EntityId(UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value))); }
    static AuthorizationService authorization() {
        return (principal, resource, permission) -> principal.has(permission) && principal.resourceScope().includes(resource)
            ? AuthorizationDecision.allow() : AuthorizationDecision.deny("SCOPE_DENIED");
    }
    static Principal operator() { return new Principal(new SubjectId("relation-output-operator"), TENANT, Set.of(Permission.ENTITY_MANAGE), ResourceScope.tenantWide()); }
    static InventoryQuery query(Map<EntityId, String> types) {
        return new InventoryQuery() {
            @Override public Optional<EntityView> find(TenantId tenant, EntityId id) {
                String type = types.get(id);
                return type == null ? Optional.empty() : Optional.of(new EntityView(id, tenant, type, "entity", "ACTIVE", 1, Map.of()));
            }
            @Override public List<EntityView> list(TenantId tenant) { return List.of(); }
        };
    }

    public static void main(String[] args) {
        var host = entityId(11); var service = entityId(12);
        var store = new InMemoryRelationStore();
        var model = new RelationModelReader.Definition("builtin.contains", 1, "builtin.host", 1, "builtin.service", 1, "MANY_TO_MANY");
        var entityService = new EntityRelationService(authorization(), query(Map.of(host, "Host", service, "Service")), store,
            (tenant, id, revision) -> model.id().equals(id) && model.revision() == revision ? Optional.of(model) : Optional.empty(),
            java.time.Clock.fixed(AT, ZoneOffset.UTC));
        var resolved = new HashMap<String, WorkflowRelationOutput.Endpoint>();
        resolved.put("host-1", new WorkflowRelationOutput.Endpoint(host, 1));
        resolved.put("svc-1", new WorkflowRelationOutput.Endpoint(service, 1));
        var output = new WorkflowRelationOutput(entityService,
            (tenant, namespace, identity, endpointModelId) -> Optional.ofNullable(resolved.get(identity)),
            java.time.Clock.fixed(AT, ZoneOffset.UTC));
        var p = operator();
        var rows = List.of(
            Map.<String, Object>of("host_identity", "host-1", "interface_identity", "svc-1"),
            Map.<String, Object>of("host_identity", "host-missing", "interface_identity", "svc-1"),
            Map.<String, Object>of("host_identity", "host-1", "interface_identity", "svc-missing"));

        var first = output.write(p, "workflow.demo", model.id(), model.revision(), "host_identity", "interface_identity", model.fromType(), model.toType(), rows, "fixture");
        check(first.relationIds().size() == 1 && first.pending().size() == 2);
        check(first.pending().get(0).index() == 1 && first.pending().get(0).code().equals("FROM_UNRESOLVED"));
        check(first.pending().get(1).index() == 2 && first.pending().get(1).code().equals("TO_UNRESOLVED"));
        var written = store.history(TENANT, host, null, 50);
        check(written.size() == 1);
        check(written.get(0).sourceRef().equals("workflow:workflow.demo") && written.get(0).dataMode().equals("fixture"));

        var replay = output.write(p, "workflow.demo", model.id(), model.revision(), "host_identity", "interface_identity", model.fromType(), model.toType(), rows, "fixture");
        check(replay.relationIds().equals(first.relationIds()));
        check(store.history(TENANT, host, null, 50).size() == 1);

        var single = List.of(Map.<String, Object>of("host_identity", "host-1", "interface_identity", "svc-1"));
        fails(RelationStore.Conflict.class, () -> output.write(p, "workflow.other", model.id(), model.revision(), "host_identity", "interface_identity", model.fromType(), model.toType(), single, "zabbix-jsonrpc"));

        store.close(TENANT, UUID.randomUUID(), written.get(0).id(), AT.plusSeconds(30), "来源失效确认", 1);
        var afterClose = output.write(p, "workflow.demo", model.id(), model.revision(), "host_identity", "interface_identity", model.fromType(), model.toType(), rows, "fixture");
        check(afterClose.relationIds().size() == 1);
        check(store.history(TENANT, host, null, 50).size() == 1);
        check(store.history(TENANT, host, null, 50).get(0).validTo() != null);
        fails(RelationStore.Conflict.class, () -> output.write(p, "workflow.later", model.id(), model.revision(), "host_identity", "interface_identity", model.fromType(), model.toType(), single, "fixture"));

        fails(IllegalArgumentException.class, () -> output.write(p, "workflow.demo", model.id(), model.revision(), "host_identity", "interface_identity", model.fromType(), model.toType(), List.of(Map.<String, Object>of("host_identity", "  ", "interface_identity", "svc-1")), "fixture"));
        fails(IllegalArgumentException.class, () -> output.write(p, "workflow.demo", model.id(), model.revision(), "host_identity", "interface_identity", model.fromType(), model.toType(), single, "import"));
        check(RelationCardinality.MANY_TO_MANY.maxActiveFrom() == 0);
        System.out.println("WorkflowRelationOutputSmoke: " + checks + " checks passed");
    }
}
