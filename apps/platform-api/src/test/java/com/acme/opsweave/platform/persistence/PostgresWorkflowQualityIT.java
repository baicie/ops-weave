package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual PG with isolated synthetic journal fixtures; quality performs no collection or output. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresWorkflowQualityIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("quality-pg-fixture-"+UUID.randomUUID());final String owner="quality-pg-author";
    final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","quality-fixture",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
    final Principal p=new Principal(new SubjectId(owner),tenant,Set.of(Permission.SOURCE_SYNC,Permission.LOG_READ,Permission.ENTITY_READ),ResourceScope.tenantWide());
    WorkflowDefinition.Source source(String kind){if(kind.equals("ZABBIX_HOST"))return new WorkflowDefinition.Source(kind,"fixture-source");var id=UUID.randomUUID();return new WorkflowDefinition.Source(kind,id.toString(),new WorkflowDefinition.ConfigurationPin(id,1,"sha256:"+"a".repeat(64)),null,WorkflowLogSourcePin.from(UUID.randomUUID(),new SourceMetricDiscovery.Item("1","2","log[/synthetic-fixture]","Synthetic Fixture","","LOG","NO_MAPPING",null)));}
    WorkflowDefinition definition(int revision,String kind){
        var d=new WorkflowDefinition("quality-fixture",revision,"Synthetic Fixture",source(kind),kind.equals("ZABBIX_HOST")?new WorkflowDefinition.Target("builtin.host",1,"sha256:"+"c".repeat(64)):new WorkflowDefinition.Target(null,1,null,"LOG"),List.of(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()),new WorkflowDefinition.Node("map",WorkflowDefinition.Type.MAP,"1",kind.equals("ZABBIX_HOST")?Map.of("name","name"):Map.of("timestamp","eventTime","body","body")),new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()),new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of())),List.of(new WorkflowDefinition.Edge("source","map"),new WorkflowDefinition.Edge("map","validate"),new WorkflowDefinition.Edge("validate","output")));
        var layout=new HashMap<String,WorkflowStore.Position>();for(var n:d.nodes())layout.put(n.id(),new WorkflowStore.Position(0,0));wiring.workflows().transaction(tenant,s->{s.publish(new WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,layout,Instant.now(),null),owner);return null;});return d;
    }
    WorkflowQualityService quality(WorkflowStore store){var guards=new WorkflowService.Sources(){public void require(Principal p,WorkflowDefinition.Source src,WorkflowStore.Session s,boolean available){assertFalse(available);}public void requireTarget(Principal p,WorkflowDefinition.Source src,WorkflowDefinition.Target target,WorkflowStore.Session s,boolean available){assertFalse(available);}};var f=new WorkflowService(store,(who,target)->{throw new AssertionError("No model execution");},(who,src,id)->{throw new AssertionError("No provider read");},guards,Clock.systemUTC());return new WorkflowQualityService(store,f,Clock.systemUTC());}
    WorkflowLogStream.Batch proof(WorkflowDefinition d,Instant at){var from=at.minusSeconds(80).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);return new WorkflowLogStream.Batch(UUID.randomUUID(),d.id(),d.revision(),d.digest(),from,from.plusSeconds(60),at,at,"IN_FLIGHT",2,0,0,List.of(0,1),List.of(from.toString(),from.plusNanos(1).toString()),"sha256:"+"a".repeat(64),"sha256:"+"b".repeat(64),null,null);}
    @Test void fixedVersionFilteringPrecedesLimitAndOriginalLookupSurvivesReopening(){
        var d=definition(1,"ZABBIX_LOG");var next=definition(2,"ZABBIX_LOG");var at=Instant.now().minusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);var original=proof(d,at);wiring.workflows().transaction(tenant,s->{s.addLogStreamBatch(owner,original);s.finishLogStreamBatch(owner,original.finish("UNKNOWN","OUTPUT_UNCONFIRMED",at));for(int i=0;i<21;i++){var b=proof(d,at.plusMillis(i+1));s.addLogStreamBatch(owner,b);s.finishLogStreamBatch(owner,b.finish("FAILED","OUTPUT_REJECTED",b.createdAt()));}for(int i=0;i<25;i++){var b=proof(next,at.plusMillis(100+i));s.addLogStreamBatch(owner,b);}return null;});
        var reopened=new PostgresWorkflowStore(source);var q=quality(reopened);var r=q.report(p,d.id(),1);assertTrue(r.truncated());assertEquals(20,r.batches().size());assertTrue(r.batches().stream().allMatch(b->b.state().equals("FAILED")));var old=q.batch(p,d.id(),1,original.id());assertEquals(2,old.counts().unknown());assertEquals(0,old.counts().confirmed());assertEquals(original.finish("UNKNOWN","OUTPUT_UNCONFIRMED",at),reopened.transaction(tenant,s->s.logStreamBatch(owner,original.id()).orElseThrow()));assertFalse(CatalogJson.JSON.writeValueAsString(r).contains("authority"));
        var other=new Principal(new SubjectId("other"),tenant,p.permissions(),p.resourceScope());assertTrue(q.report(other,d.id(),1).batches().isEmpty());assertEquals(WorkflowFailure.Code.NOT_FOUND,assertThrows(WorkflowFailure.class,()->q.batch(other,d.id(),1,original.id())).code());assertEquals(WorkflowFailure.Code.NOT_FOUND,assertThrows(WorkflowFailure.class,()->q.batch(p,d.id(),2,original.id())).code());
    }
    @Test void hostCountsRequireEveryEntityScopeAndNeverExposeRecoveryRows(){
        var d=definition(1,"ZABBIX_HOST");var entity=UUID.randomUUID().toString();var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var b=new WorkflowHostScan.Batch(UUID.randomUUID(),UUID.randomUUID(),d.id(),1,d.digest(),new WorkflowRuntime.Settings("entity_id","name"),1,null,null,true,at,List.of(Map.of("name","Synthetic Fixture private","ip","127.0.0.1","lifecycle","ACTIVE","entity_id",entity)),"READY",List.of(),null,at);
        wiring.workflows().transaction(tenant,s->{s.addHostBatch(owner,b);var inflight=b.state("IN_FLIGHT",List.of(),null,at);s.finishHostBatch(owner,inflight);s.finishHostBatch(owner,inflight.state("UNKNOWN",List.of(entity),"OUTPUT_UNAVAILABLE",at));return null;});var q=quality(new PostgresWorkflowStore(source));var r=q.report(p,d.id(),1);var c=r.batches().getFirst().counts();assertEquals(1,c.confirmed());assertNull(c.accepted());assertNull(c.outputExpected());assertNull(c.unknown());var text=CatalogJson.JSON.writeValueAsString(r);assertFalse(text.contains(entity));assertFalse(text.contains("Synthetic Fixture private"));assertFalse(text.contains("127.0.0.1"));
        var denied=new Principal(p.subjectId(),tenant,Set.of(Permission.SOURCE_SYNC),p.resourceScope());assertEquals(WorkflowFailure.Code.FORBIDDEN,assertThrows(WorkflowFailure.class,()->q.report(denied,d.id(),1)).code());
    }
    @Test void corruptPrivateMetadataCannotBecomeSuccessfulEmptyQuality()throws Exception{
        var d=definition(1,"ZABBIX_LOG");var b=proof(d,Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS));wiring.workflows().transaction(tenant,s->{s.addLogStreamBatch(owner,b);return null;});try(var connection=source.getConnection();var query=connection.prepareStatement("UPDATE integration.workflow_log_stream_batch SET body=jsonb_set(body,'{body}',to_jsonb(?::text)) WHERE tenant_id=? AND batch_id=?")){query.setString(1,"Synthetic Fixture private");query.setString(2,tenant.value());query.setObject(3,b.id());assertEquals(1,query.executeUpdate());}assertThrows(IllegalStateException.class,()->quality(new PostgresWorkflowStore(source)).report(p,d.id(),1));
    }
}
