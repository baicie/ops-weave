package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Bounded asset inputs are private recovery data, separate from public runtime metadata. */
public final class WorkflowHostScan {
    private WorkflowHostScan() {}
    public record Page(List<Map<String,Object>> records, String nextCursor, boolean complete, Instant observedAt) {
        public Page {
            records=WorkflowHostScan.records(records); Objects.requireNonNull(observedAt); cursor(nextCursor);
            if(complete!=(nextCursor==null) || records.isEmpty()&&!complete) throw new IllegalArgumentException();
        }
    }
    public record Checkpoint(String workflowId, int revision, String digest, long generation, UUID scanId,
                             String nextCursor, UUID pendingBatchId, int confirmedBatches, int confirmedRecords,
                             boolean complete, Instant updatedAt) {
        public Checkpoint {
            WorkflowDefinition.ref(workflowId,revision); WorkflowDefinition.checkDigest(digest);
            Objects.requireNonNull(scanId); Objects.requireNonNull(updatedAt); cursor(nextCursor);
            if(generation<1 || generation>1000001 || confirmedBatches<0 || confirmedBatches>200 || confirmedRecords<0 || confirmedRecords>1000
                || complete&&(nextCursor!=null||pendingBatchId!=null) || confirmedRecords>confirmedBatches*5) throw new IllegalArgumentException();
        }
        public Checkpoint generation(long value,Instant time) {return new Checkpoint(workflowId,revision,digest,value,scanId,nextCursor,pendingBatchId,confirmedBatches,confirmedRecords,complete,time);}
        public Checkpoint pending(UUID id,Instant time) {return new Checkpoint(workflowId,revision,digest,generation,scanId,nextCursor,id,confirmedBatches,confirmedRecords,false,time);}
        public Checkpoint confirm(Batch batch,Instant time) {
            if(!Objects.equals(pendingBatchId,batch.id()) || !scanId.equals(batch.scanId()) || !Objects.equals(nextCursor,batch.beforeCursor()) || !batch.state().equals("CONFIRMED"))throw new IllegalArgumentException();
            return new Checkpoint(workflowId,revision,digest,generation,scanId,batch.nextCursor(),null,confirmedBatches+1,confirmedRecords+batch.records().size(),batch.complete(),time);
        }
    }
    public record Batch(UUID id, UUID scanId, String workflowId, int revision, String digest,
                        WorkflowRuntime.Settings settings, int sequence, String beforeCursor, String nextCursor,
                        boolean complete, Instant observedAt, List<Map<String,Object>> records,
                        String state, List<String> entityIds, String error, Instant updatedAt) {
        public Batch {
            Objects.requireNonNull(id); Objects.requireNonNull(scanId); WorkflowDefinition.ref(workflowId,revision); WorkflowDefinition.checkDigest(digest);
            Objects.requireNonNull(settings); Objects.requireNonNull(observedAt); Objects.requireNonNull(updatedAt); records=WorkflowHostScan.records(records); entityIds=List.copyOf(entityIds);
            cursor(beforeCursor); cursor(nextCursor);
            if(sequence<1 || sequence>200 || complete!=(nextCursor==null) || !complete&&Objects.equals(beforeCursor,nextCursor) || records.isEmpty()&&!complete
                || !Set.of("READY","IN_FLIGHT","UNKNOWN","FAILED","CONFIRMED").contains(state) || entityIds.size()>records.size()
                || Set.of("READY","IN_FLIGHT","CONFIRMED").contains(state)!=(error==null) || updatedAt.isBefore(observedAt))throw new IllegalArgumentException();
            if(beforeCursor!=null){
                var before=beforeCursor.split("\\|");int remaining=Integer.parseInt(before[2])-Integer.parseInt(before[3]);
                if(complete){if(records.size()!=remaining)throw new IllegalArgumentException();}
                else{var after=nextCursor.split("\\|");if(!before[1].equals(after[1])||!before[2].equals(after[2])||Integer.parseInt(after[3])-Integer.parseInt(before[3])!=records.size())throw new IllegalArgumentException();}
            }else if(!complete&&Integer.parseInt(nextCursor.split("\\|")[3])!=records.size())throw new IllegalArgumentException();
            if(!complete&&records.size()!=5)throw new IllegalArgumentException();
            if(new HashSet<>(entityIds).size()!=entityIds.size())throw new IllegalArgumentException();
            for(var idValue:entityIds)UUID.fromString(idValue);
            if(error!=null&&!Set.of("OUTPUT_UNAVAILABLE","INVALID_SAMPLE","SOURCE_UNAVAILABLE","FORBIDDEN","MODEL_CHANGED","OPERATOR_CHANGED","AUTHORIZATION_EXPIRED","AUTHORIZATION_REVOKED","EXECUTION_LIMIT","BACKLOG_LIMIT","RUNTIME_UNAVAILABLE").contains(error))throw new IllegalArgumentException();
        }
        public Batch state(String next,List<String> ids,String code,Instant time) {var b=new Batch(id,scanId,workflowId,revision,digest,settings,sequence,beforeCursor,nextCursor,complete,observedAt,records,next,ids,code,time);requireSuccessor(b);return b;}
        public void requireSuccessor(Batch b) {
            if(!id.equals(b.id)||!scanId.equals(b.scanId)||!workflowId.equals(b.workflowId)||revision!=b.revision||!digest.equals(b.digest)||!settings.equals(b.settings)
                ||sequence!=b.sequence||!Objects.equals(beforeCursor,b.beforeCursor)||!Objects.equals(nextCursor,b.nextCursor)||complete!=b.complete||!observedAt.equals(b.observedAt)
                ||!records.equals(b.records)||b.updatedAt.isBefore(updatedAt)||!b.entityIds.containsAll(entityIds))throw new IllegalArgumentException();
            boolean allowed=switch(state){case "READY" -> b.state.equals("IN_FLIGHT"); case "IN_FLIGHT" -> Set.of("CONFIRMED","FAILED","UNKNOWN").contains(b.state); case "FAILED","UNKNOWN" -> b.state.equals("READY");default -> false;};
            if(!allowed)throw new IllegalArgumentException();
        }
    }
    private static List<Map<String,Object>> records(List<Map<String,Object>> rows) {
        if(rows==null||rows.size()>5)throw new IllegalArgumentException();
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows){
            if(!row.keySet().equals(Set.of("name","ip","lifecycle","entity_id")))throw new IllegalArgumentException();
            for(var value:row.values())if(!(value instanceof String text)||text.length()>255)throw new IllegalArgumentException();
            if(((String)row.get("name")).isBlank()||!Set.of("DISCOVERED","ACTIVE","INACTIVE","DELETED","ARCHIVED").contains(row.get("lifecycle")))throw new IllegalArgumentException();
            UUID.fromString((String)row.get("entity_id")); result.add(Map.copyOf(row));
        }
        if(result.stream().map(r->r.get("entity_id")).distinct().count()!=result.size())throw new IllegalArgumentException();
        return List.copyOf(result);
    }
    private static void cursor(String cursor) {if(cursor!=null&&!cursor.matches("ids-v1\\|[a-f0-9]{64}\\|[1-9][0-9]{0,3}\\|[1-9][0-9]{0,3}"))throw new IllegalArgumentException();if(cursor!=null){var p=cursor.split("\\|");int n=Integer.parseInt(p[2]),i=Integer.parseInt(p[3]);if(n>1000||i>=n)throw new IllegalArgumentException();}}
}
