package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceScanIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("scan-pg-"+UUID.randomUUID());
    final SourceScan.Scope scope=new SourceScan.Scope(tenant,"zabbix-1","host");
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);
    Connection connect()throws SQLException{return DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    ZabbixHostMapper.MappedHost host(String id){var now=Instant.now();return new ZabbixHostMapper().map(PipelineDefinition.zabbixHostV1(),tenant,"zabbix-1",Map.of("hostid",id,"host","host-"+id,"status","0"),now,now,"raw-"+UUID.randomUUID());}
    void fails(SourceScan.Code code,Runnable action){assertEquals(code,assertThrows(SourceScan.Failure.class,action::run).code());}
    void expire()throws SQLException {try(var c=connect();var s=c.prepareStatement("UPDATE inventory.source_scan_lease SET lease_until=started_at+interval '1 microsecond' WHERE tenant_id=?")){s.setString(1,tenant.value());assertEquals(1,s.executeUpdate());}}

    @Test void independentAdaptersCannotAcquireTheSameSourceAndReopeningPreservesOwnership()throws Exception {
        var second=openInventory(properties);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var attempts=List.of(wiring,second).stream().map(w->pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{return w.writer().beginScan(scope,UUID.randomUUID());}catch(SourceScan.Failure f){assertEquals(SourceScan.Code.BUSY,f.code());return null;}})).toList();
            start.countDown();var a=attempts.get(0).get(10,TimeUnit.SECONDS);var b=attempts.get(1).get(10,TimeUnit.SECONDS);assertNotEquals(a==null,b==null);
            var winner=a==null?b:a;assertEquals(1,winner.fence());
            var reopened=openInventory(properties);fails(SourceScan.Code.BUSY,()->reopened.writer().beginScan(scope,UUID.randomUUID()));
            reopened.writer().renewScan(winner);reopened.writer().releaseScan(winner);
            assertEquals(2,wiring.writer().beginScan(scope,UUID.randomUUID()).fence());
        }
    }
    @Test void takeoverRejectsStaleWriteReconcileAndReleaseWithoutTouchingTheNewOwner()throws Exception {
        var old=wiring.writer().beginScan(scope,UUID.randomUUID());expire();
        fails(SourceScan.Code.LOST,()->wiring.writer().renewScan(old));
        fails(SourceScan.Code.LOST,()->wiring.writer().beginScan(scope,old.runId()));
        fails(SourceScan.Code.BUSY,()->wiring.writer().retireMissing(tenant,"zabbix-1","host",Set.of()));
        var second=openInventory(properties);var current=second.writer().beginScan(scope,UUID.randomUUID());assertEquals(old.fence()+1,current.fence());
        var fresh=host("1");second.writer().upsert(current,fresh.entity(),fresh.observation(),fresh.link());
        fails(SourceScan.Code.LOST,()->wiring.writer().upsert(old,fresh.entity(),fresh.observation(),fresh.link()));
        fails(SourceScan.Code.LOST,()->wiring.writer().finishScan(old,Set.of()));
        wiring.writer().releaseScan(old);second.writer().renewScan(current);
        fails(SourceScan.Code.BUSY,()->wiring.writer().upsert(fresh.entity(),fresh.observation(),fresh.link()));
        assertEquals(0,second.writer().finishScan(current,Set.of("1")));
        fails(SourceScan.Code.LOST,()->second.writer().finishScan(current,Set.of()));
        assertEquals("ACTIVE",wiring.query().find(tenant,fresh.entity().id()).orElseThrow().lifecycle());
        assertEquals(1,wiring.query().find(tenant,fresh.entity().id()).orElseThrow().version());
    }
    @Test void tenantSourceAndTypeIsolationAndScopeCheck() {
        var token=wiring.writer().beginScan(scope,UUID.randomUUID());var host=host("1");
        for(var other:List.of(new SourceScan.Scope(new TenantId("other-"+UUID.randomUUID()),"zabbix-1","host"),new SourceScan.Scope(tenant,"zabbix-2","host"),new SourceScan.Scope(tenant,"zabbix-1","item"))){
            var independent=wiring.writer().beginScan(other,UUID.randomUUID());assertEquals(1,independent.fence());
            fails(SourceScan.Code.LOST,()->wiring.writer().upsert(independent,host.entity(),host.observation(),host.link()));
            wiring.writer().releaseScan(independent);
        }
        assertTrue(wiring.query().find(tenant,host.entity().id()).isEmpty());wiring.writer().releaseScan(token);
    }
    @Test void expiredTransactionRollsBackEntityObservationAndLinkWrite()throws Exception {blockedTransaction(false);}
    @Test void expiredTransactionRollsBackRetirement()throws Exception {blockedTransaction(true);}

    void blockedTransaction(boolean reconcile)throws Exception {
        var host=host("1");wiring.writer().upsert(host.entity(),host.observation(),host.link());
        var update=host("1");var token=wiring.writer().beginScan(scope,UUID.randomUUID());
        // Shorten only this test-owned lease. The token's immutable start/deadline and fence stay unchanged.
        try(var c=connect();var s=c.prepareStatement("UPDATE inventory.source_scan_lease SET lease_until=clock_timestamp()+interval '750 milliseconds' WHERE tenant_id=?")){s.setString(1,tenant.value());assertEquals(1,s.executeUpdate());}
        try(var blocker=connect();var pool=Executors.newSingleThreadExecutor()){
            blocker.setAutoCommit(false);int pid;
            try(var s=blocker.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();pid=r.getInt(1);}
            try(var s=blocker.prepareStatement("SELECT id FROM inventory.entity WHERE tenant_id=? AND id=? FOR UPDATE")){s.setString(1,tenant.value());s.setObject(2,host.entity().id().value());try(var r=s.executeQuery()){assertTrue(r.next());}}
            var result=pool.submit(()->{if(reconcile)wiring.writer().finishScan(token,Set.of());else wiring.writer().upsert(token,update.entity(),update.observation(),update.link());});
            try {
                long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(600);boolean blocked=false;
                try(var monitor=connect();var s=monitor.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))")){
                    s.setInt(1,pid);
                    do {try(var r=s.executeQuery()){r.next();blocked=r.getBoolean(1);}if(!blocked)Thread.sleep(5);}while(!blocked && System.nanoTime()<until);
                }
                assertTrue(blocked,"Writer must reach the entity lock while lease is valid");
                try(var s=blocker.createStatement()){s.execute("SELECT pg_sleep(0.8)");}
            } finally {blocker.rollback();}
            var failed=assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));assertInstanceOf(SourceScan.Failure.class,failed.getCause());assertEquals(SourceScan.Code.LOST,((SourceScan.Failure)failed.getCause()).code());
        }
        var after=wiring.query().find(tenant,host.entity().id()).orElseThrow();assertEquals("ACTIVE",after.lifecycle());assertEquals(1,after.version());
        try(var c=connect();var s=c.prepareStatement("SELECT count(*) FROM inventory.entity_observation WHERE tenant_id=?")){s.setString(1,tenant.value());try(var r=s.executeQuery()){r.next();assertEquals(1,r.getInt(1));}}
    }
}
