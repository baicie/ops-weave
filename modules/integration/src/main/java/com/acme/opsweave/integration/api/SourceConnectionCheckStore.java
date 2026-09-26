package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.SourceConnectionCheck;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;

/** Bounded receipt log for read-only source self-checks. */
public interface SourceConnectionCheckStore {
    /** Upper bound for one read page. */
    int MAX_RECENT = 50;

    /**
     * Stores one receipt and keeps at most {@link SourceConnectionCheck#MAX_KEPT} per tenant and
     * source, pruning the oldest. A check never changes a scan, a binding or an entity.
     */
    void record(SourceConnectionCheck check);

    /** Newest-first receipts for one source scope, at most {@code limit} rows. */
    List<SourceConnectionCheck> recent(TenantId tenantId, String sourceInstanceId, int limit);

    /** How many receipts are currently kept for one source scope; never more than the cap. */
    int kept(TenantId tenantId, String sourceInstanceId);
}
