package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Low-frequency schedule metadata; source pages remain in the scan journal. */
public final class WorkflowHostSchedule {
    private WorkflowHostSchedule() {}
    public enum Operation { START, STOP, RESUME }
    public static final Set<String> ERRORS=Set.of("INVALID_SAMPLE","SOURCE_UNAVAILABLE","FORBIDDEN","MODEL_CHANGED","OPERATOR_CHANGED","OUTPUT_UNAVAILABLE","BACKLOG_LIMIT","RUNTIME_UNAVAILABLE","AUTHORIZATION_EXPIRED","AUTHORIZATION_REVOKED","EXECUTION_LIMIT","TASK_CHANGED");
    public record Command(UUID requestId,String id,int revision,String digest,WorkflowRuntime.Settings settings,int intervalSeconds,long expectedGeneration,Operation operation) {
        public Command { Objects.requireNonNull(requestId);Objects.requireNonNull(operation);Objects.requireNonNull(settings);WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);interval(intervalSeconds);if(expectedGeneration<0||expectedGeneration>1000000)throw new IllegalArgumentException(); }
        public String commandDigest(){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n","host-schedule-v1",operation.name(),id,Integer.toString(revision),digest,settings.identityField(),settings.nameField(),Integer.toString(intervalSeconds),Long.toString(expectedGeneration)).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    }
    public record Schedule(String workflowId,int revision,String digest,WorkflowRuntime.Settings settings,int intervalSeconds,long generation,String state,long taskGeneration,UUID activeScanId,UUID accountedScanId,int completedScans,int confirmedRecords,int sessionBatches,Instant nextRunAt,Instant lastSuccessAt,Instant updatedAt,String error) {
        public Schedule {
            WorkflowDefinition.ref(workflowId,revision);WorkflowDefinition.checkDigest(digest);Objects.requireNonNull(settings);Objects.requireNonNull(updatedAt);interval(intervalSeconds);
            if(!settings.identityField().equals("entity_id")||generation<1||generation>1000001||taskGeneration<1||taskGeneration>1000001||!Set.of("RUNNING","STOPPED","FAILED").contains(state)||generation==1000001&&!state.equals("STOPPED")||completedScans<0||completedScans>200||confirmedRecords<0||confirmedRecords>1000||sessionBatches<0||sessionBatches>20||lastSuccessAt!=null&&lastSuccessAt.isAfter(updatedAt)||state.equals("FAILED")!=(error!=null)||error!=null&&!ERRORS.contains(error)||!state.equals("RUNNING")&&nextRunAt!=null||nextRunAt!=null&&activeScanId!=null)throw new IllegalArgumentException();
            if((completedScans==0)!=(lastSuccessAt==null)||(completedScans==0)!=(accountedScanId==null)||state.equals("RUNNING")&&activeScanId==null&&nextRunAt==null||nextRunAt!=null&&(lastSuccessAt==null||!nextRunAt.equals(lastSuccessAt.plusSeconds(intervalSeconds))))throw new IllegalArgumentException();
        }
        public Schedule state(String value,long newGeneration,long newTaskGeneration,String failure,Instant time){return new Schedule(workflowId,revision,digest,settings,intervalSeconds,newGeneration,value,newTaskGeneration,activeScanId,accountedScanId,completedScans,confirmedRecords,sessionBatches,null,lastSuccessAt,time,failure);}
        public Schedule consume(Instant time){if(sessionBatches>=20)throw new WorkflowFailure(WorkflowFailure.Code.EXECUTION_LIMIT);return new Schedule(workflowId,revision,digest,settings,intervalSeconds,generation,state,taskGeneration,activeScanId,accountedScanId,completedScans,confirmedRecords,sessionBatches+1,nextRunAt,lastSuccessAt,time,error);}
        public Schedule completed(WorkflowHostScan.Checkpoint c,Instant time){if(!c.complete()||!Objects.equals(activeScanId,c.scanId())||c.generation()!=taskGeneration||Objects.equals(accountedScanId,c.scanId()))throw new IllegalArgumentException();return new Schedule(workflowId,revision,digest,settings,intervalSeconds,generation,state,taskGeneration,null,c.scanId(),completedScans+1,confirmedRecords+c.confirmedRecords(),sessionBatches,c.updatedAt().plusSeconds(intervalSeconds),c.updatedAt(),time,null);}
        public Schedule scan(WorkflowRuntime.Task task,WorkflowHostScan.Checkpoint c,Instant time){return new Schedule(workflowId,revision,digest,settings,intervalSeconds,generation,state,task.generation(),c.scanId(),accountedScanId,completedScans,confirmedRecords,sessionBatches,null,lastSuccessAt,time,null);}
    }
    public record Receipt(UUID requestId,Operation operation,String commandDigest,Instant acceptedAt,Schedule schedule) {
        public Receipt {Objects.requireNonNull(requestId);Objects.requireNonNull(operation);WorkflowDefinition.checkDigest(commandDigest);Objects.requireNonNull(acceptedAt);Objects.requireNonNull(schedule);if(!acceptedAt.equals(schedule.updatedAt())||!(operation==Operation.STOP?schedule.state().equals("STOPPED"):schedule.state().equals("RUNNING")))throw new IllegalArgumentException();}
        public void require(Command c){if(!requestId.equals(c.requestId())||operation!=c.operation()||!commandDigest.equals(c.commandDigest())||!schedule.workflowId().equals(c.id())||schedule.revision()!=c.revision()||!schedule.digest().equals(c.digest())||!schedule.settings().equals(c.settings())||schedule.intervalSeconds()!=c.intervalSeconds()||schedule.generation()!=c.expectedGeneration()+1)throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);}
    }
    private static void interval(int value){if(value<60||value>900)throw new IllegalArgumentException();}
}
