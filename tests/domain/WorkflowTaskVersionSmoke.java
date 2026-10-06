import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.identity.domain.*;
import java.util.*;

/** Explicit protocol fixtures; terminal history, replacement CAS and output uncertainty are exercised. */
public final class WorkflowTaskVersionSmoke {
    static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("Task version check "+checks);}
    static void fails(Runnable r){checks++;try{r.run();}catch(WorkflowFailure|IllegalArgumentException e){return;}throw new AssertionError("Expected failure "+checks);}
    static WorkflowDefinition next(WorkflowDefinition d){return new WorkflowDefinition(d.id(),d.revision()+1,d.name(),d.source(),d.target(),d.nodes(),d.edges());}
    static void publish(WorkflowStore s,Principal p,WorkflowDefinition d,java.time.Instant t){s.transaction(p.tenantId(),q->{q.publish(new WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,WorkflowSmoke.layout(d),t,null),p.subjectId().value());return null;});}
    static WorkflowTaskArchive archive(WorkflowStore s,Principal p,WorkflowDefinition d){return s.transaction(p.tenantId(),q->q.taskArchive(p.subjectId().value(),d.id(),d.revision(),d.digest()).orElseThrow());}
    public static void main(String[] args)throws Exception {
        var m=new WorkflowMetricStreamSmoke.Fixture();m.command(WorkflowMetricStream.Operation.START,0);m.time.advance(11);m.tick();m.command(WorkflowMetricStream.Operation.STOP,1);
        var original=m.status();var m2=next(m.f.d);publish(m.f.store,m.f.p,m2,m.time.instant());
        check(m.service.versionStatus(m.f.p,m2.id(),2).control().startAllowed());
        var start=new WorkflowMetricStream.Command(UUID.randomUUID(),m2.id(),2,m2.digest(),2,WorkflowMetricStream.Operation.START);
        var receipt=m.service.command(m.f.p,start,()->null);check(receipt.task().generation()==3&&receipt.task().confirmedPoints()==0&&receipt.task().pendingBatchId()==null);
        check(archive(m.f.store,m.f.p,m.f.d).task().state().equals("STOPPED")&&archive(m.f.store,m.f.p,m.f.d).task().generation()==2);
        check(new WorkflowQualityService(m.f.store,m.f.workflows,m.time).report(m.f.p,m2.id(),1).task().equals(archive(m.f.store,m.f.p,m.f.d).task()));
        check(m.service.command(m.f.p,start,()->{throw new AssertionError("No replay issuance");}).equals(receipt));check(m.reads==1&&m.f.sink.writes==1&&m.status().batches().equals(original.batches()));
        check(!m.service.versionStatus(m.f.p,m2.id(),1).control().startAllowed());fails(()->m.command(WorkflowMetricStream.Operation.RESUME,3));fails(()->m.command(WorkflowMetricStream.Operation.START,3));
        var denied=new Principal(new SubjectId("other-owner"),m.f.p.tenantId(),m.f.p.permissions(),ResourceScope.tenantWide());check(m.f.store.transaction(denied.tenantId(),q->q.taskArchive(denied.subjectId().value(),m2.id(),1,m.f.d.digest())).isEmpty());

        var l=new WorkflowLogStreamSmoke.Fixture();l.command(WorkflowLogStream.Operation.START,0);l.clock.advance(11);l.unavailable=true;l.tick();var failed=l.status().task();var l2=next(l.d);publish(l.store,l.p,l2,l.clock.instant());
        check(failed.state().equals("FAILED")&&failed.pendingBatchId()==null&&l.service.versionStatus(l.p,l2.id(),2).control().startAllowed());
        l.service.command(l.p,new WorkflowLogStream.Command(UUID.randomUUID(),l2.id(),2,l2.digest(),1,WorkflowLogStream.Operation.START),()->null);
        check(archive(l.store,l.p,l.d).task().error().equals("SOURCE_UNAVAILABLE")&&l.sink.writes==0&&l.reads==1);
        check(new WorkflowQualityService(l.store,l.flows,l.clock).report(l.p,l.d.id(),1).task().state().equals("FAILED"));

        var rejected=new WorkflowLogStreamSmoke.Fixture();rejected.sink.rejected=true;rejected.command(WorkflowLogStream.Operation.START,0);rejected.clock.advance(11);rejected.tick();var rejectedBatch=rejected.status().batches().getFirst();var r2=next(rejected.d);publish(rejected.store,rejected.p,r2,rejected.clock.instant());
        check(rejected.service.versionStatus(rejected.p,r2.id(),2).control().startAllowed());rejected.service.command(rejected.p,new WorkflowLogStream.Command(UUID.randomUUID(),r2.id(),2,r2.digest(),1,WorkflowLogStream.Operation.START),()->null);
        check(archive(rejected.store,rejected.p,rejected.d).task().pendingBatchId().equals(rejectedBatch.id())&&rejected.status().batches().getFirst().equals(rejectedBatch)&&rejected.sink.writes==1);

        var unknown=new WorkflowLogStreamSmoke.Fixture();unknown.sink.unknown=true;unknown.command(WorkflowLogStream.Operation.START,0);unknown.clock.advance(11);unknown.tick();var u2=next(unknown.d);publish(unknown.store,unknown.p,u2,unknown.clock.instant());
        check(!unknown.service.versionStatus(unknown.p,u2.id(),2).control().startAllowed());fails(()->unknown.service.command(unknown.p,new WorkflowLogStream.Command(UUID.randomUUID(),u2.id(),2,u2.digest(),1,WorkflowLogStream.Operation.START),()->{throw new AssertionError("No uncertain replacement issuance");}));check(unknown.sink.writes==1&&unknown.store.transaction(unknown.p.tenantId(),q->q.taskArchiveCount(unknown.p.subjectId().value()))==0);

        var host=new WorkflowHostRuntimeSmoke.Fixture();host.command(WorkflowRuntimeControl.Operation.START,0);host.runtime.tick(host.p);host.command(WorkflowRuntimeControl.Operation.STOP,1);var page=host.runtime.hostBatches(host.p,host.d.id()).getFirst();var h2=next(host.d);publish(host.store,host.p,h2,host.clock.instant());
        host.runtime.command(host.p,new WorkflowRuntimeControl.Command(UUID.randomUUID(),h2.id(),2,h2.digest(),host.settings,2,WorkflowRuntimeControl.Operation.START),()->null);
        check(host.checkpoint().revision()==2&&host.checkpoint().confirmedRecords()==0&&host.checkpoint().pendingBatchId()==null);check(host.reads==1&&host.writes==5&&host.runtime.hostBatches(host.p,host.d.id()).contains(page));check(archive(host.store,host.p,host.d).task().state().equals("STOPPED"));
        var guards=new WorkflowService.Sources(){public void require(Principal p,WorkflowDefinition.Source d,WorkflowStore.Session s,boolean a){}public void requireTarget(Principal p,WorkflowDefinition.Source d,WorkflowDefinition.Target t,WorkflowStore.Session s,boolean a){}};
        var flows=new WorkflowService(host.store,(p,t)->WorkflowSmoke.MODEL,(p,s,id)->{throw new AssertionError("No quality source IO");},guards,host.clock);
        check(new WorkflowQualityService(host.store,flows,host.clock).report(host.p,host.d.id(),1).task().generation()==2);host.runtime.command(host.p,new WorkflowRuntimeControl.Command(UUID.randomUUID(),h2.id(),2,h2.digest(),host.settings,3,WorkflowRuntimeControl.Operation.STOP),()->null);fails(()->host.command(WorkflowRuntimeControl.Operation.START,4));

        var lost=new WorkflowHostRuntimeSmoke.Fixture();lost.outage=true;lost.command(WorkflowRuntimeControl.Operation.START,0);lost.runtime.tick(lost.p);var lost2=next(lost.d);publish(lost.store,lost.p,lost2,lost.clock.instant());var cp=lost.checkpoint();
        fails(()->lost.runtime.command(lost.p,new WorkflowRuntimeControl.Command(UUID.randomUUID(),lost2.id(),2,lost2.digest(),lost.settings,1,WorkflowRuntimeControl.Operation.START),()->{throw new AssertionError("No host uncertain replacement issuance");}));check(lost.checkpoint().equals(cp)&&lost.writes==2);

        var rollback=new WorkflowLogStreamSmoke.Fixture();rollback.command(WorkflowLogStream.Operation.START,0);rollback.command(WorkflowLogStream.Operation.STOP,1);var rb2=next(rollback.d);publish(rollback.store,rollback.p,rb2,rollback.clock.instant());var rb=rollback.status().task();
        try{rollback.service.command(rollback.p,new WorkflowLogStream.Command(UUID.randomUUID(),rb2.id(),2,rb2.digest(),2,WorkflowLogStream.Operation.START),()->{throw new IllegalStateException("Explicit issuer fixture failure");});throw new AssertionError();}catch(IllegalStateException expected){}
        check(rollback.status().task().equals(rb)&&rollback.store.transaction(rollback.p.tenantId(),q->q.taskArchiveCount(rollback.p.subjectId().value()))==0);
        var scheduled=new WorkflowHostRuntimeSmoke.Fixture();scheduled.empty=true;var scheduler=WorkflowHostScheduleSmoke.service(scheduled);scheduler.command(scheduled.p,WorkflowHostScheduleSmoke.command(scheduled,WorkflowHostSchedule.Operation.START,0),()->null);scheduled.runtime.tick(scheduled.p);scheduler.tick(scheduled.p);scheduler.command(scheduled.p,WorkflowHostScheduleSmoke.command(scheduled,WorkflowHostSchedule.Operation.STOP,1),()->null);var oldSchedule=scheduler.status(scheduled.p,scheduled.d.id()).schedule();var scheduled2=next(scheduled.d);publish(scheduled.store,scheduled.p,scheduled2,scheduled.clock.instant());scheduled.runtime.command(scheduled.p,new WorkflowRuntimeControl.Command(UUID.randomUUID(),scheduled2.id(),2,scheduled2.digest(),scheduled.settings,scheduled.task().generation(),WorkflowRuntimeControl.Operation.START),()->null);
        check(archive(scheduled.store,scheduled.p,scheduled.d).schedule().generation()==oldSchedule.generation());var scheduledFlows=new WorkflowService(scheduled.store,(p,t)->WorkflowSmoke.MODEL,(p,src,id)->{throw new AssertionError("No history IO");},guards,scheduled.clock);check(new WorkflowQualityService(scheduled.store,scheduledFlows,scheduled.clock).report(scheduled.p,scheduled.d.id(),1).task().generation()==oldSchedule.generation());
        var capacity=new WorkflowLogStreamSmoke.Fixture();capacity.command(WorkflowLogStream.Operation.START,0);capacity.command(WorkflowLogStream.Operation.STOP,1);var capacity2=next(capacity.d);publish(capacity.store,capacity.p,capacity2,capacity.clock.instant());capacity.store.transaction(capacity.p.tenantId(),q->{for(int i=0;i<200;i++){var id="fixture-archive-capacity-"+i;var a=new WorkflowQuality.Reference(id,1,"sha256:"+"a".repeat(64));var b=new WorkflowQuality.Reference(id,2,"sha256:"+"b".repeat(64));q.addTaskArchive(capacity.p.subjectId().value(),new WorkflowTaskArchive("2.0",a,WorkflowQuality.Kind.LOG_STREAM,new WorkflowQuality.Task("STOPPED",1,capacity.clock.instant(),null,null),null,b,2,capacity.clock.instant()));}return null;});fails(()->capacity.service.command(capacity.p,new WorkflowLogStream.Command(UUID.randomUUID(),capacity2.id(),2,capacity2.digest(),2,WorkflowLogStream.Operation.START),()->{throw new AssertionError("Capacity before authority issuance");}));check(capacity.status().task().revision()==1&&capacity.store.transaction(capacity.p.tenantId(),q->q.taskArchiveCount(capacity.p.subjectId().value()))==200);
        var record=archive(host.store,host.p,host.d);fails(()->new WorkflowTaskArchive("2.0",record.reference(),record.kind(),record.task(),record.schedule(),record.reference(),record.nextGeneration(),record.replacedAt()));fails(()->new WorkflowTaskArchive("2.0",record.reference(),record.kind(),record.task(),record.schedule(),record.replacedBy(),record.nextGeneration()+1,record.replacedAt()));fails(()->new WorkflowTaskArchive("2.0",record.reference(),record.kind(),record.task(),record.schedule(),record.replacedBy(),record.nextGeneration(),record.task().updatedAt().minusSeconds(1)));
        System.out.println("WorkflowTaskVersionSmoke: "+checks+" checks passed");
    }
}
