package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.catalog.api.ModelExtensionStore;
import com.acme.opsweave.catalog.domain.CatalogFailure;
import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.catalog.domain.ModelExtension;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.TenantId;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class PostgresModelExtensionStore implements ModelExtensionStore {
    private final DataSource source;
    PostgresModelExtensionStore(DataSource source) { this.source = source; }

    public Entry save(TenantId tenant, String owner, ModelDefinition.Ref base, List<ModelDefinition.Field> fields, int expected, Instant now) {
        return transaction(tenant, c -> {
            var shape = new ModelExtension(base, 1, fields);
            int revision = latestRevision(c, tenant, base) + 1;
            var extension = revision == 1 ? shape : new ModelExtension(base, revision, fields);
            String sql = expected == 0
                ? "INSERT INTO catalog.model_extension_draft (definition,digest,revision,edit_version,updated_at,tenant_id,owner_subject,base_id,base_revision) VALUES (?::jsonb,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING"
                : "UPDATE catalog.model_extension_draft SET definition=?::jsonb,digest=?,revision=?,edit_version=?,updated_at=? WHERE tenant_id=? AND owner_subject=? AND base_id=? AND base_revision=? AND edit_version=?";
            try (var s = prepare(c, sql)) {
                s.setString(1, CatalogJson.encode(extension)); s.setString(2, extension.digest()); s.setInt(3, revision);
                s.setInt(4, expected + 1); s.setTimestamp(5, Timestamp.from(now));
                s.setString(6, tenant.value()); s.setString(7, owner); s.setString(8, base.id()); s.setInt(9, base.revision());
                if (expected != 0) s.setInt(10, expected);
                if (s.executeUpdate() != 1) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
            }
            return new Entry(extension, "DRAFT", expected + 1, now);
        });
    }

    public Entry publish(TenantId tenant, String owner, ModelDefinition.Ref base, int expected, String digest, Instant now) {
        return transaction(tenant, c -> {
            ModelExtension draft;
            int draftEditVersion;
            try (var s = prepare(c, "SELECT definition, edit_version FROM catalog.model_extension_draft WHERE tenant_id=? AND owner_subject=? AND base_id=? AND base_revision=? FOR UPDATE")) {
                s.setString(1, tenant.value()); s.setString(2, owner); s.setString(3, base.id()); s.setInt(4, base.revision());
                try (var r = s.executeQuery()) {
                    if (!r.next()) throw new CatalogFailure(CatalogFailure.Code.NOT_FOUND);
                    draft = decode(r.getString("definition"));
                    draftEditVersion = r.getInt("edit_version");
                }
            }
            if (draftEditVersion != expected || !draft.digest().equals(digest)) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
            var existing = find(c, tenant, base, draft.revision());
            if (existing.isPresent()) {
                if (existing.get().extension().digest().equals(digest)) return existing.get();
                throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
            }
            int latest = latestRevision(c, tenant, base);
            if (draft.revision() != latest + 1) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
            if (latest == 0) {
                if (draft.revision() != 1) throw new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION);
            } else {
                draft.requireCompatibleWith(find(c, tenant, base, latest).orElseThrow(() -> new CatalogFailure(CatalogFailure.Code.NOT_FOUND)).extension());
            }
            try (var s = prepare(c, "INSERT INTO catalog.model_extension_version (tenant_id,base_id,base_revision,revision,definition,digest,published_by,published_at) VALUES (?,?,?,?,?::jsonb,?,?,?)")) {
                s.setString(1, tenant.value()); s.setString(2, base.id()); s.setInt(3, base.revision()); s.setInt(4, draft.revision());
                s.setString(5, CatalogJson.encode(draft)); s.setString(6, digest); s.setString(7, owner); s.setTimestamp(8, Timestamp.from(now));
                s.executeUpdate();
            }
            return new Entry(draft, "PUBLISHED", 0, now);
        });
    }

    public Optional<Entry> find(TenantId tenant, ModelDefinition.Ref base, int revision) {
        try (var c = source.getConnection()) { return find(c, tenant, base, revision); } catch (SQLException failed) { throw unavailable(); }
    }

    private Optional<Entry> find(Connection c, TenantId tenant, ModelDefinition.Ref base, int revision) throws SQLException {
        try (var s = prepare(c, "SELECT definition, digest, published_at FROM catalog.model_extension_version WHERE tenant_id=? AND base_id=? AND base_revision=? AND revision=?")) {
            s.setString(1, tenant.value()); s.setString(2, base.id()); s.setInt(3, base.revision()); s.setInt(4, revision);
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                var extension = decode(r.getString("definition"));
                if (!extension.digest().equals(r.getString("digest")) || extension.revision() != revision || !extension.base().equals(base)) throw unavailable();
                return Optional.of(new Entry(extension, "PUBLISHED", 0, r.getTimestamp("published_at").toInstant()));
            }
        }
    }

    public Optional<Entry> latest(TenantId tenant, ModelDefinition.Ref base) {
        try (var c = source.getConnection(); var s = prepare(c, "SELECT revision FROM catalog.model_extension_version WHERE tenant_id=? AND base_id=? AND base_revision=? ORDER BY revision DESC LIMIT 1")) {
            s.setString(1, tenant.value()); s.setString(2, base.id()); s.setInt(3, base.revision());
            try (var r = s.executeQuery()) { return r.next() ? find(c, tenant, base, r.getInt("revision")) : Optional.empty(); }
        } catch (SQLException failed) { throw unavailable(); }
    }

    public List<Entry> published(TenantId tenant, int limit) { return list(tenant, null, limit); }
    public List<Entry> drafts(TenantId tenant, String owner, int limit) { return list(tenant, owner, limit); }

    private List<Entry> list(TenantId tenant, String owner, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException();
        String sql = owner == null
            ? "SELECT definition, digest, 0 AS edit_version, published_at AS updated_at FROM catalog.model_extension_version WHERE tenant_id=? ORDER BY base_id,base_revision,revision DESC LIMIT ?"
            : "SELECT definition, digest, edit_version, updated_at FROM catalog.model_extension_draft WHERE tenant_id=? AND owner_subject=? ORDER BY base_id,base_revision LIMIT ?";
        try (var c = source.getConnection(); var s = prepare(c, sql)) {
            s.setString(1, tenant.value()); int index = 2; if (owner != null) s.setString(index++, owner); s.setInt(index, limit);
            var result = new ArrayList<Entry>();
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var extension = decode(r.getString("definition"));
                    if (!extension.digest().equals(r.getString("digest"))) throw unavailable();
                    result.add(new Entry(extension, owner == null ? "PUBLISHED" : "DRAFT", r.getInt("edit_version"), r.getTimestamp("updated_at").toInstant()));
                }
            }
            return List.copyOf(result);
        } catch (SQLException failed) { throw unavailable(); }
    }

    private int latestRevision(Connection c, TenantId tenant, ModelDefinition.Ref base) throws SQLException {
        try (var s = prepare(c, "SELECT COALESCE(MAX(revision),0) FROM catalog.model_extension_version WHERE tenant_id=? AND base_id=? AND base_revision=?")) {
            s.setString(1, tenant.value()); s.setString(2, base.id()); s.setInt(3, base.revision());
            try (var r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    private ModelExtension decode(String definition) {
        try { return CatalogJson.decodeExtension(definition); } catch (RuntimeException invalid) { throw unavailable(); }
    }

    private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(TenantId tenant, Work<T> work) {
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Serialize extension writes per tenant, including revision assignment and compatibility checks.
                try (var s = prepare(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) { s.setString(1, "model-extension:" + tenant.value()); s.execute(); }
                T result = work.run(c); c.commit(); return result;
            } catch (RuntimeException | SQLException failed) { c.rollback(); throw failed; }
        } catch (SQLException failed) { throw unavailable(); }
    }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException { var s = c.prepareStatement(sql); s.setQueryTimeout(5); return s; }
    private static IllegalStateException unavailable() { return new IllegalStateException("Model extension store unavailable"); }
}
