package com.acme.opsweave.integration.domain;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A persisted synchronous evaluation, not a background queue or an inventory repair. */
public record PipelineReplayRun(UUID id, TenantId tenant, String source, SubjectId owner, UUID requestKey,
    PipelineReplaySpec spec, State state, int attempt, Instant createdAt, Instant updatedAt,
    Instant leaseUntil, String failureCode, PipelineEvaluation report) {
    public static final Duration LEASE = Duration.ofSeconds(120);
    public static final int MAX_ATTEMPTS = 3;
    public enum State { RUNNING, SUCCEEDED, FAILED }
    public PipelineReplayRun {
        Objects.requireNonNull(id); Objects.requireNonNull(tenant); Objects.requireNonNull(source);
        Objects.requireNonNull(owner); Objects.requireNonNull(requestKey); Objects.requireNonNull(spec);
        Objects.requireNonNull(state); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (source.isBlank() || source.length() > 128 || attempt < 1 || attempt > MAX_ATTEMPTS
            || (state == State.RUNNING) != (leaseUntil != null)
            || (state == State.SUCCEEDED) != (report != null)
            || (state == State.FAILED) != (failureCode != null)
            || (failureCode != null && !failureCode.matches("[A-Z_]{1,64}"))) {
            throw new IllegalArgumentException("Invalid replay state");
        }
        if (report != null && (!report.sourceInstanceId().equals(source) || !report.syncRunId().equals(spec.syncRunId().toString())
            || !report.targetVersion().equals(spec.targetVersion()) || !report.mode().equals("REPLAY")
            || !report.purpose().equals(spec.purpose()) || report.rows().size() > spec.limit())) {
            throw new IllegalArgumentException("Replay result scope mismatch");
        }
    }
    public static PipelineReplayRun start(TenantId tenant, String source, SubjectId owner, UUID key, PipelineReplaySpec spec, Instant now) {
        return new PipelineReplayRun(UUID.randomUUID(), tenant, source, owner, key, spec, State.RUNNING, 1,
            now, now, now.plus(LEASE), null, null);
    }
    public boolean canResume(Instant now) {
        return attempt < MAX_ATTEMPTS && (state == State.FAILED || state == State.RUNNING && !leaseUntil.isAfter(now));
    }
    public PipelineReplayRun resume(Instant now) {
        if (!canResume(now)) throw new IllegalStateException("Replay cannot be resumed");
        return new PipelineReplayRun(id, tenant, source, owner, requestKey, spec, State.RUNNING, attempt + 1,
            createdAt, now, now.plus(LEASE), null, null);
    }
    public PipelineReplayRun finish(Instant now, PipelineEvaluation value, String error) {
        return new PipelineReplayRun(id, tenant, source, owner, requestKey, spec, value == null ? State.FAILED : State.SUCCEEDED,
            attempt, createdAt, now, null, error, value);
    }
}
