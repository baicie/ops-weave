package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.ExternalLinkRegistrationStore;
import com.acme.opsweave.inventory.domain.ExternalLinkRegistration;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

final class PostgresExternalLinkRegistrations implements ExternalLinkRegistrationStore {
    private static final String COLUMNS = "request_id,source_instance_id,external_type,external_id,generation,entity_id,reason,registered_at";
    private final DataSource source;
    PostgresExternalLinkRegistrations(DataSource source) { this.source = source; }

    @Override
    public Result register(ExternalLinkRegistration registration, Instant registeredAt) {
        TenantId tenant = registration.key().tenantId();
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("SELECT " + COLUMNS + " FROM inventory.external_link_registration WHERE tenant_id=? AND request_id=? FOR UPDATE")) {
                    s.setString(1, tenant.value());
                    s.setObject(2, registration.requestId());
                    try (var r = s.executeQuery()) {
                        if (r.next()) {
                            var previous = row(tenant, r, registeredAt);
                            if (!previous.registration().sameContent(registration)) throw new Conflict("External link request reused");
                            c.commit();
                            return new Result(previous, true);
                        }
                    }
                }
                try (var s = c.prepareStatement("""
                    INSERT INTO inventory.entity_external_link(tenant_id,source_instance_id,external_type,external_id,generation,entity_id)
                    VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,source_instance_id,external_type,external_id,generation) DO NOTHING
                    """)) {
                    s.setString(1, tenant.value());
                    s.setString(2, registration.key().sourceInstanceId());
                    s.setString(3, registration.key().externalType());
                    s.setString(4, registration.key().externalId());
                    s.setString(5, registration.key().generation());
                    s.setObject(6, registration.targetId().value());
                    s.executeUpdate();
                }
                try (var s = c.prepareStatement("SELECT entity_id FROM inventory.entity_external_link WHERE tenant_id=? AND source_instance_id=? AND external_type=? AND external_id=? AND generation=?")) {
                    s.setString(1, tenant.value());
                    s.setString(2, registration.key().sourceInstanceId());
                    s.setString(3, registration.key().externalType());
                    s.setString(4, registration.key().externalId());
                    s.setString(5, registration.key().generation());
                    try (var r = s.executeQuery()) {
                        if (!r.next() || !registration.targetId().value().equals(r.getObject(1, UUID.class))) {
                            throw new Conflict("External key already bound to another entity");
                        }
                    }
                }
                try (var s = c.prepareStatement("INSERT INTO inventory.external_link_registration(tenant_id,request_id,source_instance_id,external_type,external_id,generation,entity_id,reason,registered_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    s.setString(1, tenant.value());
                    s.setObject(2, registration.requestId());
                    s.setString(3, registration.key().sourceInstanceId());
                    s.setString(4, registration.key().externalType());
                    s.setString(5, registration.key().externalId());
                    s.setString(6, registration.key().generation());
                    s.setObject(7, registration.targetId().value());
                    if (registration.reason() == null) s.setNull(8, Types.VARCHAR); else s.setString(8, registration.reason());
                    s.setTimestamp(9, Timestamp.from(registeredAt));
                    s.executeUpdate();
                }
                c.commit();
                return new Result(new Entry(registration, registeredAt), false);
            } catch (RuntimeException | SQLException failed) {
                try { c.rollback(); } catch (SQLException ignored) {}
                if (failed instanceof ExternalLinkRegistrationStore.Conflict conflict) throw conflict;
                if (failed instanceof SQLException sql) throw new IllegalStateException("External link write unavailable", sql);
                throw new IllegalStateException("External link write unavailable");
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("External link write unavailable", failed);
        }
    }

    @Override
    public Optional<Entry> find(TenantId tenant, UUID requestId) {
        try (var c = source.getConnection();
             var s = c.prepareStatement("SELECT " + COLUMNS + " FROM inventory.external_link_registration WHERE tenant_id=? AND request_id=?")) {
            s.setString(1, tenant.value());
            s.setObject(2, requestId);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(row(tenant, r, Instant.EPOCH)) : Optional.empty();
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("External link read unavailable", failed);
        }
    }

    @Override
    public List<Entry> page(TenantId tenant, String namespace, UUID after, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid external link limit");
        String sql = "SELECT " + COLUMNS + " FROM inventory.external_link_registration WHERE tenant_id=?"
            + (namespace == null || namespace.isBlank() ? "" : " AND source_instance_id=?")
            + (after == null ? "" : " AND request_id>?")
            + " ORDER BY request_id LIMIT ?";
        try (var c = source.getConnection(); var s = c.prepareStatement(sql)) {
            int next = 1;
            s.setString(next++, tenant.value());
            if (namespace != null && !namespace.isBlank()) s.setString(next++, namespace);
            if (after != null) s.setObject(next++, after);
            s.setInt(next, limit + 1);
            try (var r = s.executeQuery()) {
                var out = new ArrayList<Entry>();
                while (r.next()) out.add(row(tenant, r, Instant.EPOCH));
                return List.copyOf(out);
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("External link read unavailable", failed);
        }
    }

    private static Entry row(TenantId tenant, ResultSet r, Instant fallback) throws SQLException {
        var key = new ExternalObjectKey(tenant, r.getString("source_instance_id"), r.getString("external_type"),
            r.getString("external_id"), r.getString("generation"));
        var registration = new ExternalLinkRegistration(r.getObject("request_id", UUID.class), key,
            new EntityId(r.getObject("entity_id", UUID.class)), r.getString("reason"));
        var at = r.getTimestamp("registered_at");
        return new Entry(registration, at == null ? fallback : at.toInstant());
    }
}
