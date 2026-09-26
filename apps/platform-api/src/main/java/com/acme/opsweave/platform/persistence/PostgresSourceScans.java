package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.domain.SourceScan;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** The scope lock and database clock guard the same transaction that writes inventory or reconciles absence. */
final class PostgresSourceScans {
    private PostgresSourceScans() {}
    static void lock(Connection c,SourceScan.Scope scope)throws SQLException{
        try(var s=c.createStatement()){s.execute("SET LOCAL statement_timeout='5s'");s.execute("SET LOCAL lock_timeout='2s'");}
        try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"inventory-scan:"+scope.tenantId().value().length()+":"+scope.tenantId().value()+":"+scope.sourceInstanceId().length()+":"+scope.sourceInstanceId()+":"+scope.externalType());s.execute();}
    }
    static Instant now(Connection c)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery("SELECT clock_timestamp()")){r.next();return r.getTimestamp(1).toInstant();}}
    static SourceScan.Lease read(Connection c,SourceScan.Scope scope)throws SQLException{
        try(var s=c.prepareStatement("SELECT * FROM inventory.source_scan_lease WHERE tenant_id=? AND source_instance_id=? AND external_type=?")){bind(s,scope);try(var r=s.executeQuery()){if(!r.next())return null;return new SourceScan.Lease(new SourceScan.Token(scope,r.getObject("run_id",UUID.class),r.getLong("fence"),r.getTimestamp("started_at").toInstant(),r.getTimestamp("deadline_at").toInstant()),r.getTimestamp("lease_until").toInstant(),r.getBoolean("released"));}}
    }
    static SourceScan.Lease require(Connection c,SourceScan.Token token)throws SQLException{lock(c,token.scope());var lease=read(c,token.scope());if(lease==null)throw new SourceScan.Failure(SourceScan.Code.LOST);lease.require(token,now(c));return lease;}
    static void unmanaged(Connection c,SourceScan.Scope scope)throws SQLException{lock(c,scope);var lease=read(c,scope);if(lease!=null && !lease.released())throw new SourceScan.Failure(SourceScan.Code.BUSY);}
    static void write(Connection c,SourceScan.Lease lease)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO inventory.source_scan_lease(tenant_id,source_instance_id,external_type,run_id,fence,started_at,deadline_at,lease_until,released) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,source_instance_id,external_type) DO UPDATE SET run_id=EXCLUDED.run_id,fence=EXCLUDED.fence,started_at=EXCLUDED.started_at,deadline_at=EXCLUDED.deadline_at,lease_until=EXCLUDED.lease_until,released=EXCLUDED.released")){
            var t=lease.token();bind(s,t.scope());s.setObject(4,t.runId());s.setLong(5,t.fence());s.setTimestamp(6,Timestamp.from(t.startedAt()));s.setTimestamp(7,Timestamp.from(t.deadlineAt()));s.setTimestamp(8,Timestamp.from(lease.leaseUntil()));s.setBoolean(9,lease.released());s.executeUpdate();
        }
    }
    private static void bind(PreparedStatement s,SourceScan.Scope scope)throws SQLException{s.setString(1,scope.tenantId().value());s.setString(2,scope.sourceInstanceId());s.setString(3,scope.externalType());}
}
