package com.acme.opsweave.integration.api;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PipelineDraftStore {
    /** expected=0 creates; otherwise compare-and-swap. A stale edit must never overwrite. */
    PipelineDraft save(TenantId tenant, String source, SubjectId owner, PipelineVersion content, int expected, Instant now);
    Optional<PipelineDraft> find(TenantId tenant, String source, SubjectId owner, String id, int revision);
    /** Recent headers only, descending update time, then id and revision; at most 51. */
    List<PipelineDraft.Header> list(TenantId tenant, String source, SubjectId owner, int limit);
}
