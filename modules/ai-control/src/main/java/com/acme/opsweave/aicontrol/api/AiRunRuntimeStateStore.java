package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.AiRunCrossStore;
import com.acme.opsweave.aicontrol.domain.AiRunRuntimeState;
import com.acme.opsweave.sharedkernel.TenantId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the Runtime state attestations and the current cross-store report snapshot (M5 5d).
 *
 * <p>The attestations are a bounded receipt projection of the Runtime's internal progress — the
 * platform never stores the StepRun/Checkpoint internals themselves and never advances a run because
 * a report arrived. Writes are idempotent on the request id (first write wins, same content on a
 * replay, an explicit conflict on different content) and the per-run window is bounded: older
 * receipts are dropped and the drop is counted, never hidden.
 *
 * <p>The cross-store report snapshot is a one-row-per-run observation of the last comparison; it is
 * a disclosure artifact, not state, and overwriting it with a newer comparison changes no run.
 */
public interface AiRunRuntimeStateStore {
    /**
     * Idempotent receipt write keyed by (tenant, requestId). A replay returns the stored report;
     * the same request id with different content is an explicit {@code AI_RUN_REQUEST_REUSED}.
     */
    AiRunRuntimeState.Report save(TenantId tenant, AiRunRuntimeState.Report report);

    Optional<AiRunRuntimeState.Report> find(TenantId tenant, UUID requestId);

    /** The newest retained attestation of one run; empty when none was ever delivered. */
    Optional<AiRunRuntimeState.Report> latest(TenantId tenant, UUID runId);

    /** The bounded window facts of one run: how many receipts are kept and how many were dropped. */
    Counts counts(TenantId tenant, UUID runId);

    /** The current cross-store report snapshot of one run; empty when none was recorded. */
    Optional<AiRunCrossStore.Report> findReport(TenantId tenant, UUID runId);

    /** Record (or replace) the cross-store report snapshot of one run. Observations, not state. */
    void saveReport(TenantId tenant, AiRunCrossStore.Report report);

    record Counts(int retained, long dropped) {
        public Counts {
            if (retained < 0 || retained > AiRunRuntimeState.RETAINED_REPORTS || dropped < 0) {
                throw new IllegalArgumentException("runtime state counts out of bounds");
            }
        }
    }
}
