package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.catalog.api.ModelCatalogStore;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.TenantId;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class PostgresModelCatalogStore implements ModelCatalogStore {
    private final DataSource source;
    PostgresModelCatalogStore(DataSource source) { this.source = source; }
    public Entry save(TenantId tenant, String owner, ModelDefinition definition, int expected, Instant now) {
        CatalogRules.editable(definition, expected);
        return transaction(tenant, c -> {
            if (find(c, tenant, definition.ref()).isPresent()) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
            String sql = expected == 0
                ? "INSERT INTO catalog.model_draft (definition,digest,edit_version,updated_at,tenant_id,owner_subject,model_id,revision) VALUES (?::jsonb,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING"
                : "UPDATE catalog.model_draft SET definition=?::jsonb,digest=?,edit_version=?,updated_at=? WHERE tenant_id=? AND owner_subject=? AND model_id=? AND revision=? AND edit_version=?";
            try (var s = prepare(c, sql)) {
                s.setString(1, CatalogJson.encode(definition)); s.setString(2, definition.digest()); s.setInt(3, expected + 1); s.setTimestamp(4, Timestamp.from(now));
                s.setString(5, tenant.value()); s.setString(6, owner); s.setString(7, definition.id()); s.setInt(8, definition.revision()); if (expected != 0) s.setInt(9, expected);
                if (s.executeUpdate() != 1) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
            }
            return new Entry(definition, definition.digest(), "DRAFT", expected + 1, now);
        });
    }
    public Entry publish(TenantId tenant, String owner, ModelDefinition.Ref ref, int expected, String digest, List<ModelDefinition> builtins, Instant now) {
        return transaction(tenant, c -> {
            Entry draft;
            try (var s = prepare(c, "SELECT *, 'DRAFT' AS state FROM catalog.model_draft WHERE tenant_id=? AND owner_subject=? AND model_id=? AND revision=? FOR UPDATE")) {
                s.setString(1, tenant.value()); s.setString(2, owner); s.setString(3, ref.id()); s.setInt(4, ref.revision());
                try (var r = s.executeQuery()) { if (!r.next()) throw new CatalogFailure(CatalogFailure.Code.NOT_FOUND); draft = entry(r); }
            }
            if (draft.editVersion() != expected || !draft.digest().equals(digest)) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
            var existing = find(c, tenant, ref);
            if (existing.isPresent()) { if (existing.get().digest().equals(digest)) return existing.get(); throw new CatalogFailure(CatalogFailure.Code.CONFLICT); }
            Optional<ModelDefinition> latest = Optional.empty();
            try (var s = prepare(c, "SELECT *, 'PUBLISHED' AS state, 0 AS edit_version, published_at AS updated_at FROM catalog.model_version WHERE tenant_id=? AND model_id=? ORDER BY revision DESC LIMIT 1")) {
                s.setString(1, tenant.value()); s.setString(2, ref.id()); try (var r = s.executeQuery()) { if (r.next()) latest = Optional.of(entry(r).definition()); }
            }
            CatalogRules.publication(draft.definition(), latest, r -> builtins.stream().filter(d -> d.ref().equals(r)).findFirst().or(() -> {
                try { return find(c, tenant, r).map(Entry::definition); } catch (SQLException failed) { throw unavailable(); }
            }));
            try (var s = prepare(c, "INSERT INTO catalog.model_version (tenant_id,model_id,revision,definition,digest,published_by,published_at) VALUES (?,?,?,?::jsonb,?,?,?)")) {
                s.setString(1, tenant.value()); s.setString(2, ref.id()); s.setInt(3, ref.revision()); s.setString(4, CatalogJson.encode(draft.definition()));
                s.setString(5, digest); s.setString(6, owner); s.setTimestamp(7, Timestamp.from(now)); s.executeUpdate();
            }
            return new Entry(draft.definition(), digest, "PUBLISHED", 0, now);
        });
    }
    public Optional<Entry> find(TenantId tenant, ModelDefinition.Ref ref) {
        try (var c = source.getConnection()) { return find(c, tenant, ref); } catch (SQLException failed) { throw unavailable(); }
    }
    private Optional<Entry> find(Connection c, TenantId tenant, ModelDefinition.Ref ref) throws SQLException {
        try (var s = prepare(c, "SELECT *, 'PUBLISHED' AS state, 0 AS edit_version, published_at AS updated_at FROM catalog.model_version WHERE tenant_id=? AND model_id=? AND revision=?")) {
            s.setString(1, tenant.value()); s.setString(2, ref.id()); s.setInt(3, ref.revision()); try (var r = s.executeQuery()) { return r.next() ? Optional.of(entry(r)) : Optional.empty(); }
        }
    }
    public List<Entry> published(TenantId tenant, int limit) { return list(tenant, null, limit); }
    public List<Entry> drafts(TenantId tenant, String owner, int limit) { return list(tenant, owner, limit); }
    private List<Entry> list(TenantId tenant, String owner, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException();
        String sql = owner == null
            ? "SELECT *, 'PUBLISHED' AS state, 0 AS edit_version, published_at AS updated_at FROM catalog.model_version WHERE tenant_id=? ORDER BY model_id,revision DESC LIMIT ?"
            : "SELECT *, 'DRAFT' AS state FROM catalog.model_draft WHERE tenant_id=? AND owner_subject=? ORDER BY model_id,revision DESC LIMIT ?";
        try (var c = source.getConnection(); var s = prepare(c, sql)) {
            s.setString(1, tenant.value()); int index = 2; if (owner != null) s.setString(index++, owner); s.setInt(index, limit);
            var result = new ArrayList<Entry>(); try (var r = s.executeQuery()) { while (r.next()) result.add(entry(r)); } return List.copyOf(result);
        } catch (SQLException failed) { throw unavailable(); }
    }
    private Entry entry(ResultSet r) throws SQLException {
        try {
            var definition = CatalogJson.decode(r.getString("definition"));
            if (!definition.id().equals(r.getString("model_id")) || definition.revision() != r.getInt("revision") || !definition.digest().equals(r.getString("digest"))) throw unavailable();
            return new Entry(definition, definition.digest(), r.getString("state"), r.getInt("edit_version"), r.getTimestamp("updated_at").toInstant());
        } catch (IllegalArgumentException invalid) { throw unavailable(); }
    }
    private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(TenantId tenant, Work<T> work) {
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Serialize catalog writes per tenant, including endpoint lookup and revision checks.
                try (var s = prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) { s.setString(1, "model-catalog:" + tenant.value()); s.execute(); }
                T result = work.run(c); c.commit(); return result;
            } catch (RuntimeException | SQLException failed) { c.rollback(); throw failed; }
        } catch (SQLException failed) { throw unavailable(); }
    }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException { var s = c.prepareStatement(sql); s.setQueryTimeout(5); return s; }
    private static IllegalStateException unavailable() { return new IllegalStateException("Model catalog unavailable"); }
}
