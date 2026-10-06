package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowLogReplay.*;
import static com.acme.opsweave.integration.domain.WorkflowLogReplay.*;
import com.acme.opsweave.integration.api.WorkflowLogWindowSink;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Foreground reconstruction using the existing bounded source and sink. No scheduler, notifications or actions. */
public final class WorkflowLogReplayService {
    private final WorkflowStore store;
    private final WorkflowService workflows;
    private final WorkflowLogStreamService.Source source;
    private final WorkflowLogWindowSink sink;
    private final Semaphore budget;
    private final Clock clock;
    private record Selection(WorkflowStore.Entry entry, WorkflowExecutionPlan plan) {}
    private record Prepared(Proof proof, WorkflowLogWindow.Batch batch) {}

    public WorkflowLogReplayService(WorkflowStore store, WorkflowService workflows, WorkflowLogStreamService.Source source,
                                      WorkflowLogWindowSink sink, Semaphore budget, Clock clock) {
        this.store = store; this.workflows = workflows; this.source = source; this.sink = sink; this.budget = budget; this.clock = clock;
    }
    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private void authorize(Principal p, WorkflowQuality.Reference ref) {
        workflows.authorize(p); var auth = new Authorizer(); var resource = new ResourceRef(p.tenantId(), "workflow", ref.id());
        if (auth.decide(p, resource, Permission.WORKFLOW_REPLAY).denied() || auth.decide(p, resource, Permission.SOURCE_SYNC).denied()
            || auth.decide(p, new ResourceRef(p.tenantId(), "log", "workflow." + ref.id()), Permission.LOG_READ).denied()) throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
    }
    private Selection selection(Principal p, WorkflowStore.Session s, WorkflowQuality.Reference ref, boolean execute) {
        authorize(p, ref); var e = workflows.accessibleVersion(p, s, ref.id(), ref.revision());
        if (!e.digest().equals(ref.digest())) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        var d = e.definition(); if (!d.source().kind().equals("ZABBIX_LOG") || d.source().configuration() == null || d.source().log() == null || !d.target().kind().equals("LOG")) throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if (execute && new Authorizer().decide(p, new ResourceRef(p.tenantId(), "log", "workflow." + ref.id()), Permission.LOG_WRITE).denied()) throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        return new Selection(e, execute ? workflows.executionPlan(p, s, e) : null);
    }
    private void scope(Principal p, WorkflowStore.Session s, Plan plan) {
        selection(p, s, plan.reference(), false); if (plan.proof() == null) return;
        var scope = plan.proof().scope();
        if (!p.tenantId().value().equals(scope.tenant()) || !WorkflowDefinition.hash(List.of(p.subjectId().value())).substring(7).equals(scope.ownerScope())) throw new IllegalStateException("Replay scope proof unavailable");
    }
    public Plan plan(Principal p, UUID id) {
        workflows.authorize(p); return store.transaction(p.tenantId(), s -> { var r = s.logReplayPlan(p.subjectId().value(), id).orElseThrow(() -> new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND)); scope(p, s, r); return r; });
    }
    public WorkflowService.Page<Plan> plans(Principal p, WorkflowQuality.Reference ref) {
        return store.transaction(p.tenantId(), s -> { selection(p, s, ref, false); var rows = s.logReplayPlans(p.subjectId().value(), ref); rows.forEach(r -> scope(p, s, r)); return new WorkflowService.Page<>(rows.subList(0, Math.min(20, rows.size())), rows.size() > 20); });
    }
    private Prepared prepare(Principal p, Plan plan) {
        var selection = store.transaction(p.tenantId(), s -> selection(p, s, plan.reference(), true));
        var d = selection.entry().definition(); var rows = source.read(p, d.source(), plan.from(), plan.till());
        if (rows == null || rows.size() > MAX_POINTS) throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
        var scope = new WorkflowLogOutput.Scope(p.tenantId().value(), WorkflowDefinition.hash(List.of(p.subjectId().value())).substring(7), plan.requestId(), d.id(), d.revision(), d.digest());
        var batch = WorkflowLogWindow.prepare(scope, selection.plan(), rows, plan.from(), plan.till(), now());
        return new Prepared(new Proof(WorkflowLogWindow.inputDigest(d.source(), plan.from(), plan.till(), rows), batch.digest(), rows.size(), batch.filtered(), scope,
            batch.records().stream().map(WorkflowLogWindow.Record::index).toList(), rows.stream().map(r -> (String)r.get("timestamp")).toList()), batch);
    }
    private String error(RuntimeException failure) {
        if (failure instanceof WorkflowFailure f && ERRORS.contains(f.code().name())) return f.code().name();
        return failure instanceof IllegalArgumentException ? "INVALID_SAMPLE" : "SOURCE_UNAVAILABLE";
    }
    public Plan create(Principal p, Command c) {
        authorize(p, c.reference()); var prior = store.transaction(p.tenantId(), s -> s.logReplayPlan(p.subjectId().value(), c.requestId()));
        if (prior.isPresent()) { prior.get().require(c); return plan(p, c.requestId()); }
        if (!budget.tryAcquire()) throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            var admitted = store.transaction(p.tenantId(), s -> { var existing = s.logReplayPlan(p.subjectId().value(), c.requestId()); if (existing.isPresent()) { existing.get().require(c); return false; }
                selection(p, s, c.reference(), true); c.requireWindow(now()); if (s.logReplayPlanCount(p.subjectId().value()) >= MAX_PLANS) throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
                s.addLogReplayPlan(p.subjectId().value(), Plan.preparing(c, now())); return true; });
            if (!admitted) return plan(p, c.requestId());
            var original = plan(p, c.requestId()); Prepared prepared;
            try { prepared = prepare(p, original); store.transaction(p.tenantId(), s -> { selection(p, s, c.reference(), true); return null; }); }
            catch (RuntimeException failure) { return finishPlan(p, original, null, error(failure)); }
            return finishPlan(p, original, prepared.proof(), null);
        } finally { budget.release(); }
    }
    private Plan finishPlan(Principal p, Plan original, Proof proof, String error) {
        return store.transaction(p.tenantId(), s -> { var current = s.logReplayPlan(p.subjectId().value(), original.requestId()).orElseThrow(); if (!current.state().equals("PREPARING")) return current;
            var result = current.finish(proof, error, now().isBefore(current.updatedAt()) ? current.updatedAt() : now()); s.finishLogReplayPlan(p.subjectId().value(), result); return result; });
    }
    public Receipt receipt(Principal p, UUID id) {
        workflows.authorize(p); return store.transaction(p.tenantId(), s -> { var r = s.logReplayReceipt(p.subjectId().value(), id).orElseThrow(() -> new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
            var plan = s.logReplayPlan(p.subjectId().value(), r.planId()).orElseThrow(); scope(p, s, plan); if (!r.reference().equals(plan.reference()) || !r.commandDigest().equals(new Execute(r.requestId(), plan.requestId(), plan.proof().inputDigest(), plan.proof().batchDigest()).commandDigest())) throw new IllegalStateException(); return r; });
    }
    public Receipt execute(Principal p, Execute c) {
        var plan = plan(p, c.planId()); c.require(plan); var prior = store.transaction(p.tenantId(), s -> s.logReplayReceipt(p.subjectId().value(), c.requestId()));
        if (prior.isPresent()) { prior.get().require(c); return receipt(p, c.requestId()); }
        if (!budget.tryAcquire()) throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            var admitted = store.transaction(p.tenantId(), s -> { var existing = s.logReplayReceipt(p.subjectId().value(), c.requestId()); if (existing.isPresent()) { existing.get().require(c); return false; }
                if (s.logReplayReceiptForPlan(p.subjectId().value(), plan.requestId()).isPresent()) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
                selection(p, s, plan.reference(), true); plan.requireExecutable(now()); c.require(plan);
                s.addLogReplayReceipt(p.subjectId().value(), new Receipt("2.0", c.requestId(), plan.requestId(), plan.reference(), c.commandDigest(), now(), now(), "PENDING", null)); return true; });
            if (!admitted) return receipt(p, c.requestId());
            var original = receipt(p, c.requestId()); Prepared prepared;
            try { plan.requireExecutable(now()); prepared = prepare(p, plan); if (!plan.proof().equals(prepared.proof())) return finish(p, original, "FAILED", "SOURCE_WINDOW_CHANGED");
                store.transaction(p.tenantId(), s -> { selection(p, s, plan.reference(), true); plan.requireExecutable(now()); return null; }); }
            catch (RuntimeException failure) { return finish(p, original, "FAILED", now().isBefore(plan.expiresAt()) ? error(failure) : "WINDOW_EXPIRED"); }
            if (prepared.batch().records().isEmpty()) return finish(p, original, "CONFIRMED", null);
            boolean wrote = false;
            try { sink.write(prepared.batch()); wrote = true;
                var found = sink.read(prepared.batch().scope());
                var readback = new WorkflowLogWindow.Batch(plan.proof().scope(), plan.from(), plan.till(), plan.proof().inputCount(), plan.proof().filtered(), found);
                if (!plan.proof().matches(readback)) return finish(p, original, "UNKNOWN", "OUTPUT_UNCONFIRMED");
            }
            catch (WorkflowLogOutputService.OutputFailure failure) { return finish(p, original, wrote || failure.unknown() ? "UNKNOWN" : "FAILED", wrote || failure.unknown() ? "OUTPUT_UNCONFIRMED" : "OUTPUT_REJECTED"); }
            catch (RuntimeException unknown) { return finish(p, original, "UNKNOWN", "OUTPUT_UNCONFIRMED"); }
            return finish(p, original, "CONFIRMED", null);
        } finally { budget.release(); }
    }
    public Receipt executionForPlan(Principal p, UUID id) {
        var selected = plan(p, id);
        var result = store.transaction(p.tenantId(), s -> s.logReplayReceiptForPlan(p.subjectId().value(), selected.requestId()));
        return result.isEmpty() ? null : receipt(p, result.get().requestId());
    }
    /** Authorized explicit output view. No source read, write, receipt refinement or checkpoint mutation. */
    public WorkflowLogStreamService.Data data(Principal p, UUID id, int afterIndex) {
        var selected = plan(p, id); var output = executionForPlan(p, id);
        if (output == null || selected.proof() == null || afterIndex < -1 || afterIndex >= MAX_POINTS
            || afterIndex != -1 && !selected.proof().indices().contains(afterIndex)) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        if (!budget.tryAcquire()) throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            var q = selected.proof(); var rows = q.indices().isEmpty() ? List.<WorkflowLogWindow.Record>of() : sink.read(q.scope());
            // Fail closed for a foreign position/index, even if the transport returned a well-formed row.
            for (var row : rows) if (!q.indices().contains(row.index()) || !q.positions().get(row.index()).equals(row.position())) throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
            store.transaction(p.tenantId(), s -> { scope(p, s, selected); return null; });
            var complete = rows.stream().map(WorkflowLogWindow.Record::index).toList().equals(q.indices())
                && WorkflowLogWindow.recordsDigest(q.scope(), selected.from(), selected.till(), q.inputCount(), q.filtered(), rows).equals(q.batchDigest());
            var remaining = rows.stream().filter(r -> r.index() > afterIndex).toList(); var page = remaining.stream().limit(50).toList();
            return new WorkflowLogStreamService.Data(id, now(), "clickhouse", q.indices().size(), complete, afterIndex, remaining.size() > 50 ? page.getLast().index() : null, page);
        } catch (WorkflowLogOutputService.OutputFailure unavailable) { throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE); }
        finally { budget.release(); }
    }
    public Receipt verify(Principal p, UUID id) {
        var original = receipt(p, id); if (Set.of("CONFIRMED", "FAILED").contains(original.state())) return original;
        // A current foreground attempt can still be reading or writing. Do not classify it from an early empty read.
        if (original.state().equals("PENDING") && now().isBefore(original.acceptedAt().plusSeconds(180))) return original;
        var plan = plan(p, original.planId()); if (!budget.tryAcquire()) throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            try { var q = plan.proof(); var rows = q.indices().isEmpty() ? List.<WorkflowLogWindow.Record>of() : sink.read(q.scope());
                var batch = new WorkflowLogWindow.Batch(q.scope(), plan.from(), plan.till(), q.inputCount(), q.filtered(), rows);
                if (q.matches(batch)) return finish(p, original, "CONFIRMED", null); }
            catch (RuntimeException unavailable) { /* Keep the original proof and do not resend. */ }
            return finish(p, original, "UNKNOWN", "OUTPUT_UNCONFIRMED");
        } finally { budget.release(); }
    }
    private Receipt finish(Principal p, Receipt original, String state, String error) {
        return store.transaction(p.tenantId(), s -> { var current = s.logReplayReceipt(p.subjectId().value(), original.requestId()).orElseThrow();
            if (Set.of("CONFIRMED", "FAILED").contains(current.state()) || current.state().equals("UNKNOWN") && state.equals("FAILED")) return current;
            var time = now().isBefore(current.updatedAt()) ? current.updatedAt() : now(); var result = current.finish(state, error, time); s.finishLogReplayReceipt(p.subjectId().value(), result); return result; });
    }
}
