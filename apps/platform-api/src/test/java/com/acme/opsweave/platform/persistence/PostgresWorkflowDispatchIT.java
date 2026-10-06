package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowLogStream.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.workflow.WorkflowDiagnosticsJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual PostgreSQL with explicitly synthetic source and output ports; no performance SLA. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresWorkflowDispatchIT extends OwnedInventoryTest {
 final TenantId tenant=new TenantId("dispatch-pg-fixture-"+UUID.randomUUID());final String owner="dispatch-author";
 final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
 final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","dispatch-fixture",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
 final Principal p=new Principal(new SubjectId(owner),tenant,Set.of(Permission.SOURCE_SYNC,Permission.LOG_READ,Permission.LOG_WRITE),ResourceScope.tenantWide());
 static final class Time extends Clock {
  final Instant anchor=Instant.now();final long nanos=System.nanoTime();Duration offset=Duration.ZERO;
  public Instant instant(){return anchor.plusNanos(System.nanoTime()-nanos).plus(offset);}
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
 }
 final Time time=new Time();
 WorkflowService flows(WorkflowStore store){var guards=new WorkflowService.Sources(){public void require(Principal p,WorkflowDefinition.Source src,WorkflowStore.Session s,boolean available){}public void requireTarget(Principal p,WorkflowDefinition.Source src,WorkflowDefinition.Target target,WorkflowStore.Session s,boolean available){}};return new WorkflowService(store,(p,target)->{throw new AssertionError("No model IO");},(p,src,id)->{throw new AssertionError("No sample IO");},guards,time);}
 WorkflowDefinition definition(String id){
  var sid=UUID.randomUUID();var item=new SourceMetricDiscovery.Item("1","2","log[/synthetic-fixture]","Synthetic Fixture","","LOG","NO_MAPPING",null);
  var src=new WorkflowDefinition.Source("ZABBIX_LOG",sid.toString(),new WorkflowDefinition.ConfigurationPin(sid,1,"sha256:"+"a".repeat(64)),null,WorkflowLogSourcePin.from(UUID.randomUUID(),item));
  var nodes=List.of(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()),new WorkflowDefinition.Node("map",WorkflowDefinition.Type.MAP,"1",Map.of("timestamp","eventTime","body","body")),new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()),new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of()));
  var d=WorkflowOperators.builtIn().pin(new WorkflowDefinition(id,1,"Synthetic Fixture",src,new WorkflowDefinition.Target(null,1,null,"LOG"),nodes,List.of(new WorkflowDefinition.Edge("source","map"),new WorkflowDefinition.Edge("map","validate"),new WorkflowDefinition.Edge("validate","output"))));var positions=new HashMap<String,WorkflowStore.Position>();for(var n:nodes)positions.put(n.id(),new WorkflowStore.Position(0,0));wiring.workflows().transaction(tenant,s->{s.publish(new WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,positions,time.instant(),null),owner);return null;});return d;
 }
 WorkflowLogStreamService runtime(WorkflowLogStreamService.Source input){var sink=new WorkflowLogWindowSink(){public boolean ready(){return true;}public void write(WorkflowLogWindow.Batch b){throw new AssertionError("No output for empty Fixture");}public List<WorkflowLogWindow.Record> read(WorkflowLogOutput.Scope scope){throw new AssertionError("No output read");}};return new WorkflowLogStreamService(wiring.workflows(),flows(wiring.workflows()),input,sink,new Semaphore(2),time);}
 void start(WorkflowLogStreamService runtime,WorkflowDefinition d){runtime.command(p,new Command(UUID.randomUUID(),d.id(),1,d.digest(),0,Operation.START),()->null);}
 WorkflowDiagnosticService diagnostics(WorkflowStore store){return new WorkflowDiagnosticService(store,flows(store),time);}

 @Test void actualWaitingIsPersistedAndCorruptionCannotInventZero()throws Exception{
  var first=definition("dispatch-first");var second=definition("dispatch-second");var calls=new int[]{0};var runtime=runtime((p,src,from,till)->{if(++calls[0]==1)try{Thread.sleep(40);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}return List.of();});start(runtime,first);start(runtime,second);time.offset=Duration.ofSeconds(20);runtime.tick(p);assertEquals(2,calls[0]);
  var reopened=new PostgresWorkflowStore(source);var a=diagnostics(reopened).report(p,first.id(),1).observations().getFirst();var b=diagnostics(reopened).report(p,second.id(),1).observations().getFirst();assertNotNull(a.dispatch());assertNotNull(b.dispatch());assertTrue(b.queueWaitMillis()>=30);assertEquals(b.dispatch().waitMillis(),b.queueWaitMillis());assertNotEquals(a.dispatch().id(),b.dispatch().id());assertTrue(b.dispatch().startedAt().isAfter(a.dispatch().startedAt()));assertEquals(0,b.sourceRead().received());assertEquals(0,b.result().received());assertEquals(b,diagnostics(reopened).observation(p,second.id(),1,b.id()));var typed=new WorkflowDiagnostics.Stored(b,List.of());assertEquals(typed,WorkflowDiagnosticsJson.decode(CatalogJson.JSON.writeValueAsString(typed)));var wire=CatalogJson.JSON.readTree(CatalogJson.JSON.writeValueAsString(WorkflowDiagnosticsJson.wire(b)));assertEquals(15,wire.size());assertEquals(b.queueWaitMillis().longValue(),wire.get("queueWaitMillis").asLong());assertEquals(3,wire.get("dispatch").size());
  assertTrue(reopened.transaction(new TenantId(tenant.value()+"-other"),s->s.diagnostic(owner,b.id())).isEmpty());var other=new Principal(new SubjectId("other"),tenant,p.permissions(),p.resourceScope());assertThrows(WorkflowFailure.class,()->diagnostics(reopened).observation(other,second.id(),1,b.id()));
  try(var c=source.getConnection();var q=c.prepareStatement("UPDATE integration.workflow_diagnostic SET body=jsonb_set(body,'{observation,queueWaitMillis}','0'::jsonb) WHERE tenant_id=? AND observation_id=?")){q.setString(1,tenant.value());q.setObject(2,b.id());assertEquals(1,q.executeUpdate());}assertThrows(IllegalStateException.class,()->diagnostics(reopened).report(p,second.id(),1));assertEquals(1,diagnostics(reopened).report(p,first.id(),1).observations().size());
 }
 @Test void stopWhileQueuedRejectsTheOldSnapshotBeforeSourceIO(){
  var first=definition("dispatch-first");var second=definition("dispatch-second");var calls=new int[]{0};var holder=new WorkflowLogStreamService[1];var runtime=runtime((p,src,from,till)->{if(++calls[0]==1)holder[0].command(p,new Command(UUID.randomUUID(),second.id(),1,second.digest(),1,Operation.STOP),()->null);return List.of();});holder[0]=runtime;start(runtime,first);start(runtime,second);time.offset=Duration.ofSeconds(20);runtime.tick(p);assertEquals(1,calls[0]);assertEquals("STOPPED",runtime.status(p,second.id()).task().state());var reopened=new PostgresWorkflowStore(source);assertTrue(diagnostics(reopened).report(p,second.id(),1).observations().isEmpty());assertEquals(1,diagnostics(reopened).report(p,first.id(),1).observations().size());assertTrue(runtime.status(p,second.id()).batches().isEmpty());
 }
}
