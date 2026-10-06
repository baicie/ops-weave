import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowHostScan.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import java.time.*;
import java.util.*;

/** Explicit fixture: durable pages, original-body recovery, authorization and stale generations. */
public final class WorkflowHostRuntimeSmoke {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("Host runtime check "+checks);}
 static void rejects(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException|WorkflowFailure e){return;}throw new AssertionError("Expected rejection "+checks);}
 static List<Map<String,Object>> rows(int first,int count){var result=new ArrayList<Map<String,Object>>();for(int i=first;i<first+count;i++)result.add(Map.of("name","Fixture host "+i,"ip","127.0.0."+i,"lifecycle","ACTIVE","entity_id",UUID.nameUUIDFromBytes(("host-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()));return result;}
 static final String CURSOR="ids-v1|"+"a".repeat(64)+"|7|5";
 static final class Fixture {
  final InMemoryWorkflowStore store=new InMemoryWorkflowStore();final WorkflowSmoke.TestClock clock=new WorkflowSmoke.TestClock();final InMemoryInventoryStore inventory=new InMemoryInventoryStore();
  final Principal p=new Principal(new SubjectId("host-fixture-owner"),new com.acme.opsweave.sharedkernel.TenantId("host-fixture-tenant"),Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ,Permission.ENTITY_MANAGE),ResourceScope.tenantWide());
  final WorkflowRuntime.Settings settings=new WorkflowRuntime.Settings("entity_id","name");final WorkflowDefinition d;
  int reads,writes;int lostAckAt=2;boolean outage,revoke,empty;Runnable onRead;List<Map<String,Object>> nextRows=rows(1,5);UUID firstBatch;
  final WorkflowRuntimeService runtime;
  Fixture(){this(java.util.function.UnaryOperator.identity());}
  Fixture(java.util.function.UnaryOperator<List<WorkflowDefinition.Node>> configure){
   var source=new WorkflowDefinition.Source("ZABBIX_HOST","host-fixture",new WorkflowDefinition.ConfigurationPin(UUID.randomUUID(),1,"sha256:"+"b".repeat(64)));
   var nodes=configure.apply(List.of(WorkflowSmoke.node("source",WorkflowDefinition.Type.SOURCE,Map.of()),WorkflowSmoke.node("map",WorkflowDefinition.Type.MAP,Map.of("name","name")),WorkflowSmoke.node("validate",WorkflowDefinition.Type.VALIDATE,Map.of()),WorkflowSmoke.node("output",WorkflowDefinition.Type.OUTPUT,Map.of())));
   d=new WorkflowDefinition("fixture-host-scan",1,"Fixture host scan",source,new WorkflowDefinition.Target(WorkflowSmoke.MODEL.id(),1,WorkflowSmoke.MODEL.digest()),nodes,java.util.stream.IntStream.range(1,nodes.size()).mapToObj(i->new WorkflowDefinition.Edge(nodes.get(i-1).id(),nodes.get(i).id())).toList());
   store.transaction(p.tenantId(),s->{s.publish(new WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,WorkflowSmoke.layout(d),clock.instant,null),p.subjectId().value());return null;});
   var sink=new WorkflowEntityOutput(inventory);
   runtime=new WorkflowRuntimeService(store,(who,t)->WorkflowSmoke.MODEL,(who,s,id)->{throw new AssertionError("No preview input reread");},(who,s,at,id)->{throw new AssertionError("No legacy batch");},new WorkflowRuntimeService.Output(){
    public void validate(Principal who,WorkflowDefinition flow,WorkflowRuntime.Settings config,List<Map<String,Object>> input,WorkflowEvaluation eval){sink.validate(who,flow,config,input,eval);}
    public String write(Principal who,WorkflowDefinition flow,WorkflowRuntime.Settings config,UUID id,Instant time,Map<String,Object> input,Map<String,Object> values,String origin){writes++;var result=sink.write(who,flow,config,id,time,input,values,origin);if(firstBatch==null)firstBatch=id;if(outage&&writes==lostAckAt)throw new IllegalStateException("Fixture response loss after durable write");return result;}
   },clock,new WorkflowHostRuntimeService.Sources(){
    public void require(WorkflowStore.Session s,Principal who,WorkflowDefinition.Source source){if(revoke)throw new WorkflowFailure(WorkflowFailure.Code.AUTHORIZATION_REVOKED);}
    public Page read(Principal who,WorkflowDefinition.Source source,String cursor){reads++;clock.instant=clock.instant.plusSeconds(1);if(onRead!=null)onRead.run();return empty?new Page(List.of(),null,true,clock.instant):cursor==null?new Page(nextRows,CURSOR,false,clock.instant):new Page(rows(6,2),null,true,clock.instant);}
   });
  }
  WorkflowRuntimeControl.Receipt command(WorkflowRuntimeControl.Operation op,long expected){return runtime.command(p,new WorkflowRuntimeControl.Command(UUID.randomUUID(),d.id(),1,d.digest(),settings,expected,op),()->null);}
  Checkpoint checkpoint(){return runtime.hostCheckpoint(p,d.id()).orElseThrow();}
  WorkflowRuntime.Task task(){return runtime.tasks(p).getFirst();}
 }
 public static void main(String[] args){
  var f=new Fixture();var start=f.command(WorkflowRuntimeControl.Operation.START,0);check(start.task().generation()==1);f.runtime.tick(f.p);check(f.reads==1&&f.writes==5);check(f.checkpoint().confirmedRecords()==5&&!f.checkpoint().complete()&&f.task().state().equals("RUNNING"));
  var stop=f.command(WorkflowRuntimeControl.Operation.STOP,1);check(stop.task().generation()==2);f.runtime.tick(f.p);check(f.reads==1&&f.writes==5);rejects(()->f.command(WorkflowRuntimeControl.Operation.START,2));
  f.command(WorkflowRuntimeControl.Operation.RESUME,2);f.runtime.tick(f.p);check(f.reads==2&&f.writes==7&&f.checkpoint().complete());check(f.checkpoint().confirmedBatches()==2&&f.task().state().equals("STOPPED"));f.runtime.tick(f.p);check(f.writes==7);rejects(()->f.command(WorkflowRuntimeControl.Operation.RESUME,3));
  var nextVersion=new WorkflowDefinition(f.d.id(),2,f.d.name(),f.d.source(),f.d.target(),f.d.nodes(),f.d.edges());f.store.transaction(f.p.tenantId(),s0->{s0.publish(new WorkflowStore.Entry(nextVersion,nextVersion.digest(),"PUBLISHED",0,WorkflowSmoke.layout(nextVersion),f.clock.instant,null),f.p.subjectId().value());return null;});f.runtime.command(f.p,new WorkflowRuntimeControl.Command(UUID.randomUUID(),nextVersion.id(),2,nextVersion.digest(),f.settings,3,WorkflowRuntimeControl.Operation.START),()->null);f.runtime.tick(f.p);check(f.checkpoint().revision()==2&&f.checkpoint().confirmedRecords()==5&&f.task().generation()==4);
  var u=new Fixture();u.outage=true;u.command(WorkflowRuntimeControl.Operation.START,0);u.runtime.tick(u.p);check(u.task().state().equals("FAILED")&&u.checkpoint().confirmedRecords()==0&&u.checkpoint().nextCursor()==null);var original=u.runtime.hostBatches(u.p,u.d.id()).getFirst();check(original.state().equals("UNKNOWN")&&original.entityIds().size()==1);check(u.writes==2&&u.reads==1);u.runtime.tick(u.p);check(u.writes==2);u.nextRows=rows(20,5);u.outage=false;
  var command=new WorkflowRuntimeControl.Command(UUID.randomUUID(),u.d.id(),1,u.d.digest(),u.settings,1,WorkflowRuntimeControl.Operation.RESUME);var receipt=u.runtime.command(u.p,command,()->null);check(u.runtime.command(u.p,command,()->{throw new AssertionError("No authority reissue on replay");}).equals(receipt));u.runtime.tick(u.p);var recovered=u.runtime.hostBatches(u.p,u.d.id()).getFirst();check(recovered.id().equals(original.id())&&recovered.records().equals(original.records())&&recovered.observedAt().equals(original.observedAt())&&recovered.state().equals("CONFIRMED"));check(u.reads==1&&u.checkpoint().confirmedRecords()==5&&u.writes==7);check(u.inventory.observations().values().stream().filter(o->o.entityId().value().toString().equals(recovered.entityIds().getFirst())).count()==1);
  rejects(()->original.requireSuccessor(new Batch(original.id(),original.scanId(),original.workflowId(),1,original.digest(),original.settings(),1,null,CURSOR,false,original.observedAt(),rows(20,5),"READY",original.entityIds(),null,original.updatedAt())));
  var revoked=new Fixture();revoked.command(WorkflowRuntimeControl.Operation.START,0);revoked.revoke=true;revoked.runtime.tick(revoked.p);check(revoked.task().error().equals("AUTHORIZATION_REVOKED")&&revoked.reads==0&&revoked.writes==0);rejects(()->revoked.command(WorkflowRuntimeControl.Operation.RESUME,1));
  var zero=new Fixture();zero.empty=true;zero.command(WorkflowRuntimeControl.Operation.START,0);zero.runtime.tick(zero.p);check(zero.checkpoint().complete()&&zero.checkpoint().confirmedRecords()==0&&zero.writes==0&&zero.task().state().equals("STOPPED"));
  rejects(()->new Page(List.of(),CURSOR,false,Instant.now()));rejects(()->new Page(rows(1,5),null,false,Instant.now()));rejects(()->new Page(rows(1,6),null,true,Instant.now()));
  var t=new Fixture();t.command(WorkflowRuntimeControl.Operation.START,0);var c=t.checkpoint();var now=t.clock.instant;var pending=new Batch(UUID.randomUUID(),c.scanId(),t.d.id(),1,t.d.digest(),t.settings,1,null,CURSOR,false,now,rows(1,5),"READY",List.of(),null,now);
  t.store.transaction(t.p.tenantId(),s->{s.addHostBatch(t.p.subjectId().value(),pending);s.finishHostBatch(t.p.subjectId().value(),pending.state("IN_FLIGHT",List.of(),null,now));s.saveHostCheckpoint(t.p.subjectId().value(),c.pending(pending.id(),now));return null;});t.runtime.tick(t.p);check(t.reads==0&&t.writes==0&&t.task().state().equals("RUNNING"));t.clock.instant=now.plusSeconds(61);t.runtime.tick(t.p);check(t.task().state().equals("FAILED")&&t.runtime.hostBatches(t.p,t.d.id()).getFirst().state().equals("UNKNOWN")&&t.writes==0);
  var other=new Principal(new SubjectId("other"),u.p.tenantId(),u.p.permissions(),ResourceScope.tenantWide());check(u.runtime.hostCheckpoint(other,u.d.id()).isEmpty()&&u.runtime.hostBatches(other,u.d.id()).isEmpty());
  var raced=new Fixture();raced.command(WorkflowRuntimeControl.Operation.START,0);raced.onRead=()->raced.command(WorkflowRuntimeControl.Operation.STOP,1);raced.runtime.tick(raced.p);check(raced.reads==1&&raced.writes==0&&raced.task().state().equals("STOPPED")&&raced.task().generation()==2&&raced.checkpoint().pendingBatchId()==null);
  var limited=new Fixture();var grant=new WorkflowTaskAuthority(UUID.randomUUID(),"https://issuer.example.invalid","fixture","sha256:"+"a".repeat(64),limited.clock.instant,limited.clock.instant.plusSeconds(60),1,0);limited.runtime.command(limited.p,new WorkflowRuntimeControl.Command(UUID.randomUUID(),limited.d.id(),1,limited.d.digest(),limited.settings,0,WorkflowRuntimeControl.Operation.START),()->grant);limited.runtime.tick(limited.p);check(limited.reads==0&&limited.writes==0);limited.runtime.tickAuthorized(limited.p.tenantId(),limited.p.subjectId().value(),t0->limited.p);check(limited.checkpoint().confirmedRecords()==5&&limited.task().authority().consumedBatches()==1);limited.runtime.tickAuthorized(limited.p.tenantId(),limited.p.subjectId().value(),t0->limited.p);check(limited.task().error().equals("EXECUTION_LIMIT")&&limited.reads==1&&limited.checkpoint().confirmedRecords()==5);
  var renewed=new WorkflowTaskAuthority(UUID.randomUUID(),grant.issuer(),grant.externalSubject(),grant.grantDigest(),limited.clock.instant,limited.clock.instant.plusSeconds(60),2,0);limited.runtime.command(limited.p,new WorkflowRuntimeControl.Command(UUID.randomUUID(),limited.d.id(),1,limited.d.digest(),limited.settings,1,WorkflowRuntimeControl.Operation.RESUME),()->renewed);limited.runtime.tickAuthorized(limited.p.tenantId(),limited.p.subjectId().value(),t0->limited.p);check(limited.checkpoint().complete()&&limited.checkpoint().confirmedRecords()==7&&limited.reads==2);
  var scoped=new Principal(u.p.subjectId(),u.p.tenantId(),u.p.permissions(),ResourceScope.of(Set.of(new ResourceRef(u.p.tenantId(),"workflow","*"),new ResourceRef(u.p.tenantId(),"catalog","*"))));rejects(()->u.runtime.hostBatches(scoped,u.d.id()));
  System.out.println("WorkflowHostRuntimeSmoke: "+checks+" checks passed");
 }
}
