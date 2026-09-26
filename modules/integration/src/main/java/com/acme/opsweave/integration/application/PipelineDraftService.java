package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.PipelineDraftStore;
import com.acme.opsweave.integration.domain.*;
import java.time.Clock;
import java.util.List;

public final class PipelineDraftService {
    private final AuthorizationService authorization;
    private final PipelineDraftStore store;
    private final String source;
    private final Clock clock;
    public PipelineDraftService(AuthorizationService authorization, PipelineDraftStore store, String source, Clock clock) {
        this.authorization = authorization; this.store = store; this.source = source; this.clock = clock;
    }
    public PipelineDraft save(Principal principal, PipelineDefinition definition, int expectedEditVersion) {
        authorize(principal);
        return store.save(principal.tenantId(), source, principal.subjectId(), PipelineVersion.of(definition), expectedEditVersion, clock.instant());
    }
    public PipelineDraft get(Principal principal, String id, int revision) {
        authorize(principal);
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || revision < 1) throw new PipelineException(PipelineException.Code.INVALID_REQUEST);
        return store.find(principal.tenantId(), source, principal.subjectId(), id, revision)
            .orElseThrow(() -> new PipelineException(PipelineException.Code.NOT_FOUND));
    }
    public Recent list(Principal principal, int limit) {
        authorize(principal);
        if (limit < 1 || limit > 50) throw new PipelineException(PipelineException.Code.INVALID_REQUEST);
        var items = store.list(principal.tenantId(), source, principal.subjectId(), limit + 1);
        return new Recent(items.stream().limit(limit).toList(), items.size() > limit);
    }
    private void authorize(Principal principal) {
        if (authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.SOURCE_SYNC).denied())
            throw new PipelineException(PipelineException.Code.FORBIDDEN);
    }
    public record Recent(List<PipelineDraft.Header> items, boolean truncated) {}
}
