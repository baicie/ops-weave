package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.RejectedWriteAttemptStore;
import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded refusal log. The insert and the prune share one transaction, so a scope never exceeds its
 * budget once a refusal has been recorded. Field names travel as a JSON array of allow-listed names;
 * no value, vendor payload or exception message is ever written here.
 */
final class PostgresRejectedWriteAttempts implements RejectedWriteAttemptStore {
    private final DataSource dataSource;
    private final RejectedWriteAttempt.Policy policy;
    private final JsonMapper json = JsonMapper.builder().build();

    PostgresRejectedWriteAttempts(DataSource dataSource, RejectedWriteAttempt.Policy policy) {
        this.dataSource = dataSource;
        this.policy = policy;
    }

    @Override
    public void record(RejectedWriteAttempt attempt) {
        Transactions.run(dataSource, connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO integration.rejected_write_attempt (
                    tenant_id, id, source_instance_id, kind, method, reason_code, actor, field_names, attempted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """)) {
                statement.setString(1, attempt.tenantId().value());
                statement.setObject(2, attempt.id());
                statement.setString(3, attempt.sourceInstanceId());
                statement.setString(4, attempt.kind().name());
                statement.setString(5, attempt.method().name());
                statement.setString(6, attempt.code().name());
                statement.setString(7, attempt.actor());
                statement.setString(8, json.writeValueAsString(attempt.fieldNames()));
                statement.setTimestamp(9, Timestamp.from(attempt.attemptedAt()));
                statement.executeUpdate();
            }
            try (var prune = connection.prepareStatement("""
                DELETE FROM integration.rejected_write_attempt
                 WHERE tenant_id = ? AND source_instance_id = ?
                   AND id NOT IN (
                       SELECT id FROM integration.rejected_write_attempt
                        WHERE tenant_id = ? AND source_instance_id = ?
                        ORDER BY attempted_at DESC, id DESC
                        LIMIT ?
                   )
                """)) {
                prune.setString(1, attempt.tenantId().value());
                prune.setString(2, attempt.sourceInstanceId());
                prune.setString(3, attempt.tenantId().value());
                prune.setString(4, attempt.sourceInstanceId());
                prune.setInt(5, policy.maxPerSource());
                prune.executeUpdate();
            }
        });
    }

    @Override
    public List<RejectedWriteAttempt> recent(TenantId tenantId, String sourceInstanceId, int limit) {
        if (limit < 1 || limit > MAX_RECENT) {
            throw new IllegalArgumentException("Invalid audit limit");
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT id, kind, method, reason_code, actor, field_names::text, attempted_at
                  FROM integration.rejected_write_attempt
                 WHERE tenant_id = ? AND source_instance_id = ?
                 ORDER BY attempted_at DESC, id DESC
                 LIMIT ?
                """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenantId.value());
            statement.setString(2, sourceInstanceId);
            statement.setInt(3, limit);
            List<RejectedWriteAttempt> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(read(tenantId, sourceInstanceId, rows));
                }
            }
            return List.copyOf(found);
        } catch (SQLException failed) {
            throw new IllegalStateException("Rejected write audit unavailable");
        }
    }

    @Override
    public int kept(TenantId tenantId, String sourceInstanceId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT count(*) FROM integration.rejected_write_attempt
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
            throw new IllegalStateException("Rejected write audit unavailable");
        }
    }

    private RejectedWriteAttempt read(TenantId tenantId, String sourceInstanceId, ResultSet rows) throws SQLException {
        JsonNode names = json.readTree(rows.getString("field_names"));
        List<String> fields = new ArrayList<>();
        names.forEach(node -> fields.add(node.asString()));
        return new RejectedWriteAttempt(
            rows.getObject("id", UUID.class),
            tenantId,
            sourceInstanceId,
            RejectedWriteAttempt.Kind.valueOf(rows.getString("kind")),
            RejectedWriteAttempt.Method.valueOf(rows.getString("method")),
            RejectedWriteAttempt.Code.valueOf(rows.getString("reason_code")),
            rows.getString("actor"),
            fields,
            rows.getTimestamp("attempted_at").toInstant()
        );
    }
}
