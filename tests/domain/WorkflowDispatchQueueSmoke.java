import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricStream.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Explicit deterministic protocol fixtures; no real latency or capacity claim. */
public final class WorkflowDispatchQueueSmoke {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("Queue check "+checks);}
 static void invalid(Runnable work){checks++;try{work.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid queue timing");}
 static final class Fixture {
  final WorkflowMetricOutputSmoke.Fixture f;final WorkflowMetricStreamSmoke.Time time=new WorkflowMetricStreamSmoke.Time();
  final WorkflowDefinition next;final WorkflowMetricStreamService runtime;int reads;boolean stopSecond;
  Fixture()throws Exception{
   f=new WorkflowMetricOutputSmoke.Fixture();next=new WorkflowDefinition("queue-second",1,"Synthetic Fixture",f.d.source(),f.d.target(),f.d.nodes(),f.d.edges());
   f.store.transaction(f.p.tenantId(),s->{s.publish(new WorkflowStore.Entry(next,next.digest(),"PUBLISHED",0,WorkflowSmoke.layout(next),time.instant(),null),f.p.subjectId().value());return null;});
   runtime=new WorkflowMetricStreamService(f.store,f.workflows,(p,src,from,till)->{reads++;if(stopSecond&&reads==1)runtimeStopSecond();time.instant=time.instant.plusMillis(250);return List.of();},f.sink,new Semaphore(2),time);
  }
  void command(WorkflowDefinition d,Operation op,long generation,WorkflowTaskAuthority authority){runtime.command(f.p,new Command(UUID.randomUUID(),d.id(),1,d.digest(),generation,op),()->authority);}
  void start(){command(f.d,Operation.START,0,null);command(next,Operation.START,0,null);time.advance(11);}
  void runtimeStopSecond(){command(next,Operation.STOP,1,null);}
  WorkflowDiagnostics.Observation observation(WorkflowDefinition d){return f.store.transaction(f.p.tenantId(),s->s.diagnostics(f.p.subjectId().value(),d.id(),1,d.digest())).getFirst().observation();}
 }
 public static void main(String[] args)throws Exception{
  var time=new WorkflowMetricStreamSmoke.Time();var queue=new WorkflowDispatchQueue<String>(time);queue.add("first");queue.add("second");check(queue.size()==2);var first=queue.poll();check(first.task().equals("first")&&first.dispatch().waitMillis()==0);time.instant=time.instant.plusMillis(275);var second=queue.poll();check(second.task().equals("second")&&second.dispatch().waitMillis()==275);check(first.dispatch().enqueuedAt().equals(second.dispatch().enqueuedAt()));check(!first.dispatch().id().equals(second.dispatch().id()));check(queue.size()==0&&queue.poll()==null);
  for(int i=0;i<WorkflowDispatchQueue.CAPACITY;i++)queue.add("bounded-"+i);checks++;try{queue.add("overflow");throw new AssertionError("Expected bounded queue");}catch(WorkflowFailure expected){check(expected.code()==WorkflowFailure.Code.CAPACITY);}check(queue.size()==21);
  invalid(()->new WorkflowDiagnostics.Dispatch(UUID.randomUUID(),time.instant(),time.instant().minusNanos(1)));invalid(()->new WorkflowDiagnostics.Dispatch(UUID.randomUUID(),time.instant(),time.instant().plusSeconds(3601)));
  var fractional=new WorkflowDiagnostics.Dispatch(UUID.randomUUID(),time.instant(),time.instant().plusNanos(999000));check(fractional.waitMillis()==0);
  var fixture=new Fixture();fixture.start();fixture.runtime.tick(fixture.f.p);var a=fixture.observation(fixture.f.d);var b=fixture.observation(fixture.next);check(fixture.reads==2&&fixture.f.sink.writes==0);check(a.queueWaitMillis()==0&&b.queueWaitMillis()==250);check(a.dispatch().enqueuedAt().equals(b.dispatch().enqueuedAt()));check(a.dispatch().startedAt().equals(a.startedAt())&&b.dispatch().startedAt().equals(b.startedAt()));check(a.sourceRead().received()==0&&b.sourceRead().received()==0);check(a.result().received()==0&&b.result().received()==0);check(a.dispatch().waitMillis()==a.queueWaitMillis()&&b.dispatch().waitMillis()==b.queueWaitMillis());
  fixture.time.advance(60);fixture.runtime.tick(fixture.f.p);var recent=fixture.f.store.transaction(fixture.f.p.tenantId(),s->s.diagnostics(fixture.f.p.subjectId().value(),fixture.f.d.id(),1,fixture.f.d.digest()));check(recent.size()==3);var sameDispatch=recent.stream().filter(v->v.observation().dispatch().id().equals(fixture.observation(fixture.f.d).dispatch().id())).toList();check(sameDispatch.size()==2);check(sameDispatch.getFirst().observation().queueWaitMillis().equals(sameDispatch.getLast().observation().queueWaitMillis()));
  var stopped=new Fixture();stopped.start();stopped.stopSecond=true;stopped.runtime.tick(stopped.f.p);check(stopped.reads==1&&stopped.runtime.status(stopped.f.p,stopped.next.id()).task().state().equals("STOPPED"));check(stopped.f.store.transaction(stopped.f.p.tenantId(),s->s.diagnostics(stopped.f.p.subjectId().value(),stopped.next.id(),1,stopped.next.digest())).isEmpty());check(stopped.f.sink.writes==0);
  var expired=new Fixture();var issued=expired.time.instant();var longGrant=new WorkflowTaskAuthority(UUID.randomUUID(),"https://fixture.invalid","synthetic-subject","sha256:"+"a".repeat(64),issued,issued.plusSeconds(900),20,0);var shortGrant=new WorkflowTaskAuthority(UUID.randomUUID(),"https://fixture.invalid","synthetic-subject","sha256:"+"b".repeat(64),issued,issued.plusMillis(11100),20,0);expired.command(expired.f.d,Operation.START,0,longGrant);expired.command(expired.next,Operation.START,0,shortGrant);expired.time.advance(11);expired.runtime.tickAuthorized(expired.f.p.tenantId(),expired.f.p.subjectId().value(),task->expired.f.p);check(expired.reads==1);check(expired.runtime.status(expired.f.p,expired.next.id()).task().error().equals("AUTHORIZATION_EXPIRED"));check(expired.f.sink.writes==0);check(expired.f.store.transaction(expired.f.p.tenantId(),s->s.diagnostics(expired.f.p.subjectId().value(),expired.next.id(),1,expired.next.digest())).isEmpty());
  var ref=a.reference();invalid(()->new WorkflowDiagnostics.Observation(a.id(),ref,a.kind(),a.generation(),a.state(),a.from(),a.till(),a.startedAt(),a.completedAt(),1L,a.relatedBatchId(),a.error(),a.result(),a.sourceRead(),a.dispatch()));invalid(()->new WorkflowDiagnostics.Observation(a.id(),ref,a.kind(),a.generation(),a.state(),a.from(),a.till(),a.startedAt(),a.completedAt(),0L,a.relatedBatchId(),a.error(),a.result()));invalid(()->new WorkflowDiagnostics.Observation(a.id(),ref,a.kind(),a.generation(),a.state(),a.from(),a.till(),a.startedAt(),a.completedAt(),0L,a.relatedBatchId(),a.error(),a.result(),a.sourceRead(),new WorkflowDiagnostics.Dispatch(UUID.randomUUID(),a.startedAt().plusNanos(1),a.startedAt().plusNanos(1))));
  System.out.println("WorkflowDispatchQueueSmoke: "+checks+" checks passed");
 }
}
