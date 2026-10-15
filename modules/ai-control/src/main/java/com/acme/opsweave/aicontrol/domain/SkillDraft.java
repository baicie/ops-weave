package com.acme.opsweave.aicontrol.domain;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The platform Skill draft registry entry (M6, ADR-105).
 *
 * <p>A draft is the authoring record of one Skill coordinate ({@code skillId@version}): the closed
 * configuration content, a state machine and a content digest. The state machine is closed — {@code
 * DRAFT -> IN_REVIEW -> PUBLISHED} — and {@code PUBLISHED} is immutable: no edit, no second publish
 * and no rollback ever rewrites a published coordinate (the red line "published Skills are never
 * overwritten").
 *
 * <p>The review gate is the publish step itself (ADR-105 §2.4): publishing is the approval, and it
 * demands <em>four eyes</em> — the publisher must hold {@code skill.manage} and must not be the
 * draft's author. The recorded {@link SkillRelease.Review} is the review receipt of that decision.
 *
 * <p>Every command is idempotent on its request id: a replay answers with the stored receipt, the
 * same request id with different content is an explicit conflict, and no command ever accepts
 * tenant, actor or permission fields.
 */
public record SkillDraft(TenantId tenantId, UUID draftId, String skillId, String version,
                         SkillContent content, String contentDigest, State state, long editVersion,
                         SubjectId author, Instant createdAt, Instant updatedAt) {

    /** Draft state machine. Published is terminal and immutable. */
    public enum State {
        DRAFT, IN_REVIEW, PUBLISHED;

        public boolean published() {
            return this == PUBLISHED;
        }
    }

    public SkillDraft {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        requireSkillId(skillId);
        requireVersion(version);
        if (contentDigest == null || !contentDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        if (!contentDigest.equals(content.contentDigest())) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        if (editVersion < 1) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (createdAt.isAfter(updatedAt)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
    }

    static void requireSkillId(String skillId) {
        if (skillId == null || !skillId.matches("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){0,3}") || skillId.length() > 64) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
    }

    static void requireVersion(String version) {
        if (version == null || !version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
    }

    /** The coordinate of a published Skill package shipped in the repository; never re-registered. */
    public static boolean reservedCoordinate(String skillId, String version) {
        return SkillRelease.RESERVED.contains(skillId + "@" + version);
    }

    /** A fresh draft at this coordinate; {@code editVersion} starts at 1. */
    public static SkillDraft create(TenantId tenantId, UUID draftId, String skillId, String version,
                                    SkillContent content, SubjectId author, Instant at) {
        Objects.requireNonNull(at, "at");
        if (reservedCoordinate(skillId, version)) throw new SkillRelease.Conflict("SKILL_COORDINATE_RESERVED");
        return new SkillDraft(tenantId, draftId, skillId, version, content, content.contentDigest(),
            State.DRAFT, 1, author, at, at);
    }

    /** Content edit: allowed in {@code DRAFT} and as an explicit withdraw from {@code IN_REVIEW}. */
    public SkillDraft saved(SkillContent next, long expectedEditVersion, Instant at) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(at, "at");
        if (state.published()) throw new SkillRelease.Conflict("SKILL_VERSION_IMMUTABLE");
        if (expectedEditVersion != editVersion) throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
        if (at.isBefore(updatedAt)) throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
        return new SkillDraft(tenantId, draftId, skillId, version, next, next.contentDigest(),
            State.DRAFT, editVersion + 1, author, createdAt, at);
    }

    /** Submit for review: the content is frozen and the draft becomes publishable. */
    public SkillDraft submitted(Instant at) {
        Objects.requireNonNull(at, "at");
        if (state != State.DRAFT) throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
        if (at.isBefore(updatedAt)) throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
        return new SkillDraft(tenantId, draftId, skillId, version, content, contentDigest,
            State.IN_REVIEW, editVersion, author, createdAt, at);
    }

    /**
     * Publish: the approval act of the review gate. The publisher must be a different subject than
     * the author (separation of duties), the frozen content must pass its deterministic checks, and
     * the resulting version is immutable from this moment on.
     */
    public SkillRelease.Version published(SubjectId reviewer, Instant at) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(at, "at");
        if (state != State.IN_REVIEW) throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
        if (reviewer.equals(author)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        if (at.isBefore(updatedAt)) throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
        return new SkillRelease.Version(tenantId, skillId, version, content, contentDigest,
            new SkillRelease.Review(reviewer, at), at);
    }

    /** A replay must name this draft with this content; anything else is an explicit conflict. */
    public void require(String expectedSkillId, String expectedVersion, String expectedDigest) {
        if (!skillId.equals(expectedSkillId) || !version.equals(expectedVersion)
            || !contentDigest.equals(expectedDigest)) {
            throw new SkillRelease.Conflict("SKILL_REQUEST_REUSED");
        }
    }

    /** The closed save command; identity fields are deliberately absent. */
    public record SaveCommand(UUID requestId, String skillId, String version, long editVersion, SkillContent content) {
        public SaveCommand {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(content, "content");
            requireSkillId(skillId);
            requireVersion(version);
            if (editVersion < 1 || editVersion > 1000000) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        /** Content only: the request id is correlation metadata, never part of the digest. */
        public String commandDigest() {
            return SkillRelease.hash("skill-save-v1", skillId, version, Long.toString(editVersion),
                content.contentDigest());
        }
    }

    /** The closed review submission; the frozen content is identified by the draft id from the path. */
    public record ReviewCommand(UUID requestId, UUID draftId) {
        public ReviewCommand {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(draftId, "draftId");
        }

        public String commandDigest() {
            return SkillRelease.hash("skill-review-v1", draftId.toString());
        }
    }

    /** The closed publish command; the publisher comes from the trusted principal, never the body. */
    public record PublishCommand(UUID requestId, UUID draftId) {
        public PublishCommand {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(draftId, "draftId");
        }

        public String commandDigest() {
            return SkillRelease.hash("skill-publish-v1", draftId.toString());
        }
    }

    /** Idempotent save receipt. */
    public record SaveReceipt(UUID requestId, UUID draftId, String skillId, String version,
                              State state, long editVersion, String contentDigest, Instant savedAt) {
        public SaveReceipt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(draftId, "draftId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(savedAt, "savedAt");
        }
    }

    /** Idempotent review submission receipt. */
    public record ReviewReceipt(UUID requestId, UUID draftId, String skillId, String version,
                                State state, Instant submittedAt) {
        public ReviewReceipt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(draftId, "draftId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(submittedAt, "submittedAt");
        }
    }

    /** Idempotent publish receipt; the review receipt is carried inside the published version. */
    public record PublishReceipt(UUID requestId, UUID draftId, String skillId, String version,
                                 String contentDigest, SkillRelease.Review review, Instant publishedAt) {
        public PublishReceipt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(draftId, "draftId");
            Objects.requireNonNull(review, "review");
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }
}
