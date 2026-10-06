package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowRuntime.Task;
import com.acme.opsweave.integration.domain.WorkflowHostScan.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Durable asset batches. A confirmed batch, rather than a read or partial write, advances the source cursor. */
public final class WorkflowHostRuntimeService {
    public interface Sources {
        void require(WorkflowStore.Session session,Principal principal,WorkflowDefinition.Source source);
        default void requireMetadata(WorkflowStore.Session session,Principal principal,WorkflowDefinition.Source source){require(session,principal,source);}
        Page read(Principal principal,WorkflowDefinition.Source source,String cursor);
    }
    private record Context(Task task,Checkpoint checkpoint,WorkflowStore.Entry entry,Principal principal) {}
    private final WorkflowStore store;
    private final WorkflowService.Models models;
    private final WorkflowRuntimeService.Output output;
    private final Sources sources;
    private final Clock clock;
    public WorkflowHostRuntimeService(WorkflowStore store,WorkflowService.Models models,WorkflowRuntimeService.Output output,Sources sources,Clock clock) {
        this.store=store;this.models=models;this.output=output;this.sources=sources;this.clock=clock;
    }
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    public void initialize(WorkflowStore.Session s,Principal p,Task task,boolean resume) { initialize(s,p,task,resume,false); }
    public void initialize(WorkflowStore.Session s,Principal p,Task task,boolean resume,boolean replaceVersion) {
        var owner=p.subjectId().value();var e=entry(s,task);sources.require(s,p,e.definition().source());
        if(!task.settings().identityField().equals("entity_id"))throw new IllegalArgumentException("Fixed host identity required");
        var previous=s.hostCheckpoint(owner,task.workflowId());
        if(resume){
            var c=previous.filter(v->v.revision()==task.revision()&&v.digest().equals(task.digest())&&!v.complete()).orElseThrow(()->fail(WorkflowFailure.Code.CONFLICT));
            if(c.pendingBatchId()!=null){
                var b=s.hostBatch(owner,c.pendingBatchId()).orElseThrow();
                if(!b.settings().equals(task.settings()))throw fail(WorkflowFailure.Code.CONFLICT);
                if(b.state().equals("IN_FLIGHT")){b=b.state("UNKNOWN",b.entityIds(),"OUTPUT_UNAVAILABLE",now());s.finishHostBatch(owner,b);}
                if(Set.of("UNKNOWN","FAILED").contains(b.state()))s.finishHostBatch(owner,b.state("READY",b.entityIds(),null,now()));
                else if(!b.state().equals("READY"))throw fail(WorkflowFailure.Code.CONFLICT);
            }
            s.saveHostCheckpoint(owner,c.generation(task.generation(),now()));
        }else{
            if(previous.isPresent()&&!previous.get().complete()&&!replaceVersion)throw fail(WorkflowFailure.Code.CONFLICT);
            s.saveHostCheckpoint(owner,new Checkpoint(task.workflowId(),task.revision(),task.digest(),task.generation(),UUID.randomUUID(),null,null,0,0,false,now()));
        }
    }
    public void stopped(WorkflowStore.Session s,String owner,Task task) {
        s.hostCheckpoint(owner,task.workflowId()).ifPresent(c->s.saveHostCheckpoint(owner,c.generation(task.generation(),now())));
    }
    public Optional<Checkpoint> checkpoint(Principal p,String id){WorkflowDefinition.ref(id,1);authorize(p,id);return store.transaction(p.tenantId(),s->{var c=s.hostCheckpoint(p.subjectId().value(),id);c.ifPresent(v->metadata(s,p,v.workflowId(),v.revision(),v.digest()));return c;});}
    public List<Batch> batches(Principal p,String id){WorkflowDefinition.ref(id,1);authorize(p,id);return store.transaction(p.tenantId(),s->{var batches=s.hostBatches(p.subjectId().value(),id);for(var b:batches){metadata(s,p,b.workflowId(),b.revision(),b.digest());for(var row:b.records())readEntity(p,(String)row.get("entity_id"));for(var entity:b.entityIds())readEntity(p,entity);}return batches;});}
    private void metadata(WorkflowStore.Session s,Principal p,String id,int revision,String digest){var e=s.published(id,revision).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));if(!e.digest().equals(digest))throw fail(WorkflowFailure.Code.CONFLICT);sources.requireMetadata(s,p,e.definition().source());}
    private static void readEntity(Principal p,String id){if(new Authorizer().decide(p,ResourceRef.entity(p.tenantId(),new com.acme.opsweave.sharedkernel.EntityId(UUID.fromString(id))),Permission.ENTITY_READ).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);}
    public void tick(TenantId tenant,String owner,Task listed,Function<Task,Principal> resolver) {
        tick(tenant,owner,listed,resolver,null);
    }
    public void tick(TenantId tenant,String owner,Task listed,Function<Task,Principal> resolver,WorkflowDiagnostics.Dispatch dispatch) {
        try {
            var context=store.transaction(tenant,s->context(s,tenant,owner,listed,resolver));
            if(context==null)return;
            var c=context.checkpoint();
            if(c.pendingBatchId()==null){
                if(c.confirmedBatches()>=200||store.transaction(tenant,s->s.hostBatchCount(owner))>=200)throw fail(WorkflowFailure.Code.CAPACITY);
                // Fixed credential IO stays outside the journal transaction. Scope witnesses are private.
                var witnesses=new ArrayList<String>();var related=new UUID[]{null};
                new WorkflowDiagnosticRecorder(store,clock).inspectSource(tenant,owner,new WorkflowQuality.Reference(listed.workflowId(),listed.revision(),listed.digest()),WorkflowQuality.Kind.HOST_SCAN,listed.generation(),null,null,
                s->{var current=context(s,tenant,owner,listed,resolver);return current!=null&&current.checkpoint().scanId().equals(c.scanId())&&(current.checkpoint().equals(c)||related[0]!=null&&Objects.equals(current.checkpoint().pendingBatchId(),related[0]));},()->List.copyOf(witnesses),()->related[0],dispatch,(inspection,started)->{
                    var page=inspection.source().read(()->{var read=sources.read(context.principal(),context.entry().definition().source(),c.nextCursor());witnesses.addAll(read.records().stream().map(row->UUID.fromString((String)row.get("entity_id")).toString()).distinct().toList());if(witnesses.size()!=read.records().size())throw fail(WorkflowFailure.Code.INVALID_SAMPLE);return read;},read->read.records().size());
                    var batch=store.transaction(tenant,s->{
                    var current=context(s,tenant,owner,listed,resolver);if(current==null||current.checkpoint().pendingBatchId()!=null||!current.checkpoint().equals(c))throw fail(WorkflowFailure.Code.CONFLICT);
                    if(page.observedAt().isAfter(now())||page.observedAt().isBefore(current.task().cursor())||current.checkpoint().confirmedBatches()>=200||s.hostBatchCount(owner)>=200)throw fail(WorkflowFailure.Code.CAPACITY);
                    var b=new Batch(UUID.randomUUID(),c.scanId(),listed.workflowId(),listed.revision(),listed.digest(),listed.settings(),c.confirmedBatches()+1,c.nextCursor(),page.nextCursor(),page.complete(),page.observedAt(),page.records(),"READY",List.of(),null,now());
                    evaluate(current,b,inspection.observer());s.addHostBatch(owner,b);s.saveHostCheckpoint(owner,c.pending(b.id(),now()));return b;
                    });related[0]=batch.id();return batch;
                });
            }
            var admitted=store.transaction(tenant,s->{
                var current=context(s,tenant,owner,listed,resolver);if(current==null||current.checkpoint().pendingBatchId()==null)return Optional.<UUID>empty();
                var b=s.hostBatch(owner,current.checkpoint().pendingBatchId()).orElseThrow();require(current,b);
                if(b.state().equals("IN_FLIGHT")){
                    if(!b.updatedAt().plusSeconds(60).isAfter(now())){
                        s.finishHostBatch(owner,b.state("UNKNOWN",b.entityIds(),"OUTPUT_UNAVAILABLE",now()));failed(s,owner,current.task(),"OUTPUT_UNAVAILABLE");
                    }
                    return Optional.<UUID>empty();
                }
                if(!b.state().equals("READY")){failed(s,owner,current.task(),b.error());return Optional.<UUID>empty();}
                s.finishHostBatch(owner,b.state("IN_FLIGHT",b.entityIds(),null,now()));return Optional.of(b.id());
            });
            if(admitted.isEmpty())return;
            store.transaction(tenant,s->{
                var current=context(s,tenant,owner,listed,resolver);if(current==null)return null;
                var b=s.hostBatch(owner,admitted.get()).orElseThrow();require(current,b);if(!b.state().equals("IN_FLIGHT"))return null;
                var ids=new ArrayList<>(b.entityIds());boolean attempted=!ids.isEmpty();String error=null;
                try {
                    var evaluation=evaluate(current,b);
                    if(evaluation!=null)for(var row:evaluation.rows()){
                        if(!row.status().equals("ACCEPTED"))continue;
                        var fresh=context(s,tenant,owner,listed,resolver);if(fresh==null)throw fail(WorkflowFailure.Code.CONFLICT);
                        attempted=true;
                        var id=output.write(fresh.principal(),fresh.entry().definition(),b.settings(),b.id(),b.observedAt(),b.records().get(row.index()),row.steps().getLast().values(),"zabbix-jsonrpc");
                        if(!ids.contains(id))ids.add(id);
                    }
                    if(context(s,tenant,owner,listed,resolver)==null)throw fail(WorkflowFailure.Code.CONFLICT);
                }catch(RuntimeException failure){error=error(failure);}
                var result=b.state(error==null?"CONFIRMED":attempted?"UNKNOWN":"FAILED",ids,error,now());s.finishHostBatch(owner,result);
                if(error==null||attempted)s.hostSchedule(owner,listed.workflowId()).filter(v->v.state().equals("RUNNING")&&v.taskGeneration()==listed.generation()&&Objects.equals(v.activeScanId(),b.scanId())).ifPresent(v->s.saveHostSchedule(owner,v.consume(now())));
                if(error==null){
                    var advanced=current.checkpoint().confirm(result,now());s.saveHostCheckpoint(owner,advanced);
                    var a=current.task().authority();if(a!=null)a=a.consume();
                    s.saveTask(owner,new Task(listed.workflowId(),listed.revision(),listed.digest(),listed.settings(),listed.generation(),advanced.complete()?"STOPPED":"RUNNING",b.observedAt(),b.id(),now(),null,a));
                    if(advanced.complete())s.hostSchedule(owner,listed.workflowId()).filter(v->v.state().equals("RUNNING")&&v.taskGeneration()==listed.generation()&&Objects.equals(v.activeScanId(),advanced.scanId())).ifPresent(v->s.saveHostSchedule(owner,v.completed(advanced,now())));
                }else {
                    var t=current.task();var a=t.authority();if(a!=null&&attempted)a=a.consume();
                    failed(s,owner,new Task(t.workflowId(),t.revision(),t.digest(),t.settings(),t.generation(),t.state(),t.cursor(),t.cursorId(),t.updatedAt(),t.error(),a),error);
                }
                return null;
            });
        }catch(RuntimeException failure){
            // If journal finalization failed, IN_FLIGHT survives. It becomes UNKNOWN, never an automatic output retry.
            store.transaction(tenant,s->{var t=s.task(owner,listed.workflowId()).orElse(null);if(t!=null&&t.generation()==listed.generation()&&t.state().equals("RUNNING"))failed(s,owner,t,error(failure));return null;});
        }
    }
    private WorkflowEvaluation evaluate(Context c,Batch b){
        return evaluate(c,b,result->{});
    }
    private WorkflowEvaluation evaluate(Context c,Batch b,java.util.function.Consumer<WorkflowDiagnostics.Result> observed){
        require(c,b);var model=models.resolve(c.principal(),c.entry().definition().target());c.entry().definition().requireOutput(model);
        for(var record:b.records())if(new Authorizer().decide(c.principal(),ResourceRef.entity(c.principal().tenantId(),new com.acme.opsweave.sharedkernel.EntityId(UUID.fromString((String)record.get("entity_id")))),Permission.ENTITY_READ).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);
        var diagnostics=new WorkflowDiagnostics.Accumulator(c.entry().definition(),b.records().size(),Set.of("entity_id"),false);
        if(b.records().isEmpty()){observed.accept(diagnostics.result());return null;}
        var evaluation=WorkflowEvaluation.evaluate(c.entry().definition(),model,b.records());
        diagnostics.add(evaluation);
        if(evaluation.rejected()>0)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.result());
        output.validate(c.principal(),c.entry().definition(),b.settings(),b.records(),evaluation);observed.accept(diagnostics.result());return evaluation;
    }
    private static void require(Context c,Batch b){
        if(!b.scanId().equals(c.checkpoint().scanId())||!b.workflowId().equals(c.task().workflowId())||b.revision()!=c.task().revision()||!b.digest().equals(c.task().digest())||!b.settings().equals(c.task().settings())||!Objects.equals(b.beforeCursor(),c.checkpoint().nextCursor())||b.sequence()!=c.checkpoint().confirmedBatches()+1)throw fail(WorkflowFailure.Code.CONFLICT);
    }
    private Context context(WorkflowStore.Session s,TenantId tenant,String owner,Task listed,Function<Task,Principal> resolver){
        var t=s.task(owner,listed.workflowId()).orElse(null);
        if(t==null||!t.state().equals("RUNNING")||t.generation()!=listed.generation())return null;
        if(t.authority()!=null)t.authority().requireBudget(now());
        var schedule=s.hostSchedule(owner,t.workflowId()).orElse(null);
        if(schedule!=null&&schedule.taskGeneration()==t.generation()&&Objects.equals(schedule.activeScanId(),s.hostCheckpoint(owner,t.workflowId()).map(Checkpoint::scanId).orElse(null))){if(!schedule.state().equals("RUNNING"))throw fail(WorkflowFailure.Code.CONFLICT);if(schedule.sessionBatches()>=20)throw fail(WorkflowFailure.Code.EXECUTION_LIMIT);}
        var p=resolver.apply(t);if(p==null||!p.tenantId().equals(tenant)||!p.subjectId().value().equals(owner))throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
        authorize(p,t.workflowId());var e=entry(s,t);sources.require(s,p,e.definition().source());
        var c=s.hostCheckpoint(owner,t.workflowId()).orElseThrow(()->fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE));
        if(c.complete()||c.generation()!=t.generation()||c.revision()!=t.revision()||!c.digest().equals(t.digest()))throw fail(WorkflowFailure.Code.CONFLICT);
        return new Context(t,c,e,p);
    }
    private static WorkflowStore.Entry entry(WorkflowStore.Session s,Task t){
        var e=s.published(t.workflowId(),t.revision()).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));
        if(!e.digest().equals(t.digest())||!e.definition().target().entity()||!e.definition().source().kind().equals("ZABBIX_HOST")||e.definition().source().configuration()==null)throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        WorkflowOperators.builtIn().require(e.definition(),false);return e;
    }
    private static void authorize(Principal p,String id){var a=new Authorizer();if(a.decide(p,new ResourceRef(p.tenantId(),"workflow",id),Permission.SOURCE_SYNC).denied()||a.decide(p,new ResourceRef(p.tenantId(),"catalog","*"),Permission.ENTITY_READ).denied()||!p.has(Permission.ENTITY_MANAGE))throw fail(WorkflowFailure.Code.FORBIDDEN);}
    private void failed(WorkflowStore.Session s,String owner,Task t,String error){s.saveTask(owner,new Task(t.workflowId(),t.revision(),t.digest(),t.settings(),t.generation(),"FAILED",t.cursor(),t.cursorId(),now(),error,t.authority()));s.hostSchedule(owner,t.workflowId()).filter(v->v.state().equals("RUNNING")&&v.taskGeneration()==t.generation()).ifPresent(v->s.saveHostSchedule(owner,v.state("FAILED",v.generation(),v.taskGeneration(),error,now())));}
    private static String error(RuntimeException failure){if(failure instanceof IllegalArgumentException)return "INVALID_SAMPLE";if(failure instanceof WorkflowFailure f)return switch(f.code()){case FORBIDDEN,MODEL_CHANGED,OPERATOR_CHANGED,INVALID_SAMPLE,AUTHORIZATION_EXPIRED,AUTHORIZATION_REVOKED,EXECUTION_LIMIT->f.code().name();case CAPACITY->"BACKLOG_LIMIT";default->"SOURCE_UNAVAILABLE";};return "OUTPUT_UNAVAILABLE";}
    private static WorkflowFailure fail(WorkflowFailure.Code c){return new WorkflowFailure(c);}
}
