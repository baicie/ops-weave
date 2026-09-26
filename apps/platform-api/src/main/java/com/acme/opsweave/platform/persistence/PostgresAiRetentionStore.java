package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.AiRetentionStore;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.ai.*;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import static com.acme.opsweave.aicontrol.domain.AiRetention.*;
import static com.acme.opsweave.aicontrol.domain.ToolFailure.Code.*;

/** Explicit tenant-scoped payload scrubbing. Primary keys, reference edges and model spend survive. */
final class PostgresAiRetentionStore implements AiRetentionStore {
    private final DataSource source;private final Clock clock;
    PostgresAiRetentionStore(DataSource source,Clock clock){this.source=source;this.clock=clock;}
    public Preview preview(Policy policy,SubjectId actor,Instant asOf){
        Preview[] out=new Preview[1];Transactions.run(source,c->{limits(c);out[0]=preview(c,policy,actor,asOf,false);out[0].checkLive(clock.instant());});return out[0];
    }
    public Optional<Receipt> receipt(TenantId tenant,UUID id){try(var c=source.getConnection()){return receipt(c,tenant,id);}catch(SQLException e){throw new ToolFailure(UNAVAILABLE);}}
    private static Optional<Receipt> receipt(Connection c,TenantId tenant,UUID id)throws SQLException{
        try(var s=c.prepareStatement("SELECT body FROM ai_control.retention_receipt WHERE tenant_id=? AND request_id=?")){s.setQueryTimeout(3);s.setString(1,tenant.value());s.setObject(2,id);try(var r=s.executeQuery()){if(!r.next())return Optional.empty();var v=AiRetentionJson.receipt(r.getString(1));if(!v.tenantId().equals(tenant) || !v.command().requestId().equals(id))throw new ToolFailure(UNAVAILABLE);return Optional.of(v);}}
    }
    public Receipt apply(Policy policy,SubjectId actor,Command command,Runnable recheckConfiguration){
        Receipt[] out=new Receipt[1];Transactions.run(source,c->{limits(c);
            try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"ai-retention:"+policy.tenantId().value());s.execute();}
            var old=receipt(c,policy.tenantId(),command.requestId());if(old.isPresent()){var r=old.get();if(!r.actor().equals(actor) || !r.command().equals(command))throw new ToolFailure(INPUT_CHANGED);out[0]=r;return;}
            if(!policy.allowPurge())throw new ToolFailure(FORBIDDEN);if(!command.policyDigest().equals(policy.digest()))throw new ToolFailure(INPUT_CHANGED);
            var preview=preview(c,policy,actor,command.asOf(),true);preview.checkLive(clock.instant());if(!preview.digest().equals(command.previewDigest()))throw new ToolFailure(INPUT_CHANGED);
            try(var s=c.prepareStatement("SELECT count(*) FROM ai_control.retention_receipt WHERE tenant_id=?")){s.setString(1,policy.tenantId().value());try(var r=s.executeQuery()){r.next();if(r.getLong(1)>=10000)throw new ToolFailure(READ_LIMIT);}}
            for(var batch:preview.batches())for(var id:batch.ids()){
                if(batch.kind()==Kind.AUDIT){try(var s=c.prepareStatement("DELETE FROM audit.tool_read_call WHERE tenant_id=? AND id=?")){s.setString(1,policy.tenantId().value());s.setObject(2,id);if(s.executeUpdate()!=1)throw new ToolFailure(INPUT_CHANGED);}}
                else scrub(c,policy,batch.kind(),id,command.asOf(),clock.instant());
            }
            recheckConfiguration.run();preview.checkLive(clock.instant());if(Thread.currentThread().isInterrupted())throw new ToolFailure(DEADLINE);
            var result=new Receipt(policy.tenantId(),actor,command,preview,clock.instant());
            try(var s=c.prepareStatement("INSERT INTO ai_control.retention_receipt(tenant_id,request_id,body) VALUES(?,?,?::jsonb)")){s.setString(1,policy.tenantId().value());s.setObject(2,command.requestId());s.setString(3,AiRetentionJson.encode(result));s.executeUpdate();}out[0]=result;
        });return out[0];
    }
    private static Preview preview(Connection c,Policy policy,SubjectId actor,Instant asOf,boolean lock)throws SQLException{
        var batches=new ArrayList<Batch>();
        for(var kind:Kind.values()){
            // PostgreSQL timestamp precision may round to the boundary; strict comparisons retain that row.
            String sql=kind==Kind.AUDIT?"SELECT x.id,0 AS bytes FROM audit.tool_read_call x LEFT JOIN ai_control.tool_read_session s ON s.tenant_id=x.tenant_id AND s.id=x.session_id LEFT JOIN ai_control.evidence_snapshot e ON e.tenant_id=x.tenant_id AND e.id=x.evidence_id WHERE x.tenant_id=? AND x.started_at<? AND COALESCE(x.completed_at,x.started_at)<? AND (s.id IS NULL OR s.deadline_at<?) AND (s.incident_id IS NULL OR NOT(s.incident_id=ANY(?))) AND (e.incident_id IS NULL OR NOT(e.incident_id=ANY(?))) ORDER BY x.started_at,x.id LIMIT ?":
                "SELECT x.id,octet_length(x.snapshot::text) AS bytes FROM "+table(kind)+" x WHERE x.tenant_id=? AND x.snapshot IS NOT NULL AND x.retention_created_at<? AND x.retention_expires_at<? AND NOT(x.incident_id=ANY(?)) ORDER BY x.retention_created_at,x.id LIMIT ?";
            if(lock)sql+=" FOR UPDATE OF x";
            var ids=new ArrayList<UUID>();long bytes=0;boolean more=false;var held=c.createArrayOf("uuid",policy.heldIncidents().toArray());
            try(var s=c.prepareStatement(sql)){s.setString(1,policy.tenantId().value());s.setTimestamp(2,Timestamp.from(policy.cutoff(kind,asOf)));s.setTimestamp(3,Timestamp.from(asOf));
                if(kind==Kind.AUDIT){s.setTimestamp(3,Timestamp.from(policy.cutoff(kind,asOf)));s.setTimestamp(4,Timestamp.from(asOf));s.setArray(5,held);s.setArray(6,held);s.setInt(7,policy.batchSize()+1);}else{s.setArray(4,held);s.setInt(5,policy.batchSize()+1);}
                try(var rows=s.executeQuery()){while(rows.next()){if(ids.size()==policy.batchSize()){more=true;break;}ids.add(rows.getObject(1,UUID.class));bytes+=rows.getLong(2);}}
            }finally{held.free();}batches.add(new Batch(kind,ids,bytes,more));
        }return new Preview(policy.tenantId(),actor,policy.digest(),asOf,batches);
    }
    private static void scrub(Connection c,Policy policy,Kind kind,UUID id,Instant asOf,Instant now)throws SQLException{
        Marker marker;
        if(kind==Kind.INSIGHT){var value=PostgresAiInsightStore.find(c,policy.tenantId(),id).orElseThrow(()->new ToolFailure(INPUT_CHANGED));
            if(!policy.eligible(kind,value.incidentId(),value.savedAt(),value.expiresAt(),asOf))throw new ToolFailure(INPUT_CHANGED);
            var keys=new HashSet<String>();for(var evidenceId:value.input().evidenceIds()){
                try(var s=c.prepareStatement("SELECT snapshot,retired_metadata FROM ai_control.evidence_snapshot WHERE tenant_id=? AND id=?")){s.setString(1,policy.tenantId().value());s.setObject(2,evidenceId);try(var r=s.executeQuery()){if(!r.next())throw new ToolFailure(UNAVAILABLE);if(r.getString(1)!=null)keys.addAll(metricKeys(ToolJson.decode(r.getString(1))));else keys.addAll(AiRetentionJson.marker(r.getString(2)).metricKeys());}}
            }
            marker=new Marker(kind,value.tenantId(),id,value.input().sessionId(),value.subjectId(),value.incidentId(),value.incidentVersion(),value.entityIds(),keys,value.expiresAt(),now,value.requestDigest());
        }else{
            try(var s=c.prepareStatement("SELECT e.snapshot,s.subject_id FROM ai_control.evidence_snapshot e JOIN ai_control.tool_read_session s ON s.tenant_id=e.tenant_id AND s.id=e.session_id WHERE e.tenant_id=? AND e.id=?")){s.setString(1,policy.tenantId().value());s.setObject(2,id);try(var r=s.executeQuery()){if(!r.next())throw new ToolFailure(UNAVAILABLE);var value=ToolJson.decode(r.getString(1));
                if(!value.tenantId().equals(policy.tenantId()) || !value.id().equals(id) || !policy.eligible(kind,value.incidentId(),value.availableAt(),value.expiresAt(),asOf))throw new ToolFailure(INPUT_CHANGED);
                marker=new Marker(kind,value.tenantId(),id,value.sessionId(),new SubjectId(r.getString(2)),value.incidentId(),value.incidentVersion(),value.entityIds(),metricKeys(value),value.expiresAt(),now,null);
            }}
        }
        try(var s=c.prepareStatement("UPDATE "+table(kind)+" SET snapshot=NULL,retired_metadata=?::jsonb WHERE tenant_id=? AND id=? AND snapshot IS NOT NULL")){s.setString(1,AiRetentionJson.encode(marker));s.setString(2,policy.tenantId().value());s.setObject(3,id);if(s.executeUpdate()!=1)throw new ToolFailure(INPUT_CHANGED);}
    }
    private static Set<String> metricKeys(PlatformEvidence e){if(!e.kind().equals("metric"))return Set.of();if(!(e.data().get("metricKey") instanceof String key))throw new ToolFailure(UNAVAILABLE);return Set.of(key);}
    static Optional<Marker> retired(Connection c,Kind kind,TenantId tenant,UUID id)throws SQLException{
        try(var s=c.prepareStatement("SELECT retired_metadata,session_id,incident_id FROM "+table(kind)+" WHERE tenant_id=? AND id=? AND snapshot IS NULL")){s.setQueryTimeout(3);s.setString(1,tenant.value());s.setObject(2,id);try(var r=s.executeQuery()){if(!r.next())return Optional.empty();var m=AiRetentionJson.marker(r.getString(1));if(m.kind()!=kind || !m.tenantId().equals(tenant) || !m.id().equals(id) || !m.sessionId().equals(r.getObject(2,UUID.class)) || !m.incidentId().equals(r.getObject(3,UUID.class)))throw new ToolFailure(UNAVAILABLE);return Optional.of(m);}}
    }
    private static String table(Kind kind){return switch(kind){case INSIGHT->"ai_control.ai_insight";case EVIDENCE->"ai_control.evidence_snapshot";default->throw new IllegalArgumentException();};}
    private static void limits(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("SET LOCAL statement_timeout='3s'");s.execute("SET LOCAL lock_timeout='2s'");}}
}
