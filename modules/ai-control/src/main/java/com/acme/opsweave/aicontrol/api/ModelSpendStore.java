package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.ModelSpend;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

public interface ModelSpendStore {
    /** Atomically reserves tenant budget. A repeated run never grants another provider invocation. */
    ModelSpend.Call reserve(ModelSpend.Call call);
    /** Same usage is idempotent; changed usage cannot overwrite the first report. No refund on unknown outcomes. */
    ModelSpend.Call report(TenantId tenant, SubjectId subject, UUID run, UUID session, ModelSpend.Usage usage, Instant now);
    Optional<ModelSpend.Call> find(TenantId tenant, UUID run);
}
