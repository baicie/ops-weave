package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricStream.*;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.*;

/** Single-executor bounded windows. Unknown output can only be verified, never automatically resent. */
public final class WorkflowMetricStreamService {
    public interface Source {List<Map<String,Object>> read(Principal p,WorkflowDefinition.Source source,Instant from,Instant till);}
    public record Status(Task task,List<Batch> batches,WorkflowRecovery.VersionControl control){public Status{batches=List.copyOf(batches);}public Status(Task task,List<Batch> batches){this(task,batches,null);}}
    private record Context(Task task,WorkflowDefinition definition,WorkflowExecutionPlan plan,Principal principal){}
    private final WorkflowStore store;private final WorkflowService workflows;private final Source source;private final WorkflowMetricOutputService.Sink sink;private final Semaphore budget;private final Clock clock;
    public WorkflowMetricStreamService(WorkflowStore store,WorkflowService workflows,Source source,WorkflowMetricOutputService.Sink sink,Semaphore budget,Clock clock){this.store=store;this.workflows=workflows;this.source=source;this.sink=sink;this.budget=budget;this.clock=clock;}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static WorkflowFailure fail(WorkflowFailure.Code code){return new WorkflowFailure(code);}
    private void scope(Principal p,String id){workflows.authorize(p);if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",id),Permission.SOURCE_SYNC).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);}
    private WorkflowStore.Entry version(Principal p,WorkflowStore.Session s,String id,int revision,String digest,boolean execute){
        scope(p,id);var e=s.published(id,revision).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));if(!e.digest().equals(digest))throw fail(WorkflowFailure.Code.CONFLICT);
        var d=e.definition();if(!d.source().kind().equals("ZABBIX_METRIC")||d.source().configuration()==null||d.source().metric()==null||d.target().mappingPin()==null)throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(execute)workflows.executionPlan(p,s,e);else workflows.accessibleVersion(p,s,id,revision);return e;
    }
    public Status status(Principal p,String id){scope(p,id);WorkflowDefinition.ref(id,1);return store.transaction(p.tenantId(),s->{var task=s.metricStreamTask(p.subjectId().value(),id).orElse(null);if(task!=null)version(p,s,id,task.revision(),task.digest(),false);var batches=s.metricStreamBatches(p.subjectId().value(),id);for(var batch:batches){version(p,s,id,batch.revision(),batch.digest(),false);if(!p.tenantId().value().equals(batch.labels().get("tenant_id"))||!WorkflowDefinition.hash(List.of(p.subjectId().value())).substring(7).equals(batch.labels().get("owner_scope")))throw fail(WorkflowFailure.Code.FORBIDDEN);}return new Status(task,batches);});}
    public Status versionStatus(Principal p,String id,int revision){scope(p,id);WorkflowDefinition.ref(id,revision);return store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var e=s.published(id,revision).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));version(p,s,id,revision,e.digest(),false);var current=s.metricStreamTask(owner,id).orElse(null);var selected=current!=null&&current.revision()==revision&&current.digest().equals(e.digest())?current:null;var closed=s.recoveryForVersion(owner,id,revision,e.digest());if(closed.isPresent()){if(closed.get().kind()!=WorkflowQuality.Kind.METRIC_STREAM)throw new IllegalStateException("Invalid recovery kind");WorkflowRecoveryService.parent(p,s,closed.get());}var rows=s.qualityMetricBatches(owner,id,revision,e.digest()).stream().limit(20).toList();for(var b:rows){if(!p.tenantId().value().equals(b.labels().get("tenant_id"))||!WorkflowDefinition.hash(List.of(owner)).substring(7).equals(b.labels().get("owner_scope")))throw fail(WorkflowFailure.Code.FORBIDDEN);}long generation=current==null?0:current.generation();boolean start=closed.isEmpty()&&(current==null||WorkflowTaskVersionService.replaceable(s,owner,new WorkflowQuality.Reference(id,current.revision(),current.digest()),WorkflowQuality.Kind.METRIC_STREAM,new WorkflowQuality.Task(current.state(),current.generation(),current.updatedAt(),current.error(),current.pendingBatchId()),revision))&&generation<999_999;return new Status(selected,rows,new WorkflowRecovery.VersionControl(generation,start,closed.isPresent()));});}
    public Receipt receipt(Principal p,UUID id){workflows.authorize(p);return store.transaction(p.tenantId(),s->{var receipt=s.metricStreamControl(p.subjectId().value(),id).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));scope(p,receipt.task().workflowId());return receipt;});}
    public Receipt command(Principal p,Command command,Supplier<WorkflowTaskAuthority> issue){
        scope(p,command.id());return store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var prior=s.metricStreamControl(owner,command.requestId());if(prior.isPresent()){prior.get().require(command);return prior.get();}
            boolean stopping=command.operation()==Operation.STOP;if(s.metricStreamControlCount(owner)>=(stopping?200:180))throw fail(WorkflowFailure.Code.CAPACITY);
            var old=s.metricStreamTask(owner,command.id()).orElse(null);if((old==null?0:old.generation())!=command.expectedGeneration())throw fail(WorkflowFailure.Code.CONFLICT);
            var time=now();Task next;
            if(stopping){if(old==null||!old.state().equals("RUNNING")||old.revision()!=command.revision()||!old.digest().equals(command.digest()))throw fail(WorkflowFailure.Code.CONFLICT);next=old.state(old.generation()+1,"STOPPED",null,time,old.sessionBatches(),old.authority());}
            else {
                if(command.expectedGeneration()>=999_999)throw fail(WorkflowFailure.Code.CAPACITY);version(p,s,command.id(),command.revision(),command.digest(),true);
                if(command.operation()==Operation.START){if(old!=null&&!WorkflowTaskVersionService.replaceable(s,owner,new WorkflowQuality.Reference(old.workflowId(),old.revision(),old.digest()),WorkflowQuality.Kind.METRIC_STREAM,new WorkflowQuality.Task(old.state(),old.generation(),old.updatedAt(),old.error(),old.pendingBatchId()),command.revision()))throw fail(WorkflowFailure.Code.CONFLICT);if(old==null&&s.metricStreamTasks(owner).size()>=20)throw fail(WorkflowFailure.Code.CAPACITY);}
                else if(old==null||Set.of("RUNNING","ABANDONED").contains(old.state())||old.revision()!=command.revision()||!old.digest().equals(command.digest()))throw fail(WorkflowFailure.Code.CONFLICT);
                boolean fresh=command.operation()==Operation.START;UUID pending=fresh?null:old.pendingBatchId();if(pending!=null){var batch=s.metricStreamBatch(owner,pending).orElseThrow();if(!batch.state().equals("FAILED"))throw fail(WorkflowFailure.Code.CONFLICT);pending=null;}
                if(fresh&&old!=null){workflows.accessibleVersion(p,s,old.workflowId(),old.revision());WorkflowTaskVersionService.archive(s,owner,new WorkflowQuality.Reference(old.workflowId(),old.revision(),old.digest()),WorkflowQuality.Kind.METRIC_STREAM,new WorkflowQuality.Task(old.state(),old.generation(),old.updatedAt(),old.error(),old.pendingBatchId()),new WorkflowQuality.Reference(command.id(),command.revision(),command.digest()),time);}
                var authority=issue.get();if(authority!=null){authority.requireBudget(time);if(authority.consumedBatches()!=0)throw new IllegalArgumentException();}
                time=now();if(old!=null&&time.isBefore(old.updatedAt()))throw fail(WorkflowFailure.Code.CONFLICT);var cursor=fresh?Instant.ofEpochSecond(time.getEpochSecond()-WorkflowMetricStream.WINDOW_SECONDS):old.cursor();
                next=new Task(command.id(),command.revision(),command.digest(),command.expectedGeneration()+1,"RUNNING",cursor,pending,fresh?0:old.confirmedWindows(),fresh?0:old.confirmedPoints(),0,time,null,authority);
            }
            var receipt=new Receipt(command.requestId(),command.operation(),command.commandDigest(),next.updatedAt(),next);receipt.require(command);s.saveMetricStreamTask(owner,next);s.addMetricStreamControl(owner,receipt);return receipt;
        });
    }
    private Context context(WorkflowStore.Session s,TenantId tenant,String owner,String id,long generation,Function<Task,Principal> resolver){
        var task=s.metricStreamTask(owner,id).orElse(null);if(task==null||task.generation()!=generation||!task.state().equals("RUNNING"))return null;
        var p=resolver.apply(task);if(p==null||!p.tenantId().equals(tenant)||!p.subjectId().value().equals(owner))throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
        if(task.authority()!=null)task.authority().requireBudget(now());if(task.sessionBatches()>=WorkflowMetricStream.MAX_BATCHES)throw fail(WorkflowFailure.Code.EXECUTION_LIMIT);
        var e=version(p,s,id,task.revision(),task.digest(),true);return new Context(task,e.definition(),workflows.executionPlan(p,s,e),p);
    }
    public void tick(Principal p){tickOwner(p.tenantId(),p.subjectId().value(),false,t->p);}
    public void tickAuthorized(TenantId tenant,String owner,Function<Task,Principal> resolver){tickOwner(tenant,owner,true,resolver);}
    public boolean hasAuthorizedTasks(TenantId tenant,String owner){return store.transaction(tenant,s->s.metricStreamTasks(owner).stream().anyMatch(t->t.authority()!=null&&t.state().equals("RUNNING")));}
    private void tickOwner(TenantId tenant,String owner,boolean authorized,Function<Task,Principal> resolver){
        if(!budget.tryAcquire())return;
        try{
            var queue=new WorkflowDispatchQueue<Task>(clock);
            for(var task:store.transaction(tenant,s->s.metricStreamTasks(owner)))if(task.state().equals("RUNNING")&&(task.authority()!=null)==authorized)queue.add(task);
            for(var delivery=queue.poll();delivery!=null;delivery=queue.poll()){var task=delivery.task();try{tickOne(tenant,owner,task,resolver,delivery.dispatch());}catch(RuntimeException failure){String error=failure instanceof WorkflowFailure f&&WorkflowMetricStream.ERRORS.contains(f.code().name())?f.code().name():"RUNTIME_UNAVAILABLE";failTask(tenant,owner,task,error);}}
        }finally{budget.release();}
    }
    private void failTask(TenantId tenant,String owner,Task expected,String error){store.transaction(tenant,s->{var task=s.metricStreamTask(owner,expected.workflowId()).orElse(null);if(task!=null&&task.generation()==expected.generation()&&task.state().equals("RUNNING"))s.saveMetricStreamTask(owner,task.state(task.generation(),"FAILED",error,now(),task.sessionBatches(),task.authority()));return null;});}
    private <T>T inspect(TenantId tenant,String owner,Task task,Instant from,Instant till,UUID related,Function<Task,Principal> resolver,WorkflowDiagnostics.Dispatch dispatch,java.util.function.BiFunction<Principal,WorkflowDiagnosticRecorder.Inspection,T> operation){
        return new WorkflowDiagnosticRecorder(store,clock).inspectSource(tenant,owner,new WorkflowQuality.Reference(task.workflowId(),task.revision(),task.digest()),WorkflowQuality.Kind.METRIC_STREAM,task.generation(),from,till,related,s->{var current=s.metricStreamTask(owner,task.workflowId()).orElse(null);if(current==null||current.generation()!=task.generation()||!current.state().equals("RUNNING")||current.pendingBatchId()!=null)return false;var p=resolver.apply(current);if(p==null||!p.tenantId().equals(tenant)||!p.subjectId().value().equals(owner))return false;version(p,s,current.workflowId(),current.revision(),current.digest(),false);return true;},dispatch,(inspection,started)->{var p=resolver.apply(task);if(p==null)throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);return operation.apply(p,inspection);});
    }
    private void tickOne(TenantId tenant,String owner,Task expected,Function<Task,Principal> resolver,WorkflowDiagnostics.Dispatch dispatch){
        var context=store.transaction(tenant,s->context(s,tenant,owner,expected.workflowId(),expected.generation(),resolver));if(context==null)return;var task=context.task();
        if(task.pendingBatchId()!=null){store.transaction(tenant,s->{var current=s.metricStreamTask(owner,task.workflowId()).orElseThrow();if(current.generation()!=task.generation()||!current.state().equals("RUNNING"))return null;var batch=s.metricStreamBatch(owner,current.pendingBatchId()).orElseThrow();if(batch.state().equals("IN_FLIGHT")&&now().isBefore(batch.createdAt().plusSeconds(60)))return null;if(batch.state().equals("IN_FLIGHT")){batch=batch.finish("UNKNOWN","OUTPUT_UNCONFIRMED",now());s.finishMetricStreamBatch(owner,batch);}s.saveMetricStreamTask(owner,current.state(current.generation(),"FAILED",batch.state().equals("FAILED")?"OUTPUT_REJECTED":"OUTPUT_UNCONFIRMED",now(),current.sessionBatches(),current.authority()));return null;});return;}
        var till=task.cursor().plusSeconds(WorkflowMetricStream.WINDOW_SECONDS);if(now().isBefore(till.plusSeconds(WorkflowMetricStream.SETTLE_SECONDS)))return;
        if(task.cursor().isBefore(now().minusSeconds(86400)))throw fail(WorkflowFailure.Code.INVALID_SAMPLE);
        if(store.transaction(tenant,s->s.metricStreamBatchCount(owner))>=200)throw fail(WorkflowFailure.Code.CAPACITY);
        // Recheck only the last confirmed minute. It never erases a prior proof or advances a cursor.
        Batch original=null;WorkflowMetricBatches.Prepared prepared=null;Instant from=task.cursor();int latePoints=0;
        if(task.confirmedWindows()>0){
            original=store.transaction(tenant,s->s.latestConfirmedMetricWindow(owner,task.workflowId(),task.revision(),task.digest(),task.cursor()).orElseThrow(()->fail(WorkflowFailure.Code.WINDOW_INCOMPLETE)));
            var previous=original;
            var recent=inspect(tenant,owner,task,previous.from(),previous.till(),previous.id(),resolver,dispatch,(p,inspection)->{var rows=inspection.source().read(()->source.read(p,context.definition().source(),previous.from(),previous.till()));return WorkflowMetricBatches.prepare(p,context.definition(),context.plan(),rows,previous.from(),previous.till(),inspection.observer());});
            var priorTimes=new HashSet<>(original.timestamps());var priorSamples=recent.batch().samples().stream().filter(point->priorTimes.contains(point.timestampMillis())).toList();
            if(!recent.batch().labels().equals(original.labels())||priorSamples.size()!=priorTimes.size()||!WorkflowMetricOutput.batchDigest(new MetricWriteBatch(original.labels(),priorSamples,0)).equals(original.batchDigest()))throw fail(WorkflowFailure.Code.SOURCE_WINDOW_CHANGED);
            latePoints=recent.batch().samples().size()-priorTimes.size();
            if(latePoints>0){prepared=recent;from=original.from();till=original.till();}else original=null;
        }
        // No upstream IO is nested in the workflow transaction. Generation and all pins are rechecked below.
        if(prepared==null){
            var current=store.transaction(tenant,s->context(s,tenant,owner,task.workflowId(),task.generation(),resolver));if(current==null||!current.task().equals(task))return;
            var windowFrom=from;var windowTill=till;
            prepared=inspect(tenant,owner,task,windowFrom,windowTill,null,resolver,dispatch,(p,inspection)->{var rows=inspection.source().read(()->source.read(p,current.definition().source(),windowFrom,windowTill));return WorkflowMetricBatches.prepare(p,current.definition(),current.plan(),rows,windowFrom,windowTill,inspection.observer());});
        }
        var payload=prepared.batch();var time=now();
        var batch=new Batch(UUID.randomUUID(),task.workflowId(),task.revision(),task.digest(),from,till,time,time,payload.samples().isEmpty()?"CONFIRMED":"IN_FLIGHT",prepared.inputCount(),prepared.filtered(),payload.collapsedPoints(),payload.labels(),WorkflowMetricOutput.batchDigest(payload),payload.samples().stream().map(MetricWriteBatch.Sample::timestampMillis).toList(),null,original==null?null:original.id(),latePoints);
        if(original!=null)batch.requireReconciliation(original);
        boolean admitted=store.transaction(tenant,s->{var current=context(s,tenant,owner,task.workflowId(),task.generation(),resolver);if(current==null||!current.task().equals(task))return false;if(s.metricStreamBatchCount(owner)>=200)throw fail(WorkflowFailure.Code.CAPACITY);
            if(batch.reconcilesBatchId()!=null)batch.requireReconciliation(s.metricStreamBatch(owner,batch.reconcilesBatchId()).orElseThrow());
            s.addMetricStreamBatch(owner,batch);var authority=task.authority()==null?null:task.authority().consume();var pending=task.pending(batch.id(),now()).state(task.generation(),"RUNNING",null,now(),task.sessionBatches()+1,authority);
            s.saveMetricStreamTask(owner,batch.state().equals("CONFIRMED")?pending.confirm(batch,now()):pending);return true;});
        if(!admitted||batch.state().equals("CONFIRMED"))return;
        // The committed IN_FLIGHT proof precedes IO. Holding the tenant journal lock serializes this local executor with STOP.
        store.transaction(tenant,s->{var current=s.metricStreamTask(owner,task.workflowId()).orElseThrow();if(current.generation()!=task.generation()||!current.state().equals("RUNNING")||!batch.id().equals(current.pendingBatchId()))return null;
            var p=resolver.apply(current);if(p==null||!p.tenantId().equals(tenant)||!p.subjectId().value().equals(owner))throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
            if(current.authority()!=null){var a=current.authority();if(now().isBefore(a.issuedAt())||!now().isBefore(a.expiresAt()))throw fail(WorkflowFailure.Code.AUTHORIZATION_EXPIRED);}
            version(p,s,task.workflowId(),task.revision(),task.digest(),true);
            Batch result;
            try{sink.write(payload);result=batch.finish("CONFIRMED",null,now());}
            catch(WorkflowMetricOutputService.OutputFailure failure){result=batch.finish(failure.unknown()?"UNKNOWN":"FAILED",failure.getMessage(),now());}
            catch(RuntimeException unknown){result=batch.finish("UNKNOWN","OUTPUT_UNCONFIRMED",now());}
            s.finishMetricStreamBatch(owner,result);s.saveMetricStreamTask(owner,result.state().equals("CONFIRMED")?current.confirm(result,now()):current.state(current.generation(),"FAILED",result.error(),now(),current.sessionBatches(),current.authority()));return null;
        });
    }
    /** Reads only the original series/timestamps. It cannot read new source values, issue POST or renew authority. */
    public Status verify(Principal p,String id,UUID batchId){
        var status=status(p,id);var task=status.task();if(task==null||task.state().equals("ABANDONED")||!Objects.equals(task.pendingBatchId(),batchId))throw fail(WorkflowFailure.Code.CONFLICT);
        var original=store.transaction(p.tenantId(),s->s.metricStreamBatch(p.subjectId().value(),batchId).orElseThrow());if(!Set.of("IN_FLIGHT","UNKNOWN").contains(original.state()))throw fail(WorkflowFailure.Code.CONFLICT);
        if(!budget.tryAcquire())throw fail(WorkflowFailure.Code.BUSY);
        boolean confirmed=false;try{try{var found=sink.read(original.labels(),original.timestamps());confirmed=found.size()==original.timestamps().size()&&WorkflowMetricOutput.batchDigest(new MetricWriteBatch(original.labels(),found,0)).equals(original.batchDigest());}catch(RuntimeException unavailable){/* Preserve proof and cursor. */}}finally{budget.release();}
        final boolean matches=confirmed;store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var current=s.metricStreamTask(owner,id).orElseThrow();var batch=s.metricStreamBatch(owner,batchId).orElseThrow();if(current.state().equals("ABANDONED")||current.generation()!=task.generation()||!Objects.equals(current.pendingBatchId(),batchId))return null;if(Set.of("CONFIRMED","FAILED").contains(batch.state()))return null;
            var result=batch.finish(matches?"CONFIRMED":"UNKNOWN",matches?null:"OUTPUT_UNCONFIRMED",now());s.finishMetricStreamBatch(owner,result);
            if(matches)s.saveMetricStreamTask(owner,current.confirm(result,now()));else if(current.state().equals("RUNNING"))s.saveMetricStreamTask(owner,current.state(current.generation(),"FAILED","OUTPUT_UNCONFIRMED",now(),current.sessionBatches(),current.authority()));return null;});return status(p,id);
    }
}
