package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowLogStream.*;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.integration.api.WorkflowLogWindowSink;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.*;

/** Single-executor bounded windows. Unknown output can only be verified, never automatically resent. */
public final class WorkflowLogStreamService {
    public interface Source {List<Map<String,Object>> read(Principal p,WorkflowDefinition.Source source,Instant from,Instant till);}
    public record Status(Task task,List<Batch> batches,WorkflowRecovery.VersionControl control,Boolean resumeAllowed,List<UUID> uncertainBatchIds){public Status{batches=List.copyOf(batches);if(uncertainBatchIds!=null){uncertainBatchIds=List.copyOf(uncertainBatchIds);if(uncertainBatchIds.size()>20||new HashSet<>(uncertainBatchIds).size()!=uncertainBatchIds.size()||!batches.stream().filter(b->b.state().equals("FAILED")).map(Batch::id).toList().containsAll(uncertainBatchIds))throw new IllegalArgumentException();}}public Status(Task task,List<Batch> batches){this(task,batches,null,null,null);}}
    public record Data(UUID batchId,Instant readAt,String storage,int expectedRecords,boolean complete,int afterIndex,Integer nextIndex,List<WorkflowLogWindow.Record> records){
        public Data{Objects.requireNonNull(batchId);Objects.requireNonNull(readAt);records=List.copyOf(records);if(!"clickhouse".equals(storage)||expectedRecords<0||expectedRecords>WorkflowLogWindow.MAX_RECORDS||records.size()>50||afterIndex< -1||afterIndex>=WorkflowLogWindow.MAX_RECORDS
            ||nextIndex!=null&&(records.size()!=50||nextIndex!=records.getLast().index()))throw new IllegalArgumentException();int previous=afterIndex;for(var row:records){if(row.index()<=previous)throw new IllegalArgumentException();previous=row.index();}}
    }
    private record Checked(WorkflowLogWindow.Batch prepared,List<Map<String,Object>> rows) {}
    private record Context(Task task,WorkflowDefinition definition,WorkflowExecutionPlan plan,Principal principal){}
    private final WorkflowStore store;private final WorkflowService workflows;private final Source source;private final WorkflowLogWindowSink sink;private final Semaphore budget;private final Clock clock;
    public WorkflowLogStreamService(WorkflowStore store,WorkflowService workflows,Source source,WorkflowLogWindowSink sink,Semaphore budget,Clock clock){this.store=store;this.workflows=workflows;this.source=source;this.sink=sink;this.budget=budget;this.clock=clock;}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static WorkflowFailure fail(WorkflowFailure.Code code){return new WorkflowFailure(code);}
    private void scope(Principal p,String id){workflows.authorize(p);if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",id),Permission.SOURCE_SYNC).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);}
    private WorkflowStore.Entry version(Principal p,WorkflowStore.Session s,String id,int revision,String digest,boolean execute){
        scope(p,id);var e=s.published(id,revision).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));if(!e.digest().equals(digest))throw fail(WorkflowFailure.Code.CONFLICT);
        var d=e.definition();if(!d.source().kind().equals("ZABBIX_LOG")||d.source().configuration()==null||d.source().log()==null||!d.target().kind().equals("LOG"))throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        if(execute){permission(p,id,Permission.LOG_WRITE);workflows.executionPlan(p,s,e);}else workflows.accessibleVersion(p,s,id,revision);return e;
    }
    public Status status(Principal p,String id){scope(p,id);WorkflowDefinition.ref(id,1);return store.transaction(p.tenantId(),s->{var task=s.logStreamTask(p.subjectId().value(),id).orElse(null);if(task!=null)version(p,s,id,task.revision(),task.digest(),false);var batches=s.logStreamBatches(p.subjectId().value(),id);for(var batch:batches)version(p,s,id,batch.revision(),batch.digest(),false);return new Status(task,batches);});}
    private void permission(Principal p,String id,Permission permission){if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"log","workflow."+id),permission).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);}
    private static WorkflowLogOutput.Scope outputScope(Principal p,UUID batchId,String id,int revision,String digest){return new WorkflowLogOutput.Scope(p.tenantId().value(),WorkflowDefinition.hash(List.of(p.subjectId().value())).substring(7),batchId,id,revision,digest);}
    private boolean matches(Principal p,Batch batch,List<WorkflowLogWindow.Record> rows){
        return rows.stream().map(WorkflowLogWindow.Record::index).toList().equals(batch.indices())&&rows.stream().map(WorkflowLogWindow.Record::position).toList().equals(batch.acceptedPositions())
            &&WorkflowLogWindow.recordsDigest(outputScope(p,batch.id(),batch.workflowId(),batch.revision(),batch.digest()),batch.from(),batch.till(),batch.inputCount(),batch.filtered(),batch.deduplicated(),rows).equals(batch.batchDigest());
    }
    public Status versionStatus(Principal p,String id,int revision){scope(p,id);WorkflowDefinition.ref(id,revision);return store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var e=s.published(id,revision).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));version(p,s,id,revision,e.digest(),false);var current=s.logStreamTask(owner,id).orElse(null);var selected=current!=null&&current.revision()==revision&&current.digest().equals(e.digest())?current:null;var closed=s.recoveryForVersion(owner,id,revision,e.digest());if(closed.isPresent()){if(closed.get().kind()!=WorkflowQuality.Kind.LOG_STREAM)throw new IllegalStateException("Invalid recovery kind");WorkflowRecoveryService.parent(p,s,closed.get());}var rows=s.qualityLogBatches(owner,id,revision,e.digest()).stream().limit(20).toList();var uncertain=rows.stream().filter(b->WorkflowLogOutcome.legacyUncertain(s,owner,b)).map(Batch::id).toList();long generation=current==null?0:current.generation();boolean start=closed.isEmpty()&&(current==null||WorkflowTaskVersionService.replaceable(s,owner,new WorkflowQuality.Reference(id,current.revision(),current.digest()),WorkflowQuality.Kind.LOG_STREAM,new WorkflowQuality.Task(current.state(),current.generation(),current.updatedAt(),current.error(),current.pendingBatchId()),revision))&&generation<999_999;boolean resume=selected!=null&&Set.of("STOPPED","FAILED").contains(selected.state())&&generation<999_999&&(selected.pendingBatchId()==null||s.logStreamBatch(owner,selected.pendingBatchId()).filter(b->b.state().equals("FAILED")&&s.logStreamRejectionKnown(owner,b.id())).isPresent());return new Status(selected,rows,new WorkflowRecovery.VersionControl(generation,start,closed.isPresent()),resume,uncertain);});}
    public Receipt receipt(Principal p,UUID id){workflows.authorize(p);return store.transaction(p.tenantId(),s->{var receipt=s.logStreamControl(p.subjectId().value(),id).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));scope(p,receipt.task().workflowId());return receipt;});}
    public Data data(Principal p,String id,UUID batchId){
        return data(p,id,batchId,-1);
    }
    public Data data(Principal p,String id,UUID batchId,int afterIndex){
        if(afterIndex< -1||afterIndex>=WorkflowLogWindow.MAX_RECORDS)throw new IllegalArgumentException();
        scope(p,id);permission(p,id,Permission.LOG_READ);var batch=store.transaction(p.tenantId(),s->{var b=s.logStreamBatch(p.subjectId().value(),batchId).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));if(!b.workflowId().equals(id))throw fail(WorkflowFailure.Code.NOT_FOUND);version(p,s,id,b.revision(),b.digest(),false);return b;});
        if(afterIndex!=-1&&!batch.indices().contains(afterIndex))throw new IllegalArgumentException();
        if(!budget.tryAcquire())throw fail(WorkflowFailure.Code.BUSY);
        try{var rows=sink.read(outputScope(p,batchId,id,batch.revision(),batch.digest()));permission(p,id,Permission.LOG_READ);var remaining=rows.stream().filter(r->r.index()>afterIndex).toList();var page=remaining.stream().limit(50).toList();return new Data(batchId,now(),"clickhouse",batch.indices().size(),matches(p,batch,rows),afterIndex,remaining.size()>50?page.getLast().index():null,page);}
        catch(WorkflowLogOutputService.OutputFailure unavailable){throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{budget.release();}
    }
    public Receipt command(Principal p,Command command,Supplier<WorkflowTaskAuthority> issue){
        scope(p,command.id());return store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var prior=s.logStreamControl(owner,command.requestId());if(prior.isPresent()){prior.get().require(command);return prior.get();}
            boolean stopping=command.operation()==Operation.STOP;if(s.logStreamControlCount(owner)>=(stopping?200:180))throw fail(WorkflowFailure.Code.CAPACITY);
            var old=s.logStreamTask(owner,command.id()).orElse(null);if((old==null?0:old.generation())!=command.expectedGeneration())throw fail(WorkflowFailure.Code.CONFLICT);
            var time=now();Task next;
            if(stopping){if(old==null||!old.state().equals("RUNNING")||old.revision()!=command.revision()||!old.digest().equals(command.digest()))throw fail(WorkflowFailure.Code.CONFLICT);next=old.state(old.generation()+1,"STOPPED",null,time,old.sessionBatches(),old.authority());}
            else {
                if(command.expectedGeneration()>=999_999)throw fail(WorkflowFailure.Code.CAPACITY);version(p,s,command.id(),command.revision(),command.digest(),true);
                if(command.operation()==Operation.START){if(old!=null&&!WorkflowTaskVersionService.replaceable(s,owner,new WorkflowQuality.Reference(old.workflowId(),old.revision(),old.digest()),WorkflowQuality.Kind.LOG_STREAM,new WorkflowQuality.Task(old.state(),old.generation(),old.updatedAt(),old.error(),old.pendingBatchId()),command.revision()))throw fail(WorkflowFailure.Code.CONFLICT);if(old==null&&s.logStreamTasks(owner).size()>=20)throw fail(WorkflowFailure.Code.CAPACITY);}
                else if(old==null||Set.of("RUNNING","ABANDONED").contains(old.state())||old.revision()!=command.revision()||!old.digest().equals(command.digest()))throw fail(WorkflowFailure.Code.CONFLICT);
                boolean fresh=command.operation()==Operation.START;UUID pending=fresh?null:old.pendingBatchId();if(pending!=null){var batch=s.logStreamBatch(owner,pending).orElseThrow();if(!batch.state().equals("FAILED")||!s.logStreamRejectionKnown(owner,batch.id()))throw fail(WorkflowFailure.Code.CONFLICT);pending=null;}
                if(fresh&&old!=null){workflows.accessibleVersion(p,s,old.workflowId(),old.revision());WorkflowTaskVersionService.archive(s,owner,new WorkflowQuality.Reference(old.workflowId(),old.revision(),old.digest()),WorkflowQuality.Kind.LOG_STREAM,new WorkflowQuality.Task(old.state(),old.generation(),old.updatedAt(),old.error(),old.pendingBatchId()),new WorkflowQuality.Reference(command.id(),command.revision(),command.digest()),time);}
                var authority=issue.get();if(authority!=null){authority.requireBudget(time);if(authority.consumedBatches()!=0)throw new IllegalArgumentException();}
                time=now();if(old!=null&&time.isBefore(old.updatedAt()))throw fail(WorkflowFailure.Code.CONFLICT);var cursor=fresh?Instant.ofEpochSecond(time.getEpochSecond()-WorkflowLogWindow.WINDOW_SECONDS):old.cursor();
                next=new Task(command.id(),command.revision(),command.digest(),command.expectedGeneration()+1,"RUNNING",cursor,pending,fresh?0:old.confirmedWindows(),fresh?0:old.confirmedRecords(),0,time,null,authority);
            }
            var receipt=new Receipt(command.requestId(),command.operation(),command.commandDigest(),next.updatedAt(),next);receipt.require(command);s.saveLogStreamTask(owner,next);s.addLogStreamControl(owner,receipt);return receipt;
        });
    }
    private Context context(WorkflowStore.Session s,TenantId tenant,String owner,String id,long generation,Function<Task,Principal> resolver){
        var task=s.logStreamTask(owner,id).orElse(null);if(task==null||task.generation()!=generation||!task.state().equals("RUNNING"))return null;
        var p=resolver.apply(task);if(p==null||!p.tenantId().equals(tenant)||!p.subjectId().value().equals(owner))throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
        if(task.authority()!=null)task.authority().requireBudget(now());if(task.sessionBatches()>=WorkflowLogStream.MAX_BATCHES)throw fail(WorkflowFailure.Code.EXECUTION_LIMIT);
        var e=version(p,s,id,task.revision(),task.digest(),true);return new Context(task,e.definition(),workflows.executionPlan(p,s,e),p);
    }
    public void tick(Principal p){tickOwner(p.tenantId(),p.subjectId().value(),false,t->p);}
    public void tickAuthorized(TenantId tenant,String owner,Function<Task,Principal> resolver){tickOwner(tenant,owner,true,resolver);}
    public boolean hasAuthorizedTasks(TenantId tenant,String owner){return store.transaction(tenant,s->s.logStreamTasks(owner).stream().anyMatch(t->t.authority()!=null&&t.state().equals("RUNNING")));}
    private void tickOwner(TenantId tenant,String owner,boolean authorized,Function<Task,Principal> resolver){
        if(!budget.tryAcquire())return;
        try{
            var queue=new WorkflowDispatchQueue<Task>(clock);
            for(var task:store.transaction(tenant,s->s.logStreamTasks(owner)))if(task.state().equals("RUNNING")&&(task.authority()!=null)==authorized)queue.add(task);
            for(var delivery=queue.poll();delivery!=null;delivery=queue.poll()){var task=delivery.task();try{tickOne(tenant,owner,task,resolver,delivery.dispatch());}catch(RuntimeException failure){String error=failure instanceof WorkflowFailure f&&WorkflowLogStream.ERRORS.contains(f.code().name())?f.code().name():"RUNTIME_UNAVAILABLE";failTask(tenant,owner,task,error);}}
        }finally{budget.release();}
    }
    private void failTask(TenantId tenant,String owner,Task expected,String error){store.transaction(tenant,s->{var task=s.logStreamTask(owner,expected.workflowId()).orElse(null);if(task!=null&&task.generation()==expected.generation()&&task.state().equals("RUNNING"))s.saveLogStreamTask(owner,task.state(task.generation(),"FAILED",error,now(),task.sessionBatches(),task.authority()));return null;});}
    private <T>T inspect(TenantId tenant,String owner,Task task,Instant from,Instant till,UUID related,Function<Task,Principal> resolver,WorkflowDiagnostics.Dispatch dispatch,java.util.function.BiFunction<Principal,WorkflowDiagnosticRecorder.Inspection,T> operation){
        return new WorkflowDiagnosticRecorder(store,clock).inspectSource(tenant,owner,new WorkflowQuality.Reference(task.workflowId(),task.revision(),task.digest()),WorkflowQuality.Kind.LOG_STREAM,task.generation(),from,till,related,s->{var current=s.logStreamTask(owner,task.workflowId()).orElse(null);if(current==null||current.generation()!=task.generation()||!current.state().equals("RUNNING")||current.pendingBatchId()!=null)return false;var p=resolver.apply(current);if(p==null||!p.tenantId().equals(tenant)||!p.subjectId().value().equals(owner))return false;version(p,s,current.workflowId(),current.revision(),current.digest(),false);return true;},dispatch,(inspection,started)->{var p=resolver.apply(task);if(p==null)throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);return operation.apply(p,inspection);});
    }
    private void tickOne(TenantId tenant,String owner,Task expected,Function<Task,Principal> resolver,WorkflowDiagnostics.Dispatch dispatch){
        var context=store.transaction(tenant,s->context(s,tenant,owner,expected.workflowId(),expected.generation(),resolver));if(context==null)return;var task=context.task();
        if(task.pendingBatchId()!=null){store.transaction(tenant,s->{var current=s.logStreamTask(owner,task.workflowId()).orElseThrow();if(current.generation()!=task.generation()||!current.state().equals("RUNNING"))return null;var batch=s.logStreamBatch(owner,current.pendingBatchId()).orElseThrow();if(batch.state().equals("IN_FLIGHT")&&now().isBefore(batch.createdAt().plusSeconds(60)))return null;if(batch.state().equals("IN_FLIGHT")){batch=batch.finish("UNKNOWN","OUTPUT_UNCONFIRMED",now());s.finishLogStreamBatch(owner,batch);}s.saveLogStreamTask(owner,current.state(current.generation(),"FAILED",batch.state().equals("FAILED")?"OUTPUT_REJECTED":"OUTPUT_UNCONFIRMED",now(),current.sessionBatches(),current.authority()));return null;});return;}
        var till=task.cursor().plusSeconds(WorkflowLogWindow.WINDOW_SECONDS);if(now().isBefore(till.plusSeconds(WorkflowLogWindow.SETTLE_SECONDS)))return;
        if(task.cursor().isBefore(now().minusSeconds(86400)))throw fail(WorkflowFailure.Code.INVALID_SAMPLE);
        if(store.transaction(tenant,s->s.logStreamBatchCount(owner))>=200)throw fail(WorkflowFailure.Code.CAPACITY);
        Batch original=null;WorkflowLogWindow.Batch prepared=null;List<Map<String,Object>> rows=null;Instant from=task.cursor();var batchId=UUID.randomUUID();
        if(task.confirmedWindows()>0){
            original=store.transaction(tenant,s->s.latestConfirmedLogWindow(owner,task.workflowId(),task.revision(),task.digest(),task.cursor()).orElseThrow(()->fail(WorkflowFailure.Code.WINDOW_INCOMPLETE)));
            var previous=original;
            var checked=inspect(tenant,owner,task,previous.from(),previous.till(),previous.id(),resolver,dispatch,(p,inspection)->{var input=inspection.source().read(()->source.read(p,context.definition().source(),previous.from(),previous.till()));return new Checked(WorkflowLogWindow.prepare(outputScope(p,batchId,task.workflowId(),task.revision(),task.digest()),context.plan(),input,previous.from(),previous.till(),now(),inspection.observer()),input);});rows=checked.rows();prepared=checked.prepared();
            var byPosition=new HashMap<String,Map<String,Object>>();for(var row:rows)byPosition.put((String)row.get("timestamp"),row);
            var priorRows=new ArrayList<Map<String,Object>>();for(var position:original.positions()){var old=byPosition.get(position);if(old==null)throw fail(WorkflowFailure.Code.SOURCE_WINDOW_CHANGED);priorRows.add(old);}
            if(!WorkflowLogWindow.inputDigest(context.definition().source(),original.from(),original.till(),priorRows).equals(original.inputDigest()))throw fail(WorkflowFailure.Code.SOURCE_WINDOW_CHANGED);
            if(rows.size()>original.inputCount()){
                var priorPositions=new HashSet<>(original.positions());var fresh=prepared.records().stream().filter(r->!priorPositions.contains(r.position())).toList();int deduplicated=prepared.records().size()-fresh.size();
                prepared=new WorkflowLogWindow.Batch(prepared.scope(),prepared.from(),prepared.till(),prepared.inputCount(),prepared.filtered(),deduplicated,fresh);from=original.from();till=original.till();
            }else{original=null;prepared=null;rows=null;}
        }
        if(prepared==null){
            var current=store.transaction(tenant,s->context(s,tenant,owner,task.workflowId(),task.generation(),resolver));if(current==null||!current.task().equals(task))return;
            var windowFrom=from;var windowTill=till;
            var checked=inspect(tenant,owner,task,windowFrom,windowTill,null,resolver,dispatch,(p,inspection)->{var input=inspection.source().read(()->source.read(p,current.definition().source(),windowFrom,windowTill));return new Checked(WorkflowLogWindow.prepare(outputScope(p,batchId,task.workflowId(),task.revision(),task.digest()),current.plan(),input,windowFrom,windowTill,now(),inspection.observer()),input);});rows=checked.rows();prepared=checked.prepared();
        }
        var payload=prepared;var time=now();
        var batch=new Batch(batchId,task.workflowId(),task.revision(),task.digest(),from,till,time,time,payload.records().isEmpty()?"CONFIRMED":"IN_FLIGHT",payload.inputCount(),payload.filtered(),payload.deduplicated(),
            payload.records().stream().map(WorkflowLogWindow.Record::index).toList(),rows.stream().map(r->(String)r.get("timestamp")).toList(),WorkflowLogWindow.inputDigest(context.definition().source(),from,till,rows),payload.digest(),null,original==null?null:original.id());
        if(original!=null)batch.requireReconciliation(original);
        boolean admitted=store.transaction(tenant,s->{var current=context(s,tenant,owner,task.workflowId(),task.generation(),resolver);if(current==null||!current.task().equals(task))return false;if(s.logStreamBatchCount(owner)>=200)throw fail(WorkflowFailure.Code.CAPACITY);
            if(batch.reconcilesBatchId()!=null)batch.requireReconciliation(s.logStreamBatch(owner,batch.reconcilesBatchId()).orElseThrow());
            s.addLogStreamBatch(owner,batch);var authority=task.authority()==null?null:task.authority().consume();var pending=task.pending(batch.id(),now()).state(task.generation(),"RUNNING",null,now(),task.sessionBatches()+1,authority);
            s.saveLogStreamTask(owner,batch.state().equals("CONFIRMED")?pending.confirm(batch,now()):pending);return true;});
        if(!admitted||batch.state().equals("CONFIRMED"))return;
        // The committed IN_FLIGHT proof precedes IO. Holding the tenant journal lock serializes this local executor with STOP.
        store.transaction(tenant,s->{var current=s.logStreamTask(owner,task.workflowId()).orElseThrow();if(current.generation()!=task.generation()||!current.state().equals("RUNNING")||!batch.id().equals(current.pendingBatchId()))return null;
            var p=resolver.apply(current);if(p==null||!p.tenantId().equals(tenant)||!p.subjectId().value().equals(owner))throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
            if(current.authority()!=null){var a=current.authority();if(now().isBefore(a.issuedAt())||!now().isBefore(a.expiresAt()))throw fail(WorkflowFailure.Code.AUTHORIZATION_EXPIRED);}
            version(p,s,task.workflowId(),task.revision(),task.digest(),true);
            Batch result;boolean wrote=false;
            try{sink.write(payload);wrote=true;var found=sink.read(payload.scope());boolean confirmed=matches(p,batch,found);result=batch.finish(confirmed?"CONFIRMED":"UNKNOWN",confirmed?null:"OUTPUT_UNCONFIRMED",now());}
            catch(WorkflowLogOutputService.OutputFailure failure){result=batch.finish(wrote||failure.unknown()?"UNKNOWN":"FAILED",wrote?"OUTPUT_UNCONFIRMED":failure.getMessage(),now());}
            catch(RuntimeException unknown){result=batch.finish("UNKNOWN","OUTPUT_UNCONFIRMED",now());}
            s.finishLogStreamBatch(owner,result);s.saveLogStreamTask(owner,result.state().equals("CONFIRMED")?current.confirm(result,now()):current.state(current.generation(),"FAILED",result.error(),now(),current.sessionBatches(),current.authority()));return null;
        });
    }
    /** Reads only the original log batch. It cannot read new source values, issue POST or renew authority. */
    public Status verify(Principal p,String id,UUID batchId){
        var status=status(p,id);permission(p,id,Permission.LOG_WRITE);var task=status.task();if(task==null||task.state().equals("ABANDONED")||!Objects.equals(task.pendingBatchId(),batchId))throw fail(WorkflowFailure.Code.CONFLICT);
        var original=store.transaction(p.tenantId(),s->s.logStreamBatch(p.subjectId().value(),batchId).orElseThrow());if(!Set.of("IN_FLIGHT","UNKNOWN").contains(original.state()))throw fail(WorkflowFailure.Code.CONFLICT);
        if(!budget.tryAcquire())throw fail(WorkflowFailure.Code.BUSY);
        boolean confirmed=false;try{try{var found=sink.read(outputScope(p,original.id(),original.workflowId(),original.revision(),original.digest()));permission(p,id,Permission.LOG_WRITE);confirmed=matches(p,original,found);}catch(RuntimeException unavailable){/* Preserve proof and cursor. */}}finally{budget.release();}
        final boolean matches=confirmed;store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var current=s.logStreamTask(owner,id).orElseThrow();var batch=s.logStreamBatch(owner,batchId).orElseThrow();if(current.state().equals("ABANDONED")||current.generation()!=task.generation()||!Objects.equals(current.pendingBatchId(),batchId))return null;if(Set.of("CONFIRMED","FAILED").contains(batch.state()))return null;
            var result=batch.finish(matches?"CONFIRMED":"UNKNOWN",matches?null:"OUTPUT_UNCONFIRMED",now());s.finishLogStreamBatch(owner,result);
            if(matches)s.saveLogStreamTask(owner,current.confirm(result,now()));else if(current.state().equals("RUNNING"))s.saveLogStreamTask(owner,current.state(current.generation(),"FAILED","OUTPUT_UNCONFIRMED",now(),current.sessionBatches(),current.authority()));return null;});return status(p,id);
    }
}
