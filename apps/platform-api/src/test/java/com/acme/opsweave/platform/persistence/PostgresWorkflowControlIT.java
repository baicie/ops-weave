package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowRuntimeControl.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PostgreSQL transactions with an isolated tenant; no collector or provider calls. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresWorkflowControlIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("workflow-control-pg-"+UUID.randomUUID());
    final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","control-fixture",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
    final Principal p=new Principal(new SubjectId("control-author"),tenant,Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ,Permission.ENTITY_MANAGE),ResourceScope.tenantWide());
    final com.acme.opsweave.catalog.domain.ModelDefinition model=new BuiltinCatalog().definitions().stream().filter(m->m.id().equals("builtin.service")).findFirst().orElseThrow();
    final WorkflowRuntime.Settings settings=new WorkflowRuntime.Settings("source_id","name");
    WorkflowDefinition definition(){return WorkflowOperators.builtIn().pin(new WorkflowDefinition("pg-control",1,"LOCALTEST command receipt",new Source("ZABBIX_HOST","control-fixture"),new Target(model.id(),1,model.digest()),List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("map",Type.MAP,"1",Map.of("name","name")),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of())),List.of(new Edge("source","map"),new Edge("map","validate"),new Edge("validate","output"))));}
    WorkflowRuntimeService runtime(){return new WorkflowRuntimeService(new PostgresWorkflowStore(source),(who,target)->model,(who,s,id)->new WorkflowService.Batch(List.of(Map.of("name","Fixture")),"fixture",1,0,false,"SUCCEEDED"),(who,s,after,id)->Optional.empty(),new WorkflowRuntimeService.Output(){public void validate(Principal who,WorkflowDefinition d,WorkflowRuntime.Settings s,List<Map<String,Object>> input,WorkflowEvaluation e){fail("Control must not write assets");}public String write(Principal who,WorkflowDefinition d,WorkflowRuntime.Settings s,UUID key,Instant time,Map<String,Object> input,Map<String,Object> output,String origin){throw new AssertionError("Control must not write assets");}},Clock.systemUTC());}
    Command prepare(){var d=definition();var store=new PostgresWorkflowStore(source);var layout=new LinkedHashMap<String,com.acme.opsweave.integration.api.WorkflowStore.Position>();for(int i=0;i<d.nodes().size();i++)layout.put(d.nodes().get(i).id(),new com.acme.opsweave.integration.api.WorkflowStore.Position(100,i*100));store.transaction(tenant,s->{s.publish(new com.acme.opsweave.integration.api.WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,layout,Instant.now(),null),p.subjectId().value());return null;});return new Command(UUID.randomUUID(),d.id(),1,d.digest(),settings,0,Operation.START);}
    @Test void concurrentDuplicatesHaveOneCommitAndNoExtraAuthorization()throws Exception {
        var command=prepare();var issues=new AtomicInteger();var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<Future<Receipt>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{ready.await();return runtime().command(p,command,()->{issues.incrementAndGet();return null;});}));
            ready.countDown();var first=futures.getFirst().get(20,TimeUnit.SECONDS);for(var future:futures)assertEquals(first,future.get(20,TimeUnit.SECONDS));
            assertEquals(1,issues.get());int count=wiring.workflows().transaction(tenant,s->s.runtimeControlCount(p.subjectId().value()));assertEquals(1,count);
            var stopped=runtime().command(p,new Command(UUID.randomUUID(),command.id(),1,command.digest(),settings,1,Operation.STOP),()->{throw new AssertionError("Stop issue");});assertEquals(2,stopped.task().generation());
            assertEquals(first,runtime().command(p,command,()->{throw new AssertionError("Replay issue");}));assertEquals("STOPPED",runtime().tasks(p).getFirst().state());
        }
    }
    @Test void changedBodyAndOtherOwnerOrTenantCannotUseAnOriginalKey() {
        var command=prepare();var original=runtime().command(p,command,()->null);
        var changed=new Command(command.requestId(),command.id(),1,command.digest(),settings,1,Operation.STOP);
        assertEquals(WorkflowFailure.Code.CONFLICT,assertThrows(WorkflowFailure.class,()->runtime().command(p,changed,()->null)).code());
        var other=new Principal(new SubjectId("other"),tenant,p.permissions(),p.resourceScope());assertEquals(WorkflowFailure.Code.NOT_FOUND,assertThrows(WorkflowFailure.class,()->runtime().controlReceipt(other,command.requestId())).code());
        var outside=new Principal(p.subjectId(),new TenantId(tenant.value()+"-other"),p.permissions(),p.resourceScope());assertEquals(WorkflowFailure.Code.NOT_FOUND,assertThrows(WorkflowFailure.class,()->runtime().controlReceipt(outside,command.requestId())).code());
        assertEquals(original,runtime().controlReceipt(p,command.requestId()));
    }
    @Test void receiptAndTaskRollBackTogetherAndNewStoreReopensTheOriginal() {
        var command=prepare();var time=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var task=new WorkflowRuntime.Task(command.id(),1,command.digest(),settings,1,"RUNNING",time,new UUID(-1,-1),time,null);
        var receipt=new Receipt(command.requestId(),Operation.START,command.commandDigest(),time,task);
        assertThrows(IllegalStateException.class,()->wiring.workflows().transaction(tenant,s->{s.saveTask(p.subjectId().value(),task);s.addRuntimeControl(p.subjectId().value(),receipt);throw new IllegalStateException("Explicit Fixture rollback");}));
        assertTrue(runtime().tasks(p).isEmpty());assertEquals(WorkflowFailure.Code.NOT_FOUND,assertThrows(WorkflowFailure.class,()->runtime().controlReceipt(p,command.requestId())).code());
        var applied=runtime().command(p,command,()->null);assertEquals(applied,runtime().controlReceipt(p,command.requestId()));
    }
    @Test void corruptRowIdentifierCannotBecomeAnAcknowledgement()throws Exception {
        var command=prepare();runtime().command(p,command,()->null);
        try(var connection=source.getConnection();var sql=connection.prepareStatement("UPDATE integration.workflow_control_command SET body=jsonb_set(body,'{requestId}',to_jsonb(?::text)) WHERE tenant_id=? AND request_id=?")){sql.setString(1,UUID.randomUUID().toString());sql.setString(2,tenant.value());sql.setObject(3,command.requestId());assertEquals(1,sql.executeUpdate());}
        assertThrows(IllegalStateException.class,()->runtime().controlReceipt(p,command.requestId()));assertEquals(1,runtime().tasks(p).getFirst().generation());
    }
}
