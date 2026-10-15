package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.SkillReleaseStore;
import com.acme.opsweave.aicontrol.domain.SkillCheck;
import com.acme.opsweave.aicontrol.domain.SkillDraft;
import com.acme.opsweave.aicontrol.domain.SkillRelease;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The controlled Skill registry use cases (M6 6a, ADR-105): draft save, deterministic check, review
 * submission, publish, rollback and read-only readback.
 *
 * <p>Nothing here executes a Skill, calls a model or touches a tool. Identity always comes from the
 * trusted principal — the commands never carry tenant, actor or permission fields. The review gate
 * is separation of duties: publishing demands {@code skill.manage} and a publisher who is not the
 * draft's author; the deterministic check must pass before a version can ever be published.
 */
public final class SkillReleaseService {

    private final AuthorizationService authorization;
    private final SkillReleaseStore store;
    private final Clock clock;

    public SkillReleaseService(AuthorizationService authorization, SkillReleaseStore store, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Idempotent draft save under a compare-and-set; a stale edit expectation is an explicit conflict. */
    public SkillDraft.SaveReceipt save(Principal p, SkillDraft.SaveCommand command) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(command, "command");
        requireManage(p, command.skillId());
        return store.save(command, p.tenantId(), p.subjectId(), now());
    }
    /**
     * The deterministic check: run the draft's evaluation cases against its schemas without any
     * model call. The report always says {@code modelComparison = "not-run"} — a passing structural
     * check is not a quality claim. Reading computes nothing stateful and writes nothing.
     */
    public SkillCheck check(Principal p, UUID draftId) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(draftId, "draftId");
        requireRead(p);
        var draft = store.findDraft(p.tenantId(), draftId).orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        return SkillCheck.run(draft.skillId(), draft.version(), draft.content());
    }

    /** Idempotent review submission; the content freezes and the draft becomes publishable. */
    public SkillDraft.ReviewReceipt submit(Principal p, SkillDraft.ReviewCommand command) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(command, "command");
        var draft = store.findDraft(p.tenantId(), command.draftId())
            .orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        requireManage(p, draft.skillId());
        return store.submit(command, p.tenantId(), now());
    }

    /**
     * Idempotent publish — the approval act. Four gates apply in order: {@code skill.manage}, the
     * deterministic check must pass on the frozen content, the publisher must not be the author
     * (separation of duties), and the coordinate must not be a reserved packaged Skill.
     */
    public SkillDraft.PublishReceipt publish(Principal p, SkillDraft.PublishCommand command) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(command, "command");
        var draft = store.findDraft(p.tenantId(), command.draftId())
            .orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        requireManage(p, draft.skillId());
        if (SkillDraft.reservedCoordinate(draft.skillId(), draft.version())) {
            throw new SkillRelease.Conflict("SKILL_COORDINATE_RESERVED");
        }
        var check = SkillCheck.run(draft.skillId(), draft.version(), draft.content());
        if (!check.result().passed()) throw new SkillRelease.Conflict("SKILL_CHECK_FAILED");
        return store.publish(command, p.tenantId(), p.subjectId(), check, now());
    }

    /** Idempotent rollback: the pointer moves back to an earlier published version, history intact. */
    public SkillRelease.RollbackReceipt rollback(Principal p, SkillRelease.RollbackCommand command) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(command, "command");
        requireManage(p, command.skillId());
        return store.rollback(command, p.tenantId(), p.subjectId(), now());
    }

    /** Read one draft with fresh authority; reading never advances anything. */
    public SkillDraft draft(Principal p, UUID draftId) {
        Objects.requireNonNull(p, "principal");
        requireRead(p);
        return store.findDraft(p.tenantId(), draftId).orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
    }

    /** Bounded draft readback, newest first; reading never advances anything. */
    public List<SkillDraft> drafts(Principal p, String after, Integer limit) {
        Objects.requireNonNull(p, "principal");
        requireRead(p);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        String cursor = after == null ? null : uuidText(after);
        return store.pageDrafts(p.tenantId(), cursor, size + 1);
    }

    /** Read one published version — the immutable pin an AIRun references. */
    public SkillRelease.Version version(Principal p, String skillId, String version) {
        Objects.requireNonNull(p, "principal");
        requireRead(p);
        return store.findVersion(p.tenantId(), skillId, version)
            .orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
    }

    /** Bounded version readback of one Skill; published history is append-only. */
    public List<SkillRelease.Version> versions(Principal p, String skillId, String after, Integer limit) {
        Objects.requireNonNull(p, "principal");
        requireRead(p);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        String cursor = after == null ? null : versionText(after);
        return store.pageVersions(p.tenantId(), skillId, cursor, size + 1);
    }

    /** The release pointer of one Skill: what new runs pin right now. */
    public SkillRelease.Pointer release(Principal p, String skillId) {
        Objects.requireNonNull(p, "principal");
        requireRead(p);
        return store.pointer(p.tenantId(), skillId).orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
    }

    private void requireManage(Principal p, String skillId) {
        if (!p.has(Permission.SKILL_MANAGE)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        if (authorization.authorize(p, new ResourceRef(p.tenantId(), "skill", skillId), Permission.SKILL_MANAGE).denied()) {
            throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        }
    }

    private void requireRead(Principal p) {
        if (!p.has(Permission.SKILL_READ)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static String uuidText(String after) {
        try {
            return UUID.fromString(after).toString();
        } catch (IllegalArgumentException invalid) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
    }

    private static String versionText(String after) {
        if (after == null || !after.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        return after;
    }
}
