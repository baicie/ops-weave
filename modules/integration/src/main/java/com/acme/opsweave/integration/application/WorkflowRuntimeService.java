package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowRuntime.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;

/** Bounded writes serialized with stop. Delegated identities are resolved at the trusted boundary. */
public final class WorkflowRuntimeService {
    public record Batch(UUID id, Instant completedAt, Instant observedAt, WorkflowService.Batch input) {}
    public interface Batches { Optional<Batch> next(Principal p, WorkflowDefinition.Source source, Instant after, UUID afterId); }
    public interface Output {
        void validate(Principal p, WorkflowDefinition d, Settings settings, List<Map<String,Object>> input, WorkflowEvaluation evaluation);
        String write(Principal p, WorkflowDefinition d, Settings settings, UUID execution, Instant observedAt, Map<String,Object> input, Map<String,Object> values, String origin);
    }
    private final WorkflowStore store;
    private final WorkflowService.Models models;
    private final WorkflowService.Samples samples;
    private final Batches batches;
    private final Output output;
    private final Clock clock;
    private final WorkflowHostRuntimeService hostRuntime;
    private final WorkflowHostRuntimeService.Sources hostSources;
    private final Semaphore budget = new Semaphore(2);

    public WorkflowRuntimeService(WorkflowStore store, WorkflowService.Models models, WorkflowService.Samples samples, Batches batches, Output output, Clock clock) {
        this(store,models,samples,batches,output,clock,null);
    }
    public WorkflowRuntimeService(WorkflowStore store, WorkflowService.Models models, WorkflowService.Samples samples, Batches batches, Output output, Clock clock,WorkflowHostRuntimeService.Sources hostSources) {
        this.store=store; this.models=models; this.samples=samples; this.batches=batches; this.output=output; this.clock=clock;this.hostSources=hostSources;
        this.hostRuntime=hostSources==null?null:new WorkflowHostRuntimeService(store,models,output,hostSources,clock);
    }
    public Optional<WorkflowHostScan.Checkpoint> hostCheckpoint(Principal p,String id){authorize(p,id);return hostRuntime==null?Optional.empty():hostRuntime.checkpoint(p,id);}
    public List<WorkflowHostScan.Batch> hostBatches(Principal p,String id){authorize(p,id);return hostRuntime==null?List.of():hostRuntime.batches(p,id);}

    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private void authorizeRead(Principal p) {
        if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.SOURCE_SYNC).denied()) throw fail(WorkflowFailure.Code.FORBIDDEN);
    }
    private void authorize(Principal p, String id) {
        authorizeRead(p);
        var auth=new Authorizer();
        if(auth.decide(p,new ResourceRef(p.tenantId(),"workflow",id),Permission.SOURCE_SYNC).denied()
            || auth.decide(p,new ResourceRef(p.tenantId(),"catalog","*"),Permission.ENTITY_READ).denied()
            || !p.has(Permission.ENTITY_MANAGE)) throw fail(WorkflowFailure.Code.FORBIDDEN);
    }
    private WorkflowStore.Entry version(WorkflowStore.Session s, String id, int revision, String digest) {
        var e=s.published(id,revision).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));
        if(!e.digest().equals(digest)) throw fail(WorkflowFailure.Code.CONFLICT);
        if(!e.definition().target().entity() || e.definition().source().configuration()!=null && (hostRuntime==null||!e.definition().source().kind().equals("ZABBIX_HOST"))) throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        WorkflowOperators.builtIn().require(e.definition(),false);
        return e;
    }
    public List<Task> tasks(Principal p) { authorizeRead(p); return store.transaction(p.tenantId(),s->s.tasks(p.subjectId().value())); }
    public List<Execution> executions(Principal p) { authorizeRead(p); return store.transaction(p.tenantId(),s->s.executions(p.subjectId().value()).stream().limit(20).toList()); }
    /** Read one persisted execution without widening the owner or tenant scope. */
    public Execution execution(Principal p, UUID id) {
        authorizeRead(p);
        Objects.requireNonNull(id);
        return store.transaction(p.tenantId(), s -> s.execution(p.subjectId().value(), id)
            .orElseThrow(() -> fail(WorkflowFailure.Code.NOT_FOUND)));
    }

    public WorkflowRuntimeControl.Receipt controlReceipt(Principal p,UUID requestId) {
        authorizeRead(p);
        return store.transaction(p.tenantId(),s->{
            var receipt=s.runtimeControl(p.subjectId().value(),requestId).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));
            if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",receipt.task().workflowId()),Permission.SOURCE_SYNC).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);
            return receipt;
        });
    }
    /** A replay reads its immutable acknowledgement and never reissues authority or changes generation. */
    public WorkflowRuntimeControl.Receipt command(Principal p,WorkflowRuntimeControl.Command command,Supplier<WorkflowTaskAuthority> issue) {
        authorizeRead(p);
        return store.transaction(p.tenantId(),s->{
            var owner=p.subjectId().value();var prior=s.runtimeControl(owner,command.requestId());
            if(prior.isPresent()) {
                prior.get().require(command);
                if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",command.id()),Permission.SOURCE_SYNC).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);
                return prior.get();
            }
            boolean start=command.operation()!=WorkflowRuntimeControl.Operation.STOP;
            if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",command.id()),Permission.SOURCE_SYNC).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);
            // Twenty slots remain available to stop every possible running task; rejected stops consume no slot.
            if(s.runtimeControlCount(owner)>=(start?180:200))throw fail(WorkflowFailure.Code.CAPACITY);
            if(!start && !s.task(owner,command.id()).map(t->t.state().equals("RUNNING")).orElse(false))throw fail(WorkflowFailure.Code.CONFLICT);
            if(command.operation()==WorkflowRuntimeControl.Operation.RESUME){
                var old=s.task(owner,command.id()).orElseThrow(()->fail(WorkflowFailure.Code.CONFLICT));
                if(Set.of("RUNNING","ABANDONED").contains(old.state())||old.revision()!=command.revision()||!old.digest().equals(command.digest())||!old.settings().equals(command.settings())||s.hostCheckpoint(owner,command.id()).isEmpty())throw fail(WorkflowFailure.Code.CONFLICT);
            }
            var task=applyControl(s,p,command.id(),command.revision(),command.digest(),command.settings(),command.expectedGeneration(),start,issue,command.operation()==WorkflowRuntimeControl.Operation.RESUME);
            var receipt=new WorkflowRuntimeControl.Receipt(command.requestId(),command.operation(),command.commandDigest(),task.updatedAt(),task);
            receipt.require(command);s.addRuntimeControl(owner,receipt);return receipt;
        });
    }

    public Task control(Principal p,String id,int revision,String digest,Settings settings,long expected,boolean start) {
        return control(p,id,revision,digest,settings,expected,start,null);
    }
    /** Authority is supplied only by the server identity adapter, never parsed from a client command. */
    public Task control(Principal p,String id,int revision,String digest,Settings settings,long expected,boolean start,WorkflowTaskAuthority authority) {
        if(start)authorize(p,id);else authorizeRead(p);
        if(!start && authority!=null)throw new IllegalArgumentException();
        return store.transaction(p.tenantId(),s->applyControl(s,p,id,revision,digest,settings,expected,start,()->authority));
    }
    private Task applyControl(WorkflowStore.Session s,Principal p,String id,int revision,String digest,Settings settings,long expected,boolean start,Supplier<WorkflowTaskAuthority> issue) {
        return applyControl(s,p,id,revision,digest,settings,expected,start,issue,false);
    }
    private Task applyControl(WorkflowStore.Session s,Principal p,String id,int revision,String digest,Settings settings,long expected,boolean start,Supplier<WorkflowTaskAuthority> issue,boolean resume) {
        return applyControl(s,p,id,revision,digest,settings,expected,start,issue,resume,false);
    }
    public void requireScheduledMetadata(WorkflowStore.Session s,Principal p,WorkflowHostSchedule.Schedule schedule){
        requireScheduledMetadata(s,p,schedule,true);
    }
    public void requireScheduledMetadata(WorkflowStore.Session s,Principal p,WorkflowHostSchedule.Schedule schedule,boolean current){
        authorize(p,schedule.workflowId());var e=version(s,schedule.workflowId(),schedule.revision(),schedule.digest());
        if(hostSources==null||e.definition().source().configuration()==null)throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(current)hostSources.require(s,p,e.definition().source());else hostSources.requireMetadata(s,p,e.definition().source());var model=models.resolve(p,e.definition().target());e.definition().requireOutput(model);
    }
    /** Called within the schedule journal transaction; no new control receipt or grant is issued. */
    public Task scheduledControl(WorkflowStore.Session s,Principal p,String id,int revision,String digest,Settings settings,long expected,boolean start,Supplier<WorkflowTaskAuthority> issue,boolean resume){
        var e=version(s,id,revision,digest);if(e.definition().source().configuration()==null)throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        return applyControl(s,p,id,revision,digest,settings,expected,start,issue,resume,true);
    }
    private Task applyControl(WorkflowStore.Session s,Principal p,String id,int revision,String digest,Settings settings,long expected,boolean start,Supplier<WorkflowTaskAuthority> issue,boolean resume,boolean scheduled) {
        if(start) authorize(p,id); else authorizeRead(p);
        WorkflowDefinition.ref(id,revision); WorkflowDefinition.checkDigest(digest);
            var owner=p.subjectId().value(); var old=s.task(owner,id);
            var schedule=s.hostSchedule(owner,id).orElse(null);
            if(!scheduled&&start&&schedule!=null&&schedule.state().equals("RUNNING"))throw fail(WorkflowFailure.Code.CONFLICT);
            if(old.map(Task::generation).orElse(0L)!=expected) throw fail(WorkflowFailure.Code.CONFLICT);
            var time=now();
            if(!start) {
                var previous=old.orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));
                if(previous.state().equals("ABANDONED")||previous.revision()!=revision || !previous.digest().equals(digest) || !previous.settings().equals(settings)) throw fail(WorkflowFailure.Code.CONFLICT);
                var stopped=new Task(id,revision,digest,settings,expected+1,"STOPPED",previous.cursor(),previous.cursorId(),time,null,previous.authority());
                if(hostRuntime!=null)hostRuntime.stopped(s,owner,stopped); s.saveTask(owner,stopped);
                if(!scheduled&&schedule!=null&&schedule.state().equals("RUNNING"))s.saveHostSchedule(owner,schedule.state("STOPPED",schedule.generation()+1,stopped.generation(),null,time));
                return stopped;
            }
            if(old.isPresent()&&old.get().state().equals("ABANDONED")&&(resume||revision<=old.get().revision()))throw fail(WorkflowFailure.Code.CONFLICT);
            if(old.isPresent() && old.get().state().equals("RUNNING")) throw fail(WorkflowFailure.Code.CONFLICT);
            // Reserve a generation for stopping; legacy running tasks at the old ceiling can still stop.
            if(expected>=999_999) throw fail(WorkflowFailure.Code.CAPACITY);
            var e=version(s,id,revision,digest); var model=models.resolve(p,e.definition().target()); e.definition().requireOutput(model);
            if(!model.fields().stream().anyMatch(f->f.id().equals(settings.nameField())) || !e.definition().source().kind().equals("ZABBIX_HOST")) throw new IllegalArgumentException();
            if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),e.definition().source().instanceId()),Permission.SOURCE_SYNC).denied()) throw fail(WorkflowFailure.Code.FORBIDDEN);
            if(old.isEmpty() && s.tasks(owner).size()>=20) throw fail(WorkflowFailure.Code.CAPACITY);
            boolean replacing=old.isPresent()&&revision!=old.get().revision();
            if(replacing){
                var previous=old.get();var checkpoint=s.hostCheckpoint(owner,id).orElse(null);
                var pending=checkpoint==null?null:checkpoint.pendingBatchId();
                if(checkpoint!=null&&!new WorkflowQuality.Reference(id,previous.revision(),previous.digest()).matches(checkpoint.workflowId(),checkpoint.revision(),checkpoint.digest()))throw fail(WorkflowFailure.Code.CONFLICT);
                var oldEntry=version(s,id,previous.revision(),previous.digest());
                if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),oldEntry.definition().source().instanceId()),Permission.SOURCE_SYNC).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);
                WorkflowTaskVersionService.archive(s,owner,new WorkflowQuality.Reference(id,previous.revision(),previous.digest()),WorkflowQuality.Kind.HOST_SCAN,new WorkflowQuality.Task(previous.state(),previous.generation(),previous.updatedAt(),previous.error(),pending),new WorkflowQuality.Reference(id,revision,digest),time);
            }
            var authority=issue.get();
            if(authority!=null) { authority.requireBudget(now()); if(!scheduled&&authority.consumedBatches()!=0) throw new IllegalArgumentException(); }
            time=now();
            var task=new Task(id,revision,digest,settings,expected+1,"RUNNING",resume?old.orElseThrow().cursor():time,resume?old.orElseThrow().cursorId():new UUID(-1,-1),time,null,authority);
            if(e.definition().source().configuration()!=null)hostRuntime.initialize(s,p,task,resume,replacing);
            else if(resume||s.hostCheckpoint(owner,id).filter(c->!c.complete()).isPresent())throw fail(WorkflowFailure.Code.CONFLICT);
            s.saveTask(owner,task); return task;
    }
    public Execution execute(Principal p,String id,int revision,String digest,Settings settings,UUID previewId,List<Map<String,Object>> manual,UUID batchId) {
        authorize(p,id);
        if(!budget.tryAcquire()) throw fail(WorkflowFailure.Code.BUSY);
        try { return store.transaction(p.tenantId(),s->{
            var owner=p.subjectId().value(); var e=version(s,id,revision,digest); if(e.definition().source().configuration()!=null)throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE); var prior=s.execution(owner,previewId);
            if(prior.isPresent()) {
                var x=prior.get();
                if(!x.workflowId().equals(id) || x.revision()!=revision || !x.digest().equals(digest) || !x.settings().equals(settings) || !Objects.equals(x.syncRunId(),batchId)) throw fail(WorkflowFailure.Code.CONFLICT);
                if(batchId==null) {
                    if(manual==null) throw new IllegalArgumentException();
                    var original=s.run(owner,previewId).orElseThrow(()->fail(WorkflowFailure.Code.CONFLICT));
                    if(!WorkflowService.inputDigest(manual,null).equals(original.receipt().inputDigest())) throw fail(WorkflowFailure.Code.CONFLICT);
                } else if(manual!=null) throw new IllegalArgumentException();
                return x;
            }
            var preview=s.run(owner,previewId).filter(r->r.mode().equals("RUN") && r.workflowId().equals(id) && r.revision()==revision && r.receipt().digest().equals(digest)).orElseThrow(()->fail(WorkflowFailure.Code.PREVIEW_REQUIRED));
            if(!preview.receipt().publishable(now())) throw fail(WorkflowFailure.Code.PREVIEW_REQUIRED);
            WorkflowService.Batch batch=null; List<Map<String,Object>> values;
            if(e.definition().source().kind().equals("MANUAL_SAMPLE")) {
                if(manual==null || batchId!=null) throw new IllegalArgumentException(); values=manual;
            } else {
                if(manual!=null || batchId==null) throw new IllegalArgumentException(); batch=samples.read(p,e.definition().source(),batchId); complete(batch); values=batch.records();
            }
            if(!WorkflowService.inputDigest(values,batchId).equals(preview.receipt().inputDigest())) throw fail(WorkflowFailure.Code.PREVIEW_REQUIRED);
            return write(s,p,e,settings,previewId,preview.receipt().createdAt(),batchId,values,batch==null?"MANUAL_SAMPLE":batch.origin(),null,()->{});
        }); } finally { budget.release(); }
    }

    /** Legacy dev polling never executes an OIDC-authorized task under the development actor. */
    public void tick(Principal p) { tickOwner(p.tenantId(),p.subjectId().value(),false,t->p); }
    public void tickAuthorized(TenantId tenant,String owner,Function<Task,Principal> resolver) { tickOwner(tenant,owner,true,resolver); }
    public boolean hasAuthorizedTasks(TenantId tenant,String owner) {
        return store.transaction(tenant,s->s.tasks(owner).stream().anyMatch(t->t.state().equals("RUNNING") && t.authority()!=null));
    }
    private Principal resolve(TenantId tenant,String owner,Task task,Function<Task,Principal> resolver) {
        var p=resolver.apply(task);
        if(p==null || !p.tenantId().equals(tenant) || !p.subjectId().value().equals(owner)) throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
        authorize(p,task.workflowId()); return p;
    }
    private void tickOwner(TenantId tenant,String owner,boolean delegated,Function<Task,Principal> resolver) {
        if(!budget.tryAcquire()) return;
        try {
            var queue=new WorkflowDispatchQueue<Task>(clock);
            for(var listed:store.transaction(tenant,s->s.tasks(owner)))if(listed.state().equals("RUNNING")&&delegated==(listed.authority()!=null))queue.add(listed);
            for(var delivery=queue.poll();delivery!=null;delivery=queue.poll()) {
                var listed=delivery.task();
                if(!listed.state().equals("RUNNING") || delegated!=(listed.authority()!=null)) continue;
                if(hostRuntime!=null&&store.transaction(tenant,s->s.published(listed.workflowId(),listed.revision()).map(e->e.definition().source().configuration()!=null).orElse(false))){hostRuntime.tick(tenant,owner,listed,resolver,delivery.dispatch());continue;}
                store.transaction(tenant,s->{
                    var current=s.task(owner,listed.workflowId()).orElseThrow();
                    if(current.generation()!=listed.generation() || !current.state().equals("RUNNING") || delegated!=(current.authority()!=null)) return null;
                    WorkflowTaskAuthority authority=current.authority();
                    try {
                        if(authority!=null) authority.requireBudget(now());
                        var p=resolve(tenant,owner,current,resolver);
                        var e=version(s,current.workflowId(),current.revision(),current.digest());
                        if(new Authorizer().decide(p,ResourceRef.source(tenant,e.definition().source().instanceId()),Permission.SOURCE_SYNC).denied()) throw fail(WorkflowFailure.Code.FORBIDDEN);
                        var next=batches.next(p,e.definition().source(),current.cursor(),current.cursorId());
                        if(next.isEmpty()) return null;
                        var batch=next.get(); complete(batch.input());
                        if(batch.completedAt().isAfter(now()) || batch.observedAt().isAfter(batch.completedAt())
                            || batch.completedAt().isBefore(current.cursor())
                            || batch.completedAt().equals(current.cursor()) && batch.id().toString().compareTo(current.cursorId().toString())<=0) throw fail(WorkflowFailure.Code.INVALID_SAMPLE);
                        var key=UUID.nameUUIDFromBytes((tenant.value()+":"+owner+":"+e.digest()+":"+current.generation()+":"+batch.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        Runnable guard=()->{ resolve(tenant,owner,current,resolver); };
                        var result=s.execution(owner,key).orElseGet(()->write(s,p,e,current.settings(),key,batch.observedAt(),batch.id(),batch.input().records(),batch.input().origin(),current.authority()==null?null:current.authority().id(),guard));
                        if(authority!=null) authority=authority.consume();
                        boolean confirmed=result.state().equals("SUCCEEDED");
                        s.saveTask(owner,new Task(current.workflowId(),current.revision(),current.digest(),current.settings(),current.generation(),confirmed?"RUNNING":"FAILED",confirmed?batch.completedAt():current.cursor(),confirmed?batch.id():current.cursorId(),now(),result.error(),authority));
                    } catch(RuntimeException failure) {
                        s.saveTask(owner,new Task(current.workflowId(),current.revision(),current.digest(),current.settings(),current.generation(),"FAILED",current.cursor(),current.cursorId(),now(),error(failure),authority));
                    }
                    return null;
                });
            }
        } finally { budget.release(); }
    }
    private Execution write(WorkflowStore.Session s,Principal p,WorkflowStore.Entry e,Settings settings,UUID key,Instant time,UUID batchId,List<Map<String,Object>> values,String origin,UUID authorizationId,Runnable guard) {
        if(s.executions(p.subjectId().value()).size()>=200) throw fail(WorkflowFailure.Code.CAPACITY);
        var model=models.resolve(p,e.definition().target()); var evaluation=WorkflowEvaluation.evaluate(e.definition(),model,values);
        if(evaluation.rejected()>0) throw fail(WorkflowFailure.Code.INVALID_SAMPLE);
        guard.run();
        try { output.validate(p,e.definition(),settings,values,evaluation); } catch(IllegalArgumentException invalid) { throw fail(WorkflowFailure.Code.INVALID_SAMPLE); }
        var ids=new ArrayList<String>(); String failureCode=null;
        for(var row:evaluation.rows()) {
            if(!row.status().equals("ACCEPTED")) continue;
            try { guard.run(); } catch(RuntimeException revoked) { failureCode=error(revoked); break; }
            try { ids.add(output.write(p,e.definition(),settings,key,time,values.get(row.index()),row.steps().getLast().values(),origin)); }
            catch(RuntimeException unavailable) { failureCode="OUTPUT_UNAVAILABLE"; break; }
        }
        if(failureCode==null) try { guard.run(); } catch(RuntimeException revoked) { failureCode=error(revoked); }
        var result=new Execution(key,e.definition().id(),e.definition().revision(),e.digest(),settings,origin,batchId,time,failureCode==null?"SUCCEEDED":"FAILED",evaluation.accepted(),evaluation.rejected(),evaluation.filtered(),ids,failureCode,authorizationId);
        s.addExecution(p.subjectId().value(),result); return result;
    }
    private static String error(RuntimeException failure) {
        if(!(failure instanceof WorkflowFailure f)) return "RUNTIME_UNAVAILABLE";
        return switch(f.code()) {
            case FORBIDDEN,MODEL_CHANGED,OPERATOR_CHANGED,INVALID_SAMPLE,AUTHORIZATION_EXPIRED,AUTHORIZATION_REVOKED,EXECUTION_LIMIT -> f.code().name();
            case CAPACITY -> "BACKLOG_LIMIT";
            default -> "SOURCE_UNAVAILABLE";
        };
    }
    private static void complete(WorkflowService.Batch b) {
        if(!b.sourceStatus().equals("SUCCEEDED") || b.truncated() || b.missingRaw()!=0 || b.retainedCount()!=b.records().size()) throw fail(WorkflowFailure.Code.INVALID_SAMPLE);
    }
    private static WorkflowFailure fail(WorkflowFailure.Code c) { return new WorkflowFailure(c); }
}
