import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowRuntime.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.*;
import java.time.*;

public final class WorkflowRuntimeSmoke {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("Runtime check "+checks);}
 static void rejects(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException|WorkflowFailure e){return;}throw new AssertionError("Expected runtime rejection "+checks);}
 public static void main(String[] args){
  var basic=WorkflowSmoke.principal("runtime-smoke","author");var p=new Principal(basic.subjectId(),basic.tenantId(),Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ,Permission.ENTITY_MANAGE),ResourceScope.tenantWide());
  var store=new InMemoryWorkflowStore();var clock=new WorkflowSmoke.TestClock();var inventory=new InMemoryInventoryStore();var d=WorkflowSmoke.flow();var settings=new Settings("source_id","name");var input=List.<Map<String,Object>>of(Map.of("raw_name","LOCALTEST Runtime","source_id","stable-1"));
  WorkflowService.Samples noSource=(who,source,id)->{throw new AssertionError("Manual source read");};var edit=new WorkflowService(store,(who,t)->WorkflowSmoke.MODEL,noSource,clock);var draft=edit.save(p,d,WorkflowSmoke.layout(d),0);var preview=edit.evaluate(p,d.id(),1,1,d.digest(),false,input,null);edit.publish(p,d.id(),1,1,d.digest(),preview.receipt().id());
  var runtime=new WorkflowRuntimeService(store,(who,t)->WorkflowSmoke.MODEL,noSource,(who,source,after,afterId)->Optional.empty(),new WorkflowEntityOutput(inventory),clock);
  rejects(()->runtime.execute(p,d.id(),1,d.digest(),settings,preview.receipt().id(),input,null));
  var tested=edit.evaluate(p,d.id(),1,0,d.digest(),true,input,null);
  rejects(()->runtime.execute(basic,d.id(),1,d.digest(),settings,tested.receipt().id(),input,null));
  rejects(()->runtime.execute(p,d.id(),1,d.digest(),settings,tested.receipt().id(),List.of(Map.of("raw_name","changed","source_id","stable-1")),null));
  var result=runtime.execute(p,d.id(),1,d.digest(),settings,tested.receipt().id(),input,null);check(result.written()==1&&result.state().equals("SUCCEEDED"));check(runtime.execute(p,d.id(),1,d.digest(),settings,tested.receipt().id(),input,null).equals(result));check(runtime.executions(p).size()==1);check(runtime.execution(p,result.id()).equals(result));rejects(()->runtime.execution(new Principal(new SubjectId("other"),p.tenantId(),p.permissions(),p.resourceScope()),result.id()));
  rejects(()->runtime.execute(p,d.id(),1,d.digest(),settings,tested.receipt().id(),List.of(Map.of("raw_name","changed after write","source_id","stable-1")),null));
  var id=new com.acme.opsweave.sharedkernel.EntityId(UUID.fromString(result.entityIds().getFirst()));var entity=inventory.find(p.tenantId(),id).orElseThrow();check(entity.name().equals("LOCALTEST Runtime")&&entity.attributes().get("workflowId").equals(d.id()));
  rejects(()->runtime.execute(p,d.id(),1,d.digest(),new Settings("other","name"),tested.receipt().id(),input,null));
  check(runtime.executions(new Principal(new SubjectId("other"),p.tenantId(),p.permissions(),p.resourceScope())).isEmpty());
  var duplicates=List.<Map<String,Object>>of(input.getFirst(),input.getFirst());var dup=edit.evaluate(p,d.id(),1,0,d.digest(),true,duplicates,null);rejects(()->runtime.execute(p,d.id(),1,d.digest(),settings,dup.receipt().id(),duplicates,null));check(runtime.executions(p).size()==1);
  var absent=List.<Map<String,Object>>of(Map.of("raw_name","No identity"));var a=edit.evaluate(p,d.id(),1,0,d.digest(),true,absent,null);rejects(()->runtime.execute(p,d.id(),1,d.digest(),settings,a.receipt().id(),absent,null));
  var source=WorkflowSmoke.flow("runtime-remote",1,new WorkflowDefinition.Source("ZABBIX_HOST","zabbix-1"));var batchId=UUID.randomUUID();var batch=new WorkflowService.Batch(input,"fixture",1,0,false,"SUCCEEDED");WorkflowService.Samples sourceRead=(who,s,b)->batch;var remoteEdit=new WorkflowService(store,(who,t)->WorkflowSmoke.MODEL,sourceRead,clock);remoteEdit.save(p,source,WorkflowSmoke.layout(source),0);var r=remoteEdit.evaluate(p,source.id(),1,1,source.digest(),false,null,batchId);remoteEdit.publish(p,source.id(),1,1,source.digest(),r.receipt().id());
  var batchTime=clock.instant.plusSeconds(1);var remote=new WorkflowRuntimeService(store,(who,t)->WorkflowSmoke.MODEL,sourceRead,(who,s,after,afterId)->batchTime.isAfter(after)?Optional.of(new WorkflowRuntimeService.Batch(batchId,batchTime,batchTime,batch)):Optional.empty(),new WorkflowEntityOutput(inventory),clock);
  var task=remote.control(p,source.id(),1,source.digest(),settings,0,true);check(task.state().equals("RUNNING"));rejects(()->remote.control(p,source.id(),1,source.digest(),settings,0,false));rejects(()->remote.control(p,source.id(),1,source.digest(),settings,1,true));clock.instant=batchTime;remote.tick(p);check(remote.executions(p).size()==2);remote.tick(p);check(remote.executions(p).size()==2);check(remote.tasks(p).getFirst().cursorId().equals(batchId));
  var stopped=remote.control(basic,source.id(),1,source.digest(),settings,1,false);check(stopped.state().equals("STOPPED")&&stopped.generation()==2);remote.tick(p);check(remote.executions(p).size()==2);
  clock.instant=clock.instant.plusSeconds(3);remote.control(p,source.id(),1,source.digest(),settings,2,true);remote.tick(basic);check(remote.tasks(p).getFirst().state().equals("FAILED")&&remote.tasks(p).getFirst().error().equals("FORBIDDEN"));
  // A partial sink failure is recorded once and never silently retried.
  var partialInput=List.<Map<String,Object>>of(Map.of("raw_name","First","source_id","partial-1"),Map.of("raw_name","Second","source_id","partial-2"));
  var partialPreview=edit.evaluate(p,d.id(),1,0,d.digest(),true,partialInput,null);var sink=new WorkflowEntityOutput(inventory);var attempts=new java.util.concurrent.atomic.AtomicInteger();
  var partial=new WorkflowRuntimeService(store,(who,t)->WorkflowSmoke.MODEL,noSource,(who,s,after,afterId)->Optional.empty(),new WorkflowRuntimeService.Output(){
   public void validate(Principal who,WorkflowDefinition flow,Settings config,List<Map<String,Object>> values,WorkflowEvaluation evaluation){sink.validate(who,flow,config,values,evaluation);}
   public String write(Principal who,WorkflowDefinition flow,Settings config,UUID key,Instant at,Map<String,Object> values,Map<String,Object> output,String origin){if(attempts.incrementAndGet()==2)throw new IllegalStateException("Synthetic sink outage");return sink.write(who,flow,config,key,at,values,output,origin);}
  },clock);
  var failed=partial.execute(p,d.id(),1,d.digest(),settings,partialPreview.receipt().id(),partialInput,null);check(failed.state().equals("FAILED")&&failed.accepted()==2&&failed.written()==1&&failed.error().equals("OUTPUT_UNAVAILABLE"));check(partial.execute(p,d.id(),1,d.digest(),settings,partialPreview.receipt().id(),partialInput,null).equals(failed)&&attempts.get()==2);
  var first=sink.write(p,d,settings,UUID.randomUUID(),clock.instant,Map.of("source_id",1),Map.of("name","Numeric"),"MANUAL_SAMPLE");var second=sink.write(p,d,settings,UUID.randomUUID(),clock.instant,Map.of("source_id",new java.math.BigDecimal("1.0")),Map.of("name","Numeric"),"MANUAL_SAMPLE");check(first.equals(second));check(!first.equals(sink.write(p,d,new Settings("other_id","name"),UUID.randomUUID(),clock.instant,Map.of("other_id",1),Map.of("name","Numeric"),"MANUAL_SAMPLE")));
  var runs=new com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore();for(int i=0;i<60;i++){var old=runs.start(p.tenantId(),"cursor","host","labeled-fixture");runs.succeed(p.tenantId(),old.id(),"hostid-watermark-snapshot");}
  var cursor=Instant.now();try{Thread.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}var fresh=runs.start(p.tenantId(),"cursor","host","labeled-fixture");runs.succeed(p.tenantId(),fresh.id(),"hostid-watermark-snapshot");var complete=runs.find(p.tenantId(),fresh.id()).orElseThrow();check(runs.completedAfter(p.tenantId(),"cursor","host",cursor,new UUID(-1,-1),50).equals(List.of(complete)));check(runs.completedAfter(p.tenantId(),"cursor","host",complete.completedAt(),complete.id(),50).isEmpty());check(runs.completedAfter(new TenantId("other"),"cursor","host",Instant.EPOCH,new UUID(0,0),50).isEmpty());check(runs.completedAfter(p.tenantId(),"cursor","host",Instant.EPOCH,new UUID(0,0),50).size()==51);
  System.out.println("WorkflowRuntimeSmoke: "+checks+" checks passed");
 }
}
