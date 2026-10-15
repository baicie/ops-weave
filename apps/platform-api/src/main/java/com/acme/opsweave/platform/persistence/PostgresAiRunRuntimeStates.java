package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.AiRunRuntimeStateStore;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunCrossStore;
import com.acme.opsweave.aicontrol.domain.AiRunRuntimeState;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.platform.ai.AiRunJson;
import com.acme.opsweave.sharedkernel.TenantId;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL backing for the Runtime state attestations and the cross-store report snapshot (M5 5d,
 * V072). The rows are a bounded receipt projection of the Runtime's internal progress — the
 * StepRun/Checkpoint internals stay Runtime-owned and are never stored here — and an ingestion never
 * moves the run: it records a fact about delivery, nothing more.
 *
 * <p>Writes are serialized per request id and per run, idempotent on the request id (first write
 * wins, same content on a replay, an explicit conflict on different content or cross-family reuse)
 * and the per-run window is bounded: older receipts are dropped and the drop is counted in the
 * per-run sequence number, so the observability aggregate can report truncation honestly.
 */
final class PostgresAiRunRuntimeStates implements AiRunRuntimeStateStore {
    private final DataSource source;

    PostgresAiRunRuntimeStates(DataSource source) {
        this.source = source;
    }

    @Override
    public AiRunRuntimeState.Report save(TenantId tenant, AiRunRuntimeState.Report report) {
        AiRunRuntimeState.Report[] saved = new AiRunRuntimeState.Report[1];
        Transactions.run(source, c -> {
            try (var statement = c.createStatement()) {
                statement.execute("SET LOCAL statement_timeout='3s'");
                statement.execute("SET LOCAL lock_timeout='2s'");
            }
            try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "ai-run-runtime-state:" + tenant.value() + ":" + report.requestId());
                lock.execute();
            }
            try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "ai-run-runtime-state-run:" + tenant.value() + ":" + report.runId());
                lock.execute();
            }
            var prior = find(c, tenant, report.requestId());
            if (prior.isPresent()) {
                prior.get().require(report);
                saved[0] = prior.get();
                return;
            }
            // One idempotency namespace with submissions and control commands: reuse across families
            // is an explicit conflict, never a second record hiding behind a foreign request id.
            if (requestUsed(c, tenant, report.requestId())) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
            long runSeq;
            try (var read = c.prepareStatement("SELECT coalesce(max(run_seq),0)+1 AS next_seq"
                + " FROM ai_control.ai_run_runtime_state WHERE tenant_id=? AND run_id=?")) {
                read.setString(1, tenant.value());
                read.setObject(2, report.runId());
                try (var rows = read.executeQuery()) {
                    rows.next();
                    runSeq = rows.getLong("next_seq");
                }
            }
            try (var insert = c.prepareStatement("INSERT INTO ai_control.ai_run_runtime_state"
                + " (tenant_id,request_id,run_id,run_seq,attempt,status,body,reported_at_text,reported_at)"
                + " VALUES (?,?,?,?,?,?,?::jsonb,?,?)")) {
                insert.setString(1, tenant.value());
                insert.setObject(2, report.requestId());
                insert.setObject(3, report.runId());
                insert.setLong(4, runSeq);
                insert.setInt(5, report.attempt());
                insert.setString(6, report.status());
                insert.setString(7, AiRunJson.storage(report));
                insert.setString(8, report.reportedAt().toString());
                insert.setTimestamp(9, Timestamp.from(report.reportedAt()));
                insert.executeUpdate();
            }
            try (var trim = c.prepareStatement("DELETE FROM ai_control.ai_run_runtime_state"
                + " WHERE tenant_id=? AND run_id=? AND run_seq<=?")) {
                trim.setString(1, tenant.value());
                trim.setObject(2, report.runId());
                trim.setLong(3, runSeq - AiRunRuntimeState.RETAINED_REPORTS);
                trim.executeUpdate();
            }
            saved[0] = report;
        });
        return saved[0];
    }

    @Override
    public Optional<AiRunRuntimeState.Report> find(TenantId tenant, UUID requestId) {
        try (var c = source.getConnection()) {
            return find(c, tenant, requestId);
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public Optional<AiRunRuntimeState.Report> latest(TenantId tenant, UUID runId) {
        try (var c = source.getConnection();
             var read = c.prepareStatement("SELECT body FROM ai_control.ai_run_runtime_state"
                 + " WHERE tenant_id=? AND run_id=? ORDER BY run_seq DESC LIMIT 1")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                return rows.next()
                    ? Optional.of(AiRunJson.runtimeState(rows.getString("body")))
                    : Optional.empty();
            }
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public Counts counts(TenantId tenant, UUID runId) {
        try (var c = source.getConnection();
             var read = c.prepareStatement("SELECT count(*) AS kept, coalesce(max(run_seq),0) AS highest"
                 + " FROM ai_control.ai_run_runtime_state WHERE tenant_id=? AND run_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                rows.next();
                int kept = rows.getInt("kept");
                long dropped = Math.max(0, rows.getLong("highest") - kept);
                return new Counts(kept, dropped);
            }
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public Optional<AiRunCrossStore.Report> findReport(TenantId tenant, UUID runId) {
        try (var c = source.getConnection();
             var read = c.prepareStatement("SELECT body FROM ai_control.ai_run_cross_store_report"
                 + " WHERE tenant_id=? AND run_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                return rows.next()
                    ? Optional.of(AiRunJson.crossStoreReport(rows.getString("body")))
                    : Optional.empty();
            }
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public void saveReport(TenantId tenant, AiRunCrossStore.Report report) {
        Transactions.run(source, c -> {
            try (var statement = c.createStatement()) {
                statement.execute("SET LOCAL statement_timeout='3s'");
                statement.execute("SET LOCAL lock_timeout='2s'");
            }
            try (var insert = c.prepareStatement("INSERT INTO ai_control.ai_run_cross_store_report"
                + " (tenant_id,run_id,findings,body,compared_at_text,compared_at)"
                + " VALUES (?,?,?,?::jsonb,?,?) ON CONFLICT (tenant_id, run_id) DO UPDATE SET"
                + " findings=EXCLUDED.findings, body=EXCLUDED.body,"
                + " compared_at_text=EXCLUDED.compared_at_text, compared_at=EXCLUDED.compared_at")) {
                insert.setString(1, tenant.value());
                insert.setObject(2, report.runId());
                insert.setInt(3, report.findings().size());
                insert.setString(4, AiRunJson.storage(report));
                insert.setString(5, report.comparedAt().toString());
                insert.setTimestamp(6, Timestamp.from(report.comparedAt()));
                insert.executeUpdate();
            }
        });
    }

    private static Optional<AiRunRuntimeState.Report> find(java.sql.Connection c, TenantId tenant, UUID requestId)
            throws SQLException {
        try (var read = c.prepareStatement("SELECT body FROM ai_control.ai_run_runtime_state"
            + " WHERE tenant_id=? AND request_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, requestId);
            try (var rows = read.executeQuery()) {
                return rows.next()
                    ? Optional.of(AiRunJson.runtimeState(rows.getString("body")))
                    : Optional.empty();
            }
        }
    }

    /** A request id is the idempotency key of exactly one command family — never shared. */
    private static boolean requestUsed(java.sql.Connection c, TenantId tenant, UUID requestId) throws SQLException {
        for (var table : List.of("ai_control.ai_run_command", "ai_control.ai_run_control_command")) {
            try (var read = c.prepareStatement("SELECT 1 FROM " + table + " WHERE tenant_id=? AND request_id=?")) {
                read.setString(1, tenant.value());
                read.setObject(2, requestId);
                try (var rows = read.executeQuery()) {
                    if (rows.next()) return true;
                }
            }
        }
        return false;
    }

    private static ToolFailure unavailable() {
        return new ToolFailure(ToolFailure.Code.UNAVAILABLE);
    }
}
