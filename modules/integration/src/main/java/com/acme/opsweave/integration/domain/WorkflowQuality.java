package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Bounded journal observations. Unknown measurements stay null; overlapping batches are not summed. */
public final class WorkflowQuality {
    private WorkflowQuality() {}
    public enum Kind { HOST_SCAN, METRIC_STREAM, LOG_STREAM }
    public enum Unit { ENTITY, POINT, LOG_RECORD }
    public static final Set<String> ERRORS;
    static {
        var codes=new HashSet<>(WorkflowLogStream.ERRORS);codes.addAll(WorkflowMetricStream.ERRORS);
        codes.addAll(WorkflowHostSchedule.ERRORS);ERRORS=Set.copyOf(codes);
    }
    public record Reference(String id,int revision,String digest) {
        public Reference {WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);}
        public boolean matches(String id,int revision,String digest){return this.id.equals(id)&&this.revision==revision&&this.digest.equals(digest);}
    }
    public record Task(String state,long generation,Instant updatedAt,String error,UUID pendingBatchId) {
        public Task {Objects.requireNonNull(updatedAt);if(!Set.of("RUNNING","STOPPED","FAILED","ABANDONED").contains(state)||generation<1||generation>1000001||generation==1000001&&!Set.of("STOPPED","ABANDONED").contains(state)||Set.of("FAILED","ABANDONED").contains(state)!=(error!=null))throw new IllegalArgumentException();if(state.equals("ABANDONED")&&(pendingBatchId==null||!Set.of("OUTPUT_UNCONFIRMED","OUTPUT_UNAVAILABLE").contains(error)))throw new IllegalArgumentException();checkError(error);}
    }
    public record Counts(Integer input,Integer accepted,Integer rejected,Integer filtered,Integer deduplicated,
                         Integer outputExpected,Integer confirmed,Integer outputRejected,Integer unknown,Integer pending,
                         Integer repeatedOutput,Integer late) {
        public Counts {
            for(var n:Arrays.asList(input,accepted,rejected,filtered,deduplicated,outputExpected,confirmed,outputRejected,unknown,pending,repeatedOutput,late))if(n!=null&&(n<0||n>1000))throw new IllegalArgumentException();
            if(input==null||confirmed==null||confirmed>input)throw new IllegalArgumentException();
            if(accepted!=null&&(rejected==null||filtered==null||deduplicated==null||accepted+rejected+filtered+deduplicated!=input))throw new IllegalArgumentException();
            if(outputExpected!=null&&(accepted==null||!outputExpected.equals(accepted)||outputRejected==null||unknown==null||pending==null||confirmed+outputRejected+unknown+pending!=outputExpected||repeatedOutput==null||late==null||repeatedOutput>outputExpected||late>outputExpected))throw new IllegalArgumentException();
        }
    }
    public record Batch(UUID id,Instant observedAt,Instant updatedAt,Instant from,Instant till,Unit unit,String state,
                        String coverage,Void sampleRate,Counts counts,String error,UUID reconcilesBatchId) {
        public Batch {
            Objects.requireNonNull(id);Objects.requireNonNull(observedAt);Objects.requireNonNull(updatedAt);Objects.requireNonNull(unit);Objects.requireNonNull(counts);
            if(updatedAt.isBefore(observedAt)||!Set.of("READY","IN_FLIGHT","UNKNOWN","FAILED","CONFIRMED").contains(state)||Set.of("READY","IN_FLIGHT","CONFIRMED").contains(state)!=(error==null)||id.equals(reconcilesBatchId))throw new IllegalArgumentException();checkError(error);
            if(unit==Unit.ENTITY){if(!coverage.equals("PAGE")||from!=null||till!=null||reconcilesBatchId!=null||counts.input()>5||counts.accepted()!=null||counts.rejected()!=null||counts.filtered()!=null||counts.deduplicated()!=null||counts.outputExpected()!=null||counts.outputRejected()!=null||counts.unknown()!=null||counts.pending()!=null||counts.repeatedOutput()!=null||counts.late()!=null)throw new IllegalArgumentException();}
            else {
                if(!coverage.equals("WINDOW")||from==null||till==null||from.getNano()!=0||!till.equals(from.plusSeconds(60))||observedAt.isBefore(till.plusSeconds(10))||counts.accepted()==null||counts.outputExpected()==null||state.equals("READY")||unit==Unit.POINT&&counts.input()>600)throw new IllegalArgumentException();
                int n=counts.outputExpected();
                if(counts.confirmed()!=(state.equals("CONFIRMED")?n:0)||counts.outputRejected()!=(state.equals("FAILED")?n:0)||counts.unknown()!=(state.equals("UNKNOWN")?n:0)||counts.pending()!=(state.equals("IN_FLIGHT")?n:0)||n==0&&!state.equals("CONFIRMED"))throw new IllegalArgumentException();
                if(reconcilesBatchId==null&&(counts.late()!=0||counts.repeatedOutput()!=0)||unit==Unit.LOG_RECORD&&counts.repeatedOutput()!=0)throw new IllegalArgumentException();
            }
        }
    }
    public record Report(String schemaVersion,Instant asOf,Reference reference,Kind kind,Task task,List<Batch> batches,boolean truncated) {
        public Report {
            Objects.requireNonNull(asOf);Objects.requireNonNull(reference);Objects.requireNonNull(kind);batches=List.copyOf(batches);
            if(!schemaVersion.equals("2.0")||batches.size()>20||truncated&&batches.size()!=20||task!=null&&task.updatedAt().isAfter(asOf)||batches.stream().map(Batch::id).distinct().count()!=batches.size())throw new IllegalArgumentException();
            Unit expected=switch(kind){case HOST_SCAN->Unit.ENTITY;case METRIC_STREAM->Unit.POINT;case LOG_STREAM->Unit.LOG_RECORD;};
            for(var b:batches)if(b.unit()!=expected||b.updatedAt().isAfter(asOf))throw new IllegalArgumentException();
        }
    }
    public static Batch log(WorkflowLogStream.Batch b,boolean rejectionKnown){
        boolean uncertain=b.state().equals("FAILED")&&!rejectionKnown;int n=b.indices().size();
        return window(b.id(),b.createdAt(),b.updatedAt(),b.from(),b.till(),Unit.LOG_RECORD,uncertain?"UNKNOWN":b.state(),b.inputCount(),b.filtered(),b.deduplicated(),n,0,b.reconcilesBatchId()==null?0:n,uncertain?"OUTPUT_UNCONFIRMED":b.error(),b.reconcilesBatchId());
    }
    public static Batch metric(WorkflowMetricStream.Batch b){int n=b.timestamps().size();return window(b.id(),b.createdAt(),b.updatedAt(),b.from(),b.till(),Unit.POINT,b.state(),b.inputCount(),b.filtered(),b.collapsed(),n,b.reconcilesBatchId()==null?0:n-b.latePoints(),b.latePoints(),b.error(),b.reconcilesBatchId());}
    public static Batch host(WorkflowHostScan.Batch b){return new Batch(b.id(),b.observedAt(),b.updatedAt(),null,null,Unit.ENTITY,b.state(),"PAGE",null,new Counts(b.records().size(),null,null,null,null,null,b.entityIds().size(),null,null,null,null,null),b.error(),null);}
    private static Batch window(UUID id,Instant created,Instant updated,Instant from,Instant till,Unit unit,String state,int input,int filtered,int dedup,int expected,int repeated,int late,String error,UUID original){
        return new Batch(id,created,updated,from,till,unit,state,"WINDOW",null,new Counts(input,expected,0,filtered,dedup,expected,state.equals("CONFIRMED")?expected:0,state.equals("FAILED")?expected:0,state.equals("UNKNOWN")?expected:0,state.equals("IN_FLIGHT")?expected:0,repeated,late),error,original);
    }
    private static void checkError(String code){if(code!=null&&!ERRORS.contains(code))throw new IllegalArgumentException();}
}
