import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowSampleRecovery.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;

/** Explicit synthetic input and in-memory output ports; no external durability claim. */
public final class WorkflowSampleRecoverySmoke {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("Sample recovery "+checks);}
 static void fail(WorkflowFailure.Code code,Runnable work){checks++;try{work.run();}catch(WorkflowFailure f){if(f.code()==code)return;throw f;}throw new AssertionError("Expected "+code);}
 static Command command(WorkflowMetricOutput.Receipt r){return new Command(UUID.randomUUID(),r.workflowId(),r.revision(),r.digest(),Kind.METRIC_SAMPLE,r.requestId(),r.batchDigest(),r.updatedAt(),true);}
 static Command command(WorkflowLogOutput.Receipt r){return new Command(UUID.randomUUID(),r.workflowId(),r.revision(),r.digest(),Kind.LOG_SAMPLE,r.requestId(),r.batchDigest(),r.updatedAt(),true);}
 static Command key(Command c,UUID id){return new Command(id,c.id(),c.revision(),c.digest(),c.kind(),c.batchId(),c.batchDigest(),c.expectedUpdatedAt(),true);}
 static Principal other(Principal p){return new Principal(new SubjectId("other"),p.tenantId(),p.permissions(),ResourceScope.tenantWide());}
 public static void main(String[] args)throws Exception {
  var m=new WorkflowMetricOutputSmoke.Fixture();m.sink.unknown=true;var original=m.service().write(m.p,m.command);var c=command(original);var s=new WorkflowSampleRecoveryService(m.store,m.clock);
  check(s.status(m.p,c.kind(),c.batchId()).closure()==null);var reads=m.sink.reads;var writes=m.sink.writes;
  fail(WorkflowFailure.Code.CONFLICT,()->s.abandon(m.p,new Command(c.requestId(),c.id(),1,c.digest(),c.kind(),c.batchId(),"sha256:"+"0".repeat(64),c.expectedUpdatedAt(),true)));
  fail(WorkflowFailure.Code.CONFLICT,()->s.abandon(m.p,new Command(c.requestId(),c.id(),1,c.digest(),c.kind(),c.batchId(),c.batchDigest(),c.expectedUpdatedAt().plusNanos(1),true)));
  fail(WorkflowFailure.Code.CONFLICT,()->new WorkflowSampleRecoveryService(m.store,Clock.offset(m.clock,Duration.ofSeconds(-1))).abandon(m.p,c));
  var r=s.abandon(m.p,c);check(r.state().equals("ABANDONED")&&r.uncertainRecords()==2);check(r.commandDigest().equals(c.commandDigest()));check(r.proofUpdatedAt().equals(original.updatedAt()));
  check(s.abandon(m.p,c).equals(r));check(s.receipt(m.p,c.requestId()).equals(r));check(s.status(m.p,c.kind(),c.batchId()).closure().equals(r));check(m.sink.reads==reads&&m.sink.writes==writes);
  fail(WorkflowFailure.Code.CONFLICT,()->s.abandon(m.p,key(c,UUID.randomUUID())));fail(WorkflowFailure.Code.NOT_FOUND,()->s.receipt(other(m.p),c.requestId()));fail(WorkflowFailure.Code.NOT_FOUND,()->s.status(other(m.p),c.kind(),c.batchId()));
  var tenant=new Principal(m.p.subjectId(),new TenantId("other-sample"),m.p.permissions(),ResourceScope.tenantWide());fail(WorkflowFailure.Code.NOT_FOUND,()->s.abandon(tenant,c));
  var denied=new Principal(m.p.subjectId(),m.p.tenantId(),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());fail(WorkflowFailure.Code.FORBIDDEN,()->s.receipt(denied,c.requestId()));
  var scope=new Principal(m.p.subjectId(),m.p.tenantId(),m.p.permissions(),ResourceScope.of(Set.of(new ResourceRef(m.p.tenantId(),"workflow",c.id()),ResourceRef.metric(m.p.tenantId(),m.d.target().metricKey()))));fail(WorkflowFailure.Code.FORBIDDEN,()->s.status(scope,c.kind(),c.batchId()));
  check(m.service().read(m.p,c.batchId()).equals(original));check(m.service().write(m.p,m.command).equals(original));fail(WorkflowFailure.Code.CONFLICT,()->m.service().confirm(m.p,c.batchId()));check(m.sink.reads==reads&&m.sink.writes==writes);
  m.sink.visible=m.sink.batch.samples();check(m.service().data(m.p,c.batchId()).proofMatches());check(m.service().read(m.p,c.batchId()).equals(original));
  fail(WorkflowFailure.Code.CONFLICT,()->m.store.transaction(m.p.tenantId(),t->{t.finishMetricOutput(m.p.subjectId().value(),original.finish("CONFIRMED",null,original.updatedAt().plusSeconds(1)));return null;}));check(m.service().read(m.p,c.batchId()).equals(original));
  var good=new WorkflowMetricOutputSmoke.Fixture();var confirmed=good.service().write(good.p,good.command);fail(WorkflowFailure.Code.CONFLICT,()->new WorkflowSampleRecoveryService(good.store,good.clock).abandon(good.p,command(confirmed)));
  var l=new WorkflowLogOutputSmoke.Fixture();l.sink.unknown=true;var unknown=l.service().write(l.p,l.command);var lc=command(unknown);var ls=new WorkflowSampleRecoveryService(l.store,l.clock);var lr=ls.abandon(l.p,lc);check(lr.uncertainRecords()==2);check(ls.receipt(l.p,lc.requestId()).equals(lr));check(l.service().read(l.p,lc.batchId()).equals(unknown));
  var lreads=l.sink.reads;fail(WorkflowFailure.Code.CONFLICT,()->l.service().confirm(l.p,lc.batchId()));check(l.sink.reads==lreads&&l.sink.writes==1);check(l.service().write(l.p,l.command).equals(unknown));l.sink.visible=l.sink.batch.records();check(l.service().data(l.p,lc.batchId()).complete());check(l.service().read(l.p,lc.batchId()).equals(unknown));
  var noWrite=new Principal(l.p.subjectId(),l.p.tenantId(),Set.of(Permission.SOURCE_SYNC,Permission.LOG_READ),ResourceScope.tenantWide());fail(WorkflowFailure.Code.FORBIDDEN,()->ls.receipt(noWrite,lc.requestId()));
  var logScope=new Principal(l.p.subjectId(),l.p.tenantId(),l.p.permissions(),ResourceScope.of(Set.of(new ResourceRef(l.p.tenantId(),"workflow",lc.id()),new ResourceRef(l.p.tenantId(),"log","workflow.other"))));fail(WorkflowFailure.Code.FORBIDDEN,()->ls.status(logScope,lc.kind(),lc.batchId()));
  fail(WorkflowFailure.Code.CONFLICT,()->l.store.transaction(l.p.tenantId(),t->{t.finishLogOutput(l.p.subjectId().value(),unknown.finish("CONFIRMED",null,unknown.updatedAt().plusSeconds(1)));return null;}));
  // A confirmation that wins the transaction makes the stale closure command invalid.
  var race=new WorkflowLogOutputSmoke.Fixture();race.sink.unknown=true;var stale=race.service().write(race.p,race.command);race.sink.visible=race.sink.batch.records();check(race.service().confirm(race.p,stale.requestId()).state().equals("CONFIRMED"));fail(WorkflowFailure.Code.CONFLICT,()->new WorkflowSampleRecoveryService(race.store,race.clock).abandon(race.p,command(stale)));
  // A closure that commits while verification is reading must win its later refinement.
  var duringMetric=new WorkflowMetricOutputSmoke.Fixture();duringMetric.sink.unknown=true;var dm=duringMetric.service().write(duringMetric.p,duringMetric.command);var dc=command(dm);var dr=new WorkflowSampleRecoveryService(duringMetric.store,duringMetric.clock);var metricReads=new java.util.concurrent.atomic.AtomicInteger();
  var msink=new WorkflowMetricOutputService.Sink(){public void write(com.acme.opsweave.telemetry.domain.MetricWriteBatch b){throw new AssertionError("No second write");}public List<com.acme.opsweave.telemetry.domain.MetricWriteBatch.Sample> read(Map<String,String> labels,List<Long> times){metricReads.incrementAndGet();dr.abandon(duringMetric.p,dc);return duringMetric.sink.batch.samples();}};
  fail(WorkflowFailure.Code.CONFLICT,()->new WorkflowMetricOutputService(duringMetric.store,duringMetric.workflows,msink,duringMetric.clock).confirm(duringMetric.p,dm.requestId()));check(metricReads.get()==1);check(duringMetric.service().read(duringMetric.p,dm.requestId()).equals(dm));check(dr.receipt(duringMetric.p,dc.requestId()).state().equals("ABANDONED"));
  var duringLog=new WorkflowLogOutputSmoke.Fixture();duringLog.sink.unknown=true;var dl=duringLog.service().write(duringLog.p,duringLog.command);var dlc=command(dl);var dlr=new WorkflowSampleRecoveryService(duringLog.store,duringLog.clock);var logReads=new java.util.concurrent.atomic.AtomicInteger();
  var lsink=new WorkflowLogOutputService.Sink(){public boolean ready(){return true;}public void write(WorkflowLogOutput.Batch b){throw new AssertionError("No second write");}public List<WorkflowLogOutput.Record> read(WorkflowLogOutput.Scope scope){logReads.incrementAndGet();dlr.abandon(duringLog.p,dlc);return duringLog.sink.batch.records();}};
  fail(WorkflowFailure.Code.CONFLICT,()->new WorkflowLogOutputService(duringLog.store,duringLog.flows,lsink,duringLog.clock,duringLog.budget).confirm(duringLog.p,dl.requestId()));check(logReads.get()==1);check(duringLog.service().read(duringLog.p,dl.requestId()).equals(dl));check(dlr.receipt(duringLog.p,dlc.requestId()).state().equals("ABANDONED"));
  // Capacity is per owner across kinds and failed admission does not mutate the proof.
  var cap=new WorkflowLogOutputSmoke.Fixture();cap.sink.unknown=true;var cp=cap.service().write(cap.p,cap.command);var cc=command(cp);var cs=new WorkflowSampleRecoveryService(cap.store,cap.clock);
  cap.store.transaction(cap.p.tenantId(),t->{for(int i=0;i<200;i++){var cmd=new Command(UUID.randomUUID(),cc.id(),cc.revision(),cc.digest(),cc.kind(),UUID.randomUUID(),cc.batchDigest(),cc.expectedUpdatedAt(),true);t.addSampleRecovery(cap.p.subjectId().value(),new Receipt("2.0",cmd.requestId(),cmd.commandDigest(),new WorkflowQuality.Reference(cc.id(),1,cc.digest()),cc.kind(),cmd.batchId(),cc.batchDigest(),cc.expectedUpdatedAt(),cc.expectedUpdatedAt(),"ABANDONED",2));}return null;});
  fail(WorkflowFailure.Code.CAPACITY,()->cs.abandon(cap.p,cc));check(cs.status(cap.p,cc.kind(),cc.batchId()).closure()==null);check(cap.service().read(cap.p,cc.batchId()).equals(cp));
  check(c.commandDigest().equals(key(c,UUID.randomUUID()).commandDigest()));
  try{new Command(c.requestId(),c.id(),1,c.digest(),c.kind(),c.batchId(),c.batchDigest(),c.expectedUpdatedAt(),false);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  System.out.println("WorkflowSampleRecoverySmoke: "+checks+" checks passed");
 }
}
