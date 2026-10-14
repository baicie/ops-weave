package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.AiInsightStore;
import com.acme.opsweave.aicontrol.api.AiRunDispatch;
import com.acme.opsweave.aicontrol.api.AiRunStore;
import com.acme.opsweave.aicontrol.api.ModelSpendStore;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunControl;
import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The AIRun executor (M5 5b, ADR-104 §2.3/§2.4): one claimed attempt drives the existing
 * synchronous diagnosis chain through the run lifecycle — {@code PENDING -> RUNNING}, the tool wait
 * {@code WAITING_TOOL <-> RUNNING}, then one terminal landing.
 *
 * <p>Semantics this class guarantees:
 *
 * <ul>
 *   <li><b>No double run after a crash.</b> Every attempt is a lease claim with a fencing token; a
 *       worker that lost its lease commits nothing (the store rejects its token) and the reclaiming
 *       worker takes a new attempt on the same run — never a second run.</li>
 *   <li><b>Unknown completion states are reconciled first</b> (model fee receipt + saved AIInsight)
 *       before claiming or re-running: a saved complete result is claimed ({@code SUCCEEDED}), a
 *       spent fee without a result is disclosed explicitly ({@code RESULT_LOST}, duplicate-fee risk)
 *       and only a clean run re-executes. Exactly-once across an external model provider is never
 *       claimed and fees are never refunded.</li>
 *   <li><b>Cooperative cancellation</b> is observed at safe points (before dispatch, after dispatch)
 *       and lands {@code CANCELLED}; it never fakes or discards a complete result.</li>
 *   <li><b>Only {@code SUCCEEDED} carries the AIInsight conclusion.</b> A saved insight is by
 *       definition a complete chain result (the chain saves only after every check), so whenever one
 *       exists the run lands {@code SUCCEEDED} — partial output is never presented as complete.</li>
 *   <li>One diagnosis still means one model call through the shared model budget; the pinned Skill
 *       and the timeouts are the M4 chain's own and are not relaxed here.</li>
 * </ul>
 */
public final class AiRunExecutionService {
    /** The lease of one claimed attempt. Longer than the dispatch deadline so the run is not stolen mid-call. */
    public static final Duration LEASE = Duration.ofSeconds(90);
    /** The attempt-level deadline made explicit (ADR-104 §2.6); the dispatch itself is bounded tighter. */
    public static final Duration ATTEMPT_DEADLINE = Duration.ofSeconds(65);

    private final AiRunStore store;
    private final AiInsightStore insights;
    private final ModelSpendStore spend;
    private final AiRunDispatch dispatch;
    private final AuthorizationService authorization;
    private final IncidentService incidents;
    private final Clock clock;
    private final String worker;

    public AiRunExecutionService(AiRunStore store, AiInsightStore insights, ModelSpendStore spend, AiRunDispatch dispatch,
            AuthorizationService authorization, IncidentService incidents, Clock clock, String worker) {
        this.store = Objects.requireNonNull(store);
        this.insights = Objects.requireNonNull(insights);
        this.spend = Objects.requireNonNull(spend);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.authorization = Objects.requireNonNull(authorization);
        this.incidents = Objects.requireNonNull(incidents);
        this.clock = Objects.requireNonNull(clock);
        this.worker = Objects.requireNonNull(worker);
        if (worker.isBlank() || worker.length() > 128) throw new IllegalArgumentException("worker id out of bounds");
    }

    public String worker() {
        return worker;
    }

    /**
     * Claim one run and execute the attempt. An empty result means someone else owns the run right
     * now (a live lease, a terminal run or a lost race) — nothing was changed.
     */
    public Optional<AiRun.Run> execute(TenantId tenant, UUID runId, Principal current) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(current, "current");
        var claim = store.claim(tenant, runId, worker, now(), LEASE, ATTEMPT_DEADLINE);
        return claim.isEmpty() ? Optional.empty() : attempt(claim.get(), current);
    }

    /**
     * One claimed attempt: reconcile, then safe points, then (when re-running) the diagnosis chain.
     * Every commit carries the claim's fencing token; an empty result means this worker lost its
     * lease and the reclaiming worker owns the run — the late result is rejected, never applied.
     */
    public Optional<AiRun.Run> attempt(AiRunStore.Claim claim, Principal current) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(current, "current");
        var run = claim.run();
        // Unknown completion state first: fee receipt + saved insight decide claim-or-rerun.
        var disclosure = reconcile(run.tenantId(), run.runId(), now());
        // The reconciliation outcome is recorded beside this attempt (5c): a disclosure record, never
        // an instruction and never hidden reasoning.
        store.append(run.tenantId(), run.runId(), AiRunEvent.Type.RECONCILED, now(),
            AiRunEvent.Payload.reconciliation(disclosure));
        if (disclosure.decision() == AiRunControl.Decision.ADOPT) {
            return land(claim, run, AiRun.Status.SUCCEEDED, null, disclosure);
        }
        // Safe point A: a recorded cancellation stops the run before any model or tool work.
        if (run.cancelRequestedAt() != null) {
            return land(claim, run, AiRun.Status.CANCELLED, AiRun.Reason.CANCELLED_BY_REQUEST, disclosure);
        }
        // Fresh re-authorization of the run owner: identity, permission, object scope, current incident.
        if (!authorized(current, run)) {
            return land(claim, run, AiRun.Status.FAILED, AiRun.Reason.AUTHORIZATION_REVOKED, disclosure);
        }
        // A spent model fee without a saved result is never silently re-run (one call per diagnosis).
        if (disclosure.decision() == AiRunControl.Decision.RESULT_LOST) {
            return land(claim, run, AiRun.Status.FAILED, AiRun.Reason.RESULT_LOST, disclosure);
        }
        return dispatchAttempt(claim, run, current, disclosure);
    }

    /** Claim and execute up to {@code limit} recoverable runs of this owner (poller sweep). */
    public List<AiRun.Run> tick(Principal current, int limit) {
        Objects.requireNonNull(current, "current");
        if (limit < 1 || limit > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        var done = new ArrayList<AiRun.Run>();
        for (var candidate : store.claimable(current.tenantId(), current.subjectId(), now(), limit)) {
            execute(current.tenantId(), candidate.runId(), current).ifPresent(done::add);
        }
        return List.copyOf(done);
    }

    /**
     * The unknown-completion reconciliation (ADR-104 §2.4): query the model fee receipt and the
     * platform AIInsight before deciding. Counts are the explicit duplicate-fee disclosure.
     */
    public static AiRunControl.Reconciliation reconcile(AiInsightStore insights, ModelSpendStore spend,
            TenantId tenant, UUID runId, Instant now) {
        boolean saved = insights.find(tenant, runId).isPresent();
        var fee = spend.find(tenant, runId).orElse(null);
        int priorCalls = fee == null ? 0 : 1;
        int uncertain = fee != null && !"REPORTED".equals(fee.state(now)) ? 1 : 0;
        return AiRunControl.reconcile(saved, priorCalls, uncertain);
    }

    public AiRunControl.Reconciliation reconcile(TenantId tenant, UUID runId, Instant at) {
        return reconcile(insights, spend, tenant, runId, at);
    }

    private Optional<AiRun.Run> dispatchAttempt(AiRunStore.Claim claim, AiRun.Run run, Principal current,
            AiRunControl.Reconciliation disclosure) {
        var waiting = store.advance(run.tenantId(), run.runId(), run.statusVersion(), claim.fence(),
            new AiRun.Transition(AiRun.Status.WAITING_TOOL, null, null, now()));
        if (waiting.isEmpty()) return Optional.empty();
        run = waiting.get();
        ToolFailure failure = null;
        try {
            dispatch.dispatch(run, current);
        } catch (ToolFailure failed) {
            failure = failed;
        }
        var resumed = store.advance(run.tenantId(), run.runId(), run.statusVersion(), claim.fence(),
            AiRun.Transition.to(AiRun.Status.RUNNING, now()));
        if (resumed.isEmpty()) return Optional.empty();
        run = resumed.get();
        // The chain saves only complete results, so a saved insight means the diagnosis finished.
        if (insights.find(run.tenantId(), run.runId()).isPresent()) {
            return land(claim, run, AiRun.Status.SUCCEEDED, null, disclosure);
        }
        // Safe point B: a failed dispatch left no result — cancel first, else fail with the cause.
        if (failure == null) {
            return land(claim, run, AiRun.Status.FAILED, AiRun.Reason.EXECUTION_FAILED, disclosure);
        }
        if (run.cancelRequestedAt() != null) {
            return land(claim, run, AiRun.Status.CANCELLED, AiRun.Reason.CANCELLED_BY_REQUEST, disclosure);
        }
        return land(claim, run, AiRun.Status.FAILED, mapFailure(failure), disclosure);
    }

    /**
     * Land a terminal state under CAS and fencing. A saved AIInsight is a complete conclusion and can
     * only stand beside {@code SUCCEEDED}: whenever one exists, the run lands there — a failure or a
     * cancellation never discards or masks a finished diagnosis.
     */
    private Optional<AiRun.Run> land(AiRunStore.Claim claim, AiRun.Run run, AiRun.Status next, AiRun.Reason reason,
            AiRunControl.Reconciliation disclosure) {
        var at = now();
        if (next != AiRun.Status.SUCCEEDED && insights.find(run.tenantId(), run.runId()).isPresent()) {
            return store.advance(run.tenantId(), run.runId(), run.statusVersion(), claim.fence(),
                new AiRun.Transition(AiRun.Status.SUCCEEDED, null, disclosure, at));
        }
        return store.advance(run.tenantId(), run.runId(), run.statusVersion(), claim.fence(),
            new AiRun.Transition(next, reason, disclosure, at));
    }

    /** The run owner, acting now: identity, permission, object scope and the current incident. */
    private boolean authorized(Principal current, AiRun.Run run) {
        try {
            if (!run.tenantId().equals(current.tenantId()) || !run.subjectId().equals(current.subjectId())
                || !current.has(Permission.AI_DIAGNOSE)) return false;
            if (authorization.authorize(current, ResourceRef.incident(run.tenantId(), run.incidentId()),
                Permission.AI_DIAGNOSE).denied()) return false;
            incidents.get(current, run.incidentId());
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private static AiRun.Reason mapFailure(ToolFailure failure) {
        return switch (failure.code()) {
            case UNAVAILABLE -> AiRun.Reason.RUNTIME_UNAVAILABLE;
            case DEADLINE -> AiRun.Reason.DEADLINE;
            case BUDGET_EXHAUSTED -> AiRun.Reason.BUDGET_EXHAUSTED;
            case FORBIDDEN -> AiRun.Reason.AUTHORIZATION_REVOKED;
            default -> AiRun.Reason.EXECUTION_FAILED;
        };
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
