package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.ToolReadStore;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.ai.ToolJson;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import static com.acme.opsweave.aicontrol.domain.ToolFailure.Code.*;

final class PostgresToolReadStore implements ToolReadStore {
    private final DataSource dataSource;
    PostgresToolReadStore(DataSource dataSource) { this.dataSource = dataSource; }
    public void create(ToolReadSession s) {
        Transactions.run(dataSource, c -> {
            limits(c);
            try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "tool-session:" + s.tenantId().value().length() + ":" + s.tenantId().value() + ":" + s.subjectId().value()); lock.execute();
            }
            try (var count = c.prepareStatement("SELECT count(*) FROM ai_control.tool_read_session WHERE tenant_id=? AND subject_id=? AND deadline_at>?")) {
                count.setString(1, s.tenantId().value()); count.setString(2, s.subjectId().value()); count.setTimestamp(3, Timestamp.from(s.createdAt()));
                try (var rows = count.executeQuery()) { rows.next(); if (rows.getLong(1) >= 4) throw new ToolFailure(BUSY); }
            }
            try (var insert = c.prepareStatement("INSERT INTO ai_control.tool_read_session (tenant_id,id,subject_id,incident_id,incident_version,entity_ids,from_text,to_text,created_at_text,deadline_at,used_calls) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, s.tenantId().value()); insert.setObject(2, s.id()); insert.setString(3, s.subjectId().value()); insert.setObject(4, s.incidentId()); insert.setLong(5, s.incidentVersion());
                Array entities = c.createArrayOf("uuid", s.entityIds().stream().map(EntityId::value).toArray());
                try {
                    insert.setArray(6, entities); insert.setString(7, s.window().from().toString()); insert.setString(8, s.window().to().toString()); insert.setString(9, s.createdAt().toString());
                    insert.setTimestamp(10, Timestamp.from(s.deadlineAt())); insert.setInt(11, s.usedCalls()); insert.executeUpdate();
                } finally { entities.free(); }
            }
        });
    }
    public Call begin(TenantId tenant, SubjectId subject, UUID sessionId, String tool, Instant now) {
        if (!ToolReadSession.TOOLS.contains(tool)) throw new ToolFailure(INVALID_REQUEST);
        Call[] result = new Call[1];
        Transactions.run(dataSource, c -> {
            limits(c); ToolReadSession s;
            try (var read = c.prepareStatement("SELECT * FROM ai_control.tool_read_session WHERE tenant_id=? AND id=? AND subject_id=? FOR UPDATE")) {
                read.setString(1, tenant.value()); read.setObject(2, sessionId); read.setString(3, subject.value());
                try (var rows = read.executeQuery()) { if (!rows.next()) throw new ToolFailure(NOT_FOUND); s = session(rows).consume(now); }
            }
            try (var update = c.prepareStatement("UPDATE ai_control.tool_read_session SET used_calls=? WHERE tenant_id=? AND id=?")) {
                update.setInt(1, s.usedCalls()); update.setString(2, tenant.value()); update.setObject(3, sessionId); update.executeUpdate();
            }
            UUID id = UUID.randomUUID(); audit(c, id, tenant, subject, sessionId, null, tool, "STARTED", 0, now, null);
            result[0] = new Call(id, s, tool, now);
        }); return result[0];
    }
    public void complete(Call call, PlatformEvidence evidence, int bytes, Instant now) {
        if (!now.isBefore(call.session().deadlineAt()) || !now.isBefore(call.startedAt().plusSeconds(15))) throw new ToolFailure(DEADLINE);
        if (bytes < 1 || bytes > 32768) throw new ToolFailure(READ_LIMIT);
        if (evidence != null && (!evidence.tenantId().equals(call.session().tenantId()) || !evidence.sessionId().equals(call.session().id()))) throw new ToolFailure(UNAVAILABLE);
        Transactions.run(dataSource, c -> {
            limits(c); interrupted();
            if (evidence != null && !call.tool().equals("evidence.get@2.0.0")) try (var insert = c.prepareStatement("INSERT INTO ai_control.evidence_snapshot (tenant_id,id,session_id,incident_id,snapshot,retention_created_at,retention_expires_at) VALUES (?,?,?,?,?::jsonb,?,?)")) {
                insert.setString(1, evidence.tenantId().value()); insert.setObject(2, evidence.id()); insert.setObject(3, evidence.sessionId());
                insert.setObject(4, evidence.incidentId()); insert.setString(5, ToolJson.encode(evidence));insert.setTimestamp(6,Timestamp.from(evidence.availableAt()));insert.setTimestamp(7,Timestamp.from(evidence.expiresAt()));insert.executeUpdate();
            }
            try (var update = c.prepareStatement("UPDATE audit.tool_read_call SET outcome=?,completed_at=?,result_bytes=?,evidence_id=? WHERE id=? AND tenant_id=? AND session_id=? AND outcome='STARTED'")) {
                update.setString(1, evidence == null || evidence.warnings().isEmpty() ? "OK" : "PARTIAL"); update.setTimestamp(2, Timestamp.from(now)); update.setInt(3, bytes);
                update.setObject(4, evidence == null ? null : evidence.id()); update.setObject(5, call.id()); update.setString(6, call.session().tenantId().value()); update.setObject(7, call.session().id());
                if (update.executeUpdate() != 1) throw new ToolFailure(UNAVAILABLE);
            }
            interrupted();
        });
    }
    public void fail(Call call, ToolFailure.Code code, Instant now) {
        try (var c = dataSource.getConnection(); var update = c.prepareStatement("UPDATE audit.tool_read_call SET outcome=?,completed_at=? WHERE id=? AND tenant_id=? AND outcome='STARTED'")) {
            update.setQueryTimeout(3); update.setString(1, code.name()); update.setTimestamp(2, Timestamp.from(now)); update.setObject(3, call.id()); update.setString(4, call.session().tenantId().value()); update.executeUpdate();
        } catch (SQLException failed) { throw new ToolFailure(UNAVAILABLE); }
    }
    public Optional<PlatformEvidence> find(TenantId tenant, UUID id) {
        try (var c = dataSource.getConnection(); var read = c.prepareStatement("SELECT snapshot,session_id,incident_id FROM ai_control.evidence_snapshot WHERE tenant_id=? AND id=? AND snapshot IS NOT NULL")) {
            read.setQueryTimeout(3); read.setString(1, tenant.value()); read.setObject(2, id);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty(); var value = ToolJson.decode(rows.getString(1));
                if (!value.id().equals(id) || !value.tenantId().equals(tenant) || !value.sessionId().equals(rows.getObject(2, UUID.class)) || !value.incidentId().equals(rows.getObject(3, UUID.class))) throw new ToolFailure(UNAVAILABLE);
                return Optional.of(value);
            }
        } catch (SQLException failed) { throw new ToolFailure(UNAVAILABLE); }
    }
    public void auditAccess(TenantId tenant, SubjectId subject, UUID id, String outcome, int bytes, Instant now) {
        try (var c = dataSource.getConnection()) { audit(c, UUID.randomUUID(), tenant, subject, null, id, "evidence.get@2.0.0", outcome, bytes, now, now); }
        catch (SQLException failed) { throw new ToolFailure(UNAVAILABLE); }
    }
    public Optional<AiRetention.Marker> retired(TenantId tenant,UUID id){try(var c=dataSource.getConnection()){return PostgresAiRetentionStore.retired(c,AiRetention.Kind.EVIDENCE,tenant,id);}catch(SQLException e){throw new ToolFailure(UNAVAILABLE);}}
    public Optional<ToolReadSession> session(TenantId tenant, SubjectId subject, UUID id) {
        try (var c = dataSource.getConnection(); var read = c.prepareStatement("SELECT * FROM ai_control.tool_read_session WHERE tenant_id=? AND subject_id=? AND id=?")) {
            read.setQueryTimeout(3); read.setString(1, tenant.value()); read.setString(2, subject.value()); read.setObject(3, id);
            try (var rows = read.executeQuery()) { return rows.next() ? Optional.of(session(rows)) : Optional.empty(); }
        } catch (SQLException error) { throw new ToolFailure(UNAVAILABLE); }
    }
    public boolean rechecked(TenantId tenant, SubjectId subject, UUID id, Set<UUID> ids) {
        try (var c = dataSource.getConnection()) { return rechecked(c, tenant, subject, id, ids); }
        catch (SQLException error) { throw new ToolFailure(UNAVAILABLE); }
    }
    static boolean rechecked(Connection c, TenantId tenant, SubjectId subject, UUID id, Set<UUID> ids) throws SQLException {
        try (var read = c.prepareStatement("SELECT DISTINCT evidence_id FROM audit.tool_read_call WHERE tenant_id=? AND subject_id=? AND session_id=? AND tool='evidence.get@2.0.0' AND outcome IN ('OK','PARTIAL') AND evidence_id IS NOT NULL")) {
            read.setQueryTimeout(3); read.setString(1, tenant.value()); read.setString(2, subject.value()); read.setObject(3, id);
            var seen = new HashSet<UUID>(); try (var rows = read.executeQuery()) { while (rows.next()) seen.add(rows.getObject(1, UUID.class)); } return seen.containsAll(ids);
        }
    }
    private static void audit(Connection c, UUID id, TenantId tenant, SubjectId subject, UUID session, UUID evidence, String tool, String outcome, int bytes, Instant start, Instant end) throws SQLException {
        try (var insert = c.prepareStatement("INSERT INTO audit.tool_read_call (id,tenant_id,subject_id,session_id,evidence_id,tool,outcome,started_at,completed_at,result_bytes) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            insert.setQueryTimeout(3); insert.setObject(1, id); insert.setString(2, tenant.value()); insert.setString(3, subject.value()); insert.setObject(4, session); insert.setObject(5, evidence);
            insert.setString(6, tool); insert.setString(7, outcome); insert.setTimestamp(8, Timestamp.from(start)); insert.setTimestamp(9, end == null ? null : Timestamp.from(end)); insert.setInt(10, bytes); insert.executeUpdate();
        }
    }
    static ToolReadSession session(ResultSet row) throws SQLException {
        Array array = row.getArray("entity_ids"); var entities = new HashSet<EntityId>();
        try { for (Object id : (Object[]) array.getArray()) entities.add(EntityId.parse(id.toString())); } finally { array.free(); }
        Instant created = Instant.parse(row.getString("created_at_text"));
        return new ToolReadSession(row.getObject("id", UUID.class), new TenantId(row.getString("tenant_id")), new SubjectId(row.getString("subject_id")), row.getObject("incident_id", UUID.class),
            row.getLong("incident_version"), entities, new ToolWindow(Instant.parse(row.getString("from_text")), Instant.parse(row.getString("to_text"))), created, created.plusSeconds(60), row.getInt("used_calls"));
    }
    private static void interrupted() { if (Thread.currentThread().isInterrupted()) throw new ToolFailure(DEADLINE); }
    private static void limits(Connection c) throws SQLException { try (var s = c.createStatement()) { s.execute("SET LOCAL statement_timeout='3s'"); s.execute("SET LOCAL lock_timeout='2s'"); } }
}
