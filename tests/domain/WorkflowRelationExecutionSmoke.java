import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.catalog.domain.ModelDefinition.*;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.application.WorkflowRelationOutput.Endpoint;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.domain.WorkflowRuntime.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.application.EntityRelationService;
import com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** RELATION output execution chain: PENDING rows stay visible, receipts keep the closed
 *  written/pending equation, and a duplicate relation stops instead of silently winning. */
public final class WorkflowRelationExecutionSmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("relation-execution-smoke");
    static final Instant AT = Instant.parse("2026-10-09T00:00:00Z");
    static final String DIGEST = "sha256:" + "a".repeat(64);
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Relation execution check " + checks); }
    static void rejects(Runnable call) { checks++; try { call.run(); } catch (IllegalArgumentException | WorkflowFailure expected) { return; } throw new AssertionError("Expected relation execution rejection " + checks); }

    static final Endpoints ENDPOINTS = new Endpoints(new Ref("builtin.host", 1), new Ref("builtin.service", 1), Cardinality.MANY_TO_MANY);
    static final ModelDefinition RELATION = new ModelDefinition("custom.contains", 1, Kind.RELATION, "Contains", "fixture", List.of(), ENDPOINTS);
    static final RelationModelReader.Definition RELATION_TYPES = new RelationModelReader.Definition("custom.contains", 1, "builtin.host", 1, "builtin.service", 1, "MANY_TO_MANY");
    static final Settings SETTINGS = new Settings("host_identity", "interface_identity");
    static final Settings HOST_SETTINGS = new Settings("entity_id", "name");
    static Node node(String id, WorkflowDefinition.Type type, Map<String, String> config) { return WorkflowOperators.builtIn().pin(new Node(id, type, "1", config)); }
    static EntityId entity(long value) { return new EntityId(UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value))); }

    static WorkflowDefinition definition(String id, Source source, Map<String, String> mapping) {
        var target = new Target(RELATION.id(), RELATION.revision(), RELATION.digest(), "RELATION", null, null, "host_identity", "interface_identity");
        var nodes = List.of(node("source", WorkflowDefinition.Type.SOURCE, Map.of()), node("mapping", WorkflowDefinition.Type.MAP, mapping),
            node("validate", WorkflowDefinition.Type.VALIDATE, Map.of()), node("output", WorkflowDefinition.Type.OUTPUT, Map.of()));
        var edges = List.of(new Edge("source", "mapping"), new Edge("mapping", "validate"), new Edge("validate", "output"));
        return new WorkflowDefinition(id, 1, "Fixture relation output", source, target, nodes, edges);
    }

    static final class Fixture {
        final InMemoryWorkflowStore store = new InMemoryWorkflowStore();
        final WorkflowSmoke.TestClock clock;
        final InMemoryRelationStore relations;
        final Map<String, Endpoint> resolved;
        final Map<String, String> types;
        final Principal p = new Principal(new SubjectId("relation-execution-owner"), TENANT, Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ, Permission.ENTITY_MANAGE), ResourceScope.tenantWide());
        final WorkflowDefinition d;
        final WorkflowService edit;
        final WorkflowRuntimeService runtime;
        final List<WorkflowRuntimeService.Batch> queued = new ArrayList<>();

        Fixture(String id, Source source, Map<String, String> mapping, WorkflowSmoke.TestClock clock, InMemoryRelationStore relations,
                Map<String, Endpoint> resolved, Map<String, String> types, WorkflowService.Samples samples, WorkflowHostRuntimeService.Sources hostSources) {
            this.clock = clock; this.relations = relations; this.resolved = resolved; this.types = types;
            d = definition(id, source, mapping);
            WorkflowService.Models models = (who, target) -> RELATION;
            AuthorizationService authorization = (principal, resource, permission) -> principal.has(permission) && principal.resourceScope().includes(resource) ? AuthorizationDecision.allow() : AuthorizationDecision.deny("SCOPE_DENIED");
            InventoryQuery entities = new InventoryQuery() {
                @Override public Optional<EntityView> find(TenantId tenant, EntityId id) {
                    for (var entry : resolved.values()) if (entry.id().equals(id)) return Optional.of(new EntityView(id, tenant, types.get(id.value().toString()), "fixture", "ACTIVE", entry.version(), Map.of()));
                    return Optional.empty();
                }
                @Override public List<EntityView> list(TenantId tenant) { return List.of(); }
            };
            var relationsSink = new WorkflowRelationOutput(new EntityRelationService(authorization, entities, relations,
                (tenant, model, revision) -> RELATION_TYPES.id().equals(model) && RELATION_TYPES.revision() == revision ? Optional.of(RELATION_TYPES) : Optional.empty(), clock),
                (tenant, namespace, identity, model) -> Optional.ofNullable(resolved.get(identity)), clock);
            var entitySink = new WorkflowRuntimeService.Output() {
                public void validate(Principal who, WorkflowDefinition flow, Settings config, List<Map<String, Object>> input, WorkflowEvaluation evaluation) { throw new AssertionError("RELATION target never validates entity rows"); }
                public String write(Principal who, WorkflowDefinition flow, Settings config, UUID key, Instant time, Map<String, Object> input, Map<String, Object> values, String origin) { throw new AssertionError("RELATION target never writes entities"); }
            };
            var outputs = new WorkflowOutputs(entitySink, relationsSink);
            edit = new WorkflowService(store, models, samples, clock);
            if (source.configuration() == null) edit.save(p, d, WorkflowSmoke.layout(d), 0);
            else store.transaction(p.tenantId(), s -> { s.publish(new WorkflowStore.Entry(d, d.digest(), "PUBLISHED", 0, WorkflowSmoke.layout(d), clock.instant, null), p.subjectId().value()); return null; });
            store.transaction(p.tenantId(), s -> { s.saveCollectionBinding(p.subjectId().value(), new WorkflowCollectionBinding.Binding(d.id(), d.revision(), d.digest(), source.instanceId(), WorkflowCollectionBinding.State.BOUND, 1, clock.instant, "Fixture explicitly binds collection")); return null; });
            runtime = new WorkflowRuntimeService(store, models, samples, (who, src, after, afterId) -> queued.isEmpty() ? Optional.empty() : Optional.of(queued.removeFirst()), outputs, clock, hostSources);
        }
        WorkflowService.Result preview(boolean published, List<Map<String, Object>> rows, UUID batchId) { return edit.evaluate(p, d.id(), 1, published ? 0 : 1, d.digest(), published, rows, batchId); }
    }

    public static void main(String[] args) {
        var shared = new InMemoryRelationStore();
        var hostA = entity(11); var serviceA = entity(12);
        var resolved = new HashMap<String, Endpoint>();
        var types = new HashMap<String, String>();
        resolved.put("host-1", new Endpoint(hostA, 1)); types.put(hostA.value().toString(), "Host");
        resolved.put("svc-1", new Endpoint(serviceA, 1)); types.put(serviceA.value().toString(), "Service");

        // --- execute() path: written + pending rows close the receipt equation ---------------------
        var clock = new WorkflowSmoke.TestClock();
        var manual = new Source("MANUAL_SAMPLE", "manual");
        var fixture = new Fixture("relation-exec", manual, Map.of("host_raw", "host_identity", "svc_raw", "interface_identity"), clock, shared, resolved, types,
            (who, src, id) -> { throw new AssertionError("Manual source read"); }, null);
        var rows = List.of(
            Map.<String, Object>of("host_raw", "host-1", "svc_raw", "svc-1"),
            Map.<String, Object>of("host_raw", "host-missing", "svc_raw", "svc-1"),
            Map.<String, Object>of("host_raw", "host-1", "svc_raw", "svc-missing"));
        var preview = fixture.preview(false, rows, null);
        fixture.edit.publish(fixture.p, fixture.d.id(), 1, 1, fixture.d.digest(), preview.receipt().id());
        var run = fixture.preview(true, rows, null);
        var result = fixture.runtime.execute(fixture.p, fixture.d.id(), 1, fixture.d.digest(), SETTINGS, run.receipt().id(), rows, null);
        check(result.state().equals("SUCCEEDED") && result.accepted() == 3 && result.rejected() == 0 && result.filtered() == 0);
        check(result.entityIds().isEmpty() && result.relationIds().size() == 1 && result.pending().size() == 2);
        check(result.written() == 1 && result.written() + result.pending().size() == result.accepted());
        check(result.pending().get(0).index() == 1 && result.pending().get(0).code().equals("FROM_UNRESOLVED"));
        check(result.pending().get(1).index() == 2 && result.pending().get(1).code().equals("TO_UNRESOLVED"));
        check(fixture.runtime.execution(fixture.p, result.id()).equals(result) && fixture.runtime.executions(fixture.p).equals(List.of(result)));
        var written = shared.history(TENANT, hostA, null, 50);
        check(written.size() == 1 && written.get(0).sourceRef().equals("workflow:workflow.relation-exec") && written.get(0).dataMode().equals("unknown"));

        // Deterministic requestId: replaying the execution neither writes again nor duplicates receipts.
        check(fixture.runtime.execute(fixture.p, fixture.d.id(), 1, fixture.d.digest(), SETTINGS, run.receipt().id(), rows, null).equals(result));
        check(shared.history(TENANT, hostA, null, 50).size() == 1);

        // Closed receipt invariants: pending never counts as written and never mixes with entity output.
        var relationId = result.relationIds().getFirst();
        rejects(() -> new Execution(result.id(), result.workflowId(), 1, DIGEST, SETTINGS, "fixture", null, AT, "SUCCEEDED", 3, 0, 0, List.of(relationId), null, null, List.of(relationId), List.of()));
        rejects(() -> new Execution(result.id(), result.workflowId(), 1, DIGEST, SETTINGS, "fixture", null, AT, "SUCCEEDED", 3, 0, 0, List.of(), null, null, List.of(relationId), List.of()));
        rejects(() -> new Execution(result.id(), result.workflowId(), 1, DIGEST, SETTINGS, "fixture", null, AT, "FAILED", 1, 0, 0, List.of(), "OUTPUT_UNAVAILABLE", null, List.of(relationId, relationId), List.of()));
        rejects(() -> new Execution(result.id(), result.workflowId(), 1, DIGEST, SETTINGS, "fixture", null, AT, "SUCCEEDED", 1, 0, 0, List.of(), null, null, List.of(), List.of(new Pending(1, "FROM_UNRESOLVED"), new Pending(1, "TO_UNRESOLVED"))));
        rejects(() -> new Execution(result.id(), result.workflowId(), 1, DIGEST, SETTINGS, "fixture", null, AT, "SUCCEEDED", 0, 0, 0, List.of(), null, null, List.of(relationId), List.of()));
        rejects(() -> new Pending(5, "FROM_UNRESOLVED"));
        rejects(() -> new Pending(0, "GUESSED"));
        check(new Execution(result.id(), result.workflowId(), 1, DIGEST, SETTINGS, "fixture", null, AT, "SUCCEEDED", 2, 0, 0, List.of(), null, null, List.of(relationId), List.of(new Pending(1, "TO_UNRESOLVED"))).written() == 1);

        // --- batch consumption (tick): one write end, fail closed, cursor only on confirmation ----
        var hostB = entity(21); var serviceB = entity(22); var hostC = entity(31); var serviceC = entity(32);
        resolved.put("batch-1", new Endpoint(hostB, 1)); types.put(hostB.value().toString(), "Host");
        resolved.put("batch-svc", new Endpoint(serviceB, 1)); types.put(serviceB.value().toString(), "Service");
        resolved.put("batch-2", new Endpoint(hostC, 1)); types.put(hostC.value().toString(), "Host");
        resolved.put("batch-svc-2", new Endpoint(serviceC, 1)); types.put(serviceC.value().toString(), "Service");
        var source = new Source("ZABBIX_HOST", "zabbix-relation");
        var firstBatch = List.of(
            Map.<String, Object>of("host_raw", "batch-1", "svc_raw", "batch-svc"),
            Map.<String, Object>of("host_raw", "batch-missing", "svc_raw", "batch-svc"));
        var secondBatch = List.of(
            Map.<String, Object>of("host_raw", "batch-2", "svc_raw", "batch-svc-2"),
            Map.<String, Object>of("host_raw", "host-1", "svc_raw", "svc-1"));
        WorkflowService.Samples samples = (who, src, id) -> new WorkflowService.Batch(firstBatch, "fixture", 2, 0, false, "SUCCEEDED");
        var batchClock = new WorkflowSmoke.TestClock();
        var batch = new Fixture("relation-batch", source, Map.of("host_raw", "host_identity", "svc_raw", "interface_identity"), batchClock, shared, resolved, types, samples, null);
        var batchPreview = batch.preview(false, null, UUID.randomUUID());
        batch.edit.publish(batch.p, batch.d.id(), 1, 1, batch.d.digest(), batchPreview.receipt().id());
        var started = batch.runtime.control(batch.p, batch.d.id(), 1, batch.d.digest(), SETTINGS, 0, true);
        check(started.state().equals("RUNNING") && started.cursor().equals(batchClock.instant));
        var firstId = UUID.randomUUID();
        var firstAt = batchClock.instant.plusSeconds(1);
        batch.queued.add(new WorkflowRuntimeService.Batch(firstId, firstAt, firstAt, new WorkflowService.Batch(firstBatch, "fixture", 2, 0, false, "SUCCEEDED")));
        batchClock.instant = firstAt;
        batch.runtime.tick(batch.p);
        var consumed = batch.runtime.executions(batch.p).getFirst();
        check(consumed.syncRunId().equals(firstId) && consumed.state().equals("SUCCEEDED"));
        check(consumed.relationIds().size() == 1 && consumed.pending().size() == 1 && consumed.written() + consumed.pending().size() == consumed.accepted());
        check(consumed.pending().getFirst().code().equals("FROM_UNRESOLVED") && consumed.pending().getFirst().index() == 1);
        check(batch.runtime.tasks(batch.p).getFirst().cursor().equals(firstAt) && batch.runtime.tasks(batch.p).getFirst().state().equals("RUNNING"));
        var batchWritten = shared.history(TENANT, hostB, null, 50);
        check(batchWritten.size() == 1 && batchWritten.get(0).sourceRef().equals("workflow:workflow.relation-batch") && batchWritten.get(0).dataMode().equals("fixture"));
        // A duplicate relation fails the batch: rows written before the failure stay recorded, the cursor stops.
        var secondId = UUID.randomUUID();
        var secondAt = firstAt.plusSeconds(1);
        batch.queued.add(new WorkflowRuntimeService.Batch(secondId, secondAt, secondAt, new WorkflowService.Batch(secondBatch, "fixture", 2, 0, false, "SUCCEEDED")));
        batchClock.instant = secondAt;
        batch.runtime.tick(batch.p);
        var stopped = batch.runtime.executions(batch.p).getFirst();
        check(stopped.syncRunId().equals(secondId) && stopped.state().equals("FAILED") && stopped.error().equals("OUTPUT_UNAVAILABLE"));
        check(stopped.accepted() == 2 && stopped.relationIds().size() == 1 && stopped.pending().isEmpty() && stopped.written() == 1);
        check(batch.runtime.tasks(batch.p).getFirst().state().equals("FAILED") && batch.runtime.tasks(batch.p).getFirst().cursor().equals(firstAt));
        check(shared.history(TENANT, hostA, null, 50).size() == 1);

        // --- host scan batches: the batch receipt carries relation ids and pending rows ------------
        var hostD = entity(41); var serviceD = entity(42); var hostE = entity(51); var serviceE = entity(52); var hostF = entity(61); var serviceF = entity(62);
        for (var pair : List.of(new Object[]{hostD, "Host"}, new Object[]{serviceD, "Service"}, new Object[]{hostE, "Host"}, new Object[]{serviceE, "Service"}, new Object[]{hostF, "Host"}, new Object[]{serviceF, "Service"})) types.put(((EntityId) pair[0]).value().toString(), (String) pair[1]);
        var hostRows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5; i++) hostRows.add(Map.<String, Object>of("name", "Fixture host " + i, "ip", "127.0.0." + i, "lifecycle", "ACTIVE", "entity_id", UUID.nameUUIDFromBytes(("relation-host-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()));
        var resolvedHost = new HashMap<String, Endpoint>();
        resolvedHost.put(hostRows.get(0).get("entity_id").toString(), new Endpoint(hostD, 1));
        resolvedHost.put("127.0.0.0", new Endpoint(serviceD, 1));
        resolvedHost.put(hostRows.get(1).get("entity_id").toString(), new Endpoint(hostE, 1));
        resolvedHost.put("127.0.0.1", new Endpoint(serviceE, 1));
        var tailRow = Map.<String, Object>of("name", "Fixture tail", "ip", "127.0.0.9", "lifecycle", "ACTIVE", "entity_id", UUID.nameUUIDFromBytes("relation-host-tail".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        resolvedHost.put(tailRow.get("entity_id").toString(), new Endpoint(hostF, 1));
        resolvedHost.put("127.0.0.9", new Endpoint(serviceF, 1));
        var conflictRow = Map.<String, Object>of("name", "Fixture conflict", "ip", "127.0.0.7", "lifecycle", "ACTIVE", "entity_id", hostA.value().toString());
        resolvedHost.put(hostA.value().toString(), new Endpoint(hostA, 1));
        resolvedHost.put("127.0.0.7", new Endpoint(serviceA, 1));
        var cursor = "ids-v1|" + "a".repeat(64) + "|7|5";
        var hostClock = new WorkflowSmoke.TestClock();
        WorkflowHostRuntimeService.Sources hostSources = new WorkflowHostRuntimeService.Sources() {
            public void require(WorkflowStore.Session s, Principal who, WorkflowDefinition.Source src) { }
            public WorkflowHostScan.Page read(Principal who, WorkflowDefinition.Source src, String after) {
                var at = hostClock.instant = hostClock.instant.plusSeconds(1);
                return after == null ? new WorkflowHostScan.Page(hostRows, cursor, false, at) : new WorkflowHostScan.Page(List.of(tailRow, conflictRow), null, true, at);
            }
        };
        var hostFixture = new Fixture("relation-host-scan", new Source("ZABBIX_HOST", "zabbix-relation", new ConfigurationPin(UUID.randomUUID(), 1, "sha256:" + "b".repeat(64))),
            Map.of("entity_id", "host_identity", "ip", "interface_identity"), hostClock, shared, resolvedHost, types, samples, hostSources);
        var hostStart = hostFixture.runtime.command(hostFixture.p, new WorkflowRuntimeControl.Command(UUID.randomUUID(), hostFixture.d.id(), 1, hostFixture.d.digest(), HOST_SETTINGS, 0, WorkflowRuntimeControl.Operation.START), () -> null);
        check(hostStart.task().state().equals("RUNNING"));
        hostFixture.runtime.tick(hostFixture.p);
        var first = hostFixture.runtime.hostBatches(hostFixture.p, hostFixture.d.id()).getFirst();
        check(first.state().equals("CONFIRMED") && first.entityIds().isEmpty());
        check(first.relationIds().size() == 2 && first.pending().size() == 3 && first.relationIds().size() + first.pending().size() == first.records().size());
        check(hostFixture.runtime.hostCheckpoint(hostFixture.p, hostFixture.d.id()).orElseThrow().confirmedRecords() == 5);
        // Conflicting endpoint pair stops the scan: written rows stay on the receipt, no cursor advance.
        hostFixture.runtime.tick(hostFixture.p);
        var batches = hostFixture.runtime.hostBatches(hostFixture.p, hostFixture.d.id());
        check(batches.size() == 2 && batches.get(0).state().equals("UNKNOWN") && batches.get(0).error().equals("OUTPUT_UNAVAILABLE"));
        check(batches.get(0).relationIds().size() == 1 && batches.get(0).pending().isEmpty());
        check(hostFixture.runtime.hostCheckpoint(hostFixture.p, hostFixture.d.id()).orElseThrow().confirmedRecords() == 5);
        check(hostFixture.runtime.tasks(hostFixture.p).getFirst().state().equals("FAILED"));
        System.out.println("WorkflowRelationExecutionSmoke: " + checks + " checks passed");
    }
}
