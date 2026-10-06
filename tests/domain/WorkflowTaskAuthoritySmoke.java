import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowRuntime.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class WorkflowTaskAuthoritySmoke {
    static int checks, serial;
    static void check(boolean value) { checks++; if(!value) throw new AssertionError("Task authority check "+checks); }
    static void rejects(Runnable action) { checks++; try { action.run(); } catch(IllegalArgumentException|WorkflowFailure expected) { return; } throw new AssertionError("Expected rejection "+checks); }
    static final class Fixture {
        final WorkflowSmoke.TestClock clock=new WorkflowSmoke.TestClock();
        final InMemoryWorkflowStore store=new InMemoryWorkflowStore();
        final Principal p=new Principal(new SubjectId("authority-fixture"),new TenantId("authority-fixture"),Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ,Permission.ENTITY_MANAGE),ResourceScope.tenantWide());
        final WorkflowDefinition definition=WorkflowSmoke.flow("authority-"+(++serial),1,new WorkflowDefinition.Source("ZABBIX_HOST","fixture-host"));
        final Settings settings=new Settings("source_id","name");
        final List<Map<String,Object>> input=List.of(Map.of("raw_name","Fixture first","source_id","one"),Map.of("raw_name","Fixture second","source_id","two"));
        final WorkflowService.Batch inputBatch=new WorkflowService.Batch(input,"fixture",2,0,false,"SUCCEEDED");
        final AtomicReference<WorkflowRuntimeService.Batch> batch=new AtomicReference<>();
        final AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger(),resolves=new AtomicInteger();
        final AtomicBoolean active=new AtomicBoolean(true);
        final WorkflowRuntimeService runtime;
        WorkflowTaskAuthority authority;
        Fixture(boolean revokeAfterFirst,boolean failSecond) {
            WorkflowService.Samples samples=(who,source,id)->inputBatch;
            var edit=new WorkflowService(store,(who,target)->WorkflowSmoke.MODEL,samples,clock);
            edit.save(p,definition,WorkflowSmoke.layout(definition),0);
            var preview=edit.evaluate(p,definition.id(),1,1,definition.digest(),false,null,UUID.randomUUID());
            edit.publish(p,definition.id(),1,1,definition.digest(),preview.receipt().id());
            runtime=new WorkflowRuntimeService(store,(who,target)->WorkflowSmoke.MODEL,samples,(who,source,cursor,cursorId)->{
                reads.incrementAndGet(); var value=batch.get();
                return value==null || !value.completedAt().isAfter(cursor)?Optional.empty():Optional.of(value);
            },new WorkflowRuntimeService.Output() {
                public void validate(Principal who,WorkflowDefinition d,Settings s,List<Map<String,Object>> values,WorkflowEvaluation evaluation) { check(evaluation.accepted()==2); }
                public String write(Principal who,WorkflowDefinition d,Settings s,UUID execution,Instant time,Map<String,Object> values,Map<String,Object> output,String origin) {
                    int count=writes.incrementAndGet();
                    if(failSecond && count==2) throw new IllegalStateException("Explicit Fixture sink failure");
                    if(revokeAfterFirst) active.set(false);
                    return UUID.nameUUIDFromBytes((execution+":"+values.get("source_id")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                }
            },clock);
        }
        Task start(int limit,int seconds) {
            authority=new WorkflowTaskAuthority(UUID.randomUUID(),"https://issuer.example.invalid","fixture-subject","sha256:"+"a".repeat(64),clock.instant,clock.instant.plusSeconds(seconds),limit,0);
            return runtime.control(p,definition.id(),1,definition.digest(),settings,0,true,authority);
        }
        void fresh() { clock.instant=clock.instant.plusSeconds(1);batch.set(new WorkflowRuntimeService.Batch(UUID.randomUUID(),clock.instant,clock.instant.minusNanos(1),inputBatch)); }
        Principal resolve(Task task) { resolves.incrementAndGet();if(!active.get()) throw new WorkflowFailure(WorkflowFailure.Code.AUTHORIZATION_REVOKED); return p; }
        void tick() { runtime.tickAuthorized(p.tenantId(),p.subjectId().value(),this::resolve); }
        Task task() { return runtime.tasks(p).getFirst(); }
    }
    public static void main(String[] args) {
        var normal=new Fixture(false,false); var initial=normal.start(20,900);normal.fresh();normal.runtime.tick(normal.p);
        check(normal.reads.get()==0 && normal.writes.get()==0); // Dev never consumes delegated work.
        normal.tick(); check(normal.writes.get()==2 && normal.resolves.get()>=5);
        check(normal.task().authority().consumedBatches()==1 && normal.task().cursorId().equals(normal.batch.get().id()));
        var execution=normal.runtime.executions(normal.p).getFirst(); check(execution.authorizationId().equals(initial.authority().id()) && execution.state().equals("SUCCEEDED"));
        normal.tick();check(normal.writes.get()==2); // The confirmed cursor prevents a repeated write.
        var stopped=normal.runtime.control(normal.p,normal.definition.id(),1,normal.definition.digest(),normal.settings,1,false);
        check(stopped.generation()==2 && stopped.authority().id().equals(initial.authority().id()));normal.fresh();normal.tick();check(normal.writes.get()==2);

        var revoked=new Fixture(true,false);var before=revoked.start(20,900);revoked.fresh();revoked.tick();
        check(revoked.task().state().equals("FAILED") && revoked.task().error().equals("AUTHORIZATION_REVOKED"));
        check(revoked.task().cursor().equals(before.cursor()) && revoked.task().cursorId().equals(before.cursorId()));
        check(revoked.writes.get()==1 && revoked.task().authority().consumedBatches()==1);
        var partial=revoked.runtime.executions(revoked.p).getFirst();check(partial.accepted()==2 && partial.written()==1 && partial.error().equals("AUTHORIZATION_REVOKED"));
        revoked.tick();check(revoked.writes.get()==1 && revoked.runtime.executions(revoked.p).size()==1);

        var outage=new Fixture(false,true);var original=outage.start(20,900);outage.fresh();outage.tick();
        check(outage.task().error().equals("OUTPUT_UNAVAILABLE") && outage.task().cursor().equals(original.cursor()));
        check(outage.runtime.executions(outage.p).getFirst().written()==1);outage.tick();check(outage.writes.get()==2);

        var limited=new Fixture(false,false);limited.start(1,900);limited.fresh();limited.tick();limited.fresh();limited.tick();
        check(limited.task().state().equals("FAILED") && limited.task().error().equals("EXECUTION_LIMIT"));
        check(limited.reads.get()==1 && limited.writes.get()==2 && limited.task().authority().consumedBatches()==1);

        var expired=new Fixture(false,false);expired.start(20,1);expired.fresh();expired.tick();
        check(expired.task().error().equals("AUTHORIZATION_EXPIRED") && expired.reads.get()==0 && expired.writes.get()==0);
        var rollback=new Fixture(false,false);rollback.start(20,900);rollback.clock.instant=rollback.clock.instant.minusNanos(1);rollback.tick();
        check(rollback.task().error().equals("AUTHORIZATION_EXPIRED") && rollback.reads.get()==0);

        var forged=new Fixture(false,false);forged.start(20,900);forged.fresh();
        forged.runtime.tickAuthorized(forged.p.tenantId(),forged.p.subjectId().value(),t->new Principal(forged.p.subjectId(),new TenantId("different"),forged.p.permissions(),ResourceScope.tenantWide()));
        check(forged.task().error().equals("AUTHORIZATION_REVOKED") && forged.reads.get()==0);
        var scoped=new Fixture(false,false);scoped.start(20,900);scoped.fresh();
        var scope=ResourceScope.of(Set.of(new ResourceRef(scoped.p.tenantId(),"workflow","*"),new ResourceRef(scoped.p.tenantId(),"catalog","*"),ResourceRef.anyEntity(scoped.p.tenantId())));
        scoped.runtime.tickAuthorized(scoped.p.tenantId(),scoped.p.subjectId().value(),t->new Principal(scoped.p.subjectId(),scoped.p.tenantId(),scoped.p.permissions(),scope));
        check(scoped.task().error().equals("FORBIDDEN") && scoped.reads.get()==0);

        var future=new Fixture(false,false);future.start(20,900);future.batch.set(new WorkflowRuntimeService.Batch(UUID.randomUUID(),future.clock.instant.plusSeconds(1),future.clock.instant,future.inputBatch));future.tick();
        check(future.task().error().equals("INVALID_SAMPLE") && future.writes.get()==0);

        var a=normal.authority;
        rejects(()->new WorkflowTaskAuthority(a.id(),a.issuer(),a.externalSubject(),a.grantDigest(),a.issuedAt(),a.issuedAt().plusSeconds(901),20,0));
        rejects(()->new WorkflowTaskAuthority(a.id(),a.issuer(),a.externalSubject(),a.grantDigest(),a.issuedAt(),a.expiresAt(),21,0));
        rejects(()->new WorkflowTaskAuthority(a.id(),a.issuer(),a.externalSubject(),a.grantDigest(),a.issuedAt(),a.expiresAt(),1,2));
        rejects(()->new WorkflowTaskAuthority(a.id(),"x".repeat(1025),a.externalSubject(),a.grantDigest(),a.issuedAt(),a.expiresAt(),20,0));
        rejects(()->new WorkflowTaskAuthority(a.id(),a.issuer(),"界".repeat(86),a.grantDigest(),a.issuedAt(),a.expiresAt(),20,0));
        System.out.println("WorkflowTaskAuthoritySmoke: "+checks+" checks passed");
    }
}
