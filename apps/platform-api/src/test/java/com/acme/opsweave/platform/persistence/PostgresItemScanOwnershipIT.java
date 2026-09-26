package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.telemetry.domain.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresItemScanOwnershipIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("item-scan-pg-"+UUID.randomUUID());
    final SourceScan.Scope scope=new SourceScan.Scope(tenant,"zabbix-1","item");
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);
    final Principal principal=new Principal(new SubjectId("user-demo"),tenant,Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());

    Connection connect()throws SQLException{return DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    void expire()throws SQLException{
        try(var c=connect();var s=c.prepareStatement("UPDATE inventory.source_scan_lease SET lease_until=started_at+interval '1 microsecond' WHERE tenant_id=? AND external_type='item'")){
            s.setString(1,tenant.value());assertEquals(1,s.executeUpdate());
        }
    }
    void seed(String itemId){
        wiring.metrics().upsert(definition());
        wiring.metrics().upsert(binding(itemId));
    }
    MetricDefinition definition(){
        return new MetricDefinition(tenant,"stale.metric","Stale","1",MetricValueType.DOUBLE,MetricType.GAUGE,List.of("mode"),1);
    }
    MetricBinding binding(String itemId){
        return new MetricBinding(tenant,"zabbix","zabbix-1",itemId,EntityIds.fromExternal(new ExternalObjectKey(tenant,"zabbix-1","host","10084","1")),"10084","stale.metric",Map.of("mode","idle"),"%","identity",1,MetricLifecycle.ACTIVE,1);
    }
    MetricLifecycle lifecycle(String itemId){return wiring.metrics().findBinding(tenant,"zabbix-1",itemId).orElseThrow().lifecycle();}
    IngestZabbixItemsUseCase items(){
        return new IngestZabbixItemsUseCase(new AuthorizeUseCase(),new FixtureZabbixItemConnector(),ClasspathMappingCatalog.load(getClass().getClassLoader()),wiring.writer(),wiring.itemWrites(),wiring.rawRecords(),wiring.syncRuns(),"labeled-fixture",wiring.label(),"zabbix-1","env:OPSWEAVE_ZABBIX_TOKEN",1);
    }

    @Test void foreignLeaseBlocksTheItemScanAndWritesNothing()throws Exception{
        seed("29999");
        var holder=wiring.writer().beginScan(scope,UUID.randomUUID());
        var blocked=items().execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE,blocked.kind());
        assertEquals("SOURCE_SCAN_BUSY",blocked.reasonCode());
        assertEquals(0,blocked.pages());assertEquals(0,blocked.accepted());
        assertEquals(MetricLifecycle.ACTIVE,lifecycle("29999"));
        assertTrue(wiring.metrics().findBinding(tenant,"zabbix-1","20001").isEmpty());
        var run=wiring.syncRuns().find(tenant,blocked.syncRunId()).orElseThrow();
        assertEquals(SyncStatus.FAILED,run.status());
        assertEquals(SyncFailureCode.SOURCE_SCAN_BUSY.storedReason(),run.failureReason());
        wiring.writer().renewScan(holder);
        wiring.writer().releaseScan(holder);
        var recovered=items().execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,recovered.kind());
        assertEquals(1,recovered.retired());
        assertEquals(MetricLifecycle.INACTIVE,lifecycle("29999"));
    }

    @Test void expiredLeaseIsRecoveredButTheSupersededScanCannotRetire()throws Exception{
        seed("29999");
        var stale=wiring.writer().beginScan(scope,UUID.randomUUID());
        expire();
        var outcome=items().execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,outcome.kind());
        assertTrue(outcome.retired()>=1);
        assertEquals(MetricLifecycle.INACTIVE,lifecycle("29999"));
        assertEquals(MetricLifecycle.ACTIVE,lifecycle("20001"));
        var before=wiring.metrics().listBindings(tenant);
        assertEquals(SourceScan.Code.LOST,assertThrows(SourceScan.Failure.class,()->wiring.itemWrites().retireMissing(stale,Set.of())).code());
        assertEquals(before,wiring.metrics().listBindings(tenant));
        assertEquals(MetricLifecycle.ACTIVE,lifecycle("20001"));
    }

    @Test void fencedWriteRefusesAnExpiredLeaseAndKeepsTheCatalog()throws Exception{
        seed("29999");
        var token=wiring.writer().beginScan(scope,UUID.randomUUID());
        expire();
        var before=wiring.metrics().listBindings(tenant);
        assertEquals(SourceScan.Code.LOST,assertThrows(SourceScan.Failure.class,()->wiring.itemWrites().retireMissing(token,Set.of())).code());
        assertEquals(SourceScan.Code.LOST,assertThrows(SourceScan.Failure.class,()->wiring.itemWrites().upsert(token,definition(),binding("20009"))).code());
        assertEquals(before,wiring.metrics().listBindings(tenant));
        assertEquals(MetricLifecycle.ACTIVE,lifecycle("29999"));
        assertTrue(wiring.metrics().findBinding(tenant,"zabbix-1","20009").isEmpty());
    }

    @Test void successfulItemScanReleasesItsLeaseForTheNextFence(){
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,items().execute(principal,null).kind());
        var next=wiring.writer().beginScan(scope,UUID.randomUUID());
        assertTrue(next.fence()>=2,"a finished item scan releases its scope");
        wiring.writer().releaseScan(next);
    }
}
