package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.AiInsightStore;
import com.acme.opsweave.aicontrol.api.AiRunStore;
import com.acme.opsweave.aicontrol.api.ModelSpendStore;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunControl;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.incident.application.IncidentService;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The controlled cancellation and recovery use cases (M5 5b, ADR-104 §2.3/§2.4).
 *
 * <p>Both commands are idempotent on their request id and answer with a stored receipt: a replay
 * returns the original receipt unchanged, the same request id with different content is an explicit
 * conflict and no second run is ever created. Identity comes only from the trusted principal.
 *
 * <p><b>Cancelling</b> records {@code cancelRequestedAt} on the run without occupying the state
 * machine — the executor lands {@code CANCELLED} at its next safe point and a run that completes
 * first still ends {@code SUCCEEDED}. Leaving a page is not a cancellation; only this explicit
 * command is. Cancelling a terminal run is idempotent and changes nothing. Cancelling is a stop
 * action on one's own run and is therefore always allowed to its owner (a revoked authority must
 * never trap a running, possibly billing job).
 *
 * <p><b>Recovering</b> is a new attempt on the same run — never a second run — and re-authorizes the
 * current incident first (identity, permission and object scope withdrawn means rejected). The
 * unknown completion state of earlier attempts is reconciled against the model fee receipts and the
 * saved AIInsight before the attempt is claimed: a saved result is claimed, a spent fee without a
 * result is disclosed explicitly (it is never refunded and continuing later may be charged again) and
 * only a clean run re-executes with fresh evidence checks. Terminal runs are final: recovery targets
 * unfinished runs, a new diagnosis is a new submission.
 */
public final class AiRunControlService {
    private final AiRunStore store;
    private final AiInsightStore insights;
    private final ModelSpendStore spend;
    private final AiRunExecutionService execution;
    private final AuthorizationService authorization;
    private final IncidentService incidents;
    private final Clock clock;

    public AiRunControlService(AiRunStore store, AiInsightStore insights, ModelSpendStore spend,
            AiRunExecutionService execution, AuthorizationService authorization, IncidentService incidents, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.insights = Objects.requireNonNull(insights);
        this.spend = Objects.requireNonNull(spend);
        this.execution = Objects.requireNonNull(execution);
        this.authorization = Objects.requireNonNull(authorization);
        this.incidents = Objects.requireNonNull(incidents);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Idempotent cooperative cancellation request; the run state machine does not move here. */
    public AiRunControl.CancelReceipt cancel(Principal p, UUID runId, AiRunControl.Command command) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(command, "command");
        if (command.kind() != AiRunControl.Kind.CANCEL || !command.runId().equals(runId)) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        var stored = store.findCancel(p.tenantId(), command.requestId());
        if (stored.isPresent()) {
            stored.get().require(command);
            return stored.get();
        }
        requireFreshRequestId(p.tenantId(), command.requestId());
        var run = loadOwn(p, runId);
        if (!p.has(Permission.AI_DIAGNOSE)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        var at = now();
        var receipt = run.status().terminal()
            ? new AiRunControl.CancelReceipt(command.requestId(), runId, at, false, run.status())
            : recordCancel(p.tenantId(), command, at);
        return store.saveCancel(p.tenantId(), receipt);
    }

    /**
     * Idempotent recovery: a new attempt on the same run after fresh authorization and after the
     * unknown completion state of earlier attempts has been reconciled and disclosed.
     */
    public AiRunControl.RetryReceipt retry(Principal p, UUID runId, AiRunControl.Command command) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(command, "command");
        if (command.kind() != AiRunControl.Kind.RETRY || !command.runId().equals(runId)) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        var stored = store.findRetry(p.tenantId(), command.requestId());
        if (stored.isPresent()) {
            stored.get().require(command);
            return stored.get();
        }
        requireFreshRequestId(p.tenantId(), command.requestId());
        var run = loadOwn(p, runId);
        // Recovery re-authorizes the current incident first: withdrawn authority is rejected.
        if (!p.has(Permission.AI_DIAGNOSE)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        if (authorization.authorize(p, ResourceRef.incident(p.tenantId(), run.incidentId()),
            Permission.AI_DIAGNOSE).denied()) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        incidents.get(p, run.incidentId());
        if (run.status().terminal()) throw new AiRun.Conflict("AI_RUN_STATE_CONFLICT");
        // Unknown completion state first (fee receipt + saved insight): the receipt is the disclosure.
        var at = now();
        var reconciliation = execution.reconcile(p.tenantId(), run.runId(), at);
        var claim = store.claim(p.tenantId(), runId, execution.worker(), at,
            AiRunExecutionService.LEASE, AiRunExecutionService.ATTEMPT_DEADLINE);
        // A live lease owns the run right now — never hand it a second worker.
        if (claim.isEmpty()) throw new AiRun.Conflict("AI_RUN_STATE_CONFLICT");
        var finished = execution.attempt(claim.get(), p);
        var status = finished.map(AiRun.Run::status)
            .orElseGet(() -> store.find(p.tenantId(), runId).map(AiRun.Run::status).orElse(run.status()));
        return store.saveRetry(p.tenantId(), new AiRunControl.RetryReceipt(command.requestId(), runId, at,
            claim.get().attempt(), status, reconciliation));
    }

    private AiRunControl.CancelReceipt recordCancel(com.acme.opsweave.sharedkernel.TenantId tenant,
            AiRunControl.Command command, Instant at) {
        var updated = store.requestCancel(tenant, command.runId(), at);
        return new AiRunControl.CancelReceipt(command.requestId(), command.runId(), at, updated.isPresent(),
            updated.map(AiRun.Run::status).orElseGet(() -> store.find(tenant, command.runId())
                .map(AiRun.Run::status).orElse(AiRun.Status.CANCELLED)));
    }

    /** The caller's own run, visible now. Another owner's run is never a guessed 403 but a 404. */
    private AiRun.Run loadOwn(Principal p, UUID runId) {
        var run = store.find(p.tenantId(), runId).orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        if (!run.subjectId().equals(p.subjectId())) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
        return run;
    }

    /** A request id is the idempotency key of exactly one command — never shared with a submission. */
    private void requireFreshRequestId(com.acme.opsweave.sharedkernel.TenantId tenant, UUID requestId) {
        if (store.byRequest(tenant, requestId).isPresent()) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
