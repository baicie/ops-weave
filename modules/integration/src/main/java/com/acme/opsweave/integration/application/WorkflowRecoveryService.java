package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowRecovery.*;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** No source, output or authority issuer. Task closure and its immutable acknowledgement share one transaction. */
public final class WorkflowRecoveryService {
    private final WorkflowStore store;private final Clock clock;
    public WorkflowRecoveryService(WorkflowStore store,Clock clock){this.store=store;this.clock=clock;}
    private static WorkflowFailure fail(WorkflowFailure.Code c){return new WorkflowFailure(c);}
    private static void permission(Principal p,ResourceRef ref,Permission permission){if(new Authorizer().decide(p,ref,permission).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);}
    private static void scope(Principal p,WorkflowStore.Session s,WorkflowQuality.Reference ref,WorkflowQuality.Kind kind){
        permission(p,new ResourceRef(p.tenantId(),"workflow",ref.id()),Permission.SOURCE_SYNC);
        var e=s.published(ref.id(),ref.revision()).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));if(!e.digest().equals(ref.digest()))throw fail(WorkflowFailure.Code.CONFLICT);
        var d=e.definition();var expected=switch(d.source().kind()){case "ZABBIX_HOST"->WorkflowQuality.Kind.HOST_SCAN;case "ZABBIX_METRIC"->WorkflowQuality.Kind.METRIC_STREAM;case "ZABBIX_LOG"->WorkflowQuality.Kind.LOG_STREAM;default->null;};
        if(expected!=kind||d.source().configuration()==null)throw fail(WorkflowFailure.Code.CONFLICT);
        permission(p,ResourceRef.source(p.tenantId(),d.source().instanceId()),Permission.SOURCE_SYNC);
        if(kind==WorkflowQuality.Kind.LOG_STREAM){permission(p,new ResourceRef(p.tenantId(),"log","workflow."+ref.id()),Permission.LOG_READ);permission(p,new ResourceRef(p.tenantId(),"log","workflow."+ref.id()),Permission.LOG_WRITE);}
        else if(kind==WorkflowQuality.Kind.HOST_SCAN){permission(p,new ResourceRef(p.tenantId(),"catalog","*"),Permission.ENTITY_READ);if(!p.has(Permission.ENTITY_MANAGE))throw fail(WorkflowFailure.Code.FORBIDDEN);}else permission(p,ResourceRef.metric(p.tenantId(),d.target().metricKey()),Permission.METRIC_READ);
    }
    private static void hostScope(Principal p,WorkflowHostScan.Batch b){var ids=new HashSet<>(b.entityIds());for(var row:b.records())ids.add((String)row.get("entity_id"));for(var id:ids)permission(p,ResourceRef.entity(p.tenantId(),new EntityId(UUID.fromString(id))),Permission.ENTITY_READ);}
    private static void ref(WorkflowQuality.Reference r,String id,int revision,String digest){if(!r.matches(id,revision,digest))throw fail(WorkflowFailure.Code.CONFLICT);}
    static void parent(Principal p,WorkflowStore.Session s,Receipt r){var owner=p.subjectId().value();switch(r.kind()){
        case HOST_SCAN->{var b=s.hostBatch(owner,r.batchId()).orElseThrow(()->new IllegalStateException("Missing recovery parent"));ref(r.reference(),b.workflowId(),b.revision(),b.digest());if(!b.state().equals("UNKNOWN"))throw new IllegalStateException("Invalid recovery parent");hostScope(p,b);}
        case LOG_STREAM->{var b=s.logStreamBatch(owner,r.batchId()).orElseThrow(()->new IllegalStateException("Missing recovery parent"));ref(r.reference(),b.workflowId(),b.revision(),b.digest());if(!WorkflowLogOutcome.uncertain(s,owner,b))throw new IllegalStateException("Invalid recovery parent");}
        case METRIC_STREAM->{var b=s.metricStreamBatch(owner,r.batchId()).orElseThrow(()->new IllegalStateException("Missing recovery parent"));ref(r.reference(),b.workflowId(),b.revision(),b.digest());if(!b.state().equals("UNKNOWN"))throw new IllegalStateException("Invalid recovery parent");if(!p.tenantId().value().equals(b.labels().get("tenant_id"))||!WorkflowDefinition.hash(List.of(owner)).substring(7).equals(b.labels().get("owner_scope")))throw fail(WorkflowFailure.Code.FORBIDDEN);}
    }}
    public Receipt receipt(Principal p,UUID id){if(!p.has(Permission.SOURCE_SYNC))throw fail(WorkflowFailure.Code.FORBIDDEN);return store.transaction(p.tenantId(),s->{var r=s.recovery(p.subjectId().value(),id).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));scope(p,s,r.reference(),r.kind());parent(p,s,r);return r;});}
    public Receipt abandon(Principal p,Command c){
        permission(p,new ResourceRef(p.tenantId(),"workflow",c.id()),Permission.SOURCE_SYNC);
        return store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var reference=new WorkflowQuality.Reference(c.id(),c.revision(),c.digest());scope(p,s,reference,c.kind());
            var prior=s.recovery(owner,c.requestId());if(prior.isPresent()){prior.get().require(c);parent(p,s,prior.get());return prior.get();}
            if(s.recoveryCount(owner)>=200)throw fail(WorkflowFailure.Code.CAPACITY);var time=clock.instant().truncatedTo(ChronoUnit.MICROS);String cursor;int batches,records;
            switch(c.kind()){
                case METRIC_STREAM->{var t=s.metricStreamTask(owner,c.id()).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));ref(reference,t.workflowId(),t.revision(),t.digest());if(t.generation()!=c.expectedGeneration()||!Set.of("FAILED","STOPPED").contains(t.state())||!c.batchId().equals(t.pendingBatchId()))throw fail(WorkflowFailure.Code.CONFLICT);var b=s.metricStreamBatch(owner,c.batchId()).orElseThrow();ref(reference,b.workflowId(),b.revision(),b.digest());if(!b.state().equals("UNKNOWN")||time.isBefore(t.updatedAt())||time.isBefore(b.updatedAt()))throw fail(WorkflowFailure.Code.CONFLICT);if(!p.tenantId().value().equals(b.labels().get("tenant_id"))||!WorkflowDefinition.hash(List.of(owner)).substring(7).equals(b.labels().get("owner_scope")))throw fail(WorkflowFailure.Code.FORBIDDEN);cursor=t.cursor().toString();batches=t.confirmedWindows();records=t.confirmedPoints();s.saveMetricStreamTask(owner,t.state(t.generation()+1,"ABANDONED","OUTPUT_UNCONFIRMED",time,t.sessionBatches(),null));}
                case LOG_STREAM->{var t=s.logStreamTask(owner,c.id()).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));ref(reference,t.workflowId(),t.revision(),t.digest());if(t.generation()!=c.expectedGeneration()||!Set.of("FAILED","STOPPED").contains(t.state())||!c.batchId().equals(t.pendingBatchId()))throw fail(WorkflowFailure.Code.CONFLICT);var b=s.logStreamBatch(owner,c.batchId()).orElseThrow();ref(reference,b.workflowId(),b.revision(),b.digest());if(!WorkflowLogOutcome.uncertain(s,owner,b)||time.isBefore(t.updatedAt())||time.isBefore(b.updatedAt()))throw fail(WorkflowFailure.Code.CONFLICT);cursor=t.cursor().toString();batches=t.confirmedWindows();records=t.confirmedRecords();s.saveLogStreamTask(owner,t.state(t.generation()+1,"ABANDONED","OUTPUT_UNCONFIRMED",time,t.sessionBatches(),null));}
                case HOST_SCAN->{var t=s.task(owner,c.id()).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));ref(reference,t.workflowId(),t.revision(),t.digest());if(t.generation()!=c.expectedGeneration()||!Set.of("FAILED","STOPPED").contains(t.state()))throw fail(WorkflowFailure.Code.CONFLICT);var cp=s.hostCheckpoint(owner,c.id()).orElseThrow();ref(reference,cp.workflowId(),cp.revision(),cp.digest());if(cp.generation()!=t.generation()||!c.batchId().equals(cp.pendingBatchId()))throw fail(WorkflowFailure.Code.CONFLICT);var b=s.hostBatch(owner,c.batchId()).orElseThrow();ref(reference,b.workflowId(),b.revision(),b.digest());if(!b.scanId().equals(cp.scanId())||!b.state().equals("UNKNOWN")||time.isBefore(t.updatedAt())||time.isBefore(cp.updatedAt())||time.isBefore(b.updatedAt()))throw fail(WorkflowFailure.Code.CONFLICT);hostScope(p,b);cursor=null;batches=cp.confirmedBatches();records=cp.confirmedRecords();var next=new WorkflowRuntime.Task(t.workflowId(),t.revision(),t.digest(),t.settings(),t.generation()+1,"ABANDONED",t.cursor(),t.cursorId(),time,"OUTPUT_UNAVAILABLE",null);s.saveTask(owner,next);s.saveHostCheckpoint(owner,cp.generation(next.generation(),time));s.hostSchedule(owner,c.id()).filter(v->v.revision()==c.revision()&&v.digest().equals(c.digest())&&v.state().equals("RUNNING")).ifPresent(v->s.saveHostSchedule(owner,v.state("STOPPED",v.generation()+1,next.generation(),null,time)));}
                default->throw new IllegalStateException();
            }
            var r=new Receipt(c.requestId(),c.commandDigest(),reference,c.kind(),c.batchId(),c.expectedGeneration(),c.expectedGeneration()+1,time,"ABANDONED",cursor,batches,records);r.require(c);s.addRecovery(owner,r);return r;
        });
    }
}
