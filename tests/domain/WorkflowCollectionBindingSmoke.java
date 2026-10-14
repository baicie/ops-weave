import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.application.WorkflowCollectionBindingService;
import com.acme.opsweave.integration.application.WorkflowRuntimeService;
import com.acme.opsweave.integration.application.WorkflowService;
import com.acme.opsweave.integration.domain.WorkflowCollectionBinding;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import com.acme.opsweave.integration.domain.WorkflowFailure;
import com.acme.opsweave.integration.domain.WorkflowRuntime;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit enable/disable of the collection binding plus the fail-closed gate it controls. Publishing
 * a version never writes a binding; without one no source batch is consumed, the task fails closed
 * and no entity is written. Enabling is a reasoned, idempotent command and never calls a model.
 */
public final class WorkflowCollectionBindingSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Collection binding check " + checks); }
    static void rejects(Runnable r) {
        checks++;
        try { r.run(); } catch (IllegalArgumentException | NullPointerException | WorkflowFailure expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }
    static void failure(WorkflowFailure.Code code, Runnable r) {
        checks++;
        try { r.run(); } catch (WorkflowFailure expected) { if (expected.code() == code) return; throw new AssertionError(expected.code() + " != " + code); }
        throw new AssertionError("Expected " + code);
    }

    public static void main(String[] args) {
        domainShapes();
        serviceSemantics();
        gateSemantics();
        System.out.println("WorkflowCollectionBindingSmoke: " + checks + " checks passed");
    }

    static void domainShapes() {
        var id = UUID.randomUUID();
        var digest = "sha256:" + "a".repeat(64);
        var command = new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest, "binding-source",
            WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection");
        check(command.commandDigest().startsWith("sha256:"));
        check(command.commandDigest().equals(new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest,
            "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection").commandDigest()));
        // Content only: the request id is correlation, not content, so it never changes the digest.
        check(command.commandDigest().equals(new WorkflowCollectionBinding.Command(UUID.randomUUID(), "bound-flow", 1,
            digest, "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection").commandDigest()));
        check(!command.commandDigest().equals(new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest,
            "binding-source", WorkflowCollectionBinding.Operation.DISABLE, "Enable fixture collection").commandDigest()));
        check(!command.commandDigest().equals(new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest,
            "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Another reason").commandDigest()));
        for (var bad : List.<Runnable>of(
            () -> new WorkflowCollectionBinding.Command(null, "bound-flow", 1, digest, "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "r"),
            () -> new WorkflowCollectionBinding.Command(id, "Bound-Flow", 1, digest, "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "r"),
            () -> new WorkflowCollectionBinding.Command(id, "bound-flow", 0, digest, "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "r"),
            () -> new WorkflowCollectionBinding.Command(id, "bound-flow", 1, "sha256:zz", "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "r"),
            () -> new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest, "bad source!", WorkflowCollectionBinding.Operation.ENABLE, "r"),
            () -> new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest, "binding-source", WorkflowCollectionBinding.Operation.ENABLE, null),
            () -> new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest, "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "  "),
            () -> new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest, "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "x".repeat(257)),
            () -> WorkflowCollectionBinding.operation("START"))) rejects(bad);
        var at = java.time.Instant.parse("2026-10-13T00:00:00Z");
        var bound = new WorkflowCollectionBinding.Binding("bound-flow", 1, digest, "binding-source",
            WorkflowCollectionBinding.State.BOUND, 1, at, "Enable fixture collection");
        // The gate matches exactly one pinned version and one source instance.
        bound.requireConsumption("bound-flow", 1, digest, "binding-source");
        checks++;
        rejects(() -> bound.requireConsumption("bound-flow", 1, digest, "other-source"));
        rejects(() -> bound.requireConsumption("bound-flow", 2, digest, "binding-source"));
        rejects(() -> bound.requireConsumption("other-flow", 1, digest, "binding-source"));
        var unbound = bound.successor(WorkflowCollectionBinding.State.UNBOUND, 2, at.plusSeconds(1), "Stop fixture collection");
        rejects(() -> unbound.requireConsumption("bound-flow", 1, digest, "binding-source"));
        rejects(() -> bound.successor(WorkflowCollectionBinding.State.UNBOUND, 3, at, "Stop fixture collection"));
        rejects(() -> bound.successor(WorkflowCollectionBinding.State.UNBOUND, 1, at, "Stop fixture collection"));
        var receipt = new WorkflowCollectionBinding.Receipt(id, WorkflowCollectionBinding.Operation.ENABLE,
            command.commandDigest(), at, bound);
        receipt.require(command);
        checks++;
        rejects(() -> receipt.require(new WorkflowCollectionBinding.Command(id, "bound-flow", 1, digest, "binding-source",
            WorkflowCollectionBinding.Operation.DISABLE, "Enable fixture collection")));
        rejects(() -> new WorkflowCollectionBinding.Receipt(id, WorkflowCollectionBinding.Operation.DISABLE,
            command.commandDigest(), at, bound));
    }

    static void serviceSemantics() {
        var clock = new WorkflowSmoke.TestClock();
        var store = new InMemoryWorkflowStore();
        var p = new Principal(new SubjectId("binding-owner"), new TenantId("binding-tenant"),
            Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ, Permission.ENTITY_MANAGE), ResourceScope.tenantWide());
        var d = WorkflowSmoke.flow("bound-flow", 1, new WorkflowDefinition.Source("ZABBIX_HOST", "binding-source"));
        var rows = List.of(Map.<String, Object>of("raw_name", "Bound", "source_id", "bind-1"));
        var batchId = UUID.randomUUID();
        var edit = new WorkflowService(store, (who, t) -> WorkflowSmoke.MODEL,
            (who, s, b) -> new WorkflowService.Batch(rows, "fixture", 1, 0, false, "SUCCEEDED"), clock);
        edit.save(p, d, WorkflowSmoke.layout(d), 0);
        var preview = edit.evaluate(p, d.id(), 1, 1, d.digest(), false, null, batchId);
        edit.publish(p, d.id(), 1, 1, d.digest(), preview.receipt().id());
        var bindings = new WorkflowCollectionBindingService(store, clock);
        check(bindings.bindings(p).isEmpty());
        // A published version is not a binding: only an explicit command creates one.
        var enable = new WorkflowCollectionBinding.Command(UUID.randomUUID(), d.id(), 1, d.digest(), "binding-source",
            WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection");
        var first = bindings.command(p, enable);
        check(!first.replayed() && first.receipt().binding().state() == WorkflowCollectionBinding.State.BOUND);
        check(first.receipt().binding().generation() == 1);
        var replay = bindings.command(p, enable);
        check(replay.replayed() && replay.receipt().equals(first.receipt()));
        check(bindings.receipt(p, enable.requestId()).equals(first.receipt()));
        check(bindings.bindings(p).size() == 1 && bindings.bindings(p).getFirst().state() == WorkflowCollectionBinding.State.BOUND);
        failure(WorkflowFailure.Code.CONFLICT, () -> bindings.command(p, new WorkflowCollectionBinding.Command(
            enable.requestId(), d.id(), 1, d.digest(), "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Different reason")));
        failure(WorkflowFailure.Code.CONFLICT, () -> bindings.command(p, new WorkflowCollectionBinding.Command(
            UUID.randomUUID(), d.id(), 1, "sha256:" + "0".repeat(64), "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection")));
        failure(WorkflowFailure.Code.NOT_FOUND, () -> bindings.command(p, new WorkflowCollectionBinding.Command(
            UUID.randomUUID(), "missing-flow", 1, "sha256:" + "0".repeat(64), "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection")));
        failure(WorkflowFailure.Code.NOT_FOUND, () -> bindings.receipt(p, UUID.randomUUID()));
        var readOnly = new Principal(p.subjectId(), p.tenantId(), Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ), ResourceScope.tenantWide());
        failure(WorkflowFailure.Code.FORBIDDEN, () -> bindings.command(readOnly, new WorkflowCollectionBinding.Command(
            UUID.randomUUID(), d.id(), 1, d.digest(), "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection")));
        check(bindings.bindings(readOnly).size() == 1);
        var other = new Principal(new SubjectId("other"), p.tenantId(), p.permissions(), ResourceScope.tenantWide());
        check(bindings.bindings(other).isEmpty());
        // Unbinding is an explicit command too, and it records the reason and advances the generation.
        var disable = new WorkflowCollectionBinding.Command(UUID.randomUUID(), d.id(), 1, d.digest(), "binding-source",
            WorkflowCollectionBinding.Operation.DISABLE, "Stop fixture collection");
        var stopped = bindings.command(p, disable);
        check(!stopped.replayed() && stopped.receipt().binding().state() == WorkflowCollectionBinding.State.UNBOUND
            && stopped.receipt().binding().generation() == 2);
        check(bindings.bindings(p).getFirst().state() == WorkflowCollectionBinding.State.UNBOUND);
        // Bounded command receipts per owner: admission is refused, never silently dropped.
        int admitted = 0;
        for (int i = 0; i < WorkflowCollectionBindingService.MAX_COMMANDS_PER_OWNER + 5; i++) {
            try {
                bindings.command(p, new WorkflowCollectionBinding.Command(UUID.randomUUID(), d.id(), 1, d.digest(),
                    "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection"));
                admitted++;
            } catch (WorkflowFailure capacity) {
                check(capacity.code() == WorkflowFailure.Code.CAPACITY);
                break;
            }
        }
        check(admitted >= 1 && admitted <= WorkflowCollectionBindingService.MAX_COMMANDS_PER_OWNER);
        failure(WorkflowFailure.Code.CAPACITY, () -> bindings.command(p, new WorkflowCollectionBinding.Command(
            UUID.randomUUID(), d.id(), 1, d.digest(), "binding-source", WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection")));
    }

    /** The gate is the whole point: without the binding nothing is consumed and the task stops. */
    static void gateSemantics() {
        var clock = new WorkflowSmoke.TestClock();
        var store = new InMemoryWorkflowStore();
        var inventory = new InMemoryInventoryStore();
        var p = new Principal(new SubjectId("gate-owner"), new TenantId("gate-tenant"),
            Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ, Permission.ENTITY_MANAGE), ResourceScope.tenantWide());
        var d = WorkflowSmoke.flow("gate-flow", 1, new WorkflowDefinition.Source("ZABBIX_HOST", "gate-source"));
        var settings = new WorkflowRuntime.Settings("source_id", "name");
        var rows = List.of(Map.<String, Object>of("raw_name", "Gate host", "source_id", "gate-1"));
        var batch = new WorkflowService.Batch(rows, "fixture", 1, 0, false, "SUCCEEDED");
        var batchId = UUID.randomUUID();
        var edit = new WorkflowService(store, (who, t) -> WorkflowSmoke.MODEL, (who, s, b) -> batch, clock);
        edit.save(p, d, WorkflowSmoke.layout(d), 0);
        var preview = edit.evaluate(p, d.id(), 1, 1, d.digest(), false, null, batchId);
        edit.publish(p, d.id(), 1, 1, d.digest(), preview.receipt().id());
        var reads = new int[] { 0 };
        var runtime = new WorkflowRuntimeService(store, (who, t) -> WorkflowSmoke.MODEL, (who, s, b) -> batch,
            (who, s, after, afterId) -> {
                reads[0]++;
                var at = clock.instant;
                return at.isAfter(after)
                    ? Optional.of(new WorkflowRuntimeService.Batch(batchId, at, at, batch)) : Optional.empty();
            }, new com.acme.opsweave.integration.application.WorkflowEntityOutput(inventory), clock);
        var tested = edit.evaluate(p, d.id(), 1, 0, d.digest(), true, null, batchId);
        // A stored source batch is collection: without the explicit binding the command is refused.
        failure(WorkflowFailure.Code.COLLECTION_BINDING_REQUIRED,
            () -> runtime.execute(p, d.id(), 1, d.digest(), settings, tested.receipt().id(), null, batchId));
        check(runtime.executions(p).isEmpty());
        var task = runtime.control(p, d.id(), 1, d.digest(), settings, 0, true);
        check(task.state().equals("RUNNING"));
        clock.instant = clock.instant.plusSeconds(1);
        runtime.tick(p);
        var failed = runtime.tasks(p).getFirst();
        check(failed.state().equals("FAILED") && "COLLECTION_BINDING_REQUIRED".equals(failed.error()));
        check(reads[0] == 0 && runtime.executions(p).isEmpty());
        // An explicit manual sample run is not collection and needs no binding.
        var manualFlow = WorkflowSmoke.flow("gate-manual", 1, new WorkflowDefinition.Source("MANUAL_SAMPLE", "manual"));
        edit.save(p, manualFlow, WorkflowSmoke.layout(manualFlow), 0);
        var manualPreview = edit.evaluate(p, manualFlow.id(), 1, 1, manualFlow.digest(), false, rows, null);
        edit.publish(p, manualFlow.id(), 1, 1, manualFlow.digest(), manualPreview.receipt().id());
        var manualRun = edit.evaluate(p, manualFlow.id(), 1, 0, manualFlow.digest(), true, rows, null);
        check(runtime.execute(p, manualFlow.id(), 1, manualFlow.digest(), settings, manualRun.receipt().id(), rows, null).state().equals("SUCCEEDED"));
        check(runtime.executions(p).size() == 1);
        // Enabling the binding is what unlocks collection; it writes no entity by itself.
        var bindings = new WorkflowCollectionBindingService(store, clock);
        check(bindings.bindings(p).isEmpty());
        bindings.command(p, new WorkflowCollectionBinding.Command(UUID.randomUUID(), d.id(), 1, d.digest(), "gate-source",
            WorkflowCollectionBinding.Operation.ENABLE, "Enable fixture collection"));
        check(runtime.executions(p).size() == 1 && reads[0] == 0);
        var resumed = runtime.control(p, d.id(), 1, d.digest(), settings, failed.generation(), true);
        check(resumed.state().equals("RUNNING"));
        clock.instant = clock.instant.plusSeconds(1);
        runtime.tick(p);
        var consumed = runtime.tasks(p).getFirst();
        check(consumed.state().equals("RUNNING") && reads[0] == 1 && runtime.executions(p).size() == 2);
        // Unbinding stops consumption immediately: the next tick fails closed and reads nothing more.
        bindings.command(p, new WorkflowCollectionBinding.Command(UUID.randomUUID(), d.id(), 1, d.digest(), "gate-source",
            WorkflowCollectionBinding.Operation.DISABLE, "Stop fixture collection"));
        clock.instant = clock.instant.plusSeconds(2);
        runtime.tick(p);
        var stopped = runtime.tasks(p).getFirst();
        check(stopped.state().equals("FAILED") && "COLLECTION_BINDING_REQUIRED".equals(stopped.error()));
        check(reads[0] == 1 && runtime.executions(p).size() == 2);
    }
}
