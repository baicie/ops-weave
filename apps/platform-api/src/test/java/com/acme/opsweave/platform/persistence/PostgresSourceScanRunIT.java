package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.application.SourceScanRunQueryService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceScanRunIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("scan-run-pg-"+UUID.randomUUID());
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);
    final String source="zabbix-1";
    final Principal reader=new Principal(new SubjectId("operator"),tenant,Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());
    final SourceScanRunQueryService trace=new SourceScanRunQueryService(new AuthorizeUseCase(),wiring.syncRuns(),wiring.pipelines(),source);

    IngestZabbixHostsUseCase.SyncOutcome sync(Connector connector){
        return new IngestZabbixHostsUseCase(new AuthorizeUseCase(),connector,wiring.writer(),wiring.rawRecords(),wiring.syncRuns(),wiring.pipelines(),"labeled-fixture",wiring.label(),source,"env:OPSWEAVE_ZABBIX_TOKEN",100).execute(reader,null);
    }
    long activeHosts()throws Exception{
        try(var db=DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
            var s=db.prepareStatement("SELECT count(*) FROM inventory.entity WHERE tenant_id=? AND lifecycle='ACTIVE'")){
            s.setString(1,tenant.value());try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }

    @Test void storedScansPageNewestFirstAndStayInScope(){
        var oldest=wiring.syncRuns().start(tenant,source,"host","labeled-fixture");
        var newest=wiring.syncRuns().start(tenant,source,"host","labeled-fixture");
        wiring.syncRuns().checkpoint(tenant,oldest.id(),"100",1,5,5,0);wiring.syncRuns().succeed(tenant,oldest.id(),SyncScan.OFFSET_ATTEMPT);
        wiring.syncRuns().fail(tenant,newest.id(),SyncFailureCode.SOURCE_FETCH_FAILED.storedReason(),SyncScan.HOSTID_WATERMARK);
        wiring.syncRuns().start(new TenantId("scan-run-pg-other-"+UUID.randomUUID()),source,"host","labeled-fixture");
        wiring.syncRuns().start(tenant,"zabbix-2","host","labeled-fixture");
        wiring.syncRuns().start(tenant,source,"item","labeled-fixture");

        var page=trace.recent(reader,"host",null,1);
        assertEquals(1,page.items().size());assertTrue(page.hasMore());assertNotNull(page.nextCursor());
        var rest=trace.recent(reader,"host",page.nextCursor(),50);
        assertEquals(1,rest.items().size());assertFalse(rest.hasMore());assertNull(rest.nextCursor());
        assertEquals(Set.of(oldest.id(),newest.id()),new HashSet<>(List.of(page.items().getFirst().run().id(),rest.items().getFirst().run().id())));
        var all=trace.recent(reader,"host",null,50);
        assertEquals(2,all.items().size());assertFalse(all.items().get(0).run().startedAt().isBefore(all.items().get(1).run().startedAt()));
        assertEquals("labeled-fixture",all.items().get(0).run().dataMode());
        assertEquals(1,trace.recent(reader,"item",null,50).items().size());
        assertEquals(SyncStatus.SUCCEEDED,trace.find(reader,"host",oldest.id()).run().status());
        assertTrue(trace.find(reader,"host",oldest.id()).run().snapshotComplete());
        assertEquals(5,trace.find(reader,"host",oldest.id()).run().accepted());
        assertEquals(SyncFailureCode.SOURCE_FETCH_FAILED,SyncFailureCode.fromStoredReason(trace.find(reader,"host",newest.id()).run().failureReason()).orElseThrow());
        assertEquals(SyncScan.HOSTID_WATERMARK,trace.find(reader,"host",newest.id()).run().scanConsistency());
        assertEquals(SyncScan.OFFSET_ATTEMPT,trace.find(reader,"host",oldest.id()).run().scanConsistency());
        assertEquals(SourceScanRunException.Code.NOT_FOUND,assertThrows(SourceScanRunException.class,()->trace.find(reader,"host",UUID.randomUUID())).code());
        assertEquals(SourceScanRunException.Code.NOT_FOUND,assertThrows(SourceScanRunException.class,()->trace.find(new Principal(new SubjectId("operator"),new TenantId("scan-run-pg-other-"+UUID.randomUUID()),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide()),"host",newest.id())).code());
        assertEquals(SourceScanRunException.Code.INVALID_REQUEST,assertThrows(SourceScanRunException.class,()->trace.recent(reader,"host",null,SourceScanRunQueryService.MAX_LIMIT+1)).code());
    }

    @Test void failedHostSyncStaysTraceableAndNeverRetiresStoredHosts()throws Exception{
        var first=sync(new FixtureZabbixHostConnector());
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,first.kind());
        var completed=trace.find(reader,"host",first.syncRunId());
        assertEquals(SyncStatus.SUCCEEDED,completed.run().status());assertTrue(completed.run().snapshotComplete());
        assertEquals(2,completed.run().accepted());assertNotNull(completed.pipelineVersion());
        assertEquals(2L,activeHosts());

        var second=sync(new UnreachableConnector());
        assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.UNAVAILABLE,second.kind());
        assertEquals("SOURCE_FETCH_FAILED",second.reasonCode());
        var failed=trace.find(reader,"host",second.syncRunId());
        assertEquals(SyncStatus.FAILED,failed.run().status());assertFalse(failed.run().snapshotComplete());
        assertEquals(0,failed.run().pages());assertEquals(0,failed.run().fetched());
        assertEquals(SyncFailureCode.SOURCE_FETCH_FAILED.storedReason(),failed.run().failureReason());
        assertNotNull(failed.pipelineVersion());
        assertEquals(2L,activeHosts());
    }

    private static final class UnreachableConnector implements Connector{
        public String type(){return "zabbix";}
        public ProbeResult probe(SourceContext source){return new ProbeResult(false,"test");}
        public Page fetch(SourceContext source,String cursor,int limit){throw new IllegalStateException("source unreachable");}
    }
}
