import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowRuntimeControl.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkflowRuntimeControlSmoke {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Control check "+checks);}
    static void failure(WorkflowFailure.Code code,Runnable action){checks++;try{action.run();}catch(WorkflowFailure expected){if(expected.code()==code)return;throw expected;}throw new AssertionError("Expected "+code);}
    public static void main(String[] args){
        var clock=new WorkflowSmoke.TestClock();var store=new InMemoryWorkflowStore();
        var p=new Principal(new SubjectId("control-fixture"),new TenantId("control-fixture"),Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ,Permission.ENTITY_MANAGE),ResourceScope.tenantWide());
        WorkflowService.Samples samples=(who,source,id)->new WorkflowService.Batch(List.of(Map.of("raw_name","Fixture")),"fixture",1,0,false,"SUCCEEDED");
        var edit=new WorkflowService(store,(who,target)->WorkflowSmoke.MODEL,samples,clock);
        var reads=new AtomicInteger();var issues=new AtomicInteger();
        var runtime=new WorkflowRuntimeService(store,(who,target)->WorkflowSmoke.MODEL,samples,(who,source,after,id)->{reads.incrementAndGet();return Optional.empty();},new WorkflowRuntimeService.Output(){
            public void validate(Principal who,WorkflowDefinition d,WorkflowRuntime.Settings settings,List<Map<String,Object>> input,WorkflowEvaluation evaluation){throw new AssertionError("Control must not write");}
            public String write(Principal who,WorkflowDefinition d,WorkflowRuntime.Settings settings,UUID execution,java.time.Instant time,Map<String,Object> input,Map<String,Object> output,String origin){throw new AssertionError("Control must not write");}
        },clock);
        var settings=new WorkflowRuntime.Settings("source_id","name");var definitions=new ArrayList<WorkflowDefinition>();
        for(int i=0;i<20;i++){
            var d=WorkflowSmoke.flow("control-"+i,1,new WorkflowDefinition.Source("ZABBIX_HOST","fixture-host"));definitions.add(d);
            edit.save(p,d,WorkflowSmoke.layout(d),0);var preview=edit.evaluate(p,d.id(),1,1,d.digest(),false,null,UUID.randomUUID());edit.publish(p,d.id(),1,1,d.digest(),preview.receipt().id());
        }
        var d=definitions.getFirst();var start=new Command(UUID.randomUUID(),d.id(),1,d.digest(),settings,0,Operation.START);
        var receipt=runtime.command(p,start,()->{issues.incrementAndGet();return new WorkflowTaskAuthority(UUID.randomUUID(),"https://issuer.example.invalid","fixture-subject","sha256:"+"a".repeat(64),clock.instant(),clock.instant().plusSeconds(900),20,0);});
        check(receipt.task().generation()==1);check(issues.get()==1);check(reads.get()==0);check(receipt.equals(runtime.command(p,start,()->{throw new AssertionError("Replay reissues authority");})));check(receipt.equals(runtime.controlReceipt(p,start.requestId())));
        var changed=new Command(start.requestId(),d.id(),1,d.digest(),new WorkflowRuntime.Settings("other_id","name"),0,Operation.START);failure(WorkflowFailure.Code.CONFLICT,()->runtime.command(p,changed,()->null));
        failure(WorkflowFailure.Code.CONFLICT,()->runtime.command(p,new Command(start.requestId(),d.id(),1,d.digest(),settings,1,Operation.STOP),()->null));
        var readOnly=new Principal(p.subjectId(),p.tenantId(),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());check(receipt.equals(runtime.command(readOnly,start,()->{throw new AssertionError("Replay must not mutate");})));
        var other=new Principal(new SubjectId("other"),p.tenantId(),p.permissions(),p.resourceScope());failure(WorkflowFailure.Code.NOT_FOUND,()->runtime.controlReceipt(other,start.requestId()));
        var otherTenant=new Principal(p.subjectId(),new TenantId("other-control"),p.permissions(),p.resourceScope());failure(WorkflowFailure.Code.NOT_FOUND,()->runtime.controlReceipt(otherTenant,start.requestId()));
        for(var definition:definitions.subList(1,20))runtime.command(p,new Command(UUID.randomUUID(),definition.id(),1,definition.digest(),settings,0,Operation.START),()->null);
        store.transaction(p.tenantId(),s->{for(int i=20;i<180;i++)s.addRuntimeControl(p.subjectId().value(),new Receipt(UUID.randomUUID(),Operation.START,start.commandDigest(),receipt.createdAt(),receipt.task()));return null;});
        failure(WorkflowFailure.Code.CAPACITY,()->runtime.command(p,new Command(UUID.randomUUID(),d.id(),1,d.digest(),settings,1,Operation.START),()->{throw new AssertionError("Budget before issue");}));
        check(store.transaction(p.tenantId(),s->s.runtimeControlCount(p.subjectId().value()))==180);
        for(var definition:definitions){var stopped=runtime.command(p,new Command(UUID.randomUUID(),definition.id(),1,definition.digest(),settings,1,Operation.STOP),()->{throw new AssertionError("Stop reissues authority");});check(stopped.task().state().equals("STOPPED"));}
        check(store.transaction(p.tenantId(),s->s.runtimeControlCount(p.subjectId().value()))==200);
        check(receipt.equals(runtime.command(p,start,()->{throw new AssertionError("Full budget replay");})));check(runtime.tasks(p).stream().allMatch(t->t.generation()==2&&t.state().equals("STOPPED")));
        check(reads.get()==0);check(issues.get()==1);
        failure(WorkflowFailure.Code.CAPACITY,()->runtime.command(p,new Command(UUID.randomUUID(),d.id(),1,d.digest(),settings,2,Operation.START),()->null));
        var capped=new Principal(new SubjectId("generation-fixture"),p.tenantId(),p.permissions(),p.resourceScope());
        store.transaction(p.tenantId(),s->{s.saveTask(capped.subjectId().value(),new WorkflowRuntime.Task(d.id(),1,d.digest(),settings,999_999,"STOPPED",clock.instant(),UUID.randomUUID(),clock.instant(),null));return null;});
        failure(WorkflowFailure.Code.CAPACITY,()->runtime.command(capped,new Command(UUID.randomUUID(),d.id(),1,d.digest(),settings,999_999,Operation.START),()->{throw new AssertionError("Generation reserve before issue");}));
        check(store.transaction(p.tenantId(),s->s.runtimeControlCount(capped.subjectId().value()))==0);
        store.transaction(p.tenantId(),s->{s.saveTask(capped.subjectId().value(),new WorkflowRuntime.Task(d.id(),1,d.digest(),settings,1_000_000,"RUNNING",clock.instant(),UUID.randomUUID(),clock.instant(),null));return null;});
        var legacyStop=new Command(UUID.randomUUID(),d.id(),1,d.digest(),settings,1_000_000,Operation.STOP);
        var finalReceipt=runtime.command(capped,legacyStop,()->{throw new AssertionError("Stop must not issue");});
        check(finalReceipt.task().generation()==1_000_001&&finalReceipt.task().state().equals("STOPPED"));
        check(finalReceipt.equals(runtime.command(capped,legacyStop,()->{throw new AssertionError("Stop replay");})));
        check(runtime.tasks(capped).getFirst().equals(finalReceipt.task()));
        try {new WorkflowRuntime.Task(d.id(),1,d.digest(),settings,1_000_001,"RUNNING",clock.instant(),UUID.randomUUID(),clock.instant(),null);throw new AssertionError("Reserved generation running");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("WorkflowRuntimeControlSmoke: "+checks+" checks passed");
    }
}
