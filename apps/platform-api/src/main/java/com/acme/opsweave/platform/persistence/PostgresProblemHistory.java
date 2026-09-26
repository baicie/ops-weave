package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.alerting.domain.ProblemObservation;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.platform.incident.ProblemHistoryJson;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

final class PostgresProblemHistory {
    private PostgresProblemHistory() {}
    // Caller owns the tenant occurrence lock and transaction that updates the live projection.
    static void capture(Connection c,ProblemObservation entry)throws SQLException {
        var p=entry.observation();
        try(var s=c.prepareStatement("SELECT * FROM alerting.problem_observation WHERE tenant_id=? AND id=?")) {
            s.setString(1,p.tenantId().value());s.setObject(2,entry.id());
            try(var rows=s.executeQuery()){if(rows.next()){if(!read(rows).sameInput(entry))throw new IncidentFailure(CONFLICT);return;}}
        }
        var entities=c.createArrayOf("uuid",entry.entities().values().stream().distinct().map(EntityId::value).toArray());
        try(var s=c.prepareStatement("INSERT INTO alerting.problem_observation(tenant_id,id,source_instance_id,problem_event_id,observed_epoch_nanos,first_received_epoch_nanos,entity_ids,has_unmapped,body) VALUES (?,?,?,?,?,?,?,?,?::jsonb)")) {
            s.setString(1,p.tenantId().value());s.setObject(2,entry.id());s.setString(3,p.sourceInstanceId());s.setString(4,p.problemEventId());
            s.setBigDecimal(5,PostgresInventoryStore.nanos(p.observedAt()));s.setBigDecimal(6,PostgresInventoryStore.nanos(entry.firstReceivedAt()));
            s.setArray(7,entities);s.setBoolean(8,entry.hasUnmappedHosts());s.setString(9,ProblemHistoryJson.encode(entry));s.executeUpdate();
        }finally{entities.free();}
    }
    static List<ProblemObservation> page(Connection c,TenantId tenant,UUID incident,IncidentVisibility visibility,ProblemHistoryQuery q)throws SQLException {
        var entities=c.createArrayOf("uuid",visibility.entities().stream().map(EntityId::value).toArray());
        try(var s=c.prepareStatement("""
            SELECT h.* FROM alerting.problem_observation h
            JOIN alerting.external_problem p ON p.tenant_id=h.tenant_id AND p.source_instance_id=h.source_instance_id AND p.problem_event_id=h.problem_event_id
            WHERE h.tenant_id=? AND p.incident_id=? AND h.observed_epoch_nanos>=? AND h.observed_epoch_nanos<=? AND h.first_received_epoch_nanos<=?
            AND (?='' OR h.source_instance_id=?) AND (?='' OR h.problem_event_id=?) AND (?::uuid IS NULL OR h.id>?::uuid)
            AND (? OR (NOT h.has_unmapped AND cardinality(h.entity_ids)>0 AND h.entity_ids <@ ?)) ORDER BY h.id LIMIT ?
            """)) {
            s.setQueryTimeout(3);s.setString(1,tenant.value());s.setObject(2,incident);s.setBigDecimal(3,PostgresInventoryStore.nanos(Instant.ofEpochSecond(q.from())));
            s.setBigDecimal(4,PostgresInventoryStore.nanos(Instant.ofEpochSecond(q.till())));s.setBigDecimal(5,PostgresInventoryStore.nanos(q.asOf()));
            s.setString(6,q.source());s.setString(7,q.source());s.setString(8,q.eventId());s.setString(9,q.eventId());s.setObject(10,q.after());s.setObject(11,q.after());
            s.setBoolean(12,visibility.allEntities());s.setArray(13,entities);s.setInt(14,q.limit()+1);
            var result=new ArrayList<ProblemObservation>();try(var rows=s.executeQuery()){while(rows.next())result.add(read(rows));}return List.copyOf(result);
        }finally{entities.free();}
    }
    private static ProblemObservation read(ResultSet rows)throws SQLException {
        var entry=ProblemHistoryJson.decode(rows.getString("body"));var p=entry.observation();
        if(!p.tenantId().value().equals(rows.getString("tenant_id"))||!entry.id().equals(rows.getObject("id",UUID.class))
                ||!p.sourceInstanceId().equals(rows.getString("source_instance_id"))||!p.problemEventId().equals(rows.getString("problem_event_id"))
                ||!p.observedAt().equals(PostgresInventoryStore.instant(rows.getBigDecimal("observed_epoch_nanos")))
                ||!entry.firstReceivedAt().equals(PostgresInventoryStore.instant(rows.getBigDecimal("first_received_epoch_nanos")))
                ||entry.hasUnmappedHosts()!=rows.getBoolean("has_unmapped"))throw new IncidentFailure(UNAVAILABLE);
        var array=rows.getArray("entity_ids");try {
            var actual=new HashSet<EntityId>();for(Object id:(Object[])array.getArray())actual.add(EntityId.parse(id.toString()));
            if(!actual.equals(new HashSet<>(entry.entities().values())))throw new IncidentFailure(UNAVAILABLE);
        }finally{array.free();}return entry;
    }
}
