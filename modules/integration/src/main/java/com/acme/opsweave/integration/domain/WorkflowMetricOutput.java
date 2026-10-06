package com.acme.opsweave.integration.domain;

import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.time.Instant;
import java.util.*;

/** Low-volume batch proofs only. Values stay in the time-series sink, never in this receipt. */
public final class WorkflowMetricOutput {
    private WorkflowMetricOutput() {}
    public record Command(UUID requestId,String id,int revision,String digest,UUID previewId,List<Map<String,Object>> samples) {
        public Command {
            Objects.requireNonNull(requestId);Objects.requireNonNull(previewId);
            WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);
            samples=samples.stream().map(Map::copyOf).toList();
            if(samples.isEmpty()||samples.size()>5)throw new IllegalArgumentException();
            for(var row:samples) {
                if(!row.keySet().equals(Set.of("timestamp","sourceKey","value")))throw new IllegalArgumentException();
                for(var entry:row.entrySet())if(!(entry.getValue() instanceof String text)||text.length()>(entry.getKey().equals("sourceKey")?2048:entry.getKey().equals("timestamp")?80:64))throw new IllegalArgumentException();
            }
        }
        public String commandDigest(){return WorkflowDefinition.hash(List.of("workflow-metric-output-v1",id,Integer.toString(revision),digest,previewId.toString(),WorkflowServiceInput.digest(samples)));}
    }
    // Keep domain canonicalization identical to the general scalar workflow input contract.
    public static final class WorkflowServiceInput {
        private WorkflowServiceInput() {}
        public static String digest(List<Map<String,Object>> rows) {
            var parts=new ArrayList<String>();parts.add("MANUAL_SAMPLE");
            for(var row:rows){parts.add("record");row.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{parts.add(e.getKey());parts.add("text");parts.add(e.getValue().toString());});}
            return WorkflowDefinition.hash(parts);
        }
    }
    public record Receipt(UUID requestId,String workflowId,int revision,String digest,UUID previewId,String commandDigest,
                          Instant createdAt,Instant updatedAt,String state,int accepted,int filtered,int collapsed,
                          int confirmed,int failed,int unknown,String error,Map<String,String> labels,String seriesHash,
                          String batchDigest,List<Long> timestamps) {
        public Receipt {
            Objects.requireNonNull(requestId);Objects.requireNonNull(previewId);Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);
            WorkflowDefinition.ref(workflowId,revision);WorkflowDefinition.checkDigest(digest);WorkflowDefinition.checkDigest(commandDigest);WorkflowDefinition.checkDigest(batchDigest);
            labels=Map.copyOf(labels);timestamps=List.copyOf(timestamps);
            var batch=new MetricWriteBatch(labels,List.of(),0);
            var required=Set.of("tenant_id","owner_scope","source_instance_id","external_item_id","host_external_id","metric_key","unit","mapping_id","mapping_revision","mapping_digest","workflow_id","workflow_revision","workflow_digest","configuration_digest","data_mode");
            if(!labels.keySet().containsAll(required)||labels.keySet().stream().anyMatch(key->!required.contains(key)&&!key.matches("dim_[A-Za-z_][A-Za-z0-9_]{0,59}"))
                ||!workflowId.equals(labels.get("workflow_id"))||!Integer.toString(revision).equals(labels.get("workflow_revision"))||!digest.equals(labels.get("workflow_digest"))
                ||!labels.get("owner_scope").matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            if(!batch.seriesHash().equals(seriesHash)||!"zabbix-jsonrpc".equals(labels.get("data_mode"))
                ||!Set.of("PENDING","CONFIRMED","UNKNOWN","FAILED").contains(state)||updatedAt.isBefore(createdAt)
                ||accepted<1||accepted>5||filtered<0||accepted+filtered>5||timestamps.isEmpty()||timestamps.size()>5
                ||collapsed!=accepted-timestamps.size()||confirmed<0||failed<0||unknown<0||confirmed+failed+unknown!=timestamps.size())throw new IllegalArgumentException();
            long previous=-1;for(var time:timestamps){if(time<=previous||time>createdAt.toEpochMilli())throw new IllegalArgumentException();previous=time;}
            if(state.equals("CONFIRMED")) {if(confirmed!=timestamps.size()||failed!=0||unknown!=0||error!=null)throw new IllegalArgumentException();}
            else if(state.equals("FAILED")) {if(confirmed!=0||failed!=timestamps.size()||unknown!=0||error==null||!Set.of("OUTPUT_REJECTED","FORBIDDEN","SOURCE_CHANGED","MAPPING_CHANGED","RUNTIME_UNAVAILABLE").contains(error))throw new IllegalArgumentException();}
            else {if(confirmed!=0||failed!=0||unknown!=timestamps.size()||(state.equals("UNKNOWN")?!"OUTPUT_UNCONFIRMED".equals(error):error!=null))throw new IllegalArgumentException();}
        }
        public void require(Command command){if(!requestId.equals(command.requestId())||!workflowId.equals(command.id())||revision!=command.revision()||!digest.equals(command.digest())||!previewId.equals(command.previewId())||!commandDigest.equals(command.commandDigest()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);}
        public void requireSuccessor(Receipt next){if(Set.of("CONFIRMED","FAILED").contains(state)||state.equals("UNKNOWN")&&next.state().equals("FAILED")||next.updatedAt().isBefore(updatedAt)||!finish(next.state(),next.error(),next.updatedAt()).equals(next))throw new IllegalStateException("Invalid output receipt refinement");}
        public Receipt finish(String state,String error,Instant time){return new Receipt(requestId,workflowId,revision,digest,previewId,commandDigest,createdAt,time,state,accepted,filtered,collapsed,state.equals("CONFIRMED")?timestamps.size():0,state.equals("FAILED")?timestamps.size():0,Set.of("PENDING","UNKNOWN").contains(state)?timestamps.size():0,error,labels,seriesHash,batchDigest,timestamps);}
    }
    public static String batchDigest(MetricWriteBatch batch) {
        var parts=new ArrayList<String>();parts.add("workflow-metric-batch-v1");parts.add(batch.seriesHash());
        for(var sample:batch.samples()){parts.add(Long.toString(sample.timestampMillis()));parts.add(sample.value().stripTrailingZeros().toPlainString());}
        return WorkflowDefinition.hash(parts);
    }
}
