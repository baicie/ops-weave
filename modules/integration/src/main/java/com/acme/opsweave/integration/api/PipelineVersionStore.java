package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.PipelineVersion;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Optional;
import java.util.UUID;

public interface PipelineVersionStore {
    /** Same content is idempotent; a different digest at the same id/revision conflicts. */
    PipelineVersion publish(TenantId tenant, String source, PipelineVersion version);
    Optional<PipelineVersion> find(TenantId tenant, String source, String id, int revision);
    /** Immutable per-run pin, persisted before any source fetch or inventory write. */
    void pin(TenantId tenant, String source, UUID run, PipelineVersion.Ref version);
    Optional<PipelineVersion.Ref> pinned(TenantId tenant, String source, UUID run);
}
