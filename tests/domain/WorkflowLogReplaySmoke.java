import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowLogReplay.*;
import java.time.*;
import java.util.*;

/** Explicit synthetic source and storage fixture; no external connection or durability claim. */
public final class WorkflowLogReplaySmoke {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Log replay "+checks);}
    static void fail(WorkflowFailure.Code code,Runnable work){checks++;try{work.run();}catch(WorkflowFailure f){if(f.code()==code)return;throw f;}throw new AssertionError("Expected "+code);}
    static final class Fixture {
        final WorkflowLogStreamSmoke.Fixture f;
        final Principal p;
        final WorkflowLogReplayService service;
        final Instant from;
        int reads; Runnable duringRead=()->{};
        Fixture(){this(false);}
        Fixture(boolean filter){f=new WorkflowLogStreamSmoke.Fixture(filter);p=new Principal(f.p.subjectId(),f.p.tenantId(),Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ,Permission.LOG_READ,Permission.LOG_WRITE,Permission.WORKFLOW_REPLAY),ResourceScope.tenantWide());from=f.clock.instant().minusSeconds(180).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);service=service(f.clock);}
        WorkflowLogReplayService service(Clock clock){return new WorkflowLogReplayService(f.store,f.flows,(who,src,start,till)->{reads++;duringRead.run();if(f.unavailable)throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);return f.rows(start);},f.sink,f.budget,clock);}
        Command command(){return new Command(UUID.randomUUID(),f.d.id(),1,f.d.digest(),from,from.plusSeconds(60));}
        Execute execute(Plan p){return new Execute(UUID.randomUUID(),p.requestId(),p.proof().inputDigest(),p.proof().batchDigest());}
    }
    public static void main(String[] args){
        var a=new Fixture();var c=a.command();var plan=a.service.create(a.p,c);
        check(plan.state().equals("READY")&&plan.proof().inputCount()==2&&plan.proof().indices().equals(List.of(0,1)));
        check(a.reads==1&&a.f.sink.writes==0&&!plan.notifications()&&!plan.actions());
        check(plan.proof().scope().requestId().equals(c.requestId())&&!plan.toString().contains("Synthetic Fixture"));
        check(a.service.create(a.p,c).equals(plan)&&a.reads==1);check(a.service.plans(a.p,plan.reference()).items().equals(List.of(plan)));
        var exec=a.execute(plan);var result=a.service.execute(a.p,exec);
        check(result.state().equals("CONFIRMED")&&a.reads==2&&a.f.sink.writes==1&&a.f.sink.reads==1);
        check(a.f.sink.batch.records().size()==2&&a.f.sink.batch.records().getFirst().body().contains("<script>"));
        check(a.service.execute(a.p,exec).equals(result)&&a.reads==2&&a.f.sink.writes==1);
        check(a.service.verify(a.p,exec.requestId()).equals(result)&&a.f.sink.reads==1);
        fail(WorkflowFailure.Code.CONFLICT,()->a.service.execute(a.p,a.execute(plan)));
        fail(WorkflowFailure.Code.CONFLICT,()->a.service.create(a.p,new Command(c.requestId(),c.id(),1,c.digest(),c.from().minusSeconds(60),c.from())));
        fail(WorkflowFailure.Code.FORBIDDEN,()->a.service.create(a.f.p,a.command()));
        var noWrite=new Principal(a.p.subjectId(),a.p.tenantId(),Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ,Permission.LOG_READ,Permission.WORKFLOW_REPLAY),ResourceScope.tenantWide());
        fail(WorkflowFailure.Code.FORBIDDEN,()->a.service.create(noWrite,a.command()));check(a.reads==2&&a.f.sink.writes==1);
        var other=new Principal(new SubjectId("other-replay-fixture"),a.p.tenantId(),a.p.permissions(),ResourceScope.tenantWide());fail(WorkflowFailure.Code.NOT_FOUND,()->a.service.plan(other,plan.requestId()));
        var changed=new Fixture();var cp=changed.service.create(changed.p,changed.command());changed.f.changed=true;var rejected=changed.service.execute(changed.p,changed.execute(cp));
        check(rejected.state().equals("FAILED")&&rejected.error().equals("SOURCE_WINDOW_CHANGED")&&changed.f.sink.writes==0);
        var lost=new Fixture();var lp=lost.service.create(lost.p,lost.command());var lc=lost.execute(lp);lost.f.sink.unknown=true;var lr=lost.service.execute(lost.p,lc);
        check(lr.state().equals("UNKNOWN")&&lost.f.sink.writes==1);check(lost.service.execute(lost.p,lc).equals(lr)&&lost.reads==2);
        check(lost.service.verify(lost.p,lc.requestId()).state().equals("UNKNOWN")&&lost.f.sink.writes==1&&lost.reads==2);
        lost.f.sink.visible.put(lp.requestId(),lost.f.sink.batch.records());check(lost.service.verify(lost.p,lc.requestId()).state().equals("CONFIRMED")&&lost.reads==2&&lost.f.sink.writes==1);
        var readLost=new Fixture();var rp=readLost.service.create(readLost.p,readLost.command());readLost.f.sink.readDown=true;var rc=readLost.execute(rp);
        check(readLost.service.execute(readLost.p,rc).state().equals("UNKNOWN")&&readLost.f.sink.writes==1);readLost.f.sink.readDown=false;check(readLost.service.verify(readLost.p,rc.requestId()).state().equals("CONFIRMED")&&readLost.f.sink.writes==1);
        var refused=new Fixture();var refusedPlan=refused.service.create(refused.p,refused.command());refused.f.sink.rejected=true;check(refused.service.execute(refused.p,refused.execute(refusedPlan)).state().equals("FAILED"));
        var empty=new Fixture();empty.f.empty=true;var ep=empty.service.create(empty.p,empty.command());check(ep.proof().inputCount()==0&&ep.proof().indices().isEmpty());check(empty.service.execute(empty.p,empty.execute(ep)).state().equals("CONFIRMED")&&empty.f.sink.writes==0&&empty.f.sink.reads==0);
        var full=new Fixture();full.f.count=1000;var fp=full.service.create(full.p,full.command());check(fp.proof().positions().size()==1000&&fp.proof().indices().getLast()==999);check(full.service.execute(full.p,full.execute(fp)).state().equals("CONFIRMED")&&full.f.sink.batch.records().size()==1000);
        var first=full.service.data(full.p,fp.requestId(),-1);check(first.complete()&&first.records().size()==50&&first.nextIndex()==49);
        var second=full.service.data(full.p,fp.requestId(),49);check(second.records().getFirst().index()==50&&second.nextIndex()==99&&full.reads==2&&full.f.sink.writes==1);
        var workflowOnly=new Principal(full.p.subjectId(),full.p.tenantId(),full.p.permissions(),ResourceScope.of(Set.of(new ResourceRef(full.p.tenantId(),"workflow","*"))));fail(WorkflowFailure.Code.FORBIDDEN,()->full.service.data(workflowOnly,fp.requestId(),-1));
        var scoped=new Principal(full.p.subjectId(),full.p.tenantId(),full.p.permissions(),ResourceScope.of(Set.of(new ResourceRef(full.p.tenantId(),"workflow","*"),new ResourceRef(full.p.tenantId(),"log","workflow."+full.f.d.id()))));check(full.service.data(scoped,fp.requestId(),999).records().isEmpty());
        fail(WorkflowFailure.Code.CONFLICT,()->full.service.data(full.p,fp.requestId(),1000));
        var overflow=new Fixture();overflow.f.count=1001;var op=overflow.service.create(overflow.p,overflow.command());check(op.state().equals("FAILED")&&op.error().equals("INVALID_SAMPLE")&&overflow.f.sink.writes==0);
        var unavailable=new Fixture();unavailable.f.unavailable=true;check(unavailable.service.create(unavailable.p,unavailable.command()).error().equals("SOURCE_UNAVAILABLE"));
        var revoke=new Fixture();var vp=revoke.service.create(revoke.p,revoke.command());revoke.duringRead=()->revoke.f.revoked=true;check(revoke.service.execute(revoke.p,revoke.execute(vp)).error().equals("FORBIDDEN")&&revoke.f.sink.writes==0);
        var expiry=new Fixture();var xp=expiry.service.create(expiry.p,expiry.command());fail(WorkflowFailure.Code.CONFLICT,()->expiry.service(Clock.offset(expiry.f.clock,Duration.ofSeconds(600))).execute(expiry.p,expiry.execute(xp)));check(expiry.reads==1&&expiry.f.sink.writes==0);
        var busy=new Fixture();busy.f.budget.acquireUninterruptibly(2);fail(WorkflowFailure.Code.BUSY,()->busy.service.create(busy.p,busy.command()));check(busy.reads==0);busy.f.budget.release(2);
        var pending=new Fixture();var pp=pending.service.create(pending.p,pending.command());var pc=pending.execute(pp);pending.duringRead=()->{check(pending.service.verify(pending.p,pc.requestId()).state().equals("PENDING"));check(pending.f.sink.reads==0);};check(pending.service.execute(pending.p,pc).state().equals("CONFIRMED")&&pending.f.sink.writes==1);
        check(a.f.store.transaction(a.p.tenantId(),s->s.logStreamTask(a.p.subjectId().value(),a.f.d.id())).isEmpty());
        System.out.println("Workflow log replay smoke: "+checks+" checks passed");
    }
}
