package com.acme.opsweave.integration.application;

import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import java.time.Instant;

/** Version replacement has no source, output or authority issuing port. Caller owns the transaction. */
final class WorkflowTaskVersionService {
    private WorkflowTaskVersionService() {}
    static boolean replaceable(WorkflowStore.Session s, String owner, WorkflowQuality.Reference ref,
                               WorkflowQuality.Kind kind, WorkflowQuality.Task task, int revision) {
        if (revision <= ref.revision() || task.state().equals("RUNNING")) return false;
        if (task.state().equals("ABANDONED")) {
            var closure = s.recoveryForVersion(owner, ref.id(), ref.revision(), ref.digest()).orElseThrow();
            if (closure.kind() != kind || closure.generation() != task.generation()
                    || !closure.batchId().equals(task.pendingBatchId())) throw new IllegalStateException("Invalid closed task");
            boolean unknown=switch(kind){
                case METRIC_STREAM -> s.metricStreamBatch(owner,task.pendingBatchId()).map(b->{require(ref,b.workflowId(),b.revision(),b.digest());return b.state().equals("UNKNOWN");}).orElseThrow();
                case LOG_STREAM -> s.logStreamBatch(owner,task.pendingBatchId()).map(b->{require(ref,b.workflowId(),b.revision(),b.digest());return WorkflowLogOutcome.uncertain(s,owner,b);}).orElseThrow();
                case HOST_SCAN -> s.hostBatch(owner,task.pendingBatchId()).map(b->{require(ref,b.workflowId(),b.revision(),b.digest());return b.state().equals("UNKNOWN");}).orElseThrow();
            };
            if(!unknown)throw new IllegalStateException("Invalid closed output parent");
            return true;
        }
        if (task.pendingBatchId() == null) return true;
        return switch (kind) {
            case METRIC_STREAM -> s.metricStreamBatch(owner, task.pendingBatchId()).map(b -> {
                require(ref,b.workflowId(),b.revision(),b.digest()); return b.state().equals("FAILED");
            }).orElseThrow();
            case LOG_STREAM -> s.logStreamBatch(owner, task.pendingBatchId()).map(b -> {
                require(ref,b.workflowId(),b.revision(),b.digest()); return b.state().equals("FAILED") && s.logStreamRejectionKnown(owner,b.id());
            }).orElseThrow();
            case HOST_SCAN -> s.hostBatch(owner, task.pendingBatchId()).map(b -> {
                require(ref,b.workflowId(),b.revision(),b.digest()); return b.state().equals("FAILED") && b.entityIds().isEmpty();
            }).orElseThrow();
        };
    }
    static void archive(WorkflowStore.Session s,String owner,WorkflowQuality.Reference ref,
                        WorkflowQuality.Kind kind,WorkflowQuality.Task task,WorkflowQuality.Reference next,Instant time) {
        if (!replaceable(s,owner,ref,kind,task,next.revision())) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        if (s.taskArchiveCount(owner) >= 200) throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
        var schedule=kind==WorkflowQuality.Kind.HOST_SCAN?s.hostSchedule(owner,ref.id())
                .filter(v->ref.matches(v.workflowId(),v.revision(),v.digest()))
                .map(v->new WorkflowQuality.Task(v.state(),v.generation(),v.updatedAt(),v.error(),task.pendingBatchId())).orElse(null):null;
        s.addTaskArchive(owner,new WorkflowTaskArchive("2.0",ref,kind,task,schedule,next,task.generation()+1,time));
    }
    static void archiveParent(WorkflowStore.Session s,String owner,WorkflowTaskArchive a){
        var t=a.task();if(t.pendingBatchId()==null)return;
        if(t.state().equals("ABANDONED")){if(!replaceable(s,owner,a.reference(),a.kind(),t,a.replacedBy().revision()))throw new IllegalStateException("Invalid closed archive");return;}
        // Historical metadata is readable even when legacy certainty cannot authorize another write.
        boolean failed=switch(a.kind()){
            case METRIC_STREAM -> s.metricStreamBatch(owner,t.pendingBatchId()).map(b->{require(a.reference(),b.workflowId(),b.revision(),b.digest());return b.state().equals("FAILED");}).orElseThrow();
            case LOG_STREAM -> s.logStreamBatch(owner,t.pendingBatchId()).map(b->{require(a.reference(),b.workflowId(),b.revision(),b.digest());return b.state().equals("FAILED");}).orElseThrow();
            case HOST_SCAN -> s.hostBatch(owner,t.pendingBatchId()).map(b->{require(a.reference(),b.workflowId(),b.revision(),b.digest());return b.state().equals("FAILED")&&b.entityIds().isEmpty();}).orElseThrow();
        };
        if(!failed)throw new IllegalStateException("Invalid archived batch parent");
    }
    static void require(WorkflowQuality.Reference ref,String id,int revision,String digest) {
        if (!ref.matches(id,revision,digest)) throw new IllegalStateException("Invalid task version parent");
    }
}
