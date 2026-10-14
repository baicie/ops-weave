package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunControl;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the durable run registry (ADR-104). Creation is idempotent on the request id and the
 * state advance is a compare-and-set, so a crashed or duplicated submitter can never create a second
 * run and a stale worker can never overwrite a newer state.
 *
 * <p>The 5b executor seam adds lease and fencing on top of the compare-and-set: a claim atomically
 * takes the run for one attempt (a new attempt on the same run — never a second run) with a lease
 * deadline and a new fencing token, and every later commit must carry that token. A worker that lost
 * its lease commits nothing: its fencing token no longer matches, so crash recovery cannot double-run
 * and the late result is rejected instead of overwriting the reclaiming worker's state.
 *
 * <p>The 5c event log ({@link AiRunEventStore}) is owned by the same store: the submission, every
 * claim and every applied state move record their {@link com.acme.opsweave.aicontrol.domain.AiRunEvent}
 * at the transition point, so a rejected compare-and-set records nothing and an event never describes
 * a state that was not applied.
 */
public interface AiRunStore extends AiRunEventStore {
    /**
     * Idempotent create keyed by (tenant, requestId). A replay returns the run that already exists;
     * callers must still verify that its content matches their request.
     */
    AiRun.Run create(AiRun.Run run);

    Optional<AiRun.Run> find(TenantId tenant, UUID runId);

    /** The immutable submission receipt of one request id; used to answer replays. */
    Optional<AiRun.Run> byRequest(TenantId tenant, UUID requestId);

    /**
     * At most {@code limit} runs of this owner strictly after the cursor in the retained ordering
     * {@code createdAt DESC, runId DESC}. The cursor must name a run visible to this owner, else the
     * read fails closed with NOT_FOUND. Callers pass {@code limit + 1} rows to assemble a page.
     */
    List<AiRun.Run> page(TenantId tenant, SubjectId owner, String after, int limit);

    /**
     * One executor claim: {@code PENDING -> RUNNING} or the reclaim of a {@code RUNNING}/{@code
     * WAITING_TOOL} run whose lease has expired (a crashed worker), advancing the attempt counter,
     * the fencing token and the CAS version in one step. A terminal run is never claimable and a run
     * whose lease is still live is never handed to a second worker.
     */
    record Claim(AiRun.Run run, long fence) {
        public Claim {
            Objects.requireNonNull(run, "run");
            if (fence < 1) throw new IllegalArgumentException("fence out of bounds");
        }

        public int attempt() {
            return run.attempt();
        }
    }

    Optional<Claim> claim(TenantId tenant, UUID runId, String worker, Instant now, Duration lease, Duration deadline);

    /**
     * Durable state advance with a compare-and-set on {@code statusVersion} and the fencing token of
     * the claiming worker. An empty result means the transition was not applied because the run is
     * gone, a concurrent update won or the worker lost its lease (stale fencing token); an illegal
     * transition is an explicit {@link AiRun.Conflict}, never a silent no-op.
     */
    Optional<AiRun.Run> advance(TenantId tenant, UUID runId, long expectedVersion, long fence, AiRun.Transition next);

    /**
     * Record the cooperative cancellation request without touching the state machine. An empty result
     * means the run had already ended (or is gone): cancelling a terminal run changes nothing.
     */
    Optional<AiRun.Run> requestCancel(TenantId tenant, UUID runId, Instant at);

    /** Runs of this owner an executor may pick up: unclaimed {@code PENDING} runs and expired leases. */
    List<AiRun.Run> claimable(TenantId tenant, SubjectId owner, Instant now, int limit);

    /** The immutable control receipt of one request id; used to answer replays. */
    Optional<AiRunControl.CancelReceipt> findCancel(TenantId tenant, UUID requestId);

    Optional<AiRunControl.RetryReceipt> findRetry(TenantId tenant, UUID requestId);

    /**
     * Idempotent receipt write keyed by (tenant, requestId): the first write wins and later writes
     * with the same request id must carry the same content, else an explicit
     * {@link AiRun.Conflict}. Returns the stored receipt.
     */
    AiRunControl.CancelReceipt saveCancel(TenantId tenant, AiRunControl.CancelReceipt receipt);

    AiRunControl.RetryReceipt saveRetry(TenantId tenant, AiRunControl.RetryReceipt receipt);

    final class Access extends RuntimeException { public Access(String message) { super(message); } }
    final class NotFound extends RuntimeException { public NotFound(String message) { super(message); } }
}
