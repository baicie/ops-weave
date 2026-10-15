package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.SkillCheck;
import com.acme.opsweave.aicontrol.domain.SkillDraft;
import com.acme.opsweave.aicontrol.domain.SkillRelease;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the platform Skill registry (M6, ADR-105). Draft saves are idempotent on the request
 * id with a compare-and-set on {@code editVersion}, published versions are immutable rows, and the
 * release pointer only moves through the explicit publish and rollback commands — never by editing
 * history.
 *
 * <p>Every mutating call carries its request id and command digest: a replay returns the stored
 * receipt unchanged, a reused request id with different content is an explicit
 * {@link com.acme.opsweave.aicontrol.domain.SkillRelease.Conflict}, and identity always comes from
 * the trusted subject recorded at the call site — never from command content.
 */
public interface SkillReleaseStore {

    /**
     * Idempotent draft save keyed by (tenant, requestId). A first call creates the draft; later
     * calls with the same request id return the original receipt. A different request id edits the
     * draft under a compare-and-set on {@code editVersion}; a stale expectation is an explicit
     * conflict, never a silent overwrite.
     */
    SkillDraft.SaveReceipt save(SkillDraft.SaveCommand command, TenantId tenant, SubjectId author, Instant at);

    /** Idempotent review submission; freezes the draft content into {@code IN_REVIEW}. */
    SkillDraft.ReviewReceipt submit(SkillDraft.ReviewCommand command, TenantId tenant, Instant at);

    /**
     * Idempotent publish — the approval act of the review gate. Stores the immutable version,
     * advances the release pointer to it and records the review receipt. The caller has already
     * verified the deterministic check passed and the reviewer is not the author; a coordinate is
     * published at most once and a published row is never rewritten.
     */
    SkillDraft.PublishReceipt publish(SkillDraft.PublishCommand command, TenantId tenant,
                                      SubjectId reviewer, SkillCheck check, Instant at);

    /** Idempotent rollback: the pointer moves back to an earlier published version, history intact. */
    SkillRelease.RollbackReceipt rollback(SkillRelease.RollbackCommand command, TenantId tenant,
                                          SubjectId actor, Instant at);

    Optional<SkillDraft> findDraft(TenantId tenant, UUID draftId);

    /** Bounded page over this tenant's drafts, ordered createdAt DESC, draftId DESC. */
    List<SkillDraft> pageDrafts(TenantId tenant, String after, int limit);

    Optional<SkillRelease.Version> findVersion(TenantId tenant, String skillId, String version);

    /** Bounded page over one Skill's published versions, ordered publishedAt DESC, version DESC. */
    List<SkillRelease.Version> pageVersions(TenantId tenant, String skillId, String after, int limit);

    Optional<SkillRelease.Pointer> pointer(TenantId tenant, String skillId);
}
