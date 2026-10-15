package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.SkillReleaseStore;
import com.acme.opsweave.aicontrol.domain.SkillCheck;
import com.acme.opsweave.aicontrol.domain.SkillDraft;
import com.acme.opsweave.aicontrol.domain.SkillRelease;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.ai.SkillJson;
import com.acme.opsweave.sharedkernel.TenantId;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL Skill registry (ADR-105, migration V073). Commands are idempotent on the request id: a
 * replay answers from {@code ai_control.skill_command} and a reused request id with different
 * content is an explicit conflict, so no second draft or version is ever created. Published
 * versions are insert-only rows — the store has no update or delete path for them — and the release
 * pointer only moves through the publish and rollback commands. Reads never write.
 */
final class PostgresSkillReleases implements SkillReleaseStore {

    private final DataSource source;

    PostgresSkillReleases(DataSource source) {
        this.source = source;
    }

    @Override
    public SkillDraft.SaveReceipt save(SkillDraft.SaveCommand command, TenantId tenant, SubjectId author, Instant at) {
        var out = new SkillDraft.SaveReceipt[1];
        Transactions.run(source, c -> {
            var prior = replay(c, tenant, command.requestId(), "SAVE", command.commandDigest());
            if (prior != null) {
                out[0] = SkillDraftSaveReceipts.decode(prior);
                return;
            }
            lockSkill(c, tenant, command.skillId());
            SkillDraft.SaveReceipt receipt;
            var existing = findDraft(c, tenant, command.skillId(), command.version());
            if (existing.isEmpty()) {
                if (command.editVersion() != 1) throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
                var draft = SkillDraft.create(tenant, UUID.randomUUID(), command.skillId(), command.version(),
                    command.content(), author, at);
                insertDraft(c, draft);
                receipt = new SkillDraft.SaveReceipt(command.requestId(), draft.draftId(), draft.skillId(),
                    draft.version(), draft.state(), draft.editVersion(), draft.contentDigest(), at);
            } else {
                if (SkillDraft.reservedCoordinate(command.skillId(), command.version())) {
                    throw new SkillRelease.Conflict("SKILL_COORDINATE_RESERVED");
                }
                var draft = existing.get().saved(command.content(), command.editVersion(), at);
                updateDraft(c, draft);
                receipt = new SkillDraft.SaveReceipt(command.requestId(), draft.draftId(), draft.skillId(),
                    draft.version(), draft.state(), draft.editVersion(), draft.contentDigest(), at);
            }
            insertCommand(c, tenant, command.requestId(), "SAVE", command.commandDigest(),
                SkillDraftSaveReceipts.encode(receipt), at);
            out[0] = receipt;
        });
        return out[0];
    }

    @Override
    public SkillDraft.ReviewReceipt submit(SkillDraft.ReviewCommand command, TenantId tenant, Instant at) {
        var out = new SkillDraft.ReviewReceipt[1];
        Transactions.run(source, c -> {
            var prior = replay(c, tenant, command.requestId(), "SUBMIT", command.commandDigest());
            if (prior != null) {
                out[0] = SkillDraftSaveReceipts.decodeReview(prior);
                return;
            }
            var existing = lockedDraft(c, tenant, command.draftId());
            var next = existing.submitted(at);
            updateDraft(c, next);
            var receipt = new SkillDraft.ReviewReceipt(command.requestId(), next.draftId(), next.skillId(),
                next.version(), next.state(), at);
            insertCommand(c, tenant, command.requestId(), "SUBMIT", command.commandDigest(),
                SkillDraftSaveReceipts.encodeReview(receipt), at);
            out[0] = receipt;
        });
        return out[0];
    }

    @Override
    public SkillDraft.PublishReceipt publish(SkillDraft.PublishCommand command, TenantId tenant,
            SubjectId reviewer, SkillCheck check, Instant at) {
        var out = new SkillDraft.PublishReceipt[1];
        Transactions.run(source, c -> {
            var prior = replay(c, tenant, command.requestId(), "PUBLISH", command.commandDigest());
            if (prior != null) {
                var body = com.acme.opsweave.platform.catalog.CatalogJson.JSON.readTree(prior);
                var published = findVersion(c, tenant, SkillDraftSaveReceipts.text(body, "skillId"),
                    SkillDraftSaveReceipts.text(body, "version"))
                    .orElseThrow(() -> new IllegalStateException("Published Skill receipt has no version"));
                // Public receipts omit identity. Recover the original reviewer from the immutable
                // private version record, rather than inventing an actor when replaying a command.
                var receipt = SkillDraftSaveReceipts.decodePublish(prior, published.review());
                if (!receipt.contentDigest().equals(published.contentDigest())
                    || !receipt.publishedAt().equals(published.publishedAt())) {
                    throw new IllegalStateException("Published Skill receipt does not match its version");
                }
                out[0] = receipt;
                return;
            }
            var existing = lockedDraft(c, tenant, command.draftId());
            if (!check.result().passed() || !check.contentDigest().equals(existing.contentDigest())) {
                throw new SkillRelease.Conflict("SKILL_CHECK_FAILED");
            }
            var version = existing.published(reviewer, at);
            if (findVersion(c, tenant, version.skillId(), version.version()).isPresent()) {
                throw new SkillRelease.Conflict("SKILL_VERSION_IMMUTABLE");
            }
            insertVersion(c, version, reviewer);
            updateDraft(c, new SkillDraft(existing.tenantId(), existing.draftId(), existing.skillId(),
                existing.version(), existing.content(), existing.contentDigest(), SkillDraft.State.PUBLISHED,
                existing.editVersion(), existing.author(), existing.createdAt(), at));
            upsertPointer(c, tenant, version.skillId(), version.version(), at);
            var receipt = new SkillDraft.PublishReceipt(command.requestId(), existing.draftId(), version.skillId(),
                version.version(), version.contentDigest(), version.review(), at);
            insertCommand(c, tenant, command.requestId(), "PUBLISH", command.commandDigest(),
                SkillDraftSaveReceipts.encodePublish(receipt), at);
            out[0] = receipt;
        });
        return out[0];
    }

    @Override
    public SkillRelease.RollbackReceipt rollback(SkillRelease.RollbackCommand command, TenantId tenant,
            SubjectId actor, Instant at) {
        var out = new SkillRelease.RollbackReceipt[1];
        Transactions.run(source, c -> {
            var prior = replay(c, tenant, command.requestId(), "ROLLBACK", command.commandDigest());
            if (prior != null) {
                out[0] = SkillDraftSaveReceipts.decodeRollback(prior);
                return;
            }
            lockSkill(c, tenant, command.skillId());
            var pointer = pointer(c, tenant, command.skillId())
                .orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
            if (command.toVersion().equals(pointer.currentVersion())) {
                throw new SkillRelease.Conflict("SKILL_STATE_CONFLICT");
            }
            if (findVersion(c, tenant, command.skillId(), command.toVersion()).isEmpty()) {
                throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
            }
            upsertPointer(c, tenant, command.skillId(), command.toVersion(), at);
            var receipt = new SkillRelease.RollbackReceipt(command.requestId(), command.skillId(),
                pointer.currentVersion(), command.toVersion(), command.reason(), at);
            insertCommand(c, tenant, command.requestId(), "ROLLBACK", command.commandDigest(),
                SkillDraftSaveReceipts.encodeRollback(receipt), at);
            out[0] = receipt;
        });
        return out[0];
    }

    @Override
    public Optional<SkillDraft> findDraft(TenantId tenant, UUID draftId) {
        try (var c = source.getConnection()) {
            return findDraft(c, tenant, draftId);
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory database read failed", failed);
        }
    }

    @Override
    public List<SkillDraft> pageDrafts(TenantId tenant, String after, int limit) {
        if (limit < 1) throw new IllegalArgumentException("page limit out of bounds");
        try (var c = source.getConnection()) {
            Timestamp cursorAt = null;
            if (after != null) {
                try (var anchor = c.prepareStatement("SELECT created_at FROM ai_control.skill_draft"
                    + " WHERE tenant_id=? AND draft_id::text=?")) {
                    anchor.setString(1, tenant.value());
                    anchor.setString(2, after);
                    try (var rows = anchor.executeQuery()) {
                        if (!rows.next()) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
                        cursorAt = rows.getTimestamp("created_at");
                    }
                }
            }
            String sql = "SELECT body,author_subject FROM ai_control.skill_draft WHERE tenant_id=?";
            if (after != null) sql += " AND (created_at<? OR (created_at=? AND draft_id::text<?))";
            sql += " ORDER BY created_at DESC, draft_id::text DESC LIMIT ?";
            try (var read = c.prepareStatement(sql)) {
                read.setString(1, tenant.value());
                if (after != null) {
                    read.setTimestamp(2, cursorAt);
                    read.setTimestamp(3, cursorAt);
                    read.setString(4, after);
                    read.setInt(5, limit);
                } else {
                    read.setInt(2, limit);
                }
                try (var rows = read.executeQuery()) {
                    var page = new ArrayList<SkillDraft>();
                    while (rows.next()) {
                        page.add(SkillJson.decodeDraft(rows.getString("body"), tenant,
                            new SubjectId(rows.getString("author_subject"))));
                    }
                    return List.copyOf(page);
                }
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory database read failed", failed);
        }
    }

    @Override
    public Optional<SkillRelease.Version> findVersion(TenantId tenant, String skillId, String version) {
        try (var c = source.getConnection()) {
            return findVersion(c, tenant, skillId, version);
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory database read failed", failed);
        }
    }

    @Override
    public List<SkillRelease.Version> pageVersions(TenantId tenant, String skillId, String after, int limit) {
        if (limit < 1) throw new IllegalArgumentException("page limit out of bounds");
        try (var c = source.getConnection()) {
            Timestamp cursorAt = null;
            if (after != null) {
                try (var anchor = c.prepareStatement("SELECT published_at FROM ai_control.skill_version"
                    + " WHERE tenant_id=? AND skill_id=? AND version=?")) {
                    anchor.setString(1, tenant.value());
                    anchor.setString(2, skillId);
                    anchor.setString(3, after);
                    try (var rows = anchor.executeQuery()) {
                        if (!rows.next()) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
                        cursorAt = rows.getTimestamp("published_at");
                    }
                }
            }
            String sql = "SELECT body,reviewer_subject FROM ai_control.skill_version WHERE tenant_id=? AND skill_id=?";
            if (after != null) sql += " AND (published_at<? OR (published_at=? AND version<?))";
            sql += " ORDER BY published_at DESC, version DESC LIMIT ?";
            try (var read = c.prepareStatement(sql)) {
                read.setString(1, tenant.value());
                read.setString(2, skillId);
                if (after != null) {
                    read.setTimestamp(3, cursorAt);
                    read.setTimestamp(4, cursorAt);
                    read.setString(5, after);
                    read.setInt(6, limit);
                } else {
                    read.setInt(3, limit);
                }
                try (var rows = read.executeQuery()) {
                    var page = new ArrayList<SkillRelease.Version>();
                    while (rows.next()) {
                        page.add(SkillJson.decodeVersion(rows.getString("body"), tenant,
                            new SubjectId(rows.getString("reviewer_subject"))));
                    }
                    return List.copyOf(page);
                }
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory database read failed", failed);
        }
    }

    @Override
    public Optional<SkillRelease.Pointer> pointer(TenantId tenant, String skillId) {
        try (var c = source.getConnection()) {
            return pointer(c, tenant, skillId);
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory database read failed", failed);
        }
    }

    // ---- internals ----

    /**
     * Every command first locks its request id, then its tenant/Skill. This serializes CAS edits,
     * review/publication and pointer movement across distinct request ids and versions. No draft
     * row lock is acquired before the Skill lock, so commands share one lock order.
     */
    private static void lockSkill(Connection c, TenantId tenant, String skillId) throws SQLException {
        try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            lock.setString(1, "skill-state|" + tenant.value() + "|" + skillId);
            lock.executeQuery();
        }
    }

    private static SkillDraft lockedDraft(Connection c, TenantId tenant, UUID draftId) throws SQLException {
        var before = findDraft(c, tenant, draftId)
            .orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
        lockSkill(c, tenant, before.skillId());
        // The lookup before the Skill lock only identifies the immutable coordinate. State and
        // content must be read again after all competing commands on this Skill have finished.
        return findDraft(c, tenant, draftId).orElseThrow(() -> new ToolFailure(ToolFailure.Code.NOT_FOUND));
    }

    /** First writer wins: a stored command with the same request id must name the same content. */
    private static String replay(Connection c, TenantId tenant, UUID requestId, String kind, String digest)
        throws SQLException {
        try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            lock.setString(1, "skill-command|" + tenant.value() + "|" + requestId);
            lock.executeQuery();
        }
        try (var read = c.prepareStatement(
            "SELECT kind,command_digest,receipt FROM ai_control.skill_command WHERE tenant_id=? AND request_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, requestId);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return null;
                if (!kind.equals(rows.getString("kind")) || !digest.equals(rows.getString("command_digest"))) {
                    throw new SkillRelease.Conflict("SKILL_REQUEST_REUSED");
                }
                return rows.getString("receipt");
            }
        }
    }

    private static void insertCommand(Connection c, TenantId tenant, UUID requestId, String kind, String digest,
            String receipt, Instant at) throws SQLException {
        try (var insert = c.prepareStatement("INSERT INTO ai_control.skill_command"
            + " (tenant_id,request_id,kind,command_digest,receipt,created_at_text,created_at) VALUES (?,?,?,?,?::jsonb,?,?)")) {
            insert.setString(1, tenant.value());
            insert.setObject(2, requestId);
            insert.setString(3, kind);
            insert.setString(4, digest);
            insert.setString(5, receipt);
            insert.setString(6, at.toString());
            insert.setTimestamp(7, Timestamp.from(at));
            insert.executeUpdate();
        }
    }

    private static void insertDraft(Connection c, SkillDraft draft) throws SQLException {
        try (var insert = c.prepareStatement("INSERT INTO ai_control.skill_draft"
            + " (tenant_id,draft_id,skill_id,version,state,edit_version,content_digest,body,author_subject,"
            + "created_at_text,updated_at_text,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)")) {
            int i = 1;
            insert.setString(i++, draft.tenantId().value());
            insert.setObject(i++, draft.draftId());
            insert.setString(i++, draft.skillId());
            insert.setString(i++, draft.version());
            insert.setString(i++, draft.state().name());
            insert.setLong(i++, draft.editVersion());
            insert.setString(i++, draft.contentDigest());
            insert.setString(i++, SkillJson.storage(draft));
            insert.setString(i++, draft.author().value());
            insert.setString(i++, draft.createdAt().toString());
            insert.setString(i++, draft.updatedAt().toString());
            insert.setTimestamp(i++, Timestamp.from(draft.createdAt()));
            insert.setTimestamp(i, Timestamp.from(draft.updatedAt()));
            insert.executeUpdate();
        }
    }

    private static void updateDraft(Connection c, SkillDraft draft) throws SQLException {
        try (var update = c.prepareStatement("UPDATE ai_control.skill_draft SET state=?,edit_version=?,"
            + "content_digest=?,body=?::jsonb,updated_at_text=?,updated_at=? WHERE tenant_id=? AND draft_id=?")) {
            update.setString(1, draft.state().name());
            update.setLong(2, draft.editVersion());
            update.setString(3, draft.contentDigest());
            update.setString(4, SkillJson.storage(draft));
            update.setString(5, draft.updatedAt().toString());
            update.setTimestamp(6, Timestamp.from(draft.updatedAt()));
            update.setString(7, draft.tenantId().value());
            update.setObject(8, draft.draftId());
            update.executeUpdate();
        }
    }

    private static Optional<SkillDraft> findDraft(Connection c, TenantId tenant, UUID draftId) throws SQLException {
        try (var read = c.prepareStatement(
            "SELECT body,author_subject FROM ai_control.skill_draft WHERE tenant_id=? AND draft_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, draftId);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(SkillJson.decodeDraft(rows.getString("body"), tenant,
                    new SubjectId(rows.getString("author_subject"))));
            }
        }
    }

    private static Optional<SkillDraft> findDraft(Connection c, TenantId tenant, String skillId, String version)
        throws SQLException {
        try (var read = c.prepareStatement(
            "SELECT body,author_subject FROM ai_control.skill_draft WHERE tenant_id=? AND skill_id=? AND version=?")) {
            read.setString(1, tenant.value());
            read.setString(2, skillId);
            read.setString(3, version);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(SkillJson.decodeDraft(rows.getString("body"), tenant,
                    new SubjectId(rows.getString("author_subject"))));
            }
        }
    }

    /** Insert-only: there is deliberately no update or delete path for a published version. */
    private static void insertVersion(Connection c, SkillRelease.Version version, SubjectId reviewer)
        throws SQLException {
        try (var insert = c.prepareStatement("INSERT INTO ai_control.skill_version"
            + " (tenant_id,skill_id,version,content_digest,body,reviewer_subject,published_at_text,published_at)"
            + " VALUES (?,?,?,?,?::jsonb,?,?,?)")) {
            int i = 1;
            insert.setString(i++, version.tenantId().value());
            insert.setString(i++, version.skillId());
            insert.setString(i++, version.version());
            insert.setString(i++, version.contentDigest());
            insert.setString(i++, SkillJson.storage(version));
            insert.setString(i++, reviewer.value());
            insert.setString(i++, version.publishedAt().toString());
            insert.setTimestamp(i, Timestamp.from(version.publishedAt()));
            insert.executeUpdate();
        }
    }

    private static Optional<SkillRelease.Version> findVersion(Connection c, TenantId tenant, String skillId,
            String version) throws SQLException {
        try (var read = c.prepareStatement(
            "SELECT body,reviewer_subject FROM ai_control.skill_version WHERE tenant_id=? AND skill_id=? AND version=?")) {
            read.setString(1, tenant.value());
            read.setString(2, skillId);
            read.setString(3, version);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(SkillJson.decodeVersion(rows.getString("body"), tenant,
                    new SubjectId(rows.getString("reviewer_subject"))));
            }
        }
    }

    private static void upsertPointer(Connection c, TenantId tenant, String skillId, String version, Instant at)
        throws SQLException {
        try (var upsert = c.prepareStatement("INSERT INTO ai_control.skill_release"
            + " (tenant_id,skill_id,current_version,updated_at_text,updated_at) VALUES (?,?,?,?,?)"
            + " ON CONFLICT (tenant_id,skill_id) DO UPDATE SET current_version=EXCLUDED.current_version,"
            + "updated_at_text=EXCLUDED.updated_at_text,updated_at=EXCLUDED.updated_at")) {
            upsert.setString(1, tenant.value());
            upsert.setString(2, skillId);
            upsert.setString(3, version);
            upsert.setString(4, at.toString());
            upsert.setTimestamp(5, Timestamp.from(at));
            upsert.executeUpdate();
        }
    }

    private static Optional<SkillRelease.Pointer> pointer(Connection c, TenantId tenant, String skillId)
        throws SQLException {
        try (var read = c.prepareStatement(
            "SELECT current_version,updated_at_text FROM ai_control.skill_release WHERE tenant_id=? AND skill_id=?")) {
            read.setString(1, tenant.value());
            read.setString(2, skillId);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new SkillRelease.Pointer(tenant, skillId, rows.getString("current_version"),
                    Instant.parse(rows.getString("updated_at_text"))));
            }
        }
    }

    /** Receipt encoding lives beside the store: a stored receipt is accepted only when it matches. */
    static final class SkillDraftSaveReceipts {
        private SkillDraftSaveReceipts() {}

        static String encode(SkillDraft.SaveReceipt receipt) {
            return com.acme.opsweave.platform.catalog.CatalogJson.JSON.writeValueAsString(
                SkillJson.wire(receipt));
        }

        static SkillDraft.SaveReceipt decode(String json) {
            var n = com.acme.opsweave.platform.catalog.CatalogJson.JSON.readTree(json);
            return new SkillDraft.SaveReceipt(uuid(n, "requestId"), uuid(n, "draftId"),
                text(n, "skillId"), text(n, "version"), SkillDraft.State.valueOf(text(n, "state")),
                longValue(n, "editVersion"), text(n, "contentDigest"), Instant.parse(text(n, "savedAt")));
        }

        static String encodeReview(SkillDraft.ReviewReceipt receipt) {
            return com.acme.opsweave.platform.catalog.CatalogJson.JSON.writeValueAsString(
                SkillJson.wire(receipt));
        }

        static SkillDraft.ReviewReceipt decodeReview(String json) {
            var n = com.acme.opsweave.platform.catalog.CatalogJson.JSON.readTree(json);
            return new SkillDraft.ReviewReceipt(uuid(n, "requestId"), uuid(n, "draftId"),
                text(n, "skillId"), text(n, "version"), SkillDraft.State.valueOf(text(n, "state")),
                Instant.parse(text(n, "submittedAt")));
        }

        static String encodePublish(SkillDraft.PublishReceipt receipt) {
            return com.acme.opsweave.platform.catalog.CatalogJson.JSON.writeValueAsString(
                SkillJson.wire(receipt));
        }

        static SkillDraft.PublishReceipt decodePublish(String json, SkillRelease.Review originalReview) {
            var n = com.acme.opsweave.platform.catalog.CatalogJson.JSON.readTree(json);
            var review = n.get("review");
            if (!originalReview.approvedAt().equals(Instant.parse(text(review, "approvedAt")))) {
                throw new IllegalStateException("Published Skill receipt does not match its review");
            }
            return new SkillDraft.PublishReceipt(uuid(n, "requestId"), uuid(n, "draftId"),
                text(n, "skillId"), text(n, "version"), text(n, "contentDigest"),
                originalReview,
                Instant.parse(text(n, "publishedAt")));
        }

        static String encodeRollback(SkillRelease.RollbackReceipt receipt) {
            return com.acme.opsweave.platform.catalog.CatalogJson.JSON.writeValueAsString(
                SkillJson.wire(receipt));
        }

        static SkillRelease.RollbackReceipt decodeRollback(String json) {
            var n = com.acme.opsweave.platform.catalog.CatalogJson.JSON.readTree(json);
            return new SkillRelease.RollbackReceipt(uuid(n, "requestId"), text(n, "skillId"),
                text(n, "fromVersion"), text(n, "toVersion"), text(n, "reason"),
                Instant.parse(text(n, "rolledBackAt")));
        }

        private static String text(tools.jackson.databind.JsonNode n, String field) {
            return com.acme.opsweave.platform.integration.PipelineJson.text(n, field);
        }

        private static UUID uuid(tools.jackson.databind.JsonNode n, String field) {
            try {
                return UUID.fromString(text(n, field));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid stored receipt");
            }
        }

        private static long longValue(tools.jackson.databind.JsonNode n, String field) {
            var value = n.get(field);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new IllegalArgumentException("Invalid stored receipt");
            }
            return value.asLong();
        }
    }
}
