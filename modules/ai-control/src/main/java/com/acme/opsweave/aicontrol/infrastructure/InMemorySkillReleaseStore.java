package com.acme.opsweave.aicontrol.infrastructure;

import com.acme.opsweave.aicontrol.api.SkillReleaseStore;
import com.acme.opsweave.aicontrol.domain.SkillCheck;
import com.acme.opsweave.aicontrol.domain.SkillDraft;
import com.acme.opsweave.aicontrol.domain.SkillRelease;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory Skill registry with the same semantics as the PostgreSQL store: idempotent commands on
 * the request id, a compare-and-set draft edit, immutable published versions and a release pointer
 * that only moves through publish and rollback. The memory build keeps the same rules — a replayed
 * command returns its original receipt and a published coordinate is never rewritten.
 */
public final class InMemorySkillReleaseStore implements SkillReleaseStore {

    /** One stored command receipt: what it was and the receipt it produced. */
    private record Stored(String kind, String digest, Object receipt) {}

    private final Map<String, SkillDraft> drafts = new LinkedHashMap<>();
    private final Map<String, SkillRelease.Version> versions = new LinkedHashMap<>();
    private final Map<String, SkillRelease.Pointer> pointers = new HashMap<>();
    private final Map<String, Stored> commands = new HashMap<>();

    private static String key(TenantId tenant, Object id) {
        return tenant.value() + "|" + id;
    }

    private Stored replay(TenantId tenant, UUID requestId, String kind, String digest) {
        var prior = commands.get(key(tenant, requestId));
        if (prior == null) return null;
        if (!prior.kind().equals(kind) || !prior.digest().equals(digest)) {
            throw new SkillRelease.Conflict("SKILL_REQUEST_REUSED");
        }
        return prior;
    }

    private void record(TenantId tenant, UUID requestId, String kind, String digest, Object receipt) {
        commands.put(key(tenant, requestId), new Stored(kind, digest, receipt));
    }

    @Override
    public synchronized SkillDraft.SaveReceipt save(SkillDraft.SaveCommand command, TenantId tenant,
            SubjectId author, Instant at) {
        var prior = replay(tenant, command.requestId(), "SAVE", command.commandDigest());
        if (prior != null) return (SkillDraft.SaveReceipt) prior.receipt();
        String coordinate = key(tenant, command.skillId() + "@" + command.version());
        var existing = drafts.get(coordinate);
        SkillDraft next;
        SkillDraft.SaveReceipt receipt;
        if (existing == null) {
            if (command.editVersion() != 1) throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
            var draft = SkillDraft.create(tenant, UUID.randomUUID(), command.skillId(), command.version(),
                command.content(), author, at);
            drafts.put(coordinate, draft);
            next = draft;
            receipt = new SkillDraft.SaveReceipt(command.requestId(), draft.draftId(), draft.skillId(),
                draft.version(), draft.state(), draft.editVersion(), draft.contentDigest(), at);
        } else {
            if (SkillDraft.reservedCoordinate(command.skillId(), command.version())) {
                throw new SkillRelease.Conflict("SKILL_COORDINATE_RESERVED");
            }
            next = existing.saved(command.content(), command.editVersion(), at);
            drafts.put(coordinate, next);
            receipt = new SkillDraft.SaveReceipt(command.requestId(), next.draftId(), next.skillId(),
                next.version(), next.state(), next.editVersion(), next.contentDigest(), at);
        }
        record(tenant, command.requestId(), "SAVE", command.commandDigest(), receipt);
        return receipt;
    }

    @Override
    public synchronized SkillDraft.ReviewReceipt submit(SkillDraft.ReviewCommand command, TenantId tenant, Instant at) {
        var prior = replay(tenant, command.requestId(), "SUBMIT", command.commandDigest());
        if (prior != null) return (SkillDraft.ReviewReceipt) prior.receipt();
        var draft = drafts.values().stream()
            .filter(d -> d.tenantId().equals(tenant) && d.draftId().equals(command.draftId()))
            .findFirst().orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        var next = draft.submitted(at);
        drafts.put(key(tenant, next.skillId() + "@" + next.version()), next);
        var receipt = new SkillDraft.ReviewReceipt(command.requestId(), next.draftId(), next.skillId(),
            next.version(), next.state(), at);
        record(tenant, command.requestId(), "SUBMIT", command.commandDigest(), receipt);
        return receipt;
    }

    @Override
    public synchronized SkillDraft.PublishReceipt publish(SkillDraft.PublishCommand command, TenantId tenant,
            SubjectId reviewer, SkillCheck check, Instant at) {
        var prior = replay(tenant, command.requestId(), "PUBLISH", command.commandDigest());
        if (prior != null) return (SkillDraft.PublishReceipt) prior.receipt();
        var draft = drafts.values().stream()
            .filter(d -> d.tenantId().equals(tenant) && d.draftId().equals(command.draftId()))
            .findFirst().orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        if (!check.result().passed() || !check.contentDigest().equals(draft.contentDigest())) {
            throw new SkillRelease.Conflict("SKILL_CHECK_FAILED");
        }
        var version = draft.published(reviewer, at);
        String coordinate = key(tenant, version.skillId() + "@" + version.version());
        if (versions.containsKey(coordinate)) throw new SkillRelease.Conflict("SKILL_VERSION_IMMUTABLE");
        versions.put(coordinate, version);
        drafts.put(key(tenant, draft.skillId() + "@" + draft.version()),
            new SkillDraft(draft.tenantId(), draft.draftId(), draft.skillId(), draft.version(), draft.content(),
                draft.contentDigest(), SkillDraft.State.PUBLISHED, draft.editVersion(), draft.author(),
                draft.createdAt(), at));
        pointers.put(key(tenant, version.skillId()), new SkillRelease.Pointer(tenant, version.skillId(),
            version.version(), at));
        var receipt = new SkillDraft.PublishReceipt(command.requestId(), draft.draftId(), version.skillId(),
            version.version(), version.contentDigest(), version.review(), at);
        record(tenant, command.requestId(), "PUBLISH", command.commandDigest(), receipt);
        return receipt;
    }

    @Override
    public synchronized SkillRelease.RollbackReceipt rollback(SkillRelease.RollbackCommand command, TenantId tenant,
            SubjectId actor, Instant at) {
        var prior = replay(tenant, command.requestId(), "ROLLBACK", command.commandDigest());
        if (prior != null) return (SkillRelease.RollbackReceipt) prior.receipt();
        var pointer = pointers.get(key(tenant, command.skillId()));
        if (pointer == null) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
        if (command.toVersion().equals(pointer.currentVersion())) {
            throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
        }
        if (!versions.containsKey(key(tenant, command.skillId() + "@" + command.toVersion()))) {
            throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
        }
        pointers.put(key(tenant, command.skillId()), new SkillRelease.Pointer(tenant, command.skillId(),
            command.toVersion(), at));
        var receipt = new SkillRelease.RollbackReceipt(command.requestId(), command.skillId(),
            pointer.currentVersion(), command.toVersion(), command.reason(), at);
        record(tenant, command.requestId(), "ROLLBACK", command.commandDigest(), receipt);
        return receipt;
    }

    @Override
    public synchronized Optional<SkillDraft> findDraft(TenantId tenant, UUID draftId) {
        return drafts.values().stream()
            .filter(d -> d.tenantId().equals(tenant) && d.draftId().equals(draftId)).findFirst();
    }

    @Override
    public synchronized List<SkillDraft> pageDrafts(TenantId tenant, String after, int limit) {
        if (limit < 1) throw new IllegalArgumentException("page limit out of bounds");
        var visible = new ArrayList<SkillDraft>();
        for (var draft : drafts.values()) {
            if (draft.tenantId().equals(tenant)) visible.add(draft);
        }
        visible.sort(Comparator.comparing(SkillDraft::createdAt).thenComparing(d -> d.draftId().toString()).reversed());
        int start = 0;
        if (after != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).draftId().toString().equals(after)) {
                    start = i + 1;
                    break;
                }
            }
        }
        var page = new ArrayList<SkillDraft>();
        for (int i = start; i < visible.size() && i < start + limit; i++) page.add(visible.get(i));
        return List.copyOf(page);
    }

    @Override
    public synchronized Optional<SkillRelease.Version> findVersion(TenantId tenant, String skillId, String version) {
        return Optional.ofNullable(versions.get(key(tenant, skillId + "@" + version)));
    }

    @Override
    public synchronized List<SkillRelease.Version> pageVersions(TenantId tenant, String skillId, String after, int limit) {
        if (limit < 1) throw new IllegalArgumentException("page limit out of bounds");
        var visible = new ArrayList<SkillRelease.Version>();
        for (var version : versions.values()) {
            if (version.tenantId().equals(tenant) && version.skillId().equals(skillId)) visible.add(version);
        }
        visible.sort(Comparator.comparing(SkillRelease.Version::publishedAt)
            .thenComparing(SkillRelease.Version::version).reversed());
        int start = 0;
        if (after != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).version().equals(after)) {
                    start = i + 1;
                    break;
                }
            }
        }
        var page = new ArrayList<SkillRelease.Version>();
        for (int i = start; i < visible.size() && i < start + limit; i++) page.add(visible.get(i));
        return List.copyOf(page);
    }

    @Override
    public synchronized Optional<SkillRelease.Pointer> pointer(TenantId tenant, String skillId) {
        return Optional.ofNullable(pointers.get(key(tenant, skillId)));
    }
}
