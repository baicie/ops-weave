package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.Objects;

/** A mutable working copy; editVersion is independent of the publication revision. */
public record PipelineDraft(PipelineVersion content, int editVersion, Instant updatedAt) {
    public PipelineDraft {
        Objects.requireNonNull(content); Objects.requireNonNull(updatedAt);
        if (editVersion < 1) throw new IllegalArgumentException("Invalid draft edit version");
    }
    public static PipelineDraft save(PipelineVersion content, int expectedEditVersion, Instant now) {
        if (expectedEditVersion < 0 || expectedEditVersion == Integer.MAX_VALUE) throw new PipelineException(PipelineException.Code.INVALID_REQUEST);
        return new PipelineDraft(content, expectedEditVersion + 1, now);
    }
    public record Header(PipelineVersion.Ref target, int editVersion, Instant updatedAt) {
        public static Header of(PipelineDraft draft) { return new Header(draft.content().ref(), draft.editVersion(), draft.updatedAt()); }
    }
}
