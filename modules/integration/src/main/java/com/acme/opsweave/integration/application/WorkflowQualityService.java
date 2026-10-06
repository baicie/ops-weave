package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowQuality.*;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Reads existing journals only. No collector, evaluator, output, credential or repair port. */
public final class WorkflowQualityService {
    private final WorkflowStore store;private final WorkflowService workflows;private final Clock clock;
    public WorkflowQualityService(WorkflowStore store,WorkflowService workflows,Clock clock){this.store=store;this.workflows=workflows;this.clock=clock;}
    public Report report(Principal p,String id,int revision){
        workflows.authorize(p);WorkflowDefinition.ref(id,revision);
        return store.transaction(p.tenantId(),s->report(p,s,id,revision,clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
    Report report(Principal p,WorkflowStore.Session s,String id,int revision,Instant asOf){workflows.authorize(p);WorkflowDefinition.ref(id,revision);var d=version(p,s,id,revision);var ref=new Reference(id,revision,d.digest());var kind=kind(d);var owner=p.subjectId().value();List<Batch> batches;Task task=null;
            switch(kind){
                case LOG_STREAM->{var t=s.logStreamTask(owner,id).filter(v->ref.matches(v.workflowId(),v.revision(),v.digest())).orElse(null);if(t!=null)task=new Task(t.state(),t.generation(),t.updatedAt(),t.error(),t.pendingBatchId());batches=s.qualityLogBatches(owner,id,revision,ref.digest()).stream().map(b->WorkflowQuality.log(b,s.logStreamRejectionKnown(owner,b.id()))).toList();}
                case METRIC_STREAM->{var t=s.metricStreamTask(owner,id).filter(v->ref.matches(v.workflowId(),v.revision(),v.digest())).orElse(null);if(t!=null)task=new Task(t.state(),t.generation(),t.updatedAt(),t.error(),t.pendingBatchId());batches=s.qualityMetricBatches(owner,id,revision,ref.digest()).stream().map(WorkflowQuality::metric).toList();}
                case HOST_SCAN->{var t=s.task(owner,id).filter(v->ref.matches(v.workflowId(),v.revision(),v.digest())).orElse(null);var c=s.hostCheckpoint(owner,id).filter(v->ref.matches(v.workflowId(),v.revision(),v.digest())).orElse(null);var schedule=s.hostSchedule(owner,id).filter(v->ref.matches(v.workflowId(),v.revision(),v.digest())).orElse(null);var pending=c==null?null:c.pendingBatchId();if(schedule!=null&&(t==null||!t.state().equals("ABANDONED")))task=new Task(schedule.state(),schedule.generation(),schedule.updatedAt(),schedule.error(),pending);else if(t!=null)task=new Task(t.state(),t.generation(),t.updatedAt(),t.error(),pending);var rows=s.qualityHostBatches(owner,id,revision,ref.digest());rows.forEach(b->entities(p,b));batches=rows.stream().map(WorkflowQuality::host).toList();}
                default->throw new IllegalStateException();
            }
            var archived=s.taskArchive(owner,id,revision,ref.digest());if(archived.isPresent()){var a=archived.get();WorkflowTaskVersionService.archiveParent(s,owner,a);if(a.kind()!=kind||task!=null&&kind!=Kind.HOST_SCAN||kind==Kind.HOST_SCAN&&s.task(owner,id).filter(t->ref.matches(t.workflowId(),t.revision(),t.digest())).isPresent())throw new IllegalStateException("Invalid archived task");var parent=s.published(id,a.replacedBy().revision()).orElseThrow();if(!parent.digest().equals(a.replacedBy().digest()))throw new IllegalStateException("Invalid archive successor");task=a.schedule()==null?a.task():a.schedule();}
            var closure=s.recoveryForVersion(owner,id,revision,ref.digest());if(closure.isPresent()){var r=closure.get();if(r.kind()!=kind)throw new IllegalStateException("Invalid recovery kind");WorkflowRecoveryService.parent(p,s,r);task=new Task("ABANDONED",r.generation(),r.acceptedAt(),kind==Kind.HOST_SCAN?"OUTPUT_UNAVAILABLE":"OUTPUT_UNCONFIRMED",r.batchId());}
            if(kind==Kind.LOG_STREAM&&task!=null&&task.state().equals("FAILED")&&task.pendingBatchId()!=null){var pending=s.logStreamBatch(owner,task.pendingBatchId()).orElseThrow();require(ref,pending.workflowId(),pending.revision(),pending.digest());if(WorkflowLogOutcome.legacyUncertain(s,owner,pending))task=new Task(task.state(),task.generation(),task.updatedAt(),"OUTPUT_UNCONFIRMED",task.pendingBatchId());}
            return new Report("2.0",asOf,ref,kind,task,batches.stream().limit(20).toList(),batches.size()>20);
    }
    public Batch batch(Principal p,String id,int revision,UUID batchId){
        workflows.authorize(p);WorkflowDefinition.ref(id,revision);Objects.requireNonNull(batchId);
        return store.transaction(p.tenantId(),s->{var d=version(p,s,id,revision);var ref=new Reference(id,revision,d.digest());var owner=p.subjectId().value();return switch(kind(d)){
            case LOG_STREAM->{var b=s.logStreamBatch(owner,batchId).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));require(ref,b.workflowId(),b.revision(),b.digest());yield WorkflowQuality.log(b,s.logStreamRejectionKnown(owner,b.id()));}
            case METRIC_STREAM->{var b=s.metricStreamBatch(owner,batchId).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));require(ref,b.workflowId(),b.revision(),b.digest());yield WorkflowQuality.metric(b);}
            case HOST_SCAN->{var b=s.hostBatch(owner,batchId).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));require(ref,b.workflowId(),b.revision(),b.digest());entities(p,b);yield WorkflowQuality.host(b);}
        };});
    }
    private WorkflowDefinition version(Principal p,WorkflowStore.Session s,String id,int revision){
        var e=s.published(id,revision).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));workflows.accessibleEntry(p,s,e);
        if(e.definition().target().kind().equals("LOG")&&new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",id),Permission.LOG_READ).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);
        return e.definition();
    }
    private static Kind kind(WorkflowDefinition d){return switch(d.source().kind()){case "ZABBIX_HOST"->{if(!d.target().entity())throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);yield Kind.HOST_SCAN;}case "ZABBIX_METRIC"->{if(d.target().mappingPin()==null)throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);yield Kind.METRIC_STREAM;}case "ZABBIX_LOG"->{if(!d.target().kind().equals("LOG"))throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);yield Kind.LOG_STREAM;}default->throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);};}
    private static void entities(Principal p,WorkflowHostScan.Batch b){var ids=new HashSet<>(b.entityIds());for(var row:b.records())ids.add((String)row.get("entity_id"));for(var id:ids)if(new Authorizer().decide(p,ResourceRef.entity(p.tenantId(),new EntityId(UUID.fromString(id))),Permission.ENTITY_READ).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);}
    private static void require(Reference ref,String id,int revision,String digest){if(!ref.matches(id,revision,digest))throw fail(WorkflowFailure.Code.NOT_FOUND);}
    private static WorkflowFailure fail(WorkflowFailure.Code c){return new WorkflowFailure(c);}
}
