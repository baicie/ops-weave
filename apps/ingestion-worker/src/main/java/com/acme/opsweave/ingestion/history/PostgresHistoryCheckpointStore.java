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

/** Two short transactions: acquire a fencing lease, then CAS the checkpoint after external work. */
public final class PostgresHistoryCheckpointStore implements CheckpointStore {
    static final int LEASE_SECONDS = 300;
    private final DataSource dataSource;

    public PostgresHistoryCheckpointStore(DataSource dataSource) { this.dataSource = dataSource; }

    public void initialize() {
        apply("db/ingestion/V001__history_checkpoint.sql");
        apply("db/ingestion/V002__history_checkpoint_lease.sql");
    }

    @Override
    public Result withLock(HistoryStream stream, long initialFrom, Function<HistoryCheckpoint, Update> work) {
        HistoryCheckpoint.initial(initialFrom);
        Held held = acquire(stream, initialFrom);
        try {
            Update update = work.apply(held.checkpoint());
            persist(stream, held, update);
            return update.result();
        } catch (RuntimeException failed) {
            release(stream, held.fencingToken());
            throw failed;
        }
    }

    private Held acquire(HistoryStream stream, long initialFrom) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                limits(connection);
                try (var insert = connection.prepareStatement("""
                    INSERT INTO ingestion.history_checkpoint
                      (tenant_id, source_instance_id, item_id, stream_name, initial_from, completed_through)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                    key(insert, stream, 1);
                    insert.setString(4, stream.streamName());
                    insert.setLong(5, initialFrom);
                    insert.setLong(6, initialFrom - 1);
                    insert.executeUpdate();
                }
                try (var claim = connection.prepareStatement("""
                    UPDATE ingestion.history_checkpoint
                    SET fencing_token = fencing_token + 1,
                        lease_until = clock_timestamp() + make_interval(secs => ?)
                    WHERE tenant_id=? AND source_instance_id=? AND item_id=? AND stream_name=? AND initial_from=?
                      AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    RETURNING initial_from, completed_through, series_hash, revision, fencing_token
                    """)) {
                    claim.setInt(1, LEASE_SECONDS);
                    key(claim, stream, 2);
                    claim.setString(5, stream.streamName());
                    claim.setLong(6, initialFrom);
                    try (var rows = claim.executeQuery()) {
                        if (rows.next()) {
                            var held = new Held(new HistoryCheckpoint(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getLong(4)), rows.getLong(5));
                            connection.commit();
                            return held;
                        }
                    }
                }
                throw classify(connection, stream, initialFrom);
            } catch (RuntimeException | SQLException failed) {
                connection.rollback();
                throw failed;
            }
        } catch (Failure known) { throw known; }
        catch (SQLException failed) { throw checkpointFailure(failed); }
    }

    private void persist(HistoryStream stream, Held held, Update update) {
        HistoryCheckpoint before = held.checkpoint();
        HistoryCheckpoint after = update.checkpoint();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                limits(connection);
                if (!after.equals(before)) {
                    if (!after.equals(before.accepted(after.completedThrough(), after.seriesHash()))) throw new Failure(Code.CHECKPOINT_FAILED);
                    try (var save = connection.prepareStatement("""
                        UPDATE ingestion.history_checkpoint
                        SET completed_through=?, series_hash=?, revision=?, lease_until=NULL, updated_at=clock_timestamp()
                        WHERE tenant_id=? AND source_instance_id=? AND item_id=? AND stream_name=?
                          AND revision=? AND fencing_token=?
                        """)) {
                        save.setLong(1, after.completedThrough());
                        save.setString(2, after.seriesHash());
                        save.setLong(3, after.revision());
                        key(save, stream, 4);
                        save.setString(7, stream.streamName());
                        save.setLong(8, before.revision());
                        save.setLong(9, held.fencingToken());
                        if (save.executeUpdate() != 1) throw new Failure(Code.CHECKPOINT_FAILED);
                    }
                } else if (!releaseRow(connection, stream, held.fencingToken())) {
                    throw new Failure(Code.CHECKPOINT_FAILED);
                }
                connection.commit();
            } catch (RuntimeException | SQLException failed) {
                connection.rollback();
                throw failed;
            }
        } catch (Failure known) { throw known; }
        catch (SQLException failed) { throw checkpointFailure(failed); }
    }

    private void release(HistoryStream stream, long fencingToken) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                limits(connection);
                releaseRow(connection, stream, fencingToken);
                connection.commit();
            } catch (RuntimeException | SQLException failed) {
                connection.rollback();
            }
        } catch (SQLException ignored) { /* The lease expires on its own. */ }
    }

    private static boolean releaseRow(Connection connection, HistoryStream stream, long fencingToken) throws SQLException {
        try (var release = connection.prepareStatement("""
            UPDATE ingestion.history_checkpoint SET lease_until=NULL
            WHERE tenant_id=? AND source_instance_id=? AND item_id=? AND fencing_token=?
            """)) {
            key(release, stream, 1);
            release.setLong(4, fencingToken);
            return release.executeUpdate() == 1;
        }
    }

    private static Failure classify(Connection connection, HistoryStream stream, long initialFrom) throws SQLException {
        try (var select = connection.prepareStatement("""
            SELECT stream_name, initial_from, lease_until IS NOT NULL AND lease_until > clock_timestamp()
            FROM ingestion.history_checkpoint
            WHERE tenant_id=? AND source_instance_id=? AND item_id=?
            """)) {
            key(select, stream, 1);
            try (var rows = select.executeQuery()) {
                if (!rows.next()) return new Failure(Code.CHECKPOINT_FAILED);
                if (!stream.streamName().equals(rows.getString(1)) || rows.getLong(2) != initialFrom) {
                    return new Failure(Code.CONFIGURATION_INVALID);
                }
                if (rows.getBoolean(3)) return new Failure(Code.CHECKPOINT_BUSY);
                return new Failure(Code.CHECKPOINT_FAILED);
            }
        }
    }

    private void apply(String resource) {
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
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

    private static void limits(Connection connection) throws SQLException {
        try (var limits = connection.createStatement()) {
            limits.execute("SET LOCAL lock_timeout = '250ms'");
            limits.execute("SET LOCAL statement_timeout = '10s'");
            limits.execute("SET LOCAL idle_in_transaction_session_timeout = '5s'");
        }
    }

    private static void key(PreparedStatement statement, HistoryStream stream, int offset) throws SQLException {
        statement.setString(offset, stream.tenantId().value());
        statement.setString(offset + 1, stream.sourceInstanceId());
        statement.setString(offset + 2, stream.itemId());
    }

    private static Failure checkpointFailure(SQLException failed) {
        return new Failure("55P03".equals(failed.getSQLState()) ? Code.CHECKPOINT_BUSY : Code.CHECKPOINT_FAILED);
    }

    private record Held(HistoryCheckpoint checkpoint, long fencingToken) {}
}
