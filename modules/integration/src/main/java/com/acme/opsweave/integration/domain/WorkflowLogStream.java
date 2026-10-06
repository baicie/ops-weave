package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Window-level metadata only. Bodies and normalized values never enter this journal. */
public final class WorkflowLogStream {
    private WorkflowLogStream() {}
    public static final int MAX_BATCHES=20, LOOKBACK_SECONDS=60;
    public enum Operation { START, STOP, RESUME }
    public static final Set<String> ERRORS=Set.of("FORBIDDEN","AUTHORIZATION_REVOKED","AUTHORIZATION_EXPIRED","EXECUTION_LIMIT","SOURCE_CHANGED","SOURCE_UNAVAILABLE","INVALID_SAMPLE","WINDOW_INCOMPLETE","SOURCE_WINDOW_CHANGED","CAPACITY","WINDOW_EXPIRED","OUTPUT_REJECTED","OUTPUT_UNCONFIRMED","RUNTIME_UNAVAILABLE","OPERATOR_CHANGED","OPERATOR_PIN_REQUIRED");
    public record Command(UUID requestId,String id,int revision,String digest,long expectedGeneration,Operation operation) {
        public Command {Objects.requireNonNull(requestId);Objects.requireNonNull(operation);WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);if(expectedGeneration<0||expectedGeneration>=1_000_001)throw new IllegalArgumentException();}
        public String commandDigest(){return WorkflowDefinition.hash(List.of("log-stream-control-v1",operation.name(),id,Integer.toString(revision),digest,Long.toString(expectedGeneration)));}
    }
    public record Task(String workflowId,int revision,String digest,long generation,String state,Instant cursor,UUID pendingBatchId,
                       int confirmedWindows,int confirmedRecords,int sessionBatches,Instant updatedAt,String error,WorkflowTaskAuthority authority) {
        public Task {
            WorkflowDefinition.ref(workflowId,revision);WorkflowDefinition.checkDigest(digest);Objects.requireNonNull(cursor);Objects.requireNonNull(updatedAt);
            if(generation<1||generation>1_000_001||!Set.of("RUNNING","STOPPED","FAILED","ABANDONED").contains(state)||cursor.getNano()!=0||cursor.getEpochSecond()<0||cursor.isAfter(updatedAt)
                ||confirmedWindows<0||confirmedWindows>200||confirmedRecords<0||confirmedRecords>confirmedWindows*WorkflowLogWindow.MAX_RECORDS
                ||generation==1_000_001&&!Set.of("STOPPED","ABANDONED").contains(state)||sessionBatches<0||sessionBatches>MAX_BATCHES
                ||authority!=null&&(authority.consumedBatches()!=sessionBatches||authority.issuedAt().isAfter(updatedAt))
                ||state.equals("ABANDONED")&&(!"OUTPUT_UNCONFIRMED".equals(error)||pendingBatchId==null||authority!=null)||Set.of("FAILED","ABANDONED").contains(state)!=(error!=null)||error!=null&&!ERRORS.contains(error))throw new IllegalArgumentException();
        }
        public Task state(long generation,String state,String error,Instant time,int consumed,WorkflowTaskAuthority authority){return new Task(workflowId,revision,digest,generation,state,cursor,pendingBatchId,confirmedWindows,confirmedRecords,consumed,time,error,authority);}
        public Task pending(UUID id,Instant time){if(pendingBatchId!=null)throw new IllegalStateException();return new Task(workflowId,revision,digest,generation,state,cursor,id,confirmedWindows,confirmedRecords,sessionBatches,time,error,authority);}
        public Task confirm(Batch batch,Instant time){
            if(state.equals("ABANDONED"))throw new IllegalStateException("Recovery closed");
            boolean late=batch.reconcilesBatchId()!=null;
            if(!Objects.equals(pendingBatchId,batch.id())||!batch.state().equals("CONFIRMED")||!cursor.equals(late?batch.till():batch.from())
                ||!workflowId.equals(batch.workflowId())||revision!=batch.revision()||!digest.equals(batch.digest()))throw new IllegalStateException();
            return new Task(workflowId,revision,digest,generation,state.equals("FAILED")?"STOPPED":state,late?cursor:batch.till(),null,
                confirmedWindows+(late?0:1),confirmedRecords+batch.indices().size(),sessionBatches,time,state.equals("FAILED")?null:error,authority);
        }
    }
    public record Batch(UUID id,String workflowId,int revision,String digest,Instant from,Instant till,Instant createdAt,Instant updatedAt,String state,
                        int inputCount,int filtered,int deduplicated,List<Integer> indices,List<String> positions,String inputDigest,String batchDigest,String error,UUID reconcilesBatchId) {
        public Batch {
            Objects.requireNonNull(id);WorkflowDefinition.ref(workflowId,revision);for(var hash:List.of(digest,inputDigest,batchDigest))WorkflowDefinition.checkDigest(hash);
            Objects.requireNonNull(from);Objects.requireNonNull(till);Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);indices=List.copyOf(indices);positions=List.copyOf(positions);
            if(from.getNano()!=0||from.getEpochSecond()<0||!till.equals(from.plusSeconds(WorkflowLogWindow.WINDOW_SECONDS))||createdAt.isBefore(till.plusSeconds(WorkflowLogWindow.SETTLE_SECONDS))||updatedAt.isBefore(createdAt)
                ||!Set.of("IN_FLIGHT","CONFIRMED","UNKNOWN","FAILED").contains(state)||inputCount<0||inputCount>WorkflowLogWindow.MAX_RECORDS||filtered<0||deduplicated<0
                ||filtered+deduplicated+indices.size()!=inputCount||positions.size()!=inputCount||reconcilesBatchId==null&&deduplicated!=0||id.equals(reconcilesBatchId))throw new IllegalArgumentException();
            Instant last=null;for(var text:positions){var position=Instant.parse(text);if(!position.toString().equals(text)||position.isBefore(from)||!position.isBefore(till)||last!=null&&!position.isAfter(last))throw new IllegalArgumentException();last=position;}
            int previous=-1;for(var index:indices){if(index<=previous||index>=inputCount)throw new IllegalArgumentException();previous=index;}
            if(indices.isEmpty()&&!state.equals("CONFIRMED")||Set.of("CONFIRMED","IN_FLIGHT").contains(state)&&error!=null
                ||state.equals("UNKNOWN")&&!"OUTPUT_UNCONFIRMED".equals(error)||state.equals("FAILED")&&!"OUTPUT_REJECTED".equals(error))throw new IllegalArgumentException();
        }
        public List<String> acceptedPositions(){return indices.stream().map(positions::get).toList();}
        public Batch finish(String state,String error,Instant time){return new Batch(id,workflowId,revision,digest,from,till,createdAt,time,state,inputCount,filtered,deduplicated,indices,positions,inputDigest,batchDigest,error,reconcilesBatchId);}
        public void requireSuccessor(Batch next){if(Set.of("CONFIRMED","FAILED").contains(state)||state.equals("UNKNOWN")&&!Set.of("UNKNOWN","CONFIRMED").contains(next.state())||next.state().equals("IN_FLIGHT")||next.updatedAt().isBefore(updatedAt)||!finish(next.state(),next.error(),next.updatedAt()).equals(next))throw new IllegalStateException("Invalid log window refinement");}
        public void requireReconciliation(Batch original){
            if(!original.state().equals("CONFIRMED")||!Objects.equals(reconcilesBatchId,original.id())||!workflowId.equals(original.workflowId())||revision!=original.revision()||!digest.equals(original.digest())
                ||!from.equals(original.from())||!till.equals(original.till())||inputCount<=original.inputCount()||!positions.containsAll(original.positions())
                ||deduplicated!=original.inputCount()-original.filtered()||acceptedPositions().stream().anyMatch(original.positions()::contains))throw new IllegalStateException("Invalid log reconciliation reference");
        }
    }
    public record Receipt(UUID requestId,Operation operation,String commandDigest,Instant acceptedAt,Task task) {
        public Receipt {Objects.requireNonNull(requestId);Objects.requireNonNull(operation);WorkflowDefinition.checkDigest(commandDigest);Objects.requireNonNull(task);if(!task.updatedAt().equals(acceptedAt)||!task.state().equals(operation==Operation.STOP?"STOPPED":"RUNNING"))throw new IllegalArgumentException();}
        public void require(Command c){if(!requestId.equals(c.requestId())||operation!=c.operation()||!commandDigest.equals(c.commandDigest())||!task.workflowId().equals(c.id())||task.revision()!=c.revision()||!task.digest().equals(c.digest())||task.generation()!=c.expectedGeneration()+1)throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);}
    }
}
