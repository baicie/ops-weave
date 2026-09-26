package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.AiInsightStore;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.platform.ai.InsightJson;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import static com.acme.opsweave.aicontrol.domain.ToolFailure.Code.*;

final class PostgresAiInsightStore implements AiInsightStore {
    private final DataSource source;
    private final Clock clock;
    PostgresAiInsightStore(DataSource source, Clock clock) { this.source = source; this.clock = clock; }
    public Optional<AiInsight> find(TenantId tenant, UUID id) {
        try (var c = source.getConnection()) { return find(c, tenant, id); } catch (SQLException error) { throw new ToolFailure(UNAVAILABLE); }
    }
    public Optional<AiRetention.Marker> retired(TenantId tenant, UUID id) {
        try(var c=source.getConnection()){return PostgresAiRetentionStore.retired(c,AiRetention.Kind.INSIGHT,tenant,id);}catch(SQLException e){throw new ToolFailure(UNAVAILABLE);}
    }
    static Optional<AiInsight> find(Connection c, TenantId tenant, UUID id) throws SQLException {
        try (var read = c.prepareStatement("SELECT * FROM ai_control.ai_insight WHERE tenant_id=? AND id=? AND snapshot IS NOT NULL")) {
            read.setQueryTimeout(3); read.setString(1, tenant.value()); read.setObject(2, id);
            try (var rows = read.executeQuery()) {
                if (!rows.next()) return Optional.empty(); var result = InsightJson.decode(rows.getString("snapshot"), rows.getString("request_digest"));
                if (!result.tenantId().equals(tenant) || !result.input().runId().equals(id) || !result.subjectId().value().equals(rows.getString("subject_id"))
                    || !result.input().sessionId().equals(rows.getObject("session_id", UUID.class)) || !result.incidentId().equals(rows.getObject("incident_id", UUID.class))
                    || result.incidentVersion() != rows.getLong("incident_version") || !result.savedAt().toString().equals(rows.getString("saved_at_text"))
                    || !result.expiresAt().toString().equals(rows.getString("expires_at_text"))) throw new ToolFailure(UNAVAILABLE);
                return Optional.of(result);
            }
        }
    }
    public AiInsight save(AiInsight value) {
        AiInsight[] saved = new AiInsight[1];
        Transactions.run(source, c -> {
            try (var statement = c.createStatement()) { statement.execute("SET LOCAL statement_timeout='3s'"); statement.execute("SET LOCAL lock_timeout='2s'"); }
            // Serialize idempotency before touching the session or Incident, consistently for every caller.
            try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "ai-insight:" + value.tenantId().value().length() + ":" + value.tenantId().value() + ":" + value.input().runId()); lock.execute();
            }
            var old = find(c, value.tenantId(), value.input().runId());
            if (old.isPresent()) {
                if (!old.get().subjectId().equals(value.subjectId()) || !old.get().requestDigest().equals(value.requestDigest())) throw new ToolFailure(INPUT_CHANGED);
                saved[0] = old.get(); return;
            }
            var retired=PostgresAiRetentionStore.retired(c,AiRetention.Kind.INSIGHT,value.tenantId(),value.input().runId());
            if(retired.isPresent()){var m=retired.get();if(!m.subjectId().equals(value.subjectId()) || !m.requestDigest().equals(value.requestDigest()))throw new ToolFailure(INPUT_CHANGED);throw new ToolFailure(EXPIRED);}
            ToolReadSession session;
            try (var read = c.prepareStatement("SELECT * FROM ai_control.tool_read_session WHERE tenant_id=? AND id=? AND subject_id=? FOR UPDATE")) {
                read.setString(1, value.tenantId().value()); read.setObject(2, value.input().sessionId()); read.setString(3, value.subjectId().value());
                try (var rows = read.executeQuery()) { if (!rows.next()) throw new ToolFailure(NOT_FOUND); session = PostgresToolReadStore.session(rows); }
            }
            if (!clock.instant().isBefore(session.deadlineAt())) throw new ToolFailure(EXPIRED);
            if (!session.incidentId().equals(value.incidentId()) || session.incidentVersion() != value.incidentVersion() || !session.entityIds().equals(value.entityIds()) || !session.window().equals(value.window())) throw new ToolFailure(INPUT_CHANGED);
            if (session.usedCalls() != 4 || !PostgresToolReadStore.rechecked(c, value.tenantId(), value.subjectId(), session.id(), Set.copyOf(value.input().evidenceIds()))) throw new ToolFailure(INVALID_REQUEST);
            try (var read = c.prepareStatement("SELECT version,entity_ids FROM incident.incident WHERE tenant_id=? AND id=? FOR SHARE")) {
                read.setString(1, value.tenantId().value()); read.setObject(2, value.incidentId());
                try (var rows = read.executeQuery()) {
                    if (!rows.next()) throw new ToolFailure(NOT_FOUND); var ids = new HashSet<EntityId>(); var array = rows.getArray("entity_ids");
                    try { for (Object id : (Object[]) array.getArray()) ids.add(EntityId.parse(id.toString())); } finally { array.free(); }
                    if (rows.getLong("version") != value.incidentVersion() || !ids.equals(value.entityIds())) throw new ToolFailure(INPUT_CHANGED);
                }
            }
            try (var duplicate = c.prepareStatement("SELECT id FROM ai_control.ai_insight WHERE tenant_id=? AND session_id=?")) {
                duplicate.setString(1, value.tenantId().value()); duplicate.setObject(2, session.id()); try (var rows = duplicate.executeQuery()) { if (rows.next()) throw new ToolFailure(INPUT_CHANGED); }
            }
            try (var insert = c.prepareStatement("INSERT INTO ai_control.ai_insight (tenant_id,id,subject_id,session_id,incident_id,incident_version,request_digest,saved_at_text,expires_at_text,snapshot,retention_created_at,retention_expires_at) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?)")) {
                insert.setString(1, value.tenantId().value()); insert.setObject(2, value.input().runId()); insert.setString(3, value.subjectId().value()); insert.setObject(4, session.id()); insert.setObject(5, value.incidentId());
                insert.setLong(6, value.incidentVersion()); insert.setString(7, value.requestDigest()); insert.setString(8, value.savedAt().toString()); insert.setString(9, value.expiresAt().toString()); insert.setString(10, InsightJson.encode(value));
                insert.setTimestamp(11,Timestamp.from(value.savedAt()));insert.setTimestamp(12,Timestamp.from(value.expiresAt()));insert.executeUpdate();
            }
            for (UUID id : value.input().evidenceIds()) try (var insert = c.prepareStatement("INSERT INTO ai_control.ai_insight_evidence (tenant_id,insight_id,evidence_id) VALUES (?,?,?)")) {
                insert.setString(1, value.tenantId().value()); insert.setObject(2, value.input().runId()); insert.setObject(3, id); insert.executeUpdate();
            }
            value.checkLive(clock.instant()); if (!clock.instant().isBefore(session.deadlineAt()) || Thread.currentThread().isInterrupted()) throw new ToolFailure(DEADLINE);
            saved[0] = value;
        }); return saved[0];
    }
}
