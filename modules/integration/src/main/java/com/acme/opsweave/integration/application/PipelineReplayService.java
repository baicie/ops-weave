package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.PipelineReplayStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class PipelineReplayService {
    private final AuthorizationService authorization;
    private final PipelineReplayStore store;
    private final HostPipelineService evaluator;
    private final String source;
    private final Clock clock;
    public PipelineReplayService(AuthorizationService authorization, PipelineReplayStore store,
            HostPipelineService evaluator, String source, Clock clock) {
        this.authorization = authorization; this.store = store; this.evaluator = evaluator; this.source = source; this.clock = clock;
    }
    public PipelineReplayRun execute(Principal principal, UUID key, PipelineReplaySpec spec) {
        authorize(principal);
        var claim = store.claim(principal.tenantId(), source, principal.subjectId(), key, spec, clock.instant());
        if (!claim.acquired()) return readable(principal, claim.run());
        var run = claim.run();
        try {
            var report = evaluator.replay(principal, spec.syncRunId(), spec.targetVersion(), spec.limit(), true, spec.purpose());
            if (!store.finish(run, clock.instant(), report, null)) throw new PipelineException(PipelineException.Code.REPLAY_LEASE_LOST);
        } catch (RuntimeException failed) {
            String code = failed instanceof PipelineException expected ? expected.code().name() : "REPLAY_EVALUATION_FAILED";
            // A failed status write leaves RUNNING until expiry; never manufacture a successful completion.
            if (!store.finish(run, clock.instant(), null, code)) throw new PipelineException(PipelineException.Code.REPLAY_LEASE_LOST);
            throw failed;
        }
        return get(principal, run.id());
    }
    public PipelineReplayRun get(Principal principal, UUID id) {
        authorize(principal);
        return readable(principal, store.find(principal.tenantId(), source, principal.subjectId(), id)
            .orElseThrow(() -> new PipelineException(PipelineException.Code.NOT_FOUND)));
    }
    public Page list(Principal principal, UUID before, int limit) {
        authorize(principal);
        if (limit < 1 || limit > 50) throw new PipelineException(PipelineException.Code.INVALID_REQUEST);
        var fetched = store.list(principal.tenantId(), source, principal.subjectId(), before, limit + 1);
        var items = fetched.stream().limit(limit).toList();
        return new Page(items, fetched.size() > limit ? items.getLast().id() : null);
    }
    private PipelineReplayRun readable(Principal principal, PipelineReplayRun run) {
        if (run.report() != null) {
            for (var row : run.report().rows()) {
                checkHost(principal, row.previous()); checkHost(principal, row.candidate());
            }
        }
        return run;
    }
    private void checkHost(Principal principal, PipelineEvaluation.Host host) {
        if (host != null && authorization.authorize(principal, ResourceRef.entity(principal.tenantId(), EntityId.parse(host.entityId())),
            Permission.ENTITY_READ).denied()) throw new PipelineException(PipelineException.Code.FORBIDDEN);
    }
    private void authorize(Principal principal) {
        if (authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.SOURCE_SYNC).denied()) {
            throw new PipelineException(PipelineException.Code.FORBIDDEN);
        }
    }
    public record Page(List<PipelineReplayStore.Summary> items, UUID nextCursor) {}
}
