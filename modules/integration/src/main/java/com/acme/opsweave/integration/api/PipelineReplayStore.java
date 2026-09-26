package com.acme.opsweave.integration.api;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineReplayStore {
    record Claim(PipelineReplayRun run, boolean acquired) {}
    Claim claim(TenantId tenant, String source, SubjectId owner, UUID key, PipelineReplaySpec spec, Instant now);
    /** Compare state, attempt fencing token and unexpired lease atomically. */
    boolean finish(PipelineReplayRun claimed, Instant now, PipelineEvaluation report, String failureCode);
    Optional<PipelineReplayRun> find(TenantId tenant, String source, SubjectId owner, UUID id);
    /** Header-only results. No reports are loaded for the history list. */
    List<Summary> list(TenantId tenant, String source, SubjectId owner, UUID before, int limit);
    record Summary(UUID id, UUID requestKey, PipelineReplaySpec spec, PipelineReplayRun.State state, int attempt,
        Instant createdAt, Instant updatedAt, Instant leaseUntil, String failureCode) {
        public static Summary of(PipelineReplayRun run) {
            return new Summary(run.id(), run.requestKey(), run.spec(), run.state(), run.attempt(), run.createdAt(),
                run.updatedAt(), run.leaseUntil(), run.failureCode());
        }
        public boolean canResume(Instant now) {
            return attempt < PipelineReplayRun.MAX_ATTEMPTS && (state == PipelineReplayRun.State.FAILED
                || state == PipelineReplayRun.State.RUNNING && !leaseUntil.isAfter(now));
        }
    }
}
