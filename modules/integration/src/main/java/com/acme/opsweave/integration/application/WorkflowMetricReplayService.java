package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricReplay.*;
import static com.acme.opsweave.integration.domain.WorkflowMetricReplay.*;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Foreground reconstruction using the existing bounded source and sink. No scheduler, notifications or actions. */
public final class WorkflowMetricReplayService {
    private final WorkflowStore store;
    private final WorkflowService workflows;
    private final WorkflowMetricStreamService.Source source;
    private final WorkflowMetricOutputService.Sink sink;
    private final Semaphore budget;
    private final Clock clock;
    private record Selection(WorkflowStore.Entry entry, WorkflowExecutionPlan plan) {}
    private record Prepared(Proof proof, MetricWriteBatch batch) {}

    public WorkflowMetricReplayService(WorkflowStore store, WorkflowService workflows, WorkflowMetricStreamService.Source source,
                                      WorkflowMetricOutputService.Sink sink, Semaphore budget, Clock clock) {
        this.store = store; this.workflows = workflows; this.source = source; this.sink = sink; this.budget = budget; this.clock = clock;
    }
    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private void authorize(Principal p, WorkflowQuality.Reference ref) {
        workflows.authorize(p); var auth = new Authorizer(); var resource = new ResourceRef(p.tenantId(), "workflow", ref.id());
        if (auth.decide(p, resource, Permission.WORKFLOW_REPLAY).denied() || auth.decide(p, resource, Permission.SOURCE_SYNC).denied()) throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
    }
    private Selection selection(Principal p, WorkflowStore.Session s, WorkflowQuality.Reference ref, boolean execute) {
        authorize(p, ref); var e = workflows.accessibleVersion(p, s, ref.id(), ref.revision());
        if (!e.digest().equals(ref.digest())) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        var d = e.definition(); if (!d.source().kind().equals("ZABBIX_METRIC") || d.source().configuration() == null || d.source().metric() == null || d.target().mappingPin() == null) throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        return new Selection(e, execute ? workflows.executionPlan(p, s, e) : null);
    }
    private void scope(Principal p, WorkflowStore.Session s, Plan plan) {
        var d = selection(p, s, plan.reference(), false).entry().definition(); if (plan.proof() == null) return;
        var l = plan.proof().labels();
        if (!p.tenantId().value().equals(l.get("tenant_id")) || !WorkflowDefinition.hash(List.of(p.subjectId().value())).substring(7).equals(l.get("owner_scope"))
            || !d.source().instanceId().equals(l.get("source_instance_id")) || !d.source().metric().itemId().equals(l.get("external_item_id")) || !d.source().metric().hostId().equals(l.get("host_external_id"))
            || !d.source().configuration().digest().equals(l.get("configuration_digest")) || !d.target().metricKey().equals(l.get("metric_key"))
            || !d.target().mappingPin().id().equals(l.get("mapping_id")) || !Integer.toString(d.target().mappingPin().revision()).equals(l.get("mapping_revision")) || !d.target().mappingPin().digest().equals(l.get("mapping_digest"))) throw new IllegalStateException("Replay scope proof unavailable");
    }
    public Plan plan(Principal p, UUID id) {
        workflows.authorize(p); return store.transaction(p.tenantId(), s -> { var r = s.metricReplayPlan(p.subjectId().value(), id).orElseThrow(() -> new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND)); scope(p, s, r); return r; });
    }
    public WorkflowService.Page<Plan> plans(Principal p, WorkflowQuality.Reference ref) {
        return store.transaction(p.tenantId(), s -> { selection(p, s, ref, false); var rows = s.metricReplayPlans(p.subjectId().value(), ref); rows.forEach(r -> scope(p, s, r)); return new WorkflowService.Page<>(rows.subList(0, Math.min(20, rows.size())), rows.size() > 20); });
    }
    private Prepared prepare(Principal p, Plan plan) {
        var selection = store.transaction(p.tenantId(), s -> selection(p, s, plan.reference(), true));
        var d = selection.entry().definition(); var rows = source.read(p, d.source(), plan.from(), plan.till());
        if (rows == null || rows.size() > MAX_POINTS) throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
        var prepared = WorkflowMetricBatches.prepare(p, d, selection.plan(), rows, plan.from(), plan.till());
        var labels = new HashMap<>(prepared.batch().labels()); labels.put("collection_mode", "REPLAY_60S"); labels.put("replay_id", plan.requestId().toString());
        var batch = new MetricWriteBatch(labels, prepared.batch().samples(), prepared.batch().collapsedPoints());
        return new Prepared(new Proof(WorkflowService.configuredInputDigest(d.source(), rows, null), WorkflowMetricOutput.batchDigest(batch), rows.size(), prepared.filtered(), batch.collapsedPoints(), labels, batch.samples().stream().map(MetricWriteBatch.Sample::timestampMillis).toList()), batch);
    }
    private String error(RuntimeException failure) {
        if (failure instanceof WorkflowFailure f && ERRORS.contains(f.code().name())) return f.code().name();
        return failure instanceof IllegalArgumentException ? "INVALID_SAMPLE" : "SOURCE_UNAVAILABLE";
    }
    public Plan create(Principal p, Command c) {
        authorize(p, c.reference()); var prior = store.transaction(p.tenantId(), s -> s.metricReplayPlan(p.subjectId().value(), c.requestId()));
        if (prior.isPresent()) { prior.get().require(c); return plan(p, c.requestId()); }
        if (!budget.tryAcquire()) throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            var admitted = store.transaction(p.tenantId(), s -> { var existing = s.metricReplayPlan(p.subjectId().value(), c.requestId()); if (existing.isPresent()) { existing.get().require(c); return false; }
                selection(p, s, c.reference(), true); c.requireWindow(now()); if (s.metricReplayPlanCount(p.subjectId().value()) >= MAX_PLANS) throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
                s.addMetricReplayPlan(p.subjectId().value(), Plan.preparing(c, now())); return true; });
            if (!admitted) return plan(p, c.requestId());
            var original = plan(p, c.requestId()); Prepared prepared;
            try { prepared = prepare(p, original); store.transaction(p.tenantId(), s -> { selection(p, s, c.reference(), true); return null; }); }
            catch (RuntimeException failure) { return finishPlan(p, original, null, error(failure)); }
            return finishPlan(p, original, prepared.proof(), null);
        } finally { budget.release(); }
    }
    private Plan finishPlan(Principal p, Plan original, Proof proof, String error) {
        return store.transaction(p.tenantId(), s -> { var current = s.metricReplayPlan(p.subjectId().value(), original.requestId()).orElseThrow(); if (!current.state().equals("PREPARING")) return current;
            var result = current.finish(proof, error, now().isBefore(current.updatedAt()) ? current.updatedAt() : now()); s.finishMetricReplayPlan(p.subjectId().value(), result); return result; });
    }
    public Receipt receipt(Principal p, UUID id) {
        workflows.authorize(p); return store.transaction(p.tenantId(), s -> { var r = s.metricReplayReceipt(p.subjectId().value(), id).orElseThrow(() -> new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
            var plan = s.metricReplayPlan(p.subjectId().value(), r.planId()).orElseThrow(); scope(p, s, plan); if (!r.reference().equals(plan.reference()) || !r.commandDigest().equals(new Execute(r.requestId(), plan.requestId(), plan.proof().inputDigest(), plan.proof().batchDigest()).commandDigest())) throw new IllegalStateException(); return r; });
    }
    public Receipt execute(Principal p, Execute c) {
        var plan = plan(p, c.planId()); c.require(plan); var prior = store.transaction(p.tenantId(), s -> s.metricReplayReceipt(p.subjectId().value(), c.requestId()));
        if (prior.isPresent()) { prior.get().require(c); return receipt(p, c.requestId()); }
        if (!budget.tryAcquire()) throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            var admitted = store.transaction(p.tenantId(), s -> { var existing = s.metricReplayReceipt(p.subjectId().value(), c.requestId()); if (existing.isPresent()) { existing.get().require(c); return false; }
                if (s.metricReplayReceiptForPlan(p.subjectId().value(), plan.requestId()).isPresent()) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
                selection(p, s, plan.reference(), true); plan.requireExecutable(now()); c.require(plan);
                s.addMetricReplayReceipt(p.subjectId().value(), new Receipt("2.0", c.requestId(), plan.requestId(), plan.reference(), c.commandDigest(), now(), now(), "PENDING", null)); return true; });
            if (!admitted) return receipt(p, c.requestId());
            var original = receipt(p, c.requestId()); Prepared prepared;
            try { plan.requireExecutable(now()); prepared = prepare(p, plan); if (!plan.proof().equals(prepared.proof())) return finish(p, original, "FAILED", "SOURCE_WINDOW_CHANGED");
                store.transaction(p.tenantId(), s -> { selection(p, s, plan.reference(), true); plan.requireExecutable(now()); return null; }); }
            catch (RuntimeException failure) { return finish(p, original, "FAILED", now().isBefore(plan.expiresAt()) ? error(failure) : "WINDOW_EXPIRED"); }
            if (prepared.batch().samples().isEmpty()) return finish(p, original, "CONFIRMED", null);
            try { sink.write(prepared.batch()); }
            catch (WorkflowMetricOutputService.OutputFailure failure) { return finish(p, original, failure.unknown() ? "UNKNOWN" : "FAILED", failure.unknown() ? "OUTPUT_UNCONFIRMED" : "OUTPUT_REJECTED"); }
            catch (RuntimeException unknown) { return finish(p, original, "UNKNOWN", "OUTPUT_UNCONFIRMED"); }
            return finish(p, original, "CONFIRMED", null);
        } finally { budget.release(); }
    }
    public Receipt executionForPlan(Principal p, UUID id) {
        var selected = plan(p, id);
        var result = store.transaction(p.tenantId(), s -> s.metricReplayReceiptForPlan(p.subjectId().value(), selected.requestId()));
        return result.isEmpty() ? null : receipt(p, result.get().requestId());
    }
    public Receipt verify(Principal p, UUID id) {
        var original = receipt(p, id); if (Set.of("CONFIRMED", "FAILED").contains(original.state())) return original;
        // A current foreground attempt can still be reading or writing. Do not classify it from an early empty read.
        if (original.state().equals("PENDING") && now().isBefore(original.acceptedAt().plusSeconds(180))) return original;
        var plan = plan(p, original.planId()); if (!budget.tryAcquire()) throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            try { var batch = new MetricWriteBatch(plan.proof().labels(), plan.proof().timestamps().isEmpty() ? List.of() : sink.read(plan.proof().labels(), plan.proof().timestamps()), 0);
                if (plan.proof().matches(batch)) return finish(p, original, "CONFIRMED", null); }
            catch (RuntimeException unavailable) { /* Keep the original proof and do not resend. */ }
            return finish(p, original, "UNKNOWN", "OUTPUT_UNCONFIRMED");
        } finally { budget.release(); }
    }
    private Receipt finish(Principal p, Receipt original, String state, String error) {
        return store.transaction(p.tenantId(), s -> { var current = s.metricReplayReceipt(p.subjectId().value(), original.requestId()).orElseThrow();
            if (Set.of("CONFIRMED", "FAILED").contains(current.state()) || current.state().equals("UNKNOWN") && state.equals("FAILED")) return current;
            var time = now().isBefore(current.updatedAt()) ? current.updatedAt() : now(); var result = current.finish(state, error, time); s.finishMetricReplayReceipt(p.subjectId().value(), result); return result; });
    }
}
