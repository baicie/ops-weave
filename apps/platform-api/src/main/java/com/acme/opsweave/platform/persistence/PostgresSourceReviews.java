package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.SourceReviewStore;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.inventory.SourceReviewJson;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Shares the entity lock and transaction with primary ingestion; DB unique indexes fence bindings. */
final class PostgresSourceReviews implements SourceReviewStore {
    private final DataSource dataSource;
    PostgresSourceReviews(DataSource dataSource) { this.dataSource = dataSource; }
    static void lock(Connection c, TenantId tenant, EntityId entity) throws SQLException {
        try (var s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            s.setString(1, "entity-review:" + tenant.value().length() + ":" + tenant.value() + entity.value()); s.setQueryTimeout(5); s.execute();
        }
    }
    static Entity entity(Connection c, TenantId tenant, EntityId entity) throws SQLException {
        try (var s = c.prepareStatement("SELECT entity_type, name, lifecycle, version, last_seen_epoch_nanos, CASE WHEN octet_length(attributes::text) <= 16384 THEN attributes END AS attributes FROM inventory.entity WHERE tenant_id = ? AND id = ?")) {
            bind(s, tenant, entity); try (var rows = s.executeQuery()) {
                if (!rows.next()) throw new SourceReview.Conflict("Entity missing");
                String attrs = rows.getString("attributes"); if (attrs == null) throw new IllegalStateException("Entity exceeds read budget");
                Map<String,Object> values = SourceReviewJson.JSON.readValue(attrs, new tools.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});
                return new Entity(entity, tenant, rows.getString("entity_type"), rows.getString("name"), Lifecycle.valueOf(rows.getString("lifecycle")), rows.getLong("version"), PostgresInventoryStore.instant(rows.getBigDecimal("last_seen_epoch_nanos")), values);
            }
        }
    }
    static Entity primary(Connection c, TenantId tenant, EntityId entity) throws SQLException {
        try (var s = c.prepareStatement("SELECT primary_snapshot FROM inventory.entity_source_authority WHERE tenant_id = ? AND entity_id = ?")) {
            bind(s, tenant, entity); try (var rows = s.executeQuery()) {
                if (!rows.next()) return null;
                var snapshot = SourceReviewJson.snapshot(rows.getString(1));
                if (!snapshot.tenantId().equals(tenant) || !snapshot.id().equals(entity)) throw new IllegalStateException("Primary snapshot scope mismatch");
                return snapshot;
            }
        }
    }
    static void primary(Connection c, Entity e) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO inventory.entity_source_authority(tenant_id, entity_id, primary_snapshot) VALUES (?, ?, ?::jsonb) ON CONFLICT (tenant_id, entity_id) DO UPDATE SET primary_snapshot = EXCLUDED.primary_snapshot")) {
            bind(s, e.tenantId(), e.id()); s.setString(3, SourceReviewJson.snapshot(e)); s.executeUpdate();
        }
    }
    static SourceReview active(Connection c, TenantId tenant, EntityId entity) throws SQLException {
        try (var s = c.prepareStatement("SELECT body FROM inventory.source_review WHERE tenant_id = ? AND entity_id = ? AND active")) {
            bind(s, tenant, entity); try (var rows = s.executeQuery()) { return rows.next() ? SourceReviewJson.decode(rows.getString(1)) : null; }
        }
    }
    static Entity incoming(Connection c, Entity e) throws SQLException {
        var primary = primary(c, e.tenantId(), e.id());
        if (primary == null || e.lastSeen().isBefore(primary.lastSeen())) return e;
        var projected = PostgresSourceSnapshots.project(c,e, active(c, e.tenantId(), e.id()), e.version());
        primary(c, e); return projected;
    }
    static void updateEntity(Connection c, Entity e) throws SQLException {
        try (var s = c.prepareStatement("UPDATE inventory.entity SET name = ?, lifecycle = ?, version = ?, attributes = ?::jsonb WHERE tenant_id = ? AND id = ?")) {
            s.setString(1, e.name()); s.setString(2, e.lifecycle().name()); s.setLong(3, e.version()); s.setString(4, SourceReviewJson.JSON.writeValueAsString(e.attributes()));
            s.setString(5, e.tenantId().value()); s.setObject(6, e.id().value()); s.setQueryTimeout(5);
            if (s.executeUpdate() != 1) throw new SourceReview.Conflict("Entity changed");
        }
    }
    @Override public SourceReview stage(TenantId tenant, EntityId entity, Import input, Instant now) {
        if (!input.source().tenantId().equals(tenant)) throw new IllegalArgumentException("Import tenant mismatch");
        SourceReview[] result = {null};
        Transactions.run(dataSource, c -> {
            lock(c, tenant, entity); var current = entity(c, tenant, entity);
            // The import id is tenant-wide, including across different target entities.
            try (var s = c.prepareStatement("SELECT body FROM inventory.source_review WHERE tenant_id = ? AND id = ?")) {
                s.setString(1, tenant.value()); s.setObject(2, input.id()); s.setQueryTimeout(5);
                try (var rows = s.executeQuery()) { if (rows.next()) {
                    var old = SourceReviewJson.decode(rows.getString(1));
                    if (!old.entityId().equals(entity) || !input.matches(old)) throw new SourceReview.Conflict("Import id reused");
                    result[0] = old; return;
                } }
            }
            PostgresAssetIdentities.validatePin(c,tenant,entity,input.identity());
            var primary = primary(c, tenant, entity); if (primary == null) primary = current;
            var review = input.stage(primary, current.version(), now);
            try (var s = c.prepareStatement("INSERT INTO inventory.source_review(tenant_id, id, entity_id, source_instance_id, external_id, body) VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (tenant_id, id) DO NOTHING")) {
                s.setString(1, tenant.value()); s.setObject(2, review.id()); s.setObject(3, entity.value()); s.setString(4, review.source().sourceInstanceId());
                s.setString(5, review.source().externalId()); s.setString(6, SourceReviewJson.encode(review)); s.setQueryTimeout(5);
                if (s.executeUpdate() != 1) throw new SourceReview.Conflict("Import id reused");
            }
            primary(c, primary); result[0] = review;
        }); return result[0];
    }
    @Override public SourceReview decide(TenantId tenant, EntityId entity, String sourceId, UUID reviewId, SourceReview.Command command, Instant now) {
        SourceReview[] result = {null};
        Transactions.run(dataSource, c -> {
            lock(c, tenant, entity);
            try (var s = c.prepareStatement("SELECT review_id, command, result FROM inventory.source_review_receipt WHERE tenant_id = ? AND entity_id = ? AND request_id = ?")) {
                bind(s, tenant, entity); s.setObject(3, command.requestId());
                try (var rows = s.executeQuery()) { if (rows.next()) {
                    var stored = SourceReviewJson.decode(rows.getString("result"));
                    if (!reviewId.equals(rows.getObject("review_id", UUID.class)) || !command.equals(SourceReviewJson.command(rows.getString("command"))) || !sourceId.equals(stored.source().sourceInstanceId())) throw new SourceReview.Conflict("Decision id reused");
                    result[0] = stored; return;
                } }
            }
            SourceReview review;
            try (var s = c.prepareStatement("SELECT body FROM inventory.source_review WHERE tenant_id = ? AND entity_id = ? AND id = ? AND source_instance_id = ?")) {
                bind(s, tenant, entity); s.setObject(3, reviewId); s.setString(4, sourceId);
                try (var rows = s.executeQuery()) { if (!rows.next()) throw new SourceReview.Conflict("Review missing"); review = SourceReviewJson.decode(rows.getString(1)); }
            }
            var current = entity(c, tenant, entity); var changed = review.decide(command, current.version(), now);
            var active = active(c, tenant, entity);
            if (command.action() == SourceReview.Action.ACCEPT) {
                PostgresAssetIdentities.validatePin(c,tenant,entity,review.identity());
                if (active != null) throw new SourceReview.Conflict("Revoke the active review first");
                // Serialize two entities competing for the same supplemental external object.
                try (var s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    s.setString(1, "source-review-binding:" + tenant.value().length() + ":" + tenant.value() + ":" + sourceId.length() + ":" + sourceId + ":" + review.source().externalId()); s.setQueryTimeout(5); s.execute();
                }
                try (var s = c.prepareStatement("SELECT 1 FROM inventory.source_review WHERE tenant_id = ? AND source_instance_id = ? AND external_id = ? AND active")) {
                    s.setString(1, tenant.value()); s.setString(2, sourceId); s.setString(3, review.source().externalId()); s.setQueryTimeout(5);
                    try (var rows = s.executeQuery()) { if (rows.next()) throw new SourceReview.Conflict("Supplemental object is already bound"); }
                }
                // The snapshot path and manual review share both binding directions, including absent/revoked pins.
                try (var s = c.prepareStatement("SELECT entity_id, external_id FROM inventory.entity_source_presence WHERE tenant_id=? AND source_instance_id=? AND (external_id=? OR entity_id=?)")) {
                    s.setString(1, tenant.value()); s.setString(2, sourceId); s.setString(3, review.source().externalId()); s.setObject(4, entity.value()); s.setQueryTimeout(5);
                    try (var rows = s.executeQuery()) { while (rows.next()) if (!entity.value().equals(rows.getObject(1, UUID.class)) || !review.source().externalId().equals(rows.getString(2))) throw new SourceReview.Conflict("Supplemental object conflicts with snapshot binding"); }
                }
                updateEntity(c, PostgresSourceSnapshots.project(c,Objects.requireNonNull(primary(c, tenant, entity)), changed, current.version() + 1));
                PostgresInventoryStore.insertObservation(c, FieldAuthority.observation(changed));
            } else if (command.action() == SourceReview.Action.REVOKE) {
                if (active == null || !active.id().equals(reviewId)) throw new SourceReview.Conflict("Review is no longer active");
                updateEntity(c, PostgresSourceSnapshots.project(c,Objects.requireNonNull(primary(c, tenant, entity)), null, current.version() + 1));
            }
            try (var s = c.prepareStatement("UPDATE inventory.source_review SET body = ?::jsonb, active = ? WHERE tenant_id = ? AND id = ?")) {
                s.setString(1, SourceReviewJson.encode(changed)); s.setBoolean(2, changed.status() == SourceReview.Status.ACCEPTED); s.setString(3, tenant.value()); s.setObject(4, reviewId); s.setQueryTimeout(5); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO inventory.source_review_receipt(tenant_id, entity_id, request_id, review_id, command, result) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)")) {
                bind(s, tenant, entity); s.setObject(3, command.requestId()); s.setObject(4, reviewId); s.setString(5, SourceReviewJson.encode(command)); s.setString(6, SourceReviewJson.encode(changed)); s.executeUpdate();
            }
            result[0] = changed;
        }); return result[0];
    }
    @Override public Page reviews(TenantId tenant, EntityId entity, String sourceId, UUID after, int limit) {
        if (limit < 1 || limit > 25) throw new IllegalArgumentException("Invalid review limit");
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setReadOnly(true);
            try (var s = c.prepareStatement("SELECT body FROM inventory.source_review WHERE tenant_id = ? AND entity_id = ? AND source_instance_id = ? AND (?::uuid IS NULL OR id > ?::uuid) ORDER BY id LIMIT ?")) {
                bind(s, tenant, entity); s.setString(3, sourceId); s.setObject(4, after); s.setObject(5, after); s.setInt(6, limit + 1);
                var list = new ArrayList<SourceReview>(); try (var rows = s.executeQuery()) { while (rows.next()) list.add(SourceReviewJson.decode(rows.getString(1))); }
                var active = active(c, tenant, entity); c.commit(); return new Page(list, active != null && active.source().sourceInstanceId().equals(sourceId) ? active : null);
            }
        } catch (SQLException failed) { throw new IllegalStateException("Source review page unavailable"); }
    }
    private static void bind(PreparedStatement s, TenantId tenant, EntityId entity) throws SQLException { s.setString(1, tenant.value()); s.setObject(2, entity.value()); s.setQueryTimeout(5); }
}
