package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;

/**
 * Append-only audit of refused write attempts. Recording never authorizes a write, never mutates the
 * refused request and never returns source or entity content to the caller.
 */
public interface RejectedWriteAttemptStore {
    /** Upper bound for one audit page. */
    int MAX_RECENT = 100;

    /**
     * Stores one refused attempt and prunes the scope back to its budget in the same operation. A
     * failed attempt never becomes a stored decision: this table records only that a write was
     * refused and why.
     */
    void record(RejectedWriteAttempt attempt);

    /** Newest-first refusals for one tenant and source, at most {@code limit} rows. */
    List<RejectedWriteAttempt> recent(TenantId tenantId, String sourceInstanceId, int limit);

    /** Rows currently stored for one tenant and source. */
    int kept(TenantId tenantId, String sourceInstanceId);
}
