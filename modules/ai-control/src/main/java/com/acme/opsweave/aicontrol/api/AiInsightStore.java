package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.AiInsight;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.*;

public interface AiInsightStore {
    Optional<AiInsight> find(TenantId tenant, UUID runId);
    default Optional<com.acme.opsweave.aicontrol.domain.AiRetention.Marker> retired(TenantId tenant, UUID runId) { return Optional.empty(); }
    /** Atomic idempotency by tenant/run, one saved result per read session, final pinned inputs checked under lock. */
    AiInsight save(AiInsight insight);
}
