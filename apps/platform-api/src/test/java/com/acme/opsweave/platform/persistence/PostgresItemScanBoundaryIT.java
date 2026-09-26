package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.IngestZabbixItemsUseCase;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixItemConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcItemConnector;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.telemetry.domain.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresItemScanBoundaryIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("item-boundary-pg-"+UUID.randomUUID());
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);
    final Principal principal=new Principal(new SubjectId("user-demo"),tenant,Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());

    void seed(String itemId){
        wiring.metrics().upsert(new MetricDefinition(tenant,"stale.metric","Stale","1",MetricValueType.DOUBLE,MetricType.GAUGE,List.of("mode"),1));
        wiring.metrics().upsert(new MetricBinding(tenant,"zabbix","zabbix-1",itemId,EntityIds.fromExternal(new ExternalObjectKey(tenant,"zabbix-1","host","10084","1")),"10084","stale.metric",Map.of("mode","idle"),"%","identity",1,MetricLifecycle.ACTIVE,1));
    }
    MetricLifecycle lifecycle(String itemId){return wiring.metrics().findBinding(tenant,"zabbix-1",itemId).orElseThrow().lifecycle();}
    IngestZabbixItemsUseCase items(Connector connector,int pageSize){
        return new IngestZabbixItemsUseCase(new AuthorizeUseCase(),connector,ClasspathMappingCatalog.load(getClass().getClassLoader()),wiring.writer(),wiring.itemWrites(),wiring.rawRecords(),wiring.syncRuns(),"labeled-fixture",wiring.label(),"zabbix-1","env:OPSWEAVE_ZABBIX_TOKEN",pageSize);
    }

    @Test void aVerifiedItemidWalkRetiresAbsentBindingsOnRealPostgres(){
        seed("29999");
        var outcome=items(new FixtureZabbixItemConnector(),1).execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,outcome.kind());
        assertTrue(outcome.snapshotComplete());
        assertEquals("itemid-watermark-snapshot",outcome.scanConsistency());
        assertEquals(1,outcome.accepted());assertEquals(1,outcome.rejected());assertEquals(1,outcome.retired());
        assertEquals(MetricLifecycle.INACTIVE,lifecycle("29999"));
        assertEquals(MetricLifecycle.ACTIVE,lifecycle("20001"));
        assertEquals("itemid-watermark-snapshot",wiring.syncRuns().find(tenant,outcome.syncRunId()).orElseThrow().scanConsistency());
    }

    @Test void aShiftedItemWalkIsRecordedAsUnverifiedAndRetiresNothing(){
        seed("29999");
        var transport=new ScriptedTransport();
        transport.script("20004",4,List.of(item("20001"),item("20002")),List.of(item("20004")));
        var connector=new ZabbixJsonRpcItemConnector(URI.create("http://127.0.0.1/api_jsonrpc.php"),transport,secretRef->"stub-token");
        var outcome=items(connector,2).execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE,outcome.kind());
        assertEquals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.name(),outcome.reasonCode());
        assertFalse(outcome.snapshotComplete());
        assertEquals("itemid-watermark-snapshot",outcome.scanConsistency());
        assertEquals(MetricLifecycle.ACTIVE,lifecycle("29999"));
        var run=wiring.syncRuns().find(tenant,outcome.syncRunId()).orElseThrow();
        assertEquals(SyncStatus.FAILED,run.status());
        assertEquals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.storedReason(),run.failureReason());
        assertEquals("itemid-watermark-snapshot",run.scanConsistency());
        assertTrue(wiring.metrics().findBinding(tenant,"zabbix-1","20003").isEmpty(),"the binding the shift skipped was never seen");
    }

    @Test void anUnboundedCompleteItemWalkCannotRetireOnRealPostgres(){
        seed("29999");
        var outcome=items(new UnboundedConnector(),1).execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE,outcome.kind());
        assertEquals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.name(),outcome.reasonCode());
        assertFalse(outcome.snapshotComplete());
        assertEquals(0,outcome.retired());
        assertEquals(MetricLifecycle.ACTIVE,lifecycle("29999"));
        var run=wiring.syncRuns().find(tenant,outcome.syncRunId()).orElseThrow();
        assertEquals(SyncStatus.FAILED,run.status());
        assertEquals(SyncScan.OFFSET_ATTEMPT,run.scanConsistency());
    }

    /** A connector that reports a complete walk it never bounded. */
    static final class UnboundedConnector implements Connector {
        @Override public String type(){return "zabbix";}
        @Override public ProbeResult probe(SourceContext source){return new ProbeResult(false,"test");}
        @Override public Page fetch(SourceContext source,String cursor,int limit){return new Page(List.of(),null,true);}
    }

    static Map<String,Object> item(String id){
        boolean mapped=!"20002".equals(id);
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("itemid",id);
        item.put("key_",mapped?"system.cpu.util[,user]":"system.cpu.util[,idle]");
        item.put("name",mapped?"CPU user time":"CPU idle time");
        item.put("value_type","0");item.put("units","%");item.put("hostid","10084");
        return item;
    }

    static final class ScriptedTransport implements ZabbixJsonRpcConnector.Transport {
        private final List<List<Map<String,Object>>> pages=new ArrayList<>();
        private String watermark;
        private long count;
        private int reads;

        @SafeVarargs
        final void script(String watermark,long count,List<Map<String,Object>>... pages){
            this.watermark=watermark;this.count=count;this.pages.clear();this.pages.addAll(List.of(pages));this.reads=0;
        }
        @Override public String exchange(URI endpoint,String jsonBody,String bearerToken){return jsonBody;}
        @Override public List<Map<String,Object>> readHostArray(String responseJson){
            if(responseJson.contains("countOutput"))throw new IllegalStateException("countOutput is not an item array");
            if(responseJson.contains("\"sortorder\":\"DESC\""))return watermark==null?List.of():List.of(item(watermark));
            if(reads>=pages.size())throw new IllegalStateException("No scripted page");
            return pages.get(reads++);
        }
        @Override public long readCount(String responseJson){return count;}
    }
}
