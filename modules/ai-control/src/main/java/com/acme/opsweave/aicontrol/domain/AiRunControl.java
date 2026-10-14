package com.acme.opsweave.aicontrol.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cooperative cancellation and same-run recovery commands (M5 5b, ADR-104 §2.3/§2.4).
 *
 * <p>Both commands are idempotent on their request id: a replay answers with the stored receipt, the
 * same request id with different content is an explicit conflict, and neither command ever accepts
 * identity fields. A cancellation records {@code cancelRequestedAt} on the run without occupying the
 * state machine; a recovery is a <em>new attempt on the same run</em> — never a second run — taken
 * only after the caller's authority over the incident is re-checked and the unknown completion state
 * of earlier attempts is reconciled against the model fee receipts and the saved AIInsight.
 *
 * <p>Duplicate fees are disclosed explicitly: this code never claims exactly-once across an external
 * model provider. A fee whose outcome is unknown stays accounted (never refunded), and continuing the
 * diagnosis later means a new submission that may be charged again.
 */
public final class AiRunControl {
    private AiRunControl() {}

    /** The two control verbs; the request id is the idempotency key of each. */
    public enum Kind { CANCEL, RETRY }

    /**
     * The closed control command: kind, request id and the target run. Tenant, actor, permissions,
     * approval state and any execution hint are deliberately absent.
     */
    public record Command(Kind kind, UUID requestId, UUID runId) {
        public Command {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(runId, "runId");
        }

        /** Content only: the request id is correlation metadata, never part of the digest. */
        public String commandDigest() {
            try {
                var md = java.security.MessageDigest.getInstance("SHA-256");
                for (String part : List.of("ai-run-control-v1", kind.name(), runId.toString())) {
                    byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    md.update((bytes.length + ":").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    md.update(bytes);
                }
                return "sha256:" + java.util.HexFormat.of().formatHex(md.digest());
            } catch (java.security.NoSuchAlgorithmException missing) {
                throw new IllegalStateException();
            }
        }
    }

    /**
     * The explicit outcome-of-unknown-state disclosure of one reconciliation:
     *
     * <ul>
     *   <li>{@code adoptedPriorResult} — an earlier attempt already saved the complete AIInsight, so
     *       this attempt claims that result instead of executing again;</li>
     *   <li>{@code rerun} — no earlier model call exists, so the diagnosis executes again;</li>
     *   <li>{@code priorModelCalls} / {@code uncertainPriorFees} — model fee receipts found for this
     *       run and how many of them are still RESERVED/UNCERTAIN (charged or possibly charged, never
     *       refunded);</li>
     *   <li>{@code duplicateFeeRisk} — a model fee was spent without a saved result, so this run
     *       cannot complete without a second model call (which the one-call-per-diagnosis invariant
     *       forbids); continuing means a new submission that may be charged again.</li>
     * </ul>
     */
    public record Reconciliation(boolean adoptedPriorResult, boolean rerun, int priorModelCalls,
                                 int uncertainPriorFees, boolean duplicateFeeRisk) {
        public Reconciliation {
            if (priorModelCalls < 0 || priorModelCalls > 8 || uncertainPriorFees < 0
                || uncertainPriorFees > priorModelCalls) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (adoptedPriorResult && rerun) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (rerun && (priorModelCalls != 0 || duplicateFeeRisk)) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (duplicateFeeRisk && priorModelCalls == 0) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        /** What happens after the reconciliation queries (fee receipt + saved insight). */
        public Decision decision() {
            if (adoptedPriorResult) return Decision.ADOPT;
            return rerun ? Decision.RERUN : Decision.RESULT_LOST;
        }

        /** A no-findings disclosure for an attempt that started clean. */
        public static Reconciliation clean() {
            return new Reconciliation(false, true, 0, 0, false);
        }
    }

    /** Reconcile the unknown completion state of earlier attempts before claiming or re-running. */
    public static Reconciliation reconcile(boolean insightSaved, int priorModelCalls, int uncertainPriorFees) {
        if (insightSaved) return new Reconciliation(true, false, priorModelCalls, uncertainPriorFees, false);
        if (priorModelCalls > 0) return new Reconciliation(false, false, priorModelCalls, uncertainPriorFees, true);
        return new Reconciliation(false, true, 0, 0, false);
    }

    public enum Decision { ADOPT, RERUN, RESULT_LOST }

    /**
     * The idempotent cancellation receipt. {@code recorded} says whether the request landed on the
     * run (the executor will cancel it at a safe point); {@code false} means the run had already
     * ended — cancelling a terminal run is idempotent and changes nothing. {@code status} is the run
     * status at receipt time.
     */
    public record CancelReceipt(UUID requestId, UUID runId, Instant requestedAt, boolean recorded, AiRun.Status status) {
        public CancelReceipt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(status, "status");
        }

        /** A replay must name this command; anything else is an explicit conflict. */
        public void require(Command command) {
            if (command.kind() != Kind.CANCEL || !requestId.equals(command.requestId())
                || !runId.equals(command.runId())) {
                throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
            }
        }
    }

    /**
     * The idempotent recovery receipt: which attempt was claimed on this run and what the
     * reconciliation found. The fee counts are the explicit duplicate-fee disclosure.
     */
    public record RetryReceipt(UUID requestId, UUID runId, Instant requestedAt, int attempt,
                               AiRun.Status status, Reconciliation reconciliation) {
        public RetryReceipt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reconciliation, "reconciliation");
            if (attempt < 1) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        /** A replay must name this command; anything else is an explicit conflict. */
        public void require(Command command) {
            if (command.kind() != Kind.RETRY || !requestId.equals(command.requestId())
                || !runId.equals(command.runId())) {
                throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
            }
        }
    }
}
