package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import tools.jackson.databind.json.JsonMapper;

final class PostgresSyncStore implements SyncRunStore, IngestZabbixHostsUseCase.RawRecordCollector {
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();

    PostgresSyncStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public SyncRun start(TenantId tenantId, String sourceInstanceId, String objectType, String dataMode) {
        UUID id = UUID.randomUUID();
        Instant startedAt = Instant.now();
        Transactions.run(dataSource, connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO integration.source_sync_run (
                    tenant_id, id, source_instance_id, object_type, status, started_at,
                    pages, fetched, accepted, rejected, snapshot_complete, data_mode
                ) VALUES (?, ?, ?, ?, 'RUNNING', ?, 0, 0, 0, 0, false, ?)
                """)) {
                statement.setString(1, tenantId.value());
                statement.setObject(2, id);
                statement.setString(3, sourceInstanceId);
                statement.setString(4, objectType);
                statement.setTimestamp(5, Timestamp.from(startedAt));
                statement.setString(6, dataMode);
                statement.executeUpdate();
            }
        });
        return new SyncRun(
            id, tenantId, sourceInstanceId, objectType, SyncStatus.RUNNING, startedAt,
            null, null, 0, 0, 0, 0, false, dataMode, null
        );
    }

    @Override
    public void checkpoint(
        TenantId tenantId,
        UUID id,
        String cursor,
        int pages,
        int fetched,
        int accepted,
        int rejected
    ) {
        Transactions.run(dataSource, connection -> {
            try (var statement = connection.prepareStatement("""
                UPDATE integration.source_sync_run
                   SET cursor_text = ?, pages = ?, fetched = ?, accepted = ?, rejected = ?, snapshot_complete = false
                 WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                """)) {
                statement.setString(1, cursor);
                statement.setInt(2, pages);
                statement.setInt(3, fetched);
                statement.setInt(4, accepted);
                statement.setInt(5, rejected);
                statement.setString(6, tenantId.value());
                statement.setObject(7, id);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Sync checkpoint was not stored");
                }
            }
        });
    }

    @Override
    public void succeed(TenantId tenantId, UUID id) {
        Transactions.run(dataSource, connection -> updateStatus(connection, tenantId, id, "SUCCEEDED", null, true));
    }

    @Override
    public void fail(TenantId tenantId, UUID id, String reason) {
        String text = reason == null || reason.isBlank() ? "failed" : reason;
        if (text.length() > 200) {
            text = text.substring(0, 200);
        }
        String failure = text;
        Transactions.run(dataSource, connection -> updateStatus(connection, tenantId, id, "FAILED", failure, false));
    }

    @Override
    public Optional<SyncRun> find(TenantId tenantId, UUID id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT source_instance_id, object_type, status, started_at, completed_at, cursor_text,
                       pages, fetched, accepted, rejected, snapshot_complete, data_mode, failure_reason
                  FROM integration.source_sync_run
                 WHERE tenant_id = ? AND id = ?
                """)) {
            statement.setString(1, tenantId.value());
            statement.setObject(2, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SyncRun(
                    id,
                    tenantId,
                    rows.getString("source_instance_id"),
                    rows.getString("object_type"),
                    SyncStatus.valueOf(rows.getString("status")),
                    rows.getTimestamp("started_at").toInstant(),
                    instant(rows.getTimestamp("completed_at")),
                    rows.getString("cursor_text"),
                    rows.getInt("pages"),
                    rows.getInt("fetched"),
                    rows.getInt("accepted"),
                    rows.getInt("rejected"),
                    rows.getBoolean("snapshot_complete"),
                    rows.getString("data_mode"),
                    rows.getString("failure_reason")
                ));
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Sync run lookup failed");
        }
    }

    @Override
    public String retain(TenantId tenantId, String sourceInstanceId, UUID syncRunId, Connector.RawRecord record) {
        String ref = IngestZabbixHostsUseCase.newRawRef();
        String payload = json.writeValueAsString(record.payload());
        Transactions.run(dataSource, connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO integration.raw_record_metadata (
                    tenant_id, id, source_instance_id, external_id, observed_at, payload, sync_run_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """)) {
                statement.setString(1, tenantId.value());
                statement.setString(2, ref);
                statement.setString(3, sourceInstanceId);
                statement.setString(4, record.externalId());
                statement.setTimestamp(5, Timestamp.from(record.observedAt()));
                statement.setString(6, payload);
                statement.setObject(7, syncRunId);
                statement.executeUpdate();
            }
        });
        return ref;
    }

    private static void updateStatus(
        Connection connection,
        TenantId tenantId,
        UUID id,
        String status,
        String failure,
        boolean complete
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE integration.source_sync_run
               SET status = ?, completed_at = ?, snapshot_complete = ?, failure_reason = ?
             WHERE tenant_id = ? AND id = ?
            """)) {
            statement.setString(1, status);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setBoolean(3, complete);
            statement.setString(4, failure);
            statement.setString(5, tenantId.value());
            statement.setObject(6, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Sync run was not updated");
            }
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
