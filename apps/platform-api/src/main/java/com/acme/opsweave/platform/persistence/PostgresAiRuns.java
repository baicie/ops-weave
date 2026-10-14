package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.AiRunStore;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.AiRunControl;
import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.ai.AiRunJson;
import com.acme.opsweave.sharedkernel.TenantId;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL backing for the durable run registry (ADR-104). Creation is serialized per request id
 * and inserts the run together with its immutable submission receipt; the state advance is a
 * compare-and-set on {@code status_version} and the fencing token, so a duplicated submitter creates
 * one run and a stale worker can never overwrite a newer state.
 *
 * <p>The 5b executor seam (V070) adds the lease claim — one atomic step that moves the run into
 * {@code RUNNING} for exactly one attempt with a lease deadline and a new fencing token — the
 * cooperative cancellation request beside the state machine, and the idempotent control receipts of
 * the cancel/retry commands. Reads never write.
 *
 * <p>The 5c event log (V071) is recorded inside those same transactions: the submission, every
 * claim, every applied move and the first recorded cancellation request each append one {@link
 * AiRunEvent} with the next per-run sequence number, so a rejected compare-and-set records nothing.
 * The retained window is a server constant and dropping old rows is what expires a cursor into an
 * explicit gap.
 */
final class PostgresAiRuns implements AiRunStore {
    private final DataSource source;
    private final int retained;

    PostgresAiRuns(DataSource source) {
        this(source, AiRunEvent.RETAINED_EVENTS);
    }

    /** The retained window is a server constant; tests make it small to exercise the gap path. */
    PostgresAiRuns(DataSource source, int retained) {
        if (retained < 1) throw new IllegalArgumentException("retained events out of bounds");
        this.source = source;
        this.retained = retained;
    }

    @Override
    public AiRun.Run create(AiRun.Run run) {
        AiRun.Run[] saved = new AiRun.Run[1];
        Transactions.run(source, c -> {
            try (var statement = c.createStatement()) {
                statement.execute("SET LOCAL statement_timeout='3s'");
                statement.execute("SET LOCAL lock_timeout='2s'");
            }
            try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "ai-run:" + run.tenantId().value() + ":" + run.requestId());
                lock.execute();
            }
            var prior = byRequest(c, run.tenantId(), run.requestId());
            if (prior.isPresent()) { saved[0] = prior.get(); return; }
            if (controlCommand(c, run.tenantId(), run.requestId()) != null) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
            try (var insert = c.prepareStatement("INSERT INTO ai_control.ai_run"
                + " (tenant_id,run_id,subject_id,incident_id,status,status_version,body,created_at_text,updated_at_text,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?)")) {
                insert.setString(1, run.tenantId().value());
                insert.setObject(2, run.runId());
                insert.setString(3, run.subjectId().value());
                insert.setObject(4, run.incidentId());
                insert.setString(5, run.status().name());
                insert.setLong(6, run.statusVersion());
                insert.setString(7, AiRunJson.storage(run));
                insert.setString(8, run.createdAt().toString());
                insert.setString(9, run.updatedAt().toString());
                insert.setTimestamp(10, Timestamp.from(run.createdAt()));
                insert.setTimestamp(11, Timestamp.from(run.updatedAt()));
                insert.executeUpdate();
            }
            try (var insert = c.prepareStatement("INSERT INTO ai_control.ai_run_command"
                + " (tenant_id,request_id,run_id,subject_id,command_digest,body,created_at_text,created_at)"
                + " VALUES (?,?,?,?,?,?::jsonb,?,?)")) {
                insert.setString(1, run.tenantId().value());
                insert.setObject(2, run.requestId());
                insert.setObject(3, run.runId());
                insert.setString(4, run.subjectId().value());
                insert.setString(5, run.commandDigest());
                insert.setString(6, AiRunJson.storage(run));
                insert.setString(7, run.createdAt().toString());
                insert.setTimestamp(8, Timestamp.from(run.createdAt()));
                insert.executeUpdate();
            }
            insertEvent(c, run.tenantId(), run.runId(), seq -> AiRunEvent.submitted(run, seq));
            saved[0] = run;
        });
        return saved[0];
    }

    @Override
    public Optional<AiRun.Run> find(TenantId tenant, UUID runId) {
        try (var c = source.getConnection()) { return find(c, tenant, runId); }
        catch (SQLException | IllegalArgumentException failed) { throw unavailable(); }
    }

    @Override
    public Optional<AiRun.Run> byRequest(TenantId tenant, UUID requestId) {
        try (var c = source.getConnection()) { return byRequest(c, tenant, requestId); }
        catch (SQLException | IllegalArgumentException failed) { throw unavailable(); }
    }

    @Override
    public List<AiRun.Run> page(TenantId tenant, SubjectId owner, String after, int limit) {
        if (limit < 1) throw new IllegalArgumentException("page limit out of bounds");
        try (var c = source.getConnection()) {
            Timestamp cursorAt = null;
            String cursorId = null;
            if (after != null) {
                try (var read = c.prepareStatement("SELECT run_id,created_at FROM ai_control.ai_run"
                    + " WHERE tenant_id=? AND subject_id=? AND run_id::text=?")) {
                    read.setString(1, tenant.value());
                    read.setString(2, owner.value());
                    read.setString(3, after);
                    try (var rows = read.executeQuery()) {
                        if (!rows.next()) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
                        cursorId = rows.getString("run_id");
                        cursorAt = rows.getTimestamp("created_at");
                    }
                }
            }
            String sql = "SELECT body,subject_id FROM ai_control.ai_run WHERE tenant_id=? AND subject_id=?";
            if (after != null) sql += " AND (created_at<? OR (created_at=? AND run_id::text<?))";
            sql += " ORDER BY created_at DESC, run_id::text DESC LIMIT ?";
            try (var read = c.prepareStatement(sql)) {
                read.setString(1, tenant.value());
                read.setString(2, owner.value());
                if (after != null) {
                    read.setTimestamp(3, cursorAt);
                    read.setTimestamp(4, cursorAt);
                    read.setString(5, cursorId);
                    read.setInt(6, limit);
                } else {
                    read.setInt(3, limit);
                }
                var page = new ArrayList<AiRun.Run>();
                try (var rows = read.executeQuery()) {
                    while (rows.next()) {
                        page.add(AiRunJson.decode(rows.getString("body"), tenant.value(), rows.getString("subject_id")));
                    }
                }
                return List.copyOf(page);
            }
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public Optional<Claim> claim(TenantId tenant, UUID runId, String worker, Instant now, Duration lease, Duration deadline) {
        if (worker == null || worker.isBlank() || worker.length() > 128) throw new IllegalArgumentException("worker out of bounds");
        Claim[] saved = new Claim[1];
        Transactions.run(source, c -> {
            AiRun.Run current;
            long currentFence;
            try (var read = c.prepareStatement("SELECT body,subject_id,fence,lease_until FROM ai_control.ai_run"
                + " WHERE tenant_id=? AND run_id=? FOR UPDATE")) {
                read.setString(1, tenant.value());
                read.setObject(2, runId);
                try (var rows = read.executeQuery()) {
                    if (!rows.next()) return;
                    var leaseUntil = rows.getTimestamp("lease_until");
                    if (leaseUntil != null && leaseUntil.toInstant().isAfter(now)) return;
                    currentFence = rows.getLong("fence");
                    current = AiRunJson.decode(rows.getString("body"), tenant.value(), rows.getString("subject_id"));
                }
            }
            if (current.status().terminal()) return;
            var claimed = current.claimed(now);
            long fence = currentFence + 1;
            try (var update = c.prepareStatement("UPDATE ai_control.ai_run SET status=?,status_version=?,attempt_no=?,fence=?,"
                + " lease_owner=?,lease_until=?,attempt_deadline_at=?,updated_at_text=?,updated_at=?,body=?::jsonb"
                + " WHERE tenant_id=? AND run_id=? AND status_version=? AND fence=? AND (lease_until IS NULL OR lease_until<=?)")) {
                update.setString(1, claimed.status().name());
                update.setLong(2, claimed.statusVersion());
                update.setInt(3, claimed.attempt());
                update.setLong(4, fence);
                update.setString(5, worker);
                update.setTimestamp(6, Timestamp.from(now.plus(lease)));
                update.setTimestamp(7, Timestamp.from(now.plus(deadline)));
                update.setString(8, claimed.updatedAt().toString());
                update.setTimestamp(9, Timestamp.from(claimed.updatedAt()));
                update.setString(10, AiRunJson.storage(claimed));
                update.setString(11, tenant.value());
                update.setObject(12, runId);
                update.setLong(13, current.statusVersion());
                update.setLong(14, currentFence);
                update.setTimestamp(15, Timestamp.from(now));
                if (update.executeUpdate() != 1) return;
            }
            insertEvent(c, tenant, runId, seq -> AiRunEvent.claimed(claimed, seq));
            saved[0] = new Claim(claimed, fence);
        });
        return Optional.ofNullable(saved[0]);
    }

    @Override
    public Optional<AiRun.Run> advance(TenantId tenant, UUID runId, long expectedVersion, long fence, AiRun.Transition next) {
        AiRun.Run[] saved = new AiRun.Run[1];
        Transactions.run(source, c -> {
            AiRun.Run current;
            String leaseOwner = null;
            Timestamp leaseUntil = null;
            try (var read = c.prepareStatement("SELECT body,subject_id,fence,lease_owner,lease_until FROM ai_control.ai_run"
                + " WHERE tenant_id=? AND run_id=? FOR UPDATE")) {
                read.setString(1, tenant.value());
                read.setObject(2, runId);
                try (var rows = read.executeQuery()) {
                    if (!rows.next()) return;
                    if (rows.getLong("fence") != fence) return;
                    leaseOwner = rows.getString("lease_owner");
                    leaseUntil = rows.getTimestamp("lease_until");
                    current = AiRunJson.decode(rows.getString("body"), tenant.value(), rows.getString("subject_id"));
                }
            }
            if (current.statusVersion() != expectedVersion) return;
            var updated = current.transitionTo(next);
            var terminal = updated.status().terminal();
            try (var update = c.prepareStatement("UPDATE ai_control.ai_run SET status=?,status_version=?,reason=?,"
                + " lease_owner=?,lease_until=?,updated_at_text=?,updated_at=?,body=?::jsonb"
                + " WHERE tenant_id=? AND run_id=? AND status_version=? AND fence=?")) {
                update.setString(1, updated.status().name());
                update.setLong(2, updated.statusVersion());
                update.setString(3, updated.reason() == null ? null : updated.reason().name());
                update.setString(4, terminal ? null : leaseOwner);
                update.setTimestamp(5, terminal ? null : leaseUntil);
                update.setString(6, updated.updatedAt().toString());
                update.setTimestamp(7, Timestamp.from(updated.updatedAt()));
                update.setString(8, AiRunJson.storage(updated));
                update.setString(9, tenant.value());
                update.setObject(10, runId);
                update.setLong(11, expectedVersion);
                update.setLong(12, fence);
                if (update.executeUpdate() != 1) return;
            }
            insertEvent(c, tenant, runId, seq -> AiRunEvent.transitioned(updated, seq));
            saved[0] = updated;
        });
        return Optional.ofNullable(saved[0]);
    }

    @Override
    public Optional<AiRun.Run> requestCancel(TenantId tenant, UUID runId, Instant at) {
        AiRun.Run[] saved = new AiRun.Run[1];
        Transactions.run(source, c -> {
            AiRun.Run current;
            try (var read = c.prepareStatement("SELECT body,subject_id FROM ai_control.ai_run"
                + " WHERE tenant_id=? AND run_id=? FOR UPDATE")) {
                read.setString(1, tenant.value());
                read.setObject(2, runId);
                try (var rows = read.executeQuery()) {
                    if (!rows.next()) return;
                    current = AiRunJson.decode(rows.getString("body"), tenant.value(), rows.getString("subject_id"));
                }
            }
            if (current.status().terminal()) return;
            var updated = current.cancelRequested(at);
            boolean fresh = current.cancelRequestedAt() == null;
            try (var update = c.prepareStatement("UPDATE ai_control.ai_run SET cancel_requested_at=?,cancel_requested_at_text=?,body=?::jsonb"
                + " WHERE tenant_id=? AND run_id=? AND status_version=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')")) {
                update.setTimestamp(1, Timestamp.from(at));
                update.setString(2, at.toString());
                update.setString(3, AiRunJson.storage(updated));
                update.setString(4, tenant.value());
                update.setObject(5, runId);
                update.setLong(6, current.statusVersion());
                if (update.executeUpdate() != 1) return;
            }
            // The request is recorded at most once: only the first one gets an event record.
            if (fresh) insertEvent(c, tenant, runId, seq -> AiRunEvent.cancelRequested(runId, seq, at));
            saved[0] = updated;
        });
        return Optional.ofNullable(saved[0]);
    }

    @Override
    public List<AiRun.Run> claimable(TenantId tenant, SubjectId owner, Instant now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("claimable limit out of bounds");
        try (var c = source.getConnection();
             var read = c.prepareStatement("SELECT body,subject_id FROM ai_control.ai_run WHERE tenant_id=? AND subject_id=?"
                 + " AND (status='PENDING' OR (status IN ('RUNNING','WAITING_TOOL') AND lease_until IS NOT NULL AND lease_until<=?))"
                 + " ORDER BY created_at, run_id::text LIMIT ?")) {
            read.setString(1, tenant.value());
            read.setString(2, owner.value());
            read.setTimestamp(3, Timestamp.from(now));
            read.setInt(4, limit);
            var candidates = new ArrayList<AiRun.Run>();
            try (var rows = read.executeQuery()) {
                while (rows.next()) {
                    candidates.add(AiRunJson.decode(rows.getString("body"), tenant.value(), rows.getString("subject_id")));
                }
            }
            return List.copyOf(candidates);
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public Optional<AiRunControl.CancelReceipt> findCancel(TenantId tenant, UUID requestId) {
        return findControl(tenant, requestId, "CANCEL").map(AiRunJson::cancelReceipt);
    }

    @Override
    public Optional<AiRunControl.RetryReceipt> findRetry(TenantId tenant, UUID requestId) {
        return findControl(tenant, requestId, "RETRY").map(AiRunJson::retryReceipt);
    }

    @Override
    public AiRunControl.CancelReceipt saveCancel(TenantId tenant, AiRunControl.CancelReceipt receipt) {
        var json = saveControl(tenant, AiRunControl.Kind.CANCEL, receipt.requestId(), receipt.runId(),
            AiRunJson.storage(receipt), receipt.requestedAt());
        return AiRunJson.cancelReceipt(json);
    }

    @Override
    public AiRunControl.RetryReceipt saveRetry(TenantId tenant, AiRunControl.RetryReceipt receipt) {
        var json = saveControl(tenant, AiRunControl.Kind.RETRY, receipt.requestId(), receipt.runId(),
            AiRunJson.storage(receipt), receipt.requestedAt());
        return AiRunJson.retryReceipt(json);
    }

    /**
     * Append one explicit event record (the reconciliation disclosure) with the next sequence number
     * of the run. The run row is locked first, so the sequence stays monotonic beside the transition
     * records and a missing run fails closed instead of inventing a log.
     */
    @Override
    public AiRunEvent.Event append(TenantId tenant, UUID runId, AiRunEvent.Type type, Instant occurredAt, AiRunEvent.Payload payload) {
        AiRunEvent.Event[] saved = new AiRunEvent.Event[1];
        Transactions.run(source, c -> {
            try (var statement = c.createStatement()) {
                statement.execute("SET LOCAL statement_timeout='3s'");
                statement.execute("SET LOCAL lock_timeout='2s'");
            }
            try (var lock = c.prepareStatement("SELECT status_version FROM ai_control.ai_run WHERE tenant_id=? AND run_id=? FOR UPDATE")) {
                lock.setString(1, tenant.value());
                lock.setObject(2, runId);
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
                }
            }
            saved[0] = insertEvent(c, tenant, runId,
                seq -> new AiRunEvent.Event(runId, seq, type, occurredAt, AiRunEvent.PAYLOAD_VERSION, payload));
        });
        return saved[0];
    }

    @Override
    public List<AiRunEvent.Event> read(TenantId tenant, UUID runId, long afterSeq, int limit) {
        if (afterSeq < 0 || limit < 1) throw new IllegalArgumentException("event cursor out of bounds");
        try (var c = source.getConnection();
             var read = c.prepareStatement("SELECT body,run_id,seq,type,occurred_at_text,payload_version"
                 + " FROM ai_control.ai_run_event WHERE tenant_id=? AND run_id=? AND seq>? ORDER BY seq LIMIT ?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            read.setLong(3, afterSeq);
            read.setInt(4, limit);
            var events = new ArrayList<AiRunEvent.Event>();
            try (var rows = read.executeQuery()) {
                while (rows.next()) events.add(eventRow(rows, runId));
            }
            return List.copyOf(events);
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public AiRunEvent.Bounds bounds(TenantId tenant, UUID runId) {
        try (var c = source.getConnection();
             var read = c.prepareStatement("SELECT coalesce(min(seq),0) AS lowest, coalesce(max(seq),0) AS highest"
                 + " FROM ai_control.ai_run_event WHERE tenant_id=? AND run_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                rows.next();
                return new AiRunEvent.Bounds(rows.getLong("lowest"), rows.getLong("highest"));
            }
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    /**
     * Insert the next event of one run and drop rows below the retained window. The caller holds the
     * run row lock, so the sequence assignment is serialized with every transition record.
     */
    private AiRunEvent.Event insertEvent(Connection c, TenantId tenant, UUID runId,
            java.util.function.LongFunction<AiRunEvent.Event> factory) throws SQLException {
        long next;
        try (var read = c.prepareStatement("SELECT coalesce(max(seq),0)+1 AS next_seq FROM ai_control.ai_run_event"
            + " WHERE tenant_id=? AND run_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                rows.next();
                next = rows.getLong("next_seq");
            }
        }
        var event = factory.apply(next);
        try (var insert = c.prepareStatement("INSERT INTO ai_control.ai_run_event"
            + " (tenant_id,run_id,seq,type,payload_version,body,occurred_at_text,occurred_at)"
            + " VALUES (?,?,?,?,?,?::jsonb,?,?)")) {
            insert.setString(1, tenant.value());
            insert.setObject(2, runId);
            insert.setLong(3, event.seq());
            insert.setString(4, event.type().name());
            insert.setInt(5, event.payloadVersion());
            insert.setString(6, AiRunJson.storage(event));
            insert.setString(7, event.occurredAt().toString());
            insert.setTimestamp(8, Timestamp.from(event.occurredAt()));
            insert.executeUpdate();
        }
        if (event.seq() > retained) {
            try (var trim = c.prepareStatement("DELETE FROM ai_control.ai_run_event"
                + " WHERE tenant_id=? AND run_id=? AND seq<=?")) {
                trim.setString(1, tenant.value());
                trim.setObject(2, runId);
                trim.setLong(3, event.seq() - retained);
                trim.executeUpdate();
            }
        }
        return event;
    }

    /** Stored events are read back field by field; a body that disagrees with its columns is 503. */
    private static AiRunEvent.Event eventRow(java.sql.ResultSet rows, UUID runId) throws SQLException {
        var result = AiRunJson.decodeEvent(rows.getString("body"), runId);
        if (result.seq() != rows.getLong("seq") || !result.type().name().equals(rows.getString("type"))
            || result.payloadVersion() != rows.getInt("payload_version")
            || !result.occurredAt().toString().equals(rows.getString("occurred_at_text"))) {
            throw unavailable();
        }
        return result;
    }

    /** First writer wins: a stored receipt with the same request id must name the same command. */
    private String saveControl(TenantId tenant, AiRunControl.Kind kind, UUID requestId, UUID runId, String receipt, Instant at) {
        String[] stored = new String[1];
        Transactions.run(source, c -> {
            try (var statement = c.createStatement()) {
                statement.execute("SET LOCAL statement_timeout='3s'");
                statement.execute("SET LOCAL lock_timeout='2s'");
            }
            try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "ai-run-control:" + tenant.value() + ":" + requestId);
                lock.execute();
            }
            if (byRequest(c, tenant, requestId).isPresent()) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
            var existing = controlCommand(c, tenant, requestId);
            if (existing != null) {
                if (!kind.name().equals(existing.kind()) || !runId.equals(existing.runId())) {
                    throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
                }
                stored[0] = existing.receipt();
                return;
            }
            try (var insert = c.prepareStatement("INSERT INTO ai_control.ai_run_control_command"
                + " (tenant_id,request_id,kind,run_id,subject_id,command_digest,receipt,created_at_text,created_at)"
                + " SELECT ?,?,?,?,subject_id,?,?::jsonb,?,? FROM ai_control.ai_run WHERE tenant_id=? AND run_id=?")) {
                insert.setString(1, tenant.value());
                insert.setObject(2, requestId);
                insert.setString(3, kind.name());
                insert.setObject(4, runId);
                insert.setString(5, new AiRunControl.Command(kind, requestId, runId).commandDigest());
                insert.setString(6, receipt);
                insert.setString(7, at.toString());
                insert.setTimestamp(8, Timestamp.from(at));
                insert.setString(9, tenant.value());
                insert.setObject(10, runId);
                if (insert.executeUpdate() != 1) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
            }
            stored[0] = receipt;
        });
        return stored[0];
    }

    private Optional<String> findControl(TenantId tenant, UUID requestId, String kind) {
        try (var c = source.getConnection()) {
            var row = controlCommand(c, tenant, requestId);
            if (row == null) return Optional.empty();
            if (!kind.equals(row.kind())) throw new AiRun.Conflict("AI_RUN_REQUEST_REUSED");
            return Optional.of(row.receipt());
        } catch (SQLException failed) { throw unavailable(); }
    }

    private record ControlCommand(String kind, UUID runId, String receipt) {}

    private static ControlCommand controlCommand(Connection c, TenantId tenant, UUID requestId) throws SQLException {
        try (var read = c.prepareStatement("SELECT kind,run_id,receipt FROM ai_control.ai_run_control_command"
            + " WHERE tenant_id=? AND request_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, requestId);
            try (var rows = read.executeQuery()) {
                return rows.next()
                    ? new ControlCommand(rows.getString("kind"), rows.getObject("run_id", UUID.class), rows.getString("receipt"))
                    : null;
            }
        }
    }

    private static Optional<AiRun.Run> find(Connection c, TenantId tenant, UUID runId) throws SQLException {
        try (var read = c.prepareStatement("SELECT * FROM ai_control.ai_run WHERE tenant_id=? AND run_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, runId);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var result = AiRunJson.decode(rows.getString("body"), tenant.value(), rows.getString("subject_id"));
                var cancelText = rows.getString("cancel_requested_at_text");
                if (!result.runId().equals(runId) || !result.tenantId().equals(tenant)
                    || !result.subjectId().value().equals(rows.getString("subject_id"))
                    || !result.incidentId().equals(rows.getObject("incident_id", UUID.class))
                    || !result.status().name().equals(rows.getString("status"))
                    || result.statusVersion() != rows.getLong("status_version")
                    || result.attempt() != rows.getInt("attempt_no")
                    || !result.createdAt().toString().equals(rows.getString("created_at_text"))
                    || !result.updatedAt().toString().equals(rows.getString("updated_at_text"))
                    || (result.reason() == null ? rows.getString("reason") != null
                        : !result.reason().name().equals(rows.getString("reason")))
                    || (result.cancelRequestedAt() == null ? cancelText != null
                        : !result.cancelRequestedAt().toString().equals(cancelText))) {
                    throw unavailable();
                }
                return Optional.of(result);
            }
        }
    }

    private static Optional<AiRun.Run> byRequest(Connection c, TenantId tenant, UUID requestId) throws SQLException {
        try (var read = c.prepareStatement("SELECT run_id FROM ai_control.ai_run_command WHERE tenant_id=? AND request_id=?")) {
            read.setString(1, tenant.value());
            read.setObject(2, requestId);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return find(c, tenant, rows.getObject("run_id", UUID.class));
            }
        }
    }

    private static ToolFailure unavailable() {
        return new ToolFailure(ToolFailure.Code.UNAVAILABLE);
    }
}
