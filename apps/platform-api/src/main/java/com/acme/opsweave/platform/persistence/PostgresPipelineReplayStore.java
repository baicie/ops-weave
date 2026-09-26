package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.PipelineReplayStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.json.JsonMapper;

final class PostgresPipelineReplayStore implements PipelineReplayStore {
    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();
    PostgresPipelineReplayStore(DataSource dataSource) { this.dataSource = dataSource; }

    public Claim claim(TenantId tenant, String source, SubjectId owner, UUID key, PipelineReplaySpec spec, Instant now) {
        var candidate = PipelineReplayRun.start(tenant, source, owner, key, spec, now);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int inserted;
                try (var statement = connection.prepareStatement("""
                    INSERT INTO integration.pipeline_replay_run
                        (tenant_id, source_instance_id, owner_subject, request_key, id, spec, state, attempt, created_at, updated_at, lease_until)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, 'RUNNING', 1, ?, ?, ?)
                    ON CONFLICT (tenant_id, source_instance_id, owner_subject, request_key) DO NOTHING
                    """)) {
                    statement.setQueryTimeout(5); scope(statement, tenant, source, owner);
                    statement.setObject(4, key); statement.setObject(5, candidate.id());
                    statement.setString(6, json.writeValueAsString(spec));
                    statement.setTimestamp(7, Timestamp.from(now)); statement.setTimestamp(8, Timestamp.from(now));
                    statement.setTimestamp(9, Timestamp.from(candidate.leaseUntil())); inserted = statement.executeUpdate();
                }
                PipelineReplayRun current;
                try (var statement = connection.prepareStatement("""
                    SELECT * FROM integration.pipeline_replay_run
                    WHERE tenant_id = ? AND source_instance_id = ? AND owner_subject = ? AND request_key = ? FOR UPDATE
                    """)) {
                    statement.setQueryTimeout(5); scope(statement, tenant, source, owner); statement.setObject(4, key);
                    try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException(); current = run(rows); }
                }
                if (!current.spec().equals(spec)) throw new PipelineException(PipelineException.Code.REPLAY_KEY_CONFLICT);
                boolean acquired = inserted == 1;
                if (!acquired && current.canResume(now)) {
                    current = current.resume(now); acquired = true; writeState(connection, current);
                } else if (!acquired && current.state() == PipelineReplayRun.State.RUNNING && !current.leaseUntil().isAfter(now)) {
                    current = current.finish(now, null, "REPLAY_ATTEMPTS_EXHAUSTED"); writeState(connection, current);
                }
                connection.commit(); return new Claim(current, acquired);
            } catch (SQLException | RuntimeException failed) {
                connection.rollback();
                if (failed instanceof PipelineException expected) throw expected;
                throw new IllegalStateException("Replay claim unavailable");
            }
        } catch (SQLException failed) { throw new IllegalStateException("Replay claim unavailable"); }
    }

    public boolean finish(PipelineReplayRun claimed, Instant now, PipelineEvaluation report, String failure) {
        var completed = claimed.finish(now, report, failure);
        String encoded = report == null ? null : json.writeValueAsString(report);
        if (encoded != null && encoded.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw new PipelineException(PipelineException.Code.REPLAY_RESULT_TOO_LARGE);
        }
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            UPDATE integration.pipeline_replay_run SET state = ?, updated_at = ?, lease_until = NULL, failure_code = ?, report = ?::jsonb
            WHERE tenant_id = ? AND source_instance_id = ? AND owner_subject = ? AND id = ?
              AND state = 'RUNNING' AND attempt = ? AND lease_until > ?
            """)) {
            statement.setQueryTimeout(5); statement.setString(1, completed.state().name());
            statement.setTimestamp(2, Timestamp.from(now)); statement.setString(3, failure); statement.setString(4, encoded);
            statement.setString(5, claimed.tenant().value()); statement.setString(6, claimed.source());
            statement.setString(7, claimed.owner().value()); statement.setObject(8, claimed.id());
            statement.setInt(9, claimed.attempt()); statement.setTimestamp(10, Timestamp.from(now));
            return statement.executeUpdate() == 1;
        } catch (SQLException failed) { throw new IllegalStateException("Replay result unavailable"); }
    }
    public Optional<PipelineReplayRun> find(TenantId tenant, String source, SubjectId owner, UUID id) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT * FROM integration.pipeline_replay_run
            WHERE tenant_id = ? AND source_instance_id = ? AND owner_subject = ? AND id = ?
            """)) {
            statement.setQueryTimeout(5); scope(statement, tenant, source, owner); statement.setObject(4, id);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(run(rows)) : Optional.empty(); }
        } catch (SQLException failed) { throw new IllegalStateException("Replay lookup unavailable"); }
    }
    public List<Summary> list(TenantId tenant, String source, SubjectId owner, UUID before, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException("Invalid history limit");
        try (var connection = dataSource.getConnection()) {
            Instant cursor = null;
            if (before != null) {
                try (var statement = connection.prepareStatement("""
                    SELECT created_at FROM integration.pipeline_replay_run
                    WHERE tenant_id = ? AND source_instance_id = ? AND owner_subject = ? AND id = ?
                    """)) {
                    statement.setQueryTimeout(5); scope(statement, tenant, source, owner); statement.setObject(4, before);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new PipelineException(PipelineException.Code.NOT_FOUND);
                        cursor = rows.getTimestamp(1).toInstant();
                    }
                }
            }
            try (var statement = connection.prepareStatement("""
                SELECT id, request_key, spec, state, attempt, created_at, updated_at, lease_until, failure_code
                FROM integration.pipeline_replay_run
                WHERE tenant_id = ? AND source_instance_id = ? AND owner_subject = ?
                  AND (?::timestamptz IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
                ORDER BY created_at DESC, id DESC LIMIT ?
                """)) {
                statement.setQueryTimeout(5); scope(statement, tenant, source, owner);
                statement.setTimestamp(4, cursor == null ? null : Timestamp.from(cursor));
                statement.setTimestamp(5, cursor == null ? null : Timestamp.from(cursor)); statement.setObject(6, before); statement.setInt(7, limit);
                var list = new ArrayList<Summary>();
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) list.add(new Summary(rows.getObject("id", UUID.class), rows.getObject("request_key", UUID.class),
                        json.readValue(rows.getString("spec"), PipelineReplaySpec.class), PipelineReplayRun.State.valueOf(rows.getString("state")),
                        rows.getInt("attempt"), rows.getTimestamp("created_at").toInstant(), rows.getTimestamp("updated_at").toInstant(),
                        instant(rows.getTimestamp("lease_until")), rows.getString("failure_code")));
                }
                return List.copyOf(list);
            }
        } catch (SQLException failed) { throw new IllegalStateException("Replay history unavailable"); }
    }
    private PipelineReplayRun run(ResultSet rows) throws SQLException {
        try {
            String report = rows.getString("report");
            return new PipelineReplayRun(rows.getObject("id", UUID.class), new TenantId(rows.getString("tenant_id")),
                rows.getString("source_instance_id"), new SubjectId(rows.getString("owner_subject")), rows.getObject("request_key", UUID.class),
                json.readValue(rows.getString("spec"), PipelineReplaySpec.class), PipelineReplayRun.State.valueOf(rows.getString("state")),
                rows.getInt("attempt"), rows.getTimestamp("created_at").toInstant(), rows.getTimestamp("updated_at").toInstant(),
                instant(rows.getTimestamp("lease_until")), rows.getString("failure_code"), report == null ? null : json.readValue(report, PipelineEvaluation.class));
        } catch (RuntimeException corrupt) { throw new IllegalStateException("Stored replay failed verification"); }
    }
    private static void writeState(Connection connection, PipelineReplayRun run) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE integration.pipeline_replay_run SET state = ?, attempt = ?, updated_at = ?, lease_until = ?, failure_code = ?, report = NULL
            WHERE tenant_id = ? AND id = ?
            """)) {
            statement.setQueryTimeout(5); statement.setString(1, run.state().name()); statement.setInt(2, run.attempt());
            statement.setTimestamp(3, Timestamp.from(run.updatedAt())); statement.setTimestamp(4, run.leaseUntil() == null ? null : Timestamp.from(run.leaseUntil()));
            statement.setString(5, run.failureCode()); statement.setString(6, run.tenant().value()); statement.setObject(7, run.id());
            if (statement.executeUpdate() != 1) throw new SQLException();
        }
    }
    private static void scope(PreparedStatement statement, TenantId tenant, String source, SubjectId owner) throws SQLException {
        statement.setString(1, tenant.value()); statement.setString(2, source); statement.setString(3, owner.value());
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
