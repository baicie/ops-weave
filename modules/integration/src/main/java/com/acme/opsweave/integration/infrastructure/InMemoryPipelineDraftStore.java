package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.PipelineDraftStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** Explicit development storage, never a fallback for PostgreSQL. */
public final class InMemoryPipelineDraftStore implements PipelineDraftStore {
    private record Key(TenantId tenant, String source, SubjectId owner, String id, int revision) {}
    private final Map<Key, PipelineDraft> drafts = new HashMap<>();
    public synchronized PipelineDraft save(TenantId tenant, String source, SubjectId owner, PipelineVersion content, int expected, Instant now) {
        var next = PipelineDraft.save(content, expected, now);
        var key = new Key(tenant, source, owner, content.definition().id(), content.definition().revision());
        var current = drafts.get(key);
        if (current == null ? expected != 0 : current.editVersion() != expected) throw new PipelineException(PipelineException.Code.DRAFT_CONFLICT);
        drafts.put(key, next); return next;
    }
    public synchronized Optional<PipelineDraft> find(TenantId tenant, String source, SubjectId owner, String id, int revision) {
        return Optional.ofNullable(drafts.get(new Key(tenant, source, owner, id, revision)));
    }
    public synchronized List<PipelineDraft.Header> list(TenantId tenant, String source, SubjectId owner, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException("Invalid draft limit");
        return drafts.entrySet().stream().filter(e -> e.getKey().tenant().equals(tenant) && e.getKey().source().equals(source) && e.getKey().owner().equals(owner))
            .map(Map.Entry::getValue).sorted(Comparator.comparing(PipelineDraft::updatedAt)
                .thenComparing(d -> d.content().definition().id()).thenComparingInt(d -> d.content().definition().revision()).reversed())
            .limit(limit).map(PipelineDraft.Header::of).toList();
    }
}
