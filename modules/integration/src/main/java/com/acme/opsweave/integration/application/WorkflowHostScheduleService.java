package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowHostSchedule.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** A completed scan schedules one future scan. Missed intervals never create a catch-up queue. */
public final class WorkflowHostScheduleService {
    public record Status(Schedule schedule,WorkflowRuntime.Task task) {}
    private final WorkflowStore store;private final WorkflowRuntimeService runtime;private final Clock clock;
    public WorkflowHostScheduleService(WorkflowStore store,WorkflowRuntimeService runtime,Clock clock){this.store=store;this.runtime=runtime;this.clock=clock;}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static void authorize(Principal p,String id){WorkflowDefinition.ref(id,1);var a=new Authorizer();if(a.decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.SOURCE_SYNC).denied()||a.decide(p,new ResourceRef(p.tenantId(),"workflow",id),Permission.SOURCE_SYNC).denied())throw fail(WorkflowFailure.Code.FORBIDDEN);}
    public Status status(Principal p,String id){authorize(p,id);return store.transaction(p.tenantId(),s->{var schedule=s.hostSchedule(p.subjectId().value(),id).orElse(null);if(schedule!=null)runtime.requireScheduledMetadata(s,p,schedule,false);return new Status(schedule,schedule==null?null:s.task(p.subjectId().value(),id).orElseThrow());});}
    public Receipt receipt(Principal p,UUID id){return store.transaction(p.tenantId(),s->{var r=s.hostScheduleControl(p.subjectId().value(),id).orElseThrow(()->fail(WorkflowFailure.Code.NOT_FOUND));authorize(p,r.schedule().workflowId());return r;});}
    public Receipt command(Principal p,Command c,Supplier<WorkflowTaskAuthority> issue){authorize(p,c.id());return store.transaction(p.tenantId(),s->{
        var owner=p.subjectId().value();var prior=s.hostScheduleControl(owner,c.requestId());if(prior.isPresent()){prior.get().require(c);return prior.get();}
        var old=s.hostSchedule(owner,c.id()).orElse(null);if((old==null?0:old.generation())!=c.expectedGeneration())throw fail(WorkflowFailure.Code.CONFLICT);
        boolean stop=c.operation()==Operation.STOP;if(s.hostScheduleControlCount(owner)>=(stop?200:180))throw fail(WorkflowFailure.Code.CAPACITY);
        Schedule next;
        if(stop){
            if(old==null||!old.state().equals("RUNNING")||old.revision()!=c.revision()||!old.digest().equals(c.digest())||!old.settings().equals(c.settings())||old.intervalSeconds()!=c.intervalSeconds())throw fail(WorkflowFailure.Code.CONFLICT);
            var task=s.task(owner,c.id()).orElseThrow();if(task.generation()!=old.taskGeneration())throw fail(WorkflowFailure.Code.CONFLICT);
            if(task.state().equals("RUNNING"))task=runtime.scheduledControl(s,p,c.id(),c.revision(),c.digest(),c.settings(),task.generation(),false,()->null,false);
            var completed=s.hostCheckpoint(owner,c.id()).orElseThrow();if(completed.complete()&&old.activeScanId()!=null&&completed.scanId().equals(old.activeScanId()))old=old.completed(completed,now());
            next=old.state("STOPPED",old.generation()+1,task.generation(),null,now());
        }else{
            if(c.expectedGeneration()>=999999||old!=null&&old.state().equals("RUNNING"))throw fail(WorkflowFailure.Code.CONFLICT);
            if(old==null&&s.hostSchedules(owner).size()>=20)throw fail(WorkflowFailure.Code.CAPACITY);
            boolean resume=c.operation()==Operation.RESUME;
            if(resume&&(old==null||old.revision()!=c.revision()||!old.digest().equals(c.digest())||!old.settings().equals(c.settings())||old.intervalSeconds()!=c.intervalSeconds()))throw fail(WorkflowFailure.Code.CONFLICT);
            var previous=s.task(owner,c.id()).orElse(null);if(previous!=null&&previous.state().equals("RUNNING")||resume&&(previous==null||previous.generation()!=old.taskGeneration()))throw fail(WorkflowFailure.Code.CONFLICT);
            var checkpoint=s.hostCheckpoint(owner,c.id()).orElse(null);boolean incomplete=checkpoint!=null&&!checkpoint.complete();
            if(!resume&&incomplete)throw fail(WorkflowFailure.Code.CONFLICT);
            var grant=issue.get();if(grant!=null&&grant.consumedBatches()!=0)throw new IllegalArgumentException();
            var task=runtime.scheduledControl(s,p,c.id(),c.revision(),c.digest(),c.settings(),previous==null?0:previous.generation(),true,()->grant,resume&&incomplete);
            var scan=s.hostCheckpoint(owner,c.id()).orElseThrow();
            next=new Schedule(c.id(),c.revision(),c.digest(),c.settings(),c.intervalSeconds(),c.expectedGeneration()+1,"RUNNING",task.generation(),scan.scanId(),resume?old.accountedScanId():null,resume?old.completedScans():0,resume?old.confirmedRecords():0,0,null,resume?old.lastSuccessAt():null,now(),null);
        }
        s.saveHostSchedule(owner,next);var r=new Receipt(c.requestId(),c.operation(),c.commandDigest(),next.updatedAt(),next);s.addHostScheduleControl(owner,r);return r;
    });}
    public void tick(Principal p){tickOwner(p.tenantId(),p.subjectId().value(),false,t->p);}
    public void tickAuthorized(TenantId tenant,String owner,Function<WorkflowRuntime.Task,Principal> resolver){tickOwner(tenant,owner,true,resolver);}
    public boolean hasAuthorizedTasks(TenantId tenant,String owner){return store.transaction(tenant,s->s.hostSchedules(owner).stream().anyMatch(v->v.state().equals("RUNNING")&&s.task(owner,v.workflowId()).map(t->t.authority()!=null).orElse(false)));}
    private void tickOwner(TenantId tenant,String owner,boolean delegated,Function<WorkflowRuntime.Task,Principal> resolver){
        for(var listed:store.transaction(tenant,s->s.hostSchedules(owner))){
            if(!listed.state().equals("RUNNING"))continue;
            store.transaction(tenant,s->{var v=s.hostSchedule(owner,listed.workflowId()).orElseThrow();if(v.generation()!=listed.generation()||!v.state().equals("RUNNING"))return null;
                var task=s.task(owner,v.workflowId()).orElseThrow();if(delegated!=(task.authority()!=null))return null;
                try{
                    if(task.generation()!=v.taskGeneration()||task.revision()!=v.revision()||!task.digest().equals(v.digest())||!task.settings().equals(v.settings())){s.saveHostSchedule(owner,v.state("FAILED",v.generation(),v.taskGeneration(),"TASK_CHANGED",now()));return null;}
                    var p=resolver.apply(task);if(p==null||!p.tenantId().equals(tenant)||!p.subjectId().value().equals(owner))throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
                    authorize(p,v.workflowId());runtime.requireScheduledMetadata(s,p,v);
                    if(task.state().equals("FAILED")){s.saveHostSchedule(owner,v.state("FAILED",v.generation(),v.taskGeneration(),task.error(),now()));return null;}
                    if(task.state().equals("RUNNING")){if(task.authority()!=null)task.authority().requireBudget(now());if(v.sessionBatches()>=20)throw fail(WorkflowFailure.Code.EXECUTION_LIMIT);return null;}
                    var c=s.hostCheckpoint(owner,v.workflowId()).orElseThrow();
                    if(v.activeScanId()!=null){if(!c.complete()||!c.scanId().equals(v.activeScanId()))throw fail(WorkflowFailure.Code.CONFLICT);v=v.completed(c,now());s.saveHostSchedule(owner,v);}
                    if(task.authority()!=null)task.authority().requireBudget(now());if(v.sessionBatches()>=20)throw fail(WorkflowFailure.Code.EXECUTION_LIMIT);
                    if(v.nextRunAt()==null||now().isBefore(v.nextRunAt()))return null;
                    // This is the same original grant with its consumed budget, never a renewal.
                    var authority=task.authority();var next=runtime.scheduledControl(s,p,v.workflowId(),v.revision(),v.digest(),v.settings(),task.generation(),true,()->authority,false);
                    s.saveHostSchedule(owner,v.scan(next,s.hostCheckpoint(owner,v.workflowId()).orElseThrow(),now()));
                }catch(RuntimeException failure){String error=failure instanceof WorkflowFailure f&&WorkflowHostSchedule.ERRORS.contains(f.code().name())?f.code().name():"SOURCE_UNAVAILABLE";s.saveHostSchedule(owner,v.state("FAILED",v.generation(),v.taskGeneration(),error,now()));if(task.generation()==v.taskGeneration()&&task.state().equals("RUNNING"))s.saveTask(owner,new WorkflowRuntime.Task(task.workflowId(),task.revision(),task.digest(),task.settings(),task.generation(),"FAILED",task.cursor(),task.cursorId(),now(),error.equals("TASK_CHANGED")?"AUTHORIZATION_REVOKED":error,task.authority()));}
                return null;
            });
        }
    }
    private static WorkflowFailure fail(WorkflowFailure.Code c){return new WorkflowFailure(c);}
}
