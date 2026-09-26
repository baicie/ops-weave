package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.AiRetention.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

public interface AiRetentionStore {
    Preview preview(Policy policy,SubjectId actor,Instant asOf);
    /** Recomputes the exact preview under the tenant lock; scrub + original receipt commit atomically. */
    Receipt apply(Policy policy,SubjectId actor,Command command,Runnable recheckConfiguration);
    Optional<Receipt> receipt(TenantId tenant,UUID requestId);
}
