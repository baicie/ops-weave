package com.acme.opsweave.ingestion.history;

import com.acme.opsweave.integration.api.HistoryIngestionPorts.*;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.Failure.Code;
import com.acme.opsweave.integration.domain.HistoryCheckpoint;
import com.acme.opsweave.integration.domain.HistoryStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.function.Function;
import javax.sql.DataSource;

public final class PostgresHistoryCheckpointStore implements CheckpointStore {
    private final DataSource dataSource;

    public PostgresHistoryCheckpointStore(DataSource dataSource) { this.dataSource = dataSource; }

    public void initialize() {
        try (var input = getClass().getClassLoader().getResourceAsStream("db/ingestion/V001__history_checkpoint.sql")) {
            if (input == null) throw new Failure(Code.CHECKPOINT_FAILED);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    statement.execute("SELECT pg_advisory_xact_lock(1875725101)");
                    statement.execute(sql);
                    connection.commit();
                } catch (Exception failed) { connection.rollback(); throw failed; }
            }
        } catch (Exception failed) { throw new Failure(Code.CHECKPOINT_FAILED); }
    }

    @Override
    public Result withLock(HistoryStream stream, long initialFrom, Function<HistoryCheckpoint, Update> work) {
        HistoryCheckpoint.initial(initialFrom);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var limits = connection.createStatement()) {
                    limits.execute("SET LOCAL lock_timeout = '250ms'");
                    limits.execute("SET LOCAL statement_timeout = '300s'");
                    limits.execute("SET LOCAL idle_in_transaction_session_timeout = '600s'");
                }
                try (var insert = connection.prepareStatement("""
                    INSERT INTO ingestion.history_checkpoint
                      (tenant_id, source_instance_id, item_id, stream_name, initial_from, completed_through)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                    key(insert, stream, 1); insert.setLong(5, initialFrom); insert.setLong(6, initialFrom - 1);
                    insert.executeUpdate();
                }
                HistoryCheckpoint before;
                try (var select = connection.prepareStatement("""
                    SELECT initial_from, completed_through, series_hash, revision FROM ingestion.history_checkpoint
                    WHERE tenant_id=? AND source_instance_id=? AND item_id=? AND stream_name=? FOR UPDATE NOWAIT
                    """)) {
                    key(select, stream, 1);
                    try (var rows = select.executeQuery()) {
                        if (!rows.next()) throw new Failure(Code.CHECKPOINT_FAILED);
                        before = new HistoryCheckpoint(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getLong(4));
                    }
                }
                if (before.initialFrom() != initialFrom) throw new Failure(Code.CONFIGURATION_INVALID);
                Update update = work.apply(before);
                HistoryCheckpoint after = update.checkpoint();
                if (!after.equals(before)) {
                    if (!after.equals(before.accepted(after.completedThrough(), after.seriesHash()))) throw new Failure(Code.CHECKPOINT_FAILED);
                    try (var save = connection.prepareStatement("""
                        UPDATE ingestion.history_checkpoint SET completed_through=?, series_hash=?, revision=?, updated_at=now()
                        WHERE tenant_id=? AND source_instance_id=? AND item_id=? AND stream_name=? AND revision=?
                        """)) {
                        save.setLong(1, after.completedThrough()); save.setString(2, after.seriesHash()); save.setLong(3, after.revision());
                        key(save, stream, 4); save.setLong(8, before.revision());
                        if (save.executeUpdate() != 1) throw new Failure(Code.CHECKPOINT_FAILED);
                    }
                }
                connection.commit();
                return update.result();
            } catch (RuntimeException | SQLException failed) {
                try { connection.rollback(); } catch (SQLException ignored) { /* No checkpoint success may be reported. */ }
                throw failed;
            }
        } catch (Failure known) { throw known; }
        catch (SQLException failed) {
            throw new Failure("55P03".equals(failed.getSQLState()) ? Code.CHECKPOINT_BUSY : Code.CHECKPOINT_FAILED);
        }
    }

    private static void key(PreparedStatement statement, HistoryStream stream, int offset) throws SQLException {
        statement.setString(offset, stream.tenantId().value()); statement.setString(offset + 1, stream.sourceInstanceId());
        statement.setString(offset + 2, stream.itemId()); statement.setString(offset + 3, stream.streamName());
    }
}
