package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.domain.ScanRunRetention;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Trace storage stays bounded on real PostgreSQL. The budget is applied in the transaction that
 * opens a scan, a pinned mapping version can never be broken by retention, and reading the trace
 * never prunes.
 */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresScanRunRetentionIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("scan-retention-pg-"+UUID.randomUUID());
    final String source="zabbix-1";
    final OpsweaveProperties properties=new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),
        new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN",source,1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);

    DriverManagerDataSource dataSource(){
        return new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    }

    ScanRunRetention.Policy tight(){ return new ScanRunRetention.Policy(3,5000); }

    SyncRun finished(PostgresSyncStore store, String objectType){
        SyncRun run=store.start(tenant,source,objectType,"labeled-fixture");
        store.succeed(tenant,run.id(),"hostid-watermark-snapshot");
        return run;
    }

    long rowsInScope()throws Exception{
        try(var db=DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
            var s=db.prepareStatement("SELECT count(*) FROM integration.source_sync_run WHERE tenant_id=? AND source_instance_id=? AND object_type='host'")){
            s.setString(1,tenant.value());s.setString(2,source);
            try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }

    @Test void openingScansKeepsTheScopeInsideItsBudget()throws Exception{
        var store=new PostgresSyncStore(dataSource(),tight());
        List<SyncRun> opened=new ArrayList<>();
        for(int index=0;index<6;index++){
            opened.add(finished(store,"host"));
            assertTrue(store.recent(tenant,source,"host",null,50).size()<=3,"a scope never holds more than its budget");
            assertTrue(store.find(tenant,opened.get(index).id()).isPresent(),"the run that just closed is never the victim of its own sweep");
        }
        assertEquals(3,store.recent(tenant,source,"host",null,50).size(),"six scans leave the budget of three");
        assertEquals(3,store.retained(tenant,source,"host"),"the reported retained count matches the stored rows");
        assertEquals(3L,rowsInScope(),"the budget is what is really stored, not only what is reported");
        assertTrue(store.find(tenant,opened.get(5).id()).isPresent(),"the newest run is still traceable");
    }

    @Test void aReadNeverPrunesAndReportsThePublishedBudget()throws Exception{
        var store=new PostgresSyncStore(dataSource(),tight());
        var only=finished(store,"host");
        assertEquals(1,store.recent(tenant,source,"host",null,50).size());
        assertEquals(1,store.retained(tenant,source,"host"));
        assertEquals(1,store.retained(tenant,source,"host"),"reading the trace twice prunes nothing");
        assertTrue(store.find(tenant,only.id()).isPresent(),"reading the trace never deletes the run it reports");
        assertEquals(1L,rowsInScope());
    }

    @Test void retentionNeverBreaksAPinnedMappingVersion()throws Exception{
        var store=new PostgresSyncStore(dataSource(),tight());
        SyncRun pinned=store.start(tenant,source,"host","labeled-fixture");
        var version=wiring.pipelines().publish(tenant,source,com.acme.opsweave.integration.domain.PipelineVersion.of(com.acme.opsweave.integration.domain.PipelineDefinition.zabbixHostV1()));
        wiring.pipelines().pin(tenant,source,pinned.id(),version.ref());
        SyncRun second=store.start(tenant,source,"host","labeled-fixture");
        store.succeed(tenant,second.id(),"hostid-watermark-snapshot");
        for(int index=0;index<4;index++){
            finished(store,"host");
        }
        assertTrue(store.find(tenant,pinned.id()).isPresent(),"a pinned run is never deleted by retention");
        assertEquals(version.ref(),wiring.pipelines().pinned(tenant,source,pinned.id()).orElseThrow(),"the pin still resolves after sweeps");
        // A protected row is outside the budget: retention bounds the rows it may delete, so the
        // pinned run is one extra row on top of the budget and never counts against it.
        assertEquals(3,store.retained(tenant,source,"host"),"the budget still bounds the rows retention may delete");
        assertEquals(4,store.recent(tenant,source,"host",null,50).size(),"the pinned run stays on top of the budget");
        assertEquals(4L,rowsInScope(),"the scope holds exactly the budget plus the protected run");
    }

    @Test void budgetsArePerScopeAndPerTenant()throws Exception{
        var store=new PostgresSyncStore(dataSource(),tight());
        var host=finished(store,"host");
        var item=finished(store,"item");
        assertEquals(1,store.retained(tenant,source,"host"));
        assertEquals(1,store.retained(tenant,source,"item"));
        assertTrue(store.find(tenant,host.id()).isPresent()&&store.find(tenant,item.id()).isPresent(),"a scope sweep never touches another object type");
        var otherSource=store.start(tenant,"zabbix-2","host","labeled-fixture");
        store.succeed(tenant,otherSource.id(),"hostid-watermark-snapshot");
        assertTrue(store.find(tenant,host.id()).isPresent()&&store.find(tenant,otherSource.id()).isPresent(),"another source has its own budget");
        var otherTenant=new TenantId("scan-retention-pg-other-"+UUID.randomUUID());
        var foreign=store.start(otherTenant,source,"host","labeled-fixture");
        store.succeed(otherTenant,foreign.id(),"hostid-watermark-snapshot");
        assertTrue(store.find(otherTenant,foreign.id()).isPresent()&&store.find(tenant,host.id()).isPresent(),"one tenant's budget never prunes another tenant");
    }
}
