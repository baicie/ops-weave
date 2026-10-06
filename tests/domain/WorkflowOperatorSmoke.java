import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.application.WorkflowService;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.integration.api.WorkflowStore.*;
import java.util.*;

public final class WorkflowOperatorSmoke {
 static int checks;
 static void check(boolean result){checks++;if(!result)throw new AssertionError("Operator check "+checks);}
 static void fails(WorkflowFailure.Code code,Runnable work){checks++;try{work.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+code);}
 static WorkflowDefinition legacy(WorkflowDefinition d){return new WorkflowDefinition(d.id(),d.revision(),d.name(),d.source(),d.target(),d.nodes().stream().map(n->new Node(n.id(),n.type(),n.version(),n.config())).toList(),d.edges());}
 public static void main(String[] args){
  var registry=WorkflowOperators.builtIn();var d=WorkflowSmoke.flow(WorkflowSmoke.node("trim",Type.TRIM,Map.of()));var old=legacy(d);
  check(registry.descriptors().size()==11);check(registry.catalogVersion().equals("1.0.0"));
  check(registry.catalogDigest().startsWith("sha256:"));check(registry.pin(old).equals(d));check(!d.digest().equals(old.digest()));
  var input=List.<Map<String,Object>>of(Map.of("raw_name"," Fixture old ","raw_count","3"));
  var plan=registry.compile(d,WorkflowSmoke.MODEL);check(plan.parents("trim").equals(List.of("map")));check(plan.operators().get("trim").digest().equals(d.nodes().get(2).operatorDigest()));
  check(WorkflowEvaluation.evaluate(plan,input).equals(WorkflowEvaluation.evaluate(old,WorkflowSmoke.MODEL,input)));
  var ports=registry.descriptor(Type.MERGE,"1");check(ports.input().minimum()==1&&ports.input().maximum()==16);check(registry.descriptor(Type.OUTPUT,"1").output()==null);
  try{plan.parents("trim").add("source");throw new AssertionError();}catch(UnsupportedOperationException e){checks++;}
  try{plan.operators().clear();throw new AssertionError();}catch(UnsupportedOperationException e){checks++;}
  fails(WorkflowFailure.Code.OPERATOR_PIN_REQUIRED,()->registry.require(old,true));
  var nodes=new ArrayList<>(d.nodes());nodes.set(2,new Node("trim",Type.TRIM,"1",Map.of(),"sha256:"+"0".repeat(64)));
  var wrong=new WorkflowDefinition(d.id(),1,d.name(),d.source(),d.target(),nodes,d.edges());check(!wrong.digest().equals(d.digest()));
  fails(WorkflowFailure.Code.OPERATOR_CHANGED,()->registry.compile(wrong,WorkflowSmoke.MODEL));
  fails(WorkflowFailure.Code.OPERATOR_CHANGED,()->registry.descriptor(Type.TRIM,"2"));
  var store=new InMemoryWorkflowStore();var clock=new WorkflowSmoke.TestClock();var principal=WorkflowSmoke.principal("operator-fixture","author");
  var service=new WorkflowService(store,(p,t)->WorkflowSmoke.MODEL,(p,s,id)->{throw new AssertionError("Must reject before source I/O");},clock);
  fails(WorkflowFailure.Code.OPERATOR_CHANGED,()->service.save(principal,wrong,WorkflowSmoke.layout(wrong),0));check(service.drafts(principal).items().isEmpty());
  var saved=service.save(principal,old,WorkflowSmoke.layout(old),0);check(saved.definition().equals(old));
  var receipt=service.evaluate(principal,old.id(),1,1,old.digest(),false,input,null).receipt();
  fails(WorkflowFailure.Code.OPERATOR_PIN_REQUIRED,()->service.publish(principal,old.id(),1,1,old.digest(),receipt.id()));
  var upgraded=service.save(principal,d,WorkflowSmoke.layout(d),1);check(upgraded.preview()==null);
  fails(WorkflowFailure.Code.PREVIEW_REQUIRED,()->service.publish(principal,d.id(),1,2,d.digest(),receipt.id()));
  var fresh=service.evaluate(principal,d.id(),1,2,d.digest(),false,input,null);check(service.publish(principal,d.id(),1,2,d.digest(),fresh.receipt().id()).definition().equals(d));
  // A legacy immutable version stays readable and executable without being rewritten.
  var legacyPublished=new WorkflowDefinition("legacy-operator",1,old.name(),old.source(),old.target(),old.nodes(),old.edges());
  store.transaction(principal.tenantId(),s->{s.publish(new Entry(legacyPublished,legacyPublished.digest(),"PUBLISHED",0,WorkflowSmoke.layout(legacyPublished),clock.instant(),null),principal.subjectId().value());return null;});
  check(service.version(principal,legacyPublished.id(),1).definition().equals(legacyPublished));
  check(service.evaluate(principal,legacyPublished.id(),1,0,legacyPublished.digest(),true,input,null).evaluation().accepted()==1);
  // Persisted wrong pins are readable metadata, but cannot read upstream samples.
  var remote=new WorkflowDefinition("stale-operator",1,wrong.name(),new Source("ZABBIX_HOST","fixture-source"),wrong.target(),wrong.nodes(),wrong.edges());
  store.transaction(principal.tenantId(),s->{s.saveDraft(principal.subjectId().value(),new Entry(remote,remote.digest(),"DRAFT",1,WorkflowSmoke.layout(remote),clock.instant(),null));return null;});
  fails(WorkflowFailure.Code.OPERATOR_CHANGED,()->service.evaluate(principal,remote.id(),1,1,remote.digest(),false,null,UUID.randomUUID()));
  check(service.runs(principal).items().size()==3);
  var writer=new com.acme.opsweave.identity.domain.Principal(principal.subjectId(),principal.tenantId(),Set.of(com.acme.opsweave.identity.domain.Permission.SOURCE_SYNC,com.acme.opsweave.identity.domain.Permission.ENTITY_READ,com.acme.opsweave.identity.domain.Permission.ENTITY_MANAGE),principal.resourceScope());
  var settings=new WorkflowRuntime.Settings("source_id","name");
  store.transaction(principal.tenantId(),s->{s.publish(new Entry(remote,remote.digest(),"PUBLISHED",0,WorkflowSmoke.layout(remote),clock.instant(),null),principal.subjectId().value());s.saveTask(principal.subjectId().value(),new WorkflowRuntime.Task(remote.id(),1,remote.digest(),settings,1,"RUNNING",clock.instant(),UUID.randomUUID(),clock.instant(),null));return null;});
  var runtime=new com.acme.opsweave.integration.application.WorkflowRuntimeService(store,(p,t)->WorkflowSmoke.MODEL,(p,s,id)->{throw new AssertionError("No stale operator source read");},(p,s,after,id)->{throw new AssertionError("No stale operator batch read");},new com.acme.opsweave.integration.application.WorkflowRuntimeService.Output(){
   public void validate(com.acme.opsweave.identity.domain.Principal p,WorkflowDefinition d,WorkflowRuntime.Settings settings,List<Map<String,Object>> input,WorkflowEvaluation e){throw new AssertionError("No stale operator output");}
   public String write(com.acme.opsweave.identity.domain.Principal p,WorkflowDefinition d,WorkflowRuntime.Settings settings,UUID id,java.time.Instant time,Map<String,Object> input,Map<String,Object> values,String origin){throw new AssertionError("No stale operator write");}
  },clock);
  fails(WorkflowFailure.Code.OPERATOR_CHANGED,()->runtime.execute(writer,remote.id(),1,remote.digest(),settings,UUID.randomUUID(),null,UUID.randomUUID()));
  runtime.tick(writer);var task=runtime.tasks(writer).getFirst();check(task.state().equals("FAILED")&&task.error().equals("OPERATOR_CHANGED"));check(runtime.executions(writer).isEmpty());
  check(runtime.control(writer,remote.id(),1,remote.digest(),settings,task.generation(),false).state().equals("STOPPED"));
  System.out.println("Workflow operator smoke: "+checks+" checks passed");
 }
}
