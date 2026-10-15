package com.acme.opsweave.aicontrol.domain;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The immutable published Skill version and its release pointer (M6, ADR-105).
 *
 * <p>Publication freezes one content digest forever. The release pointer names the version new runs
 * pin; it can move forward by publishing and move backward by an explicit rollback, but no pointer
 * move ever rewrites a published version — history stays intact, and an AIRun that already pinned a
 * version keeps that pin (runs never follow {@code latest}).
 *
 * <p>Coordinates of the Skill packages shipped inside this repository are reserved: the platform
 * registry must never re-register or shadow {@code extensions/skills} content.
 */
public final class SkillRelease {

    /**
     * Coordinates of the published Skill packages checked into {@code extensions/skills}. The
     * platform registry refuses to create a draft at these coordinates: published Skills are never
     * overwritten and the repository's packaged Skills are never shadowed by a registry entry.
     */
    public static final Set<String> RESERVED = Set.of(
        "incident.diagnose@1.0.0",
        "incident.diagnose@2.0.0",
        "incident.summary@1.0.0");

    private SkillRelease() {}

    /** The review receipt recorded at publish: who approved, and when. */
    public record Review(SubjectId reviewer, Instant approvedAt) {
        public Review {
            Objects.requireNonNull(reviewer, "reviewer");
            Objects.requireNonNull(approvedAt, "approvedAt");
        }
    }

    /**
     * One immutable published version. The content and its digest never change after publication;
     * the review receipt is part of the record so the approval stays attributable.
     */
    public record Version(TenantId tenantId, String skillId, String version, SkillContent content,
                          String contentDigest, Review review, Instant publishedAt) {
        public Version {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(review, "review");
            Objects.requireNonNull(publishedAt, "publishedAt");
            SkillDraft.requireSkillId(skillId);
            SkillDraft.requireVersion(version);
            if (contentDigest == null || !contentDigest.matches("sha256:[0-9a-f]{64}")
                || !contentDigest.equals(content.contentDigest())) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        /** Immutable pin for an AIRun: fixed id, version and digest — never {@code latest}. */
        public InsightSubmission.SkillRef pin() {
            return new InsightSubmission.SkillRef(skillId, version, contentDigest);
        }
    }

    /**
     * The release pointer of one Skill: which published version new runs pin. Rollback moves it
     * back to an earlier published version and records why; the version records themselves never
     * change.
     */
    public record Pointer(TenantId tenantId, String skillId, String currentVersion, Instant updatedAt) {
        public Pointer {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(updatedAt, "updatedAt");
            SkillDraft.requireSkillId(skillId);
            SkillDraft.requireVersion(currentVersion);
        }
    }

    /** The closed rollback command: the target version and a reason, never identity. */
    public record RollbackCommand(UUID requestId, String skillId, String toVersion, String reason) {
        public RollbackCommand {
            Objects.requireNonNull(requestId, "requestId");
            requireSkillIdName(skillId);
            SkillDraft.requireVersion(toVersion);
            if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 256) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }

        private static void requireSkillIdName(String skillId) {
            SkillDraft.requireSkillId(skillId);
        }

        /** Content only: the request id is correlation metadata, never part of the digest. */
        public String commandDigest() {
            return hash("skill-rollback-v1", skillId, toVersion, reason);
        }
    }

    /** Idempotent rollback receipt: pointer movement is recorded, history is not rewritten. */
    public record RollbackReceipt(UUID requestId, String skillId, String fromVersion, String toVersion,
                                  String reason, Instant rolledBackAt) {
        public RollbackReceipt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(skillId, "skillId");
            Objects.requireNonNull(fromVersion, "fromVersion");
            Objects.requireNonNull(toVersion, "toVersion");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(rolledBackAt, "rolledBackAt");
            if (fromVersion.equals(toVersion)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
    }

    /** A version page over one Skill's published versions (bounded, ordered, cursor readback). */
    public record Page(List<Version> items, String nextCursor, boolean hasMore) {
        public Page {
            Objects.requireNonNull(items, "items");
            if (hasMore != (nextCursor != null)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
    }

    /** Closed conflict codes of the Skill registry; the API maps these to 409. */
    public static final class Conflict extends RuntimeException {
        private final String code;

        public Conflict(String code) {
            super(code);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    /** Content-only digest, the same length-prefixed SHA-256 scheme the runtime loader uses. */
    static String hash(String tag, String... parts) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            for (String part : java.util.stream.Stream.concat(java.util.stream.Stream.of(tag),
                java.util.stream.Stream.of(parts)).toList()) {
                byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                md.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
                md.update(bytes);
            }
            return "sha256:" + java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException();
        }
    }
}
