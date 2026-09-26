package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresHostScanBoundaryIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("host-boundary-pg-"+UUID.randomUUID());
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);
    final Principal principal=new Principal(new SubjectId("user-demo"),tenant,Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());
    final Instant now=Instant.parse("2026-09-21T12:00:00Z");

    EntityId seed(String hostId){
        var mapped=new ZabbixHostMapper().map(PipelineDefinition.zabbixHostV1(),tenant,"zabbix-1",
            Map.of("hostid",hostId,"host","host-"+hostId,"name","host-"+hostId,"status","0",
                "interfaces",List.of(Map.of("ip","10.0.0.1","main","1","type","1"))),now,now,"raw-"+UUID.randomUUID());
        wiring.writer().upsert(mapped.entity(),mapped.observation(),mapped.link());
        return mapped.entity().id();
    }
    IngestZabbixHostsUseCase ingest(ScriptedTransport transport,int pageSize){
        var connector=new ZabbixJsonRpcConnector(URI.create("http://127.0.0.1/api_jsonrpc.php"),transport,secretRef->"stub-token");
        return ingest(connector,pageSize);
    }
    IngestZabbixHostsUseCase ingest(Connector connector,int pageSize){
        return new IngestZabbixHostsUseCase(new AuthorizeUseCase(),connector,wiring.writer(),wiring.rawRecords(),wiring.syncRuns(),wiring.pipelines(),"labeled-fixture",wiring.label(),"zabbix-1","env:OPSWEAVE_ZABBIX_TOKEN",pageSize);
    }
    String lifecycle(EntityId id){return wiring.query().find(tenant,id).orElseThrow().lifecycle();}

    @Test void aVerifiedBoundedWalkRetiresAbsentHostsOnRealPostgres(){
        var absent=seed("999");
        var transport=new ScriptedTransport();
        transport.script("10085",2,List.of(host("10084"),host("10085")));
        var outcome=ingest(transport,2).execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,outcome.kind());
        assertTrue(outcome.snapshotComplete());
        assertEquals("hostid-watermark-snapshot",outcome.scanConsistency());
        assertEquals(2,outcome.accepted());
        assertEquals(1,outcome.retired());
        assertEquals("INACTIVE",lifecycle(absent));
        assertEquals("ACTIVE",lifecycle(entity("10084")));
        assertEquals("hostid-watermark-snapshot",wiring.syncRuns().find(tenant,outcome.syncRunId()).orElseThrow().scanConsistency());
    }

    @Test void aShiftedWalkIsRecordedAsUnverifiedAndRetiresNothing()throws Exception{
        var absent=seed("999");
        var transport=new ScriptedTransport();
        transport.script("10087",4,List.of(host("10084"),host("10085")),List.of(host("10087")));
        var outcome=ingest(transport,2).execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE,outcome.kind());
        assertEquals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.name(),outcome.reasonCode());
        assertFalse(outcome.snapshotComplete());
        assertEquals("ACTIVE",lifecycle(absent));
        var run=wiring.syncRuns().find(tenant,outcome.syncRunId()).orElseThrow();
        assertEquals(SyncStatus.FAILED,run.status());
        assertEquals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.storedReason(),run.failureReason());
        assertEquals("hostid-watermark-snapshot",run.scanConsistency());
        assertEquals("ACTIVE",lifecycle(entity("10084")));
        assertEquals("ACTIVE",lifecycle(entity("10087")));
        assertTrue(wiring.query().find(tenant,entity("10086")).isEmpty(),"the row the shift skipped was never seen");
    }

    @Test void anUnboundedCompleteWalkCannotRetireOnRealPostgres(){
        var absent=seed("999");
        var outcome=ingest(new UnboundedConnector(),1).execute(principal,null);
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE,outcome.kind());
        assertEquals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.name(),outcome.reasonCode());
        assertFalse(outcome.snapshotComplete());
        assertEquals(0,outcome.retired());
        assertEquals("ACTIVE",lifecycle(absent));
        var run=wiring.syncRuns().find(tenant,outcome.syncRunId()).orElseThrow();
        assertEquals(SyncStatus.FAILED,run.status());
        assertEquals(SyncFailureCode.SOURCE_SCAN_UNVERIFIED.storedReason(),run.failureReason());
        assertEquals(SyncScan.OFFSET_ATTEMPT,run.scanConsistency());
    }

    EntityId entity(String hostId){return EntityIds.fromExternal(new ExternalObjectKey(tenant,"zabbix-1","host",hostId,"1"));}

    /** A connector that reports a complete walk it never bounded. */
    static final class UnboundedConnector implements Connector {
        @Override public String type(){return "zabbix";}
        @Override public ProbeResult probe(SourceContext source){return new ProbeResult(false,"test");}
        @Override public Page fetch(SourceContext source,String cursor,int limit){return new Page(List.of(),null,true);}
    }

    static Map<String,Object> host(String id){
        Map<String,Object> host=new LinkedHashMap<>();
        host.put("hostid",id);host.put("host","host-"+id);host.put("name","host-"+id);host.put("status","0");
        host.put("interfaces",List.of(Map.of("ip","10.0.0.1","main","1","type","1")));
        return host;
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
            if(responseJson.contains("countOutput"))throw new IllegalStateException("countOutput is not a host array");
            if(responseJson.contains("\"sortorder\":\"DESC\""))return watermark==null?List.of():List.of(host(watermark));
            if(reads>=pages.size())throw new IllegalStateException("No scripted page");
            return pages.get(reads++);
        }
        @Override public long readCount(String responseJson){return count;}
    }
}
