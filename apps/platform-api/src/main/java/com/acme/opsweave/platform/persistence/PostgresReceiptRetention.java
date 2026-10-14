package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.integration.api.ReceiptRetentionStore;
import com.acme.opsweave.integration.domain.ReceiptRetention;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.platform.workflow.ReceiptRetentionJson;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL view over the stored receipts this slice governs. Protection is resolved in SQL as well
 * as in the service, so a protected row survives even if a caller hands over a stale candidate list.
 *
 * <p>Protection is per kind and never guessed: an onboarding receipt backs a live source instance, a
 * command receipt is the current authority of its source instance and a registration is the current
 * authority of its external key. Only superseded receipts are ever candidates.
 */
final class PostgresReceiptRetention implements ReceiptRetentionStore {
    /** ISO timestamps sort chronologically only when the shape is checked before casting. */
    private static final String INSTANT = "^[0-9]{4}-[0-9]{2}-[0-9]{2}T";

    private final DataSource source;

    PostgresReceiptRetention(DataSource source) { this.source = source; }

    private static String commandTime(String alias) {
        String raw = alias + ".body->'instance'->>'createdAt'";
        return "CASE WHEN " + raw + " ~ '" + INSTANT + "' THEN (" + raw + ")::timestamptz END";
    }

    /** The newest command of one source instance is its current authority; older ones are candidates. */
    private static String newestCommand(String alias) {
        return "(SELECT " + alias + "2.request_id FROM integration.source_command " + alias + "2"
            + " WHERE " + alias + "2.tenant_id=" + alias + ".tenant_id AND " + alias + "2.owner_subject=" + alias + ".owner_subject"
            + " AND " + alias + "2.source_id=" + alias + ".source_id"
            + " ORDER BY " + commandTime(alias + "2") + " DESC, " + alias + "2.request_id DESC LIMIT 1)";
    }

    private static String newestRegistration(String alias) {
        return "(SELECT " + alias + "2.request_id FROM inventory.external_link_registration " + alias + "2"
            + " WHERE " + alias + "2.tenant_id=" + alias + ".tenant_id AND " + alias + "2.source_instance_id=" + alias + ".source_instance_id"
            + " AND " + alias + "2.external_type=" + alias + ".external_type AND " + alias + "2.external_id=" + alias + ".external_id"
            + " AND " + alias + "2.generation=" + alias + ".generation"
            + " ORDER BY " + alias + "2.registered_at DESC, " + alias + "2.request_id DESC LIMIT 1)";
    }

    private static String instanceBacked(String alias) {
        return "EXISTS(SELECT 1 FROM integration.source_instance i WHERE i.tenant_id=" + alias + ".tenant_id"
            + " AND i.owner_subject=" + alias + ".owner_subject AND i.source_id=" + alias + ".setup_id)";
    }

    @Override
    public List<ReceiptRetention.Row> rows(TenantId tenant, ReceiptRetention.Kind kind, String scopeKey, int limit) {
        if (limit < 1 || limit > ReceiptRetention.MAX_SCAN_ROWS) throw new IllegalArgumentException("Invalid receipt scan limit");
        String sql = switch (kind) {
            case ONBOARDING -> "SELECT s.setup_id::text AS id, s.created_at AS at, " + instanceBacked("s") + " AS protected"
                + " FROM integration.source_setup s WHERE s.tenant_id=? AND s.owner_subject=?"
                + " ORDER BY s.created_at, s.setup_id LIMIT ?";
            case SOURCE_COMMAND -> "SELECT c.request_id::text AS id, " + commandTime("c") + " AS at,"
                + " (c.request_id = " + newestCommand("c") + ") AS protected"
                + " FROM integration.source_command c WHERE c.tenant_id=? AND c.source_id::text=?"
                + " ORDER BY at, c.request_id LIMIT ?";
            case EXTERNAL_LINK -> "SELECT r.request_id::text AS id, r.registered_at AS at,"
                + " (r.request_id = " + newestRegistration("r") + ") AS protected"
                + " FROM inventory.external_link_registration r WHERE r.tenant_id=? AND r.source_instance_id=?"
                + " ORDER BY r.registered_at, r.request_id LIMIT ?";
        };
        try (var c = source.getConnection(); var s = c.prepareStatement(sql)) {
            s.setString(1, tenant.value());
            s.setString(2, scopeKey);
            s.setInt(3, limit);
            try (var r = s.executeQuery()) {
                var rows = new ArrayList<ReceiptRetention.Row>();
                while (r.next()) {
                    var at = r.getTimestamp("at");
                    rows.add(new ReceiptRetention.Row(r.getString("id"), at == null ? Instant.EPOCH : at.toInstant(), r.getBoolean("protected")));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException failed) {
            throw unavailable();
        }
    }

    @Override
    public List<String> scopes(TenantId tenant, ReceiptRetention.Kind kind) {
        String sql = switch (kind) {
            case ONBOARDING -> "SELECT DISTINCT owner_subject FROM integration.source_setup WHERE tenant_id=? ORDER BY owner_subject LIMIT 50";
            case SOURCE_COMMAND -> "SELECT DISTINCT source_id::text FROM integration.source_command WHERE tenant_id=? ORDER BY 1 LIMIT 50";
            case EXTERNAL_LINK -> "SELECT DISTINCT source_instance_id FROM inventory.external_link_registration WHERE tenant_id=? ORDER BY 1 LIMIT 50";
        };
        try (var c = source.getConnection(); var s = c.prepareStatement(sql)) {
            s.setString(1, tenant.value());
            try (var r = s.executeQuery()) {
                var scopes = new ArrayList<String>();
                while (r.next()) scopes.add(r.getString(1));
                return List.copyOf(scopes);
            }
        } catch (SQLException failed) {
            throw unavailable();
        }
    }

    @Override
    public int remove(TenantId tenant, ReceiptRetention.Kind kind, String scopeKey, List<String> ids) {
        if (ids.isEmpty()) return 0;
        if (ids.size() > 100) throw new IllegalArgumentException("Invalid receipt cleanup batch");
        String sql = switch (kind) {
            case ONBOARDING -> "DELETE FROM integration.source_setup s WHERE s.tenant_id=? AND s.owner_subject=?"
                + " AND s.setup_id::text IN (%s) AND NOT " + instanceBacked("s");
            case SOURCE_COMMAND -> "DELETE FROM integration.source_command s WHERE s.tenant_id=? AND s.source_id::text=?"
                + " AND s.request_id::text IN (%s) AND s.request_id <> " + newestCommand("s");
            case EXTERNAL_LINK -> "DELETE FROM inventory.external_link_registration s WHERE s.tenant_id=? AND s.source_instance_id=?"
                + " AND s.request_id::text IN (%s) AND s.request_id <> " + newestRegistration("s");
        };
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        try (var c = source.getConnection(); var s = c.prepareStatement(String.format(sql, placeholders))) {
            s.setString(1, tenant.value());
            s.setString(2, scopeKey);
            for (int i = 0; i < ids.size(); i++) s.setString(3 + i, ids.get(i));
            return s.executeUpdate();
        } catch (SQLException failed) {
            throw unavailable();
        }
    }

    @Override
    public Optional<ReceiptRetention.Receipt> command(TenantId tenant, UUID requestId) {
        try (var c = source.getConnection();
             var s = c.prepareStatement("SELECT body FROM integration.receipt_retention_command WHERE tenant_id=? AND request_id=?")) {
            s.setString(1, tenant.value());
            s.setObject(2, requestId);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(ReceiptRetentionJson.receipt(r.getString("body"))) : Optional.empty();
            }
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public void addCommand(TenantId tenant, ReceiptRetention.Receipt receipt) {
        try (var c = source.getConnection();
             var s = c.prepareStatement("INSERT INTO integration.receipt_retention_command"
                 + " (tenant_id,request_id,kind,scope_key,body,created_at) VALUES (?,?,?,?,?::jsonb,?)")) {
            s.setString(1, tenant.value());
            s.setObject(2, receipt.requestId());
            s.setString(3, receipt.kind().label());
            s.setString(4, receipt.scopeKey());
            s.setString(5, ReceiptRetentionJson.storage(receipt));
            s.setTimestamp(6, Timestamp.from(receipt.createdAt()));
            s.executeUpdate();
        } catch (SQLException failed) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Receipt retention store unavailable");
    }
}
