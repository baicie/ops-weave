package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.PipelineReplayStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** Explicit development storage; never used as a fallback for PostgreSQL failure. */
public final class InMemoryPipelineReplayStore implements PipelineReplayStore {
    private final Map<UUID, PipelineReplayRun> runs = new HashMap<>();
    private record Key(TenantId tenant, String source, SubjectId owner, UUID key) {}
    private final Map<Key, UUID> keys = new HashMap<>();
    public synchronized Claim claim(TenantId tenant, String source, SubjectId owner, UUID key, PipelineReplaySpec spec, Instant now) {
        var scope = new Key(tenant, source, owner, key);
        UUID id = keys.get(scope);
        if (id == null) {
            var run = PipelineReplayRun.start(tenant, source, owner, key, spec, now);
            runs.put(run.id(), run); keys.put(scope, run.id());
            return new Claim(run, true);
        }
        var run = runs.get(id);
        if (!run.spec().equals(spec)) throw new PipelineException(PipelineException.Code.REPLAY_KEY_CONFLICT);
        if (run.canResume(now)) { run = run.resume(now); runs.put(id, run); return new Claim(run, true); }
        if (run.state() == PipelineReplayRun.State.RUNNING && !run.leaseUntil().isAfter(now)) {
            run = run.finish(now, null, "REPLAY_ATTEMPTS_EXHAUSTED"); runs.put(id, run);
        }
        return new Claim(run, false);
    }
    public synchronized boolean finish(PipelineReplayRun claimed, Instant now, PipelineEvaluation report, String failure) {
        var current = runs.get(claimed.id());
        if (current == null || current.state() != PipelineReplayRun.State.RUNNING || current.attempt() != claimed.attempt()
            || !current.leaseUntil().isAfter(now) || !current.tenant().equals(claimed.tenant())
            || !current.owner().equals(claimed.owner()) || !current.source().equals(claimed.source())) return false;
        runs.put(current.id(), current.finish(now, report, failure)); return true;
    }
    public synchronized Optional<PipelineReplayRun> find(TenantId tenant, String source, SubjectId owner, UUID id) {
        return Optional.ofNullable(runs.get(id)).filter(run -> run.tenant().equals(tenant) && run.source().equals(source) && run.owner().equals(owner));
    }
    public synchronized List<Summary> list(TenantId tenant, String source, SubjectId owner, UUID before, int limit) {
        var order = Comparator.comparing(PipelineReplayRun::createdAt).thenComparing(run -> run.id().toString()).reversed();
        var cursor = before == null ? null : find(tenant, source, owner, before).orElseThrow(() -> new PipelineException(PipelineException.Code.NOT_FOUND));
        return runs.values().stream().filter(run -> run.tenant().equals(tenant) && run.source().equals(source) && run.owner().equals(owner))
            .filter(run -> cursor == null || order.compare(run, cursor) > 0).sorted(order).limit(limit).map(Summary::of).toList();
    }
}
