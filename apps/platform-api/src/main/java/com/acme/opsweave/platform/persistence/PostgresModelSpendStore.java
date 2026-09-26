package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.ModelSpendStore;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.platform.ai.ModelSpendJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

final class PostgresModelSpendStore implements ModelSpendStore {
    private final DataSource dataSource;
    PostgresModelSpendStore(DataSource dataSource) { this.dataSource=dataSource; }
    private static void lock(Connection c,TenantId tenant) throws SQLException {
        try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) { s.setString(1,"model-spend:"+tenant.value());s.setQueryTimeout(5);s.execute(); }
    }
    private static Optional<ModelSpend.Call> find(Connection c,TenantId tenant,UUID run) throws SQLException {
        try(var s=c.prepareStatement("SELECT body FROM ai_control.model_spend WHERE tenant_id=? AND run_id=?")) {
            s.setString(1,tenant.value());s.setObject(2,run);s.setQueryTimeout(5);try(var rows=s.executeQuery()){return rows.next()?Optional.of(ModelSpendJson.decode(rows.getString(1))):Optional.empty();}
        }
    }
    @Override public ModelSpend.Call reserve(ModelSpend.Call call) {
        Transactions.run(dataSource,c->{
            lock(c,call.tenantId()); if(find(c,call.tenantId(),call.runId()).isPresent()) throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED);
            try(var s=c.prepareStatement("SELECT count(*),COALESCE(sum(accounted_micros) FILTER (WHERE day=? OR NOT reported),0) FROM ai_control.model_spend WHERE tenant_id=?")) {
                s.setObject(1,call.day());s.setString(2,call.tenantId().value());s.setQueryTimeout(5);
                try(var rows=s.executeQuery()){rows.next();ModelSpend.admit(call,rows.getLong(2),rows.getLong(1));}
            }
            try(var s=c.prepareStatement("INSERT INTO ai_control.model_spend(tenant_id,run_id,day,reported,accounted_micros,body) VALUES (?,?,?,false,?,?::jsonb)")) {
                s.setString(1,call.tenantId().value());s.setObject(2,call.runId());s.setObject(3,call.day());s.setLong(4,call.reservedMicros());s.setString(5,ModelSpendJson.encode(call));s.setQueryTimeout(5);s.executeUpdate();
            }
        });return call;
    }
    @Override public ModelSpend.Call report(TenantId tenant,SubjectId subject,UUID run,UUID session,ModelSpend.Usage usage,Instant now) {
        ModelSpend.Call[] result={null};Transactions.run(dataSource,c->{
            lock(c,tenant);var old=find(c,tenant,run).orElseThrow(()->new ToolFailure(ToolFailure.Code.NOT_FOUND));
            if(!old.subjectId().equals(subject) || !old.sessionId().equals(session)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
            var updated=old.report(usage,now);
            try(var s=c.prepareStatement("UPDATE ai_control.model_spend SET reported=true,accounted_micros=?,body=?::jsonb WHERE tenant_id=? AND run_id=?")) {
                s.setLong(1,updated.chargedMicros());s.setString(2,ModelSpendJson.encode(updated));s.setString(3,tenant.value());s.setObject(4,run);s.setQueryTimeout(5);s.executeUpdate();
            }result[0]=updated;
        });return result[0];
    }
    @Override public Optional<ModelSpend.Call> find(TenantId tenant,UUID run) {
        try(var c=dataSource.getConnection()){return find(c,tenant,run);}catch(SQLException failed){throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);}
    }
}
