package com.acme.opsweave.integration.domain;

import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.time.Instant;
import java.util.*;

/** Closed one-minute windows and bounded metadata journals. Point values are never persisted here. */
public final class WorkflowMetricStream {
    private WorkflowMetricStream() {}
    public static final int WINDOW_SECONDS=60, SETTLE_SECONDS=10, MAX_BATCHES=20, MAX_POINTS=600;
    public static final int MAX_HISTORY_REQUESTS=20, LOOKBACK_SECONDS=60;
    public enum Operation { START, STOP, RESUME }
    public record Command(UUID requestId,String id,int revision,String digest,long expectedGeneration,Operation operation) {
        public Command {Objects.requireNonNull(requestId);Objects.requireNonNull(operation);WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);if(expectedGeneration<0||expectedGeneration>=1_000_001)throw new IllegalArgumentException();}
        public String commandDigest(){return WorkflowDefinition.hash(List.of("metric-stream-control-v1",operation.name(),id,Integer.toString(revision),digest,Long.toString(expectedGeneration)));}
    }
    public record Task(String workflowId,int revision,String digest,long generation,String state,Instant cursor,UUID pendingBatchId,
                       int confirmedWindows,int confirmedPoints,int sessionBatches,Instant updatedAt,String error,WorkflowTaskAuthority authority) {
        public Task {
            WorkflowDefinition.ref(workflowId,revision);WorkflowDefinition.checkDigest(digest);Objects.requireNonNull(cursor);Objects.requireNonNull(updatedAt);
            if(generation<1||generation>1_000_001||!Set.of("RUNNING","STOPPED","FAILED","ABANDONED").contains(state)||cursor.getNano()!=0||cursor.getEpochSecond()<0
                ||cursor.isAfter(updatedAt)||confirmedWindows<0||confirmedWindows>200||confirmedPoints<0||confirmedPoints>confirmedWindows*MAX_POINTS
                ||generation==1_000_001&&!Set.of("STOPPED","ABANDONED").contains(state)||authority!=null&&(authority.consumedBatches()!=sessionBatches||authority.issuedAt().isAfter(updatedAt))||sessionBatches<0||sessionBatches>MAX_BATCHES||state.equals("ABANDONED")&&(!"OUTPUT_UNCONFIRMED".equals(error)||pendingBatchId==null||authority!=null)||Set.of("FAILED","ABANDONED").contains(state)!=(error!=null)||error!=null&&!ERRORS.contains(error))throw new IllegalArgumentException();
        }
        public Task state(long generation,String state,String error,Instant time,int consumed,WorkflowTaskAuthority authority){return new Task(workflowId,revision,digest,generation,state,cursor,pendingBatchId,confirmedWindows,confirmedPoints,consumed,time,error,authority);}
        public Task pending(UUID id,Instant time){if(pendingBatchId!=null)throw new IllegalStateException();return new Task(workflowId,revision,digest,generation,state,cursor,id,confirmedWindows,confirmedPoints,sessionBatches,time,error,authority);}
        public Task confirm(Batch batch,Instant time){
            if(state.equals("ABANDONED"))throw new IllegalStateException("Recovery closed");
            boolean late=batch.reconcilesBatchId()!=null;
            if(!Objects.equals(pendingBatchId,batch.id())||!batch.state().equals("CONFIRMED")||!cursor.equals(late?batch.till():batch.from())||!workflowId.equals(batch.workflowId())||revision!=batch.revision()||!digest.equals(batch.digest()))throw new IllegalStateException();
            return new Task(workflowId,revision,digest,generation,state.equals("FAILED")?"STOPPED":state,late?cursor:batch.till(),null,confirmedWindows+(late?0:1),confirmedPoints+(late?batch.latePoints():batch.timestamps().size()),sessionBatches,time,state.equals("FAILED")?null:error,authority);
        }
    }
    public static final Set<String> ERRORS=Set.of("FORBIDDEN","AUTHORIZATION_REVOKED","AUTHORIZATION_EXPIRED","EXECUTION_LIMIT","SOURCE_CHANGED","MAPPING_CHANGED","SOURCE_UNAVAILABLE","INVALID_SAMPLE","WINDOW_INCOMPLETE","SOURCE_WINDOW_CHANGED","CAPACITY","WINDOW_EXPIRED","OUTPUT_REJECTED","OUTPUT_UNCONFIRMED","RUNTIME_UNAVAILABLE");
    public record Batch(UUID id,String workflowId,int revision,String digest,Instant from,Instant till,Instant createdAt,Instant updatedAt,
                        String state,int inputCount,int filtered,int collapsed,Map<String,String> labels,String batchDigest,List<Long> timestamps,String error,UUID reconcilesBatchId,int latePoints) {
        /** Legacy proofs remain readable; their bytes and meaning are not rewritten. */
        public Batch(UUID id,String workflowId,int revision,String digest,Instant from,Instant till,Instant createdAt,Instant updatedAt,String state,int inputCount,int filtered,int collapsed,Map<String,String> labels,String batchDigest,List<Long> timestamps,String error){this(id,workflowId,revision,digest,from,till,createdAt,updatedAt,state,inputCount,filtered,collapsed,labels,batchDigest,timestamps,error,null,0);}
        public Batch {
            Objects.requireNonNull(id);WorkflowDefinition.ref(workflowId,revision);WorkflowDefinition.checkDigest(digest);WorkflowDefinition.checkDigest(batchDigest);
            Objects.requireNonNull(from);Objects.requireNonNull(till);Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);labels=Map.copyOf(labels);timestamps=List.copyOf(timestamps);
            var series=new MetricWriteBatch(labels,List.of(),0);
            if(from.getNano()!=0||from.getEpochSecond()<0||!till.equals(from.plusSeconds(WINDOW_SECONDS))||createdAt.isBefore(till.plusSeconds(SETTLE_SECONDS))||updatedAt.isBefore(createdAt)
                ||!Set.of("IN_FLIGHT","CONFIRMED","UNKNOWN","FAILED").contains(state)||inputCount<0||inputCount>MAX_POINTS||filtered<0||collapsed<0||filtered+collapsed+timestamps.size()!=inputCount
                ||!workflowId.equals(labels.get("workflow_id"))||!Integer.toString(revision).equals(labels.get("workflow_revision"))||!digest.equals(labels.get("workflow_digest"))
                ||!"zabbix-jsonrpc".equals(labels.get("data_mode"))||!"WINDOW_60S".equals(labels.get("collection_mode"))
                ||reconcilesBatchId==null&&latePoints!=0||reconcilesBatchId!=null&&(reconcilesBatchId.equals(id)||latePoints<1||latePoints>timestamps.size()))throw new IllegalArgumentException();
            var required=Set.of("tenant_id","owner_scope","source_instance_id","external_item_id","host_external_id","metric_key","unit","mapping_id","mapping_revision","mapping_digest","workflow_id","workflow_revision","workflow_digest","configuration_digest","data_mode","collection_mode");
            if(!labels.keySet().containsAll(required)||!labels.get("owner_scope").matches("[a-f0-9]{64}")||labels.keySet().stream().anyMatch(k->!required.contains(k)&&!k.matches("dim_[A-Za-z_][A-Za-z0-9_]{0,59}")))throw new IllegalArgumentException();
            long previous=-1;for(var stamp:timestamps){if(stamp<=previous||stamp<from.toEpochMilli()||stamp>=till.toEpochMilli())throw new IllegalArgumentException();previous=stamp;}
            if(timestamps.isEmpty()&&(!state.equals("CONFIRMED")||!WorkflowMetricOutput.batchDigest(series).equals(batchDigest))
                ||Set.of("CONFIRMED","IN_FLIGHT").contains(state)&&error!=null
                ||state.equals("UNKNOWN")&&!"OUTPUT_UNCONFIRMED".equals(error)
                ||state.equals("FAILED")&&(!"OUTPUT_REJECTED".equals(error)))throw new IllegalArgumentException();
        }
        public Batch finish(String state,String error,Instant time){return new Batch(id,workflowId,revision,digest,from,till,createdAt,time,state,inputCount,filtered,collapsed,labels,batchDigest,timestamps,error,reconcilesBatchId,latePoints);}
        public void requireReconciliation(Batch original){
            if(!original.state().equals("CONFIRMED")||!Objects.equals(reconcilesBatchId,original.id())||!workflowId.equals(original.workflowId())||revision!=original.revision()||!digest.equals(original.digest())||!from.equals(original.from())||!till.equals(original.till())||!labels.equals(original.labels())||!timestamps.containsAll(original.timestamps())||latePoints!=timestamps.size()-original.timestamps().size())throw new IllegalStateException("Invalid metric reconciliation reference");
        }
        public void requireSuccessor(Batch next){if(Set.of("CONFIRMED","FAILED").contains(state)||state.equals("UNKNOWN")&&!Set.of("UNKNOWN","CONFIRMED").contains(next.state())||next.state().equals("IN_FLIGHT")||next.updatedAt().isBefore(updatedAt)||!finish(next.state(),next.error(),next.updatedAt()).equals(next))throw new IllegalStateException("Invalid metric batch refinement");}
    }
    public record Receipt(UUID requestId,Operation operation,String commandDigest,Instant acceptedAt,Task task) {
        public Receipt {Objects.requireNonNull(requestId);Objects.requireNonNull(operation);WorkflowDefinition.checkDigest(commandDigest);Objects.requireNonNull(task);if(!task.updatedAt().equals(acceptedAt)||!task.state().equals(operation==Operation.STOP?"STOPPED":"RUNNING"))throw new IllegalArgumentException();}
        public void require(Command command){if(!requestId.equals(command.requestId())||operation!=command.operation()||!commandDigest.equals(command.commandDigest())||!task.workflowId().equals(command.id())||task.revision()!=command.revision()||!task.digest().equals(command.digest())||task.generation()!=command.expectedGeneration()+1)throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);}
    }
}
