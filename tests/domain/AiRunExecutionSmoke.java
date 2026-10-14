import com.acme.opsweave.aicontrol.api.*;
import com.acme.opsweave.aicontrol.application.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.aicontrol.infrastructure.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.incident.infrastructure.InMemoryIncidentStore;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.*;
import java.util.*;

/**
 * AIRun execution semantics (ADR-104 5b): the executor drives the run lifecycle under lease and
 * fencing, cooperative cancellation lands at safe points, recovery is a new attempt on the same run
 * after re-authorization, and unknown completion states are reconciled against the model fee receipt
 * and the saved AIInsight before anything is claimed or re-run. Duplicate fees are disclosed
 * explicitly and exactly-once across an external model provider is never claimed.
 *
 * <p>The insight store and the diagnosis dispatch below are <b>explicit test fixtures</b> (labelled
 * {@code Fixture}): the real chain is the M4 synchronous diagnosis and its persistence is covered by
 * the PostgreSQL IT. No model call happens here.
 */
public class AiRunExecutionSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("AI run execution check " + checks); }
    static void invalid(Runnable action) {
        checks++;
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }
    static void fails(ToolFailure.Code code, Runnable action) {
        checks++;
        try { action.run(); }
        catch (ToolFailure failure) { if (failure.code() == code) return; throw new AssertionError(failure.code() + " != " + code); }
        catch (IncidentFailure failure) { if (code.name().equals(failure.code().name())) return; throw new AssertionError(failure.code() + " != " + code); }
        throw new AssertionError("Expected " + code + " " + checks);
    }
    static void conflict(String code, Runnable action) {
        checks++;
        try { action.run(); } catch (AiRun.Conflict failure) { if (failure.code().equals(code)) return; throw new AssertionError(failure.code() + " != " + code); }
        throw new AssertionError("Expected " + code + " " + checks);
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-13T01:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    /** Explicit test fixture: a saved complete result keyed by run id, nothing else. */
    static final class FixtureInsightStore implements AiInsightStore {
        final Map<String, AiInsight> saved = new HashMap<>();
        static String key(TenantId tenant, java.util.UUID runId) { return tenant.value() + "|" + runId; }
        public Optional<AiInsight> find(TenantId tenant, java.util.UUID id) { return Optional.ofNullable(saved.get(key(tenant, id))); }
        public AiInsight save(AiInsight insight) { saved.put(key(insight.tenantId(), insight.input().runId()), insight); return insight; }
    }

    /** Explicit test fixture: the diagnosis chain stand-in. One dispatch = at most one model call. */
    static final class FixtureDispatch implements AiRunDispatch {
        String mode = "ok";
        int calls;
        AiRun.Status seenAtDispatch;
        Runnable during;
        final FixtureInsightStore insights;
        final TenantId tenant;
        final com.acme.opsweave.identity.domain.SubjectId subject;
        final java.util.UUID incident;
        final ToolWindow window;
        FixtureDispatch(FixtureInsightStore insights, TenantId tenant, com.acme.opsweave.identity.domain.SubjectId subject,
                java.util.UUID incident, ToolWindow window) {
            this.insights = insights; this.tenant = tenant; this.subject = subject; this.incident = incident; this.window = window;
        }
        public void dispatch(AiRun.Run run, com.acme.opsweave.identity.domain.Principal as) {
            calls++;
            seenAtDispatch = run.status();
            if (during != null) during.run();
            if ("fail".equals(mode)) throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);
            if ("deadline".equals(mode)) throw new ToolFailure(ToolFailure.Code.DEADLINE);
            if ("save-then-fail".equals(mode)) {
                insights.save(fixtureInsight(run.runId()));
                throw new ToolFailure(ToolFailure.Code.DEADLINE);
            }
            if (!"none".equals(mode)) insights.save(fixtureInsight(run.runId()));
        }
        AiInsight fixtureInsight(java.util.UUID runId) {
            var evidence = List.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
            var draft = new InsightDraft("Fixture summary",
                List.of(new InsightDraft.Finding("observation", "Fixture observed", List.of(evidence.get(0)))), List.of(), List.of());
            var at = Instant.parse("2026-10-13T01:00:30Z");
            var input = new InsightSubmission(runId, java.util.UUID.randomUUID(), "Summarize the incident", at.minusSeconds(2),
                at.minusSeconds(1), at, new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64)),
                new InsightSubmission.ModelRef("mock-deterministic", "mock-current-v1"), evidence, draft);
            return new AiInsight(tenant, subject, incident, 1, Set.of(), window, input, "sha256:" + "a".repeat(64), at,
                at.plusSeconds(3600), List.of("labeled-fixture"), List.of("CURRENT_KNOWLEDGE_ONLY"));
        }
    }

    static ModelSpend.Call fee(TenantId tenant, com.acme.opsweave.identity.domain.SubjectId subject, java.util.UUID runId,
            java.util.UUID incident, Instant reservedAt) {
        return new ModelSpend.Call(tenant, subject, runId, java.util.UUID.randomUUID(), incident,
            "sha256:" + "b".repeat(64), 32, ModelSpend.Policy.mock(), reservedAt, reservedAt.plusSeconds(60), null, null);
    }

    public static void main(String[] args) {
        var clock = new TestClock();
        var tenant = new TenantId("ai-run-exec-domain");
        var user = new com.acme.opsweave.identity.domain.SubjectId("reader");
        var p = new Principal(user, tenant, Set.of(Permission.AI_DIAGNOSE, Permission.AI_INSIGHT_READ, Permission.INCIDENT_READ,
            Permission.ENTITY_READ, Permission.METRIC_READ, Permission.EVIDENCE_READ), ResourceScope.tenantWide());
        var auth = new AuthorizeUseCase();
        var incidents = new InMemoryIncidentStore((t, s, h) -> Map.of());
        var observation = new ExternalProblem(tenant, "zabbix-1", "101", "201", "Fixture incident", 3,
            clock.now.minusSeconds(30), clock.now, List.of(), false, null, null);
        incidents.ingest(tenant, "zabbix-1", "labeled-fixture", List.of(observation), clock.now);
        var incident = IncidentRecord.initialId(observation);
        var incidentService = new IncidentService(auth, incidents, clock);
        var store = new InMemoryAiRunStore();
        var service = new AiRunService(auth, incidentService, store, clock);
        var insightStore = new FixtureInsightStore();
        var spend = new InMemoryModelSpendStore();
        var skill = new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64));
        var window = new ToolWindow(clock.now.minusSeconds(600), clock.now.minusSeconds(300));
        var dispatch = new FixtureDispatch(insightStore, tenant, user, incident, window);
        var execution = new AiRunExecutionService(store, insightStore, spend, dispatch, auth, incidentService, clock, "worker-a");
        var control = new AiRunControlService(store, insightStore, spend, execution, auth, incidentService, clock);

        // ---- The executor drives the full lifecycle: PENDING -> RUNNING -> WAITING_TOOL -> RUNNING -> SUCCEEDED.
        var command = new AiRun.Command(java.util.UUID.randomUUID(), incident, "Summarize the incident", window, "current", skill);
        var run = service.submit(p, command);
        check(run.status() == AiRun.Status.PENDING && run.statusVersion() == 0 && run.attempt() == 0);
        var done = execution.execute(tenant, run.runId(), p).orElseThrow();
        check(done.status() == AiRun.Status.SUCCEEDED && done.attempt() == 1);
        check(dispatch.seenAtDispatch == AiRun.Status.WAITING_TOOL);
        check(dispatch.calls == 1 && insightStore.saved.size() == 1);
        check(done.reason() == null && done.disclosure().decision() == AiRunControl.Decision.RERUN);
        check(done.disclosure().priorModelCalls() == 0 && !done.disclosure().duplicateFeeRisk());
        check(store.find(tenant, run.runId()).orElseThrow().statusVersion() == done.statusVersion());
        // A terminal run is never claimed again: one run, no second execution.
        check(execution.execute(tenant, run.runId(), p).isEmpty());
        check(dispatch.calls == 1 && store.find(tenant, run.runId()).orElseThrow().attempt() == 1);

        // ---- Only SUCCEEDED carries a conclusion: a failed chain leaves no AIInsight behind.
        dispatch.mode = "fail";
        var failed = execution.execute(tenant, service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident,
            "Second question", window, "current", skill)).runId(), p).orElseThrow();
        check(failed.status() == AiRun.Status.FAILED && failed.reason() == AiRun.Reason.RUNTIME_UNAVAILABLE);
        check(insightStore.saved.size() == 1);
        // A complete saved result is claimed even when the dispatch reports failure afterwards.
        dispatch.mode = "save-then-fail";
        var adopted = execution.execute(tenant, service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident,
            "Third question", window, "current", skill)).runId(), p).orElseThrow();
        check(adopted.status() == AiRun.Status.SUCCEEDED && insightStore.saved.size() == 2);
        dispatch.mode = "ok";

        // ---- Cooperative cancellation: the request does not occupy the state machine.
        var cancelTarget = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Cancel me",
            window, "current", skill));
        var cancelCommand = new AiRunControl.Command(AiRunControl.Kind.CANCEL, java.util.UUID.randomUUID(), cancelTarget.runId());
        var cancelReceipt = control.cancel(p, cancelTarget.runId(), cancelCommand);
        check(cancelReceipt.recorded() && cancelReceipt.status() == AiRun.Status.PENDING);
        var afterCancel = store.find(tenant, cancelTarget.runId()).orElseThrow();
        check(afterCancel.status() == AiRun.Status.PENDING && afterCancel.statusVersion() == 0
            && afterCancel.cancelRequestedAt() != null);
        // The executor lands CANCELLED at its safe point before any model or tool work.
        int callsBefore = dispatch.calls;
        var cancelled = execution.execute(tenant, cancelTarget.runId(), p).orElseThrow();
        check(cancelled.status() == AiRun.Status.CANCELLED && cancelled.reason() == AiRun.Reason.CANCELLED_BY_REQUEST);
        check(dispatch.calls == callsBefore && insightStore.saved.size() == 2);
        // Replays answer with the original receipt; a new command on a terminal run changes nothing.
        check(control.cancel(p, cancelTarget.runId(), cancelCommand).equals(cancelReceipt));
        var late = control.cancel(p, cancelTarget.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, java.util.UUID.randomUUID(), cancelTarget.runId()));
        check(!late.recorded() && late.status() == AiRun.Status.CANCELLED);

        // ---- Cancel racing the dispatch: it lands CANCELLED at the post-dispatch safe point.
        var raced = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Cancel mid-dispatch",
            window, "current", skill));
        dispatch.mode = "deadline";
        dispatch.during = () -> control.cancel(p, raced.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, java.util.UUID.randomUUID(), raced.runId()));
        var racedDone = execution.execute(tenant, raced.runId(), p).orElseThrow();
        check(racedDone.status() == AiRun.Status.CANCELLED && racedDone.reason() == AiRun.Reason.CANCELLED_BY_REQUEST);
        check(racedDone.disclosure() != null && racedDone.disclosure().decision() == AiRunControl.Decision.RERUN);
        // The same race with a successful dispatch still ends SUCCEEDED: a completed result is never masked.
        var tooLate = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Cancel too late",
            window, "current", skill));
        dispatch.mode = "ok";
        dispatch.during = () -> control.cancel(p, tooLate.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, java.util.UUID.randomUUID(), tooLate.runId()));
        var tooLateDone = execution.execute(tenant, tooLate.runId(), p).orElseThrow();
        check(tooLateDone.status() == AiRun.Status.SUCCEEDED);
        dispatch.during = null;

        // ---- Unknown completion state: a saved result from a crashed attempt is claimed, not re-run.
        var crashed = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Crashed after save",
            window, "current", skill));
        var first = store.claim(tenant, crashed.runId(), "worker-a", clock.now,
            AiRunExecutionService.LEASE, AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        check(first.attempt() == 1 && first.run().status() == AiRun.Status.RUNNING);
        insightStore.save(dispatch.fixtureInsight(crashed.runId()));
        clock.now = clock.now.plusSeconds(300); // the worker died; its lease expired
        int callsBeforeRecover = dispatch.calls;
        var recovered = execution.execute(tenant, crashed.runId(), p).orElseThrow();
        check(recovered.status() == AiRun.Status.SUCCEEDED && recovered.attempt() == 2);
        check(recovered.disclosure().adoptedPriorResult() && recovered.disclosure().decision() == AiRunControl.Decision.ADOPT);
        check(dispatch.calls == callsBeforeRecover && insightStore.saved.size() >= 4);

        // ---- Unknown completion state without a result: the fee is disclosed, never silently re-run.
        var lost = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Crashed after fee",
            window, "current", skill));
        store.claim(tenant, lost.runId(), "worker-a", clock.now, AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        spend.reserve(fee(tenant, user, lost.runId(), incident, clock.now.minusSeconds(300)));
        clock.now = clock.now.plusSeconds(300);
        callsBeforeRecover = dispatch.calls;
        var lostDone = execution.execute(tenant, lost.runId(), p).orElseThrow();
        check(lostDone.status() == AiRun.Status.FAILED && lostDone.reason() == AiRun.Reason.RESULT_LOST);
        check(lostDone.disclosure().priorModelCalls() == 1 && lostDone.disclosure().uncertainPriorFees() == 1);
        check(lostDone.disclosure().duplicateFeeRisk() && lostDone.disclosure().decision() == AiRunControl.Decision.RESULT_LOST);
        check(dispatch.calls == callsBeforeRecover); // the one-call-per-diagnosis invariant: no second provider call
        check(spend.find(tenant, lost.runId()).isPresent()); // the fee is retained, never refunded
        // A recovery of the same unknown state discloses the same counts in its receipt.
        conflict("AI_RUN_STATE_CONFLICT", () -> control.retry(p, lost.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, java.util.UUID.randomUUID(), lost.runId())));

        // ---- Recovery of an unfinished run: same runId, new attempt, re-authorized first.
        var stuck = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Crashed before work",
            window, "current", skill));
        store.claim(tenant, stuck.runId(), "worker-a", clock.now, AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        clock.now = clock.now.plusSeconds(300);
        var retryCommand = new AiRunControl.Command(AiRunControl.Kind.RETRY, java.util.UUID.randomUUID(), stuck.runId());
        var retryReceipt = control.retry(p, stuck.runId(), retryCommand);
        check(retryReceipt.attempt() == 2 && retryReceipt.status() == AiRun.Status.SUCCEEDED);
        check(retryReceipt.reconciliation().rerun() && retryReceipt.reconciliation().priorModelCalls() == 0);
        check(store.find(tenant, stuck.runId()).orElseThrow().status() == AiRun.Status.SUCCEEDED);
        check(control.retry(p, stuck.runId(), retryCommand).equals(retryReceipt)); // idempotent replay
        conflict("AI_RUN_REQUEST_REUSED", () -> control.retry(p, run.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, retryCommand.requestId(), run.runId())));
        // A live lease owns the run: a second worker is never handed the same attempt.
        var busy = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Busy run",
            window, "current", skill));
        store.claim(tenant, busy.runId(), "worker-a", clock.now, AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        conflict("AI_RUN_STATE_CONFLICT", () -> control.retry(p, busy.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, java.util.UUID.randomUUID(), busy.runId())));
        // Terminal runs are final: recovery targets unfinished runs only.
        conflict("AI_RUN_STATE_CONFLICT", () -> control.retry(p, run.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, java.util.UUID.randomUUID(), run.runId())));

        // ---- Re-authorization before recovery: withdrawn authority is rejected and nothing runs.
        var revoked = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Revoked owner",
            window, "current", skill));
        store.claim(tenant, revoked.runId(), "worker-a", clock.now, AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        clock.now = clock.now.plusSeconds(300);
        var denied = new Principal(user, tenant, Set.of(Permission.AI_INSIGHT_READ), ResourceScope.tenantWide());
        fails(ToolFailure.Code.FORBIDDEN, () -> control.retry(denied, revoked.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, java.util.UUID.randomUUID(), revoked.runId())));
        check(store.find(tenant, revoked.runId()).orElseThrow().attempt() == 1);
        int callsBeforeDenied = dispatch.calls;
        var deniedRun = execution.execute(tenant, revoked.runId(), denied).orElseThrow();
        check(deniedRun.status() == AiRun.Status.FAILED && deniedRun.reason() == AiRun.Reason.AUTHORIZATION_REVOKED);
        check(dispatch.calls == callsBeforeDenied);

        // ---- Fencing: a worker that lost its lease commits nothing.
        var fenced = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Fenced worker",
            window, "current", skill));
        var stale = store.claim(tenant, fenced.runId(), "worker-a", clock.now, AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        clock.now = clock.now.plusSeconds(300);
        var fresh = store.claim(tenant, fenced.runId(), "worker-b", clock.now, AiRunExecutionService.LEASE,
            AiRunExecutionService.ATTEMPT_DEADLINE).orElseThrow();
        check(fresh.fence() > stale.fence() && fresh.attempt() == 2);
        check(store.advance(tenant, fenced.runId(), stale.run().statusVersion(), stale.fence(),
            new AiRun.Transition(AiRun.Status.FAILED, AiRun.Reason.EXECUTION_FAILED, null, clock.now)).isEmpty());
        check(store.advance(tenant, fenced.runId(), fresh.run().statusVersion(), fresh.fence(),
            AiRun.Transition.to(AiRun.Status.SUCCEEDED, clock.now)).isPresent());
        check(store.find(tenant, fenced.runId()).orElseThrow().status() == AiRun.Status.SUCCEEDED);

        // ---- Terminal states never move: no claim, no transition, no cancellation recording.
        for (var terminal : List.of(run.runId(), failed.runId(), cancelled.runId())) {
            var value = store.find(tenant, terminal).orElseThrow();
            check(value.status().terminal());
            check(store.claim(tenant, terminal, "worker-a", clock.now, AiRunExecutionService.LEASE,
                AiRunExecutionService.ATTEMPT_DEADLINE).isEmpty());
            check(store.requestCancel(tenant, terminal, clock.now).isEmpty());
            conflict("AI_RUN_STATE_CONFLICT", () -> value.transitionTo(AiRun.Status.RUNNING, clock.now.plusSeconds(1)));
            conflict("AI_RUN_STATE_CONFLICT", () -> value.claimed(clock.now.plusSeconds(1)));
            conflict("AI_RUN_STATE_CONFLICT", () -> value.cancelRequested(clock.now.plusSeconds(1)));
        }

        // ---- Control commands are closed and idempotent.
        var target = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Command checks",
            window, "current", skill));
        invalid(() -> new AiRunControl.Command(null, java.util.UUID.randomUUID(), target.runId()));
        invalid(() -> new AiRunControl.Command(AiRunControl.Kind.CANCEL, java.util.UUID.randomUUID(), null));
        fails(ToolFailure.Code.INVALID_REQUEST, () -> control.cancel(p, target.runId(),
            new AiRunControl.Command(AiRunControl.Kind.RETRY, java.util.UUID.randomUUID(), target.runId())));
        fails(ToolFailure.Code.INVALID_REQUEST, () -> control.cancel(p, run.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, java.util.UUID.randomUUID(), target.runId())));
        var unknownRun = java.util.UUID.randomUUID();
        fails(ToolFailure.Code.NOT_FOUND, () -> control.cancel(p, unknownRun,
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, java.util.UUID.randomUUID(), unknownRun)));
        var stranger = new Principal(new com.acme.opsweave.identity.domain.SubjectId("stranger"), tenant, p.permissions(),
            ResourceScope.tenantWide());
        fails(ToolFailure.Code.NOT_FOUND, () -> control.cancel(stranger, target.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, java.util.UUID.randomUUID(), target.runId())));
        // A request id is the idempotency key of exactly one command family.
        conflict("AI_RUN_REQUEST_REUSED", () -> control.cancel(p, target.runId(),
            new AiRunControl.Command(AiRunControl.Kind.CANCEL, command.requestId(), target.runId())));

        // ---- The reconciliation disclosure is a closed, explicitly checked object.
        check(AiRunControl.reconcile(true, 1, 1).decision() == AiRunControl.Decision.ADOPT);
        check(AiRunControl.reconcile(false, 1, 0).decision() == AiRunControl.Decision.RESULT_LOST);
        check(AiRunControl.reconcile(false, 0, 0).decision() == AiRunControl.Decision.RERUN);
        check(AiRunControl.Reconciliation.clean().decision() == AiRunControl.Decision.RERUN);
        invalid(() -> new AiRunControl.Reconciliation(true, true, 0, 0, false));
        invalid(() -> new AiRunControl.Reconciliation(false, true, 1, 0, false));
        invalid(() -> new AiRunControl.Reconciliation(false, false, 1, 2, true));
        invalid(() -> new AiRunControl.Reconciliation(false, false, 0, 0, true));
        invalid(() -> new AiRunControl.Reconciliation(false, false, 9, 0, true));

        // ---- The poller sweep claims recoverable runs of this owner (stuck lease and pending alike).
        dispatch.mode = "ok";
        var pendingOne = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Sweep one",
            window, "current", skill));
        var pendingTwo = service.submit(p, new AiRun.Command(java.util.UUID.randomUUID(), incident, "Sweep two",
            window, "current", skill));
        var swept = execution.tick(p, 10);
        check(swept.size() == 4); // the expired-lease run, the unexecuted pending run and the two new ones
        check(store.find(tenant, pendingOne.runId()).orElseThrow().status() == AiRun.Status.SUCCEEDED);
        check(store.find(tenant, pendingTwo.runId()).orElseThrow().status() == AiRun.Status.SUCCEEDED);
        check(store.find(tenant, busy.runId()).orElseThrow().status() == AiRun.Status.SUCCEEDED);
        check(store.find(tenant, target.runId()).orElseThrow().status() == AiRun.Status.SUCCEEDED);
        check(execution.tick(p, 10).isEmpty());
        fails(ToolFailure.Code.INVALID_REQUEST, () -> execution.tick(p, 0));

        // ---- Storage-level transition metadata rules.
        invalid(() -> new AiRun.Transition(AiRun.Status.WAITING_TOOL, AiRun.Reason.DEADLINE, null, clock.now));
        invalid(() -> new AiRun.Transition(AiRun.Status.SUCCEEDED, AiRun.Reason.DEADLINE, null, clock.now));
        invalid(() -> new AiRun.Transition(AiRun.Status.FAILED, null, null, clock.now));
        invalid(() -> new AiRun.Transition(AiRun.Status.RUNNING, null, AiRunControl.Reconciliation.clean(), clock.now));
        check(new AiRun.Transition(AiRun.Status.FAILED, AiRun.Reason.EXECUTION_FAILED, AiRunControl.Reconciliation.clean(),
            clock.now).next() == AiRun.Status.FAILED);

        System.out.println("AiRunExecutionSmoke: " + checks + " checks passed");
    }
}
