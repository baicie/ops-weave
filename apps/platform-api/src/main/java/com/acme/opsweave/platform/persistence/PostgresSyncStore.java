package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.api.RawRecordReader;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.domain.SyncRunCursor;
import com.acme.opsweave.integration.domain.SyncScan;
import com.acme.opsweave.integration.domain.SyncStatus;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import tools.jackson.databind.json.JsonMapper;

final class PostgresSyncStore implements SyncRunStore, IngestZabbixHostsUseCase.RawRecordCollector, RawRecordReader {
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
            null, null, 0, 0, 0, 0, false, dataMode, null, SyncScan.OFFSET_ATTEMPT
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
    public void succeed(TenantId tenantId, UUID id, String scanConsistency) {
        Transactions.run(dataSource, connection -> updateStatus(connection, tenantId, id, "SUCCEEDED", null, true, scanConsistency));
    }

    @Override
    public void fail(TenantId tenantId, UUID id, String reason, String scanConsistency) {
        String text = reason == null || reason.isBlank() ? "failed" : reason;
        if (text.length() > 200) {
            text = text.substring(0, 200);
        }
        String failure = text;
        Transactions.run(dataSource, connection -> updateStatus(connection, tenantId, id, "FAILED", failure, false, scanConsistency));
    }

    @Override
    public Optional<SyncRun> find(TenantId tenantId, UUID id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                SELECT id, source_instance_id, object_type, status, started_at, completed_at, cursor_text,
                       pages, fetched, accepted, rejected, snapshot_complete, data_mode, failure_reason, scan_consistency
                  FROM integration.source_sync_run
                 WHERE tenant_id = ? AND id = ?
                """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenantId.value());
            statement.setObject(2, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(tenantId, rows)) : Optional.empty();
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Sync run lookup failed");
        }
    }

    @Override
    public List<SyncRun> recent(
        TenantId tenantId,
        String sourceInstanceId,
        String objectType,
        SyncRunCursor after,
        int limit
    ) {
        if (limit < 1 || limit > SyncRunStore.MAX_RECENT) {
            throw new IllegalArgumentException("Invalid run limit");
        }
        String boundary = after == null ? "" : " AND (started_at < ? OR (started_at = ? AND id < ?))";
        String sql = """
            SELECT id, source_instance_id, object_type, status, started_at, completed_at, cursor_text,
                   pages, fetched, accepted, rejected, snapshot_complete, data_mode, failure_reason, scan_consistency
              FROM integration.source_sync_run
             WHERE tenant_id = ? AND source_instance_id = ? AND object_type = ?
            """ + boundary + " ORDER BY started_at DESC, id DESC LIMIT ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenantId.value());
            statement.setString(2, sourceInstanceId);
            statement.setString(3, objectType);
            int index = 4;
            if (after != null) {
                Timestamp at = Timestamp.from(after.startedAt());
                statement.setTimestamp(index++, at);
                statement.setTimestamp(index++, at);
                statement.setObject(index++, after.id());
            }
            statement.setInt(index, limit + 1);
            List<SyncRun> rows = new java.util.ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(read(tenantId, result));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failed) {
            throw new IllegalStateException("Sync run history unavailable");
        }
    }

    private static SyncRun read(TenantId tenantId, ResultSet rows) throws SQLException {
        return new SyncRun(
            rows.getObject("id", UUID.class),
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
            rows.getString("failure_reason"),
            rows.getString("scan_consistency")
        );
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
        boolean complete,
        String scanConsistency
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE integration.source_sync_run
               SET status = ?, completed_at = ?, snapshot_complete = ?, failure_reason = ?, scan_consistency = ?
             WHERE tenant_id = ? AND id = ?
            """)) {
            statement.setString(1, status);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setBoolean(3, complete);
            statement.setString(4, failure);
            statement.setString(5, scanConsistency);
            statement.setString(6, tenantId.value());
            statement.setObject(7, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Sync run was not updated");
            }
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    @Override
    public Batch read(TenantId tenant, String source, UUID run, int limit) {
        if (limit < 1 || limit > MAX_RECORDS) throw new IllegalArgumentException("Invalid raw limit");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT id, external_id, observed_at, count(*) OVER () AS retained_count,
                   CASE WHEN octet_length(payload::text) <= 65536 THEN payload::text ELSE NULL END AS payload_text
              FROM integration.raw_record_metadata
             WHERE tenant_id = ? AND source_instance_id = ? AND sync_run_id = ?
             ORDER BY id LIMIT ?
            """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenant.value()); statement.setString(2, source);
            statement.setObject(3, run); statement.setInt(4, limit);
            var records = new java.util.ArrayList<Retained>();
            long retained = 0;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    retained = rows.getLong("retained_count");
                    String payload = rows.getString("payload_text");
                    Connector.RawRecord record = null;
                    if (payload != null) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> decoded = json.readValue(payload, java.util.Map.class);
                        record = new Connector.RawRecord(rows.getString("external_id"), rows.getTimestamp("observed_at").toInstant(), decoded);
                    }
                    records.add(new Retained(rows.getString("id"), record));
                }
            }
            return new Batch(records, retained);
        } catch (SQLException failed) { throw new IllegalStateException("Raw records unavailable"); }
    }
}
