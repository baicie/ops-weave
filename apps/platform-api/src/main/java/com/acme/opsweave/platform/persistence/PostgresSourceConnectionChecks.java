package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.integration.api.SourceConnectionCheckStore;
import com.acme.opsweave.integration.domain.SourceConnectionCheck;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

final class PostgresSourceConnectionChecks implements SourceConnectionCheckStore {
    private final DataSource dataSource;

    PostgresSourceConnectionChecks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void record(SourceConnectionCheck check) {
        Transactions.run(dataSource, connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO integration.source_connection_check (
                    tenant_id, source_instance_id, check_id, actor, checked_at, data_mode, reachable,
                    status_code, reported_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, check.tenantId().value());
                statement.setString(2, check.sourceInstanceId());
                statement.setObject(3, check.id());
                statement.setString(4, check.actor());
                statement.setTimestamp(5, Timestamp.from(check.checkedAt()));
                statement.setString(6, check.dataMode());
                statement.setBoolean(7, check.reachable());
                statement.setString(8, check.statusCode());
                statement.setString(9, check.reportedVersion());
                statement.executeUpdate();
            }
            try (var prune = connection.prepareStatement("""
                DELETE FROM integration.source_connection_check
                 WHERE tenant_id = ? AND source_instance_id = ?
                   AND check_id NOT IN (
                       SELECT check_id FROM integration.source_connection_check
                        WHERE tenant_id = ? AND source_instance_id = ?
                        ORDER BY checked_at DESC, check_id DESC
                        LIMIT ?
                   )
                """)) {
                prune.setString(1, check.tenantId().value());
                prune.setString(2, check.sourceInstanceId());
                prune.setString(3, check.tenantId().value());
                prune.setString(4, check.sourceInstanceId());
                prune.setInt(5, SourceConnectionCheck.MAX_KEPT);
                prune.executeUpdate();
            }
        });
    }

    @Override
    public List<SourceConnectionCheck> recent(TenantId tenantId, String sourceInstanceId, int limit) {
        if (limit < 1 || limit > MAX_RECENT) {
            throw new IllegalArgumentException("Invalid check limit");
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT check_id, actor, checked_at, data_mode, reachable, status_code, reported_version
                  FROM integration.source_connection_check
                 WHERE tenant_id = ? AND source_instance_id = ?
                 ORDER BY checked_at DESC, check_id DESC
                 LIMIT ?
                """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenantId.value());
            statement.setString(2, sourceInstanceId);
            statement.setInt(3, limit);
            List<SourceConnectionCheck> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(read(tenantId, sourceInstanceId, rows));
                }
            }
            return List.copyOf(found);
        } catch (SQLException failed) {
            throw new IllegalStateException("Connection checks unavailable");
        }
    }

    private static SourceConnectionCheck read(TenantId tenantId, String sourceInstanceId, ResultSet rows) throws SQLException {
        return new SourceConnectionCheck(
            rows.getObject("check_id", UUID.class),
            tenantId,
            sourceInstanceId,
            rows.getString("actor"),
            rows.getTimestamp("checked_at").toInstant(),
            rows.getString("data_mode"),
            rows.getBoolean("reachable"),
            rows.getString("status_code"),
            rows.getString("reported_version")
        );
    }

    @Override
    public int kept(TenantId tenantId, String sourceInstanceId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT count(*) FROM integration.source_connection_check
                 WHERE tenant_id = ? AND source_instance_id = ?
                """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenantId.value());
            statement.setString(2, sourceInstanceId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Connection checks unavailable");
        }
    }
}
